package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
 import org.xander.edt.storagebridge.core.OutgoingMetadataObjectPlan;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.InitialFullDumpRequest;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;

/**
 * Uses EXISTING 1C XML dump -> EDT native conversion -> EDT model comparison
 * to inspect ALL differences before potential outgoing publication.
 *
 * This path is deliberately READ-ONLY: no native RepositoryUpdate, Lock,
 * LoadConfigFromFiles, Commit, Git modification or EDT merge is performed.
 * EDT comparison is opened with noMerge=true, not the receive editor input.
 */
public final class OutgoingReadOnlyComparisonCoordinator {
    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DUMP_TIMEOUT = Duration.ofHours(2);
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;
    private OutgoingReadOnlyComparisonCoordinator() {}

    /** Called on SWT after native HEAD and Git preflight were checked. */
    public static void review(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline expectedGit, long headRevision) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        if (headRevision < 1) {
            MessageDialog.openWarning(shell, "Сверка перед помещением",
                "Версия хранилища не подтверждена.");
            return;
        }
        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project, settings);
        } catch (IOException ex) {
            MessageDialog.openInformation(shell, "Сверка перед помещением",
                "Для проекта или базы-посредника уже выполняется операция.");
            return;
        }
        Display display = shell.getDisplay();
        Job job = new Job("1С: Сравнение EDT с хранилищем перед помещением") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                SelectiveDumpRunner.ExportResult export = null;
                Path conversionRoot = null;
                boolean transferred = false;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    String intermediary = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    if (ReceiveRevisionStore.reviewed(project, settings.address(),
                            intermediary, expectedGit.branch()) != headRevision
                            || !ReceivePendingStore.get(project,
                                settings.address(), expectedGit.branch(),
                                intermediary).isEmpty()) {
                        throw new IOException("Incoming review is incomplete");
                    }

                    // Require an attested actual repository revision for the
                    // intermediary inventory; otherwise its contents might be
                    // behind the global repository HEAD even when Git is clean.
                    var journal = new ReceiveBaselineJournal(project,
                        settings.address(), expectedGit.branch(), intermediary);
                    if (journal.scannedRevision() != headRevision
                            || journal.lastScannedInfo() == null) {
                        throw new UnattestedSourceException();
                    }
                    var expectedVersions = ConfigDumpInfoIndex.readVersions(
                        journal.lastScannedInfo());
                    Path exe = Path.of(settings.platformExecutable()).toAbsolutePath();
                    var targetBase = new InfobaseLocation(
                        settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER,
                        settings.infobaseAddress());
                    var probe = new ChangeDetectionRequest(exe, targetBase,
                        PROBE_TIMEOUT, settings.infobaseUsername(),
                        settings.infobasePassword());

                    monitor.subTask("Проверка достоверности источника хранилища");
                    try (var snapshot = new ConfigVersionSnapshotRunner().capture(
                            probe, monitor::isCanceled)) {
                        if (!expectedVersions.equals(ConfigDumpInfoIndex.readVersions(
                                snapshot.configDumpInfo()))) {
                            throw new IOException("Intermediary differs from reviewed storage");
                        }
                    }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    monitor.subTask("Полная XML-выгрузка источника для сверки");
                    export = new SelectiveDumpRunner().exportInitialFull(
                        new InitialFullDumpRequest(exe, targetBase, DUMP_TIMEOUT,
                            settings.infobaseUsername(), settings.infobasePassword()),
                        monitor::isCanceled);
                    if (!expectedVersions.equals(ConfigDumpInfoIndex.readVersions(
                            export.xmlDirectory().resolve("ConfigDumpInfo.xml")))) {
                        throw new IOException("Source changed during full XML export");
                    }
                    try (var snapshot = new ConfigVersionSnapshotRunner().capture(
                            probe, monitor::isCanceled)) {
                        if (!expectedVersions.equals(ConfigDumpInfoIndex.readVersions(
                                snapshot.configDumpInfo()))) {
                            throw new IOException("Intermediary changed during full XML export");
                        }
                    }
                    long secondHead = RepositoryLatestRevisionReader.latest(
                        RepositoryHistoryRequest.latest(exe, targetBase,
                            Duration.ofMinutes(5)),
                        settings.infobaseUsername(), settings.infobasePassword(),
                        settings.address(), settings.username(),
                        settings.password(), monitor::isCanceled);
                    if (secondHead != headRevision) {
                        throw new IOException("Repository changed during outgoing review");
                    }
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    monitor.subTask("Штатное преобразование XML и сравнение моделей EDT");
                    conversionRoot = Files.createTempDirectory(
                        "edt-storage-outgoing-model-");
                    Path converted = Files.createDirectory(
                        conversionRoot.resolve("edt"));
                    var descriptor = EdtPublicServiceBridge.prepare(
                        project, export.xmlDirectory(), converted,
                        List.of(), true, monitor);
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;

                    // The NATIVE EDT comparison is the only authority for
                    // outgoing metadata differences. Never compare source
                    // bytes or turn .mdo/.distr serialization into outgoing
                    // objects. Subscribe BEFORE opening the native editor.
                    GitReceiveGuard.requireUnchanged(project, expectedGit);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var nativeReview =
                        EdtPublicServiceBridge.observeOutgoingNativeComparison(descriptor);

                    // Blocking dispatch ensures either the UI editor takes
                    // ownership of BOTH staged directories or this job cleans
                    // them in finally, even if workbench closes during launch.
                    var ownsStaging = new AtomicBoolean(false);
                    var uiFailure = new AtomicReference<Exception>();
                    final var retainedExport = export;
                    final Path retainedConversion = conversionRoot;
                    display.syncExec(() -> {
                        if (shell.isDisposed()) return;
                        IEditorPart editor = null;
                        try {
                            editor = EdtPublicServiceBridge
                                .openOutgoingReadOnlyEditor(descriptor);
                            if (editor == null || editor.getSite() == null
                                    || editor.getSite().getPage() == null) {
                                throw new IOException("Native EDT editor was not opened");
                            }
                            final IWorkbenchPage page = editor.getSite().getPage();
                            final IEditorPart opened = editor;
                            IEditorReference reference = java.util.Arrays.stream(
                                    page.getEditorReferences())
                                .filter(candidate -> candidate.getEditor(false) == opened)
                                .findFirst().orElseThrow(() ->
                                    new IOException("Native EDT editor reference is unavailable"));
                            IPartListener2 listener = new IPartListener2() {
                                private boolean handled;
                                @Override
                                public void partClosed(IWorkbenchPartReference part) {
                                    if (handled || part != reference) return;
                                    handled = true;
                                    page.removePartListener(this);
                                    Job cleanup = new Job("Очистка временной сверки 1С") {
                                        @Override
                                        protected IStatus run(IProgressMonitor m) {
                                            OutgoingMetadataObjectPlan confirmed = null;
                                            IOException evidenceError = null;
                                            try {
                                                var identities =
                                                    nativeReview.awaitNativeIdentities();
                                                var owners = EdtPublicServiceBridge
                                                    .outgoingOwnersFromNative(
                                                        project, identities);
                                                confirmed = OutgoingMetadataObjectPlan
                                                    .fromNativeChangedOwners(owners);
                                            } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                                evidenceError = new IOException(
                                                    "Native outgoing review interrupted", ex);
                                            } catch (IOException | RuntimeException ex) {
                                                evidenceError = new IOException(
                                                    "Native EDT difference evidence is incomplete", ex);
                                            } finally {
                                                nativeReview.close();
                                                closeQuietly(retainedExport,
                                                    retainedConversion, lease);
                                            }
                                            final var nativePlan = confirmed;
                                            final var nativeFailure = evidenceError;
                                            if (!display.isDisposed()) {
                                                display.asyncExec(() -> {
                                                    if (shell.isDisposed()) return;
                                                    if (nativeFailure != null) {
                                                        Platform.getLog(
                                                            OutgoingReadOnlyComparisonCoordinator.class)
                                                            .log(new Status(IStatus.ERROR,
                                                                PLUGIN,
                                                                "Outgoing native EDT scope unavailable",
                                                                nativeFailure));
                                                        MessageDialog.openWarning(shell,
                                                            "Помещение остановлено",
                                                            "Не удалось получить подтверждённый "
                                                            + "состав отличий из штатного сравнения "
                                                            + "EDT. Никакого резервного файлового "
                                                            + "сравнения нет. Ничего не помещено. "
                                                            + "Подробности — в Error Log EDT.");
                                                    } else if (nativePlan == null
                                                            || nativePlan.owners().isEmpty()) {
                                                        MessageDialog.openInformation(shell,
                                                            "Сверка завершена",
                                                            "Штатное сравнение EDT не обнаружило "
                                                            + "изменённых объектов. "
                                                            + "Ничего не помещено.");
                                                    } else {
                                                        boolean export =
                                                            MessageDialog.openQuestion(shell,
                                                            "Помещение: отличия по EDT",
                                                            "Штатное сравнение подтвердило "
                                                            + "изменения в "
                                                            + nativePlan.owners().size()
                                                            + " объектах.\n"
                                                            + String.join("\n",
                                                                nativePlan.owners().keySet())
                                                            + "\n\nПодготовить нативный "
                                                            + "экспорт только этих объектов? "
                                                            + "Это пока операция чтения.");
                                                        if (export) {
                                                            OutgoingMetadataExportCoordinator
                                                                .preview(shell, project, settings,
                                                                    expectedGit, headRevision,
                                                                    nativePlan);
                                                        }
                                                    }
                                                });
                                            }
                                            return Status.OK_STATUS;
                                        }
                                    };
                                    cleanup.setSystem(true);
                                    cleanup.schedule();
                                }
                            };
                            page.addPartListener(listener);
                            ownsStaging.set(true);
                        } catch (Exception ex) {
                            nativeReview.close();
                            uiFailure.set(ex);
                            if (editor != null && editor.getSite() != null
                                    && editor.getSite().getPage() != null) {
                                editor.getSite().getPage().closeEditor(editor, false);
                            }
                        }
                    });
                    if (uiFailure.get() != null) {
                        nativeReview.close();
                        throw new IOException("Unable to show read-only EDT comparison",
                            uiFailure.get());
                    }
                    transferred = ownsStaging.get();
                    if (!transferred) {
                        nativeReview.close();
                        throw new IOException("EDT workbench closed before comparison");
                    }
                    // The read-only editor now owns all temporary files and
                    // the shared intermediary/project lease until closure.
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (UnattestedSourceException ex) {
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Невозможно подтвердить состояние хранилища",
                                "Снимок состава объектов базы-посредника не "
                                + "привязан к текущей реальной ревизии хранилища. "
                                + "Показывать его как актуальную конфигурацию "
                                + "нельзя. Проверьте завершение предыдущего "
                                + "получения и фиксацию версии в EDT."
                                + "\n\nНичего не изменено и не помещено.");
                        }
                    });
                    return new Status(IStatus.WARNING, PLUGIN,
                        "Outgoing review blocked: repository inventory not attested");
                } catch (Exception ex) {
                    // Do not expose exception details, XML paths or credentials
                    // in user dialogs; native logs may contain sensitive data.
                    Platform.getLog(OutgoingReadOnlyComparisonCoordinator.class)
                        .log(new Status(IStatus.ERROR, PLUGIN,
                            "Outgoing read-only EDT comparison failed", ex));
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openWarning(shell,
                                "Сверка EDT с хранилищем не выполнена",
                                "Не удалось подтвердить актуальность базы-посредника "
                                + "или подготовить штатное сравнение. "
                                + "Проверьте получение изменений и журнал Error Log EDT."
                                + "\nНикаких изменений в хранилище не помещено.");
                        }
                    });
                    return new Status(IStatus.ERROR, PLUGIN,
                        "Outgoing comparison was not completed");
                } finally {
                    if (!transferred) closeQuietly(export, conversionRoot, lease);
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static final class UnattestedSourceException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static void closeQuietly(SelectiveDumpRunner.ExportResult export,
            Path conversionRoot, AutoCloseable lease) {
        if (export != null) {
            try { export.close(); } catch (IOException ignored) { /* cleanup */ }
        }
        if (conversionRoot != null) {
            try (Stream<Path> files = Files.walk(conversionRoot)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ignored) { /* cleanup */ }
        }
        try { lease.close(); } catch (Exception ignored) { /* release */ }
    }
}
