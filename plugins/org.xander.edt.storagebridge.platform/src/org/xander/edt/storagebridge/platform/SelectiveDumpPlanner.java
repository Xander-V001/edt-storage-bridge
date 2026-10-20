package org.xander.edt.storagebridge.platform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Build a native Designer -listFile without inventing surrogate metadata
 * paths. A bare Configuration/Language label occurs in version inventories
 * but is not guaranteed to be a valid exported metadata/property name.
 * Do not lose its pending change: defer it for a verified root-name mapping.
 * Independently versioned forms, templates and modules must be exported
 * together with their top-level owner: -listFile does NOT imply all independent
 * children are included when only the owner is listed.
 * Configuration-root AND language changes stay pending: EDT 2026.2 cannot reliably
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
        return planIncludingRelated(objectNames, List.of());
    }

    /**
     * Expand each changed top-level owner with EVERY independently versioned
     * descendant recorded by the CURRENT native ConfigDumpInfo inventory.
     * E.g. changing Document.X.ObjectModule still exports its unchanged
     * Form, Template and other separate development files: EDT receives a
     * complete owner rather than a partial surrogate model.
     *
     * The final -listFile uses only names produced by the same canonical
     * native names mapper used for change records (embedded properties map
     * to parents). Full receive does NOT call this planner.
     */
    public static Plan planIncludingRelated(Iterable<String> objectNames,
            Iterable<String> remoteVersionedNames) {
        Objects.requireNonNull(objectNames, "objectNames");
        Objects.requireNonNull(remoteVersionedNames, "remoteVersionedNames");
        var requested = new LinkedHashSet<String>();
        var requestedOwners = new LinkedHashSet<String>();
        for (String name : objectNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid metadata object");
            }
            requested.add(name);
            if (!isDeferredRoot(name)) {
                String[] parts = name.split("\\.");
                if (parts.length >= 2) {
                    requestedOwners.add(parts[0] + "." + parts[1]);
                }
            }
        }
        var included = new LinkedHashSet<String>(requested);
        for (String name : remoteVersionedNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid native metadata inventory name");
            }
            int firstDot = name.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : name.indexOf('.', firstDot + 1);
            if (firstDot > 0) {
                String owner = secondDot < 0 ? name : name.substring(0, secondDot);
                if (requestedOwners.contains(owner)) {
                    included.add(name);
                }
            }
        }
        var exportable = new LinkedHashSet<String>();
        var deferred = new LinkedHashSet<String>();
        for (String name : included) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid metadata object");
            }
            if (isDeferredRoot(name)) {
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

    /**
     * Native EDT XML import needs the Configuration root and independent
     * Language files even when the actual comparison is only Document.X.
     * This is a SOURCE-ONLY dependency like in GitConverter: do not add
     * these identities to the pending journal or EDT comparison scope.
     * Never use a project-created or synthetic Configuration.xml.
     */
    public static List<String> withImportContext(Plan plan,
            Iterable<String> nativeCurrentInventory) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(nativeCurrentInventory);
        var export = new LinkedHashSet<String>(plan.exportable());
        // GitConverter exports the native configuration root for EDT even
        // when ConfigDumpInfo names it Configuration.<ActualName>. A bare
        // Configuration index entry is not guaranteed to exist.
        export.add("Configuration");
        for (String name : nativeCurrentInventory) {
            if (name != null && name.startsWith("Language.")
                    && name.indexOf('.', "Language.".length()) < 0) {
                export.add(name);
            }
        }
        return List.copyOf(export);
    }

    /**
     * Official GitConverter algorithm: after Designer -update has written
     * the changed objects, request ONLY the unchanged siblings/owner files
     * needed to import the affected objects into EDT, plus real root/languages.
     * The changed identities already present in -getChanges stay in the
     * existing native patch and must not be exported again.
     */
    public static List<String> supplementsForNativePatch(Plan plan,
            Iterable<String> currentInventory,
            java.util.Set<String> nativeChangedNames) {
        Objects.requireNonNull(nativeChangedNames);
        var supplement = new LinkedHashSet<>(
            withImportContext(plan, currentInventory));
        supplement.removeAll(nativeChangedNames);
        return List.copyOf(supplement);
    }

    private static boolean isDeferredRoot(String name) {
        return "Configuration".equals(name)
            || name.startsWith("Configuration.")
            || "Language".equals(name)
            || name.startsWith("Language.")
            || "Язык".equals(name)
            || name.startsWith("Язык.");
    }
}
