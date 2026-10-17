package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Distinguish repeatable full verification from normal incremental transport.
 * The full mode deliberately does not consult earlier object version deltas.
 * It does NOT erase previously pending changes or certify deleted objects.
 */
public final class RemoteReceiveScopePlanner {
    private RemoteReceiveScopePlanner() {}

    public static Set<String> candidates(Path previousInfo, Path currentInfo,
            boolean fullReview) throws IOException {
        Set<String> names = fullReview || previousInfo == null
            ? ConfigDumpInfoIndex.exportableNames(currentInfo)
            : ConfigDumpInfoIndex.changedSince(previousInfo, currentInfo);
        return Set.copyOf(new LinkedHashSet<>(
            new ConfigurationChangesParser.ChangeSet(
                List.copyOf(names), List.of()).objectsToExport()));
    }

    /** Full reviews must preserve identities deleted from the repository. */
    public static Set<String> removedSince(Path previousInfo, Path currentInfo)
            throws IOException {
        if (previousInfo == null) {
            return Set.of();
        }
        var oldNames = ConfigDumpInfoIndex.readVersions(previousInfo).keySet();
        var newNames = ConfigDumpInfoIndex.readVersions(currentInfo).keySet();
        var deleted = new LinkedHashSet<>(oldNames);
        deleted.removeAll(newNames);
        return Set.copyOf(deleted);
    }

    /**
     * Stale pending object identities cannot be staged from the current full
     * source: do not silently mark them received and do not let them make
     * an otherwise valid full XML import fail with a missing .mdo owner.
     * Deletion/rename reconciliation requires its own explicit workflow.
     */
    public static Set<String> availableInFullReview(
            Set<String> pending, Set<String> currentInventory) {
        var available = new LinkedHashSet<>(pending);
        available.retainAll(currentInventory);
        return Set.copyOf(available);
    }
}
