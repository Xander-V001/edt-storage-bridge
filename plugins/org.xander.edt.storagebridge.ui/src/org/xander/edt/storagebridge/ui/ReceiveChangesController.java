package org.xander.edt.storagebridge.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.xander.edt.storagebridge.edt.EdtReceiveService;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.ui.infobases.InfobaseSelectDialog;

/**
 * User-triggered classic storage receive into a connected IB, followed by
 * the native EDT interactive compare/merge into the existing Git task branch.
 *
 * <p>Never updates a Git branch, creates a technical branch, resets Git state,
 * or marks a classical repository revision as fully applied.
 * The callback and its comparison UI belong to EDT, not this plugin.
 */
public final class ReceiveChangesController {
    private static final Set<IProject> ACTIVE_PROJECTS = ConcurrentHashMap.newKeySet();

    private ReceiveChangesController() {
    }

    /** Returns true ONLY if a Job was scheduled (caller may close settings dialog). */
    public static boolean start(IProject project, Shell dialogShell) {
        if (project == null || !project.isAccessible()
                || dialogShell == null || dialogShell.isDisposed()) {
            return false;
        }
        // Use the workbench shell, not the settings dialog's transient shell.
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getShell().isDisposed()) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "Не найдено активное окно EDT.");
            return false;
        }
        Shell workbenchShell = window.getShell();
        Display display = workbenchShell.getDisplay();
        if (ACTIVE_PROJECTS.contains(project)) {
            MessageDialog.openInformation(dialogShell, "Работа с хранилищем",
                "Для проекта уже выполняется получение изменений.");
            return false;
        }

        final StorageProjectSettings.Snapshot settings;
        final RepositoryUpdateRequest request;
        try {
            settings = StorageProjectSettings.load(project);
            if (settings.address().isBlank() || settings.infobaseAddress().isBlank()
                    || settings.platformExecutable().isBlank()) {
                MessageDialog.openInformation(dialogShell, "Получение из хранилища",
                    "Укажите адрес хранилища, подключённую ИБ и путь к 1cv8.exe.");
                return false;
            }
            InfobaseLocation.Kind kind =
                settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                    ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER;
            request = new RepositoryUpdateRequest(Path.of(settings.platformExecutable()),
                new InfobaseLocation(kind, settings.infobaseAddress()),
                Duration.ofMinutes(20));
        } catch (StorageException | RuntimeException ex) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "Не удалось прочитать настройки или путь к платформе.");
            return false;
        }

        final EdtReceiveService edt;
        try {
            edt = BridgeActivator.getDefault().getInjector()
                .getInstance(EdtReceiveService.class);
        } catch (RuntimeException ex) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "Сервисы сравнения и синхронизации EDT недоступны.");
            return false;
        }

        InfobaseSelectDialog chooser = new InfobaseSelectDialog(
            dialogShell, edt.infobaseManager(), null, null,
            "Выберите ИБ, уже связанную с этим проектом EDT.");
        if (chooser.open() != Window.OK) {
            return false;
        }
        InfobaseReference selected = chooser.getSelectedInfobase().orElse(null);
        if (!InfobaseIdentityMatcher.matches(
                selected, settings.infobaseType(), settings.infobaseAddress())) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "Выбранная в EDT ИБ не совпадает с адресом ИБ в настройках. "
                + "Получение остановлено до изменения каких-либо данных.");
            return false;
        }
        // This is BEFORE launching Designer or any command that can change the IB.
        if (!edt.hasKnownBaseline(project, selected)) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "EDT не имеет подтверждённой синхронизации этого проекта с "
                + "выбранной ИБ. Сначала установите связь штатными средствами EDT.");
            return false;
        }

        final IInfobaseChangesResolver interactiveMerge;
        try {
            interactiveMerge = edt.newInteractiveMerge(workbenchShell);
        } catch (RuntimeException ex) {
            MessageDialog.openError(dialogShell, "Работа с хранилищем",
                "Не удалось подготовить штатное окно сравнения EDT.");
            return false;
        }
        boolean approved = MessageDialog.openConfirm(dialogShell,
            "Подтвердите получение",
            "Будет изменена конфигурация подключённой ИБ:\n"
                + settings.infobaseAddress() + "\n\n"
                + "Не должно быть незавершённых изменений и захватов "
                + "в Конфигураторе.\n"
                + "Будет использовано получение из хранилища (-force, БЕЗ -revised).\n"
                + "После этого EDT покажет штатное сравнение и объединение. "
                + "Вы сами определите, что принять в проект.\n\n"
                + "Продолжить?");
        if (!approved) {
            return false;
        }
        if (!ACTIVE_PROJECTS.add(project)) {
            MessageDialog.openInformation(dialogShell, "Работа с хранилищем",
                "Для проекта уже выполняется получение изменений.");
            return false;
        }

        Job job = new Job("EDT Storage Bridge: получить изменения") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Хранилище 1С → ИБ → сравнение EDT", 3);
                try {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    GitReceiveGuard.Baseline gitBaseline =
                        GitReceiveGuard.requireCleanBranch(project);
                    monitor.worked(1);
                    if (!edt.hasKnownBaseline(project, selected)) {
                        notifyUser(display, workbenchShell,
                            "Состояние синхронизации EDT изменилось. "
                            + "Получение в ИБ не запускалось.");
                        return Status.CANCEL_STATUS;
                    }

                    new RepositoryUpdateRunner().receive(request, monitor::isCanceled);
                    monitor.worked(1);
                    GitReceiveGuard.requireUnchanged(project, gitBaseline);
                    if (monitor.isCanceled()) {
                        notifyUser(display, workbenchShell,
                            "Получение в ИБ могло завершиться, но импорт EDT отменён. "
                            + "Проверьте состояние ИБ.");
                        return Status.CANCEL_STATUS;
                    }

                    InfobaseChangesResolutionResult result =
                        edt.receive(project, selected, interactiveMerge, monitor);
                    monitor.worked(1);
                    if (result == InfobaseChangesResolutionResult.NO_CHANGES
                            || result == InfobaseChangesResolutionResult.CHANGES_RESOLVED) {
                        notifyUser(display, workbenchShell,
                            "EDT завершила этап обработки изменений: " + result.name()
                            + ". Версия хранилища автоматически НЕ фиксируется.");
                        return Status.OK_STATUS;
                    }
                    // The native comparison editor might still be open when
                    // CHANGES_NOT_RESOLVED is returned. No success claim.
                    notifyUser(display, workbenchShell,
                        "Штатное сравнение EDT: " + result.name()
                        + ". Если редактор объединения открыт, завершите выбор "
                        + "объектов вручную. Версия хранилища не изменена в настройках.");
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    notifyUser(display, workbenchShell,
                        "Обновление прервано. Проверьте состояние подключённой ИБ.");
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    // Never display third-party messages, which could contain
                    // credentials, paths or other sensitive connection details.
                    notifyUser(display, workbenchShell,
                        "Обновление не подтверждено. Проверьте состояние ИБ и EDT. "
                        + "Версия хранилища не фиксировалась.");
                    return new Status(IStatus.ERROR, BridgeActivator.PLUGIN_ID,
                        "Receive was not fully verified");
                } finally {
                    ACTIVE_PROJECTS.remove(project);
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        // Do NOT hold an IProject workspace scheduling rule while calling EDT:
        // EDT itself may schedule jobs that would deadlock on that same rule.
        job.schedule();
        return true;
    }

    private static void notifyUser(Display display, Shell shell, String message) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!shell.isDisposed()) {
                    MessageDialog.openInformation(shell, "Работа с хранилищем", message);
                }
            });
        }
    }
}
