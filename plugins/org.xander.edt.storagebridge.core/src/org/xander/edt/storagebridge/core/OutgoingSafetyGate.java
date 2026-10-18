package org.xander.edt.storagebridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only outgoing preparation diagnostics, NOT a repository write gate.
 *
 * The classic storage operation is OBJECT-SCOPED. A missing project-wide
 * applied revision, pending changes in unrelated objects, a dirty Git
 * worktree, or the absence of a standing scratch infobase MUST NOT prevent
 * selecting local objects for review.
 *
 * Actual publishing will require a NEW, operation-specific confirmation
 * after fresh comparison against the selected objects' repository state,
 * native lock ownership, exact loaded-object diff and explicit user consent.
 * This class never grants permission to run Designer write operations.
 */
public final class OutgoingSafetyGate {
    public static final long UNKNOWN = -1L;

    public enum Notice {
        HEAD_REPORT_UNAVAILABLE,
        PROJECT_WIDE_APPLIED_REVISION_UNKNOWN,
        PROJECT_WIDE_REVISIONS_DIFFER,
        OTHER_INCOMING_CHANGES_MAY_EXIST,
        LOCAL_GIT_CHANGES_MAY_BE_INCLUDED
    }

    public record Evidence(long nativeHead, long appliedRevision,
            int incomingPending, Boolean gitWorkingTreeClean) {
        public Evidence {
            if (incomingPending < 0) {
                throw new IllegalArgumentException("Negative pending count");
            }
        }
    }

    public record Decision(List<Notice> notices) {
        public Decision {
            notices = List.copyOf(Objects.requireNonNull(notices));
        }

        /**
         * Read-only object selection and snapshot preparation may proceed.
         * This is NEVER an authorization for Lock, Load or Commit.
         */
        public boolean mayPrepareSelection() {
            return true;
        }
    }

    private OutgoingSafetyGate() {}

    public static Decision evaluate(Evidence evidence) {
        Objects.requireNonNull(evidence);
        var notices = new ArrayList<Notice>();
        if (evidence.nativeHead() <= 0) {
            notices.add(Notice.HEAD_REPORT_UNAVAILABLE);
        }
        if (evidence.appliedRevision() <= 0) {
            notices.add(Notice.PROJECT_WIDE_APPLIED_REVISION_UNKNOWN);
        } else if (evidence.nativeHead() > 0
                && evidence.nativeHead() != evidence.appliedRevision()) {
            notices.add(Notice.PROJECT_WIDE_REVISIONS_DIFFER);
        }
        if (evidence.incomingPending() > 0) {
            notices.add(Notice.OTHER_INCOMING_CHANGES_MAY_EXIST);
        }
        if (Boolean.FALSE.equals(evidence.gitWorkingTreeClean())) {
            notices.add(Notice.LOCAL_GIT_CHANGES_MAY_BE_INCLUDED);
        }
        return new Decision(notices);
    }
}
