package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.EdtMergeAcceptance;

public final class EdtMergeAcceptanceTest {
    @Test
    public void nativeEdtDecisionClosesAllPresentedWithoutComparingBslBytes() {
        var decision = EdtMergeAcceptance.evaluate(
            Set.of("Document.Payroll", "Document.Payroll.ManagerModule",
                "Document.Payroll.Form.Main"),
            Set.of("Document.Payroll", "Document.Payroll.ManagerModule",
                "Document.Payroll.Form.Main"),
            true, true);
        assertEquals(3, decision.acknowledged().size());
        assertTrue(decision.remaining().isEmpty());
        assertTrue(decision.allReviewed());
    }

    @Test
    public void editorCloseOrCancelledMergeNeverDiscardsPendingChanges() {
        var decision = EdtMergeAcceptance.evaluate(
            Set.of("Document.Payroll"), Set.of("Document.Payroll"),
            false, true);
        assertTrue(decision.acknowledged().isEmpty());
        assertEquals(Set.of("Document.Payroll"), decision.remaining());
        assertFalse(decision.allReviewed());
    }

    @Test
    public void missingRepositoryHeadProofDoesNotClaimReviewedRevision() {
        var decision = EdtMergeAcceptance.evaluate(
            Set.of("Document.Payroll"), Set.of("Document.Payroll"),
            true, false);
        assertTrue(decision.acknowledged().isEmpty());
        assertFalse(decision.allReviewed());
    }

    @Test
    public void omittedRemoteObjectsStayPendingAfterPartialReview() {
        var decision = EdtMergeAcceptance.evaluate(
            Set.of("Document.Payroll", "Catalog.People",
                "Configuration"),
            Set.of("Document.Payroll"),
            true, true);
        assertEquals(Set.of("Document.Payroll"), decision.acknowledged());
        assertEquals(Set.of("Catalog.People", "Configuration"),
            decision.remaining());
        assertFalse(decision.allReviewed());
    }
}
