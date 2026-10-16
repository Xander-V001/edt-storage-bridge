package org.xander.edt.storagebridge.ui;

import org.eclipse.core.runtime.Plugin;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.wiring.AbstractServiceAwareModule;

/** Uses public 1C:EDT OSGi services through the documented Guice bridge. */
public final class BridgeServiceModule extends AbstractServiceAwareModule {

    public BridgeServiceModule(Plugin plugin) {
        super(plugin);
    }

    @Override
    protected void doConfigure() {
        bind(IConfigurationProvider.class).toService();
    }
}
