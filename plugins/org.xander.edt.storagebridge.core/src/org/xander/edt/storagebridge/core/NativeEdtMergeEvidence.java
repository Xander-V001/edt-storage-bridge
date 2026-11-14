package org.xander.edt.storagebridge.core;

import java.util.Objects;

/**
 * Thread-safe record of ALL native EDT per-comparison process status events.
 * A late MERGE_PROCESS_DISCARDED/UNKNOWN must never erase an earlier
 * MERGE_PROCESS_FINISHED. No byte-level or filesystem comparisons are done.
 */
public final class NativeEdtMergeEvidence {
    public record Snapshot(String lastStatus, boolean finished,
            boolean cancelled, boolean mergeStarted, boolean discarded) {
        /**
         * EDT has released the comparison session, finished all post-merge
         * actions, or explicitly cancelled the operation.
         */
        public boolean canReleaseStaging() {
            return finished || cancelled || discarded;
        }

        /** EDT itself, not a closing dialog or manual approval, confirms Merge. */
        public boolean appliedByEdt() {
            return finished && !cancelled;
        }
    }

    private String last = "UNKNOWN";
    private boolean finished;
    private boolean cancelled;
    private boolean started;
    private boolean discarded;

    public synchronized void observe(String status) {
        Objects.requireNonNull(status);
        last = status;
        switch (status) {
            case "MERGE_PROCESS_STARTED",
                 "MERGE_PROCESS_USING_EXTERNAL_TOOL_STARTED",
                 "MERGE_PROCESS_VALIDATION_STARTED" -> started = true;
            case "MERGE_PROCESS_FINISHED" -> {
                started = true;
                finished = true;
            }
            case "MERGE_PROCESS_DISCARDED" -> discarded = true;
            case "COMPARISON_MERGE_PROCESS_CANCELLED",
                 "MERGE_PROCESS_USING_EXTERNAL_TOOL_CANCELED" -> {
                if (!finished) cancelled = true;
            }
            default -> { /* Native comparison status. */ }
        }
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(last, finished, cancelled, started, discarded);
    }

    /**
     * Wait ON A BACKGROUND ECLIPSE JOB until EDT's asynchronous native merge
     * has finished or its comparison was discarded/cancelled. Closing an SWT
     * editor is not a completion signal. This uses only observed EDT events
     * and must never be called on the SWT thread.
     *
     * No merge started: allow a short grace interval for EDT's async job
     * scheduling. Once started: wait up to the long limit, never indefinitely.
     */
    public synchronized Snapshot awaitAfterEditorClose(
            long graceMilliseconds, long mergeTimeoutMilliseconds,
            java.util.function.BooleanSupplier canceled)
            throws InterruptedException {
        Objects.requireNonNull(canceled);
        if (graceMilliseconds < 1 || mergeTimeoutMilliseconds < graceMilliseconds) {
            throw new IllegalArgumentException("Invalid EDT merge wait limits");
        }
        long begun = System.nanoTime();
        while (!finished && !cancelled && !discarded) {
            if (canceled.getAsBoolean()) {
                throw new InterruptedException("EDT merge wait cancelled");
            }
            long timeout = (started ? mergeTimeoutMilliseconds
                : graceMilliseconds);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - begun);
            if (elapsed >= timeout) break;
            wait(Math.min(250L, Math.max(1L, timeout - elapsed)));
        }
        return snapshot();
    }
}
