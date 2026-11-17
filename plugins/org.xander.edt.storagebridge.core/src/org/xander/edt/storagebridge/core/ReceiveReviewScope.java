package org.xander.edt.storagebridge.core;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the exact metadata identities eligible for acceptance after EDT
 * merge. Only identities in the CURRENT repository inventory can be reviewed
 * automatically. A selective comparison never includes deferred root/language
 * records. A full native EDT comparison can include them; an owner deleted
 * from the repository is still NOT evidence of an accepted native deletion.
 */
public final class ReceiveReviewScope {
    private ReceiveReviewScope() {}

    public static Set<String> eligible(Set<String> pending,
            Set<String> currentInventory, Set<String> deferred,
            boolean fullNativeComparison) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(currentInventory);
        Objects.requireNonNull(deferred);
        var covered = new LinkedHashSet<>(pending);
        covered.retainAll(currentInventory);
        if (!fullNativeComparison) {
            covered.removeAll(deferred);
        }
        return Set.copyOf(covered);
    }

    public static Set<String> missingFromSource(Set<String> pending,
            Set<String> currentInventory) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(currentInventory);
        var removed = new LinkedHashSet<>(pending);
        removed.removeAll(currentInventory);
        return Set.copyOf(removed);
    }
}
