package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Route the exact changes reported by 1C Designer -getChanges.
 *
 * No ConfigDumpInfo id/configVersion differences are calculated by Java:
 * the values are opaque internal platform tokens. Separate native manifest
 * name sets are only used to find objects that disappeared entirely.
 */
public final class RemoteReceiveScopePlanner {
    private RemoteReceiveScopePlanner() {}

    public record Changes(Set<String> present, Set<String> removed,
            Set<String> unindexed, boolean fullReview) {
        public Changes {
            present = Set.copyOf(present);
            removed = Set.copyOf(removed);
            unindexed = Set.copyOf(unindexed);
        }

        public Set<String> pendingIdentities() {
            var result = new LinkedHashSet<>(present);
            result.addAll(removed);
            return Set.copyOf(result);
        }

        public boolean requiresFullNativeComparison() {
            return fullReview || !removed.isEmpty()
                || !unindexed.isEmpty()
                || present.stream().anyMatch(RemoteReceiveScopePlanner::isRootOrLanguage);
        }
    }

    /**
     * Explicit initial/full review compares the current native inventory.
     * A missing former identity is still an unresolved deletion. Never
     * discard deletions merely because the user requested FULL comparison.
     * Both inputs are platform-created files, not fabricated version tokens.
     */
    public static Changes fullInventory(Path previousInfo, Path currentInfo)
            throws IOException {
        var current = ConfigDumpInfoIndex.exportableNames(currentInfo);
        if (previousInfo == null) {
            return new Changes(current, Set.of(), Set.of(), true);
        }
        var removed = new LinkedHashSet<>(
            ConfigDumpInfoIndex.exportableNames(previousInfo));
        removed.removeAll(current);
        return new Changes(current, removed, Set.of(), true);
    }

    /**
     * Native -getChanges decides all new/modified names. Compare only native
     * NAME SETS for deletion (not version tokens); missing names force full
     * merge. Native FullDump is authoritative and also forces full.
     * An unknown native name stays pending, with full review required.
     */
    public static Changes reconcileNative(Path oldInfo, Path currentInfo,
            ChangedObjectsProbe.NativeChanges nativeResult) throws IOException {
        Objects.requireNonNull(oldInfo, "oldInfo");
        Objects.requireNonNull(currentInfo, "currentInfo");
        Objects.requireNonNull(nativeResult, "nativeResult");
        var oldNames = ConfigDumpInfoIndex.exportableNames(oldInfo);
        var currentNames = ConfigDumpInfoIndex.exportableNames(currentInfo);
        var deleted = new LinkedHashSet<>(oldNames);
        deleted.removeAll(currentNames);
        if (nativeResult.fullDump()) {
            return new Changes(currentNames, deleted, Set.of(), true);
        }
        var nativeNames = nativeResult.exactNames();
        var unindexed = new LinkedHashSet<>(nativeNames);
        unindexed.removeAll(currentNames);
        return new Changes(nativeNames, deleted, unindexed, false);
    }

    /** Restrict transport to source-present entries; never delete pending. */
    public static Set<String> availableInFullReview(
            Set<String> pending, Set<String> currentInventory) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(currentInventory);
        var available = new LinkedHashSet<>(pending);
        available.retainAll(currentInventory);
        return Set.copyOf(available);
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
