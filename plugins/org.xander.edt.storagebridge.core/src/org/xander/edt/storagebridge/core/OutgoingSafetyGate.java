package org.xander.edt.storagebridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Independent, read-only prerequisite gate for EDT -> classic repository
 * publishing. This class does NOT read/write EDT, Git or a 1C infobase.
 *
 * A matching native HEAD and inbound applied revision are NECESSARY but not
 * SUFFICIENT for outbound commit. A trustworthy content baseline and isolated
 * validation of the selected XML are further mandatory prerequisites.
 */
public final class OutgoingSafetyGate {
    public static final long UNKNOWN = -1L;

    public enum Blocker {
        NATIVE_HEAD_UNKNOWN,
        APPLIED_REVISION_UNKNOWN,
        REVISION_DIVERGED,
        INCOMING_PENDING,
        GIT_NOT_CLEAN,
        VERIFIED_CONTENT_BASELINE_MISSING,
        ISOLATED_VALIDATION_MISSING
    }

    public record Evidence(long nativeHead, long appliedRevision,
            int incomingPending, boolean cleanGit,
            boolean verifiedContentBaseline, boolean isolatedValidation) {
        public Evidence {
            if (incomingPending < 0) {
                throw new IllegalArgumentException("Negative pending count");
            }
        }
    }

    public record Decision(List<Blocker> blockers) {
        public Decision {
            blockers = List.copyOf(Objects.requireNonNull(blockers));
        }

        /** Whether prerequisites to a future outbound write have been proven. */
        public boolean permitsWrite() {
            return blockers.isEmpty();
        }
    }

    private OutgoingSafetyGate() {}

    public static Decision evaluate(Evidence evidence) {
        Objects.requireNonNull(evidence);
        var blockers = new ArrayList<Blocker>();
        if (evidence.nativeHead() <= 0) {
            blockers.add(Blocker.NATIVE_HEAD_UNKNOWN);
        }
        if (evidence.appliedRevision() <= 0) {
            blockers.add(Blocker.APPLIED_REVISION_UNKNOWN);
        }
        if (evidence.nativeHead() > 0 && evidence.appliedRevision() > 0
                && evidence.nativeHead() != evidence.appliedRevision()) {
            blockers.add(Blocker.REVISION_DIVERGED);
        }
        if (evidence.incomingPending() > 0) {
            blockers.add(Blocker.INCOMING_PENDING);
        }
        if (!evidence.cleanGit()) {
            blockers.add(Blocker.GIT_NOT_CLEAN);
        }
        if (!evidence.verifiedContentBaseline()) {
            blockers.add(Blocker.VERIFIED_CONTENT_BASELINE_MISSING);
        }
        if (!evidence.isolatedValidation()) {
            blockers.add(Blocker.ISOLATED_VALIDATION_MISSING);
        }
        return new Decision(blockers);
    }
}
