package org.xander.edt.storagebridge.platform;

import java.util.List;
import java.util.Objects;

/**
 * 1C infobase credentials (/N, /P), which are distinct from classical
 * repository credentials (/ConfigurationRepositoryN, /ConfigurationRepositoryP).
 *
 * Never log an argv list returned by this helper: 1C credentials are passed
 * to its native process. The password remains in Eclipse Secure Storage at rest.
 */
public final class DesignerInfobaseAuth {
    private DesignerInfobaseAuth() {}

    public static void validate(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if ((!password.isEmpty() && username.isBlank())
                || username.indexOf('\n') >= 0 || username.indexOf('\r') >= 0
                || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                "Infobase password requires username; multiline credentials forbidden");
        }
    }

    /** Add credentials immediately after the /F or /S infobase locator. */
    public static void addTo(List<String> arguments, String username, String password) {
        validate(username, password);
        if (!username.isBlank()) {
            arguments.add("/N");
            arguments.add(username.trim());
            if (!password.isEmpty()) {
                arguments.add("/P");
                arguments.add(password);
            }
        }
    }
}
