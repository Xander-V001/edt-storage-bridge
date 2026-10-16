package org.xander.edt.storagebridge.ui;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

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
import org.xander.edt.storagebridge.edt.EdtReceiveService;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.ui.infobases.InfobaseSelectDialog;

/**
 * Explicit, user-triggered two-stage update: classic repository → connected
 * infobase → EDT model. Refuses unknown EDT sync state and unsafe local edits.
 *
 * <p>The selected EDT infobase MUST be the same one as the specified /F or /S
 * base, confirmed by the user. Automatic address mapping is pending.
 */
public final class ReceiveChangesController {
    private static final Set<IProject> ACTIVE_PROJECTS = ConcurrentHashMap.newKeySet();

    private ReceiveChangesController() {
    }

    /**
     * Fail-closed while selective compare/merge is not implemented.
     * Intentionally no callable production entry point into the prototype.
     */
    public static void start(IProject project, Shell shell) {
        if (project == null || !project.isAccessible() || shell == null || shell.isDisposed()) {
            return;
        }
        MessageDialog.openInformation(shell, "Получение из хранилища",
            "Получение временно заблокировано. Сначала необходимо подтвердить "
            + "предварительное сравнение и выборочное объединение в EDT 2026.2.");
        return;
    }

    // The mutating proof-of-concept below is retained for isolated review ONLY.
    // No GUI or public start() method can invoke it.
    private static void startUnverifiedPrototype(IProject project, Shell shell) {
        if (project == null || !project.isAccessible() || shell == null || shell.isDisposed()) {
            return;
        }
        final StorageProjectSettings.Snapshot settings;
        final RepositoryUpdateRequest request;
        try {
            settings = StorageProjectSettings.load(project);
            if (settings.address().isBlank() || settings.infobaseAddress().isBlank()
                    || settings.platformExecutable().isBlank()) {
                MessageDialog.openInformation(shell, "Получение из хранилища",
                    "Заполните и сохраните адрес хранилища, подключённую ИБ "
                    + "и путь к 1cv8.exe.");
                return;
            }
            var ibKind = settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER;
            request = new RepositoryUpdateRequest(
                Path.of(settings.platformExecutable()),
                new InfobaseLocation(ibKind, settings.infobaseAddress()),
                Duration.ofMinutes(20));
        } catch (StorageException | RuntimeException ex) {
            MessageDialog.openError(shell, "Получение из хранилища",
                "Не удалось прочитать настройки или выбран неверный путь к платформе 1С.");
            return;
        }

        final EdtReceiveService edt;
        try {
            edt = BridgeActivator.getDefault().getInjector()
                .getInstance(EdtReceiveService.class);
        } catch (RuntimeException ex) {
            MessageDialog.openError(shell, "Получение из хранилища",
                "Сервис синхронизации EDT недоступен. Проверьте Error Log.");
            return;
        }

        InfobaseSelectDialog chooser = new InfobaseSelectDialog(shell,
            edt.infobaseManager(), null, null,
            "Выберите в списке EDT ИМЕННО ту же информационную базу, "
            + "которая указана в настройках хранилища.");
        if (chooser.open() != Window.OK) {
            return;
        }
        InfobaseReference selected = chooser.getSelectedInfobase().orElse(null);
        if (selected == null) {
            return;
        }

        boolean approved = MessageDialog.openConfirm(shell,
            "Подтверждение получения",
            "Будет изменена КОНФИГУРАЦИЯ подключённой базы 1С:\n"
            + settings.infobaseAddress() + "\n\n"
            + "Подтвердите, что выбранная в EDT база — та же самая "
            + "и что её конфигурация не содержит незавершённой работы.\n"
            + "Команда получит изменения из хранилища, включая добавление и удаление "
            + "объектов (-force), но НЕ использует -revised.\n\n"
            + "После этого EDT импортирует изменения в выбранный проект, "
            + "только если обнаружит безопасное состояние для объединения.\n"
            + "Продолжить?");
        if (!approved) {
            return;
        }
        if (!ACTIVE_PROJECTS.add(project)) {
            MessageDialog.openInformation(shell, "Работа с хранилищем",
                "Для этого проекта уже выполняется получение.");
            return;
        }
        // Capture SWT display on the UI thread; Job must not call Widget#getDisplay.
        Display display = shell.getDisplay();
        Job task = new Job("EDT Storage Bridge — получить изменения") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Получение из хранилища в EDT", 3);
                try {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    GitReceiveGuard.Baseline gitBaseline =
                        GitReceiveGuard.requireCleanBranch(project);
                    monitor.worked(1);
                    // Fail closed BEFORE changing the connected 1C configuration.
                    if (!edt.hasKnownBaseline(project, selected)) {
                        show(display, shell, "Импорт не запущен: EDT не имеет "
                            + "подтверждённой истории синхронизации проекта с этой ИБ. "
                            + "Сначала свяжите их штатными средствами EDT.");
                        return Status.CANCEL_STATUS;
                    }
                    new RepositoryUpdateRunner().receive(request, monitor::isCanceled);
                    monitor.worked(1);

                    // Git branch / working tree must not have changed during Designer.
                    GitReceiveGuard.requireUnchanged(project, gitBaseline);
                    if (monitor.isCanceled()) {
                        show(display, shell, "Конфигурация ИБ могла измениться, импорт EDT не выполнялся.");
                        return Status.CANCEL_STATUS;
                    }
                    InfobaseChangesResolutionResult result =
                        edt.receive(project, selected, monitor);
                    monitor.worked(1);
                    if (result == InfobaseChangesResolutionResult.CHANGES_RESOLVED
                            || result == InfobaseChangesResolutionResult.NO_CHANGES) {
                        show(display, shell, "Этап получения в ИБ завершён, EDT ответила: "
                            + result.name() + ".\n"
                            + "Версия хранилища пока НЕ фиксируется автоматически: "
                            + "её нужно отдельно подтвердить по истории.");
                        return Status.OK_STATUS;
                    }
                    show(display, shell, "Конфигурация ИБ могла измениться. EDT вернула: "
                        + result.name() + ".\n"
                        + "Проект не объявлен обновлённым, контрольная версия не изменена. "
                        + "Проверьте конфликты в EDT.");
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    show(display, shell, "Операция прервана. Состояние ИБ требует проверки.");
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    // Exception messages from external tools may contain credentials.
                    show(display, shell, "Получение не подтверждено. "
                        + "Проверьте подключённую ИБ, связи проекта и журнал ошибок. "
                        + "Контрольная версия не изменена.");
                    return new Status(IStatus.ERROR, BridgeActivator.PLUGIN_ID,
                        "Repository receive did not reach a verified completed state");
                } finally {
                    ACTIVE_PROJECTS.remove(project);
                    monitor.done();
                }
            }
        };
        task.setUser(true);
        // Do NOT claim the IProject scheduling rule: EDT synchronization may
        // schedule child project jobs and would deadlock waiting for our rule.
        task.schedule();
    }

    private static void show(Display display, Shell shell, String text) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!shell.isDisposed()) {
                    MessageDialog.openInformation(shell, "Работа с хранилищем", text);
                }
            });
        }
    }
}
