package org.xander.edt.storagebridge.edt;

import java.util.Objects;
import org.xander.edt.storagebridge.core.NativeEdtMergeEvidence;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;

/**
 * Capture the PUBLIC native EDT merge status events starting BEFORE the
 * editor starts its comparison. getStatus() on partClosed is insufficient:
 * EDT may discard its session as soon as the merge/editor finishes, causing
 * getStatus() to return null and losing evidence of MERGE_PROCESS_FINISHED.
 *
 * Never starts/stops/merges a process. Just listens and unregisters.
 */
public final class NativeEdtMergeWitness implements AutoCloseable {
    private final IComparisonManager manager;
    private final IComparisonManager.ICompareMergeStatusListener listener;
    private final NativeEdtMergeEvidence evidence;
    private final NativeEdtMergeChoiceObserver choices;
    private boolean closed;

    private NativeEdtMergeWitness(IComparisonManager manager,
            IComparisonManager.ICompareMergeStatusListener listener,
            NativeEdtMergeEvidence evidence,
            NativeEdtMergeChoiceObserver choices) {
        this.manager = manager;
        this.listener = listener;
        this.evidence = evidence;
        this.choices = choices;
    }

    public static NativeEdtMergeWitness observe(
            IComparisonManager manager, ComparisonProcessHandle handle) {
        Objects.requireNonNull(manager);
        Objects.requireNonNull(handle);
        var state = new NativeEdtMergeEvidence();
        var choices = new NativeEdtMergeChoiceObserver(manager, handle);
        var listener = new IComparisonManager.ICompareMergeStatusListener() {
            @Override
            public void statusChanged(ComparisonProcessHandle updated,
                    ComparisonProcessStatus status) {
                if (handle.equals(updated) && status != null) {
                    state.observe(status.name());
                    choices.onStatus(status.name());
                }
            }

            @Override
            public void statusChanged(CompareMergeProcessBatch batch,
                    CompareMergeProcessBatchStatus status) {
                // Per-handle notifications are the authoritative evidence.
            }
        };
        manager.addStatusListener(listener);
        try {
            ComparisonProcessStatus current = manager.getStatus(handle);
            if (current != null) {
                state.observe(current.name());
                choices.onStatus(current.name());
            }
        } catch (RuntimeException ex) {
            // Not started yet, or EDT has no current session.
        }
        return new NativeEdtMergeWitness(manager, listener, state, choices);
    }

    /** Marker when the EDT public manager cannot be connected. */
    public static NativeEdtMergeWitness unavailable() {
        return new NativeEdtMergeWitness(null, null,
            new NativeEdtMergeEvidence(), null);
    }

    public String choiceSummary() {
        return choices == null
            ? "недоступно; решения UNKNOWN" : choices.summary();
    }

    /**
     * Used by receive completion after native MERGE_PROCESS_FINISHED:
     * actual observed node choices, not a secondary user checklist.
     */
    public NativeEdtMergeChoiceObserver.Decision classifyPending(
            java.util.Set<String> pending) {
        if (choices == null) {
            return new NativeEdtMergeChoiceObserver.Decision(
                java.util.Set.of(), java.util.Set.of(), pending);
        }
        return choices.snapshot().classifyPending(pending);
    }

    /**
     * Only after a confirmed global EDT Merge. A missing/incomplete
     * comparison snapshot retains previous KEPT_LOCAL choices; it never
     * produces a speculative empty set.
     */
    public NativeEdtMergeChoiceObserver.KeptLocalReview recheckKeptLocal(
            java.util.Set<String> candidates) {
        if (choices == null) {
            return new NativeEdtMergeChoiceObserver.KeptLocalReview(
                candidates, false);
        }
        return choices.snapshot().recheckKeptLocal(candidates);
    }

    /** Immutable UI-safe rows: native EDT model objects never cross bundles. */
    public java.util.List<String> choiceRows(int limit) {
        if (choices == null) return java.util.List.of(
            "Наблюдатель EDT недоступен: изменения выбора неизвестны.");
        return choices.snapshot().rows(limit);
    }

    public NativeEdtMergeEvidence.Snapshot snapshot() {
        return evidence.snapshot();
    }

    /**
     * Wait in the background after SWT reports editor disposal. EDT's native
     * merge is asynchronous, and we must not drop status events or delete
     * its input stage before MERGE_PROCESS_FINISHED/DISCARDED/CANCELLED.
     */
    public NativeEdtMergeEvidence.Snapshot awaitAfterEditorClose(
            long graceMilliseconds, long mergeTimeoutMilliseconds,
            java.util.function.BooleanSupplier canceled)
            throws InterruptedException {
        return evidence.awaitAfterEditorClose(
            graceMilliseconds, mergeTimeoutMilliseconds, canceled);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (choices != null) choices.close();
            if (manager != null && listener != null) {
                try {
                    manager.removeStatusListener(listener);
                } catch (RuntimeException ignored) {
                    // EDT may have already disposed the comparison service.
                    // Losing a status subscription during cleanup must not
                    // prevent finishing and journalling a native merge.
                }
            }
        }
    }
}
