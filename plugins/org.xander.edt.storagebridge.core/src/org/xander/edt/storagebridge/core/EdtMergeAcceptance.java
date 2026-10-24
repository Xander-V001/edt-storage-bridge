package org.xander.edt.storagebridge.core;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Native EDT merge outcome policy: the native merge is authoritative, not
 * a second byte-by-byte comparison of BSL, XML, pictures or form resources.
 *
 * An editor close alone is never acceptance. Even with user confirmation,
 * no identity can be cleared without a verified reviewed-source snapshot. Only
 * identities actually in the native EDT review scope may be acknowledged;
 * omitted/deferred identities remain pending.
 */
public final class EdtMergeAcceptance {
    private EdtMergeAcceptance() {}

    public record Decision(Set<String> acknowledged, Set<String> remaining,
            boolean allReviewed) {
        public Decision {
            acknowledged = Set.copyOf(acknowledged);
            remaining = Set.copyOf(remaining);
            if (allReviewed && !remaining.isEmpty()) {
                throw new IllegalArgumentException("Cannot review with pending changes");
            }
        }
    }

    /** Stable, explicit disposition of the whole native EDT selective scope. */
    public record ReviewedChoices(Set<String> accepted, Set<String> keptLocal) {
        public ReviewedChoices {
            accepted = Set.copyOf(accepted);
            keptLocal = Set.copyOf(keptLocal);
            if (!java.util.Collections.disjoint(accepted, keptLocal)) {
                throw new IllegalArgumentException(
                    "A changed object cannot be both accepted and kept local");
            }
        }
    }

    /**
     * The native comparison API does not expose a durable per-checkbox
     * acceptance receipt after the editor has completed. The developer
     * explicitly marks identities kept locally; every other presented
     * identity is confirmed as reviewed/accepted. No byte comparisons.
     */
    public static ReviewedChoices classifyReviewed(
            Set<String> presented, Set<String> explicitlyKeptLocal) {
        Objects.requireNonNull(presented);
        Objects.requireNonNull(explicitlyKeptLocal);
        if (!presented.containsAll(explicitlyKeptLocal)) {
            throw new IllegalArgumentException(
                "Unpresented object cannot be marked retained locally");
        }
        var accepted = new LinkedHashSet<>(presented);
        accepted.removeAll(explicitlyKeptLocal);
        return new ReviewedChoices(accepted, explicitlyKeptLocal);
    }

    /** Native EDT public status: completed means its merge operation finished. */
    public static boolean nativeMergeFinished(String status) {
        return "MERGE_PROCESS_FINISHED".equals(status);
    }

    /** Native EDT explicitly reports cancellation: user approval cannot override. */
    public static boolean nativeMergeCancelled(String status) {
        return "COMPARISON_MERGE_PROCESS_CANCELLED".equals(status);
    }

    public static Decision evaluate(Set<String> pending,
            Set<String> presentedInNativeEditor,
            boolean developerConfirmed, boolean reviewedSourceConfirmed) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(presentedInNativeEditor);
        if (!developerConfirmed || !reviewedSourceConfirmed) {
            return new Decision(Set.of(), pending, false);
        }
        var acknowledged = new LinkedHashSet<>(pending);
        acknowledged.retainAll(presentedInNativeEditor);
        var remaining = new LinkedHashSet<>(pending);
        remaining.removeAll(acknowledged);
        return new Decision(acknowledged, remaining, remaining.isEmpty());
    }
}
