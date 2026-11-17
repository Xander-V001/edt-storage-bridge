package org.xander.edt.storagebridge.core;

import java.util.Objects;

/**
 * Git checks after EDT's native merge must not confuse ordinary edited files
 * with branch movement, or a forward commit with a rewritten task history.
 * This policy never treats a Git result as proof that 1C changes were applied.
 */
public final class ReceiveGitStatePolicy {
    public enum Result {
        UNCHANGED, ADVANCED_ON_SAME_BRANCH, SWITCHED_BRANCH, REWRITTEN_HISTORY
    }

    private ReceiveGitStatePolicy() {}

    public static Result assess(String beforeBranch, String beforeHead,
            String currentBranch, String currentHead, boolean beforeIsAncestor) {
        Objects.requireNonNull(beforeBranch);
        Objects.requireNonNull(beforeHead);
        Objects.requireNonNull(currentBranch);
        Objects.requireNonNull(currentHead);
        if (beforeBranch.isBlank() || currentBranch.isBlank()
                || !validSha(beforeHead) || !validSha(currentHead)) {
            throw new IllegalArgumentException("Unverifiable Git state");
        }
        if (!beforeBranch.equals(currentBranch)) {
            return Result.SWITCHED_BRANCH;
        }
        if (beforeHead.equalsIgnoreCase(currentHead)) {
            return Result.UNCHANGED;
        }
        return beforeIsAncestor
            ? Result.ADVANCED_ON_SAME_BRANCH : Result.REWRITTEN_HISTORY;
    }

    private static boolean validSha(String sha) {
        return sha.matches("(?i)[0-9a-f]{40}([0-9a-f]{24})?");
    }
}
