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
            // An unversioned attribute/form can still be exported using the
            // native changed name and its 1C owner. Missing configVersion
            // is NOT a reason to dump the whole configuration. Actual
            // removals, native FullDump and Configuration/Language roots are.
            return fullReview || !removed.isEmpty()
                || present.stream().anyMatch(RemoteReceiveScopePlanner::isRootOrLanguage);
        }
    }

    /**
     * A global comparison is a comparison SCOPE, not a list of 20k changed
     * metadata objects. It is a fresh-baseline review, and the developer must
     * explicitly confirm completion before a reviewed revision is saved.
     * Only names that disappeared from the previous inventory can be
     * recognized here; NEW/MODIFIED names come from native -getChanges.
     */
    public static Changes fullInventory(Path previousInfo, Path currentInfo)
            throws IOException {
        var current = ConfigDumpInfoIndex.exportableNames(currentInfo);
        if (previousInfo == null) {
            return new Changes(Set.of(), Set.of(), Set.of(), true);
        }
        var removed = new LinkedHashSet<>(
            ConfigDumpInfoIndex.exportableNames(previousInfo));
        removed.removeAll(current);
        return new Changes(Set.of(), removed, Set.of(), true);
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
            // Designer did not provide a trustworthy item delta. Request a
            // complete EDT review instead of fabricating 20k pending changes.
            return new Changes(Set.of(), deleted, Set.of(), true);
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
