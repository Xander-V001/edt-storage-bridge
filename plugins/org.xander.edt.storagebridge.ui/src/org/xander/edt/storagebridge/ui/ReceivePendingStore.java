package org.xander.edt.storagebridge.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Persistent per-repository, per-project, per-user-Git-branch remote-object debt.
 *
 * An object is recorded as PENDING before showing the merge editor. Neither
 * cancellation nor a crash can silently mark it as applied. Clearing is
 * permitted ONLY after inspecting the resulting project object and proving the
 * corresponding remote content was received. No repository-wide revision
 * checkpoint is advanced here.
 */
public final class ReceivePendingStore {
    private static final String ROOT = "org.xander.edt.storagebridge.ui";
    private static final String ITEM = "obj-";
    private static final String INTERMEDIARY = "boundIntermediary";
    private ReceivePendingStore() {}

    public static Set<String> get(IProject project, String repositoryAddress,
            String branch, String intermediaryIdentity) throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch, intermediaryIdentity);
        Set<String> values = new LinkedHashSet<>();
        for (String key : node.keys()) {
            if (key.startsWith(ITEM)) {
                String value = node.get(key, "");
                if (value.isBlank()) {
                    throw new IllegalStateException("Corrupted pending object journal");
                }
                values.add(value);
            }
        }
        return Set.copyOf(values);
    }

    public static void add(IProject project, String repositoryAddress,
            String branch, String intermediaryIdentity, Set<String> names)
            throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch, intermediaryIdentity);
        for (String name : names) {
            requireObject(name);
            node.put(ITEM + digest(name), name);
        }
        node.flush();
    }

    /**
     * Call only after verifying the resulting EDT project object against
     * staged, repository-sourced EDT content. UI selections alone are NOT proof.
     */
    public static void clearVerified(IProject project, String repositoryAddress,
            String branch, String intermediaryIdentity, Set<String> verifiedObjects)
            throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch, intermediaryIdentity);
        // Validate the entire batch BEFORE the first mutation. Otherwise
        // a stale/missing record in the middle of the set could remove some
        // unrelated pending items and then fail the EDT completion.
        for (String name : verifiedObjects) {
            requireObject(name);
            if (!name.equals(node.get(ITEM + digest(name), ""))) {
                throw new IllegalStateException(
                    "Pending journal does not match verified metadata object");
            }
        }
        for (String name : verifiedObjects) {
            node.remove(ITEM + digest(name));
        }
        node.flush();
    }

    /**
     * A review can intentionally KEEP local changes instead of taking the
     * remote version byte-for-byte. Clear only identities that actually
     * appeared in the review opened by EDT and for which the developer
     * EXPLICITLY confirmed completion. Never invoke on editor close/cancel.
     */
    public static void acknowledgeReviewed(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> explicitlyReviewedNames) throws BackingStoreException {
        Objects.requireNonNull(explicitlyReviewedNames);
        Preferences node = node(project, repositoryAddress, branch,
            intermediaryIdentity);
        // Validate ALL requested identities BEFORE modifying any preference.
        for (String name : explicitlyReviewedNames) {
            requireObject(name);
            if (!name.equals(node.get(ITEM + digest(name), ""))) {
                throw new IllegalStateException(
                    "Reviewed metadata identity is not in the pending journal");
            }
        }
        for (String name : explicitlyReviewedNames) {
            node.remove(ITEM + digest(name));
        }
        node.flush();
    }

    private static Preferences node(IProject project, String repositoryAddress,
            String branch, String intermediaryIdentity) throws BackingStoreException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(repositoryAddress, "repositoryAddress");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(intermediaryIdentity, "intermediaryIdentity");
        if (branch.isBlank() || repositoryAddress.isBlank()
                || intermediaryIdentity.isBlank() || project.getLocationURI() == null) {
            throw new IllegalArgumentException("Unidentified receive context");
        }
        String key = digest(project.getLocationURI().normalize().toString()
            + "\u0000" + repositoryAddress.trim() + "\u0000" + branch);
        Preferences result = InstanceScope.INSTANCE.getNode(ROOT)
            .node("pendingReceive").node(key);
        String fingerprint = digest(intermediaryIdentity.trim());
        String previous = result.get(INTERMEDIARY, "");
        if (!previous.isBlank() && !previous.equals(fingerprint)) {
            throw new IllegalStateException(
                "Configured intermediary differs from pending remote changes");
        }
        if (previous.isBlank()) {
            // A v24 pending list has no recorded intermediary identity.
            // Never silently reinterpret it as coming from the current IB.
            for (String entry : result.keys()) {
                if (entry.startsWith(ITEM)) {
                    throw new IllegalStateException(
                        "Legacy remote changes require manual intermediary verification");
                }
            }
            result.put(INTERMEDIARY, fingerprint);
            result.flush();
        }
        return result;
    }

    private static void requireObject(String name) {
        if (name == null || name.isBlank() || name.length() > 1024
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..") || name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid metadata object");
        }
    }

    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(
                value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
