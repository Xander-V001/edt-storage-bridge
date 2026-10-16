package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Non-mutating selective transport for changes from a SINGLE pre-existing
 * classical-repository-connected IB. Never accesses EDT-connected infobases.
 *
 * <p>Caller MUST supply precisely the changed objects, obtained from
 * trustworthy repository history. No full configuration fallback is allowed.
 */
public final class SelectiveDumpRunner {

    public ExportResult export(SelectiveDumpRequest request, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("1C executable not found");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Export cancelled");
        }
        Path temporaryRoot = Files.createTempDirectory("edt-storage-selective-");
        boolean succeeded = false;
        try {
            Path target = Files.createDirectory(temporaryRoot.resolve("objects"));
            Path list = temporaryRoot.resolve("objects.txt");
            Path log = temporaryRoot.resolve("designer.txt");
            Path output = temporaryRoot.resolve("stdout.txt");
            // UTF-8 BOM and CRLF are supported for 1C metadata object list files.
            String lines = String.join("\r\n", request.objectNames()) + "\r\n";
            byte[] content = lines.getBytes(StandardCharsets.UTF_8);
            byte[] withBom = new byte[content.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(content, 0, withBom, 3, content.length);
            Files.write(list, withBom);
            Process process = new ProcessBuilder(
                    SelectiveDumpCommand.arguments(request, target, list, log))
                .directory(temporaryRoot.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
                .start();
            try {
                long startedAt = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Selective export cancelled");
                    }
                    if (System.nanoTime() - startedAt > request.timeout().toNanos()) {
                        throw new IOException("Selective export timed out");
                    }
                    if (tooLarge(log) || tooLarge(output)) {
                        throw new IOException("Diagnostics exceeded size limit");
                    }
                }
                if (process.exitValue() != 0 || cancelled.getAsBoolean()) {
                    throw new IOException("Selective Designer dump did not finish successfully");
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
            try (Stream<Path> paths = Files.walk(target)) {
                if (paths.noneMatch(p -> Files.isRegularFile(p))) {
                    throw new IOException("Designer did not export any metadata files");
                }
            }
            succeeded = true;
            return new ExportResult(temporaryRoot, target);
        } finally {
            if (!succeeded) {
                deleteTree(temporaryRoot);
            }
        }
    }

    private static boolean tooLarge(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) > 16L * 1024 * 1024;
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Staged export: caller must convert/compare it against the EDT project.
     * Closing deletes the private export. No transfer to EDT occurs implicitly.
     */
    public record ExportResult(Path workingRoot, Path xmlDirectory)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(workingRoot);
        }
    }
}
