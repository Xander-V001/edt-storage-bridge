package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingSafetyGate;

/** No IDE, Git, native 1C executable, or user's storage is used. */
public final class OutgoingSafetyGateTest {
    @Test
    public void acceptedNativeRevisionAloneCannotEnablePublishing() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 0,
                true, false, false));
        assertFalse(result.prerequisitesSatisfied());
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.VERIFIED_CONTENT_BASELINE_MISSING));
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.ISOLATED_VALIDATION_MISSING));
    }

    @Test
    public void genuineNewStorageVersionBlocksEvenWithCleanGitAndBaseline() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29406, 29405, 0,
                true, true, true));
        assertFalse(result.prerequisitesSatisfied());
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.REVISION_DIVERGED));
    }

    @Test
    public void unknownNativeHeadCannotBeReplacedByXmlVersionToken() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(-1, 29405, 0,
                true, true, true));
        assertFalse(result.prerequisitesSatisfied());
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.NATIVE_HEAD_UNKNOWN));
    }

    @Test
    public void unknownAppliedRevisionNeverCountsAsReady() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, -1, 0,
                true, true, true));
        assertFalse(result.prerequisitesSatisfied());
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.APPLIED_REVISION_UNKNOWN));
    }

    @Test
    public void incomingChangesOrDirtyGitIndependentlyBlockPublishing() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 2,
                false, true, true));
        assertFalse(result.prerequisitesSatisfied());
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.INCOMING_PENDING));
        assertTrue(result.blockers().contains(
            OutgoingSafetyGate.Blocker.GIT_NOT_CLEAN));
    }

    @Test
    public void onlyAllPrerequisitesProvenCanPassThePureGate() {
        var result = OutgoingSafetyGate.evaluate(
            new OutgoingSafetyGate.Evidence(29405, 29405, 0,
                true, true, true));
        assertTrue(result.prerequisitesSatisfied());
        assertTrue(result.blockers().isEmpty());
    }

    @Test
    public void snapshotValidationRejectsImpossibleNegativePendingCount() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingSafetyGate.Evidence(29405, 29405, -1,
                true, true, true));
    }
}
