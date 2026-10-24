package org.xander.edt.storagebridge.edt;

import org.eclipse.core.runtime.Plugin;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.wiring.AbstractServiceAwareModule;

/**
 * EDT DI bindings use exported EDT service APIs. Native comparison is opened
 * with an exported input factory and the Eclipse editor extension registry.
 * Runtime smoke test on EDT 2026.2 is still required.
 */
public final class EdtServiceModule extends AbstractServiceAwareModule {
    public EdtServiceModule(Plugin plugin) {
        super(plugin);
    }

    @Override
    protected void doConfigure() {
        bind(IConfigurationProvider.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IImportServiceRegistry.class).toService();
        bind(IQualifiedNameFilePathConverter.class).toService();
        bind(IDtComparisonEditorInputFactory.class).toService();
    }
}
