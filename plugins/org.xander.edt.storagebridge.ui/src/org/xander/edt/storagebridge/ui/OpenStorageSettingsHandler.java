package org.xander.edt.storagebridge.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open repository settings for the single selected workspace project. */
public final class OpenStorageSettingsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = InspectProjectHandler.findProject(
            HandlerUtil.getCurrentSelection(event));
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Работа с хранилищем",
                "Выберите открытый проект EDT в Навигаторе.");
            return null;
        }
        new StorageSettingsDialog(shell, project).open();
        return null;
    }
}
