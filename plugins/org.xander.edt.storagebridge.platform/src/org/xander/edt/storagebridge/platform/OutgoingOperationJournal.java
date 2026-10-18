package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * Local, durable, redacted, append-only diagnostics for a native write.
 * One project/repository/IB/branch scope is permanently blocked after
 * a possibly-executed native write fails until manual reconciliation.
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
        Path active = root.resolve("active.txt");
        // Never overwrite a possibly incomplete native operation: it might
        // already have captured objects or committed a new storage version.
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "Unresolved previous outgoing operation. Inspect local outgoing journal");
        }
        String id = UUID.randomUUID().toString();
        Path folder = Files.createDirectory(root.resolve(id));
        lockDown(folder);
        var journal = new OutgoingOperationJournal(folder, id);
        // Write all startup evidence before creating the active marker.
        journal.event("BEGIN", "git=" + gitSha + " revision=" + version);
        journal.state("PREPARED");
        try {
            Files.writeString(active, id + "\n", StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            journal.event("FAILED", "unable-to-create-active-marker");
            throw e;
        }
        return journal;
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

    /** On success, only independently verified repository HEAD may unblock. */
    public void verified(long newVersion) throws IOException {
        if (newVersion < 1)
            throw new IllegalArgumentException("Unverified repository revision");
        if (!"COMMIT_PROCESS_OK".equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim())) {
            throw new IOException("No confirmed native Commit process outcome");
        }
        event("VERIFIED", "native-head=" + newVersion);
        state("COMPLETED");
        Path active = directory.getParent().resolve("active.txt");
        if (!runId.equals(Files.readString(active, StandardCharsets.US_ASCII).trim()))
            throw new IOException("Operation marker identity changed");
        Files.delete(active);
    }

    /** Before any mutation, a cancellation can safely discard the active marker. */
    public void abortBeforeMutation() throws IOException {
        if (!"PREPARED".equals(Files.readString(marker,
                StandardCharsets.US_ASCII).trim()))
            throw new IOException("Cannot abort a possibly mutating operation");
        event("ABORT", "before-lock");
        state("ABORTED");
        Path active = directory.getParent().resolve("active.txt");
        if (runId.equals(Files.readString(active, StandardCharsets.US_ASCII).trim()))
            Files.delete(active);
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
