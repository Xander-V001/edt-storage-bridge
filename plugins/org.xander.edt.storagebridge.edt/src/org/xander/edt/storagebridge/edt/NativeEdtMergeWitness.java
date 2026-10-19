package org.xander.edt.storagebridge.edt;

import java.util.Objects;
import org.xander.edt.storagebridge.core.NativeEdtMergeEvidence;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessHandle;
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
    private boolean closed;

    private NativeEdtMergeWitness(IComparisonManager manager,
            IComparisonManager.ICompareMergeStatusListener listener,
            NativeEdtMergeEvidence evidence) {
        this.manager = manager;
        this.listener = listener;
        this.evidence = evidence;
    }

    public static NativeEdtMergeWitness observe(
            IComparisonManager manager, ComparisonProcessHandle handle) {
        Objects.requireNonNull(manager);
        Objects.requireNonNull(handle);
        var state = new NativeEdtMergeEvidence();
        var listener = new IComparisonManager.ICompareMergeStatusListener() {
            @Override
            public void statusChanged(ComparisonProcessHandle updated,
                    ComparisonProcessStatus status) {
                if (handle.equals(updated) && status != null) {
                    state.observe(status.name());
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
            if (current != null) state.observe(current.name());
        } catch (RuntimeException ex) {
            // Not started yet, or EDT has no current session.
        }
        return new NativeEdtMergeWitness(manager, listener, state);
    }

    /** Marker when the EDT public manager cannot be connected. */
    public static NativeEdtMergeWitness unavailable() {
        return new NativeEdtMergeWitness(null, null,
            new NativeEdtMergeEvidence());
    }

    public NativeEdtMergeEvidence.Snapshot snapshot() {
        return evidence.snapshot();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (manager != null && listener != null) {
                manager.removeStatusListener(listener);
            }
        }
    }
}
