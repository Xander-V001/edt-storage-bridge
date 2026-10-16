package org.xander.edt.storagebridge.edt;

import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.ides.monitoring.IMonitoringEventDispatcher;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.ui.infobases.sync.InfobaseUpdateDialogBasedCallback;
import com.google.inject.Inject;

/**
 * EDT -> existing project receive adapter using the platform's OWN interactive
 * compare/merge callback. Does not checkout Git branches or perform file copies.
 *
 * This module must be compiled and smoke-tested on EDT 2026.2 before enabling
 * receive on a working infobase.
 */
public final class EdtReceiveService {
    private final IInfobaseManager infobases;
    private final IInfobaseSynchronizationManager synchronization;
    private final IInfobaseSynchronizationStateManager states;
    private final IV8ProjectManager projects;
    private final IDtComparisonEditorInputFactory compareEditors;
    private final IComparisonManager comparisons;
    private final IMonitoringEventDispatcher monitoring;
    private final IWorkspaceOrchestrator workspaces;
    private final IQualifiedNameFilePathConverter names;

    @Inject
    public EdtReceiveService(
            IInfobaseManager infobases,
            IInfobaseSynchronizationManager synchronization,
            IInfobaseSynchronizationStateManager states,
            IV8ProjectManager projects,
            IDtComparisonEditorInputFactory compareEditors,
            IComparisonManager comparisons,
            IMonitoringEventDispatcher monitoring,
            IWorkspaceOrchestrator workspaces,
            IQualifiedNameFilePathConverter names) {
        this.infobases = Objects.requireNonNull(infobases);
        this.synchronization = Objects.requireNonNull(synchronization);
        this.states = Objects.requireNonNull(states);
        this.projects = Objects.requireNonNull(projects);
        this.compareEditors = Objects.requireNonNull(compareEditors);
        this.comparisons = Objects.requireNonNull(comparisons);
        this.monitoring = Objects.requireNonNull(monitoring);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.names = Objects.requireNonNull(names);
    }

    public IInfobaseManager infobaseManager() {
        return infobases;
    }

    /**
     * Must be checked BEFORE running Designer, which can mutate the linked IB.
     * Known sync history alone is not sufficient: connection must still exist.
     */
    public boolean hasKnownBaseline(IProject project, InfobaseReference infobase) {
        return synchronization.isConnected(project, infobase)
            && states.hasSynchronizationInfo(project, infobase)
            && !states.isFlowActive(infobase);
    }

    /**
     * Create the native UI callback on the SWT UI thread, before scheduling the
     * receiving Job. EDT itself opens a compare/merge dialog and handles changes.
     * The callback is single-use: do not share it between receive attempts.
     *
     * IMPORTANT: never enable the "overwrite remote IB with EDT" choice.
     */
    public IInfobaseChangesResolver newInteractiveMerge(Shell shell) {
        Objects.requireNonNull(shell, "shell");
        if (shell.isDisposed()
                || Thread.currentThread() != shell.getDisplay().getThread()) {
            throw new IllegalStateException("Must initialize EDT merge on SWT UI thread");
        }
        var callback = new InfobaseUpdateDialogBasedCallback(
            shell, projects, compareEditors, comparisons, monitoring,
            workspaces, names, states);
        callback.setAllowOverrideConflict(false);
        return callback;
    }

    /**
     * Pull from the ALREADY connected IB into the EXISTING project.
     * The native callback controls object selection and merge, not our code.
     * No checkpoint advances here; compare editor may complete asynchronously.
     */
    public InfobaseChangesResolutionResult receive(
            IProject project, InfobaseReference infobase,
            IInfobaseChangesResolver interactiveMerge,
            IProgressMonitor monitor) throws Exception {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(interactiveMerge, "interactiveMerge");
        Objects.requireNonNull(monitor, "monitor");
        if (!hasKnownBaseline(project, infobase)) {
            throw new IllegalStateException(
                "Для проекта и выбранной ИБ нет подтверждённого состояния синхронизации EDT.");
        }
        return synchronization.retrieveInfobaseChanges(
            project, infobase, interactiveMerge, false, monitor);
    }
}
