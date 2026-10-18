package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;

/**
 * Guard against modifying native metadata identities outside the confirmed
 * outgoing owner list while staging a partial XML load. Opaque native tokens
 * are compared only for equality; they are never used as EDT Git revisions.
 *
 * Does not prove BSL bytes are equal or authorize commit on its own.
 */
public final class OutgoingNativeVersionVerifier {
    private OutgoingNativeVersionVerifier() {}

    public static Set<String> changedOnlyWithinOwners(
            Map<String, ConfigDumpInfoIndex.Version> before,
            Map<String, ConfigDumpInfoIndex.Version> after,
            Set<String> allowedClassicOwners) throws IOException {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        Objects.requireNonNull(allowedClassicOwners);
        if (allowedClassicOwners.isEmpty()) {
            throw new IOException("No confirmed metadata owner scope");
        }
        var keys = new LinkedHashSet<String>(before.keySet());
        keys.addAll(after.keySet());
        var changed = new LinkedHashSet<String>();
        for (String key : keys) {
            if (Objects.equals(before.get(key), after.get(key))) continue;
            boolean allowed = false;
            for (String owner : allowedClassicOwners) {
                if (key.equals(owner) || key.startsWith(owner + ".")) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IOException(
                    "Partial load changed metadata outside confirmed owner scope");
            }
            changed.add(key);
        }
        if (changed.isEmpty()) {
            throw new IOException(
                "No native metadata version change after partial load");
        }
        return Set.copyOf(changed);
    }
}
