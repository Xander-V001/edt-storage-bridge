package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveCompletionPolicy;
import org.xander.edt.storagebridge.core.ReceiveCompletionPolicy.Next;

public final class ReceiveCompletionPolicyTest {
    @Test
    public void versionOnlyRepositoryCommitCanAdvanceFromReviewedNativeBaseline() {
        org.junit.Assert.assertTrue(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                29432, 29432, 29434, 29434, 29434, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                -1, 29432, 29434, 29434, 29434, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                29432, 29431, 29434, 29434, 29434, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                29432, 29432, 29434, 29434, 29435, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                29432, 29432, 29434, 29434, 29434, false, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                29432, 29432, 29434, 29434, 29434, true, false));
    }

    @Test
    public void recoveredNoDeltaReusesReviewedNativeInventoryWithoutAnotherUpdate() {
        org.junit.Assert.assertTrue(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29437, true, true, true));
        // Repository can receive a comment-only revision; no metadata
        // changes means no EDT import and no repeated Designer update.
        org.junit.Assert.assertTrue(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29438, true, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29438, false, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29438, true, false, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29438, true, true, false));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29436, 29438, true, true, true));
        org.junit.Assert.assertFalse(
            ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                29437, 29437, 29436, true, true, true));
    }

    @Test
    public void exactFullMergeMarksAppliedEvenWithNoObjectDelta() {
        assertEquals(Next.FULLY_APPLIED, ReceiveCompletionPolicy.decide(
            true, true, true, true, true, 1, Set.of(), Set.of()));
    }

    @Test
    public void exactSelectiveMergeIsReviewedButNeverFullyApplied() {
        assertEquals(Next.REVIEWED_EXACT_SCOPE, ReceiveCompletionPolicy.decide(
            true, true, false, true, true, 1, Set.of(), Set.of()));
        assertEquals(Next.REVIEWED_EXACT_SCOPE, ReceiveCompletionPolicy.decide(
            true, true, false, true, true, 1, Set.of(), Set.of("Document.Payroll")));
    }

    @Test
    public void noOwnersAuditedDoesNotProofFullMirror() {
        // Even an apparently complete full comparison cannot accept a
        // repository version when not one metadata owner was audited.
        assertEquals(Next.AUDIT_INCOMPLETE, ReceiveCompletionPolicy.decide(
            true, true, true, true, true, 0, Set.of(), Set.of()));
    }

    @Test
    public void missingNativeOwnerInFullEdtImportBlocksReview() {
        assertEquals(Next.SOURCE_INCOMPLETE, ReceiveCompletionPolicy.decide(
            true, true, false, false, true, 15, Set.of(), Set.of()));
    }

    @Test
    public void localCommittedChangesNeedExplicitReview() {
        assertEquals(Next.REQUIRE_USER_CONFIRMATION, ReceiveCompletionPolicy.decide(
            true, true, true, true, false, 1, Set.of("Document.Local"), Set.of("Document.Local")));
    }

    @Test
    public void fullComparisonWithLocalOnlyEdtChangesNeedsExplicitReview() {
        assertEquals(Next.REQUIRE_USER_CONFIRMATION, ReceiveCompletionPolicy.decide(
            true, true, true, true, false, 1, Set.of(), Set.of()));
    }

    @Test
    public void skippedChangeNeverAdvancesCompleteRevision() {
        assertEquals(Next.PARTIAL, ReceiveCompletionPolicy.decide(
            true, true, false, true, false, 1, Set.of("Document.Unreviewed"), Set.of("Document.Other")));
    }

    @Test
    public void newerHeadCannotBeSilentlyAcknowledgedFromOlderSnapshot() {
        assertEquals(Next.UNPROVEN_SOURCE, ReceiveCompletionPolicy.decide(
            true, false, true, true, true, 1, Set.of(), Set.of()));
    }

    @Test
    public void editorCloseOrCancelledMergeIsNotAcceptance() {
        assertEquals(Next.NOT_MERGED, ReceiveCompletionPolicy.decide(
            false, true, true, true, true, 1, Set.of(), Set.of()));
    }
}
