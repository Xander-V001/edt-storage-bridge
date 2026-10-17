package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Controlled read-only Designer report runner.
 *
 * <p>Runs ONLY /ConfigurationRepositoryReport. Optional infobase credentials
 * are supplied via /N and /P just like the existing Designer update runner;
 * repository credentials are never needed here. No shell or mutation.
 * Temporary reports and diagnostics are removed after exit.
 */
public final class ReadOnlyReportRunner {

    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long POLL_MILLIS = 200L;
    private static final long KILL_WAIT_MILLIS = 1000L;

    public RepositoryHistoryReport readHistory(RepositoryHistoryRequest request,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        return readHistory(request, cancelled, "", "");
    }

    public RepositoryHistoryReport readHistory(RepositoryHistoryRequest request,
            BooleanSupplier cancelled, String ibUsername, String ibPassword)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        DesignerInfobaseAuth.validate(ibUsername, ibPassword);
        Path executable = request.executable();
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReportExecutionException(
                ReportExecutionException.Reason.INVALID_EXECUTABLE,
                "Specified 1C executable does not exist", null);
        }
        if (cancelled.getAsBoolean()) {
            throw new ReportExecutionException(ReportExecutionException.Reason.CANCELLED,
                "Read-only report request was cancelled", null);
        }

        Path work = Files.createTempDirectory("edt-storage-report-");
        try {
            try {
                Files.setPosixFilePermissions(work,
                    PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ex) {
                // NTFS/Windows uses platform-specific ACLs.
            }

            Path report = work.resolve("history.txt");
            Path log = work.resolve("designer.txt");
            Path stdout = work.resolve("stdout.txt");
            Path stderr = work.resolve("stderr.txt");
            List<String> baseArguments =
                ReadOnlyReportCommand.arguments(request, report, log);
            var arguments = new java.util.ArrayList<String>(baseArguments);
            if (!ibUsername.isBlank() || !ibPassword.isBlank()) {
                var authentication = new java.util.ArrayList<String>();
                DesignerInfobaseAuth.addTo(authentication, ibUsername, ibPassword);
                arguments.addAll(4, authentication);
            }
            Process process = new ProcessBuilder(arguments)
                .directory(work.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
            try {
                long start = System.nanoTime();
                while (true) {
                    if (cancelled.getAsBoolean()) {
                        throw new ReportExecutionException(
                            ReportExecutionException.Reason.CANCELLED,
                            "Read-only report request was cancelled", null);
                    }
                    if (System.nanoTime() - start >= request.timeout().toNanos()) {
                        throw new ReportExecutionException(
                            ReportExecutionException.Reason.TIMEOUT,
                            "1C report generation timed out", null);
                    }
                    if (tooLarge(report, log, stdout, stderr)) {
                        throw new ReportExecutionException(
                            ReportExecutionException.Reason.OVERSIZED_OUTPUT,
                            "1C report or diagnostics exceeded the output limit", null);
                    }
                    if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new ReportExecutionException(
                        ReportExecutionException.Reason.CANCELLED,
                        "Read-only report request was cancelled", null);
                }
                int exit = process.exitValue();
                if (exit != 0) {
                    throw new ReportExecutionException(
                        ReportExecutionException.Reason.EXIT_CODE,
                        "1C Designer report command failed (exit code " + exit + ")",
                        exit);
                }
                if (!Files.isRegularFile(report) || Files.size(report) == 0) {
                    throw new ReportExecutionException(
                        ReportExecutionException.Reason.MISSING_REPORT,
                        "1C Designer exited without a nonempty history report", exit);
                }
                if (tooLarge(report, log, stdout, stderr)) {
                    throw new ReportExecutionException(
                        ReportExecutionException.Reason.OVERSIZED_OUTPUT,
                        "1C report or diagnostics exceeded the output limit", exit);
                }
                byte[] bytes = Files.readAllBytes(report);
                return new RepositoryHistoryReport(bytes, request.beginVersion(),
                    request.endVersion());
            } finally {
                terminateTree(process);
            }
        } finally {
            // Files below are generated only inside our own private temp dir.
            deleteQuietly(work.resolve("history.txt"));
            deleteQuietly(work.resolve("designer.txt"));
            deleteQuietly(work.resolve("stdout.txt"));
            deleteQuietly(work.resolve("stderr.txt"));
            deleteQuietly(work);
        }
    }

    private static boolean tooLarge(Path... files) throws IOException {
        for (Path file : files) {
            if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) {
                return true;
            }
        }
        return false;
    }

    private static void terminateTree(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.descendants().forEach(child -> child.destroy());
            process.destroy();
            if (!process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Do not mask the original failure; temp paths are never reused.
        }
    }
}
