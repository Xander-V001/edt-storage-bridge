package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;

/**
 * Strong check that the infobase registered inside EDT points to EXACTLY
 * the same file directory or server/reference configured for Designer.
 *
 * No remote connection or path mutation is performed. Ambiguous identifiers
 * are always rejected, never accepted through a matching display name.
 */
public final class InfobaseIdentityMatcher {
    private InfobaseIdentityMatcher() {
    }

    public static boolean matches(InfobaseReference selected,
            StorageProjectSettings.InfobaseType type, String configuredAddress) {
        Objects.requireNonNull(type, "type");
        if (selected == null || configuredAddress == null
                || configuredAddress.isBlank()) {
            return false;
        }
        IConnectionString connection = selected.getConnectionString();
        if (type == StorageProjectSettings.InfobaseType.FILE
                && connection instanceof FileConnectionString file) {
            try {
                Path expected = Path.of(configuredAddress.trim()).toRealPath();
                Path actual = Path.of(file.getFile()).toRealPath();
                return Files.isDirectory(expected) && Files.isSameFile(expected, actual);
            } catch (IOException | RuntimeException ex) {
                // Missing / moved / inaccessible base is not an identity match.
                return false;
            }
        }
        if (type == StorageProjectSettings.InfobaseType.SERVER
                && connection instanceof ServerConnectionString server) {
            String address = configuredAddress.trim();
            int separator = address.lastIndexOf('/');
            if (separator < 1 || separator == address.length() - 1) {
                return false;
            }
            String configuredServer = address.substring(0, separator).trim();
            String configuredReference = address.substring(separator + 1).trim();
            return !configuredServer.isEmpty() && !configuredReference.isEmpty()
                && configuredServer.equalsIgnoreCase(server.getServer())
                && configuredReference.equalsIgnoreCase(server.getReference());
        }
        return false;
    }
}
