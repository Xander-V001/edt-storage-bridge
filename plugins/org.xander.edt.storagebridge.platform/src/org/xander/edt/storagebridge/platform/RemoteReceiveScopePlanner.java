package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Repository metadata CHANGE DETECTION, not a native Designer export planner.
 *
 * This class used to run changed metadata identities through
 * ConfigurationChangesParser.objectsToExport() BEFORE persisting them in
 * ReceivePendingStore. That produced fake "pending" entries for ancestors
 * (e.g. Document.X when only Document.X.ManagerModule changed).
 *
 * Inventory names are exact native 1C names. Expansion to parent objects
 * and related forms happens later, ONLY while creating Designer -listFile.
 * A deletion is a real change and must never make an incremental update fail
 * before native EDT can offer full comparison.
 */
public final class RemoteReceiveScopePlanner {
    private RemoteReceiveScopePlanner() {}

    public record Changes(Set<String> present, Set<String> removed,
            Set<String> renamed, Set<String> replaced, boolean fullReview) {
        public Changes {
            present = Set.copyOf(present);
            removed = Set.copyOf(removed);
            renamed = Set.copyOf(renamed);
            replaced = Set.copyOf(replaced);
        }
        /** Changes to track in pending: only REAL ConfigDumpInfo identities. */
        public Set<String> pendingIdentities() {
            var names = new LinkedHashSet<>(present);
            names.addAll(removed);
            return Set.copyOf(names);
        }
        public boolean requiresFullNativeComparison() {
            return fullReview || !removed.isEmpty() || !renamed.isEmpty()
                || !replaced.isEmpty()
                || present.stream().anyMatch(RemoteReceiveScopePlanner::isRootOrLanguage);
        }
    }

    /**
     * Full = all current identities plus old identities absent from the new
     * source. Incremental = changed/new current identities plus removals.
     *
     * Renames/replacements need FULL native comparison: a same-name UUID
     * replacement is not an ordinary edit. These cases stay explicit, never
     * silently recast as an update of the original object.
     */
    public static Changes discover(Path previousInfo, Path currentInfo,
            boolean fullReview) throws IOException {
        Objects.requireNonNull(currentInfo);
        Map<String, ConfigDumpInfoIndex.Version> current =
            ConfigDumpInfoIndex.readVersions(currentInfo);
        if (previousInfo == null) {
            return new Changes(current.keySet(), Set.of(), Set.of(),
                Set.of(), true);
        }
        Map<String, ConfigDumpInfoIndex.Version> before =
            ConfigDumpInfoIndex.readVersions(previousInfo);
        Map<String, String> priorNamesById = uniqueNamesById(before);
        uniqueNamesById(current); // reject ambiguous identities on either side

        var changed = new LinkedHashSet<String>();
        var removed = new LinkedHashSet<String>();
        var renamed = new LinkedHashSet<String>();
        var replaced = new LinkedHashSet<String>();

        for (var old : before.entrySet()) {
            if (!current.containsKey(old.getKey())) {
                removed.add(old.getKey());
            }
        }
        for (var entry : current.entrySet()) {
            String name = entry.getKey();
            ConfigDumpInfoIndex.Version version = entry.getValue();
            ConfigDumpInfoIndex.Version oldSameName = before.get(name);
            String previousNameForId = priorNamesById.get(version.id());
            if (previousNameForId != null && !previousNameForId.equals(name)) {
                renamed.add(previousNameForId + " -> " + name);
            }
            if (oldSameName != null && !oldSameName.id().equals(version.id())) {
                replaced.add(name);
            }
            if (fullReview || oldSameName == null
                    || !oldSameName.equals(version)) {
                changed.add(name);
            }
        }
        return new Changes(changed, removed, renamed, replaced, fullReview);
    }

    /**
     * Deprecated old convenience entry point retained for existing callers:
     * returns exact changes, NOT ancestor export paths. Deletions included.
     */
    public static Set<String> candidates(Path previousInfo, Path currentInfo,
            boolean fullReview) throws IOException {
        return discover(previousInfo, currentInfo, fullReview)
            .pendingIdentities();
    }

    public static Set<String> removedSince(Path previousInfo, Path currentInfo)
            throws IOException {
        return discover(previousInfo, currentInfo, false).removed();
    }

    /** Only identities present in the current source can be XML-exported. */
    public static Set<String> availableInFullReview(
            Set<String> pending, Set<String> currentInventory) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(currentInventory);
        var current = new LinkedHashSet<>(pending);
        current.retainAll(currentInventory);
        return Set.copyOf(current);
    }

    private static Map<String, String> uniqueNamesById(
            Map<String, ConfigDumpInfoIndex.Version> inventory)
            throws IOException {
        var byId = new LinkedHashMap<String, String>();
        for (var entry : inventory.entrySet()) {
            String earlier = byId.putIfAbsent(entry.getValue().id(),
                entry.getKey());
            if (earlier != null && !earlier.equals(entry.getKey())) {
                throw new IOException("Ambiguous native metadata identity in ConfigDumpInfo");
            }
        }
        return byId;
    }

    private static boolean isRootOrLanguage(String name) {
        return "Configuration".equals(name)
            || name.startsWith("Configuration.")
            || "Language".equals(name)
            || name.startsWith("Language.")
            || "Язык".equals(name)
            || name.startsWith("Язык.");
    }
}
