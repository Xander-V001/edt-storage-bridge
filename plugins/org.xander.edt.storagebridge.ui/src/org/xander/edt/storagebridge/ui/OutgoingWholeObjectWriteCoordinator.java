package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.xander.edt.storagebridge.core.OutgoingMetadataObjectPlan;
import org.xander.edt.storagebridge.core.OutgoingWholeObjectWritePlan;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.OutgoingDesignerCommands;
import org.xander.edt.storagebridge.platform.OutgoingDesignerRunner;
import org.xander.edt.storagebridge.platform.OutgoingOperationJournal;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * One test-only outgoing path for modified, added and removed top-level metadata.
 *
 * 1. Review the actual 1C baseline and choose the changed EDT owners.
 * 2. Export each complete owner by the official EDT export API.
 * 3. Lock owners with children, partially load their root XML files from
 *    the complete export tree, then commit the selected owners.
 *
 * Native 1C owns the import semantics; additions and removals carry the
 * Configuration parent, with separate Lock/Commit object selection. The
 * repository revision and Git baseline are checked
 * before the first mutation and again before Commit. A possibly executed
 * native command is never automatically retried.
 */
public final class OutgoingWholeObjectWriteCoordinator {
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;

    private OutgoingWholeObjectWriteCoordinator() {}

    /** Called after the user's read-only EDT comparison and export preview. */
    public static void confirmTestWrite(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline reviewedGit, long reviewedRevision,
            OutgoingMetadataObjectPlan sourcePlan,
            OutgoingWholeObjectWritePlan previewPlan) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(reviewedGit);
        Objects.requireNonNull(sourcePlan);
        Objects.requireNonNull(previewPlan);
        if (shell.isDisposed()) return;

