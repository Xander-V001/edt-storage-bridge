package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingSafetyGate;

/**
 * Read-only outbound preparation must not be blocked by unrelated project
 * status. No Designer write is authorized by this assessment.
 */
public final class OutgoingSafetyGateTest {
    @Test
    public void unknownGlobalAppliedCheckpointDoesNotBlockSelectingObjects() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, -1, 0, null));
        assertTrue(result.mayPrepareSelection());
        assertTrue(result.notices().contains(
            OutgoingSafetyGate.Notice.PROJECT_WIDE_APPLIED_REVISION_UNKNOWN));
    }

    @Test
    public void remoteRevisionAdvanceRequiresObjectConflictCheckNotGlobalRefusal() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29406, 29405, 0, true));
        assertTrue(result.mayPrepareSelection());
        assertTrue(result.notices().contains(
            OutgoingSafetyGate.Notice.PROJECT_WIDE_REVISIONS_DIFFER));
    }

    @Test
    public void unknownVersionReportCanStillPrepareSelectedObjectPreview() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(-1, 29405, 0, true));
        assertTrue(result.mayPrepareSelection());
        assertTrue(result.notices().contains(
            OutgoingSafetyGate.Notice.HEAD_REPORT_UNAVAILABLE));
    }

    @Test
    public void pendingIncomingAndDirtyGitWarnButDoNotPreventReview() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 2, false));
        assertTrue(result.mayPrepareSelection());
        assertTrue(result.notices().contains(
            OutgoingSafetyGate.Notice.OTHER_INCOMING_CHANGES_MAY_EXIST));
        assertTrue(result.notices().contains(
            OutgoingSafetyGate.Notice.LOCAL_GIT_CHANGES_MAY_BE_INCLUDED));
    }

    @Test
    public void noWarningsWhenProjectWideStatusIsConsistent() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 0, true));
        assertTrue(result.mayPrepareSelection());
        assertTrue(result.notices().isEmpty());
    }

    @Test
    public void unknownGitDirtyStatusDoesNotFalselyClaimDirty() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 0, null));
        assertFalse(result.notices().contains(
            OutgoingSafetyGate.Notice.LOCAL_GIT_CHANGES_MAY_BE_INCLUDED));
    }

    @Test
    public void impossiblePendingCountRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingSafetyGate.Evidence(29405, 29405, -1, null));
    }
}
