package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Read-only, native comparison of TWO specific repository revisions.
 * The Designer /CompareCfg command compares resulting configurations,
 * not the list of commits between them. Its TXT report is deliberately
 * NOT parsed as a machine-readable list of object identities: 1C does
 * not document such a schema. The existing native -getChanges report
 * remains the source of identities for selective EDT import.
 *
 * This command never locks, updates or commits repository objects.
 */
public final class NativeRepositoryCompare {
    private static final long MAX_REPORT_BYTES = 32L * 1024 * 1024;
    private static final long POLL_MILLIS = 200L;

    private NativeRepositoryCompare() {}

    public record Result(long fromRevision, long toRevision, long reportBytes) {
        public Result {
            if (fromRevision < 1 || toRevision <= fromRevision || reportBytes < 1) {
                throw new IllegalArgumentException("Invalid native comparison proof");
            }
        }
    }

    public static List<String> command(Path executable, InfobaseLocation infobase,
            String ibUsername, String ibPassword,
            String repositoryAddress, String repositoryUsername,
            String repositoryPassword, long fromRevision, long toRevision,
            Path reportFile, Path outputFile) {
        Objects.requireNonNull(executable);
        Objects.requireNonNull(infobase);
        Objects.requireNonNull(repositoryAddress);
        Objects.requireNonNull(repositoryUsername);
        Objects.requireNonNull(repositoryPassword);
        Objects.requireNonNull(reportFile);
        Objects.requireNonNull(outputFile);
        DesignerInfobaseAuth.validate(ibUsername, ibPassword);
        if (fromRevision < 1 || toRevision <= fromRevision
                || !executable.isAbsolute()
                || !reportFile.isAbsolute() || !outputFile.isAbsolute()
                || repositoryAddress.isBlank()
                || (!repositoryPassword.isEmpty() && repositoryUsername.isBlank())
                || repositoryAddress.indexOf('\n') >= 0
                || repositoryUsername.indexOf('\n') >= 0
                || repositoryPassword.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid 1C repository comparison");
        }
        var args = new ArrayList<String>();
        args.add(executable.toString());
        args.add("DESIGNER");
        args.add(infobase.switchName());
        args.add(infobase.address());
        DesignerInfobaseAuth.addTo(args, ibUsername, ibPassword);
        args.add("/ConfigurationRepositoryF");
        args.add(repositoryAddress);
        if (!repositoryUsername.isBlank()) {
            args.add("/ConfigurationRepositoryN");
            args.add(repositoryUsername);
            if (!repositoryPassword.isEmpty()) {
                args.add("/ConfigurationRepositoryP");
                args.add(repositoryPassword);
            }
        }
        args.add("/DisableStartupDialogs");
        args.add("/DisableStartupMessages");
        args.add("/DisableUnrecoverableErrorMessage");
        args.add("/CompareCfg");
        args.add("-FirstConfigurationType");
        args.add("ConfigurationRepository");
        args.add("-FirstVersion");
        args.add(Long.toString(fromRevision));
        args.add("-SecondConfigurationType");
        args.add("ConfigurationRepository");
        args.add("-SecondVersion");
        args.add(Long.toString(toRevision));
        args.add("-ReportType");
        args.add("Brief");
        args.add("-IncludeChangedObjects");
        args.add("-IncludeDeletedObjects");
        args.add("-IncludeAddedObjects");
        args.add("-ReportFormat");
        args.add("txt");
        args.add("-ReportFile");
        args.add(reportFile.toString());
        args.add("/Out");
        args.add(outputFile.toString());
        return List.copyOf(args);
    }

    /**
     * Run on a background Eclipse Job. All native artifacts remain private
     * and are removed on completion. Never log arguments (they contain
     * authentication) or raw report content (it contains metadata names).
     */
    public static Result compare(Path executable, InfobaseLocation infobase,
            String ibUsername, String ibPassword,
            String repositoryAddress, String repositoryUsername,
            String repositoryPassword, long fromRevision, long toRevision,
            Duration timeout, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(cancelled);
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Invalid comparison timeout");
        }
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("1C platform executable is unavailable");
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("Repository comparison cancelled");
        }
        Path dir = Files.createTempDirectory("edt-storage-revision-compare-");
        try {
            Path report = dir.resolve("comparison.txt");
            Path output = dir.resolve("designer.txt");
            Path stdout = dir.resolve("stdout.txt");
            Path stderr = dir.resolve("stderr.txt");
            Process process = new ProcessBuilder(command(
                    executable, infobase, ibUsername, ibPassword,
                    repositoryAddress, repositoryUsername, repositoryPassword,
                    fromRevision, toRevision, report, output))
                .directory(dir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
            try {
                long begun = System.nanoTime();
                while (true) {
                    if (cancelled.getAsBoolean()) {
                        throw new InterruptedException("Repository comparison cancelled");
                    }
                    if (System.nanoTime() - begun > timeout.toNanos()) {
                        throw new IOException("Native repository comparison timed out");
                    }
                    for (Path file : List.of(report, output, stdout, stderr)) {
                        if (Files.exists(file) && Files.size(file) > MAX_REPORT_BYTES) {
                            throw new IOException("Native comparison output too large");
                        }
                    }
                    if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Native repository comparison failed");
                }
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(report)
                        || Files.size(report) == 0
                        || Files.size(report) > MAX_REPORT_BYTES) {
                    throw new IOException("Native repository comparison report missing");
                }
                return new Result(fromRevision, toRevision, Files.size(report));
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
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
