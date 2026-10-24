package org.xander.edt.storagebridge.ui;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.xander.edt.storagebridge.edt.EdtServiceModule;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * Eclipse plug-in lifecycle. Guice injector is lazy and scoped to the plug-in.
 */
public final class BridgeActivator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.xander.edt.storagebridge.ui";
    private static volatile BridgeActivator instance;
    private Injector injector;

    public static BridgeActivator getDefault() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        injector = null;
        super.stop(context);
    }

    public synchronized Injector getInjector() {
        if (injector == null) {
            injector = Guice.createInjector(new EdtServiceModule(this));
        }
        return injector;
    }
}
