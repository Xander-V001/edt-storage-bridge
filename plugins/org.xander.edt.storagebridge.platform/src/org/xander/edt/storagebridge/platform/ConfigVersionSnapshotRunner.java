package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Creates ConfigDumpInfo.xml of the currently loaded configuration of the
 * already existing repository-bound 1C infobase WITHOUT exporting XML objects.
 *
 * IMPORTANT: This file is a snapshot of the base's CURRENT configuration.
 * It can only be associated with a classical storage revision after separate
 * identity/revision verification. Do NOT generate it on storage 101 and label
 * it as the Git project baseline 100.
 */
public final class ConfigVersionSnapshotRunner {
    public List<String> command(ChangeDetectionRequest request,
            Path dumpDirectory, Path log) {
        Objects.requireNonNull(request, "request");
        if (dumpDirectory == null || log == null
                || !dumpDirectory.isAbsolute() || !log.isAbsolute()) {
            throw new IllegalArgumentException("Absolute output paths required");
        }
        List<String> command = new ArrayList<>();
        command.add(request.executable().toString());
        command.add("DESIGNER");
        command.add(request.infobase().switchName());
        command.add(request.infobase().address());
        DesignerInfobaseAuth.addTo(command,
            request.infobaseUsername(), request.infobasePassword());
        command.add("/DisableStartupDialogs");
        command.add("/DisableStartupMessages");
        command.add("/DisableUnrecoverableErrorMessage");
        command.add("/DumpConfigToFiles");
        command.add(dumpDirectory.toString());
        command.add("-Format");
        command.add("Hierarchical");
        command.add("-configDumpInfoOnly");
        command.add("/Out");
        command.add(log.toString());
        // Native Designer also exposes its numerical batch result.
        // Keep it alongside the platform log; never infer success from
        // absence of messages in /Out.
        command.add("/DumpResult");
        command.add(log.resolveSibling("result.txt").toString());
        return List.copyOf(command);
    }

    public Snapshot capture(ChangeDetectionRequest request,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("1C executable not found");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Config version snapshot cancelled");
        }

