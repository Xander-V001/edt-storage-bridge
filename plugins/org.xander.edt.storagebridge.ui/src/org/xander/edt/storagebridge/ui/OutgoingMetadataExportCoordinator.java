package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.xander.edt.storagebridge.core.OutgoingMetadataObjectPlan;
import org.xander.edt.storagebridge.core.OutgoingWholeObjectWritePlan;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * Preview the outgoing native XML representation for ANY top-level EDT owner.
 * This is the next stage after native read-only comparison closes.
 *
 * The export manifest is diagnostic evidence, NOT a partial Designer load
 * list; forms, rights, dependent object structures and deletion semantics
 * are never inferred from filenames.
 */
public final class OutgoingMetadataExportCoordinator {
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;
    private OutgoingMetadataExportCoordinator() {}

    /** Must be called on the SWT UI thread after comparison editor closes. */
    public static void preview(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline expectedGit, long reviewedRevision,
            OutgoingMetadataObjectPlan plan) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(expectedGit);
        Objects.requireNonNull(plan);
        if (plan.owners().isEmpty() || shell.isDisposed()) return;

        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project, settings);
        } catch (IOException ex) {
            MessageDialog.openWarning(shell, "Экспорт метаданных",
                "Для этой базы или проекта уже выполняется другая операция.");
            return;
        }
        Display display = shell.getDisplay();
        Job job = new Job("1С: Штатный XML-экспорт всех изменённых видов метаданных") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                Path folder = null;
                long started = System.nanoTime();
                String phase = "PREFLIGHT";
                try {
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    requireRevision(settings, reviewedRevision, monitor);

                    folder = BridgeActivator.getDefault().getStateLocation()
                        .toFile().toPath().resolve("outgoing-metadata-preview")
                        .resolve(UUID.randomUUID().toString());
                    Files.createDirectories(folder);
                    Path log = folder.resolve("events.log");
                    event(log, "START", "git=" + expectedGit.head()
                        + " revision=" + reviewedRevision);
                    event(log, "SCOPE", "owners=" + plan.owners().size()
                        + " changed_resources=" + plan.changedResourceCount()
                        + " removed_owners=" + plan.removedOwners().size());
                    // Metadata names and output paths stay ONLY in the local
                    // diagnostic manifest, never credentials or native argv.
                    var summary = new StringBuilder(
                        "# EDT native XML export — READ ONLY, NOT APPROVED FOR LOAD\n"
                        + "Git SHA: " + expectedGit.head()
                        + "\nRepository revision at review: " + reviewedRevision
                        + "\n\nAll affected metadata owners:\n");
                    for (var owner : plan.owners().values()) {
                        summary.append(owner.path()).append(" | ")
                            .append(owner.added() ? "NEW " : "")
                            .append(owner.removed() ? "REMOVED " : "")
                            .append(owner.descriptorChanged() ? "STRUCTURAL " : "")
                            .append("changed_resources=").append(
                                owner.changes().size()).append('\n');
                    }
                    summary.append("\nDeleted metadata owners (handled through Configuration.xml parent):\n");
                    plan.removedOwners().forEach(x -> summary.append(x).append('\n'));

                    phase = "NATIVE_EDT_EXPORT";
                    monitor.subTask("Экспорт изменённых объектов средствами EDT");
                    int files = 0;
                    OutgoingWholeObjectWritePlan qualifiedWrite = null;
                    var exportable = plan.exportableOwners();
                    if (!exportable.isEmpty()) {
                        Path exportRoot = Files.createDirectory(folder.resolve("xml"));
                        long exportStarted = System.nanoTime();
                        var result = EdtPublicServiceBridge.previewNativeMetadataExport(
                            project, exportable, exportRoot, monitor);
                        files = result.outputFiles().size();
                        // Native export never silently defines a write scope.
                        // Qualify ALL owners and ALL actual output files,
                        // including forms/templates/roles/registers, as one
                        // operation; any unsupported resource blocks writing.
                        try {
                            qualifiedWrite = OutgoingWholeObjectWritePlan.qualify(
                                plan, result.edtOwners(),
                                result.nativeQualifiedNames(),
                                result.outputFiles());
                            event(log, "WRITE_QUALIFIED",
                                "owners=" + qualifiedWrite.classicOwners().size()
                                + " root_files=" + qualifiedWrite.loadFiles().size());
                        } catch (IOException unsupported) {
                            event(log, "WRITE_REJECTED",
                                "reason=" + unsupported.getClass().getSimpleName()
                                + " code=FULL_OBJECT_SCOPE_UNVERIFIED");
                        }
                        event(log, "EXPORT_OK", "owners=" + exportable.size()
                            + " files=" + files + " elapsed_ms="
                            + elapsedMillis(exportStarted));
                        summary.append("\nNative EDT object qualified names:\n");
                        result.nativeQualifiedNames().forEach(x ->
                            summary.append(x).append('\n'));
                        summary.append("\nProduced platform files (diagnostic only):\n");
                        int max = Math.min(files, 8_000);
                        for (int i = 0; i < max; i++) {
                            summary.append(result.outputFiles().get(i)).append('\n');
                        }
                        if (files > max) {
                            summary.append("... additional files: ")
                                .append(files - max).append('\n');
                        }
                    } else {
                        event(log, "NO_EXPORT", "nothing-exportable");
                    }
                    phase = "VERIFY";
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    requireRevision(settings, reviewedRevision, monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    // This manifest is not a native Designer -listFile.
                    // Never use it for Lock / Load / Commit.
                    Files.writeString(folder.resolve("manifest.txt"),
                        summary.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                    event(log, "COMPLETE", "elapsed_ms="
                        + elapsedMillis(started));
                    final Path resultDir = folder;
                    final int fileCount = files;
                    final OutgoingWholeObjectWritePlan approvedPlan = qualifiedWrite;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        MessageDialog.openInformation(shell,
                            "Экспорт метаданных EDT завершён",
                            "Объектов с изменениями: " + plan.owners().size()
                            + "\nСформировано XML/ресурсов: " + fileCount
                            + "\nУдалённых объектов (через родителя Configuration): "
                            + plan.removedOwners().size()
                            + "\n\nОтчёт и файлы: " + resultDir
                            + "\n\nЭто диагностический экспорт без изменений "
                            + "проекта, базы и хранилища. "
                            + "Списки Lock/Load/Commit не сформированы.");
                        if (approvedPlan != null && !shell.isDisposed()
                                && settings.manualOutgoing()) {
                            if (MessageDialog.openQuestion(shell,
                                    "Ручное помещение через Конфигуратор",
                                    "Подготовить кандидат конфигурации (.cf) "
                                    + "из актуальной базы-посредника и "
                                    + "выбранных изменений EDT?\n\n"
                                    + "Для сборки файла будет создана "
                                    + "временная файловая база 1С. "
                                    + "Рабочую базу и хранилище плагин "
                                    + "не изменяет, Lock/Load/Commit "
                                    + "автоматически не выполняет.")) {
                                OutgoingManualHandoffCoordinator.prepare(
                                    shell, project, settings, expectedGit,
                                    reviewedRevision, approvedPlan,
                                    resultDir.resolve("xml"));
                            }
                        } else if (approvedPlan != null && !shell.isDisposed()) {
                            if (MessageDialog.openQuestion(shell,
                                    "Экспериментальная запись целых объектов",
                                    "Штатный экспорт охватил все выбранные "
                                    + "метаданные и их подчинённые файлы. "
                                    + "Подготовить ТЕСТОВОЕ помещение "
                                    + approvedPlan.classicOwners().size()
                                    + " объектов? "
                                    + (approvedPlan.capturesAllRootChildren()
                                        ? "Для удаления будет рекурсивно захвачен "
                                          + "корень конфигурации. "
                                        : "")
                                    + "Рабочие хранилища не использовать. "
                                    + "Перед записью будут ещё два подтверждения.")) {
                                OutgoingWholeObjectWriteCoordinator.confirmTestWrite(
                                    shell, project, settings, expectedGit,
                                    reviewedRevision, plan, approvedPlan);
                            }
                        } else if (approvedPlan == null && !shell.isDisposed()) {
                            MessageDialog.openInformation(shell,
                                "Помещение объектов пока заблокировано",
                                "Штатный XML-экспорт или состав объектов "
                                + "не прошёл проверку допустимой области "
                                + "частичной загрузки. "
                                + "Данные тестового экспорта сохранены для "
                                + "диагностики. Ничего в 1С не записывалось.");
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    // The outgoing stage has no side effects on 1C yet.
                    // Record the exact *public interface* that EDT failed to
                    // provide. No paths, object names, credentials or raw
                    // third-party exception messages are put in events.log.
                    String failureCode = ex.getClass().getSimpleName();
                    if (ex instanceof EdtPublicServiceBridge.ExportServiceUnavailable missing) {
                        failureCode = "SERVICE_UNAVAILABLE";
                        failureCode += " service=" + missing.type();
                        // Report only a safe Java TYPE, never native exception
                        // messages that may include credentials/arguments.
                        // Distinguishes old ServiceAccess errors from failures
                        // inside the built-in EDT export injector.
                        Throwable cause = missing.getCause();
                        if (cause != null) {
                            failureCode += " cause="
                                + cause.getClass().getSimpleName();
                        }
                    }
                    if (folder != null) {
                        try {
                            event(folder.resolve("events.log"), "FAILED",
                                "phase=" + phase + " exception=" + failureCode);
                        } catch (IOException ignored) { /* workbench log follows */ }
                    }
                    Platform.getLog(OutgoingMetadataExportCoordinator.class)
                        .log(new Status(IStatus.ERROR, PLUGIN,
                            "Native metadata export preview failed at " + phase, ex));
                    final Path diagnostic = folder;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        MessageDialog.openWarning(shell,
                            "Экспорт метаданных EDT не выполнен",
                            "Не удалось подготовить полный штатный экспорт "
                            + "выбранных объектов. Помещение не запускалось."
                            + (diagnostic == null ? ""
                                : "\nДиагностика: " + diagnostic)
                            + (ex instanceof EdtPublicServiceBridge.ExportServiceUnavailable missing
                                ? "\nСервис EDT не найден: " + missing.type()
                                  + ". Запись в хранилище не начиналась."
                                : "")
                            + "\nПодробная ошибка: Error Log EDT.");
                    });
                    return new Status(IStatus.ERROR, PLUGIN,
                        "Outgoing all-metadata read-only preview failed");
                } finally {
                    try {
                        lease.close();
                    } catch (Exception ex) {
                        Platform.getLog(OutgoingMetadataExportCoordinator.class)
                            .log(new Status(IStatus.WARNING, PLUGIN,
                                "Unable to release outgoing preview lease", ex));
                    }
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static void requireRevision(
            StorageProjectSettings.Snapshot settings, long expected,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        Path exe = Path.of(settings.platformExecutable()).toAbsolutePath();
        var ib = new InfobaseLocation(
            settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER,
            settings.infobaseAddress());
        long current = RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(exe, ib, Duration.ofMinutes(5)),
            settings.infobaseUsername(), settings.infobasePassword(),
            settings.address(), settings.username(), settings.password(),
            monitor::isCanceled);
        if (current != expected) {
            throw new IOException("Repository revision changed after review: "
                + "expected=" + expected + " current=" + current);
        }
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            Math.max(0, System.nanoTime() - started));
    }

    private static void event(Path log, String stage, String detail)
            throws IOException {
        if (!stage.matches("[A-Z_]{2,40}") || detail.length() > 4096
                || detail.indexOf('\r') >= 0 || detail.indexOf('\n') >= 0) {
            throw new IOException("Unsafe diagnostic event");
        }
        Files.writeString(log, Instant.now() + " | " + stage
            + " | " + detail + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
