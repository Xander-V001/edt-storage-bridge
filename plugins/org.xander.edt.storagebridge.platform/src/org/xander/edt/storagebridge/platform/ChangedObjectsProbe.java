package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Read-only incremental detector using 1C Designer -getChanges against a
 * PREVIOUSLY VERIFIED ConfigDumpInfo.xml. No full configuration export.
 *
 * The caller must establish that the baseline belongs to the same classical
 * repository and revision as the EDT/Git common ancestor. This code never
 * fabricates that relationship or modifies the repository-bound infobase.
 */
public final class ChangedObjectsProbe {
    private static final long MAX_DIAGNOSTICS = 8L * 1024 * 1024;

    public List<String> command(ChangeDetectionRequest request, Path info,
            Path inspectionDirectory, Path changeFile, Path serviceLog) {
        Objects.requireNonNull(request, "request");
        for (Path path : new Path[]{info, inspectionDirectory, changeFile, serviceLog}) {
            if (path == null || !path.isAbsolute()) {
                throw new IllegalArgumentException("Absolute files required");
            }
        }
        ArrayList<String> args = new ArrayList<>();
        args.add(request.executable().toString());
        args.add("DESIGNER");
        args.add(request.infobase().switchName());
        args.add(request.infobase().address());
        args.add("/DisableStartupDialogs");
        args.add("/DumpConfigToFiles");
        args.add(inspectionDirectory.toString());
        // -configDumpInfoForChanges is supported only together with -update
        // and -getChanges (1C batch Designer contract). The previous command
        // omitted -update, causing the change probe to fail at runtime.
        args.add("-update");
        args.add("-getChanges");
        args.add(changeFile.toString());
        args.add("-configDumpInfoForChanges");
        args.add(info.toString());
        args.add("/Out");
        args.add(serviceLog.toString());
        return List.copyOf(args);
    }

    public ConfigurationChangesParser.ChangeSet detect(
            ChangeDetectionRequest request, Path verifiedDumpInfo,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(verifiedDumpInfo, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(verifiedDumpInfo)
                || Files.size(verifiedDumpInfo) < 10) {
            throw new IOException("No trusted ConfigDumpInfo.xml baseline is available");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Change detection cancelled");
        }

        Path work = Files.createTempDirectory("edt-storage-getchanges-");
        try {
            try {
                Files.setPosixFilePermissions(work,
                    PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows NTFS has different ACL semantics.
            }
            Path inspection = Files.createDirectory(work.resolve("inspection"));
            Path changes = work.resolve("changes.txt");
            Path log = work.resolve("1c-log.txt");
            Path output = work.resolve("stdout.txt");
            Path errors = work.resolve("stderr.txt");
            var process = new ProcessBuilder(
                    command(request, verifiedDumpInfo.toAbsolutePath(),
                        inspection, changes, log))
                .directory(work.toFile())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
            try {
                long start = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Change detection cancelled");
                    }
                    if (System.nanoTime() - start > request.timeout().toNanos()) {
                        throw new IOException("Change detection timed out");
                    }
                    if (tooLarge(output) || tooLarge(errors)
                            || tooLarge(log) || tooLarge(changes)) {
                        throw new IOException("1C change output exceeded size limit");
                    }
                }
                if (process.exitValue() != 0 || cancelled.getAsBoolean()
                        || !Files.isRegularFile(changes)) {
                    throw new IOException("1C could not produce a verified changes list");
                }
                return ConfigurationChangesParser.read(changes);
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
            deleteRecursively(work);
        }
    }

    private static boolean tooLarge(Path path) throws IOException {
        return Files.exists(path) && Files.size(path) > MAX_DIAGNOSTICS;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
