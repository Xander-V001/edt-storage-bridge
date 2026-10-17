package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

        Path work = Files.createTempDirectory("edt-storage-configversions-");
        boolean succeeded = false;
        boolean launched = false;
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
                    // Do not delete the only native 1C diagnostic output.
                    // The UI exposes only this intentionally generated
                    // directory, never raw potentially sensitive log text.
                    throw new SnapshotFailure(process.exitValue(), work);
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
            if (!succeeded && !launched) {
                deleteTree(work);
            }
            // Failed launched operations retain /Out and stdout locally,
            // matching the previous working 1C-Updater troubleshooting model.
            // A successful Snapshot.close() still deletes the temporary files.
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