        // Keep the native 1C XML path short: long nested paths in %TEMP%
        // can exceed Designer's restrictions even when only a versions
        // manifest is requested. Diagnostics are copied separately to a
        // stable, user-visible application data directory on failure.
        Path work = Files.createTempDirectory("1ci-");
        boolean succeeded = false;
        boolean launched = false;
        boolean diagnosticsPreserved = false;
        try {
            Path output = Files.createDirectory(work.resolve("dump"));
            Path log = work.resolve("designer.log");
            Path stdout = work.resolve("stdout.txt");
            var process = new ProcessBuilder(command(request, output, log))
                .directory(work.toFile())
                .redirectErrorStream(true)
                .redirectOutput(stdout.toFile())
                .start();
            launched = true;
            try {
                long start = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Config version snapshot cancelled");
                    }
                    if (System.nanoTime() - start > request.timeout().toNanos()) {
                        throw new IOException("Config version snapshot timed out");
                    }
                    if ((Files.exists(log) && Files.size(log) > 8L * 1024 * 1024)
                            || (Files.exists(stdout)
                                && Files.size(stdout) > 8L * 1024 * 1024)) {
                        throw new IOException("1C diagnostic output exceeded limit");
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Config version snapshot cancelled");
                }
                if (process.exitValue() != 0) {
                    // /Out may contain the ONLY explanation of exit=1
                    // (authorization, 1C version, infobase lock, bad path).
                    // Preserve BEFORE cleanup and report ONLY a verified
                    // durable path, not a transient %TEMP% directory.
                    Path saved = saveFailureDiagnostics(work, process.exitValue());
                    diagnosticsPreserved = true;
                    throw new SnapshotFailure(process.exitValue(), saved);
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
            Path info = output.resolve("ConfigDumpInfo.xml");
            if (!Files.isRegularFile(info, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(info) < 20 || Files.size(info) > 64L * 1024 * 1024) {
                throw new IOException("1C did not return a valid versions file");
            }
            // -configDumpInfoOnly must not unexpectedly export any configuration
            // objects: fail closed, never accept a silent fallback to full dump.
            try (Stream<Path> files = Files.walk(output)) {
                if (files.filter(Files::isRegularFile)
                        .anyMatch(file -> !file.equals(info))) {
                    throw new IOException(
                        "Version-only command produced unexpected configuration files");
                }
            }
            succeeded = true;
            return new Snapshot(work, info);
        } finally {
            if (!succeeded && (!launched || diagnosticsPreserved)) {
                // Never override the original Designer failure with a
                // secondary temporary-directory cleanup exception.
                try {
                    deleteTree(work);
                } catch (IOException ignored) {
                    // Diagnostics have already been preserved independently.
                }
            }
            // If process startup/collection failed before a durable copy
            // could be created, keep the original temporary data untouched.
            // Successful Snapshot.close() deletes its own temporary data.
        }
    }

    /**
     * Preserve only native diagnostic files, NEVER exported configuration
     * XML or infobase credentials. Failures are kept in the current user's
     * non-temporary profile directory where Explorer can find them.
     *
     * Exposed for offline regression tests. No Designer process is started.
     */
    public static Path saveFailureDiagnostics(Path stagingRoot, int exitCode)
            throws IOException {
        Objects.requireNonNull(stagingRoot);
        if (!Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No native snapshot staging directory");
        }
        String appData = System.getenv("LOCALAPPDATA");
        Path base = appData != null && !appData.isBlank()
            ? Path.of(appData)
            : Path.of(System.getProperty("user.home"),
                ".local", "share");
        Path diagnostics = base.resolve("EDTStorageBridge")
            .resolve("diagnostics");
        return saveFailureDiagnostics(stagingRoot, exitCode, diagnostics);
    }

    /**
     * Explicit destination is only for deterministic offline tests.
     * Production always uses LOCALAPPDATA/EDTStorageBridge/diagnostics,
     * not a transient Java %TEMP% location.
     */
    public static Path saveFailureDiagnostics(
            Path stagingRoot, int exitCode, Path diagnostics)
            throws IOException {
        Objects.requireNonNull(stagingRoot);
        Objects.requireNonNull(diagnostics);
        if (!Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No native snapshot staging directory");
        }
        Files.createDirectories(diagnostics);
        // Contains unredacted native /Out; never expose this directory
        // through a public/shared workspace or Git project.
        restrictToOwner(diagnostics, true);
        Path saved = Files.createDirectory(
            diagnostics.resolve("snapshot-" + UUID.randomUUID()));
        restrictToOwner(saved, true);
        // Avoid ever publishing paths, login/password arguments or raw
        // diagnostic text to the regular EDT error log. The user inspects
        // the private files explicitly before sharing them.
        for (String name : List.of("designer.log", "stdout.txt", "stderr.txt", "result.txt")) {
            Path source = stagingRoot.resolve(name);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(source) > 8L * 1024 * 1024) {
                    throw new IOException("Native 1C diagnostics exceed size limit");
                }
                Path copied = Files.copy(source, saved.resolve(name));
                restrictToOwner(copied, false);
            }
        }
        Files.writeString(saved.resolve("snapshot-failure.txt"),
            "1C Designer snapshot command failed." + System.lineSeparator()
            + "Process exit code: " + exitCode + System.lineSeparator()
            + "Files: designer.log, stdout.txt, stderr.txt, result.txt (if 1C created them)."
            + System.lineSeparator()
            + "These files may contain private environment data; review before sharing."
            + System.lineSeparator(), StandardCharsets.UTF_8);
        restrictToOwner(saved.resolve("snapshot-failure.txt"), false);
        // Never advertise a directory before its existence is verified.
        if (!Files.isDirectory(saved)
                || !Files.isRegularFile(saved.resolve("snapshot-failure.txt"))) {
            throw new IOException("Native snapshot diagnostics could not be saved");
        }
        return saved.toRealPath();
    }

    private static void restrictToOwner(Path path, boolean directory)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                PosixFilePermissions.fromString(directory ? "rwx------"
                    : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses ACLs inherited from per-user LOCALAPPDATA.
        }
    }

    public static final class SnapshotFailure extends IOException {
        private final int exitCode;
        private final Path diagnosticsDirectory;

        public SnapshotFailure(int exitCode, Path diagnosticsDirectory) {
            super("Designer snapshot failed with exit code " + exitCode);
            this.exitCode = exitCode;
            this.diagnosticsDirectory =
                Objects.requireNonNull(diagnosticsDirectory).toAbsolutePath();
        }

        public int exitCode() { return exitCode; }
        public Path diagnosticsDirectory() { return diagnosticsDirectory; }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record Snapshot(Path stagingRoot, Path configDumpInfo)
            implements AutoCloseable {
        public Snapshot {
            Objects.requireNonNull(stagingRoot, "stagingRoot");
            Objects.requireNonNull(configDumpInfo, "configDumpInfo");
        }

        @Override
        public void close() throws IOException {
            deleteTree(stagingRoot);
        }
    }
}
