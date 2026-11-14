package org.xander.edt.storagebridge.ui;

import org.osgi.framework.Bundle;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Injector;

/** Creates Eclipse command handlers in the official EDT Guice environment. */
public final class BridgeExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    @Override
    protected Bundle getBundle() {
        return BridgeActivator.getDefault().getBundle();
    }

    @Override
    protected Injector getInjector() {
        return BridgeActivator.getDefault().getInjector();
    }
}
