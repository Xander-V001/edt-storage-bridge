package org.xander.edt.storagebridge.ui;

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
 * The bearer credential is stored only in Eclipse Secure Storage.
 */
final class BridgeMcpSettings {
    static final int DEFAULT_PORT = 8769;
    private static final String NODE = "org.xander.edt.storagebridge.ui";
    private static final String TOKEN = "bearerToken";
    private static final String PORT = "port";

    record Snapshot(int port, String token) {}

    private BridgeMcpSettings() {}

    static Snapshot load() throws StorageException {
        int port = preferences().getInt(PORT, DEFAULT_PORT);
        if (port < 1024 || port > 65535) port = DEFAULT_PORT;
        return new Snapshot(port, secrets().get(TOKEN, ""));
    }

    static void save(int port, String token)
            throws StorageException, BackingStoreException {
        validate(port, token);
        ISecurePreferences node = secrets();
        node.put(TOKEN, token, true);
        node.flush();
        Preferences preferences = preferences();
        preferences.putInt(PORT, port);
        preferences.flush();
    }

    static void validate(int port, String token) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Порт MCP: укажите число от 1024 до 65535.");
        }
        if (token == null || !token.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Токен MCP должен содержать 64 шестнадцатеричных символа.");
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
