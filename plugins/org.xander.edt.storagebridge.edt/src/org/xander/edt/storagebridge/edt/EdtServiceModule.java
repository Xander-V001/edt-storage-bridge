package org.xander.edt.storagebridge.edt;

import org.eclipse.core.runtime.Plugin;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.wiring.AbstractServiceAwareModule;

/** Public EDT bindings only; storage intermediary is unrelated to EDT infobases. */
public final class EdtServiceModule extends AbstractServiceAwareModule {
    public EdtServiceModule(Plugin plugin) {
        super(plugin);
    }

    @Override
    protected void doConfigure() {
        bind(IConfigurationProvider.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IDtComparisonEditorInputFactory.class).toService();
    }
}
