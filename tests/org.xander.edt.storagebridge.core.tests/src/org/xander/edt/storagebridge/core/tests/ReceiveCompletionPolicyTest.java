package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveCompletionPolicy;
import org.xander.edt.storagebridge.core.ReceiveCompletionPolicy.Next;

public final class ReceiveCompletionPolicyTest {
    @Test
    public void exactFullMergeMarksAppliedEvenWithNoObjectDelta() {
        assertEquals(Next.FULLY_APPLIED, ReceiveCompletionPolicy.decide(
            true, true, true, Set.of(), Set.of()));
    }

    @Test
    public void localCommittedChangesNeedExplicitReview() {
        assertEquals(Next.REQUIRE_USER_CONFIRMATION, ReceiveCompletionPolicy.decide(
            true, true, false, Set.of("Document.Local"), Set.of("Document.Local")));
    }

    @Test
    public void fullComparisonWithLocalOnlyEdtChangesNeedsExplicitReview() {
        assertEquals(Next.REQUIRE_USER_CONFIRMATION, ReceiveCompletionPolicy.decide(
            true, true, false, Set.of(), Set.of()));
    }

    @Test
    public void skippedChangeNeverAdvancesCompleteRevision() {
        assertEquals(Next.PARTIAL, ReceiveCompletionPolicy.decide(
            true, true, false, Set.of("Document.Unreviewed"), Set.of("Document.Other")));
    }

    @Test
    public void newerHeadCannotBeSilentlyAcknowledgedFromOlderSnapshot() {
        assertEquals(Next.UNPROVEN_SOURCE, ReceiveCompletionPolicy.decide(
            true, false, true, Set.of(), Set.of()));
    }

    @Test
    public void editorCloseOrCancelledMergeIsNotAcceptance() {
        assertEquals(Next.NOT_MERGED, ReceiveCompletionPolicy.decide(
            false, true, true, Set.of(), Set.of()));
    }
}
