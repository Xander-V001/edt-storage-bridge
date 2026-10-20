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
 * Safety of checkpoint updates: require a verified native HEAD and a
 * completed EDT merge BEFORE any persistence. In the new selective-review
 * path, persist the explicit REVIEWED checkpoint before clearing PENDING
 * identities, so a crash never silently loses the only list of unresolved
 * changes. Other legacy completion paths have separate stricter proofs;
 * their multi-node preference writes are not a single atomic transaction.
 */
public final class ReceiveCompletionRecorder {
    private ReceiveCompletionRecorder() {}

    /** A native 1C revision containing no object delta needs no EDT merge. */
    public static void reviewedNoChanges(IProject project, String repository,
            String intermediary, String branch, long revision)
            throws BackingStoreException {
        requireCheckpointVersion(project, repository, intermediary, branch, revision);
        if (!ReceivePendingStore.get(
                project, repository, branch, intermediary).isEmpty()) {
            throw new IllegalStateException(
                "Cannot advance empty-delta version while changes remain pending");
        }
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
    }

    /**
     * Native EDT confirmed a FINISHED global comparison/merge and the 1C HEAD
     * was attested. There are NO pending native changes to classify, and the
     * full 1C metadata inventory was present in the imported EDT source.
     *
     * A byte-identical mirror is NOT implied by EDT's merge status: its
     * serializer may produce different .mdo/.distr bytes. Therefore this
     * records REVIEWED only, never APPLIED or artificial KEPT_LOCAL entries.
     * It also avoids reading the entire 1C/EDT resource tree a second time.
     */
    public static void reviewedGlobalNativeMerge(IProject project,
            String repository, String intermediary, String branch,
            long revision, ReceiveBaselineJournal journal,
            boolean initialReview) throws IOException, BackingStoreException {
        requireCheckpointVersion(project, repository, intermediary, branch, revision);
        if (!ReceivePendingStore.get(project, repository, branch,
                intermediary).isEmpty()) {
            throw new IllegalStateException(
                "Cannot acknowledge an unreviewed native change by global merge alone");
        }
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
        if (initialReview) {
            journal.markInitialReviewPrepared();
        }
    }

    public static int partial(IProject project, String repository, String intermediary,
            String branch, Set<String> exactlyVerified)
            throws BackingStoreException {
        clearExact(project, repository, intermediary, branch, exactlyVerified);
        return exactlyVerified.size();
    }

    /**
     * Native EDT finished a selective merge. The developer explicitly
     * confirmed that EVERY native 1C change in this pending batch was shown
     * and considered. We cannot infer the choice of individual checkboxes
     * from EDT's serialized .mdo/.distr bytes.
     *
     * A reviewed native revision can advance even if some remote changes
     * were intentionally left local. This NEVER writes APPLIED or fabricates
     * a KEPT_LOCAL decision for an unidentified object.
     */
    public static int reviewedNativeSelective(IProject project,
            String repository, String intermediary, String branch,
            long revision, Set<String> presentedPending)
            throws BackingStoreException {
        requireCheckpointVersion(project, repository, intermediary, branch, revision);
        var current = ReceivePendingStore.get(
            project, repository, branch, intermediary);
        if (!current.equals(presentedPending)) {
            throw new IllegalStateException(
                "Not all pending changes were present in native EDT comparison");
        }
        // Once native Merge and the complete presented scope were confirmed
        // by the developer, REVIEWED is the authoritative checkpoint.
        // Persist it BEFORE removing pending names: if the process dies
        // between preference flushes, stale PENDING can be safely reviewed
        // again, while deleting PENDING first could irrevocably lose the
        // only trace of an unfinished checkpoint.
        ReceiveRevisionStore.recordReviewed(
            project, repository, intermediary, branch, revision);
        if (!current.isEmpty()) {
            ReceivePendingStore.acknowledgeNativeReviewedScope(
                project, repository, branch, intermediary, current);
        }
        return current.size();
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
        // Full native-project equality supersedes all older decisions
        // to retain a locally different remote metadata identity.
        ReceivePendingStore.clearKeptLocalAfterGlobalEquality(
            project, repository, branch, intermediary);
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
