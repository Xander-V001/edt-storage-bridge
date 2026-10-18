package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

import org.xander.edt.storagebridge.core.OutgoingBslWritePlan;
import org.xander.edt.storagebridge.core.OutgoingNativeVersionVerifier;
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
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;

/**
 * TEST-COPY ONLY outgoing publication of automatically identified
 * standalone BSL modules. The user confirms after inspecting the
 * NATIVE EDT read-only semantic comparison.
 *
 * Do not call on forms, attributes, macros, deletions, new objects or mixed
 * changes. Those need a separately proven native partial XML round-trip.
 */
public final class OutgoingBslWriteCoordinator {
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;
    private static final Duration NATIVE_TIMEOUT = Duration.ofMinutes(30);
    private OutgoingBslWriteCoordinator() {}

    /** Must be entered on SWT UI, after native comparison editor CLOSED. */
    public static void confirmTestWrite(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline reviewedGit, long reviewedRevision,
            OutgoingBslWritePlan plan) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(plan);
        if (shell.isDisposed()) return;
        var dialog = new InputDialog(shell,
            "ТЕСТОВОЕ помещение изменений в хранилище 1С",
            "Вы проверили список изменений в штатном сравнении EDT.\n\n"
            + "Поддерживаются только изменения существующих BSL-модулей: "
            + plan.sourceFiles().size() + " файл(ов), "
            + plan.classicOwners().size() + " объект(ов).\n"
            + "База-посредник и ХРАНИЛИЩЕ БУДУТ ИЗМЕНЕНЫ.\n"
            + "Убедитесь, что настроена именно ТЕСТОВАЯ КОПИЯ хранилища.\n\n"
            + "Для продолжения введите: ТЕСТ", "",
            text -> "ТЕСТ".equals(text) ? null : "Введите точно ТЕСТ");
        if (dialog.open() != Window.OK) return;
        if (!MessageDialog.openConfirm(shell,
                "Последнее подтверждение записи в хранилище",
                "После подтверждения плагин захватит выбранные объекты, "
                + "частично загрузит BSL и выполнит Commit.\n"
                + "При сбое повтор запрещён до сверки журнала.\n"
                + "Подтвердить помещение именно в ТЕСТОВУЮ копию?")) {
            return;
        }
        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project,
                settings);
        } catch (IOException ex) {
            MessageDialog.openWarning(shell, "Хранилище занято",
                "Для проекта или базы-посредника уже выполняется операция.");
            return;
        }
        Display display = shell.getDisplay();
        Job job = new Job("1С: ТЕСТОВОЕ помещение выбранных BSL в хранилище") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                OutgoingOperationJournal journal = null;
                Path unresolvedJournal = null;
                boolean mayAbort = true;
                String phase = "PREFLIGHT";
                try {
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String source = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    String branch = reviewedGit.branch();
                    if (ReceiveRevisionStore.reviewed(project,
                            settings.address(), source, branch) != reviewedRevision
                            || !ReceivePendingStore.get(project,
                                settings.address(), branch, source).isEmpty())
                        throw new IOException("Incoming revision is not reviewed");

                    var receive = new ReceiveBaselineJournal(project,
                        settings.address(), branch, source);
                    if (receive.scannedRevision() != reviewedRevision)
                        throw new IOException("No attested version of storage inventory");
                    var baselineVersions = ConfigDumpInfoIndex.readVersions(
                        receive.lastScannedInfo());

                    Path exe = Path.of(settings.platformExecutable()).toAbsolutePath();
                    var location = new InfobaseLocation(
                        settings.infobaseType()
                            == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER,
                        settings.infobaseAddress());
                    var probe = new ChangeDetectionRequest(exe, location,
                        NATIVE_TIMEOUT,
                        settings.infobaseUsername(), settings.infobasePassword());
                    var connection = new OutgoingDesignerCommands.Connection(
                        exe, location, settings.infobaseUsername(),
                        settings.infobasePassword(), settings.address(),
                        settings.username(), settings.password());

                    phase = "VERIFY_BASELINE";
                    monitor.subTask("Контроль исходной версии перед помещением");
                    requireHead(connection, reviewedRevision, monitor);
                    if (!baselineVersions.equals(inventory(probe, monitor))) {
                        throw new IOException("Intermediary has changed since incoming review");
                    }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    var statePath = BridgeActivator.getDefault()
                        .getStateLocation().toFile().toPath();
                    String scope = project.getLocationURI().normalize()
                        + "\0" + settings.address().trim()
                        + "\0" + source + "\0" + branch;
                    unresolvedJournal = OutgoingOperationJournal
                        .unresolvedDirectory(statePath, scope);
                    if (unresolvedJournal != null)
                        throw new IOException(
                            "Prior native outgoing operation needs manual reconciliation");
                    journal = OutgoingOperationJournal.begin(statePath,
                        scope, reviewedGit.head(), reviewedRevision);
                    Path root = journal.directory();
                    journal.event("SCOPE", "owners=" + plan.classicOwners().size()
                        + " modules=" + plan.platformFiles().size());
                    // The local journal lists every requested identity and
                    // relative file; credentials and full source are excluded.
                    for (String owner : plan.classicOwners()) {
                        journal.event("OWNER", "classic=" + owner);
                    }
                    for (String filename : plan.platformFiles()) {
                        journal.event("MODULE", "file=" + filename);
                    }
                    Path objects = root.resolve("objects.xml");
                    Path files = root.resolve("load-list.txt");
                    plan.writeScopes(objects, files);
                    Path nativeExport = Files.createDirectory(root.resolve("export"));

                    phase = "NATIVE_EDT_EXPORT";
                    monitor.subTask("Штатный выборочный EDT-экспорт исходящих объектов");
                    var result = EdtPublicServiceBridge.previewNativeBslExport(
                        project, plan.sourceFiles(), nativeExport, monitor);
                    plan.verifyExport(result.directory());
                    journal.event("EDT_EXPORT_OK",
                        "owners=" + result.exportedOwners().size()
                        + " files=" + result.platformLoadCandidates().size());
                    for (String filename : plan.platformFiles()) {
                        Path resource = nativeExport.resolve(filename);
                        journal.event("ARTIFACT",
                            "file=" + filename + " bytes=" + Files.size(resource)
                            + " sha256=" + fileDigest(resource));
                    }
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    if (!baselineVersions.equals(inventory(probe, monitor))) {
                        throw new IOException("Staging intermediary changed before Lock");
                    }
                    if (monitor.isCanceled()) {
                        journal.abortBeforeMutation();
                        return Status.CANCEL_STATUS;
                    }

                    // Every mutating stage leaves a durable UNCERTAIN marker
                    // before the native process launches. Never auto-retry.
                    mayAbort = false;
                    phase = "LOCK";
                    monitor.subTask("Захват подтверждённых объектов в хранилище");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOCK,
                        objects, nativeExport, files, "", NATIVE_TIMEOUT,
                        journal, monitor::isCanceled);
                    requireHead(connection, reviewedRevision, monitor);
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);

                    phase = "LOAD";
                    monitor.subTask("Выборочная загрузка только BSL-модулей");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOAD,
                        objects, nativeExport, files, "", NATIVE_TIMEOUT,
                        journal, monitor::isCanceled);

                    // An EXIT 0 does not establish object equality.
                    // Check the native 1C content from the SAME intermediary.
                    phase = "VERIFY_LOADED_MODULES";
                    monitor.subTask("Независимая выборочная проверка загрузки");
                    try (var verified = new SelectiveDumpRunner().export(
                            new SelectiveDumpRequest(exe, location,
                                plan.independentNativeNames(), NATIVE_TIMEOUT,
                                settings.infobaseUsername(),
                                settings.infobasePassword()),
                            monitor::isCanceled)) {
                        for (String file : plan.platformFiles()) {
                            Path expected = nativeExport.resolve(file);
                            Path installed = verified.xmlDirectory().resolve(file);
                            if (!Files.isRegularFile(installed,
                                    LinkOption.NOFOLLOW_LINKS)
                                    || Files.mismatch(expected, installed) != -1L) {
                                throw new IOException(
                                    "Native verification differs from exported EDT module");
                            }
                            journal.event("ROUND_TRIP_OK",
                                "file=" + file + " sha256=" + fileDigest(installed));
                        }
                    }
                    var after = inventory(probe, monitor);
                    var changed = OutgoingNativeVersionVerifier
                        .changedOnlyWithinOwners(baselineVersions, after,
                            plan.classicOwners());
                    journal.event("VERIFICATION_OK",
                        "native-versioned-changes=" + changed.size());
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    if (monitor.isCanceled())
                        throw new IOException("Cancelled after native Load");

                    phase = "COMMIT";
                    monitor.subTask("Помещение подтверждённых BSL в тестовое хранилище");
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.COMMIT,
                        objects, nativeExport, files,
                        "EDT Storage Bridge: confirmed test BSL changes",
                        NATIVE_TIMEOUT, journal, monitor::isCanceled);
                    phase = "VERIFY_NEW_HEAD";
                    long newHead = head(connection, monitor);
                    // Changes from another writer during our operation are
                    // not treated as our successful single publication.
                    if (newHead != reviewedRevision + 1L) {
                        throw new IOException(
                            "Native HEAD is not the expected next single revision");
                    }
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    journal.verified(newHead);
                    // Native Commit succeeded. A preference storage failure
                    // cannot undo it and must not turn the result into an
                    // "uncertain write" or permit a second Commit.
                    boolean checkpointSaved = true;
                    try {
                        ReceiveRevisionStore.recordObserved(project,
                            settings.address(), source, newHead);
                    } catch (org.osgi.service.prefs.BackingStoreException ex) {
                        checkpointSaved = false;
                        journal.event("CHECKPOINT_WARNING",
                            "native-commit-verified; workspace-preference-flush-failed");
                    }
                    final boolean saved = checkpointSaved;
                    final Path diagnostics = root;
                    final long publishedVersion = newHead;
                    display.asyncExec(() -> {
                        if (!shell.isDisposed())
                            MessageDialog.openInformation(shell,
                                "Тестовое помещение завершено",
                                "Хранилище подтвердило новую версию "
                                + publishedVersion + ".\n"
                                + "Локальный журнал: " + diagnostics + "\n"
                                + (saved ? "" : "Внимание: локальный показатель "
                                    + "версии не сохранился. Сверьте следующую "
                                    + "операцию с реальной версией хранилища.\n")
                                + "Изменения проекта EDT и Git не выполнялись.");
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    if (journal != null && mayAbort) {
                        try { journal.abortBeforeMutation(); }
                        catch (IOException ignored) { /* leave unknown marker */ }
                    } else if (journal != null) {
                        try {
                            journal.event("INTERRUPTED",
                                "stage-uncertain=true; manual-check-required");
                        } catch (IOException ignored) { /* Error Log follows */ }
                    }
                    final Path diagnostic = journal == null ? unresolvedJournal
                        : journal.directory();
                    final boolean uncertain = !mayAbort;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Тестовое помещение прервано",
                                (uncertain
                                    ? "Конфигуратор мог уже изменить базу или "
                                      + "хранилище. Повтор запрещён до сверки."
                                    : "Помещение остановлено до захвата объектов.")
                                + (diagnostic == null ? ""
                                    : "\nЖурнал: " + diagnostic));
                        }
                    });
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    if (journal != null && mayAbort) {
                        try { journal.abortBeforeMutation(); }
                        catch (IOException ignored) {
                            // An uncertain state must remain blocked.
                        }
                    }
                    if (journal != null) {
                        try { journal.event("FAILURE", "type="
                            + ex.getClass().getSimpleName()
                            + " phase=" + phase
                            + " stage-uncertain=" + !mayAbort); }
                        catch (IOException ignored) { /* preserve Error Log */ }
                    }
                    Platform.getLog(OutgoingBslWriteCoordinator.class).log(
                        new Status(IStatus.ERROR, PLUGIN,
                            "Outgoing native TEST publish failed at " + phase, ex));
                    final Path diagnosticPath = journal == null ? unresolvedJournal
                        : journal.directory();
                    final boolean uncertain = !mayAbort;
                    final String failurePhase = phase;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed())
                            MessageDialog.openWarning(shell,
                                "Тестовое помещение остановлено",
                                "Не удалось завершить исходящую операцию.\nЭтап: "
                                + failurePhase + "\n"
                                + (uncertain
                                    ? "Lock/Load/Commit могли изменить тестовую "
                                      + "базу или хранилище. НЕ ПОВТОРЯЙТЕ "
                                      + "операцию до сверки состояния.\n"
                                    : "Запись в хранилище ещё не начиналась.\n")
                                + (diagnosticPath == null
                                    ? "Подробности в Error Log EDT."
                                    : "Локальный журнал: " + diagnosticPath));
                    });
                    return new Status(IStatus.ERROR, PLUGIN,
                        "Outgoing native TEST publish stopped");
                } finally {
                    try { lease.close(); } catch (Exception ignored) { /* release */ }
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static String fileDigest(Path file) throws IOException {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1)
                    hash.update(buffer, 0, size);
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Map<String, ConfigDumpInfoIndex.Version> inventory(
            ChangeDetectionRequest request, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        try (var snapshot = new ConfigVersionSnapshotRunner().capture(request,
                monitor::isCanceled)) {
            return ConfigDumpInfoIndex.readVersions(snapshot.configDumpInfo());
        }
    }

    private static void requireHead(OutgoingDesignerCommands.Connection connection,
            long expected, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        if (head(connection, monitor) != expected)
            throw new IOException("Classic repository version advanced during publication");
    }

    private static long head(OutgoingDesignerCommands.Connection connection,
            IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(connection.executable(),
                connection.infobase(), Duration.ofMinutes(5)),
            connection.infobaseUsername(), connection.infobasePassword(),
            connection.repositoryAddress(), connection.repositoryUsername(),
            connection.repositoryPassword(), monitor::isCanceled);
    }
}
