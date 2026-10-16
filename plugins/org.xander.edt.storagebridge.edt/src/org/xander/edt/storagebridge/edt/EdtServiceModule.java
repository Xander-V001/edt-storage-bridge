package org.xander.edt.storagebridge.edt;

import org.eclipse.core.runtime.Plugin;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com._1c.g5.v8.dt.internal.compare.ui.editor.IComparisonEditorOpenHelper;
import com._1c.g5.wiring.AbstractServiceAwareModule;

/**
 * EDT DI bindings. IComparisonEditorOpenHelper is a documented EDT internal
 * API without a public equivalent for displaying the native compare editor.
 * This single integration point must be smoke-tested on EDT 2026.2.
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
        bind(IComparisonEditorOpenHelper.class).toService();
    }
}
