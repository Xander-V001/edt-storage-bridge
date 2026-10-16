package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.xander.edt.storagebridge.edt.EdtDeltaComparisonService;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;

/**
 * Receive from ONE existing repository-connected intermediary directly into
 * the selected existing EDT project through the native EDT compare editor.
 *
 * The existing intermediary is NOT an EDT infobase. No new IB, EDT project or
 * Git branch/worktree is created. XML and converted EDT sources are staged
 * outside the project. All candidate metadata objects are initially UNSELECTED.
 *
 * This controller does not claim a three-way Git/storage common ancestor.
 * It uses explicit two-way native comparison, and won't touch any object that
 * the user didn't select. An optional proven three-way ancestor can be added
 * later without changing the storage transport.
 */
public final class ReceiveChangesCoordinator {
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private ReceiveChangesCoordinator() {}

    public static void receive(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        SelectiveDumpRunner.ExportResult export = null;
        Path conversionRoot = null;
        boolean editorOwnsStaging = false;
        try {
            if (settings.platformExecutable().isBlank()
                    || settings.infobaseAddress().isBlank()
                    || settings.address().isBlank()) {
                throw new IOException("Укажи существующую базу хранилища"
                    + " и исполняемый файл платформы 1С в настройках.");
            }
            GitReceiveGuard.Baseline git = progress(shell, monitor -> {
                monitor.beginTask("Проверка локальной ветки Git", 1);
                var baseline = GitReceiveGuard.requireCleanBranch(project);
                monitor.worked(1);
                return baseline;
            });
            String branch = git.branch();
            var journal = new ReceiveBaselineJournal(project,
                settings.address(), branch);
            var transport = new InfobaseLocation(
                settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                    ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER,
                settings.infobaseAddress());
            Path executable = Path.of(settings.platformExecutable()).toAbsolutePath();
            var detect = new ChangeDetectionRequest(executable, transport, TIMEOUT);

            boolean recovery = journal.exists();
            if (!recovery && !MessageDialog.openConfirm(shell,
                    "Получить изменения из хранилища 1С",
                    "Будет обновлена только указанная в настройках "
                    + "существующая информационная база, подключённая к хранилищу. "
                    + "Она НЕ относится к информационным базам проекта EDT. "
                    + "Обновление базы может перезаписать её собственные "
                    + "несохранённые изменения. Продолжить?")) {
                return;
            }

            var changes = progress(shell, monitor -> {
                BooleanSupplier canceled = monitor::isCanceled;
                monitor.beginTask("Получение изменений из хранилища 1С", 3);
                if (!journal.exists()) {
                    monitor.subTask("Фиксация состава старой конфигурации без XML-выгрузки");
                    try (var snapshot = new ConfigVersionSnapshotRunner()
                            .capture(detect, canceled)) {
                        journal.persist(snapshot.configDumpInfo());
                    }
                    monitor.worked(1);
                    if (canceled.getAsBoolean()) {
                        throw new IOException("Получение отменено до изменения базы");
                    }
                    monitor.subTask("Получение последней версии в существующую базу");
                    new RepositoryUpdateRunner().receive(
                        new RepositoryUpdateRequest(executable, transport, TIMEOUT),
                        canceled);
                } else {
                    // A previous process may have crashed after updating the IB.
                    // Detect against the durable old snapshot instead of
                    // overwriting it and losing pending remote objects.
                    monitor.subTask("Восстановление прерванного получения");
                }
                monitor.worked(1);
                monitor.subTask("Поиск только изменённых объектов");
                var detected = new ChangedObjectsProbe().detect(
                    detect, journal.path(), canceled);
                Set<String> changedNames =
                    new LinkedHashSet<>(detected.objectsToExport());
                // Store both owning MD objects and separately exported forms,
                // templates, etc. A later receive must not forget their names.
                ReceivePendingStore.add(
                    project, settings.address(), branch, changedNames);
                journal.acknowledgeJournaledChanges();
                monitor.worked(1);
                return changedNames;
            });

            Set<String> pending = ReceivePendingStore.get(
                project, settings.address(), branch);
            if (pending.isEmpty()) {
                MessageDialog.openInformation(shell, "Работа с хранилищем",
                    "Новых изменений для выбранного проекта нет.");
                return;
            }
            Set<String> candidateRoots = topObjects(new ArrayList<>(pending));
            var choices = new ListSelectionDialog(shell,
                candidateRoots.stream().sorted().toList(),
                ArrayContentProvider.getInstance(),
                new LabelProvider(),
                "Выбери объекты для сравнения с EDT. "
                + "Все объекты изначально не выбраны. "
                + "Пропущенные остаются в списке следующего получения.");
            choices.setTitle("Изменения из хранилища — выбор объектов");
            choices.setInitialSelections(new Object[0]);
            if (choices.open() != org.eclipse.jface.window.Window.OK) {
                return;
            }
            Set<String> selected = new LinkedHashSet<>();
            for (Object object : choices.getResult()) {
                selected.add((String) object);
            }
            if (selected.isEmpty()) {
                MessageDialog.openInformation(shell, "Работа с хранилищем",
                    "Ни один объект не выбран. Изменения сохранены для следующего раза.");
                return;
            }
            GitReceiveGuard.requireUnchanged(project, git);

            // Convert root-level UI selection back to ALL explicit Designer
            // metadata names required for independently developed children.
            Set<String> selectedPendingNames = new LinkedHashSet<>();
            for (String pendingName : pending) {
                if (selected.contains(topObject(pendingName))) {
                    selectedPendingNames.add(pendingName);
                }
            }
            List<String> selectedList = new ArrayList<>(selectedPendingNames);
            export = progress(shell, monitor -> {
                monitor.beginTask("Выгрузка только выбранных объектов", 1);
                var result = new SelectiveDumpRunner().export(
                    new SelectiveDumpRequest(executable, transport,
                        selectedList, TIMEOUT), monitor::isCanceled);
                monitor.worked(1);
                return result;
            });
            // Converted EDT files, like 1C XML, never go inside active Git.
            conversionRoot = Files.createTempDirectory("edt-storage-edt-delta-");
            Path converted = Files.createDirectory(conversionRoot.resolve("edt"));
            SelectiveDumpRunner.ExportResult selectedExport = export;
            GitReceiveGuard.requireUnchanged(project, git);

            var nativeDescriptor = progress(shell, monitor -> {
                monitor.beginTask("Подготовка штатного сравнения EDT", 1);
                var service = BridgeActivator.getDefault().getInjector()
                    .getInstance(EdtSelectiveReviewService.class);
                var descriptor = service.prepare(project,
                    selectedExport.xmlDirectory(), converted, selectedList, monitor);
                monitor.worked(1);
                return descriptor;
            });
            GitReceiveGuard.requireUnchanged(project, git);

            var editorService = BridgeActivator.getDefault().getInjector()
                .getInstance(EdtDeltaComparisonService.class);
            IEditorPart editor = editorService.openEditor(nativeDescriptor);
            if (editor == null || editor.getSite() == null
                    || editor.getSite().getPage() == null) {
                throw new IOException("EDT comparison editor failed to initialize");
            }
            final IWorkbenchPage page = editor.getSite().getPage();
            final IEditorReference editorReference = java.util.Arrays
                .stream(page.getEditorReferences())
                .filter(reference -> reference.getEditor(false) == editor)
                .findFirst()
                .orElseThrow(() -> new IOException(
                    "EDT editor reference is not available"));
            final Path retainedConverted = conversionRoot;
            final SelectiveDumpRunner.ExportResult retainedExport = export;
            // Compare is interactive. The editor may stay open after this
            // method returns: cleanup must happen only when it closes.
            IPartListener2 listener = new IPartListener2() {
                @Override
                public void partClosed(IWorkbenchPartReference part) {
                    if (part == editorReference) {
                        page.removePartListener(this);
                        try {
                            VerifiedReceiveContent.clearMatchingSelected(
                                project, settings.address(), branch,
                                selected, selectedPendingNames,
                                retainedConverted.resolve("edt"));
                        } catch (Exception ex) {
                            BridgeActivator.getDefault().getLog().warn(
                                "Partial receive verification failed; "
                                + "unresolved objects remain pending.");
                        } finally {
                            cleanupQuietly(retainedExport, retainedConverted);
                        }
                    }
                }
            };
            page.addPartListener(listener);
            editorOwnsStaging = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            MessageDialog.openInformation(shell, "Работа с хранилищем",
                "Операция прервана. Неполученные объекты сохраняются.");
        } catch (Exception ex) {
            // Do not expose connection strings, credentials or raw Designer
            // output. A failed post-update attempt retains its old snapshot.
            MessageDialog.openError(shell, "Не удалось получить изменения",
                "Проверка или подготовка обновления не завершена. "
                + "Операция не подтверждена как выполненная. "
                + "Сведения о незавершённых объектах сохранены. "
                + "Подробности — в журнале ошибок EDT.");
            BridgeActivator.getDefault().getLog().error(
                "Storage receive failed: " + ex.getClass().getSimpleName());
        } finally {
            if (!editorOwnsStaging) {
                cleanupQuietly(export, conversionRoot);
            }
        }
    }

