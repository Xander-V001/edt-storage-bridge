package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * One MCP endpoint for the entire EDT workspace, never per project.
 * Optional bearer credential is stored only in Eclipse Secure Storage.
 * An empty token permits local-only access (127.0.0.1); remote bind is never supported.
 */
final class BridgeMcpSettings {
    static final int DEFAULT_PORT = 8769;
    private static final String NODE = "org.xander.edt.storagebridge.ui";
    private static final String TOKEN = "bearerToken";
    private static final String PORT = "port";
    private static final String AUTO_START = "autoStart";
    private static final String AUTH_MODE = "authMode";
    private static final String AUTH_NONE = "none";
    private static final String AUTH_TOKEN = "token";

    // Fresh workspaces use token-free loopback access. Legacy configured
    // workspaces keep their previously saved token until explicitly cleared.
    record Snapshot(int port, String token, boolean autoStart) {}

    private BridgeMcpSettings() {}

    static boolean autoStartEnabled() {
        return preferences().getBoolean(AUTO_START, true);
    }

    static Snapshot load() throws StorageException {
        Preferences preferences = preferences();
        int port = preferences.getInt(PORT, DEFAULT_PORT);
        if (port < 1024 || port > 65535) port = DEFAULT_PORT;

        String mode = preferences.get(AUTH_MODE, "");
        // Old releases saved a port and a mandatory token, but no mode.
        // Do not accidentally turn off authentication when upgrading one.
        boolean legacyWithSettings = mode.isEmpty()
            && preferences.get(PORT, null) != null;
        String token = AUTH_NONE.equals(mode) || (mode.isEmpty() && !legacyWithSettings)
            ? "" : secrets().get(TOKEN, "");
        return new Snapshot(port, token, preferences.getBoolean(AUTO_START, true));
    }

    static void save(int port, String token)
            throws StorageException, BackingStoreException, IOException {
        save(port, token, preferences().getBoolean(AUTO_START, true));
    }

    static void save(int port, String token, boolean autoStart)
            throws StorageException, BackingStoreException, IOException {
        validate(port, token);
        // Do not touch Secure Storage for anonymous loopback mode: otherwise
        // a fresh installation could prompt for a master password on startup.
        if (!token.isEmpty()) {
            ISecurePreferences node = secrets();
            node.put(TOKEN, token, true);
            node.flush();
        }
        Preferences preferences = preferences();
        preferences.putInt(PORT, port);
        preferences.putBoolean(AUTO_START, autoStart);
        preferences.put(AUTH_MODE, token.isEmpty() ? AUTH_NONE : AUTH_TOKEN);
        preferences.flush();
    }

    static void validate(int port, String token) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Порт MCP: укажите число от 1024 до 65535.");
        }
        if (token == null || (!token.isEmpty() && !token.matches("[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("Токен MCP: оставьте поле пустым для локального доступа или укажите 64 шестнадцатеричных символа.");
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String url(int port) {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    private static Preferences preferences() {
        return InstanceScope.INSTANCE.getNode(NODE).node("mcp");
    }

    private static ISecurePreferences secrets() {
        ISecurePreferences storage = SecurePreferencesFactory.getDefault();
        if (storage == null) {
            throw new IllegalStateException("Secure Storage EDT недоступен.");
        }
        return storage.node(NODE).node("mcp");
    }
}
