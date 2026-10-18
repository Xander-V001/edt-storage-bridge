package org.xander.edt.storagebridge.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.OutgoingBslPreviewPlan;

/**
 * Explicit user-triggered native EDT BSL preview. NEVER calls Designer,
 * repository Lock/Load/Commit or modifies the EDT project or its Git.
 */
public final class OutgoingNativeExportPreviewHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        var selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection items)
                || items.isEmpty() || items.size() > 128) {
            show(shell, "Выберите 1–128 файлов BSL в Навигаторе EDT.");
            return null;
        }
        List<String> selectedPaths = new ArrayList<>();
        IProject project = null;
        for (Object value : items.toList()) {
            IFile file = value instanceof IFile f ? f
                : value instanceof org.eclipse.core.runtime.IAdaptable adaptable
                    ? adaptable.getAdapter(IFile.class) : null;
            if (file == null || !file.exists()) {
                show(shell, "Для экспорта нужны существующие файлы BSL.");
                return null;
            }
            if (project == null) project = file.getProject();
            if (!project.equals(file.getProject())) {
                show(shell, "Выберите файлы одного проекта EDT.");
                return null;
            }
            selectedPaths.add(file.getProjectRelativePath().toPortableString());
        }
        if (project == null || !project.isAccessible()) return null;
        final IProject targetProject = project;
        final List<String> sourcePaths = List.copyOf(selectedPaths);
        try {
            // Validate before confirmation, native model resolution and temp I/O.
            OutgoingBslPreviewPlan.of(sourcePaths);
        } catch (IllegalArgumentException ex) {
            show(shell, "Пробный экспорт пока допускает только модули общих "
                + "модулей и модули менеджера/объекта документов.");
            return null;
        }
        if (!MessageDialog.openConfirm(shell, "Пробный экспорт EDT",
                "Выполнить штатный экспорт выделенных модулей в отдельный "
                + "временный каталог?\n\nБаза 1С и хранилище не изменяются.")) {
            return null;
        }
        Display display = shell.getDisplay();
        Job job = new Job("EDT Storage Bridge — пробный экспорт BSL") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    Path destination = Files.createTempDirectory(
                        "edt-storage-outgoing-preview-");
                    var result = EdtPublicServiceBridge.previewNativeBslExport(
                        targetProject, sourcePaths, destination, monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    StringBuilder message = new StringBuilder(
                        "Штатный экспорт выбранных объектов выполнен.\n"
                        + "В хранилище и базу 1С ничего не записано.\n\n"
                        + "Временный каталог: " + result.directory() + "\n"
                        + "Объектов: " + result.exportedOwners().size()
                        + "\nКандидаты для частичной загрузки:\n");
                    for (String file : result.platformLoadCandidates()) {
                        message.append("• ").append(file).append('\n');
                    }
                    final String completed = message.toString();
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openInformation(shell,
                                "Пробный экспорт выполнен", completed);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (Exception ex) {
                    IStatus status = new Status(IStatus.ERROR,
                        BridgeActivator.PLUGIN_ID,
                        "Native EDT BSL export preview failed", ex);
                    Platform.getLog(OutgoingNativeExportPreviewHandler.class)
                        .log(status);
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Пробный экспорт не выполнен",
                                "Проверьте Error Log EDT. База 1С и "
                                + "хранилище не изменялись.");
                        }
                    });
                    return status;
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void show(Shell shell, String message) {
        if (shell != null && !shell.isDisposed())
            MessageDialog.openInformation(shell, "Пробный экспорт EDT", message);
    }
}
