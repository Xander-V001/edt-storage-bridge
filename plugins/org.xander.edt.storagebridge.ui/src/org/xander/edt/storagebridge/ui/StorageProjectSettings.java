package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Local, workspace-scoped configuration of one EDT project.
 *
 * <p>Connection metadata/checkpoints live in workspace instance preferences,
 * never in project files or Git. Passwords are stored exclusively in encrypted
 * Eclipse Secure Storage. The two repository revisions are intentionally distinct.
 */
public final class StorageProjectSettings {

    private static final String NODE = "org.xander.edt.storagebridge.ui";
    private static final String ADDRESS = "repositoryAddress";
    private static final String USER = "repositoryUser";
    private static final String PASSWORD = "repositoryPassword";
    private static final String LAST_REMOTE = "lastObservedRemoteVersion";
    private static final String LAST_APPLIED = "lastVerifiedAppliedVersion";
    public static final long UNKNOWN_VERSION = -1L;

    private StorageProjectSettings() {
    }

    public static final class Snapshot {
        private final String address;
        private final String username;
        private final String password;
        private final long observedVersion;
        private final long appliedVersion;

        private Snapshot(String address, String username, String password,
                long observedVersion, long appliedVersion) {
            this.address = address;
            this.username = username;
            this.password = password;
            this.observedVersion = observedVersion;
            this.appliedVersion = appliedVersion;
        }

        public String address() { return address; }
        public String username() { return username; }
        public String password() { return password; }
        public long observedVersion() { return observedVersion; }
        public long appliedVersion() { return appliedVersion; }

        @Override
        public String toString() {
            return "StorageProjectSettings.Snapshot[credentials=REDACTED]";
        }
    }

    public static Snapshot load(IProject project) throws StorageException {
        Preferences preferences = preferences(project);
        ISecurePreferences secretNode = secureNode(project);
        return new Snapshot(
            preferences.get(ADDRESS, ""),
            preferences.get(USER, ""),
            secretNode.get(PASSWORD, ""),
            preferences.getLong(LAST_REMOTE, UNKNOWN_VERSION),
            preferences.getLong(LAST_APPLIED, UNKNOWN_VERSION));
    }

    /** Save connection settings; changing storage address invalidates both revisions. */
    public static void save(IProject project, String address, String username,
            String password) throws StorageException, IOException, BackingStoreException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        String normalizedAddress = address.trim();
        if (normalizedAddress.isBlank()) {
            throw new IllegalArgumentException("Укажите адрес хранилища.");
        }
        Preferences preferences = preferences(project);
        ISecurePreferences secretNode = secureNode(project);

        // Never continue to write unprotected preferences if encryption fails.
        secretNode.put(PASSWORD, password, true);
        secretNode.flush();

        boolean changed = !normalizedAddress.equals(preferences.get(ADDRESS, ""));
        preferences.put(ADDRESS, normalizedAddress);
        preferences.put(USER, username.trim());
        if (changed) {
            // A checkpoint from storage A is invalid for storage B.
            preferences.remove(LAST_REMOTE);
            preferences.remove(LAST_APPLIED);
        }
        preferences.flush();
    }

    /**
     * Called by future read-only transport ONLY after remote version is verified.
     * No transport has been implemented or invokes this method yet.
     */
    public static void recordObservedRemoteVersion(IProject project, long version)
            throws BackingStoreException {
        requireVersion(version);
        Preferences preferences = preferences(project);
        preferences.putLong(LAST_REMOTE, version);
        preferences.flush();
    }

    /**
     * Called by future synchronization coordinator ONLY after receive, conflict
     * resolution, EDT import and verification have all succeeded.
     * This method is deliberately not connected to UI actions yet.
     */
    public static void recordVerifiedAppliedVersion(IProject project, long version)
            throws BackingStoreException {
        requireVersion(version);
        Preferences preferences = preferences(project);
        preferences.putLong(LAST_APPLIED, version);
        preferences.flush();
    }

    private static void requireVersion(long version) {
        if (version < 1) {
            throw new IllegalArgumentException("Версия хранилища должна быть положительной.");
        }
    }

    private static Preferences preferences(IProject project) {
        return InstanceScope.INSTANCE.getNode(NODE + "/projects/" + projectKey(project));
    }

    private static ISecurePreferences secureNode(IProject project) {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        if (root == null) {
            throw new IllegalStateException("Безопасное хранилище Eclipse недоступно.");
        }
        return root.node(NODE + "/projects/" + projectKey(project));
    }

    private static String projectKey(IProject project) {
        Objects.requireNonNull(project, "project");
        if (project.getLocationURI() == null) {
            throw new IllegalArgumentException("Не удалось определить путь проекта EDT.");
        }
        String location = project.getLocationURI().normalize().toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                location.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 недоступен.", ex);
        }
    }
}
