package org.xander.edt.storagebridge.edt;

import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.google.inject.Inject;

/**
 * Pull changes into an EXISTING EDT project using supported EDT sync API.
 *
 * <p>Never initializes a new base association, never pushes project changes
 * into the infobase and never chooses "override infobase" conflict policy.
 */
public final class EdtReceiveService {
    private final IInfobaseManager infobases;
    private final IInfobaseSynchronizationManager synchronization;
    private final IInfobaseSynchronizationStateManager states;

    @Inject
    public EdtReceiveService(IInfobaseManager infobases,
            IInfobaseSynchronizationManager synchronization,
            IInfobaseSynchronizationStateManager states) {
        this.infobases = Objects.requireNonNull(infobases);
        this.synchronization = Objects.requireNonNull(synchronization);
        this.states = Objects.requireNonNull(states);
    }

    public IInfobaseManager infobaseManager() {
        return infobases;
    }

    /** Fail closed before the caller mutates its already connected 1C infobase. */
    public boolean hasKnownBaseline(IProject project, InfobaseReference infobase) {
        return states.hasSynchronizationInfo(project, infobase)
            && !states.isFlowActive(infobase);
    }

    public InfobaseChangesResolutionResult receive(IProject project,
            InfobaseReference infobase, IProgressMonitor monitor) throws Exception {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(monitor, "monitor");
        if (!hasKnownBaseline(project, infobase)) {
            throw new IllegalStateException(
                "Для проекта и выбранной ИБ нет подтверждённого состояния синхронизации EDT.");
        }
        return synchronization.retrieveInfobaseChanges(project, infobase,
            new IInfobaseChangesResolver() {
                @Override
                public InfobaseConflictResolutionResult resolveInfobaseChanges(
                        IProject target, InfobaseReference source,
                        Set<EObject> edtAddedObjects, Set<EObject> edtModifiedObjects,
                        IInfobaseConfigurationChange changes,
                        IInfobaseUpdateConflictResolver conflictResolver,
                        IInfobaseUpdateConflictResolver.IConflictResolveAssist assist,
                        IInfobaseSynchronizationFlow flow,
                        IProgressMonitor current) throws com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException {
                    // Divergent EDT changes are NOT overwritten. An approved
                    // interactive merge will be added after real EDT 2026.2 test.
                    if (!edtAddedObjects.isEmpty() || !edtModifiedObjects.isEmpty()) {
                        return InfobaseConflictResolutionResult.DEFERRED;
                    }
                    // Delegate actual import to EDT's own merge machinery.
                    return conflictResolver.resolveConflict(target, source,
                        edtAddedObjects, edtModifiedObjects, changes, assist,
                        flow, current);
                }
            }, false, monitor);
    }
}
