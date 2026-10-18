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
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * Stage 1 of commit -> publish: actual latest classic repository revision
 * versus verified incoming revision for the selected Git branch.
 *
 * If remote is newer or incoming review is incomplete, offer the EXISTING
 * receive mechanism. No custom Git merge, XML export, Designer lock or
 * repository commit is performed here. A matching revision is never a
 * substitute for validating an outgoing delta before writing.
 */
public final class OutgoingReadinessCoordinator {
    private OutgoingReadinessCoordinator() {}

    public static void check(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Display display = shell.getDisplay();
        Job job = new Job("1С: Проверка перед помещением — " + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Проверка коммита и версии хранилища", 3);
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    // A commit is mandatory under the accepted workflow.
                    // This reads Git only and never switches branches.
                    var git = GitReceiveGuard.requireCleanBranch(project);
                    monitor.worked(1);

                    var kind = settings.infobaseType()
                        == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER;
                    var infobase = new InfobaseLocation(kind,
                        settings.infobaseAddress());
                    long latest = RepositoryLatestRevisionReader.latest(
                        RepositoryHistoryRequest.latest(
                            Path.of(settings.platformExecutable()).toAbsolutePath(),
                            infobase, Duration.ofMinutes(5)),
                        settings.infobaseUsername(), settings.infobasePassword(),
                        settings.address(), settings.username(),
                        settings.password(), monitor::isCanceled);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    monitor.worked(1);

                    // The native revision is observed, NOT accepted merely
                    // because the user pressed the outgoing command.
                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    ReceiveRevisionStore.recordObserved(project,
                        settings.address(), intermediary, latest);
                    long reviewed = ReceiveRevisionStore.reviewed(project,
                        settings.address(), intermediary, git.branch());
                    int pending = ReceivePendingStore.get(project,
                        settings.address(), git.branch(), intermediary).size();

                    // Automatically determine candidates for ALL metadata
                    // types. Origin validation is still required before Commit.
                    String outgoingCandidates = "";
                    if (latest == reviewed && pending == 0) {
                        var published = OutgoingPublishedGitStore.read(project,
                            settings.address(), intermediary, git.branch());
                        if (published == null) {
                            outgoingCandidates = "Нет подтверждённой Git-точки "
                                + "последнего реального помещения. "
                                + "Вычислять отправку через HEAD~1 нельзя.";
                        } else {
                            if (published.revision() > latest) {
                                throw new java.io.IOException(
                                    "Publication is newer than current repository");
                            }
                            var candidates = OutgoingGitDiffReader.inspect(
                                Path.of(project.getLocation().toOSString()),
                                published.head(), git.head(), monitor::isCanceled);
                            var list = new StringBuilder(
                                "По Git автоматически найдены кандидаты: файлов "
                                + candidates.files().size() + ", объектов "
                                + candidates.ownerChanges().size() + ".");
                            int count = 0;
                            for (String owner : candidates.ownerChanges().keySet()) {
                                if (count++ == 12) {
                                    list.append("\n…и другие");
                                    break;
                                }
                                list.append("\n• ").append(owner);
                            }
                            list.append("\nЭто пока НЕ список для записи: "
                                + "в Git могут быть также полученные изменения "
                                + "из хранилища.");
                            outgoingCandidates = list.toString();
                        }
                    }
                    GitReceiveGuard.requireUnchanged(project, git);
                    monitor.worked(1);

                    // The dialog runs only after the background native report
                    // has finished. Do not start receive on the job thread.
                    display.asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        if (latest != reviewed || pending != 0) {
                            String reason = reviewed < 1
                                ? "Нет подтверждения полного получения актуальной "
                                    + "версии хранилища."
                                : latest > reviewed
                                    ? "Хранилище обновилось: версия " + latest
                                        + ", обработанная версия " + reviewed + "."
                                    : "Нужно завершить обработку изменений "
                                        + "из хранилища.";
                            if (pending > 0) {
                                reason += "\nНеобработанных входящих объектов: "
                                    + pending + ".";
                            }
                            boolean receive = MessageDialog.openQuestion(shell,
                                "Перед помещением требуется обновление",
                                reason + "\n\nЗапустить штатное получение "
                                + "из хранилища? После объединения и "
                                + "коммита повторите команду помещения.");
                            if (receive) {
                                ReceiveChangesCoordinator.receive(shell,
                                    project, settings);
                            }
                            return;
                        }
                        MessageDialog.openInformation(shell,
                            "Помещение — актуальность подтверждена",
                            "Последняя версия хранилища: " + latest
                            + "\nВетка Git: " + git.branch()
                            + "\nКоммит: " + git.head().substring(0,
                                Math.min(12, git.head().length()))
                            + "\n\n" + outgoingCandidates
                            + "\n\nРеальное помещение пока заблокировано. "
                            + "Нужно подтвердить происхождение изменений, "
                            + "безопасную загрузку и результат Commit. "
                            + "В хранилище ничего не записано.");
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    String message = ex instanceof java.io.IOException
                        ? "Проверьте, что все изменения закоммичены в Git, "
                            + "ветка не переключалась и доступ к хранилищу "
                            + "настроен."
                        : "Не удалось подтвердить актуальность "
                            + "версии хранилища. Помещение отменено.";
                    display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Помещение не начато",
                                message + "\nНичего не записано в 1С.");
                        }
                    });
                    return new Status(IStatus.WARNING, BridgeActivator.PLUGIN_ID,
                        "Outgoing native revision preflight failed ("
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
