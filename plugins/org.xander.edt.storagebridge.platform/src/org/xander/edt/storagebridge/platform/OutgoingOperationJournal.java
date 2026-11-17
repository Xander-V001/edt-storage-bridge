package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Local, durable, redacted, append-only diagnostics for ONE native write.
 * Each invocation owns an independent UUID directory. Older journals are
 * NEVER consulted and cannot block the start of a new invocation.
 *
 * No raw argv, passwords or Designer output are ever logged by this class.
 * Native stderr/logs are copied only through a redacting runner.
 */
public final class OutgoingOperationJournal {
    private final Path directory;
    private final Path marker;
    private final Path events;
    private final String runId;

    private OutgoingOperationJournal(Path directory, String runId) {
        this.directory = directory;
        this.marker = directory.resolve("state.txt");
        this.events = directory.resolve("events.log");
        this.runId = runId;
    }

    public static OutgoingOperationJournal begin(Path workspaceState,
            String scope, String gitSha, long version) throws IOException {
        Objects.requireNonNull(workspaceState);
        if (scope == null || scope.isBlank() || gitSha == null
                || !gitSha.matches("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")
                || version < 1) {
            throw new IllegalArgumentException("Unidentified outgoing operation");
        }
        Path root = workspaceState.toAbsolutePath().normalize()
            .resolve("outgoing").resolve(sha256(scope));
        Files.createDirectories(root);
        lockDown(root);
        // Always start a new independent run. Historical active.txt files
        // from older releases are ignored; this version never creates one.
        String id = UUID.randomUUID().toString();
        Path folder = Files.createDirectory(root.resolve(id));
        lockDown(folder);
        var journal = new OutgoingOperationJournal(folder, id);
        journal.event("BEGIN", "git=" + gitSha + " revision=" + version);
        journal.state("PREPARED");
        return journal;
    }

    /**
     * Diagnostic events may only contain controlled metadata, never command
     * lines, object names, paths, comments, passwords or raw exception text.
     */
    public void stageDetail(String stage, String outcome, long elapsedMillis)
            throws IOException {
        if (stage == null || !stage.matches("[A-Z_]{2,30}")
                || outcome == null || !outcome.matches("[A-Z_]{2,30}")
                || elapsedMillis < 0) {
            throw new IllegalArgumentException("Unsafe stage diagnostic");
        }
        event("STAGE_DETAIL", "stage=" + stage + " outcome=" + outcome
            + " elapsed_ms=" + elapsedMillis);
    }

    public void selectedScope(int objects, int files, String sha256Digest)
            throws IOException {
        if (objects < 1 || files < 0 || sha256Digest == null
                || !sha256Digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid scope evidence");
        }
        event("SELECTED_SCOPE", "objects=" + objects + " files=" + files
            + " digest=" + sha256Digest);
    }

    public void observedRevision(String checkpoint, long revision)
            throws IOException {
        if (checkpoint == null || !checkpoint.matches("[A-Z_]{2,30}")
                || revision < 1) {
            throw new IllegalArgumentException("Invalid repository checkpoint");
        }
        event("OBSERVED_REVISION", "checkpoint=" + checkpoint
            + " revision=" + revision);
    }

    public Path directory() { return directory; }
    public String id() { return runId; }

    public void before(String stage) throws IOException {
        if (!stage.matches("(LOCK|LOAD|COMMIT)"))
            throw new IllegalArgumentException("Invalid native stage");
        String required = switch (stage) {
            case "LOCK" -> "PREPARED";
            case "LOAD" -> "LOCK_PROCESS_OK";
            case "COMMIT" -> "LOAD_PROCESS_OK";
            default -> throw new IllegalArgumentException("Invalid stage");
        };
        if (!required.equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim())) {
            throw new IOException("Outgoing stage order is invalid: reconcile state");
        }
        state(stage + "_UNCERTAIN");
        event("BEFORE_" + stage, "attempt-recorded-before-process-start");
    }

    public void after(String stage, int exit) throws IOException {
        if (!stage.matches("(LOCK|LOAD|COMMIT)"))
            throw new IllegalArgumentException("Invalid native stage");
        if (!(stage + "_UNCERTAIN").equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim())) {
            throw new IOException("Unexpected native stage result");
        }
        event("EXIT_" + stage, "exit=" + exit);
        if (exit != 0) {
            state(stage + "_UNCERTAIN");
            throw new IOException("Native stage failed; repository state uncertain");
        }
        state(stage + "_PROCESS_OK");
    }

    /**
     * Finalize only this invocation after native Designer Commit (exit 0).
     * No repository HEAD read, historical marker check or cross-run lock.
     */
    public void completedAfterNativeCommit() throws IOException {
        if (!"COMMIT_PROCESS_OK".equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim())) {
            throw new IOException("No confirmed native Commit process outcome");
        }
        event("COMMIT_SUCCEEDED", "native-designer-exit-zero");
        state("COMPLETED");
    }

    /** Abort this invocation before Lock; other runs are not affected. */
    public void abortBeforeMutation() throws IOException {
        if (!"PREPARED".equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim()))
            throw new IOException("Cannot abort a possibly mutating operation");
        event("ABORT", "before-lock");
        state("ABORTED");
    }

    public synchronized void event(String code, String safeMessage)
            throws IOException {
        if (code == null || !code.matches("[A-Z_]{2,40}")
                || safeMessage == null || safeMessage.length() > 4096
                || safeMessage.indexOf('\n') >= 0
                || safeMessage.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Only bounded redacted events allowed");
        }
        Files.writeString(events,
            Instant.now() + " | " + code + " | " + safeMessage + "\n",
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }

    private void state(String phase) throws IOException {
        Path tmp = directory.resolve("state.part");
        Files.writeString(tmp, phase + "\n", StandardCharsets.US_ASCII);
        try {
            Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void lockDown(Path folder) {
        try {
            Files.setPosixFilePermissions(folder,
                PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows NTFS ACLs are inherited from Eclipse workspace.
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
