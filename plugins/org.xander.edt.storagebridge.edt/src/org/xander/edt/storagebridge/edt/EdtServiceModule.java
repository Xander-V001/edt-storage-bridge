package org.xander.edt.storagebridge.edt;

import org.eclipse.core.runtime.Plugin;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
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
    }
}
