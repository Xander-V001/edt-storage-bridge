package org.xander.edt.storagebridge.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Scope of an ACTUALLY CONFIRMED native repository publication, distinct
 * from last observed/reviewed/received revision. No read-only path writes it.
 */
public final class OutgoingPublishedGitStore {
    private static final String ROOT = "org.xander.edt.storagebridge.ui";
    public record Baseline(String head, long revision) {}
    private OutgoingPublishedGitStore() {}

    public static Baseline read(IProject project, String repo,
            String intermediary, String branch) {
        var prefs = node(project, repo, intermediary, branch);
        String head = prefs.get("confirmedPublishHead", "");
        long revision = prefs.getLong("confirmedRepositoryRevision", -1L);
        if (revision < 1 || !head.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
            return null;
        return new Baseline(head, revision);
    }

    /**
     * FUTURE writer only after verifying native Commit result and Git HEAD;
     * never call on receive or read-only preflight.
     */
    public static void recordVerifiedPublication(IProject project, String repo,
            String intermediary, String branch, String head, long revision)
            throws BackingStoreException {
        if (revision < 1 || head == null
                || !head.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Missing verified publish identity");
        var prefs = node(project, repo, intermediary, branch);
        if (revision <= prefs.getLong("confirmedRepositoryRevision", -1))
            throw new IllegalStateException("Repository revision did not advance");
        prefs.remove("confirmedPublishHead");
        prefs.flush();
        prefs.putLong("confirmedRepositoryRevision", revision);
        prefs.put("confirmedPublishHead", head);
        prefs.flush();
    }

    private static Preferences node(IProject project, String repo,
            String intermediary, String branch) {
        if (project == null || project.getLocationURI() == null
                || repo == null || repo.isBlank()
                || intermediary == null || intermediary.isBlank()
                || branch == null || branch.isBlank())
            throw new IllegalArgumentException("Missing repository context");
        String scope = project.getLocationURI().normalize().toString()
            + (char) 0 + repo.trim() + (char) 0
            + intermediary.trim() + (char) 0 + branch;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.UTF_8)));
            return InstanceScope.INSTANCE.getNode(ROOT)
                .node("confirmedPublications").node(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
