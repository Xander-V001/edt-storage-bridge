package org.xander.edt.storagebridge.edt;

import org.eclipse.core.runtime.Plugin;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.ides.monitoring.IMonitoringEventDispatcher;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;

/**
 * EDT public-service bindings are owned by the EDT adapter, not the workbench UI.
 */
public final class EdtServiceModule extends AbstractServiceAwareModule {

    public EdtServiceModule(Plugin plugin) {
        super(plugin);
    }

    @Override
    protected void doConfigure() {
        bind(IConfigurationProvider.class).toService();
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseSynchronizationManager.class).toService();
        bind(IInfobaseSynchronizationStateManager.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IDtComparisonEditorInputFactory.class).toService();
        bind(IComparisonManager.class).toService();
        bind(IMonitoringEventDispatcher.class).toService();
        bind(IWorkspaceOrchestrator.class).toService();
        bind(IQualifiedNameFilePathConverter.class).toService();
    }
}
