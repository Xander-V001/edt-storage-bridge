package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.ReportExecutionException;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;
import org.xander.edt.storagebridge.platform.RepositoryReportFormatException;

/**
 * Explicit read-only repository status probe, independent of XML receive.
 * Does not update the intermediary, import into EDT or alter the applied
 * checkpoint. The version comes only from a validated native 1C TXT report.
 */
public final class RepositoryRevisionRefreshCoordinator {
    private RepositoryRevisionRefreshCoordinator() {}

    /**
     * Call on the SWT thread; callbacks also run on the SWT thread. The
     * settings dialog can be closed while this job is running.
     */
    public static void refresh(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            Consumer<Long> onSuccess, Consumer<String> onFailure) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(onSuccess);
        Objects.requireNonNull(onFailure);
        Display display = shell.getDisplay();
        Job job = new Job("1С: Проверка последней версии хранилища — "
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Чтение последней версии хранилища 1С", 1);
                try {
                    var type = settings.infobaseType()
                        == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER;
                    var base = new InfobaseLocation(type,
                        settings.infobaseAddress());
                    long latest = RepositoryLatestRevisionReader.latest(
                        RepositoryHistoryRequest.latest(
                            Path.of(settings.platformExecutable()).toAbsolutePath(),
                            base, Duration.ofMinutes(5)),
                        settings.infobaseUsername(), settings.infobasePassword(),
                        settings.address(), settings.username(),
                        settings.password(), monitor::isCanceled);
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    ReceiveRevisionStore.recordObserved(
                        project, settings.address(), intermediary, latest);
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            onSuccess.accept(latest);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    String reason = failureReason(ex);
                    // No raw native Designer messages, credentials or file
                    // contents are logged. The reason is from an allowlist.
                    BridgeActivator.getDefault().getLog()
                        .warn("Repository revision refresh failed: " + reason);
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            onFailure.accept(reason);
                        }
                    });
                    return new Status(IStatus.WARNING, BridgeActivator.PLUGIN_ID,
                        reason);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    public static String failureReason(Exception ex) {
        if (ex instanceof ReportExecutionException failure) {
            return switch (failure.reason()) {
                case INVALID_EXECUTABLE -> "Исполняемый файл 1С не найден.";
                case EXIT_CODE -> "Конфигуратор не смог построить отчёт хранилища"
                    + (failure.exitCode() == null ? "."
                        : " (код завершения " + failure.exitCode() + ").");
                case TIMEOUT -> "Превышено время ожидания отчёта хранилища.";
                case CANCELLED -> "Чтение отчёта отменено.";
                case OVERSIZED_OUTPUT -> "Размер отчёта или журнала превысил лимит.";
                case MISSING_REPORT -> "Конфигуратор не создал текстовый отчёт.";
            };
        }
        if (ex instanceof RepositoryReportFormatException failure) {
            String explanation = switch (failure.reason()) {
                case UNKNOWN_REPORT_HEADER ->
                    "Сформирован TXT, но он не похож на отчёт «По версиям хранилища». "
                    + "Проверьте тип группировки отчёта.";
                case MISSING_VERSION_BLOCK ->
                    "В отчёте 1С не найден полноценный блок номера версии "
                    + "(Версия, Версия конфигурации, пользователь, дата, время).";
                case INVALID_OR_DUPLICATE_VERSION ->
                    "В отчёте 1С найден некорректный или повторяющийся блок версии.";
                case UNKNOWN_ENCODING ->
                    "Не удалось определить кодировку текстового отчёта 1С.";
            };
            return explanation + (failure.localReport() == null
                ? " Копию отчёта сохранить не удалось."
                : " Для диагностики исходный TXT сохранён: "
                  + failure.localReport()
                  + ". Он может содержать адрес сервера, пользователей и "
                  + "комментарии — проверьте файл перед отправкой.");
        }
        if (ex instanceof IOException failure) {
            if (failure.getCause() instanceof CharacterCodingException) {
                return "Неподдерживаемая кодировка текстового отчёта хранилища.";
            }
            return "Отчёт не удалось прочитать или распознать. "
                + "Проверьте формат TXT «По версиям хранилища» и журнал EDT.";
        }
        return "Не удалось выполнить чтение версии. "
            + "Проверьте адрес базы, права и учётные данные.";
    }
}
