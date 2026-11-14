package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs a single, explicitly scoped Designer stage with no shell and a durable
 * stage marker written BEFORE spawning any mutating native process.
 * Logs are local, bounded and redacted; argv/credentials are never serialized.
 */
public final class OutgoingDesignerRunner {
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private OutgoingDesignerRunner() {}

    public static void run(OutgoingDesignerCommands.Connection connection,
            OutgoingDesignerCommands.Stage stage, Path xmlOwners,
            Path exportRoot, Path listFile, String comment, Duration timeout,
            OutgoingOperationJournal journal, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofHours(2)) > 0)
            throw new IllegalArgumentException("Invalid native stage timeout");
        if (!Files.isRegularFile(connection.executable(),
                LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(xmlOwners, LinkOption.NOFOLLOW_LINKS)
                || (stage == OutgoingDesignerCommands.Stage.LOAD
                    && (!Files.isRegularFile(listFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(exportRoot, LinkOption.NOFOLLOW_LINKS))))
            throw new IOException("Required native executable or scope missing");
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        String stageName = stage.name();
        Path log = journal.directory().resolve(stageName.toLowerCase() + "-designer.log");
        Path stdout = journal.directory().resolve(stageName.toLowerCase() + "-stdout.tmp");
        List<String> args = OutgoingDesignerCommands.arguments(connection,
            stage, xmlOwners, exportRoot, listFile, log, comment);
        journal.before(stageName);
        Process process = null;
        try {
            process = new ProcessBuilder(args)
                .directory(journal.directory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(stdout.toFile()).start();
            journal.event("PROCESS_STARTED", "stage=" + stageName);
            long start = System.nanoTime();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Native stage cancelled; result uncertain");
                }
                if (System.nanoTime() - start > timeout.toNanos()) {
                    throw new IOException("Native stage timed out; result uncertain");
                }
                if (oversize(log) || oversize(stdout)) {
                    throw new IOException("Native diagnostic output exceeded limit");
                }
            }
            if (cancelled.getAsBoolean())
                throw new IOException("Cancelled after native stage execution");
            journal.after(stageName, process.exitValue());
            // A zero exit is NOT proof of expected 1C state: caller must
            // re-read native ConfigDumpInfo, verify files and repository HEAD.
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            // Sanitize the native stdout and /Out messages; those may contain
            // credentials because 1C is not guaranteed to redact messages.
            for (Path file : List.of(stdout, log)) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        String text = readBounded(file);
                        text = redact(text, connection);
                        Path safe = file.resolveSibling(file.getFileName()
                            + ".redacted.txt");
                        Files.writeString(safe, text, StandardCharsets.UTF_8);
                        journal.event("NATIVE_DIAGNOSTIC",
                            "stage=" + stageName + " file=" + safe.getFileName());
                    } catch (Exception e) {
                        journal.event("NATIVE_DIAGNOSTIC",
                            "stage=" + stageName + " sanitized-copy-failed");
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    private static boolean oversize(Path file) throws IOException {
        return Files.exists(file) && Files.size(file) > MAX_BYTES;
    }

    private static String readBounded(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES)
            throw new IOException("Native diagnostic too large");
        byte[] raw = Files.readAllBytes(file);
        // /Out from 1C Designer can use UTF-8, UTF-16 with BOM,
        // UTF-16LE without BOM, or Windows-1251.
        if (raw.length >= 2) {
            if (((raw[0] & 255) == 0xFF && (raw[1] & 255) == 0xFE)
                    || ((raw[0] & 255) == 0xFE && (raw[1] & 255) == 0xFF)) {
                return StandardCharsets.UTF_16.decode(
                    ByteBuffer.wrap(raw)).toString();
            }
        }
        if (raw.length >= 16) {
            int oddZeroes = 0, evenZeroes = 0;
            int limit = Math.min(raw.length & ~1, 2048);
            for (int i = 0; i < limit; i += 2) {
                if (raw[i] == 0) evenZeroes++;
                if (raw[i + 1] == 0) oddZeroes++;
            }
            if (oddZeroes > limit / 5 && evenZeroes < limit / 20) {
                return StandardCharsets.UTF_16LE.decode(
                    ByteBuffer.wrap(raw)).toString();
            }
            if (evenZeroes > limit / 5 && oddZeroes < limit / 20) {
                return StandardCharsets.UTF_16BE.decode(
                    ByteBuffer.wrap(raw)).toString();
            }
        }
        for (var charset : List.of(StandardCharsets.UTF_8,
                java.nio.charset.Charset.forName("windows-1251"))) {
            try {
                return charset.newDecoder().onMalformedInput(
                    CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException ignored) { /* next charset */ }
        }
        throw new IOException("Native diagnostic text encoding not recognized");
    }

    private static String redact(String source,
            OutgoingDesignerCommands.Connection c) {
        String sanitized = source;
        for (String secret : List.of(c.infobasePassword(),
                c.repositoryPassword(), c.infobaseUsername(),
                c.repositoryUsername(), c.repositoryAddress(),
                c.infobase().address())) {
            if (secret != null && !secret.isBlank())
                sanitized = sanitized.replace(secret, "[REDACTED]");
        }
        return sanitized.length() > 2_000_000
            ? sanitized.substring(0, 2_000_000) + "\n[TRUNCATED]\n"
            : sanitized;
    }
}
