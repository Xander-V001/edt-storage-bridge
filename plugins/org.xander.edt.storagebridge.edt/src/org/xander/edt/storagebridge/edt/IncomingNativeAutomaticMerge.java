package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.MergeRule;

/**
 * Native 1C:EDT two-way compare/merge without opening its editor.
 * The OTHER side is the imported classic repository configuration.
 *
 * All changed nodes must be configured for GET_FROM_OTHER and selected
 * before we ask EDT itself to start the asynchronous merge. We never
 * implement a competing filesystem/XML merge and never write the project
 * directly. A missing terminal native event cannot count as success.
 */
public final class IncomingNativeAutomaticMerge {
    private static final int MAX_NODES = 750_000;
    private static final Duration COMPARISON_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration MERGE_TIMEOUT = Duration.ofMinutes(30);

    /** Native merge might still be using the imported XML stage. Do not delete it. */
    public static final class UncertainMergeException extends IOException {
        private static final long serialVersionUID = 1L;
        public UncertainMergeException(String reason) { super(reason); }
        public UncertainMergeException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    public record Result(int differences, boolean merged) {}

    private record Snapshot(long rootId, int differences) {}

    private final IComparisonManager manager;

    public IncomingNativeAutomaticMerge(IComparisonManager manager) {
        this.manager = Objects.requireNonNull(manager);
    }

