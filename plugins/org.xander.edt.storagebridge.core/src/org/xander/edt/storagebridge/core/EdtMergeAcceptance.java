package org.xander.edt.storagebridge.core;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Native EDT merge outcome policy: the native merge is authoritative, not
 * a second byte-by-byte comparison of BSL, XML, pictures or form resources.
 *
 * An editor close alone is never acceptance. Even with user confirmation,
 * no identity can be cleared without a verified repository HEAD. Only
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
            if (!acknowledged.isEmpty() && !allReviewed && remaining.isEmpty()) {
                throw new IllegalArgumentException("Inconsistent review state");
            }
        }
    }

    public static Decision evaluate(Set<String> pending,
            Set<String> presentedInNativeEditor,
            boolean developerConfirmed, boolean repositoryHeadConfirmed) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(presentedInNativeEditor);
        if (!developerConfirmed || !repositoryHeadConfirmed) {
            return new Decision(Set.of(), pending, false);
        }
        var acknowledged = new LinkedHashSet<>(pending);
        acknowledged.retainAll(presentedInNativeEditor);
        var remaining = new LinkedHashSet<>(pending);
        remaining.removeAll(acknowledged);
        return new Decision(acknowledged, remaining, remaining.isEmpty());
    }
}
