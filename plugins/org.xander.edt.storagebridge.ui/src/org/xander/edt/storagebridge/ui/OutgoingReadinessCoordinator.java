package org.xander.edt.storagebridge.ui;

import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * FAST outbound preparation status. No Designer startup and no XML export.
 *
 * Selecting objects must not require the slow native repository version
 * report, a 2+ GB configuration dump, EDT import, or native comparison.
 * A fresh object-scoped conflict check belongs to the ACTUAL publish step,
 * after selection and immediately before native Lock/Commit, not here.
 *
 * Completely independent of ReceiveChangesCoordinator and its import path.
 */
public final class OutgoingReadinessCoordinator {
    private OutgoingReadinessCoordinator() {}

    public static void check(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Display display = shell.getDisplay();
        Job job = new Job("1С: Подготовка выборочного помещения — "
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Чтение локального состояния EDT", 2);
                try {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    String branch = GitReceiveGuard.currentBranch(project);
                    monitor.worked(1);

                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    long applied = ReceiveRevisionStore.applied(project,
                        settings.address(), intermediary, branch);
                    int pending = ReceivePendingStore.get(project,
                        settings.address(), branch, intermediary).size();
                    monitor.worked(1);

                    // Never start Designer just to OPEN outgoing selection.
                    // This is an informational staging screen, not a native
                    // freshness check and not authorization to write.
                    String report = "Подготовка выборочной отправки.\n\n"
                        + "Git-ветка: " + branch + "\n"
                        + "Отметка полного получения: "
                        + (applied > 0 ? Long.toString(applied) : "не установлена")
                        + "\nОжидающие входящие записи (все объекты): "
                        + pending + "\n\n"
                        + "Эти сведения НЕ блокируют выборочную отправку.\n"
                        + "Коммит в Git перед выбором не требуется.\n"
                        + "Для быстрого обычного помещения нужны только "
                        + "выбранные объекты, их актуальные версии и "
                        + "точный список захватов.\n"
                        + "Если выбранный объект изменён другим "
                        + "разработчиком, потребуется его сравнение.\n\n"
                        + "Реальный экспорт, захват и Commit в данной "
                        + "версии ещё не подключены. Ни 1С, ни Git не изменены.";
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openInformation(shell,
                                "Помещение в хранилище — подготовка", report);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    String problem = ex instanceof org.osgi.service.prefs.BackingStoreException
                        ? "Не удалось прочитать список ожидающих изменений."
                        : "Не удалось определить проект, Git-ветку или "
                          + "его локальные настройки.";
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Подготовка выборочного помещения",
                                problem + "\nОперации в хранилище не выполнялись.");
                        }
                    });
                    return new Status(IStatus.WARNING, BridgeActivator.PLUGIN_ID,
                        "Read-only outgoing local preparation failed ("
                        + ex.getClass().getSimpleName() + ")");
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }
}
