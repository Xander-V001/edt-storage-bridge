package org.xander.edt.storagebridge.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.xander.edt.storagebridge.core.ProjectSnapshot;
import org.xander.edt.storagebridge.edt.EdtProjectInspector;

import com.google.inject.Inject;

/**
 * Read-only asynchronous diagnosis of the selected 1C:EDT workspace project.
 */
public final class InspectProjectHandler extends AbstractHandler {

    @Inject
    private EdtProjectInspector inspector;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        IProject project = findProject(selection);
        Shell shell = HandlerUtil.getActiveShell(event);

        if (project == null) {
            show(shell, "Выберите проект в навигаторе EDT и повторите команду.");
            return null;
        }
        if (!project.isAccessible()) {
            show(shell, "Проект закрыт или недоступен: " + project.getName());
            return null;
        }

        IPath location = project.getLocation();
        if (location == null) {
            show(shell, "Не удалось определить локальный каталог: " + project.getName());
            return null;
        }
        ProjectSnapshot snapshot = new ProjectSnapshot(project.getName(), location.toOSString());
        Display display = shell == null ? null : shell.getDisplay();

        Job job = new Job("EDT Storage Bridge — проверка модели") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }
                try {
                    boolean available = inspector.hasConfigurationModel(project);
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    String result = "Проект: " + snapshot.name() + "\n"
                        + "Каталог: " + snapshot.location() + "\n"
                        + "Модель конфигурации EDT: "
                        + (available ? "доступна" : "не получена") + "\n\n"
                        + "Только чтение. Хранилище 1С не опрашивалось.";
                    postResult(display, shell, result);
                    return Status.OK_STATUS;
                } catch (RuntimeException ex) {
                    BridgeActivator.getDefault().getLog().log(
                        new Status(IStatus.ERROR, BridgeActivator.PLUGIN_ID,
                            "Cannot inspect EDT configuration model", ex));
                    postResult(display, shell, "Проверка модели EDT завершилась с ошибкой. "
                        + "Подробности — в журнале ошибок Eclipse.");
                    return new Status(IStatus.ERROR, BridgeActivator.PLUGIN_ID,
                        "Project model inspection failed");
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void show(Shell shell, String text) {
        if (shell != null && !shell.isDisposed()) {
            MessageDialog.openInformation(shell, "EDT Storage Bridge — диагностика", text);
        }
    }

    private static void postResult(Display display, Shell shell, String text) {
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> show(shell, text));
        }
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
