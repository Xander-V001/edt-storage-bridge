package org.xander.edt.storagebridge.edt;

import java.util.Objects;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.google.inject.Inject;

/**
 * Read-only facade over the supported 1C:EDT configuration model provider.
 *
 * <p>This class deliberately does not mutate, save or export the model. It
 * separates EDT-specific APIs from the synchronization engine and UI.
 * A null provider result means "model unavailable", not "not an EDT project".
 */
public final class EdtProjectInspector {

    private final IConfigurationProvider configurationProvider;

    @Inject
    public EdtProjectInspector(IConfigurationProvider configurationProvider) {
        this.configurationProvider = Objects.requireNonNull(configurationProvider);
    }

    /**
     * Checks whether EDT currently exposes a configuration model for the project.
     * This call may involve model loading and must run off the SWT UI thread.
     */
    public boolean hasConfigurationModel(IProject project) {
        Objects.requireNonNull(project, "project");
        Configuration configuration = configurationProvider.getConfiguration(project);
        return configuration != null;
    }
}
