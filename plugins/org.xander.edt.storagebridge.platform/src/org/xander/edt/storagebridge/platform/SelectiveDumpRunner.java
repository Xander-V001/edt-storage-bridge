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
 * Selective export never silently falls back to full export.
 * Full export is used for the initial comparison and explicitly requested
 * repeat full reviews. Both require the same user's confirmation in the UI.
 */
public final class SelectiveDumpRunner {

    public ExportResult export(SelectiveDumpRequest request, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return exportInternal(request, cancelled);
    }

    /**
     * Complete a platform-generated -update XML patch IN PLACE, like the
     * published 1C GitConverter: only unchanged owner dependencies are listed.
     * The caller retains ownership of the original patch directory. We neither
     * manufacture XML nor discard the native incremental output.
     */
    public void supplement(SelectiveDumpRequest request, Path nativeXml,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new java.io.InterruptedIOException("Selective completion cancelled");
        }
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(nativeXml, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(nativeXml)
                || !Files.isRegularFile(nativeXml.resolve("ConfigDumpInfo.xml"),
                    LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Native incremental XML source not ready");
        }
        Path diagnostics = Files.createTempDirectory("edt-storage-supplement-");
        boolean success = false;
        try {
            Path objects = diagnostics.resolve("objects.txt");
            Path log = diagnostics.resolve("designer.txt");
            Path output = diagnostics.resolve("stdout.txt");
            writeNativeList(objects, request.objectNames());
            Process process = new ProcessBuilder(
                SelectiveDumpCommand.arguments(request,
                    nativeXml.toRealPath(), objects, log))
                .directory(diagnostics.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
                .start();
            try {
                long start = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new java.io.InterruptedIOException(
                            "Selective completion cancelled");
                    }
                    if (System.nanoTime() - start > request.timeout().toNanos()) {
                        throw new DumpFailure(-1, diagnostics, "timeout");
                    }
                    if (tooLarge(log) || tooLarge(output)) {
                        throw new DumpFailure(-1, diagnostics, "diagnostics limit");
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new java.io.InterruptedIOException(
                        "Selective completion cancelled");
                }
                if (process.exitValue() != 0) {
                    throw new DumpFailure(process.exitValue(),
                        diagnostics, "native -listFile completion");
                }
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.descendants().forEach(
                            ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        process.waitFor(1, TimeUnit.SECONDS);
                    }
                }
            }
            // Native Designer owns the manifest and all XML. Do not combine
            // manifests by hand or treat a 0 exit code as sufficient evidence.
            if (!Files.isRegularFile(nativeXml.resolve("ConfigDumpInfo.xml"),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new DumpFailure(0, diagnostics,
                    "native incremental index lost during completion");
            }
            try (Stream<Path> paths = Files.walk(nativeXml)) {
                if (paths.noneMatch(p -> Files.isRegularFile(p,
                        LinkOption.NOFOLLOW_LINKS)
                        && !p.equals(nativeXml.resolve("ConfigDumpInfo.xml")))) {
                    throw new DumpFailure(0, diagnostics, "no native XML files");
                }
            }
            success = true;
        } finally {
            if (success) {
                deleteTree(diagnostics);
            }
            // On failure diagnostics remain for diagnosis; caller releases
            // the native XML patch, never an incomplete EDT import.
        }
    }

    /** Explicit full XML source dump; never a hidden selective fallback. */
    public ExportResult exportInitialFull(InitialFullDumpRequest request,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        return exportInternal(request, cancelled);
    }

