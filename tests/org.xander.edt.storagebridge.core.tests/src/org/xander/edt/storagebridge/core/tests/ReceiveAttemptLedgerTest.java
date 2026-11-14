package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;

public final class ReceiveAttemptLedgerTest {
    @Test
    public void stateSurvivesRestartAndPreventsUnexpectedRetry() throws Exception {
        Path root = Files.createTempDirectory("receive-ledger-test-");
        var before = new ReceiveAttemptLedger(root);
        assertEquals(Phase.NONE, before.read());
        before.transition(Phase.NONE, Phase.SNAPSHOT_READY);
        assertEquals(Phase.SNAPSHOT_READY, new ReceiveAttemptLedger(root).read());
        before.transition(Phase.SNAPSHOT_READY, Phase.UPDATE_ATTEMPTED);
        assertEquals(Phase.UPDATE_ATTEMPTED, new ReceiveAttemptLedger(root).read());
        assertThrows(IOException.class, () ->
            new ReceiveAttemptLedger(root).transition(
                Phase.SNAPSHOT_READY, Phase.UPDATE_ATTEMPTED));
        assertThrows(IllegalArgumentException.class, () ->
            before.transition(Phase.UPDATE_ATTEMPTED, Phase.CHANGES_JOURNALED));
        before.transition(Phase.UPDATE_ATTEMPTED, Phase.UPDATE_COMPLETED);
        before.transition(Phase.UPDATE_COMPLETED, Phase.CHANGES_JOURNALED);
        before.finishJournaled();
        assertEquals(Phase.NONE, new ReceiveAttemptLedger(root).read());
    }

    @Test
    public void failedOrUnknownOperationCannotBeSilentlyAcknowledged() throws Exception {
        Path root = Files.createTempDirectory("receive-ledger-unknown-");
        var ledger = new ReceiveAttemptLedger(root);
        ledger.transition(Phase.NONE, Phase.SNAPSHOT_READY);
        assertThrows(IOException.class, ledger::finishJournaled);
        ledger.transition(Phase.SNAPSHOT_READY, Phase.UPDATE_ATTEMPTED);
        assertThrows(IOException.class, ledger::finishJournaled);
        assertEquals(Phase.UPDATE_ATTEMPTED, new ReceiveAttemptLedger(root).read());
    }

    @Test
    public void corruptJournalFailsClosed() throws Exception {
        Path root = Files.createTempDirectory("receive-ledger-corrupt-");
        Files.writeString(root.resolve("receive-phase.txt"), "bogus");
        assertThrows(IOException.class, () -> new ReceiveAttemptLedger(root).read());
    }
}
