package org.xander.edt.storagebridge.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Verified classic repository revision markers, never derived from XML object
 * version hashes. Scoped to active project + repository + intermediary.
 * Applied checkpoint is further scoped to the user's actual Git branch.
 *
 * UNKNOWN is preferable to an invented "synchronized" revision.
 */
public final class ReceiveRevisionStore {
    public static final long UNKNOWN = -1L;
    private static final String ROOT = "org.xander.edt.storagebridge.ui";
    private static final String OBSERVED = "observedLatestRevision";
    private static final String APPLIED = "verifiedFullyAppliedRevision";
    // All remote changes at this revision have been reviewed by the developer.
    // Unlike APPLIED, REVIEWED does not claim byte equality with repository XML.
    private static final String REVIEWED = "userReviewedRepositoryRevision";

    private ReceiveRevisionStore() {}

    public static long observed(IProject project, String repository,
            String intermediary) {
        return scope(project, repository, intermediary).getLong(OBSERVED, UNKNOWN);
    }

    public static void recordObserved(IProject project, String repository,
            String intermediary, long revision) throws BackingStoreException {
        requireRevision(revision);
        var preferences = scope(project, repository, intermediary);
        preferences.putLong(OBSERVED, revision);
        preferences.flush();
    }

    public static long applied(IProject project, String repository,
            String intermediary, String branch) {
        long result = branchScope(project, repository, intermediary, branch)
            .getLong(APPLIED, UNKNOWN);
        long remote = observed(project, repository, intermediary);
        // Repository may have been recreated/reset on the same endpoint.
        // A numerically impossible checkpoint cannot be shown as verified.
        return remote >= 1 && result <= remote ? result : UNKNOWN;
    }

    /**
     * Last explicitly completed incoming review, including deliberate choices
     * to retain local code. A review is not a byte-identical remote copy.
     * Older installations can already have a stricter applied checkpoint.
     */
    public static long reviewed(IProject project, String repository,
            String intermediary, String branch) {
        long result = branchScope(project, repository, intermediary, branch)
            .getLong(REVIEWED, UNKNOWN);
        long applied = applied(project, repository, intermediary, branch);
        long remote = observed(project, repository, intermediary);
        if (remote < 1) {
            return UNKNOWN;
        }
        if (result < 1 || result > remote) {
            return applied;
        }
        return Math.max(result, applied);
    }

    /**
     * Record only after the user explicitly finished reviewing the latest
     * staged incoming changes AND a native 1C report confirmed the exact HEAD.
     * This is not allowed to silently advance on editor-close alone.
     */
    public static void recordReviewed(IProject project, String repository,
            String intermediary, String branch, long revision)
            throws BackingStoreException {
        requireRevision(revision);
        if (observed(project, repository, intermediary) != revision) {
            throw new IllegalStateException(
                "Native HEAD has not confirmed the reviewed repository revision");
        }
        var preferences = branchScope(project, repository, intermediary, branch);
        long previous = preferences.getLong(REVIEWED, UNKNOWN);
        if (previous > revision) {
            throw new IllegalStateException("Repository review revision regressed");
        }
        preferences.putLong(REVIEWED, revision);
        preferences.flush();
    }

    /**
     * Caller MUST already have proved every remote object accepted and no
     * later remote revision was observed during the staged comparison.
     */
    public static void recordApplied(IProject project, String repository,
            String intermediary, String branch, long revision)
            throws BackingStoreException {
        requireRevision(revision);
        if (observed(project, repository, intermediary) != revision) {
            throw new IllegalStateException(
                "Remote version changed: applied checkpoint cannot advance");
        }
        var preferences = branchScope(project, repository, intermediary, branch);
        long previous = preferences.getLong(APPLIED, UNKNOWN);
        if (previous > revision) {
            throw new IllegalStateException(
                "Fully applied repository revision would regress");
        }
        preferences.putLong(APPLIED, revision);
        preferences.flush();
    }

    private static Preferences scope(IProject project, String repository,
            String intermediary) {
        Objects.requireNonNull(project);
        if (project.getLocationURI() == null
                || repository == null || repository.isBlank()
                || intermediary == null || intermediary.isBlank()) {
            throw new IllegalArgumentException("Unidentified repository receive source");
        }
        String context = project.getLocationURI().normalize()
            + "\u0000" + repository.trim() + "\u0000" + intermediary.trim();
        return InstanceScope.INSTANCE.getNode(ROOT)
            .node("repositoryRevisions").node(digest(context));
    }

    private static Preferences branchScope(IProject project, String repository,
            String intermediary, String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("Unidentified Git branch");
        }
        return scope(project, repository, intermediary)
            .node("branches").node(digest(branch));
    }

    private static void requireRevision(long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("Unverified repository revision");
        }
    }

    private static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
