package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.Preferences;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;

/**
 * Opt-in, backed-up reset of receive CHECKPOINTS for one project/branch/source.
 * Never changes connection settings, passwords, EDT Git files, the infobase
 * or classical repository. No automatic reset on ordinary receive failures.
 */
public final class ReceiveStateMaintenance {
    private static final String ROOT = "org.xander.edt.storagebridge.ui";

    private ReceiveStateMaintenance() {}

    public static Path archiveAndReset(IProject project,
            StorageProjectSettings.Snapshot settings, String branch)
            throws Exception {
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        if (branch == null || branch.isBlank()
                || project.getLocationURI() == null
                || settings.address().isBlank()
                || settings.infobaseAddress().isBlank()) {
            throw new IOException("Нет точной привязки проекта, ветки и хранилища");
        }
        // Reuse the existing exclusion to prevent receive/merge/reset races.
        try (AutoCloseable guard = ReceiveChangesCoordinator.acquireOutgoingReview(
                project, settings)) {
            String intermediary = settings.infobaseType().name() + ":"
                + settings.infobaseAddress().trim();
            var journal = new ReceiveBaselineJournal(
                project, settings.address(), branch, intermediary);
            // A legacy/corrupt intermediary fingerprint is exactly one
            // reason a user might need a reset. Read ONLY the phase ledger,
            // without requiring the old source fingerprint to be trusted.
            if (new ReceiveAttemptLedger(journal.stateDirectory()).read()
                    != Phase.NONE) {
                throw new IOException("Остался журнал незавершённого обновления 1С. "
                    + "Сброс запрещён до проверки состояния базы-посредника.");
            }
            Preferences root = InstanceScope.INSTANCE.getNode(ROOT);
            String projectUri = project.getLocationURI().normalize().toString();
            String pendingKey = digest(projectUri + "\u0000"
                + settings.address().trim() + "\u0000" + branch);
            String sourceKey = digest(project.getLocationURI().normalize()
                + "\u0000" + settings.address().trim()
                + "\u0000" + intermediary.trim());
            Preferences pendingParent = root.node("pendingReceive");
            Preferences pending = pendingParent.node(pendingKey);
            Preferences revisionsParent = root.node("repositoryRevisions");
            Preferences revision = revisionsParent.node(sourceKey);
            Preferences branches = revision.node("branches");
            Preferences branchRevisions = branches.node(digest(branch));
            Path backup = BridgeActivator.getDefault().getStateLocation()
                .toFile().toPath().resolve("receive-reset-backups")
                .resolve(UUID.randomUUID().toString());
            Files.createDirectories(backup);
            // Never write Secure Storage settings or passwords into a backup.
            savePreferences(backup.resolve("pending.txt"), pending);
            savePreferences(backup.resolve("source-revisions.txt"), revision);
            savePreferences(backup.resolve("branch-revisions.txt"), branchRevisions);
            Path oldFiles = journal.stateDirectory();
            if (Files.exists(oldFiles)) {
                if (!Files.isDirectory(oldFiles) || Files.isSymbolicLink(oldFiles)) {
                    throw new IOException("Небезопасный каталог состояния получения");
                }
                Files.move(oldFiles, backup.resolve("receive-files"));
            }
            // Keep connection and Secure Storage preferences untouched.
            pending.removeNode();
            pendingParent.flush();
            branchRevisions.removeNode();
            branches.flush();
            // OBSERVED is shared across branches, but can be reacquired from
            // native read-only 1C report; never preserve a stale display HEAD.
            revision.remove("observedLatestRevision");
            revision.flush();
            return backup.toAbsolutePath();
        }
    }

    private static void savePreferences(Path target, Preferences prefs)
            throws Exception {
        StringBuilder s = new StringBuilder();
        for (String key : prefs.keys()) {
            s.append(key).append('=').append(prefs.get(key, ""))
                .append('\n');
        }
        Files.writeString(target, s.toString(), StandardCharsets.UTF_8);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
