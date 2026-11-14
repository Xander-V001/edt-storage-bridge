package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveGitStatePolicy;
import org.xander.edt.storagebridge.core.ReceiveGitStatePolicy.Result;

public final class ReceiveGitStatePolicyTest {
    private static final String OLD =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW =
        "fedcba9876543210fedcba9876543210fedcba98";

    @Test
    public void editsWithoutCommitDoNotChangeBranchOrHead() {
        assertEquals(Result.UNCHANGED,
            ReceiveGitStatePolicy.assess("feature/task", OLD,
                "feature/task", OLD, false));
    }

    @Test
    public void sameBranchNewCommitRequiresAncestorProof() {
        assertEquals(Result.ADVANCED_ON_SAME_BRANCH,
            ReceiveGitStatePolicy.assess("feature/task", OLD,
                "feature/task", NEW, true));
    }

    @Test
    public void gitResetOrRebaseIsNotSilentlyAccepted() {
        assertEquals(Result.REWRITTEN_HISTORY,
            ReceiveGitStatePolicy.assess("feature/task", OLD,
                "feature/task", NEW, false));
    }

    @Test
    public void anotherBranchCannotAcknowledgeIncomingChanges() {
        assertEquals(Result.SWITCHED_BRANCH,
            ReceiveGitStatePolicy.assess("feature/task", OLD,
                "develop", NEW, true));
    }

    @Test
    public void unverifiableGitHeadFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            ReceiveGitStatePolicy.assess("feature/task", OLD,
                "feature/task", "", false));
    }
}
