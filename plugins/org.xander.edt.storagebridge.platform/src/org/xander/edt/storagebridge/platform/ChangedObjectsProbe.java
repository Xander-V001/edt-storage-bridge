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
 * One native 1C Designer -update -getChanges invocation produces BOTH
 * the changed-name report and the actual incremental XML patch. The plugin
 * retains that patch for EDT instead of deleting and re-exporting it with
 * a hand-built -listFile. The connected 1C infobase is read-only here.
 *
 * The comparison ConfigDumpInfo.xml is a real, unchanged platform snapshot.
 * No Git/EDT common ancestor or repository revision is inferred from its
 * opaque version tokens.
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
        // Keep the argument order of 1C's published native patch example:
        // -update -configDumpInfoForChanges <base> -getChanges <report>.
        args.add("-update");
        args.add("-configDumpInfoForChanges");
        args.add(info.toString());
        args.add("-getChanges");
        args.add(changeFile.toString());
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
                try {
                    validateNativeStage(result, inspection);
                } catch (NativeStageIncomplete incomplete) {
                    // The native stage must be removed on failure, but its
                    // small LOCAL-only Designer reports are vital for the
                    // next diagnosis. Never retain XML metadata or the
                    // previous ConfigDumpInfo snapshot in this directory.
                    incomplete.setDiagnosticsDirectory(
                        preserveDiagnostics(log, changes, output, errors));
                    throw incomplete;
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

    /**
     * Validate a PLATFORM-generated incremental XML stage against its separate
     * -getChanges report. An EMPTY changes report needs no EDT XML import and
     * is allowed to have no ConfigDumpInfo.xml. It will be checked against
     * separately captured COMPLETE ConfigDumpInfo manifests by the receiver.
     *
     * A NON-EMPTY report, in contrast, must have a native, usable XML patch.
     * Never fabricate a metadata index, invent XML or silently treat missing
     * files as a successful selective import.
     */
    public static void validateNativeStage(NativeChanges changes,
            Path inspection) throws IOException {
        Objects.requireNonNull(changes);
        Objects.requireNonNull(inspection);
        if (!Files.isDirectory(inspection, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(inspection)) {
            throw new IOException("Native incremental export directory missing");
        }
        if (changes.fullDump()) {
            // A native FullDump requests a separately approved global export.
            // An incomplete incremental XML stage is not used for EDT import.
            return;
        }
        Path manifest = inspection.resolve("ConfigDumpInfo.xml");
        boolean hasManifest = Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(manifest);
        boolean hasMetadata;
        try (var files = Files.walk(inspection)) {
            hasMetadata = files.anyMatch(file ->
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(file) && !file.equals(manifest));
        }
        if (changes.exactNames().isEmpty()) {
            if (hasMetadata) {
                throw new NativeStageIncomplete(
                    "1C returned an empty changes report but emitted metadata files",
                    changes, hasManifest, true);
            }
            // 1C documents generation of a NEW ConfigDumpInfo.xml for
            // -update -configDumpInfoForChanges, even when the changed-object
            // report is empty. Do not silently waive the native output
            // contract based on an unverified hypothesis.
            if (!hasManifest) {
                throw new NativeStageIncomplete(
                    "1C emitted an empty change report but no new version manifest",
                    changes, false, false);
            }
            ConfigDumpInfoIndex.exportableNames(manifest);
            return;
        }
        if (!hasManifest || !hasMetadata) {
            throw new NativeStageIncomplete(
                "1C reported changed objects without an importable XML patch",
                changes, hasManifest, hasMetadata);
        }
        // Only the 1C platform creates the manifest; never synthesize one.
        ConfigDumpInfoIndex.exportableNames(manifest);
    }

    /** User-safe diagnostic without source paths, object names or passwords. */
    public static final class NativeStageIncomplete extends IOException {
        private static final long serialVersionUID = 1L;
        private final NativeChanges nativeChanges;
        private final boolean manifestPresent;
        private final boolean metadataPresent;
        private Path diagnosticsDirectory;

        public NativeStageIncomplete(String reason, NativeChanges nativeChanges,
                boolean manifestPresent, boolean metadataPresent) {
            super(reason);
            this.nativeChanges = Objects.requireNonNull(nativeChanges);
            this.manifestPresent = manifestPresent;
            this.metadataPresent = metadataPresent;
        }

        /** The actual, parsed 1C change list; never inferred from the inventory. */
        public NativeChanges nativeChanges() { return nativeChanges; }
        public int reportedChanges() { return nativeChanges.exactNames().size(); }
        public boolean manifestPresent() { return manifestPresent; }
        public boolean metadataPresent() { return metadataPresent; }

        /**
         * A Designer no-delta report with no payload at all can only be
         * acknowledged by the COORDINATOR, which independently proves an
         * unchanged complete 1C inventory and stable repository HEAD.
         * Never use this property alone as proof.
         */
        public boolean emptyDeltaMissingOnlyManifest() {
            return !nativeChanges.fullDump()
                && nativeChanges.exactNames().isEmpty()
                && !manifestPresent && !metadataPresent;
        }
        public Path diagnosticsDirectory() { return diagnosticsDirectory; }

        private void setDiagnosticsDirectory(Path directory) {
            this.diagnosticsDirectory = directory;
        }
    }

    private static Path preserveDiagnostics(Path designerLog, Path changeReport,
            Path stdout, Path stderr) {
        Path destination = null;
        try {
            destination = Files.createTempDirectory("edt-storage-native-diagnostics-");
            try {
                Files.setPosixFilePermissions(destination,
                    PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // On Windows the directory retains OS-managed local ACLs.
            }
            Path[] sources = {designerLog, changeReport, stdout, stderr};
            String[] names = {"1c-log.txt", "changes.txt", "stdout.txt", "stderr.txt"};
            for (int i = 0; i < sources.length; i++) {
                if (Files.isRegularFile(sources[i], LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(sources[i])
                        && Files.size(sources[i]) <= MAX_DIAGNOSTICS) {
                    Files.copy(sources[i], destination.resolve(names[i]));
                }
            }
            return destination;
        } catch (IOException | SecurityException ex) {
            if (destination != null) {
                try { deleteRecursively(destination); }
                catch (IOException ignored) { /* best effort */ }
            }
            return null;
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
