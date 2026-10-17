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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;
import org.xander.edt.storagebridge.edt.EdtDeltaComparisonService;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigurationChangesParser;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;
import org.xander.edt.storagebridge.platform.SelectiveDumpPlanner;
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
    /** The SAME existing 1C infobase must never receive two parallel Designer runs. */
    private static final java.util.Set<String> ACTIVE_INTERMEDIARIES =
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
        // Different EDT projects may point to the same existing intermediary.
        // Lock across both the Designer update and the native compare session:
        // no second caller can change the source until the staged review ends.
        String intermediaryKey = settings.infobaseType().name() + ":"
            + settings.infobaseAddress().trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!ACTIVE_INTERMEDIARIES.add(intermediaryKey)) {
            ACTIVE.remove(operationKey);
            MessageDialog.openInformation(shell, "Работа с хранилищем",
                "Для этой базы-посредника уже выполняется получение "
                + "или открыт редактор сравнения другого проекта.");
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
                        intermediaryKey, SubMonitor.convert(monitor, 100), outcome);
                } catch (RuntimeException ex) {
                    // A failure must never leave the project permanently busy.
                    ACTIVE.remove(operationKey);
                    ACTIVE_INTERMEDIARIES.remove(intermediaryKey);
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
            String intermediaryKey, SubMonitor jobProgress,
            AtomicReference<IStatus> outcome) {
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
            var detect = new ChangeDetectionRequest(executable, transport,
                TIMEOUT, settings.infobaseUsername(), settings.infobasePassword());
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
            // SNAPSHOT_READY means the mutation flag was not yet written:
            // the previous operation was interrupted BEFORE Designer start.
            // It is safe to continue only after verifying the intermediary
            // still matches the durable pre-update snapshot (below).
            final boolean resumePrepared = recovery
                && storedPhase == Phase.SNAPSHOT_READY;
            if (recovery && storedPhase != Phase.UPDATE_COMPLETED
                    && !resumePrepared) {
                throw new IOException(
                    "Предыдущая попытка получения не завершена, результат "
                    + "обновления базы неизвестен. Повторный запуск заблокирован.");
            }
            if (!recovery && storedPhase != Phase.NONE) {
                throw new IOException(
                    "Найден незавершённый журнал обновления без исходного "
                    + "снимка. Повторный запуск заблокирован.");
            }
            if (recovery && !resumePrepared) {
                updateAttempted.set(true);
            }
            Path previousInventory = journal.lastScannedInfo();
            boolean firstInventory = previousInventory == null;
            // A failed selective dump has ALREADY persisted the remote
            // changed-object list. Retrying it must not force another native
            // repository update. Require a read-only intermediary version
            // check before using those names against the existing 1C base.
            boolean retryPendingOnly = !recovery && !firstInventory
                && !ReceivePendingStore.get(project, settings.address(),
                    branch, intermediaryIdentity).isEmpty()
                && confirmOnUi(shell, "Повторная выгрузка уже найденных объектов",
                    "Есть объекты хранилища, оставшиеся после предыдущего "
                    + "получения. Повторить только их выборочную выгрузку "
                    + "БЕЗ обновления базы-посредника? Сначала будет выполнена "
                    + "проверка, что конфигурация базы не изменилась.");
            phase.set("Подтверждение получения из хранилища");
            if (!retryPendingOnly && (!recovery || resumePrepared) && !confirmOnUi(shell,
                    "Получить изменения из хранилища 1С",
                    "База-посредник: " + settings.infobaseAddress() + "\n"
                    + "Хранилище: " + settings.address() + "\n"
                    + (firstInventory
                        ? "ПЕРВАЯ СВЕРКА: предыдущая подтверждённая точка "
                          + "получения EDT неизвестна. Будут подготовлены ВСЕ "
                          + "объекты текущего хранилища для полного начального "
                          + "сравнения с EDT. Это долгая операция, но не "
                          + "замена проекта. Последующие сверки используют "
                          + "инкрементальный список изменений. "
                        : "Сравниваем с сохранённым состоянием хранилища "
                          + "для текущего проекта EDT и Git-ветки. ")
                    + "Изменится конфигурация только указанной базы, "
                    + "она не связана с EDT. "
                    + "Несохранённые локальные изменения этой базы могут быть потеряны. "
                    + (settings.password().isEmpty() && settings.infobasePassword().isEmpty() ? "" :
                         "Пароли ИБ и хранилища передаются штатному Конфигуратору "
                        + "через параметры запуска и может быть доступен "
                        + "администраторам этой системы. ")
                    + "Продолжить?")) {
                return;
            }

            if (retryPendingOnly) {
                progress(jobProgress, 45, monitor -> {
                    phase.set("Проверка состояния базы перед повторной выгрузкой");
                    monitor.beginTask("Сверка базы с последним снимком", 1);
                    try (var snapshot = new ConfigVersionSnapshotRunner()
                            .capture(detect, monitor::isCanceled)) {
                        if (!ConfigDumpInfoIndex.readVersions(previousInventory)
                                .equals(ConfigDumpInfoIndex.readVersions(
                                    snapshot.configDumpInfo()))) {
                            throw new IOException(
                                "Конфигурация базы-посредника изменилась "
                                + "после прошлого получения. Повторная "
                                + "выгрузка прежних объектов заблокирована.");
                        }
                    }
                    monitor.worked(1);
                    return null;
                });
            } else {
            // The PRE-update ConfigDumpInfo belongs to the intermediary only.
            // It is NEVER the baseline of the current EDT project. The first
            // scan must inventory the entire remote configuration; later scans
            // use the last remote snapshot recorded FOR THIS EDT/GIT BRANCH.
            progress(jobProgress, 45, monitor -> {
                BooleanSupplier canceled = monitor::isCanceled;
                monitor.beginTask("Получение состояния хранилища 1С", 4);
                if (!recovery || resumePrepared) {
                    if (!recovery) {
                        phase.set("Фиксация состояния базы перед обновлением");
                        monitor.subTask("Защита от повторного обновления после сбоя");
                        try (var snapshot = new ConfigVersionSnapshotRunner()
                                .capture(detect, canceled)) {
                            journal.persist(snapshot.configDumpInfo());
                        }
                    } else {
                        phase.set("Проверка базы после прерванной подготовки");
                        monitor.subTask("Сравнение с сохранённым снимком до обновления");
                        try (var current = new ConfigVersionSnapshotRunner()
                                .capture(detect, canceled)) {
                            if (!ConfigDumpInfoIndex.readVersions(journal.path())
                                    .equals(ConfigDumpInfoIndex.readVersions(
                                        current.configDumpInfo()))) {
                                throw new IOException(
                                    "База-посредник изменилась после подготовки. "
                                    + "Автоматическое повторное обновление запрещено.");
                            }
                        }
                    }
                    monitor.worked(1);
                    if (canceled.getAsBoolean()) {
                        throw new IOException("Получение отменено до изменения базы");
                    }
                    phase.set("Обновление конфигурации базы-посредника");
                    monitor.subTask("Получение актуального состояния хранилища");
                    updateAttempted.set(true);
                    journal.markUpdateAttempted();
                    new RepositoryUpdateRunner().receive(
                        new RepositoryUpdateRequest(executable, transport, TIMEOUT,
                            settings.address(), settings.username(),
                            settings.password(), settings.infobaseUsername(),
                            settings.infobasePassword()),
                        canceled);
                    journal.markUpdateCompleted();
                } else {
                    phase.set("Восстановление незавершённого получения");
                    monitor.subTask("База уже обновлена: повторный запуск запрещён");
                }
                monitor.worked(1);

                // Read the version index AFTER the repository update. This is
                // the only current remote inventory; no EDT state is inferred
                // from the intermediary pre/post delta.
                phase.set("Чтение актуального состава объектов хранилища");
                monitor.subTask("Получение актуального ConfigDumpInfo.xml");
                try (var remote = new ConfigVersionSnapshotRunner()
                        .capture(detect, canceled)) {
                    Path lastScanned = journal.lastScannedInfo();
                    Set<String> remoteNames;
                    if (lastScanned == null) {
                        phase.set("Первичная сверка со всем хранилищем");
                        monitor.subTask(
                            "Первая сверка: все объекты хранилища, не только последнее обновление базы");
                        var manifest = ConfigDumpInfoIndex.exportableNames(
                            remote.configDumpInfo());
                        // A version index does not contain the XML objects.
                        // Only after explicit -listFile export can EDT compare.
                        remoteNames = new LinkedHashSet<>(
                            new ConfigurationChangesParser.ChangeSet(
                                List.copyOf(manifest), List.of())
                                .objectsToExport());
                    } else {
                        phase.set("Поиск изменений со времени предыдущей сверки EDT");
                        monitor.subTask("Сравнение с сохранённой версией для EDT/Git-ветки");
                        // Two immutable, project-scoped manifests; no second
                        // Designer batch operation or intermediary-delta guess.
                        // Refuse deleted/renamed metadata until native EDT
                        // deletion merge is separately proven.
                        Set<String> changed = ConfigDumpInfoIndex.changedSince(
                            lastScanned, remote.configDumpInfo());
                        remoteNames = new LinkedHashSet<>(
                            new ConfigurationChangesParser.ChangeSet(
                                List.copyOf(changed), List.of()).objectsToExport());
                    }
                    monitor.worked(1);
                    if (canceled.getAsBoolean()) {
                        throw new InterruptedException(
                            "Получение отменено до сохранения списка объектов");
                    }
                    phase.set("Сохранение всех ожидающих изменений EDT");
                    // Crash ordering: first the debt, THEN the last-scanned
                    // remote snapshot. Never mark an object "applied" here.
                    ReceivePendingStore.add(
                        project, settings.address(), branch,
                        intermediaryIdentity, remoteNames);
                    journal.recordScannedInfo(remote.configDumpInfo());
                    journal.acknowledgeJournaledChanges();
                    monitor.worked(1);
                }
                return null;
            });
            }

            phase.set("Загрузка ожидающих обработки объектов");
            Set<String> pending = ReceivePendingStore.get(
                project, settings.address(), branch, intermediaryIdentity);
            if (pending.isEmpty()) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Новых и ожидающих изменений хранилища для этого проекта EDT "
                    + "не обнаружено. Полное соответствие EDT хранилищу "
                    + "не подтверждается до проверки итогового сравнения.");
                return;
            }
            // ALL pending objects are candidates for native EDT review.
            // Do not send bare Configuration/Language version-inventory
            // markers to Designer -listFile: affected 1C versions reject
            // these as non-object names. They remain pending, NOT applied.
            var exportPlan = SelectiveDumpPlanner.plan(pending);
            if (exportPlan.exportable().isEmpty()) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Найдено только изменение корня конфигурации/языков, "
                    + "для которого ещё не установлено точное имя выгрузки "
                    + "для этой версии 1С. Изменения оставлены ожидающими.");
                return;
            }
            if (!exportPlan.deferred().isEmpty()) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Корневые записи версии " + exportPlan.deferred()
                    + " временно не включены в -listFile: Конфигуратор "
                    + "может не принимать их как имена объектов. "
                    + "Они остаются ожидающими, остальные объекты "
                    + "будут переданы в штатное сравнение EDT.");
            }
            Set<String> selectedPendingNames = new LinkedHashSet<>(
                exportPlan.exportable());
            Set<String> selected = topObjects(exportPlan.exportable());
            List<String> selectedList = exportPlan.exportable();
            phase.set("Проверка Git перед подготовкой сравнения");
            GitReceiveGuard.requireUnchanged(project, git);
            phase.set("Выборочная выгрузка объектов 1С");
            export = progress(jobProgress, 20, monitor -> {
                monitor.beginTask("Выгрузка только выбранных объектов", 1);
                var result = new SelectiveDumpRunner().export(
                    new SelectiveDumpRequest(executable, transport,
                        selectedList, TIMEOUT, settings.infobaseUsername(),
                        settings.infobasePassword()), monitor::isCanceled);
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
                var descriptor = EdtPublicServiceBridge.prepare(project,
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
                phase.set("Открытие редактора сравнения EDT");
                IEditorPart editor = EdtPublicServiceBridge.openEditor(nativeDescriptor);
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
                                ACTIVE_INTERMEDIARIES.remove(intermediaryKey);
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
                "Этап: " + phase.get() + ".\n"
                + safeReason(ex) + "\n"
                + databaseState + "\n"
                + "Диагностика с этапом и строками кода записана в журнал EDT.",
                true);
        } finally {
            if (!editorOwnsStaging) {
                cleanupQuietly(export, conversionRoot);
                ACTIVE.remove(operationKey);
                ACTIVE_INTERMEDIARIES.remove(intermediaryKey);
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
        if (failure instanceof SelectiveDumpRunner.DumpFailure dump) {
            return "Конфигуратор 1С не выполнил выборочную выгрузку. "
                + "Код завершения: " + dump.exitCode()
                + ". Диагностика: " + dump.diagnosticsDirectory()
                + " (designer.txt, stdout.txt, objects.txt). "
                + "Файлы могут содержать служебные или конфиденциальные данные.";
        }
        if (failure instanceof ConfigVersionSnapshotRunner.SnapshotFailure snapshot) {
            return "Конфигуратор 1С завершил создание снимка с кодом "
                + snapshot.exitCode() + ". Сохранены диагностические журналы: "
                + snapshot.diagnosticsDirectory();
        }
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
        if ("Language".equals(name)) {
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
