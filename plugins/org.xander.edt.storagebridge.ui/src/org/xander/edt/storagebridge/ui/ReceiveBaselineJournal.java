package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.eclipse.core.resources.IProject;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;

/**
 * Crash-recoverable ConfigDumpInfo.xml snapshot from BEFORE 1C storage update.
 * Do not discard the snapshot after a failed or uncertain Designer operation.
 *
 * Local Eclipse state storage only; NOT in project/Git. A stale snapshot allows
 * -getChanges to recover updated objects after EDT or Designer crashes.
 */
public final class ReceiveBaselineJournal {
    private final Path baselineFile;
    private final Path lastScannedFile;
    private final ReceiveAttemptLedger ledger;
    private final Path intermediaryFingerprintFile;
    private final String intermediaryFingerprint;

    public ReceiveBaselineJournal(IProject project, String storageAddress,
            String branch, String intermediaryIdentity) throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(storageAddress);
        Objects.requireNonNull(branch);
        Objects.requireNonNull(intermediaryIdentity);
        if (project.getLocationURI() == null || storageAddress.isBlank()
                || branch.isBlank() || intermediaryIdentity.isBlank()
                || BridgeActivator.getDefault() == null) {
            throw new IOException("Cannot identify receiving project/context");
        }
        String identity = project.getLocationURI().normalize() + "\u0000"
            + storageAddress.trim() + "\u0000" + branch;
        Path workspaceState = BridgeActivator.getDefault()
            .getStateLocation().toFile().toPath().toAbsolutePath().normalize();
        Path folder = workspaceState.resolve("receive").resolve(digest(identity));
        this.baselineFile = folder.resolve("previous-ConfigDumpInfo.xml");
        this.lastScannedFile = folder.resolve("last-scanned-ConfigDumpInfo.xml");
        this.ledger = new ReceiveAttemptLedger(folder);
        this.intermediaryFingerprintFile = folder.resolve("intermediary.sha256");
        this.intermediaryFingerprint = digest(intermediaryIdentity.trim());
    }

    public boolean exists() throws IOException {
        if (!Files.exists(baselineFile)) {
            return false;
        }
        if (!Files.isRegularFile(baselineFile)
                || Files.size(baselineFile) < 20) {
            throw new IOException("Stored ConfigDumpInfo is invalid; recovery required");
        }
        return true;
    }

    public Phase phase() throws IOException {
        verifyIntermediary();
        return ledger.read();
    }

    /**
     * The identity must not change while a previous snapshot or pending
     * changes exist. A v24 snapshot without this marker is an unknown source,
     * not evidence that it belongs to a newly configured intermediary.
     */
    public void verifyIntermediary() throws IOException {
        if (Files.exists(intermediaryFingerprintFile)) {
            if (!Files.isRegularFile(intermediaryFingerprintFile)
                    || !intermediaryFingerprint.equals(
                        Files.readString(intermediaryFingerprintFile,
                            StandardCharsets.US_ASCII).trim())) {
                throw new IOException(
                    "База-посредник изменена относительно сохранённого "
                    + "получения. Для продолжения требуется ручная сверка.");
            }
        } else if (Files.exists(baselineFile) || Files.exists(lastScannedFile)
                || ledger.read() != Phase.NONE) {
            throw new IOException(
                "Осталось получение прежней версии плагина без "
                + "подтверждённой базы-посредника. Требуется ручная сверка.");
        }
    }

    /**
     * Last successfully INVENTORIED remote configuration, not the last
     * fully applied EDT revision. The object-level pending journal is part of
     * this checkpoint: omitted/rejected objects remain pending indefinitely.
     */
    public Path lastScannedInfo() throws IOException {
        verifyIntermediary();
        if (!Files.exists(lastScannedFile)) {
            return null;
        }
        ConfigDumpInfoIndex.exportableNames(lastScannedFile);
        return lastScannedFile;
    }

    /**
     * Must run AFTER persisting every newly discovered remote object to the
     * pending store. If the process crashes before this atomic replacement,
     * the next scan may repeat objects but never loses any.
     */
    public void recordScannedInfo(Path currentInfo) throws IOException {
        verifyIntermediary();
        ConfigDumpInfoIndex.exportableNames(currentInfo);
        Files.createDirectories(lastScannedFile.getParent());
        Path partial = lastScannedFile.resolveSibling("last-scanned.part");
        try {
            Files.copy(currentInfo, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, lastScannedFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(partial, lastScannedFile,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    public void markUpdateAttempted() throws IOException {
        if (!exists()) {
            throw new IOException("Cannot update 1C without durable baseline");
        }
        ledger.transition(Phase.SNAPSHOT_READY, Phase.UPDATE_ATTEMPTED);
    }

    public void markUpdateCompleted() throws IOException {
        ledger.transition(Phase.UPDATE_ATTEMPTED, Phase.UPDATE_COMPLETED);
    }

    /**
     * A crash after journaling but before cleanup must not cause another
     * update. Only the already persisted pending list can be acknowledged.
     */
    public void finishAlreadyJournaled() throws IOException {
        if (ledger.read() != Phase.CHANGES_JOURNALED) {
            throw new IOException("Receive result is not durably journaled");
        }
        Files.deleteIfExists(baselineFile);
        ledger.finishJournaled();
    }

    public Path path() throws IOException {
        if (!exists()) {
            throw new IOException("No stored ConfigDumpInfo baseline");
        }
        return baselineFile;
    }

    /** Must be called BEFORE mutating the connected intermediary infobase. */
    public void persist(Path versionFile) throws IOException {
        Objects.requireNonNull(versionFile);
        verifyIntermediary();
        if (exists()) {
            // Never overwrite a snapshot before pending changes were journaled:
            // it might be the only record of remote changes after a crash.
            throw new IOException("Unfinished receive snapshot already exists");
        }
        Files.createDirectories(baselineFile.getParent());
        // Only a SHA-256 fingerprint is persisted, never an IB address or
        // repository password. Do not silently rebind an existing journal.
        if (!Files.exists(intermediaryFingerprintFile)) {
            Files.writeString(intermediaryFingerprintFile,
                intermediaryFingerprint + "\n", StandardCharsets.US_ASCII);
        }
        Path partial = baselineFile.resolveSibling("previous-ConfigDumpInfo.part");
        try {
            Files.copy(versionFile, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, baselineFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(partial, baselineFile);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        // A snapshot with no phase marker (including from an old installation)
        // is UNKNOWN; never assume no Designer mutation happened.
        ledger.transition(Phase.NONE, Phase.SNAPSHOT_READY);
    }

    /**
     * Call ONLY after all newly discovered objects have been committed to
     * ReceivePendingStore. Pending changes survive even when not selected.
     */
    public void acknowledgeJournaledChanges() throws IOException {
        // The caller must flush ALL pending objects before invoking this.
        ledger.transition(Phase.UPDATE_COMPLETED, Phase.CHANGES_JOURNALED);
        finishAlreadyJournaled();
    }

    private static String digest(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
