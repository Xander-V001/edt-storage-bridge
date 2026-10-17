package org.xander.edt.storagebridge.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
                    // The selected Git branch is the single task branch.
                    // Require a clean worktree; outbound snapshots must not
                    // unexpectedly incorporate uncommitted local edits.
                    var git = GitReceiveGuard.requireCleanBranch(project);
                    monitor.worked(1);

                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    long applied = ReceiveRevisionStore.applied(project,
                        settings.address(), intermediary, git.branch());
                    int incoming = ReceivePendingStore.get(project,
                        settings.address(), git.branch(), intermediary).size();
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

                    // Re-check Git after the external report request: the
                    // entire read-only assessment belongs to ONE branch/HEAD.
                    GitReceiveGuard.requireUnchanged(project, git);
                    monitor.worked(1);

                    // Outbound content baseline and isolated Designer
                    // round-trip have NOT been implemented/verified yet.
                    // Always block real writes at this development stage.
                    var result = OutgoingSafetyGate.evaluate(
                        new OutgoingSafetyGate.Evidence(nativeHead, applied,
                            incoming, true, false, false));
                    String report = formatReport(git, nativeHead, applied,
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
                            ? "Git не подтвердил чистую ветку и неизменный HEAD."
                            : "Не удалось проверить настройки и состояние проекта.";
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Отправка EDT в хранилище заблокирована",
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

    private static String formatReport(GitReceiveGuard.Baseline git,
            long nativeHead, long applied, int pending,
            String nativeFailure, OutgoingSafetyGate.Decision decision) {
        var report = new StringBuilder(1024);
        report.append("Проверка только для чтения. Запись в 1С отключена.\n\n")
            .append("Ветка Git: ").append(git.branch()).append("\n")
            .append("HEAD Git: ").append(git.head(), 0, Math.min(12, git.head().length()))
            .append("\n")
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
        if (decision.prerequisitesSatisfied()) {
            // Not reachable until a separate trusted baseline AND scratch
            // validation are implemented; no write command exists here.
            report.append("Предварительные проверки пройдены. "
                + "Реальная отправка в этом режиме отсутствует.");
        } else {
            report.append("Помещение в хранилище НЕ РАЗРЕШЕНО:\n");
            List<String> blockers = new ArrayList<>();
            for (OutgoingSafetyGate.Blocker blocker : decision.blockers()) {
                blockers.add(switch (blocker) {
                    case NATIVE_HEAD_UNKNOWN ->
                        "неизвестна актуальная версия хранилища";
                    case APPLIED_REVISION_UNKNOWN ->
                        "нет подтверждённой полностью применённой ревизии EDT";
                    case REVISION_DIVERGED ->
                        "последняя версия хранилища отличается от применённой EDT";
                    case INCOMING_PENDING ->
                        "остались необработанные входящие изменения";
                    case GIT_NOT_CLEAN ->
                        "ветка Git содержит незакоммиченные изменения";
                    case VERIFIED_CONTENT_BASELINE_MISSING ->
                        "ещё не подготовлен проверенный baseline содержимого";
                    case ISOLATED_VALIDATION_MISSING ->
                        "ещё не выполнена проверка в изолированной базе 1С";
                });
            }
            for (String blocker : blockers) {
                report.append("• ").append(blocker).append("\n");
            }
        }
        report.append("\nЭтот режим не захватывает объекты, не обновляет "
            + "базу-посредник и не создаёт версию хранилища.");
        return report.toString();
    }
}
