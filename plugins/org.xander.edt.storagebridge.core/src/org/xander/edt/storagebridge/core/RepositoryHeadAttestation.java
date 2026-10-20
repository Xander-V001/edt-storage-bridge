package org.xander.edt.storagebridge.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Read-only confirmation of a repository HEAD after EDT's native merge.
 *
 * The original repository revision was proven unchanged before/after the
 * Designer update. Native report generation can fail transiently when EDT is
 * also working with the connected intermediary. Retry UNKNOWN responses;
 * NEVER retry past, ignore, or accept a DIFFERENT positive native revision.
 * Never infer a revision from XML tokens or the result of an EDT merge.
 */
public final class RepositoryHeadAttestation {
    public static final long UNKNOWN = -1;
    private static final long[] DELAYS_MS = {0, 2_000, 5_000, 10_000};

    public enum State { CONFIRMED, CHANGED, UNAVAILABLE, CANCELLED }

    public record Result(State state, long observed, int attempts) {
        public Result {
            Objects.requireNonNull(state);
            if (attempts < 0 || attempts > DELAYS_MS.length) {
                throw new IllegalArgumentException("Invalid attestation attempt count");
            }
        }
    }

    @FunctionalInterface
    public interface Pause {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private RepositoryHeadAttestation() {}

    /**
     * A later repository commit does not invalidate an ALREADY attested and
     * unchanged intermediary snapshot. It does invalidate any claim that the
     * current HEAD is fully processed. Callers may acknowledge only the
     * EARLIER staged revision, only after native EDT merge + content review.
     */
    public static boolean mayReviewOlderStagedSnapshot(long stagedRevision,
            long scannedInventoryRevision, long latestHead,
            boolean intermediaryUnchanged) {
        return stagedRevision > 0
            && scannedInventoryRevision == stagedRevision
            && latestHead > stagedRevision
            && intermediaryUnchanged;
    }

    /**
     * The caller has already verified the intermediary ConfigDumpInfo is
     * byte-identical to the saved snapshot. Return that exact older staged
     * version instead of wrongly relabeling it with a newer HEAD.
     */
    public static long attestedPendingRetrySource(long staged, long latest) {
        return staged > 0 && latest >= staged ? staged : UNKNOWN;
    }

    public static Result confirm(long expectedRevision, LongSupplier nativeProbe,
            BooleanSupplier cancelled, Pause pause) throws InterruptedException {
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("Expected proven native revision");
        }
        Objects.requireNonNull(nativeProbe);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(pause);
        for (int i = 0; i < DELAYS_MS.length; i++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return new Result(State.CANCELLED, UNKNOWN, i);
            }
            if (DELAYS_MS[i] > 0) {
                pause.sleep(DELAYS_MS[i]);
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new Result(State.CANCELLED, UNKNOWN, i);
                }
            }
            long actual = nativeProbe.getAsLong();
            if (actual == expectedRevision) {
                return new Result(State.CONFIRMED, actual, i + 1);
            }
            if (actual > 0) {
                // Positive, different native HEAD is a real safety conflict.
                // Do not conceal it by waiting for the old HEAD to come back.
                return new Result(State.CHANGED, actual, i + 1);
            }
        }
        return new Result(State.UNAVAILABLE, UNKNOWN, DELAYS_MS.length);
    }
}
