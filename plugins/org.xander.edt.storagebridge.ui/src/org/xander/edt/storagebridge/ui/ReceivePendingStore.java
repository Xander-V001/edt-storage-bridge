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
    // Remote changes deliberately reviewed but retained as local EDT code.
    // They are NOT pending review, nor proof of an APPLIED repository mirror.
    private static final String KEPT_LOCAL = "kept-";
    // Native Merge finished but EDT individual selection was not proven.
    private static final String CHOICE_UNKNOWN = "unknown-choice-";
    // A finished GLOBAL native EDT review supersedes a preceding selective
    // choice receipt even when the local code may differ. Keep the old
    // uncertain identity in this audit namespace, never mark it ACCEPTED.
    private static final String FULL_REVIEWED_UNKNOWN = "full-reviewed-unknown-";
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
            node.remove(CHOICE_UNKNOWN + digest(name));
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
            // If a later repository revision is now exactly applied, clear
            // the previous explicit keep-local decision for this identity.
            node.remove(KEPT_LOCAL + digest(name));
            node.remove(CHOICE_UNKNOWN + digest(name));
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
            String key = digest(name);
            node.put(KEPT_LOCAL + key, name);
            node.remove(ITEM + key);
        }
        node.flush();
    }

    /**
     * After EDT Merge, the user explicitly identifies which presented
     * 1C changes were intentionally retained locally. Persist those choices
     * by exact native identity, not serialized EDT bytes. The remaining
     * presented identities were explicitly confirmed as reviewed and accepted.
     * On a NEW native delta, an earlier kept-local marker is superseded only
     * by the user's current choice for that SAME native metadata identity.
     */
    public static void acknowledgeNativeReviewedScope(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> presentedNames, Set<String> explicitlyKeptLocal)
            throws BackingStoreException {
        Objects.requireNonNull(presentedNames);
        Objects.requireNonNull(explicitlyKeptLocal);
        if (!presentedNames.containsAll(explicitlyKeptLocal)) {
            throw new IllegalArgumentException(
                "Local choices must belong to the native EDT review scope");
        }
        Preferences node = node(project, repositoryAddress, branch,
            intermediaryIdentity);
        // Validate all entries before the FIRST preference modification.
        for (String name : presentedNames) {
            requireObject(name);
            if (!name.equals(node.get(ITEM + digest(name), ""))) {
                throw new IllegalStateException(
                    "Native EDT review scope is not in current pending journal");
            }
        }
        for (String name : presentedNames) {
            String id = digest(name);
            if (explicitlyKeptLocal.contains(name)) {
                // Preserve a real human choice even though we cannot inspect
                // individual EDT Merge checkboxes after its session closes.
                node.put(KEPT_LOCAL + id, name);
            } else {
                // Explicitly reviewed as received; clear a previously kept
                // local decision for this newer 1C change.
                node.remove(KEPT_LOCAL + id);
            }
            node.remove(ITEM + id);
            node.remove(CHOICE_UNKNOWN + id);
        }
        node.flush();
    }


    /**
     * Native EDT Merge completed and all pending identities were presented.
     * Exact per-object accepted/retained choices are unavailable; record
     * UNKNOWN rather than inventing a zero kept-local count. Store UNKNOWN
     * before removing PENDING so crashes cannot silently lose the only trace.
     */
    public static void acknowledgeNativeReviewedUnknown(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> names) throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch,
            intermediaryIdentity);
        for (String name : names) {
            requireObject(name);
            if (!name.equals(node.get(ITEM + digest(name), ""))) {
                throw new IllegalStateException("EDT review scope differs from PENDING");
            }
        }
        for (String name : names) {
            String id = digest(name);
            node.put(CHOICE_UNKNOWN + id, name);
            node.remove(KEPT_LOCAL + id);
        }
        node.flush();
        for (String name : names) node.remove(ITEM + digest(name));
        node.flush();
    }

    /**
     * ONLY AFTER a native FINISHED global comparison/merge, verified source
     * HEAD, and EMPTY PENDING. Previous selective UNKNOWN choices have now
     * been reviewed again in a newer global comparison. They are no longer
     * current SELECTIVE-choice debt, but their original acceptance remains
     * unproven. Archive by exact original identity before deleting active
     * markers. This does NOT mean remote code was accepted or fully applied.
     */
    public static int archiveUnknownAfterGlobalReview(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity)
            throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch,
            intermediaryIdentity);
        if (!get(project, repositoryAddress, branch,
                intermediaryIdentity).isEmpty()) {
            throw new IllegalStateException(
                "Cannot archive review choices while PENDING exists");
        }
        Set<String> names = unknownChoices(
            project, repositoryAddress, branch, intermediaryIdentity);
        if (names.isEmpty()) return 0;
        // Crash ordering: first make every identity durable in the historical
        // namespace, THEN clear active markers. Never erase the only trace.
        for (String name : names) {
            node.put(FULL_REVIEWED_UNKNOWN + digest(name), name);
        }
        node.flush();
        for (String name : names) {
            node.remove(CHOICE_UNKNOWN + digest(name));
        }
        node.flush();
        return names.size();
    }

    public static Set<String> unknownChoices(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity)
            throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch,
            intermediaryIdentity);
        Set<String> result = new LinkedHashSet<>();
        for (String key : node.keys()) {
            if (key.startsWith(CHOICE_UNKNOWN)) {
                String name = node.get(key, "");
                requireObject(name);
                if (!key.equals(CHOICE_UNKNOWN + digest(name))) {
                    throw new IllegalStateException("Corrupt unknown EDT choice entry");
                }
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    /** Legacy no-keep convenience; only call when the user explicitly chose
     * zero locally retained changes, never based on serialized EDT equality. */
    public static void acknowledgeNativeReviewedScope(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> presentedNames) throws BackingStoreException {
        acknowledgeNativeReviewedScope(project, repositoryAddress, branch,
            intermediaryIdentity, presentedNames, Set.of());
    }

    /**
     * Actual locally-retained differences, separately persisted from PENDING.
     * No further full review is forced just because a developer explicitly
     * kept committed local changes on a previously reviewed remote revision.
     */
    public static Set<String> keptLocal(IProject project, String repositoryAddress,
            String branch, String intermediaryIdentity) throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch, intermediaryIdentity);
        Set<String> kept = new LinkedHashSet<>();
        for (String key : node.keys()) {
            if (key.startsWith(KEPT_LOCAL)) {
                String name = node.get(key, "");
                requireObject(name);
                if (!key.equals(KEPT_LOCAL + digest(name))) {
                    throw new IllegalStateException("Corrupt retained-local journal entry");
                }
                kept.add(name);
            }
        }
        return Set.copyOf(kept);
    }

    /**
     * Global EDT equality was proven against the full native source: there
     * are no longer any known intentionally retained remote differences.
     */
    public static void clearKeptLocalAfterGlobalEquality(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity)
            throws BackingStoreException {
        Preferences node = node(project, repositoryAddress, branch, intermediaryIdentity);
        for (String key : node.keys()) {
            if (key.startsWith(KEPT_LOCAL) || key.startsWith(CHOICE_UNKNOWN)) {
                node.remove(key);
            }
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
