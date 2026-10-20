package org.xander.edt.storagebridge.core;

import java.util.Objects;
import java.util.Set;

/**
 * Single source of truth for completing a native 1C -> EDT merge.
 *
 * A completed native merge does not itself prove that every remote object was
 * accepted. A pending object is no longer pending only if its imported content
 * was verified or if the developer explicitly reviewed that exact comparison
 * and intentionally kept an alternative. Neither operation guesses a HEAD.
 */
public final class ReceiveCompletionPolicy {
    public enum Next {
        /** Project exactly matches the complete native source, no debt. */
        FULLY_APPLIED,
        /** All remaining pending objects were presented; explicit review needed. */
        REQUIRE_USER_CONFIRMATION,
        /** At least one remote change was not covered by this comparison. */
        PARTIAL,
        /** The staged 1C revision cannot be linked to an unchanged native HEAD. */
        UNPROVEN_SOURCE,
        /** The native merge was not completed; no acceptance is possible. */
        NOT_MERGED
    }

    private ReceiveCompletionPolicy() {}

    public static Next decide(boolean nativeMergeFinished,
            boolean nativeSourceRevisionProven,
            boolean sourceCompletelyIdentical,
            Set<String> pendingAfterReadOnlyAudit,
            Set<String> presentedIdentities) {
        Objects.requireNonNull(pendingAfterReadOnlyAudit);
        Objects.requireNonNull(presentedIdentities);
        if (!nativeMergeFinished) {
            return Next.NOT_MERGED;
        }
        if (!nativeSourceRevisionProven) {
            return Next.UNPROVEN_SOURCE;
        }
        if (pendingAfterReadOnlyAudit.isEmpty() && sourceCompletelyIdentical) {
            return Next.FULLY_APPLIED;
        }
        if (!presentedIdentities.containsAll(pendingAfterReadOnlyAudit)) {
            return Next.PARTIAL;
        }
        return Next.REQUIRE_USER_CONFIRMATION;
    }
}
