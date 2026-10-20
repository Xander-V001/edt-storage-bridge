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
        /** All incoming changes exactly matched, but global equality was NOT proven. */
        REVIEWED_EXACT_SCOPE,
        /** The native EDT result contained no owners to inspect. */
        AUDIT_INCOMPLETE,
        /** The full 1C native inventory was not materialized by the EDT importer. */
        SOURCE_INCOMPLETE,
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
            boolean fullProjectCoverageVerified,
            boolean fullSourceInventoryCovered,
            boolean allComparedOwnersIdentical,
            int auditedOwners,
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
        if (auditedOwners <= 0) {
            // Never turn 0 of 0 matching resources into an accepted revision.
            return Next.AUDIT_INCOMPLETE;
        }
        if (!fullSourceInventoryCovered) {
            // Full compare with missing native owners must not be
            // acknowledged as a complete review or mirror of that HEAD.
            return Next.SOURCE_INCOMPLETE;
        }
        if (pendingAfterReadOnlyAudit.isEmpty() && allComparedOwnersIdentical) {
            // A selective patch verifies just its covered owners. In no case
            // does that establish equality of the entire EDT configuration.
            return fullProjectCoverageVerified
                ? Next.FULLY_APPLIED : Next.REVIEWED_EXACT_SCOPE;
        }
        if (!presentedIdentities.containsAll(pendingAfterReadOnlyAudit)) {
            return Next.PARTIAL;
        }
        return Next.REQUIRE_USER_CONFIRMATION;
    }
}
