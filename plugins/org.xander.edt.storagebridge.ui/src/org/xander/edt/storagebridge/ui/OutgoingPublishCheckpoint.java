package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.osgi.service.prefs.BackingStoreException;

import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.OutgoingOperationJournal;

/**
 * Saves durable publication checkpoints AFTER native Commit was independently
 * verified and journal.verified() made the operation non-retryable.
 *
 * Updating just "observed" makes the developer repeat incoming receive for
 * their OWN newly published revision. This method attests a fresh native
 * ConfigDumpInfo with the new HEAD, then records reviewed and published Git
 * baselines. Never marks the full project as byte-identically APPLIED.
 *
 * Failure only invalidates/reduces local proofs. Native Commit is NOT retried.
 */
public final class OutgoingPublishCheckpoint {
    private OutgoingPublishCheckpoint() {}

    public static boolean save(IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline git,
            long newRevision, ChangeDetectionRequest probe,
            OutgoingOperationJournal journal,
            IProgressMonitor monitor) throws InterruptedException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(git);
        Objects.requireNonNull(probe);
        Objects.requireNonNull(journal);
        Objects.requireNonNull(monitor);
        String source = settings.infobaseType().name() + ":"
            + settings.infobaseAddress().trim();
        try {
            // A full native inventory is only a metadata-version snapshot,
            // NOT a potentially destructive full XML import.
            try (var snapshot = new ConfigVersionSnapshotRunner().capture(
                    probe, monitor::isCanceled)) {
                var receive = new ReceiveBaselineJournal(project,
                    settings.address(), git.branch(), source);
                receive.recordScannedInfo(snapshot.configDumpInfo());
                receive.recordScannedRevision(newRevision);
            }
            journal.event("CHECKPOINT", "native-inventory-and-head-attested");
            ReceiveRevisionStore.recordObserved(project, settings.address(),
                source, newRevision);
            ReceiveRevisionStore.recordReviewed(project, settings.address(),
                source, git.branch(), newRevision);
            journal.event("CHECKPOINT", "repository-version-reviewed");
            OutgoingPublishedGitStore.recordVerifiedPublication(project,
                settings.address(), source, git.branch(), git.head(), newRevision);
            journal.event("CHECKPOINT", "git-publication-baseline-written");
            return true;
        } catch (IOException | BackingStoreException | IllegalStateException ex) {
            try {
                journal.event("CHECKPOINT_WARNING",
                    "native-commit-verified local-proof-failed="
                    + ex.getClass().getSimpleName());
            } catch (IOException ignored) {
                // The main journal already records a confirmed native write.
            }
            return false;
        }
    }
}
