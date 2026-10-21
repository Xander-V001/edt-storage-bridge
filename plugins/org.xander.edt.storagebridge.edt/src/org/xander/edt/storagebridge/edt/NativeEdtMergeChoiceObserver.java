package org.xander.edt.storagebridge.edt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.MergeSettings;

/**
 * Experimental native EDT merge-settings observer, READ-ONLY.
 *
 * The public EDT comparison session is disposed after Merge. Subscribe during
 * comparison and sample settings through EDT's own read-only BM transaction
 * BEFORE merge begins. A last-second settings change may race with Merge, so
 * all observations are DIAGNOSTIC and are NEVER treated as a durable per-node
 * acceptance receipt. This class never alters a setting, merges objects,
 * reads EDT files, or performs any byte comparisons.
 */
public final class NativeEdtMergeChoiceObserver implements AutoCloseable {
    private final IComparisonManager manager;
    private final ComparisonProcessHandle handle;
    private final IComparisonSession.IComparisonTreeListener listener;
    private final Job captureJob;
    private volatile IComparisonSession session;
    private volatile boolean closed;
    private volatile boolean mergeStarted;
    private volatile String summary = "снимок недоступен; решения UNKNOWN";
    private long events;

    public NativeEdtMergeChoiceObserver(IComparisonManager manager,
            ComparisonProcessHandle handle) {
        this.manager = manager;
        this.handle = handle;
        this.captureJob = new Job("Storage Bridge: read EDT merge selection (readonly)") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                capture();
                return Status.OK_STATUS;
            }
        };
        captureJob.setSystem(true);
        this.listener = changes -> {
            synchronized (NativeEdtMergeChoiceObserver.this) {
                if (!closed && !mergeStarted) {
                    events += changes == null ? 1 : changes.size();
                    captureJob.schedule(80L);
                }
            }
        };
    }

    public synchronized void onStatus(String status) {
        if (closed) return;
        if ("COMPARISON_PROCESS_FINISHED".equals(status)) {
            try {
                if (session == null) {
                    IComparisonSession found = manager.getComparisonSession(handle);
                    if (found != null) {
                        found.addComparisonTreeListener(listener);
                        session = found;
                    }
                }
                captureJob.schedule(80L);
            } catch (RuntimeException | LinkageError ignored) {
                // Diagnostics must NEVER prevent EDT Merge or close PENDING.
            }
        } else if ("MERGE_PROCESS_STARTED".equals(status)
                || "MERGE_PROCESS_VALIDATION_STARTED".equals(status)
                || "MERGE_PROCESS_USING_EXTERNAL_TOOL_STARTED".equals(status)
                || "MERGE_PROCESS_FINISHED".equals(status)
                || "COMPARISON_MERGE_PROCESS_CANCELLED".equals(status)) {
            mergeStarted = true;
            captureJob.cancel();
        }
    }

    private void capture() {
        IComparisonSession active = session;
        if (active == null || closed || mergeStarted) return;
        try {
            int[] counts = active.runComparisonTreeReadonlyTask(new IBmTask<int[]>() {
                @Override public Object getId() { return null; }
                @Override public String getName() {
                    return "Read EDT comparison tree merge settings";
                }
                @Override public Object getServiceId() { return null; }
                @Override public int[] execute(IBmTransaction tx,
                        IProgressMonitor monitor) {
                    if (!tx.isReadOnly()) {
                        throw new IllegalStateException("EDT comparison model not read-only");
                    }
                    int changed = 0, excluded = 0, local = 0, custom = 0, nested = 0;
                    Set<String> owners = new HashSet<>();
                    Collection<Long> ids = active.getNodesWithChangedMergeSettings();
                    for (Long id : ids) {
                        if (id == null || monitor.isCanceled()) break;
                        ComparisonNode node = active.getNode(id);
                        if (node == null) continue;
                        MergeSettings settings = node.getMergeSettings();
                        if (settings == null) continue;
                        changed++;
                        if (!settings.isMustBeMerged()) excluded++;
                        String rule = String.valueOf(settings.getMergeRule());
                        if ("DO_NOT_MERGE".equals(rule)
                            || "MERGE_PRIORITIZING_MAIN".equals(rule)) local++;
                        if ("CUSTOM_MERGE".equals(rule)
                            || settings.getCustomSettings() != null) custom++;
                        if (settings.isHaveChildrenExcludedFromMerge()) nested++;
                        var top = active.getTopNodeOf(node);
                        if (top != null) {
                            owners.add(Integer.toHexString(System.identityHashCode(top)));
                        }
                    }
                    return new int[]{changed, excluded, local, custom,
                        nested, owners.size()};
                }
            });
            if (!closed && !mergeStarted) {
                summary = "изменённых настроек " + counts[0]
                    + ", исключённых узлов " + counts[1]
                    + ", локальных правил " + counts[2]
                    + ", CUSTOM " + counts[3]
                    + ", вложенных исключений " + counts[4]
                    + ", владельцев " + counts[5]
                    + ", событий дерева " + events
                    + "; снимок до Merge, не квитанция; решения UNKNOWN";
            }
        } catch (RuntimeException | LinkageError ignored) {
            summary = "не удалось прочитать настройки EDT; решения UNKNOWN";
        }
    }

    public String summary() { return summary; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        captureJob.cancel();
        if (session != null) {
            try { session.removeComparisonTreeListener(listener); }
            catch (RuntimeException ignored) { /* EDT session already disposed */ }
            session = null;
        }
    }
}
