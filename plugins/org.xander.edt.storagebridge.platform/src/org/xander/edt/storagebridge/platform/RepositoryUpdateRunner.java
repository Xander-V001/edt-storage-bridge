package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Run one user-approved repository receive into the configured connected
 * infobase. This is mutating: NEVER invoke without explicit confirmation.
 *
 * <p>No shell, secrets or arbitrary switches. A zero process exit status
 * proves only Designer process completion, not repository revision parity.
 * Run cannot be automatically retried after an uncertain result.
 */
public final class RepositoryUpdateRunner {
    private static final long POLL_MILLIS = 200L;
    private static final long LOG_MAX_BYTES = 16L * 1024 * 1024;

    public void receive(RepositoryUpdateRequest request, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Указанный исполняемый файл 1С не найден.");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Получение отменено до запуска.");
        }
        Path directory = Files.createTempDirectory("edt-storage-update-");
        try {
            try {
                Files.setPosixFilePermissions(directory,
                    PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows uses filesystem ACLs.
            }
            Path log = directory.resolve("designer-log.txt");
            Path stdout = directory.resolve("stdout.txt");
            Path stderr = directory.resolve("stderr.txt");
            Process process = new ProcessBuilder(
                RepositoryUpdateCommand.arguments(request, log))
                .directory(directory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
            try {
                long started = System.nanoTime();
                while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Получение прервано. Состояние ИБ требует проверки.");
                    }
                    if (System.nanoTime() - started > request.timeout().toNanos()) {
                        throw new IOException("Истекло время выполнения. Состояние ИБ требует проверки.");
                    }
                    if (tooLarge(log, stdout, stderr)) {
                        throw new IOException("Размер служебного вывода превышен.");
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Операция была отменена. Состояние ИБ требует проверки.");
                }
                int exit = process.exitValue();
                if (exit != 0) {
                    throw new IOException("Конфигуратор завершился с кодом " + exit
                        + ". Состояние ИБ требует проверки.");
                }
                if (tooLarge(log, stdout, stderr)) {
                    throw new IOException("Служебный вывод Конфигуратора слишком велик.");
                }
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        process.waitFor(1, TimeUnit.SECONDS);
                    }
                }
            }
        } finally {
            deleteQuietly(directory.resolve("designer-log.txt"));
            deleteQuietly(directory.resolve("stdout.txt"));
            deleteQuietly(directory.resolve("stderr.txt"));
            deleteQuietly(directory);
        }
    }

    private static boolean tooLarge(Path... logs) throws IOException {
        for (Path path : logs) {
            if (Files.exists(path) && Files.size(path) > LOG_MAX_BYTES) {
                return true;
            }
        }
        return false;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Do not hide an operational failure.
        }
    }
}
