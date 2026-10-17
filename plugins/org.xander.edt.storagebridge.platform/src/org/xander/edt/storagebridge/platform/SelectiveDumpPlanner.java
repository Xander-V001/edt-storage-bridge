package org.xander.edt.storagebridge.platform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Build a native Designer -listFile without inventing surrogate metadata
 * paths. A bare Configuration/Language label occurs in version inventories
 * but is not guaranteed to be a valid exported metadata/property name.
 * Do not lose its pending change: defer it for a verified root-name mapping.
 * Independently versioned forms, templates and modules must be exported\n * together with their top-level owner: -listFile does NOT imply all independently\n * developed children are included when only the owner is listed.\n * Configuration-root AND language changes are kept pending: EDT 2026.2 cannot reliably
 * convert their special Ext resources outside a complete project context.
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
            if ("Configuration".equals(name)
                    || name.startsWith("Configuration.")
                    || "Language".equals(name)
                    || name.startsWith("Language.")
                    || "Язык".equals(name)
                    || name.startsWith("Язык.")) {
                // Language is embedded in Configuration.mdo, not in Languages/*.mdo.
                // Root ConfigDumpInfo entries cannot safely be converted to
                // isolated EDT resources with the existing partial importer:
                // its Ext/HomePageWorkArea.xml and
                // Ext/ClientApplicationInterface.xml fail with null paths.
                // Never silently mark configuration-root changes as applied.
                // Keep them pending until a verified conversion exists.
                deferred.add(name);
            } else {
                // Parent + separately versioned child; embedded attributes
                // and commands still map to their parent via the canonical
                // 1C change-list expansion. Never discard an exact Form,
                // Template, ObjectModule, Rights, etc. before -listFile.
                exportable.addAll(new ConfigurationChangesParser.ChangeSet(
                    List.of(name), List.of()).objectsToExport());
            }
        }
        return new Plan(List.copyOf(exportable), List.copyOf(deferred));
    }
}
