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

/** Explicit, read-only first step toward EDT -> classic storage publishing. */
public final class OutgoingReadinessHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection == null) {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        IProject project = InspectProjectHandler.findProject(selection);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell,
                "Проверка отправки в хранилище",
                "Выберите открытый проект EDT в Навигаторе.");
            return null;
        }
        try {
            var settings = StorageProjectSettings.load(project);
            if (settings.address().isBlank()
                    || settings.infobaseAddress().isBlank()
                    || settings.platformExecutable().isBlank()) {
                MessageDialog.openInformation(shell,
                    "Проверка отправки в хранилище",
                    "Сначала настройте существующее подключение к хранилищу "
                    + "через «Работа с хранилищем».");
                return null;
            }
            OutgoingReadinessCoordinator.check(shell, project, settings);
        } catch (StorageException | RuntimeException ex) {
            MessageDialog.openError(shell,
                "Проверка отправки в хранилище",
                "Не удалось прочитать защищённые настройки проекта EDT.");
        }
        return null;
    }
}
