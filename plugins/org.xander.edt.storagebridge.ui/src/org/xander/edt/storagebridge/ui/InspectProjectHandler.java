package org.xander.edt.storagebridge.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.xander.edt.storagebridge.core.ProjectSnapshot;

/**
 * Read-only command: identifies an Eclipse project without touching a 1C repository.
 */
public final class InspectProjectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        IProject project = findProject(selection);
        String message;

        if (project == null) {
            message = "Выберите проект в навигаторе EDT и повторите команду.";
        } else if (!project.isAccessible()) {
            message = "Проект закрыт или недоступен: " + project.getName();
        } else {
            IPath location = project.getLocation();
            if (location == null) {
                message = "Для проекта не удалось определить локальный путь: " + project.getName();
            } else {
                ProjectSnapshot snapshot = new ProjectSnapshot(project.getName(),
                    location.toOSString());
                message = "Проект: " + snapshot.name() + "\n"
                    + "Каталог: " + snapshot.location() + "\n\n"
                    + "Режим: только чтение. Обмен с хранилищем не выполнялся.";
            }
        }

        MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
            "EDT Storage Bridge — диагностика", message);
        return null;
    }

    static IProject findProject(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured)
                || structured.isEmpty()) {
            return null;
        }
        Object first = structured.getFirstElement();
        if (first instanceof IProject project) {
            return project;
        }
        if (first instanceof IResource resource) {
            return resource.getProject();
        }
        if (first instanceof IAdaptable adaptable) {
            IProject project = adaptable.getAdapter(IProject.class);
            if (project != null) {
                return project;
            }
            IResource resource = adaptable.getAdapter(IResource.class);
            if (resource != null) {
                return resource.getProject();
            }
        }
        return null;
    }
}
