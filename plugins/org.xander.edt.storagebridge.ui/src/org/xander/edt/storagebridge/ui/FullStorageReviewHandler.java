package org.xander.edt.storagebridge.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Explicit user-invoked full repository comparison, separate from incremental
 * receive. No repository mutation happens without the coordinator's confirmation.
 */
public final class FullStorageReviewHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection == null) {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        IProject project = InspectProjectHandler.findProject(selection);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Получить полную конфигурацию из хранилища",
                "Выберите открытый проект EDT в Навигаторе.");
            return null;
        }
        try {
            var settings = StorageProjectSettings.load(project);
            if (settings.address().isBlank()
                    || settings.infobaseAddress().isBlank()
                    || settings.platformExecutable().isBlank()) {
                MessageDialog.openInformation(shell, "Получить полную конфигурацию из хранилища",
                    "Сначала заполните и сохраните подключение через "
                    + "«Работа с хранилищем».");
                return null;
            }
            ReceiveChangesCoordinator.receive(shell, project, settings,
                ReceiveChangesCoordinator.ReceiveMode.FULL_REVIEW);
        } catch (StorageException | RuntimeException ex) {
            MessageDialog.openError(shell, "Получить полную конфигурацию из хранилища",
                "Не удалось прочитать настройки проекта из Secure Storage EDT.");
        }
        return null;
    }
}