    @FunctionalInterface
    private interface ProgressStage<T> {
        T execute(IProgressMonitor monitor) throws Exception;
    }

    private static <T> T progress(Shell shell, ProgressStage<T> action)
            throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                try {
                    result.set(action.execute(monitor));
                } catch (Exception ex) {
                    throw new InvocationTargetException(ex);
                }
            });
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception failure) {
                throw failure;
            }
            throw new IOException("Unexpected receive failure", cause);
        }
        return result.get();
    }

    private static Set<String> topObjects(List<String> objectNames) {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : objectNames) {
            result.add(topObject(entry));
        }
        return result;
    }

    public static String topObject(String name) {
        if ("Configuration".equals(name) || name.startsWith("Configuration.")) {
            return "Configuration";
        }
        if ("Language".equals(name) || name.startsWith("Language.")) {
            return "Language";
        }
        String[] parts = name.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : name;
    }

    private static void cleanupQuietly(
            SelectiveDumpRunner.ExportResult export, Path edtTempRoot) {
        try {
            if (export != null) {
                export.close();
            }
        } catch (IOException ignored) { /* staging only */ }
        if (edtTempRoot != null) {
            try (Stream<Path> files = Files.walk(edtTempRoot)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) { /* staging only */ }
        }
    }
}
