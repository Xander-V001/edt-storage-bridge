package org.xander.edt.storagebridge.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.xander.edt.storagebridge.core.OutgoingSafetyGate;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * Stage 1 of EDT -> classic repository publishing: independent READ-ONLY
 * prerequisite probe. No outgoing XML export, Designer load, lock, unlock or
 * repository commit is reachable from this class.
 *
 * Keep this path separate from ReceiveChangesCoordinator. The existing
 * pending-store getter may initialize only its local Eclipse preference node;
 * no pending entry or applied-revision checkpoint is cleared/advanced, and
 * the intermediary, Git worktree and 1C repository are not modified.
 */
public final class OutgoingReadinessCoordinator {
    private OutgoingReadinessCoordinator() {}

    /** SWT-only entry point. All external Git/Designer reads run in an Eclipse Job. */
    public static void check(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Display display = shell.getDisplay();
        Job job = new Job("1С: Проверка готовности к помещению — "
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Проверка исходящей отправки (только чтение)", 4);
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    // A local Git commit is NOT required to prepare an
                    // outgoing selection. Inspect the active branch only;
                    // choosing current EDT content is an explicit later step.
                    String branchName = GitReceiveGuard.currentBranch(project);
                    monitor.worked(1);

                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    long applied = ReceiveRevisionStore.applied(project,
                        settings.address(), intermediary, branchName);
                    int incoming = ReceivePendingStore.get(project,
                        settings.address(), branchName, intermediary).size();
                    monitor.worked(1);

                    InfobaseLocation.Kind kind =
                        settings.infobaseType()
                            == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER;
                    var infobase = new InfobaseLocation(kind,
                        settings.infobaseAddress());
                    long nativeHead = OutgoingSafetyGate.UNKNOWN;
                    String revisionFailure = null;
                    try {
                        nativeHead = RepositoryLatestRevisionReader.latest(
                            RepositoryHistoryRequest.latest(
                                Path.of(settings.platformExecutable())
                                    .toAbsolutePath(), infobase,
                                Duration.ofMinutes(5)),
                            settings.infobaseUsername(),
                            settings.infobasePassword(),
                            settings.address(), settings.username(),
                            settings.password(), monitor::isCanceled);
                    } catch (java.io.IOException | IllegalArgumentException ex) {
                        // A read failure is NOT proof that the revision is
                        // unchanged. Never synthesize HEAD from ConfigDumpInfo.
                        revisionFailure =
                            RepositoryRevisionRefreshCoordinator.failureReason(ex);
                    }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    monitor.worked(1);

                    // A branch switch invalidates this read-only report.
                    // New/dirty Git changes are NOT a global outbound blocker.
                    if (!branchName.equals(GitReceiveGuard.currentBranch(project))) {
                        throw new java.io.IOException("Git branch changed during review");
                    }
                    monitor.worked(1);

                    // Project-wide status is context, not permission to write.
                    // The eventual per-selection preflight must compare against
                    // fresh classic storage objects and verify own native locks.
                    var result = OutgoingSafetyGate.evaluate(
                        new OutgoingSafetyGate.Evidence(nativeHead, applied,
                            incoming, null));
                    String report = formatReport(branchName, nativeHead, applied,
                        incoming, revisionFailure, result);

                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openInformation(shell,
                                "Отправка EDT в хранилище — предварительная проверка",
                                report);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    // Git/checkpoint/credentials may contain confidential
                    // context: report a fixed class of error, never raw
                    // native stdout, exception stack or connection strings.
                    String problem = ex instanceof org.osgi.service.prefs.BackingStoreException
                        ? "Не удалось прочитать журнал входящих изменений."
                        : ex instanceof java.io.IOException
                            ? "Не удалось определить стабильную ветку Git проекта."
                            : "Не удалось проверить настройки и состояние проекта.";
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Проверка исходящей отправки не завершена",
                                problem + "\nНичего не выгружено и не записано в 1С.");
                        }
                    });
                    return new Status(IStatus.WARNING, BridgeActivator.PLUGIN_ID,
                        "Outgoing read-only preflight blocked ("
                        + ex.getClass().getSimpleName() + ")");
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static String formatReport(String branchName,
            long nativeHead, long applied, int pending,
            String nativeFailure, OutgoingSafetyGate.Decision decision) {
        var report = new StringBuilder(1024);
        report.append("Можно готовить выборочную отправку (только чтение).\n")
            .append("Ветка Git: ").append(branchName).append("\n")
            .append("Коммит в Git для подготовки не обязателен.\n")
            .append("Запись в хранилище пока не реализована.\n\n")
            .append("Последняя ревизия хранилища: ")
            .append(nativeHead > 0 ? Long.toString(nativeHead) : "не подтверждена")
            .append("\n")
            .append("Полностью применённая в EDT ревизия: ")
            .append(applied > 0 ? Long.toString(applied) : "не подтверждена")
            .append("\n")
            .append("Ожидающие входящие изменения: ").append(pending)
            .append("\n\n");

        if (nativeFailure != null) {
            report.append("Отчёт 1С: ").append(nativeFailure).append("\n\n");
        }
        report.append("Дальше: выбрать объекты EDT, сравнить только их "
            + "с актуальными объектами хранилища, показать отличия, "
            + "захватить выбранные объекты и поместить после явного "
            + "подтверждения.\n");
        if (!decision.notices().isEmpty()) {
            report.append("\nСведения для проверки выбранных объектов:\n");
            for (OutgoingSafetyGate.Notice notice : decision.notices()) {
                String message = switch (notice) {
                    case HEAD_REPORT_UNAVAILABLE ->
                        "Номер версии по TXT не считан; актуальность выбранных "
                        + "объектов потребуется проверить отдельно";
                    case PROJECT_WIDE_APPLIED_REVISION_UNKNOWN ->
                        "Нет общей отметки полного получения — это не запрет "
                        + "выборочной отправки";
                    case PROJECT_WIDE_REVISIONS_DIFFER ->
                        "Ревизии различаются — проверяем конфликты только "
                        + "в выбранных объектах";
                    case OTHER_INCOMING_CHANGES_MAY_EXIST ->
                        "Есть входящие изменения — при выборе объектов "
                        + "проверим пересечения";
                    case LOCAL_GIT_CHANGES_MAY_BE_INCLUDED ->
                        "Есть локальные изменения Git — выбор включаемого "
                        + "содержимого требуется подтвердить";
                };
                report.append("• ").append(message).append("\n");
            }
        }
        report.append("\nЭтот режим не захватывает объекты, не обновляет "
            + "базу-посредник и не создаёт версию хранилища.");
        return report.toString();
    }
}
