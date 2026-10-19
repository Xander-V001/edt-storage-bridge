package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
        DesignerInfobaseAuth.addTo(args,
            request.infobaseUsername(), request.infobasePassword());
        args.add("/DisableStartupDialogs");
        args.add("/DisableStartupMessages");
        args.add("/DisableUnrecoverableErrorMessage");
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

    /** Offline-testable native -update staging: output starts EMPTY. */
    public record Workspace(Path inspectionDirectory, Path baselineCopy) {}

    public static Workspace prepareWorkspace(Path work, Path versionFile)
            throws IOException {
        Objects.requireNonNull(work);
        Objects.requireNonNull(versionFile);
        if (!Files.isDirectory(work)
                || !Files.isRegularFile(versionFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid native change detection workspace");
        }
        Path output = Files.createDirectory(work.resolve("inspection"));
        Path privateBaseline = work.resolve("reviewed-ConfigDumpInfo.xml");
        Files.copy(versionFile, privateBaseline);
        return new Workspace(output, privateBaseline);
    }

    /** Platform-native -getChanges result. FullDump is a required GLOBAL
     * merge decision, not an IO failure and not a partial object selection. */
    public record NativeChanges(boolean fullDump,
            ConfigurationChangesParser.ChangeSet changes) {
        public NativeChanges {
            Objects.requireNonNull(changes);
            if (fullDump && (!changes.newObjects().isEmpty()
                    || !changes.modifiedObjects().isEmpty())) {
                throw new IllegalArgumentException("FullDump has no partial object list");
            }
        }

        public java.util.Set<String> exactNames() {
            var names = new java.util.LinkedHashSet<String>(changes.newObjects());
            names.addAll(changes.modifiedObjects());
            return java.util.Set.copyOf(names);
        }
    }

    /**
     * Native patch and native change report are ONE command result. Retain the
     * PLATFORM-generated XML files until EDT finishes comparing them.
     * The owner controls their lifetime; no second -listFile export occurs.
     */
    public record NativeExport(Path workingRoot, Path xmlDirectory,
            NativeChanges changes) implements AutoCloseable {
        public NativeExport {
            Objects.requireNonNull(workingRoot);
            Objects.requireNonNull(xmlDirectory);
            Objects.requireNonNull(changes);
        }

        @Override
        public void close() throws IOException {
            deleteRecursively(workingRoot);
        }
    }

    public NativeExport exportNative(ChangeDetectionRequest request,
            Path verifiedDumpInfo, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(verifiedDumpInfo, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(verifiedDumpInfo)
                || Files.size(verifiedDumpInfo) < 10) {
            throw new IOException("No trusted ConfigDumpInfo.xml baseline is available");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Native 1C incremental export cancelled");
        }

        Path work = Files.createTempDirectory("edt-storage-native-increment-");
        boolean ready = false;
        try {
            try {
                Files.setPosixFilePermissions(work,
                    PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // NTFS permissions are handled differently.
            }
            Workspace prepared = prepareWorkspace(work, verifiedDumpInfo);
            Path inspection = prepared.inspectionDirectory();
            Path privateBaseline = prepared.baselineCopy();
            Path changes = work.resolve("changes.txt");
            Path log = work.resolve("1c-log.txt");
            Path output = work.resolve("stdout.txt");
            Path errors = work.resolve("stderr.txt");

            Process process = new ProcessBuilder(
                    command(request, privateBaseline, inspection, changes, log))
                .directory(work.toFile())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
            try {
                long start = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Native 1C incremental export cancelled");
                    }
                    if (System.nanoTime() - start > request.timeout().toNanos()) {
                        throw new IOException("Native 1C incremental export timed out");
                    }
                    if (tooLarge(output) || tooLarge(errors)
                            || tooLarge(log) || tooLarge(changes)) {
                        throw new IOException("1C incremental diagnostics exceeded limit");
                    }
                }
                if (process.exitValue() != 0 || cancelled.getAsBoolean()
                        || !Files.isRegularFile(changes, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("1C did not complete native incremental export");
                }
                if (Files.mismatch(verifiedDumpInfo, privateBaseline) != -1) {
                    throw new IOException("1C changed the comparison baseline");
                }

                NativeChanges result = parseNativeFile(changes);
                Path producedVersions = inspection.resolve("ConfigDumpInfo.xml");
                if (!Files.isRegularFile(producedVersions, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Native increment has no ConfigDumpInfo.xml");
                }
                // Only 1C is authorized to produce this file. Validate it but
                // never synthesize, edit, or filter its contents.
                ConfigDumpInfoIndex.exportableNames(producedVersions);
                if (!result.fullDump() && !result.exactNames().isEmpty()) {
                    try (var exported = Files.walk(inspection)) {
                        if (exported.noneMatch(file -> Files.isRegularFile(file,
                                LinkOption.NOFOLLOW_LINKS)
                                && !file.equals(producedVersions))) {
                            throw new IOException(
                                "1C reported changes but did not export metadata files");
                        }
                    }
                }
                ready = true;
                return new NativeExport(work, inspection, result);
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
            if (!ready) {
                deleteRecursively(work);
            }
        }
    }

    /** Legacy list-only operation: immediately release its native patch. */
    public NativeChanges detectNative(ChangeDetectionRequest request,
            Path verifiedDumpInfo, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        try (NativeExport export = exportNative(
                request, verifiedDumpInfo, cancelled)) {
            return export.changes();
        }
    }

    /**
     * Parse ONLY the platform-generated UTF-8 change report. The marker
     * FullDump is not an error or "no changes"; it requires a complete
     * EDT comparison. Unknown statuses still fail closed.
     */
    public static NativeChanges parseNativeFile(Path file)
            throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_DIAGNOSTICS) {
            throw new IOException("1C change report missing or too large");
        }
        byte[] bytes = Files.readAllBytes(file);
        int offset = bytes.length >= 3
            && (bytes[0] & 0xff) == 0xef
            && (bytes[1] & 0xff) == 0xbb
            && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        return parseNativeText(new String(bytes, offset,
            bytes.length - offset, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static NativeChanges parseNativeText(String text)
            throws IOException {
        Objects.requireNonNull(text);
        if ("FullDump".equalsIgnoreCase(text.strip())) {
            return new NativeChanges(true,
                new ConfigurationChangesParser.ChangeSet(
                    List.of(), List.of()));
        }
        return new NativeChanges(false,
            ConfigurationChangesParser.parse(text));
    }

    /** Legacy entry point; never reinterpret a platform FullDump as empty. */
    public ConfigurationChangesParser.ChangeSet detect(
            ChangeDetectionRequest request, Path verifiedDumpInfo,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        NativeChanges nativeResult = detectNative(
            request, verifiedDumpInfo, cancelled);
        if (nativeResult.fullDump()) {
            throw new IOException("1C requires a complete configuration dump");
        }
        return nativeResult.changes();
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
