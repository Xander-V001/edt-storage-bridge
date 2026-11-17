package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Crash-durable phase of a single classical repository receive.
 *
 * A saved pre-update ConfigDumpInfo.xml by itself cannot show whether the
 * native 1C update command already started. Never infer that from its mere
 * existence. This small ledger is not stored inside the EDT Git project.
 */
public final class ReceiveAttemptLedger {
    public enum Phase {
        NONE, SNAPSHOT_READY, UPDATE_ATTEMPTED, UPDATE_COMPLETED, CHANGES_JOURNALED
    }

    private static final String VERSION = "v1:";
    private final Path file;

    public ReceiveAttemptLedger(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("Receive state directory must be absolute");
        }
        this.file = directory.resolve("receive-phase.txt");
    }

    public Phase read() throws IOException {
        if (!Files.exists(file)) {
            return Phase.NONE;
        }
        if (!Files.isRegularFile(file) || Files.size(file) > 80) {
            throw new IOException("Unrecognized receive phase journal; manual recovery required");
        }
        String contents = Files.readString(file, StandardCharsets.US_ASCII).trim();
        if (!contents.startsWith(VERSION)) {
            throw new IOException("Unsupported receive phase journal version");
        }
        try {
            Phase phase = Phase.valueOf(contents.substring(VERSION.length()));
            if (phase == Phase.NONE) {
                throw new IllegalArgumentException("NONE cannot be persisted");
            }
            return phase;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Corrupt receive phase journal", ex);
        }
    }

    public void transition(Phase expected, Phase next) throws IOException {
        if (!allowed(expected, next)) {
            throw new IllegalArgumentException("Forbidden receive phase transition");
        }
        if (read() != expected) {
            throw new IOException("Receive phase mismatch; refusing uncertain storage update");
        }
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling("receive-phase.part");
        try {
            Files.writeString(partial, VERSION + next.name() + "\n",
                StandardCharsets.US_ASCII);
            try {
                Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Only after change names were durably persisted and snapshot retired. */
    public void finishJournaled() throws IOException {
        if (read() != Phase.CHANGES_JOURNALED) {
            throw new IOException("Only journaled changes may close a receive attempt");
        }
        Files.delete(file);
    }

    private static boolean allowed(Phase previous, Phase next) {
        return (previous == Phase.NONE && next == Phase.SNAPSHOT_READY)
            || (previous == Phase.SNAPSHOT_READY && next == Phase.UPDATE_ATTEMPTED)
            || (previous == Phase.UPDATE_ATTEMPTED && next == Phase.UPDATE_COMPLETED)
            || (previous == Phase.UPDATE_COMPLETED && next == Phase.CHANGES_JOURNALED);
    }
}
