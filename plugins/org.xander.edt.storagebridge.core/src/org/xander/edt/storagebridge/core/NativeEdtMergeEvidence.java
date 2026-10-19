package org.xander.edt.storagebridge.core;

import java.util.Objects;

/**
 * Thread-safe record of ALL native EDT per-comparison process status events.
 * A late MERGE_PROCESS_DISCARDED/UNKNOWN must never erase an earlier
 * MERGE_PROCESS_FINISHED. No byte-level or filesystem comparisons are done.
 */
public final class NativeEdtMergeEvidence {
    public record Snapshot(String lastStatus, boolean finished,
            boolean cancelled, boolean mergeStarted) {
        /** EDT itself, not a closing dialog or manual approval, confirms Merge. */
        public boolean appliedByEdt() {
            return finished && !cancelled;
        }
    }

    private String last = "UNKNOWN";
    private boolean finished;
    private boolean cancelled;
    private boolean started;

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
            case "COMPARISON_MERGE_PROCESS_CANCELLED",
                 "MERGE_PROCESS_USING_EXTERNAL_TOOL_CANCELED" -> {
                if (!finished) cancelled = true;
            }
            default -> { /* Native comparison and discard statuses. */ }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(last, finished, cancelled, started);
    }
}