        var input = new InputDialog(shell, "Тестовое помещение объектов EDT",
            "Будут целиком выгружены выбранные объекты EDT вместе с формами, "
            + "модулями и внешними ресурсами. Затем Конфигуратор захватит "
            + "объекты, частично загрузит их XML и поместит изменения."
            + "\n\nЭто реальная запись в базу и хранилище 1С. "
            + "Используйте только изолированную тестовую копию."
            + "\n\nДля продолжения введите ТЕСТ:", "",
            value -> "ТЕСТ".equals(value) ? null : "Введите точно ТЕСТ");
        if (input.open() != Window.OK) return;
        if (!MessageDialog.openConfirm(shell, "Подтверждение помещения",
                "Изменятся база-посредник и тестовое хранилище. "
                + "При сбое после захвата автоматический повтор запрещён. "
                + "\n\nВыполнить Lock → Load → Commit?")) return;

        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project, settings);
        } catch (IOException ex) {
            MessageDialog.openWarning(shell, "Помещение не начато",
                "Для проекта или базы-посредника уже выполняется операция.");
            return;
        }

        Display display = shell.getDisplay();
        Job job = new Job("1С: Тестовое помещение полных объектов EDT") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                OutgoingOperationJournal journal = null;
                String phase = "PREFLIGHT";
                boolean mutationStarted = false;
                try {
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String source = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    String branch = reviewedGit.branch();
                    var received = new ReceiveBaselineJournal(project,
                        settings.address(), branch, source);
                    if (ReceiveRevisionStore.reviewed(project,
                            settings.address(), source, branch) != reviewedRevision
                            || !ReceivePendingStore.get(project,
                                settings.address(), branch, source).isEmpty()
                            || received.scannedRevision() != reviewedRevision
                            || received.lastScannedInfo() == null) {
                        throw new IOException("Repository revision is not reviewed");
                    }

                    Path exe = Path.of(settings.platformExecutable()).toAbsolutePath();
                    var ib = new InfobaseLocation(
                        settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER,
                        settings.infobaseAddress());
                    var connection = new OutgoingDesignerCommands.Connection(
                        exe, ib, settings.infobaseUsername(),
                        settings.infobasePassword(), settings.address(),
                        settings.username(), settings.password());
                    var probe = new ChangeDetectionRequest(exe, ib, TIMEOUT,
                        settings.infobaseUsername(), settings.infobasePassword());
                    var original = ConfigDumpInfoIndex.readVersions(
                        received.lastScannedInfo());
                    requireHead(connection, reviewedRevision, monitor);
                    requireSameInfobase(probe, original, monitor);

                    Path state = BridgeActivator.getDefault().getStateLocation()
                        .toFile().toPath();
                    String scope = project.getLocationURI().normalize()
                        + "\0" + settings.address().trim()
                        + "\0" + source + "\0" + branch;
                    if (OutgoingOperationJournal.unresolvedDirectory(
                            state, scope) != null) {
                        throw new IOException("Unresolved previous native write");
                    }
                    journal = OutgoingOperationJournal.begin(state, scope,
                        reviewedGit.head(), reviewedRevision);
                    Path root = journal.directory();
                    Path exportRoot = Files.createDirectory(root.resolve("export"));
                    Path lockXml = root.resolve("lock-objects.xml");
                    Path commitXml = root.resolve("commit-objects.xml");
                    Path loadList = root.resolve("load-list.txt");

                    phase = "EDT_EXPORT";
                    monitor.subTask("Штатный экспорт целых объектов из EDT");
                    var export = EdtPublicServiceBridge.previewNativeMetadataExport(
                        project, sourcePlan.exportableOwners(), exportRoot, monitor);
                    var plan = OutgoingWholeObjectWritePlan.qualify(
                        sourcePlan, export.edtOwners(),
                        export.nativeQualifiedNames(), export.outputFiles());
                    if (!plan.edtOwners().equals(previewPlan.edtOwners())
                            || !plan.nativeNames().equals(previewPlan.nativeNames())
                            || !plan.loadFiles().equals(previewPlan.loadFiles())) {
                        throw new IOException("Selected owners changed after preview");
                    }
                    plan.verifyFiles(exportRoot);
                    plan.writeScopes(lockXml, commitXml, loadList);
                    journal.selectedScope(plan.lockOwners().size(),
                        plan.loadFiles().size(), sha256(plan.nativeNames().toString()));

                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    requireSameInfobase(probe, original, monitor);
                    if (monitor.isCanceled()) {
                        journal.abortBeforeMutation();
                        return Status.CANCEL_STATUS;
                    }

                    // From this point native actions may have changed the
                    // intermediary or its repository captures.
                    mutationStarted = true;
                    phase = "LOCK";
                    monitor.subTask("Захват выбранных объектов с подчинёнными");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOCK,
                        lockXml, exportRoot, loadList, "", TIMEOUT,
                        journal, monitor::isCanceled);
                    requireHead(connection, reviewedRevision, monitor);

                    phase = "LOAD";
                    monitor.subTask("Частичная загрузка объектов из XML");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOAD,
                        lockXml, exportRoot, loadList, "", TIMEOUT,
                        journal, monitor::isCanceled);
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    if (monitor.isCanceled()) {
                        throw new IOException("Load may have executed before cancellation");
                    }

                    phase = "COMMIT";
                    monitor.subTask("Помещение выбранных объектов в хранилище");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.COMMIT,
                        commitXml, exportRoot, loadList,
                        "EDT Storage Bridge: metadata objects",
                        TIMEOUT, journal, monitor::isCanceled);

                    phase = "VERIFY_HEAD";
                    long current = head(connection, monitor);
                    journal.observedRevision("AFTER_COMMIT", current);
                    if (current != reviewedRevision + 1) {
                        throw new IOException("Repository version after commit is unexpected");
                    }
                    journal.verified(current);
                    boolean saved = OutgoingPublishCheckpoint.save(
                        project, settings, reviewedGit, current,
                        probe, journal, monitor);
                    final long verified = current;
                    final boolean checkpointSaved = saved;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) MessageDialog.openInformation(
                            shell, "Тестовое помещение завершено",
                            "Хранилище подтвердило версию " + verified
                            + ". Объектов: " + plan.classicOwners().size()
                            + ".\nДиагностика: " + root
                            + (checkpointSaved ? "" :
                                "\nЛокальная фиксация версии не сохранилась."));
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return fail(journal, mutationStarted, phase, ex,
                        shell, display);
                } catch (Exception ex) {
                    return fail(journal, mutationStarted, phase, ex,
                        shell, display);
                } finally {
                    try { lease.close(); }
                    catch (Exception ex) {
                        Platform.getLog(OutgoingWholeObjectWriteCoordinator.class)
                            .log(new Status(IStatus.WARNING, PLUGIN,
                                "Unable to release outgoing lease", ex));
                    }
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static IStatus fail(OutgoingOperationJournal journal,
            boolean mutationStarted, String phase, Exception ex,
            Shell shell, Display display) {
        if (journal != null) {
            try {
                if (mutationStarted) {
                    journal.event("NATIVE_UNCERTAIN",
                        "phase=" + phase + " manual-reconciliation-required");
                } else {
                    journal.abortBeforeMutation();
                }
            } catch (IOException ignored) {
                // Journal must not falsely certify a native write.
            }
        }
        Platform.getLog(OutgoingWholeObjectWriteCoordinator.class)
            .log(new Status(IStatus.ERROR, PLUGIN,
                "Outgoing metadata publish failed at " + phase, ex));
        Path diagnostic = journal == null ? null : journal.directory();
        if (!display.isDisposed()) display.asyncExec(() -> {
            if (!shell.isDisposed()) MessageDialog.openWarning(shell,
                "Помещение остановлено",
                (mutationStarted
                    ? "Конфигуратор мог изменить тестовую базу или хранилище. "
                      + "Нельзя повторять операцию до ручной сверки."
                    : "Запись в хранилище не начиналась.")
                + (diagnostic == null ? "" :
                    "\nЖурнал: " + diagnostic)
                + "\nПодробности в EDT Error Log.");
        });
        return new Status(IStatus.ERROR, PLUGIN,
            "Outgoing metadata publish failed at " + phase);
    }

    private static void requireSameInfobase(ChangeDetectionRequest request,
            java.util.Map<String, ConfigDumpInfoIndex.Version> expected,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        try (var snapshot = new ConfigVersionSnapshotRunner().capture(
                request, monitor::isCanceled)) {
            if (!expected.equals(ConfigDumpInfoIndex.readVersions(
                    snapshot.configDumpInfo()))) {
                throw new IOException("Intermediary differs from reviewed repository");
            }
        }
    }

    private static String sha256(String data) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long head(OutgoingDesignerCommands.Connection connection,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        return RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(connection.executable(),
                connection.infobase(), Duration.ofMinutes(5)),
            connection.infobaseUsername(), connection.infobasePassword(),
            connection.repositoryAddress(), connection.repositoryUsername(),
            connection.repositoryPassword(), monitor::isCanceled);
    }

    private static void requireHead(OutgoingDesignerCommands.Connection connection,
            long revision, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        if (head(connection, monitor) != revision) {
            throw new IOException("Repository changed: update EDT before publishing");
        }
    }
}