    private ExportResult exportInternal(XmlDumpConnection request,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        boolean initialFull = request instanceof InitialFullDumpRequest;
        if (!Files.isRegularFile(request.executable(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("1C executable not found");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Export cancelled");
        }
        Path temporaryRoot = Files.createTempDirectory(
            initialFull ? "edt-storage-initial-" : "edt-storage-selective-");
        Path target = temporaryRoot.resolve("objects");
        boolean succeeded = false;
        boolean launched = false;
        try {
            Files.createDirectory(target);
            Path list = temporaryRoot.resolve("objects.txt");
            Path log = temporaryRoot.resolve("designer.txt");
            Path output = temporaryRoot.resolve("stdout.txt");
            if (!initialFull) {
                writeNativeList(list,
                    ((SelectiveDumpRequest) request).objectNames());
            }
            Process process = new ProcessBuilder(initialFull
                    ? InitialFullDumpCommand.arguments(
                        (InitialFullDumpRequest) request, target, log)
                    : SelectiveDumpCommand.arguments(
                        (SelectiveDumpRequest) request, target, list, log))
                .directory(temporaryRoot.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
                .start();
            launched = true;
            try {
                long startedAt = System.nanoTime();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Designer XML export cancelled");
                    }
                    if (System.nanoTime() - startedAt > request.timeout().toNanos()) {
                        throw new IOException("Designer XML export timed out");
                    }
                    if (tooLarge(log) || tooLarge(output)) {
                        throw new IOException("Diagnostics exceeded size limit");
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new DumpFailure(-1, temporaryRoot, "cancelled");
                }
                if (process.exitValue() != 0) {
                    // Preserve the REAL Designer diagnostics and the exact
                    // UTF-8 metadata list. Without /Out an exit=1 reveals
                    // nothing about an invalid -listFile entry.
                    throw new DumpFailure(process.exitValue(), temporaryRoot,
                        "nonzero exit");
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
                // ConfigDumpInfo.xml is ALWAYS created even if not a single
                // requested metadata object was dumped. Do not mistake the
                // version manifest for a successful selective XML export.
                if (paths.noneMatch(p -> Files.isRegularFile(p)
                        && !"ConfigDumpInfo.xml".equals(
                            p.getFileName().toString()))) {
                    throw new DumpFailure(0, temporaryRoot,
                        "no metadata files, only version index");
                }
            }
            succeeded = true;
            return new ExportResult(temporaryRoot, target);
        } catch (IOException ex) {
            if (launched && !(ex instanceof DumpFailure)) {
                // Even a filesystem/timeout error after Designer start needs
                // a discoverable local diagnostic folder. Do not surface raw
                // IOException messages; native logs may contain passwords.
                throw new DumpFailure(-1, temporaryRoot,
                    "incomplete native process or output", ex);
            }
            throw ex;
        } finally {
            if (!succeeded) {
                if (launched) {
                    // Retain designer.txt, stdout.txt, objects.txt locally
                    // after failure; avoid keeping partial XML/BSL output
                    // which can be very large or contain confidential code.
                    deleteTree(target);
                } else {
                    deleteTree(temporaryRoot);
                }
            }
        }
    }

    /**
     * Only sanitized exit status and the location of retained evidence are
     * exposed in EDT. Native 1C logs may contain sensitive data: do not copy
     * their contents to Eclipse logs, UI, Git or CI.
     */
    public static final class DumpFailure extends IOException {
        private final int exitCode;
        private final Path diagnosticsDirectory;

        public DumpFailure(int exitCode, Path diagnosticsDirectory, String kind) {
            this(exitCode, diagnosticsDirectory, kind, null);
        }

        public DumpFailure(int exitCode, Path diagnosticsDirectory, String kind,
                Throwable cause) {
            super("Designer selective XML dump failed: " + kind, cause);
            this.exitCode = exitCode;
            this.diagnosticsDirectory = diagnosticsDirectory.toAbsolutePath();
        }

        public int exitCode() {
            return exitCode;
        }

        public Path diagnosticsDirectory() {
            return diagnosticsDirectory;
        }
    }

    /** Same UTF-8-BOM + CRLF list format for initial and supplemental dumps. */
    private static void writeNativeList(Path path,
            java.util.List<String> names) throws IOException {
        if (names.isEmpty()) {
            throw new IOException("Cannot export an empty metadata list");
        }
        String lines = String.join("\r\n", names) + "\r\n";
        byte[] content = lines.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[content.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(content, 0, withBom, 3, content.length);
        Files.write(path, withBom);
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
