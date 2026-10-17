package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;
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
    /** Prevent overlapping native editors and repository operations per EDT project. */
    private static final java.util.Set<String> ACTIVE =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ReceiveChangesCoordinator() {}

    /**
     * Invoked only from SWT UI. All long-running operations are executed by
     * the standard Eclipse Jobs framework and shown in its Progress view.
     * The settings dialog closes before calling this method.
     */
    public static void receive(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        if (project == null || project.getLocationURI() == null) {
            MessageDialog.openError(shell, "Работа с хранилищем",
                "Не удалось определить существующий EDT-проект.");
            return;
        }
        String operationKey = project.getLocationURI().normalize().toString();
        if (!ACTIVE.add(operationKey)) {
            MessageDialog.openInformation(shell, "Работа с хранилищем",
                "Для этого проекта уже выполняется получение или открыт "
                + "редактор объединения.");
            return;
        }
        Job job = new Job("1С: Получение изменений из хранилища — "
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                AtomicReference<IStatus> outcome =
                    new AtomicReference<>(Status.OK_STATUS);
                try {
                    receiveInBackground(shell, project, settings, operationKey,
                        SubMonitor.convert(monitor, 100), outcome);
                } catch (RuntimeException ex) {
                    // A failure must never leave the project permanently busy.
                    ACTIVE.remove(operationKey);
                    return new Status(IStatus.ERROR,
                        "org.xander.edt.storagebridge.ui",
                        "Получение прервалось: "
                        + ex.getClass().getSimpleName());
                } finally {
                    monitor.done();
                }
                return outcome.get();
            }
        };
        job.setUser(true);
        job.setPriority(Job.LONG);
        job.schedule();
    }

    /** Background-only: SWT widgets are accessed via UI-thread helpers. */
    private static void receiveInBackground(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings, String operationKey,
            SubMonitor jobProgress, AtomicReference<IStatus> outcome) {
        SelectiveDumpRunner.ExportResult export = null;
        Path conversionRoot = null;
        boolean editorOwnsStaging = false;
        AtomicReference<String> phase = new AtomicReference<>("Проверка настроек");
        AtomicBoolean updateAttempted = new AtomicBoolean(false);
        try {
            if (settings.platformExecutable().isBlank()
                    || settings.infobaseAddress().isBlank()
                    || settings.address().isBlank()) {
                throw new IOException("Укажи существующую базу хранилища"
                    + " и исполняемый файл платформы 1С в настройках.");
            }
            Path executable = Path.of(settings.platformExecutable()).toAbsolutePath();
            if (!Files.isRegularFile(executable)) {
                throw new IOException("Указанный исполняемый файл 1С не найден.");
            }
            if (settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE) {
                Path infobase = Path.of(settings.infobaseAddress());
                if (!Files.isDirectory(infobase)
                        || !Files.isRegularFile(infobase.resolve("1Cv8.1CD"))) {
                    throw new IOException(
                        "В указанном каталоге файловой базы не найден 1Cv8.1CD.");
                }
            }
            phase.set("Проверка Git и незакоммиченных изменений");
            GitReceiveGuard.Baseline git = progress(jobProgress, 10, monitor -> {
                monitor.beginTask("Проверка локальной ветки Git", 1);
                var baseline = GitReceiveGuard.requireCleanBranch(project);
                monitor.worked(1);
                return baseline;
            });
            phase.set("Проверка состояния предыдущего получения");
            String branch = git.branch();
            var journal = new ReceiveBaselineJournal(project,
                settings.address(), branch,
                settings.infobaseType().name() + ":" + settings.infobaseAddress());
            var transport = new InfobaseLocation(
                settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
                    ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER,
                settings.infobaseAddress());
            var detect = new ChangeDetectionRequest(executable, transport, TIMEOUT);
            String intermediaryIdentity = settings.infobaseType().name()
                + ":" + settings.infobaseAddress().trim();

            Phase storedPhase = journal.phase();
            if (storedPhase == Phase.CHANGES_JOURNALED) {
                // Pending object names were already flushed. Only complete
                // interrupted cleanup; do not run native Designer again.
                journal.finishAlreadyJournaled();
                storedPhase = Phase.NONE;
            }
            boolean recovery = journal.exists();
            if (recovery && storedPhase != Phase.UPDATE_COMPLETED) {
                throw new IOException(
                    "Предыдущая попытка получения не завершена, результат "
                    + "обновления базы неизвестен. Повторный запуск заблокирован.");
            }
            if (!recovery && storedPhase != Phase.NONE) {
                throw new IOException(
                    "Найден незавершённый журнал обновления без исходного "
                    + "снимка. Повторный запуск заблокирован.");
            }
            if (recovery) {
                updateAttempted.set(true);
            }
            phase.set("Подтверждение получения из хранилища");
            if (!recovery && !confirmOnUi(shell,
                    "Получить изменения из хранилища 1С",
                    "База-посредник: " + settings.infobaseAddress() + "\n"
                    + "Хранилище: " + settings.address() + "\n"
                    + "Изменится конфигурация только указанной базы, "
                    + "она не связана с EDT. "
                    + "Несохранённые локальные изменения этой базы могут быть потеряны. "
                    + (settings.password().isEmpty() ? "" :
                        "Пароль хранилища передаётся штатному Конфигуратору "
                        + "через параметры запуска и может быть доступен "
                        + "администраторам этой системы. ")
                    + "Продолжить?")) {
                return;
            }

            var changes = progress(jobProgress, 45, monitor -> {
                BooleanSupplier canceled = monitor::isCanceled;
                monitor.beginTask("Получение изменений из хранилища 1С", 3);
                if (!journal.exists()) {
                    phase.set("Создание ConfigDumpInfo.xml исходной конфигурации");
                    monitor.subTask("Фиксация состава старой конфигурации без XML-выгрузки");
                    try (var snapshot = new ConfigVersionSnapshotRunner()
                            .capture(detect, canceled)) {
                        journal.persist(snapshot.configDumpInfo());
                    }
                    monitor.worked(1);
                    if (canceled.getAsBoolean()) {
                        throw new IOException("Получение отменено до изменения базы");
                    }
                    phase.set("Обновление конфигурации базы-посредника");
                    monitor.subTask("Получение последней версии в существующую базу");
                    updateAttempted.set(true);
                    journal.markUpdateAttempted();
                    new RepositoryUpdateRunner().receive(
                        new RepositoryUpdateRequest(executable, transport, TIMEOUT,
                            settings.address(), settings.username(),
                            settings.password()),
                        canceled);
                    journal.markUpdateCompleted();
                } else {
                    // A previous process may have crashed after updating the IB.
                    // Detect against the durable old snapshot instead of
                    // overwriting it and losing pending remote objects.
                    phase.set("Восстановление незавершённого получения");
                    monitor.subTask("Восстановление прерванного получения");
                }
                monitor.worked(1);
                phase.set("Получение списка изменённых объектов 1С");
                monitor.subTask("Поиск только изменённых объектов");
                var detected = new ChangedObjectsProbe().detect(
                    detect, journal.path(), canceled);
                Set<String> changedNames =
                    new LinkedHashSet<>(detected.objectsToExport());
                // Store both owning MD objects and separately exported forms,
                // templates, etc. A later receive must not forget their names.
                phase.set("Сохранение списка необработанных объектов");
                ReceivePendingStore.add(
                    project, settings.address(), branch, intermediaryIdentity,
                    changedNames);
                journal.acknowledgeJournaledChanges();
                monitor.worked(1);
                return changedNames;
            });

            phase.set("Загрузка ожидающих обработки объектов");
            Set<String> pending = ReceivePendingStore.get(
                project, settings.address(), branch, intermediaryIdentity);
            if (pending.isEmpty()) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Изменений относительно сохранённого состояния базы-посредника "
                    + "не обнаружено. Это НЕ доказывает, что проект EDT соответствует "
                    + "хранилищу: если база-посредник уже была обновлена раньше, "
                    + "необходимо отдельно восстановить исходную версию обмена.");
                return;
            }
            Set<String> candidateRoots = topObjects(new ArrayList<>(pending));
            phase.set("Выбор объектов для сравнения");
            Set<String> selected = chooseObjectsOnUi(shell, candidateRoots);
            if (selected == null) {
                return;
            }
            if (selected.isEmpty()) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Ни один объект не выбран. Изменения сохранены для следующего раза.");
                return;
            }
            phase.set("Проверка Git перед подготовкой сравнения");
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
            phase.set("Выборочная выгрузка объектов 1С");
            export = progress(jobProgress, 20, monitor -> {
                monitor.beginTask("Выгрузка только выбранных объектов", 1);
                var result = new SelectiveDumpRunner().export(
                    new SelectiveDumpRequest(executable, transport,
                        selectedList, TIMEOUT), monitor::isCanceled);
                monitor.worked(1);
                return result;
            });
            // Converted EDT files, like 1C XML, never go inside active Git.
            phase.set("Создание временных файлов сравнения EDT");
            conversionRoot = Files.createTempDirectory("edt-storage-edt-delta-");
            Path converted = Files.createDirectory(conversionRoot.resolve("edt"));
            SelectiveDumpRunner.ExportResult selectedExport = export;
            GitReceiveGuard.requireUnchanged(project, git);

            phase.set("Конвертация объектов и подготовка сравнения EDT");
            var nativeDescriptor = progress(jobProgress, 25, monitor -> {
                monitor.beginTask("Подготовка штатного сравнения EDT", 1);
                var service = BridgeActivator.getDefault().getInjector()
                    .getInstance(EdtSelectiveReviewService.class);
                var descriptor = service.prepare(project,
                    selectedExport.xmlDirectory(), converted, selectedList, monitor);
                monitor.worked(1);
                return descriptor;
            });
            GitReceiveGuard.requireUnchanged(project, git);

            // Selection in this dialog means "show in comparison", NOT
            // "safe to accept remotely". Native EDT two-way comparison
            // automatically prechecks differences for taking the other side.
            // Force a separate, explicit acknowledgement before opening it.
            if (!confirmOnUi(shell,
                    "Проверка перед объединением EDT",
                    "Подготовлено объектов для сравнения: "
                    + selected.size() + ".\n"
                    + "Это ДВУСТОРОННЕЕ сравнение, общий предок Git/хранилища "
                    + "не установлен. В EDT отличия второго источника могут "
                    + "быть отмечены для переноса ПО УМОЛЧАНИЮ. "
                    + "Не нажимайте «Объединить», пока не проверите каждый "
                    + "флажок, особенно у объектов с вашими коммитами. "
                    + "Подготовленные изменения останутся в журнале при отмене. "
                    + "Открыть редактор сравнения?")) {
                return;
            }
            // The native editor and its part listener MUST be created on
            // SWT UI, never from the Eclipse background Job.
            final Path retainedConverted = conversionRoot;
            final SelectiveDumpRunner.ExportResult retainedExport = export;
            uiCall(shell, parent -> {
                var editorService = BridgeActivator.getDefault().getInjector()
                .getInstance(EdtDeltaComparisonService.class);
            phase.set("Открытие редактора сравнения EDT");
                IEditorPart editor = editorService.openEditor(nativeDescriptor);
            if (editor == null || editor.getSite() == null
                    || editor.getSite().getPage() == null) {
                throw new IOException("EDT comparison editor failed to initialize");
            }
            final IWorkbenchPage page = editor.getSite().getPage();
            final IEditorReference editorReference;
            try {
                editorReference = java.util.Arrays.stream(page.getEditorReferences())
                    .filter(reference -> reference.getEditor(false) == editor)
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                        "EDT editor reference is not available"));
            } catch (Exception ex) {
                // Do not leave an editor pointing to a stage that the outer
                // finally block must clean up on a failed initialization.
                page.closeEditor(editor, false);
                throw ex;
            }
            // Compare is interactive. The editor may stay open after this
            // method returns: cleanup must happen only when it closes.
            IPartListener2 listener = new IPartListener2() {
                @Override
                public void partClosed(IWorkbenchPartReference part) {
                    if (part == editorReference) {
                        page.removePartListener(this);
                        try {
                            GitReceiveGuard.requireSameBranchAndHead(project, git);
                            VerifiedReceiveContent.clearMatchingSelected(
                                project, settings.address(), branch,
                                intermediaryIdentity, selected, selectedPendingNames,
                                retainedConverted.resolve("edt"));
                        } catch (Exception ex) {
                            BridgeActivator.getDefault().getLog().warn(
                                "Partial receive verification failed; "
                                + "unresolved objects remain pending.");
                        } finally {
                            cleanupQuietly(retainedExport, retainedConverted);
                            ACTIVE.remove(operationKey);
                        }
                    }
                }
            };
            try {
                page.addPartListener(listener);
                // Staging is now owned by the comparison editor listener.
            } catch (RuntimeException ex) {
                page.closeEditor(editor, false);
                throw ex;
            }
                return null;
            });
            editorOwnsStaging = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logFailure(phase.get(), ex);
            outcome.set(Status.CANCEL_STATUS);
            informationOnUi(shell, "Работа с хранилищем",
                "Операция прервана на этапе: " + phase.get()
                + ". Если обновление базы уже начиналось, проверьте её состояние.");
        } catch (Exception ex) {
            // No arbitrary exception messages or Designer output are logged:
            // connection strings and credentials may be embedded in them.
            logFailure(phase.get(), ex);
            String databaseState = updateAttempted.get()
                ? "Команда обновления базы-посредника уже могла выполняться. "
                  + "Не повторяйте её до проверки состояния базы."
                : "Команда обновления базы-посредника ещё не запускалась.";
            outcome.set(new Status(IStatus.ERROR,
                "org.xander.edt.storagebridge.ui",
                "Этап: " + phase.get() + ". " + safeReason(ex)));
            notifyOnUi(shell, "Не удалось получить изменения",
                "Этап: " + phase.get() + ".\\n"
                + safeReason(ex) + "\\n"
                + databaseState + "\\n"
                + "Диагностика с этапом и строками кода записана в журнал EDT.",
                true);
        } finally {
            if (!editorOwnsStaging) {
                cleanupQuietly(export, conversionRoot);
                ACTIVE.remove(operationKey);
            }
        }
    }

    @FunctionalInterface
    private interface UiAction<T> {
        T run(Shell activeShell) throws Exception;
    }

    /**
     * Only short interactive prompts and native editor manipulation run on
     * SWT UI; external 1C processes and EDT XML conversion stay in a Job.
     */
    private static <T> T uiCall(Shell owner, UiAction<T> action)
            throws Exception {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            throw new IOException("EDT user interface has been closed");
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable runnable = () -> {
            try {
                Shell active = liveShell(owner);
                if (active == null) {
                    throw new IOException("EDT main window is unavailable");
                }
                result.set(action.run(active));
            } catch (Exception ex) {
                failure.set(ex);
            }
        };
        if (Display.getCurrent() == display) {
            runnable.run();
        } else {
            display.syncExec(runnable);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static Shell liveShell(Shell owner) {
        if (owner != null && !owner.isDisposed()) {
            return owner;
        }
        var window = org.eclipse.ui.PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow();
        return window == null || window.getShell().isDisposed()
            ? null : window.getShell();
    }

    private static boolean confirmOnUi(Shell shell, String title,
            String message) throws Exception {
        return uiCall(shell, active ->
            MessageDialog.openConfirm(active, title, message));
    }

    /** Null means the user dismissed the selection dialog. */
    private static Set<String> chooseObjectsOnUi(Shell shell,
            Set<String> candidateRoots) throws Exception {
        return uiCall(shell, active -> {
            var choices = new ListSelectionDialog(active,
                candidateRoots.stream().sorted().toList(),
                ArrayContentProvider.getInstance(),
                new LabelProvider(),
                "Выбери объекты для сравнения с EDT. "
                + "Все объекты изначально не выбраны. "
                + "Пропущенные остаются в списке следующего получения.");
            choices.setTitle("Изменения из хранилища — выбор объектов");
            choices.setInitialSelections(new Object[0]);
            if (choices.open() != org.eclipse.jface.window.Window.OK) {
                return null;
            }
            Set<String> selected = new LinkedHashSet<>();
            for (Object object : choices.getResult()) {
                selected.add((String) object);
            }
            return selected;
        });
    }

    /** Non-blocking completion/error notice; never manipulates SWT from a Job. */
    private static void informationOnUi(Shell shell, String title,
            String message) {
        notifyOnUi(shell, title, message, false);
    }

    private static void notifyOnUi(Shell shell, String title, String message,
            boolean error) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            Shell active = liveShell(shell);
            if (active != null) {
                if (error) {
                    MessageDialog.openError(active, title, message);
                } else {
                    MessageDialog.openInformation(active, title, message);
                }
            }
        });
    }

    /**
     * Record only exception types and our own stack frames. Java exceptions
     * from Git, Designer or the filesystem can contain local paths, repository
     * addresses or credentials in their messages.
     */
    private static void logFailure(String phase, Throwable failure) {
        StringBuilder diagnostic = new StringBuilder("Storage receive failure. Stage: ")
            .append(phase);
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 5; depth++) {
            diagnostic.append("; cause[").append(depth).append("]: ")
                .append(current.getClass().getName());
            int count = 0;
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("org.xander.edt.storagebridge.")
                        && count++ < 8) {
                    diagnostic.append("; at ").append(frame.getClassName())
                        .append(".").append(frame.getMethodName())
                        .append(":").append(frame.getLineNumber());
                }
            }
            current = current.getCause();
        }
        BridgeActivator.getDefault().getLog().error(diagnostic.toString());
    }

    /** Only explicitly vetted own error messages may appear in the dialog. */
    private static String safeReason(Throwable failure) {
        if (failure instanceof IOException) {
            String reason = failure.getMessage();
            if (reason != null && (
                    reason.equals("Проект не находится под управлением локального Git.")
                    || reason.equals("Git находится в detached HEAD; выберите ветку задачи.")
                    || reason.startsWith("В Git есть незакоммиченные файлы.")
                    || reason.startsWith("Не удалось проверить Git.")
                    || reason.equals("Истекло время проверки Git.")
                    || reason.equals("Указанный исполняемый файл 1С не найден.")
                    || reason.equals("В указанном каталоге файловой базы не найден 1Cv8.1CD.")
                    || reason.equals("Укажи существующую базу хранилища и исполняемый файл платформы 1С в настройках.")
                    || reason.equals("1C executable not found")
                    || reason.equals("1C did not return a valid versions file")
                    || reason.equals("Designer version snapshot failed")
                    || reason.equals("1C could not produce a verified changes list")
                    || reason.equals("No trusted ConfigDumpInfo.xml baseline is available")
                    || reason.startsWith("Предыдущая попытка получения не завершена")
                    || reason.startsWith("Найден незавершённый журнал обновления")
                    || reason.equals("Получение отменено до изменения базы")
                    || reason.matches("Конфигуратор завершился с кодом [0-9]+\\. Состояние ИБ требует проверки\\."))) {
                return reason;
            }
        }
        return "Тип ошибки: " + failure.getClass().getSimpleName()
            + ". Подробности определяются по этапу в журнале EDT.";
    }

    @FunctionalInterface
    private interface ProgressStage<T> {
        T execute(IProgressMonitor monitor) throws Exception;
    }

    private static <T> T progress(SubMonitor parent, int ticks,
            ProgressStage<T> action) throws Exception {
        if (parent.isCanceled()) {
            throw new InterruptedException("Получение отменено пользователем");
        }
        SubMonitor stage = parent.split(ticks);
        try {
            return action.execute(stage);
        } finally {
            stage.done();
        }
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
