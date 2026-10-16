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

/**
 * Crash-recoverable ConfigDumpInfo.xml snapshot from BEFORE 1C storage update.
 * Do not discard the snapshot after a failed or uncertain Designer operation.
 *
 * Local Eclipse state storage only; NOT in project/Git. A stale snapshot allows
 * -getChanges to recover updated objects after EDT or Designer crashes.
 */
public final class ReceiveBaselineJournal {
    private final Path baselineFile;

    public ReceiveBaselineJournal(IProject project, String storageAddress,
            String branch) throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(storageAddress);
        Objects.requireNonNull(branch);
        if (project.getLocationURI() == null || storageAddress.isBlank()
                || branch.isBlank() || BridgeActivator.getDefault() == null) {
            throw new IOException("Cannot identify receiving project/context");
        }
        String identity = project.getLocationURI().normalize() + "\u0000"
            + storageAddress.trim() + "\u0000" + branch;
        Path workspaceState = BridgeActivator.getDefault()
            .getStateLocation().toFile().toPath().toAbsolutePath().normalize();
        Path folder = workspaceState.resolve("receive").resolve(digest(identity));
        this.baselineFile = folder.resolve("previous-ConfigDumpInfo.xml");
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

    public Path path() throws IOException {
        if (!exists()) {
            throw new IOException("No stored ConfigDumpInfo baseline");
        }
        return baselineFile;
    }

    /** Must be called BEFORE mutating the connected intermediary infobase. */
    public void persist(Path versionFile) throws IOException {
        Objects.requireNonNull(versionFile);
        if (exists()) {
            // Never overwrite a snapshot before pending changes were journaled:
            // it might be the only record of remote changes after a crash.
            throw new IOException("Unfinished receive snapshot already exists");
        }
        Files.createDirectories(baselineFile.getParent());
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
    }

    /**
     * Call ONLY after all newly discovered objects have been committed to
     * ReceivePendingStore. Pending changes survive even when not selected.
     */
    public void acknowledgeJournaledChanges() throws IOException {
        Files.deleteIfExists(baselineFile);
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
