package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

/**
 * Captures changed native EDT identities from an ALREADY OPENED, readonly
 * comparison. It never computes file deltas, compares bytes, sets merge
 * rules or starts a merge. No export/publish is permitted without a
 * completed comparison session and an immutable native evidence snapshot.
 */
public final class OutgoingNativeComparisonReview implements AutoCloseable {
    private static final int MAX_NODES = 500_000;
    private static final int MAX_IDENTITIES = 10_000;

    private record Item(ComparisonNode node, String identity) {}

    private final IComparisonManager manager;
    private final ComparisonProcessHandle handle;
    private final IComparisonManager.ICompareMergeStatusListener listener;
    private final CountDownLatch captured = new CountDownLatch(1);
    private boolean started;
    private boolean closed;
    private volatile List<String> identities;
    private volatile IOException failure;

    public OutgoingNativeComparisonReview(IComparisonManager manager,
            ComparisonProcessHandle handle) {
        this.manager = Objects.requireNonNull(manager);
        this.handle = Objects.requireNonNull(handle);
        this.listener = new IComparisonManager.ICompareMergeStatusListener() {
            @Override public void statusChanged(ComparisonProcessHandle current,
                    ComparisonProcessStatus status) {
                if (handle.equals(current) && status != null) {
                    if ("COMPARISON_PROCESS_FINISHED".equals(status.name())) {
                        scheduleCapture();
                    }
                }
            }
            @Override public void statusChanged(CompareMergeProcessBatch batch,
                    CompareMergeProcessBatchStatus status) {
                // Only the specific native comparison handle is authoritative.
            }
        };
        manager.addStatusListener(listener);
        try {
            var status = manager.getStatus(handle);
            if (status != null && "COMPARISON_PROCESS_FINISHED".equals(status.name())) {
                scheduleCapture();
            }
        } catch (RuntimeException ignored) {
            // The editor will start the native process after subscription.
        }
    }

    private synchronized void scheduleCapture() {
        if (closed || started) return;
        started = true;
        Job job = new Job("1С: Чтение фактических отличий из штатного EDT") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    IComparisonSession session = manager.getComparisonSession(handle);
                    if (session == null) {
                        throw new IOException("EDT comparison session is unavailable");
                    }
                    List<String> found = session.runComparisonTreeReadonlyTask(
                        new IBmTask<List<String>>() {
                            @Override public Object getId() { return null; }
                            @Override public Object getServiceId() { return null; }
                            @Override public String getName() {
                                return "Read native outgoing comparison changes";
                            }
                            @Override public List<String> execute(
                                    IBmTransaction tx, IProgressMonitor ignored) {
                                if (!tx.isReadOnly()) {
                                    throw new IllegalStateException(
                                        "Native comparison tree task is not readonly");
                                }
                                ComparisonNode root = session.getRootNode();
                                if (root == null) {
                                    throw new IllegalStateException(
                                        "Native EDT comparison has no root");
                                }
                                Set<String> result = new LinkedHashSet<>();
                                var stack = new ArrayDeque<Item>();
                                stack.push(new Item(root, null));
                                int visited = 0;
                                boolean foundNativeDiff = false;
                                while (!stack.isEmpty()) {
                                    if (monitor.isCanceled()) {
                                        throw new IllegalStateException(
                                            "Native comparison review cancelled");
                                    }
                                    Item item = stack.pop();
                                    ComparisonNode node = item.node();
                                    if (++visited > MAX_NODES) {
                                        throw new IllegalStateException(
                                            "Native comparison tree exceeds limit");
                                    }
                                    var flags = node.getComparisonFlags();
                                    boolean differs = flags != null
                                        && flags.hasDiffsMainOther();
                                    String identity = item.identity();
                                    if (node instanceof TopComparisonNode top) {
                                        String symlink = top.getMainSymlink();
                                        if (symlink == null || symlink.isBlank()) {
                                            symlink = top.getOtherSymlink();
                                        }
                                        if (symlink != null && !symlink.isBlank()) {
                                            identity = symlink;
                                        }
                                    }
                                    List<ComparisonNode> children =
                                        new ArrayList<>(node.<ComparisonNode>getChildren());
                                    boolean hasChangedChild = false;
                                    for (ComparisonNode child : children) {
                                        var childFlags = child.getComparisonFlags();
                                        if (childFlags != null
                                                && childFlags.hasDiffsMainOther()) {
                                            hasChangedChild = true;
                                        }
                                    }
                                    // A parent flagged due to differing descendants
                                    // is not a separate change to Configuration.
                                    if (differs && !hasChangedChild
                                            && node != root) {
                                        foundNativeDiff = true;
                                        if (identity == null || identity.isBlank()) {
                                            throw new IllegalStateException(
                                                "Changed native node has no metadata symlink");
                                        }
                                        result.add(identity);
                                        if (result.size() > MAX_IDENTITIES) {
                                            throw new IllegalStateException(
                                                "Too many changed native identities");
                                        }
                                    }
                                    // Do not prune using aggregate parent flags:
                                    // native descendants can carry a semantic
                                    // change even if the parent has no diff flag.
                                    for (int i = children.size() - 1; i >= 0; i--) {
                                        stack.push(new Item(children.get(i), identity));
                                    }
                                }
                                if (!foundNativeDiff) {
                                    var rootFlags = root.getComparisonFlags();
                                    if (rootFlags != null
                                            && rootFlags.hasDiffsMainOther()) {
                                        throw new IllegalStateException(
                                            "Native root has differences not classified into metadata objects");
                                    }
                                    return List.of();
                                }
                                return List.copyOf(result);
                            }
                        });
                    if (found == null) {
                        throw new IOException("Native comparison returned no result");
                    }
                    identities = List.copyOf(found);
                    return Status.OK_STATUS;
                } catch (Exception | LinkageError ex) {
                    failure = new IOException(
                        "Could not obtain complete native EDT comparison differences", ex);
                    return new Status(IStatus.ERROR,
                        "org.xander.edt.storagebridge.edt",
                        "Outgoing native comparison evidence unavailable", ex);
                } finally {
                    captured.countDown();
                }
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Must be called by the cleanup background Job, never on SWT UI thread. */
    public List<String> awaitNativeIdentities() throws IOException, InterruptedException {
        if (!captured.await(30, TimeUnit.SECONDS)) {
            throw new IOException("Native EDT comparison never completed or was closed early");
        }
        if (failure != null) throw failure;
        if (identities == null) {
            throw new IOException("Native EDT comparison evidence is incomplete");
        }
        return identities;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { manager.removeStatusListener(listener); }
        catch (RuntimeException ignored) { /* EDT may already dispose the manager */ }
    }
}