    public Result run(CompareMergeProcessDescriptor descriptor, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(monitor);
        ComparisonProcessHandle handle = descriptor.getHandle();
        CompareMergeProcessBatch batch = new CompareMergeProcessBatch(descriptor);
        Object signal = new Object();
        final boolean[] compared = { false };
        final boolean[] finished = { false };
        final boolean[] cancelled = { false };
        final String[] lastStatus = { "UNKNOWN" };
        var listener = new IComparisonManager.ICompareMergeStatusListener() {
            @Override public void statusChanged(ComparisonProcessHandle changed,
                    ComparisonProcessStatus status) {
                if (!handle.equals(changed) || status == null) return;
                synchronized (signal) {
                    lastStatus[0] = status.name();
                    switch (status.name()) {
                        case "COMPARISON_PROCESS_FINISHED" -> compared[0] = true;
                        case "MERGE_PROCESS_FINISHED" -> finished[0] = true;
                        case "COMPARISON_MERGE_PROCESS_CANCELLED",
                             "MERGE_PROCESS_USING_EXTERNAL_TOOL_CANCELED",
                             "MERGE_PROCESS_DISCARDED" -> cancelled[0] = true;
                        default -> { }
                    }
                    signal.notifyAll();
                }
            }
            @Override public void statusChanged(CompareMergeProcessBatch current,
                    CompareMergeProcessBatchStatus status) {
                // Per-handle native status, never the batch status, is proof.
            }
        };
        boolean mergeScheduled = false;
        boolean nativeFinished = false;
        manager.addStatusListener(listener);
        try {
            manager.startComparison(batch);
            await(signal, compared, cancelled, lastStatus,
                COMPARISON_TIMEOUT, monitor, "comparison");
            IComparisonSession session = manager.getComparisonSession(handle);
            if (session == null) {
                throw new IOException("Native EDT comparison session was not created");
            }
            Snapshot original = inspect(session, false);
            if (original.differences() == 0) {
                return new Result(0, false);
            }
            // Both operations are official EDT comparison-model APIs and run
            // ONLY after the native comparison completed. Never call them
            // while the comparison engine is still building its tree.
            boolean rulesSet = session.setMergeRuleToSubtree(
                original.rootId(), MergeRule.GET_FROM_OTHER);
            boolean selected = session.setMustBeMerged(original.rootId(), true, true);
            if (!rulesSet || !selected) {
                throw new IOException(
                    "EDT refused accept-all merge settings; open manual comparison");
            }
            // Do not start a partial merge when some native node did not
            // take the remote choice (module procedures, forms, deletions etc).
            Snapshot checked = inspect(session, true);
            if (checked.differences() != original.differences()) {
                throw new IOException("EDT comparison tree changed while selecting changes");
            }
            if (monitor.isCanceled()) throw new InterruptedException();
            IStatus scheduled = manager.startMerge(batch, monitor);
            if (scheduled == null || !scheduled.isOK()) {
                throw new IOException("EDT refused to schedule the native merge");
            }
            mergeScheduled = true;
            try {
                await(signal, finished, cancelled, lastStatus,
                    MERGE_TIMEOUT, monitor, "merge");
                nativeFinished = true;
            } catch (IOException | InterruptedException ex) {
                throw new UncertainMergeException(
                    "Native EDT Merge may still be active; staged files preserved", ex);
            }
            return new Result(checked.differences(), true);
        } catch (RuntimeException | LinkageError ex) {
            if (mergeScheduled && !nativeFinished) {
                throw new UncertainMergeException(
                    "Native EDT Merge state is uncertain; staged files preserved", ex);
            }
            throw new IOException("Native EDT automatic merge failed", ex);
        } finally {
            try { manager.removeStatusListener(listener); }
            catch (RuntimeException ignored) { }
            if (!mergeScheduled || nativeFinished) {
                try { manager.stop(handle); }
                catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * The comparison model belongs to EDT's BM engine. Read it exclusively
     * through the supported read-only task, never by naked EMF traversal.
     */
    private static Snapshot inspect(IComparisonSession session, boolean checkRules)
            throws IOException {
        try {
            return session.runComparisonTreeReadonlyTask(new IBmTask<Snapshot>() {
                @Override public Object getId() { return null; }
                @Override public Object getServiceId() { return null; }
                @Override public String getName() {
                    return "Validate complete automatic incoming EDT selection";
                }
                @Override public Snapshot execute(IBmTransaction tx,
                        IProgressMonitor monitor) {
                    if (!tx.isReadOnly())
                        throw new IllegalStateException("EDT tree task is not readonly");
                    ComparisonNode root = session.getRootNode();
                    if (root == null) throw new IllegalStateException(
                        "Native EDT comparison has no tree root");
                    var stack = new ArrayDeque<ComparisonNode>();
                    stack.push(root);
                    int visited = 0, changed = 0;
                    while (!stack.isEmpty()) {
                        ComparisonNode node = stack.pop();
                        if (++visited > MAX_NODES) {
                            throw new IllegalStateException("Native comparison is too large");
                        }
                        var children = node.<ComparisonNode>getChildren();
                        boolean childHasDiff = false;
                        for (int i = 0; i < children.size(); i++) {
                            ComparisonNode child = children.get(i);
                            if (child == null) {
                                throw new IllegalStateException("Null native comparison node");
                            }
                            var f = child.getComparisonFlags();
                            if (f != null && f.hasDiffsMainOther()) childHasDiff = true;
                            stack.push(child);
                        }
                        var flags = node.getComparisonFlags();
                        if (node != root && !childHasDiff && flags != null
                                && flags.hasDiffsMainOther()) {
                            changed++;
                            if (checkRules) {
                                var merge = node.getMergeSettings();
                                if (merge == null || !merge.isMustBeMerged()
                                        || merge.getMergeRule() != MergeRule.GET_FROM_OTHER) {
                                    throw new IllegalStateException(
                                        "Native EDT did not select all remote differences");
                                }
                            }
                        }
                    }
                    if (changed == 0) {
                        var rootFlags = root.getComparisonFlags();
                        if (rootFlags != null && rootFlags.hasDiffsMainOther()) {
                            throw new IllegalStateException(
                                "Unclassified native root differences cannot be auto-merged");
                        }
                    }
                    return new Snapshot(root.bmGetId(), changed);
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            throw new IOException("Native EDT comparison tree selection unavailable", ex);
        }
    }

    private static void await(Object signal, boolean[] done, boolean[] cancelled,
            String[] lastStatus, Duration timeout, IProgressMonitor monitor,
            String phase) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (signal) {
            while (!done[0]) {
                if (cancelled[0]) {
                    throw new IOException("EDT native " + phase
                        + " cancelled; status=" + lastStatus[0]);
                }
                if (monitor.isCanceled()) throw new InterruptedException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Native EDT " + phase
                        + " timed out; status=" + lastStatus[0]);
                }
                TimeUnit.NANOSECONDS.timedWait(signal,
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)));
            }
        }
    }
}
