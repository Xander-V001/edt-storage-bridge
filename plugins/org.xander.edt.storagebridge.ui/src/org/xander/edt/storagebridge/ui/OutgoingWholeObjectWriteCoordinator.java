package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
import org.xander.edt.storagebridge.core.OutgoingNativeVersionVerifier;
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
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;

/**
 * TEST COPY ONLY: an EXPERIMENTAL generic whole-object publication path.
 *
 * Unlike the legacy BSL-only writer this is not a type allowlist. EDT
 * resolves native top EObject for EVERY existing metadata kind and supplies
 * the full platform XML/BSL/binary resource set. An exact native round-trip
 * is mandatory before Commit. Any type that fails validation is blocked;
 * no partial "success" on a subset is permitted.
 *
 * New/deleted owners and Configuration root have DIFFERENT native semantics
 * and cannot enter this write path without a qualified dedicated operation.
 */
public final class OutgoingWholeObjectWriteCoordinator {
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;
    private OutgoingWholeObjectWriteCoordinator() {}

    /** SWT thread, after read-only native diff and successful export preview. */
    public static void confirmTestWrite(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline reviewedGit, long reviewedRevision,
            OutgoingMetadataObjectPlan sourcePlan,
            OutgoingWholeObjectWritePlan previewPlan) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(sourcePlan);
        Objects.requireNonNull(previewPlan);
        if (shell.isDisposed()) return;
        var input = new InputDialog(shell,
            "ЭКСПЕРИМЕНТАЛЬНОЕ помещение целых объектов в 1С",
            "Будет выполнена полная выборочная загрузка " +
                previewPlan.edtOwners().size() + " объектов метаданных, включая "
                + "ВСЕ ИХ ПОДЧИНЁННЫЕ РЕСУРСЫ. Это не BSL-only. "
                + "Существующие формы/макеты/свойства могут измениться!\n\n"
                + "Проверьте, что это ИЗОЛИРОВАННАЯ ТЕСТОВАЯ КОПИЯ "
                + "хранилища и базы-посредника.\n\n"
                + "Для подтверждения введите: ТЕСТ", "",
            value -> "ТЕСТ".equals(value) ? null : "Введите точно ТЕСТ");
        if (input.open() != Window.OK) return;
        if (!MessageDialog.openConfirm(shell, "Последнее подтверждение Lock/Load/Commit",
                "Все выбранные объекты будут захвачены, полностью "
                + "загружены из нативного экспорта EDT и помещены "
                + "в указанное тестовое хранилище.\n"
                + "При неполном результате повтор автоматически запрещается."
                + "\nВыполнить тестовое помещение?")) return;

        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project, settings);
        } catch (IOException ex) {
            MessageDialog.openWarning(shell, "Хранилище занято",
                "Другая операция уже работает с базой-посредником.");
            return;
        }
        Display display = shell.getDisplay();
        Job job = new Job("1С: Тестовое помещение полных объектов любых типов") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                OutgoingOperationJournal journal = null;
                Path unresolved = null;
                boolean beforeMutation = true;
                String phase = "PREFLIGHT";
                try {
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    String branch = reviewedGit.branch();
                    if (ReceiveRevisionStore.reviewed(project,
                            settings.address(), intermediary, branch) != reviewedRevision
                            || !ReceivePendingStore.get(project,
                                settings.address(), branch, intermediary).isEmpty()) {
                        throw new IOException("Unresolved incoming repository changes");
                    }
                    var received = new ReceiveBaselineJournal(project,
                        settings.address(), branch, intermediary);
                    if (received.scannedRevision() != reviewedRevision
                            || received.lastScannedInfo() == null) {
                        throw new IOException("No attested intermediary inventory");
                    }
                    var baseline = ConfigDumpInfoIndex.readVersions(
                        received.lastScannedInfo());
                    Path executable = Path.of(settings.platformExecutable())
                        .toAbsolutePath();
                    var infobase = new InfobaseLocation(
                        settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER,
                        settings.infobaseAddress());
                    var connection = new OutgoingDesignerCommands.Connection(
                        executable, infobase, settings.infobaseUsername(),
                        settings.infobasePassword(), settings.address(),
                        settings.username(), settings.password());
                    var inventoryRequest = new ChangeDetectionRequest(
                        executable, infobase, TIMEOUT,
                        settings.infobaseUsername(), settings.infobasePassword());
                    phase = "VERIFY_SOURCE";
                    requireHead(connection, reviewedRevision, monitor);
                    if (!baseline.equals(inventory(inventoryRequest, monitor))) {
                        throw new IOException("Intermediary differs from reviewed source");
                    }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    Path state = BridgeActivator.getDefault().getStateLocation()
                        .toFile().toPath();
                    String scope = project.getLocationURI().normalize()
                        + "\0" + settings.address().trim()
                        + "\0" + intermediary + "\0" + branch;
                    unresolved = OutgoingOperationJournal.unresolvedDirectory(
                        state, scope);
                    if (unresolved != null) {
                        throw new IOException("Previous uncertain outgoing native operation");
                    }
                    journal = OutgoingOperationJournal.begin(state, scope,
                        reviewedGit.head(), reviewedRevision);
                    Path root = journal.directory();
                    journal.selectedScope(previewPlan.nativeNames().size(),
                        previewPlan.loadFiles().size(),
                        sha256(previewPlan.nativeNames().toString()));
                    journal.observedRevision("BEFORE_LOCK", reviewedRevision);
                    Path exportRoot = Files.createDirectory(root.resolve("export"));
                    Path objects = root.resolve("objects.xml");
                    Path fileList = root.resolve("load-list.txt");

                    phase = "EXPORT";
                    monitor.subTask("Штатная выгрузка всех подтверждённых объектов EDT");
                    long started = System.nanoTime();
                    var actualExport = EdtPublicServiceBridge.previewNativeMetadataExport(
                        project, sourcePlan.exportableOwners(), exportRoot, monitor);
                    var actualPlan = OutgoingWholeObjectWritePlan.qualify(
                        sourcePlan, actualExport.edtOwners(),
                        actualExport.nativeQualifiedNames(),
                        actualExport.outputFiles());
                    // If the selected resources or native owner FQNs differ
                    // from the review, never accept a newly widened scope.
                    if (!actualPlan.edtOwners().equals(previewPlan.edtOwners())
                            || !actualPlan.nativeNames().equals(previewPlan.nativeNames())
                            || !actualPlan.loadFiles().equals(previewPlan.loadFiles())) {
                        throw new IOException("Native export changed after user confirmation");
                    }
                    actualPlan.verifyFiles(exportRoot);
                    actualPlan.writeScopes(objects, fileList);
                    journal.stageDetail("EDT_EXPORT", "COMPLETED", elapsed(started));
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    if (!baseline.equals(inventory(inventoryRequest, monitor))) {
                        throw new IOException("Source changed before Lock");
                    }
                    if (monitor.isCanceled()) {
                        journal.abortBeforeMutation();
                        return Status.CANCEL_STATUS;
                    }

                    // UNCERTAIN is persisted BEFORE running every mutating
                    // Designer stage. Native failure is never auto-retried.
                    beforeMutation = false;
                    phase = "LOCK";
                    monitor.subTask("Захват ровно выбранных объектов хранилища");
                    started = System.nanoTime();
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOCK,
                        objects, exportRoot, fileList, "", TIMEOUT,
                        journal, monitor::isCanceled);
                    journal.stageDetail("LOCK", "PROCESS_OK", elapsed(started));
                    requireHead(connection, reviewedRevision, monitor);
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);

                    phase = "LOAD";
                    monitor.subTask("Частичная загрузка XML + всех зависимых ресурсов");
                    started = System.nanoTime();
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.LOAD,
                        objects, exportRoot, fileList, "", TIMEOUT,
                        journal, monitor::isCanceled);
                    journal.stageDetail("LOAD", "PROCESS_OK", elapsed(started));

                    phase = "VERIFY_NATIVE_ROUNDTRIP";
                    monitor.subTask("Независимая сверка загруженных файлов и метаданных");
                    started = System.nanoTime();
                    try (var nativeDump = new SelectiveDumpRunner().export(
                            new SelectiveDumpRequest(executable, infobase,
                                actualPlan.nativeNames(), TIMEOUT,
                                settings.infobaseUsername(),
                                settings.infobasePassword()),
                            monitor::isCanceled)) {
                        verifyAllNativeArtifacts(actualPlan, exportRoot,
                            nativeDump.xmlDirectory(), journal);
                    }
                    journal.stageDetail("ROUND_TRIP", "COMPLETED", elapsed(started));
                    var after = inventory(inventoryRequest, monitor);
                    var changed = OutgoingNativeVersionVerifier
                        .changedOnlyWithinOwners(baseline, after,
                            actualPlan.classicOwners());
                    journal.event("VERIFICATION_OK",
                        "changed_native_records=" + changed.size());
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(connection, reviewedRevision, monitor);
                    if (monitor.isCanceled()) {
                        throw new IOException("Native load completed before cancellation");
                    }

                    phase = "COMMIT";
                    monitor.subTask("Помещение подтверждённых полных объектов");
                    started = System.nanoTime();
                    OutgoingDesignerRunner.run(connection,
                        OutgoingDesignerCommands.Stage.COMMIT,
                        objects, exportRoot, fileList,
                        "EDT Storage Bridge: test full metadata objects",
                        TIMEOUT, journal, monitor::isCanceled);
                    journal.stageDetail("COMMIT", "PROCESS_OK", elapsed(started));
                    phase = "VERIFY_RESULT";
                    long revisionAfter = head(connection, monitor);
                    journal.observedRevision("AFTER_COMMIT", revisionAfter);
                    if (revisionAfter != reviewedRevision + 1) {
                        throw new IOException("Native repository revision not expected");
                    }
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    journal.verified(revisionAfter);

                    boolean stored = true;
                    try {
                        ReceiveRevisionStore.recordObserved(project,
                            settings.address(), intermediary, revisionAfter);
                    } catch (org.osgi.service.prefs.BackingStoreException ex) {
                        stored = false;
                        journal.event("CHECKPOINT_WARNING",
                            "native-revision-verified-local-pref-not-saved");
                    }
                    final boolean checkpoint = stored;
                    final long finalRevision = revisionAfter;
                    final Path diag = root;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openInformation(shell,
                                "Тестовое помещение объектов завершено",
                                "Хранилище подтвердило версию " + finalRevision
                                + "\nВыборочных объектов: "
                                + previewPlan.nativeNames().size()
                                + "\nДиагностика: " + diag
                                + (checkpoint ? "" :
                                    "\nЛокальная запись версии не сохранилась.")
                                + "\nФайлы EDT и Git не изменялись.");
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    if (journal != null && beforeMutation) {
                        try { journal.abortBeforeMutation(); }
                        catch (IOException ignored) { /* fail closed */ }
                    } else if (journal != null) {
                        try {
                            journal.event("INTERRUPTED", "phase=" + phase
                                + " native_state_uncertain=true");
                        } catch (IOException ignored) { /* Error Log follows */ }
                    }
                    final Path diag = journal != null ? journal.directory() : unresolved;
                    final String interruptedStage = phase;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Тестовое помещение прервано",
                                "Операция остановлена на этапе " + interruptedStage
                                + ". Состояние базы/хранилища может "
                                + "быть неопределённым. Повтор выполнять "
                                + "только после сверки."
                                + (diag == null ? "" : "\nЖурнал: " + diag));
                        }
                    });
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    if (journal != null && beforeMutation) {
                        try { journal.abortBeforeMutation(); }
                        catch (IOException ignored) { /* fail closed */ }
                    }
                    if (journal != null) {
                        try {
                            journal.event("FAILURE", "phase=" + phase
                                + " type=" + ex.getClass().getSimpleName()
                                + " native_state_uncertain=" + !beforeMutation);
                        } catch (IOException ignored) { /* Error Log follows */ }
                    }
                    Platform.getLog(OutgoingWholeObjectWriteCoordinator.class)
                        .log(new Status(IStatus.ERROR, PLUGIN,
                            "Full-object TEST publish failed at " + phase, ex));
                    final Path diagnostic = journal != null
                        ? journal.directory() : unresolved;
                    final boolean nativeUncertain = !beforeMutation;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Помещение целых объектов остановлено",
                                (nativeUncertain
                                    ? "Конфигуратор мог изменить базу/захваты/хранилище."
                                      + " Новый запуск ЗАПРЕЩЁН до сверки."
                                    : "Запись не начиналась.")
                                + (diagnostic == null ? ""
                                    : "\nЛокальный журнал: " + diagnostic)
                                + "\nПодробности: EDT Error Log.");
                        }
                    });
                    return new Status(IStatus.ERROR, PLUGIN,
                        "Generic metadata TEST write failed");
                } finally {
                    try { lease.close(); }
                    catch (Exception ignored) { /* logging job handles errors */ }
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static void verifyAllNativeArtifacts(
            OutgoingWholeObjectWritePlan plan, Path expected, Path actual,
            OutgoingOperationJournal journal) throws IOException {
        Set<String> wanted = new HashSet<>(plan.loadFiles());
        for (String name : plan.loadFiles()) {
            Path left = expected.resolve(name);
            Path right = actual.resolve(name);
            if (!Files.isRegularFile(right, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(right)) {
                journal.event("ROUND_TRIP_MISSING",
                    "file=" + safeLogName(name));
                throw new IOException("Native round-trip missing selected resource");
            }
            if (Files.mismatch(left, right) != -1L) {
                journal.event("ROUND_TRIP_DIFF",
                    "file=" + safeLogName(name)
                    + " before_sha256=" + digest(left)
                    + " after_sha256=" + digest(right));
                throw new IOException("Native round-trip differs for selected resource");
            }
            journal.event("ROUND_TRIP_MATCH",
                "file=" + safeLogName(name)
                + " bytes=" + Files.size(right)
                + " sha256=" + digest(right));
        }
        // Detect any additional downloaded resources that our EDT export
        // omitted (e.g., forgotten form extensions). Extra platform files
        // must not silently survive a supposed whole-object replacement.
        try (var walk = Files.walk(actual)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symlink in native verification dump");
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                String relative = actual.relativize(path)
                    .toString().replace('\\', '/');
                if ("ConfigDumpInfo.xml".equals(relative)) continue;
                if (!wanted.contains(relative)) {
                    throw new IOException("Unexpected extra native object resource");
                }
            }
        }
        journal.event("ROUND_TRIP_OK",
            "verified_files=" + plan.loadFiles().size());
    }

    private static String safeLogName(String name) {
        // Native file paths may be long, but event fields are strictly bounded.
        // Raw 1C process arguments and passwords are NEVER included.
        return name.length() > 2048 ? name.substring(0, 2048) : name;
    }

    private static String digest(Path file) throws IOException {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    hash.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(String input) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(
            Math.max(0L, System.nanoTime() - start));
    }

    private static Map<String, ConfigDumpInfoIndex.Version> inventory(
            ChangeDetectionRequest req, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        try (var snapshot = new ConfigVersionSnapshotRunner().capture(
                req, monitor::isCanceled)) {
            return ConfigDumpInfoIndex.readVersions(snapshot.configDumpInfo());
        }
    }

    private static long head(OutgoingDesignerCommands.Connection c,
            IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(c.executable(), c.infobase(),
                Duration.ofMinutes(5)),
            c.infobaseUsername(), c.infobasePassword(),
            c.repositoryAddress(), c.repositoryUsername(),
            c.repositoryPassword(), monitor::isCanceled);
    }

    private static void requireHead(OutgoingDesignerCommands.Connection c,
            long revision, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        if (head(c, monitor) != revision) {
            throw new IOException("Classic repository HEAD advanced during outgoing write");
        }
    }
}
