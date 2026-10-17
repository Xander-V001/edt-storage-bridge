package org.xander.edt.storagebridge.platform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Build a native Designer -listFile without inventing surrogate metadata
 * paths. A bare Configuration/Language label occurs in version inventories
 * but is not guaranteed to be a valid exported metadata/property name.
 * Do not lose its pending change: defer it for a verified root-name mapping.
 */
public final class SelectiveDumpPlanner {
    private SelectiveDumpPlanner() {}

    public record Plan(List<String> exportable, List<String> deferred) {
        public Plan {
            exportable = List.copyOf(exportable);
            deferred = List.copyOf(deferred);
        }
    }

    public static Plan plan(Iterable<String> objectNames) {
        Objects.requireNonNull(objectNames, "objectNames");
        var exportable = new LinkedHashSet<String>();
        var deferred = new LinkedHashSet<String>();
        for (String name : objectNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid metadata object");
            }
            if ("Configuration".equals(name) || "Language".equals(name)) {
                // "Configuration" alone was rejected by some Designer builds.
                // Mapping it to Configuration.Help or another unrelated item
                // would silently claim receipt of the wrong content.
                deferred.add(name);
            } else {
                exportable.add(name);
            }
        }
        return new Plan(List.copyOf(exportable), List.copyOf(deferred));
    }
}
