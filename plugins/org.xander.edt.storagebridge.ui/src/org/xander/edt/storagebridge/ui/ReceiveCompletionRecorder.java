package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.osgi.service.prefs.BackingStoreException;

/**
 * The ONLY write boundary for finishing a received EDT merge.
 *
 * Precondition: the coordinator has witnessed native EDT completion, checked
 * Git branch/HEAD, finished a read-only audit, and (for review rather than
 * equality) obtained explicit developer confirmation.
 *
 * Crash-safe ordering: remove only proven/explicitly-reviewed pending names,
 * then persist the reviewed revision, then the stricter applied revision,
 * then mark the initial full review complete. A failure may require a retry,
 * but cannot claim a HEAD that was not reviewed.
 */
public final class ReceiveCompletionRecorder {
    private ReceiveCompletionRecorder() {}

    public static int partial(IProject project, String repository, String intermediary,
            String branch, Set<String> exactlyVerified)
            throws BackingStoreException {
        clearExact(project, repository, intermediary, branch, exactlyVerified);
        return exactlyVerified.size();
    }

    /**
     * Every presented changed owner matches native 1C content, but the
     * comparison covered only the change set, not the whole configuration.
     * Never write APPLIED from this method.
     */
    public static int reviewedExactScope(IProject project, String repository,
            String intermediary, String branch, long revision,
            Set<String> exactlyVerified) throws BackingStoreException {
        requireCompleteCheckpoint(project, repository, intermediary, branch,
            revision, exactlyVerified);
        clearExact(project, repository, intermediary, branch, exactlyVerified);
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
        return exactlyVerified.size();
    }

    public static int fullyApplied(IProject project, String repository,
            String intermediary, String branch, long revision,
            Set<String> exactlyVerified, ReceiveBaselineJournal journal,
            boolean initialReview) throws IOException, BackingStoreException {
        requireCompleteCheckpoint(project, repository, intermediary, branch,
            revision, exactlyVerified);
        clearExact(project, repository, intermediary, branch, exactlyVerified);
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
        ReceiveRevisionStore.recordApplied(
            project, repository, intermediary, branch, revision);
        if (initialReview) {
            journal.markInitialReviewPrepared();
        }
        return exactlyVerified.size();
    }

    public static int reviewedWithLocalDifferences(IProject project,
            String repository, String intermediary, String branch,
            long revision, Set<String> exactlyVerified,
            Set<String> deliberatelyReviewedPending,
            Set<String> presentedPending, ReceiveBaselineJournal journal,
            boolean initialReview) throws IOException, BackingStoreException {
        if (!presentedPending.containsAll(deliberatelyReviewedPending)) {
            throw new IllegalArgumentException(
                "Cannot mark unpresented metadata identities reviewed");
        }
        var current = ReceivePendingStore.get(
            project, repository, branch, intermediary);
        if (!current.containsAll(exactlyVerified)
                || !current.containsAll(deliberatelyReviewedPending)) {
            throw new IllegalStateException(
                "Receive pending journal changed since last merge audit");
        }
        var accepted = new LinkedHashSet<>(deliberatelyReviewedPending);
        accepted.addAll(exactlyVerified);
        if (!current.equals(accepted)) {
            throw new IllegalStateException(
                "Cannot record a complete review while changes remain outside it");
        }
        requireCheckpointVersion(project, repository, intermediary, branch,
            revision);
        // Both groups are known to be drawn from the same native comparison.
        // One has byte-equality proof; the other has explicit user acceptance.
        clearExact(project, repository, intermediary, branch, exactlyVerified);
        if (!deliberatelyReviewedPending.isEmpty()) {
            ReceivePendingStore.acknowledgeReviewed(project, repository, branch,
                intermediary, deliberatelyReviewedPending);
        }
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
        // Deliberate preservation of local files is NOT a full applied HEAD.
        if (initialReview) {
            journal.markInitialReviewPrepared();
        }
        return accepted.size();
    }

    private static void requireCompleteCheckpoint(IProject project,
            String repository, String intermediary, String branch,
            long revision, Set<String> exactlyVerified)
            throws BackingStoreException {
        requireCheckpointVersion(project, repository, intermediary, branch, revision);
        var pending = ReceivePendingStore.get(
            project, repository, branch, intermediary);
        if (!pending.equals(exactlyVerified)) {
            throw new IllegalStateException(
                "Complete review scope and pending objects changed before persistence");
        }
    }

    /** Validate all version rules before clearing the first pending object. */
    private static void requireCheckpointVersion(IProject project,
            String repository, String intermediary, String branch,
            long revision) {
        if (revision < 1 || ReceiveRevisionStore.observed(
                project, repository, intermediary) != revision
                || ReceiveRevisionStore.reviewed(
                    project, repository, intermediary, branch) > revision
                || ReceiveRevisionStore.applied(
                    project, repository, intermediary, branch) > revision) {
            throw new IllegalStateException(
                "Repository HEAD or checkpoint changed before receive persistence");
        }
    }

    private static void clearExact(IProject project, String repository,
            String intermediary, String branch, Set<String> exactlyVerified)
            throws BackingStoreException {
        if (!exactlyVerified.isEmpty()) {
            ReceivePendingStore.clearVerified(project, repository, branch,
                intermediary, exactlyVerified);
        }
    }
}
