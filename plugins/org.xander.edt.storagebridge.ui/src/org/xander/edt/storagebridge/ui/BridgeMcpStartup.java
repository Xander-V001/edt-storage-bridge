package org.xander.edt.storagebridge.ui;

import java.io.IOException;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.ui.IStartup;

/**
 * Workbench startup hook. Eclipse calls earlyStartup on a background thread,
 * whether or not the user ever opens the Storage Bridge settings dialog.
 *
 * No token generation, UI dialogs, remote connections or 1C operations here.
 */
public final class BridgeMcpStartup implements IStartup {
    @Override
    public void earlyStartup() {
        try {
            if (!BridgeMcpSettings.autoStartEnabled()) return;

            // A fresh workspace has no credential. A user must configure MCP
            // once in EDT before an automatic listener can be created.
            BridgeMcpSettings.Snapshot settings = BridgeMcpSettings.load();
            if (settings.token().isBlank()) return;
            BridgeMcpSettings.validate(settings.port(), settings.token());

            BridgeMcpServer.INSTANCE.start(settings.port(), settings.token());
            log(IStatus.INFO, "MCP автоматически запущен на 127.0.0.1:"
                + settings.port() + ".");
        } catch (StorageException ex) {
            log(IStatus.WARNING, "Автозапуск MCP не выполнен: "
                + "не удалось прочитать токен из Eclipse Secure Storage.");
        } catch (IOException ex) {
            log(IStatus.WARNING, "Автозапуск MCP не выполнен: "
                + "порт недоступен или занят другим процессом.");
        } catch (RuntimeException ex) {
            // Never log a token, raw Secure Storage exception or credentials.
            log(IStatus.WARNING, "Автозапуск MCP не выполнен: "
                + ex.getClass().getSimpleName() + ".");
        }
    }

    private static void log(int severity, String message) {
        Platform.getLog(BridgeMcpStartup.class).log(
            new Status(severity, BridgeActivator.PLUGIN_ID, message));
    }
}
