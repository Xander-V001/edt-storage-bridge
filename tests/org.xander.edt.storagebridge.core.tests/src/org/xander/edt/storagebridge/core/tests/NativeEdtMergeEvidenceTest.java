package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.xander.edt.storagebridge.core.NativeEdtMergeEvidence;

public final class NativeEdtMergeEvidenceTest {
    @Test
    public void retainsCompletedMergeAfterEdtDiscardsSession() {
        var recorder = new NativeEdtMergeEvidence();
        recorder.observe("COMPARISON_PROCESS_FINISHED");
        recorder.observe("MERGE_PROCESS_STARTED");
        recorder.observe("MERGE_PROCESS_FINISHED");
        recorder.observe("MERGE_PROCESS_DISCARDED");
        var state = recorder.snapshot();
        assertTrue(state.finished());
        assertTrue(state.mergeStarted());
        assertFalse(state.cancelled());
        assertEquals("MERGE_PROCESS_DISCARDED", state.lastStatus());
        assertTrue(state.discarded());
        assertTrue(state.canReleaseStaging());
    }

    @Test
    public void closingEditorOrConfirmingSettingsDoesNotApplyNativeMerge() {
        var recorder = new NativeEdtMergeEvidence();
        recorder.observe("COMPARISON_PROCESS_FINISHED");
        recorder.observe("MERGE_PROCESS_DISCARDED");
        assertFalse(recorder.snapshot().appliedByEdt());

        var actuallyApplied = new NativeEdtMergeEvidence();
        actuallyApplied.observe("MERGE_PROCESS_STARTED");
        actuallyApplied.observe("MERGE_PROCESS_FINISHED");
        actuallyApplied.observe("MERGE_PROCESS_DISCARDED");
        assertTrue(actuallyApplied.snapshot().appliedByEdt());
    }

    @Test
    public void mergeCompletingAfterEditorCloseIsStillObserved() throws Exception {
        var events = new NativeEdtMergeEvidence();
        var producer = new Thread(() -> {
            try {
                Thread.sleep(80);
                events.observe("MERGE_PROCESS_STARTED");
                Thread.sleep(80);
                events.observe("MERGE_PROCESS_FINISHED");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        var result = events.awaitAfterEditorClose(400, 1000, () -> false);
        producer.join();
        assertTrue(result.appliedByEdt());
        assertTrue(result.canReleaseStaging());
    }

    @Test
    public void unknownMergeStatusAfterClosingCannotReleaseSource() throws Exception {
        var events = new NativeEdtMergeEvidence();
        events.observe("COMPARISON_PROCESS_FINISHED");
        var result = events.awaitAfterEditorClose(30, 100, () -> false);
        assertFalse(result.appliedByEdt());
        assertFalse(result.canReleaseStaging());
    }

    @Test
    public void cancellationOrJustComparisonIsNotMerge() {
        var recorder = new NativeEdtMergeEvidence();
        recorder.observe("COMPARISON_PROCESS_FINISHED");
        assertFalse(recorder.snapshot().finished());
        recorder.observe("COMPARISON_MERGE_PROCESS_CANCELLED");
        assertTrue(recorder.snapshot().cancelled());
        assertFalse(recorder.snapshot().finished());
    }

    @Test
    public void externalToolCancelIsNotSuccess() {
        var recorder = new NativeEdtMergeEvidence();
        recorder.observe("MERGE_PROCESS_USING_EXTERNAL_TOOL_STARTED");
        recorder.observe("MERGE_PROCESS_USING_EXTERNAL_TOOL_CANCELED");
        assertTrue(recorder.snapshot().mergeStarted());
        assertFalse(recorder.snapshot().finished());
        assertTrue(recorder.snapshot().cancelled());
    }
}
