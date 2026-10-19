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
