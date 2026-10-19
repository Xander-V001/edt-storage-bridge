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
import java.util.concurrent.atomic.AtomicLong;
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
import org.xander.edt.storagebridge.core.RepositoryHeadAttestation;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;
import org.xander.edt.storagebridge.edt.EdtDeltaComparisonService;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigurationChangesParser;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.InitialFullDumpRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;
import org.xander.edt.storagebridge.platform.SelectiveDumpPlanner;
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;
import org.xander.edt.storagebridge.platform.RemoteReceiveScopePlanner;

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
     * Reuses the same project+intermediary exclusion as receive. No mutation
     * of the intermediary is permitted while outgoing source is snapshotted.
     * Release only after closing the read-only native comparison editor.
     */
    static AutoCloseable acquireOutgoingReview(IProject project,
            StorageProjectSettings.Snapshot settings) throws IOException {
        if (project == null || project.getLocationURI() == null) {
            throw new IOException("Selected EDT project has no identity");
        }
        String key = project.getLocationURI().normalize().toString();
        String source = settings.infobaseType().name() + ":"
            + settings.infobaseAddress().trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!ACTIVE.add(key)) {
            throw new IOException("A storage operation is active for the EDT project");
        }
        if (!ACTIVE_INTERMEDIARIES.add(source)) {
            ACTIVE.remove(key);
            throw new IOException("A storage operation is active for this intermediary");
        }
        return () -> {
            ACTIVE_INTERMEDIARIES.remove(source);
            ACTIVE.remove(key);
        };
    }

    /** Explicit caller-selected transport mode; never inferred from old pending items. */
    public enum ReceiveMode { INCREMENTAL, FULL_REVIEW }

    /**
     * Invoked only from SWT UI. All long-running operations are executed by
     * the standard Eclipse Jobs framework and shown in its Progress view.
     * The settings dialog closes before calling this method.
     */
    public static void receive(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings) {
        receive(shell, project, settings, ReceiveMode.INCREMENTAL);
    }

    public static void receive(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings, ReceiveMode mode) {
        java.util.Objects.requireNonNull(mode, "mode");
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
        Job job = new Job((mode == ReceiveMode.FULL_REVIEW
                ? "1С: Полная сверка с хранилищем — "
                : "1С: Получение изменений из хранилища — ")
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                AtomicReference<IStatus> outcome =
                    new AtomicReference<>(Status.OK_STATUS);
                try {
                    receiveInBackground(shell, project, settings, operationKey,
                        intermediaryKey, mode, SubMonitor.convert(monitor, 100), outcome);
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
            String intermediaryKey, ReceiveMode mode, SubMonitor jobProgress,
            AtomicReference<IStatus> outcome) {
        SelectiveDumpRunner.ExportResult export = null;
        Path conversionRoot = null;
        boolean editorOwnsStaging = false;
        AtomicReference<String> phase = new AtomicReference<>("Проверка настроек");
        AtomicBoolean updateAttempted = new AtomicBoolean(false);
        // Native exit=0 is not yet a verified post-update base inventory.
        AtomicBoolean updateNeedsSnapshot = new AtomicBoolean(false);
        // A real native repository revision, NEVER a ConfigDumpInfo token.
        // Unknown revision must not block the existing XML receiving flow.
        AtomicLong revisionForReview = new AtomicLong(ReceiveRevisionStore.UNKNOWN);
        // A successful object comparison does not establish the native
        // repository revision. Preserve the exact missing proof reason for
        // the user instead of reporting that they failed to review objects.
        AtomicReference<String> revisionProofIssue = new AtomicReference<>();
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
            // Existing historical pending state without a proof marker is
            // migrated conservatively to an initial full review once.
            boolean initialFullReview = journal.requiresInitialFullReview();
            // A force-full comparison re-exports the entire CURRENT remote
            // configuration without touching the persisted initial marker.
            // This is not a pending-only retry and must run Designer update.
            final boolean fullReview = initialFullReview
                || mode == ReceiveMode.FULL_REVIEW;
            // A failed selective dump has ALREADY persisted the remote
            // changed-object list. Retrying it must not force another native
            // repository update. Require a read-only intermediary version
            // check before using those names against the existing 1C base.
            boolean retryPendingOnly = mode == ReceiveMode.INCREMENTAL
                && !recovery && !firstInventory
                && !ReceivePendingStore.get(project, settings.address(),
                    branch, intermediaryIdentity).isEmpty()
                && confirmOnUi(shell, "Повторная выгрузка уже найденных объектов",
                    "Есть объекты хранилища, оставшиеся после предыдущего "
                    + "получения. Повторить "
                    + (fullReview ? "полную выгрузку"
                        : "только их выборочную выгрузку")
                    + " БЕЗ обновления базы-посредника? Сначала будет выполнена "
                    + "проверка, что конфигурация базы не изменилась.");
            phase.set("Подтверждение получения из хранилища");
            if (!retryPendingOnly && (!recovery || resumePrepared) && !confirmOnUi(shell,
                    "Получить изменения из хранилища 1С",
                    "База-посредник: " + settings.infobaseAddress() + "\n"
                    + "Хранилище: " + settings.address() + "\n"
                    + (fullReview
                        ? (mode == ReceiveMode.FULL_REVIEW
                            ? "ПОЛНАЯ СВЕРКА ПО ЗАПРОСУ: будет повторно выгружена "
                              + "ВСЯ конфигурация, не только новые изменения. "
                            : "ПЕРВАЯ СВЕРКА: будет ОДИН полный XML-дамп ")
                          + "во временный каталог вне EDT и Git. "
                          + "Это не сброс и не перезапись проекта. "
                          + "Он может занять много времени и диска. "
                          + "Рабочий проект EDT не перезаписывается напрямую. "
                          + "В редакторе EDT нужно вручную выбрать изменения. "
                          + "Следующее обычное получение останется выборочным. "
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

            // For an applied-version claim, require the native repository
            // HEAD to be unchanged across a FRESH Designer receive. If a
            // concurrent commit arrives while Designer updates the base,
            // the staged XML cannot prove it includes that newer revision.
            // Recovery/pending-only retries have no such provenance proof.
            final long revisionBeforeUpdate =
                !retryPendingOnly && !recovery
                    ? readLatestRevisionOrUnknown(
                        executable, transport, settings, jobProgress::isCanceled)
                    : ReceiveRevisionStore.UNKNOWN;

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
                    // Do not mark the base updated on exit code alone.
                    // The next read-only ConfigDumpInfo must succeed first.
                    updateNeedsSnapshot.set(true);
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
                    // Validate the current base metadata AFTER the native
                    // repository update and BEFORE advancing the durable
                    // completion marker or starting selective export.
                    // Validate the full current inventory even on an incremental run.
                    ConfigDumpInfoIndex.exportableNames(remote.configDumpInfo());
                    if (updateNeedsSnapshot.get()) {
                        journal.markUpdateCompleted();
                        updateNeedsSnapshot.set(false);
                    }
                    Path lastScanned = journal.lastScannedInfo();
                    boolean selectAllRemote = fullReview || lastScanned == null;
                    phase.set(selectAllRemote
                        ? (mode == ReceiveMode.FULL_REVIEW
                            ? "Принудительная полная сверка со всем хранилищем"
                            : "Первичная сверка со всем хранилищем")
                        : "Поиск изменений со времени предыдущей сверки EDT");
                    monitor.subTask(selectAllRemote
                        ? "Проверка всех актуальных объектов хранилища"
                        : "Поиск версий объектов, изменённых после предыдущего получения");
                    Set<String> remoteNames = new LinkedHashSet<>(
                        RemoteReceiveScopePlanner.candidates(
                            lastScanned, remote.configDumpInfo(), selectAllRemote));
                    if (selectAllRemote && lastScanned != null) {
                        // Deleted 1C metadata has no right-side XML descriptor.
                        // Preserve the debt for native comparison/verification.
                        remoteNames.addAll(RemoteReceiveScopePlanner.removedSince(
                            lastScanned, remote.configDumpInfo()));
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

            // The native /ConfigurationRepositoryReport -NBegin -1 is
            // read-only. It is deliberately optional: unsupported report
            // formats or unavailable repository credentials cannot be
            // substituted with fabricated 1C revision numbers.
            phase.set("Чтение номера последней версии хранилища");
            long observedRevision = readLatestRevisionOrUnknown(
                executable, transport, settings, jobProgress::isCanceled);
            if (observedRevision > 0) {
                ReceiveRevisionStore.recordObserved(project, settings.address(),
                    intermediaryIdentity, observedRevision);
                if (revisionBeforeUpdate == observedRevision
                        && revisionBeforeUpdate > 0) {
                    // Only an unchanged native HEAD surrounding the actual
                    // repository update may establish a NEW snapshot proof.
                    journal.recordScannedRevision(observedRevision);
                    revisionForReview.set(observedRevision);
                } else if (retryPendingOnly && !recovery
                        && journal.scannedRevision() == observedRevision) {
                    // An earlier fresh update already tied this exact
                    // ConfigDumpInfo inventory to the native revision.
                    // Pending-only retry has independently verified that
                    // the intermediary content did not change.
                    revisionForReview.set(observedRevision);
                }
            }
            if (revisionForReview.get() < 1) {
                // Never infer a reviewed version from a ConfigDumpInfo token.
                // A report failure, a concurrent repository Commit or an
                // uncertain recovery must all remain explicit and distinct.
                final String proofProblem;
                if (observedRevision < 1) {
                    proofProblem = "Не удалось определить текущую версию "
                        + "хранилища по штатному отчёту 1С. Объекты "
                        + "сравнивались, но обработанная версия не доказана.";
                } else if (retryPendingOnly) {
                    proofProblem = "Повторная проверка ожидающих объектов "
                        + "не связана с подтверждённым снимком версии "
                        + observedRevision + ". Нельзя считать эту версию "
                        + "обработанной без восстановления привязки.";
                } else if (recovery) {
                    proofProblem = "После восстановления прерванного получения "
                        + "не подтверждена связь текущего снимка объектов "
                        + "с версией " + observedRevision + " хранилища.";
                } else if (revisionBeforeUpdate < 1) {
                    proofProblem = "Не удалось определить версию хранилища "
                        + "ДО получения изменений. Текущая версия "
                        + observedRevision + " прочитана, но нельзя "
                        + "доказать, что она не сменилась во время получения.";
                } else {
                    proofProblem = "Версия хранилища изменилась во время "
                        + "получения: до обновления "
                        + revisionBeforeUpdate + ", после обновления "
                        + observedRevision + ". Требуется повторная "
                        + "проверка актуальности изменений.";
                }
                revisionProofIssue.set(proofProblem);
                BridgeActivator.getDefault().getLog().warn(
                    "Receive revision attestation unavailable: before="
                    + revisionBeforeUpdate + " after=" + observedRevision
                    + " retryPendingOnly=" + retryPendingOnly
                    + " recovery=" + recovery
                    + " mode=" + mode
                    + "; changes remain pending until verified review.");
            }

            phase.set("Загрузка ожидающих обработки объектов");
            Set<String> pending = ReceivePendingStore.get(
                project, settings.address(), branch, intermediaryIdentity);
            Set<String> reviewCandidates = pending;
            if (fullReview) {
                // An old pending identity may refer to a deleted/renamed
                // metadata object. It is not present in this full export:
                // keep it pending, but do not require an impossible .mdo.
                Set<String> currentInventory = ConfigDumpInfoIndex.exportableNames(
                    journal.lastScannedInfo());
                reviewCandidates = RemoteReceiveScopePlanner.availableInFullReview(
                    pending, currentInventory);
                if (reviewCandidates.size() < pending.size()) {
                    informationOnUi(shell, "Полная сверка с хранилищем",
                        "Найдено отсутствующих в текущем хранилище ожидающих "
                        + "записей: " + (pending.size() - reviewCandidates.size())
                        + ". Удаления/переименования сохраняются в журнале "
                        + "и не будут автоматически применены в EDT.");
                }
            }
            // Missing entries include remote-deleted objects. Their parent
            // may still exist (a deleted Form), or the complete top-level
            // owner may be gone (a deleted Report). Both remain in the
            // journal until the editor's result is checked.
            Set<String> absentFromRemote = new LinkedHashSet<>(pending);
            absentFromRemote.removeAll(reviewCandidates);
            if (!fullReview) {
                absentFromRemote.clear();
            }
            final Set<String> deletedPendingNames = Set.copyOf(absentFromRemote);
            if (reviewCandidates.isEmpty() && !fullReview) {
                informationOnUi(shell, "Работа с хранилищем",
                    "Новых и ожидающих изменений хранилища для этого проекта EDT "
                    + "не обнаружено. Полное соответствие EDT хранилищу "
                    + "не подтверждается до проверки итогового сравнения.");
                return;
            }
            // The FULL workflow must not build any metadata selection:
            // full Designer XML -> full native EDT import -> GLOBAL native
            // comparison. ConfigDumpInfo is the durable pending journal,
            // NOT a filter over the real imported configuration contents.
            //
            // Selective mode alone retains explicit roots to limit work.
            Set<String> selectedPendingNames = new LinkedHashSet<>(pending);
            List<String> selectedList = new ArrayList<>();
            Set<String> selected;
            if (fullReview) {
                selected = Set.of();
            } else {
                // Pull every independently developed resource under each
                // affected owner from the CURRENT repository inventory.
                // A changed form alone must never create a truncated parent.
                var exportPlan = SelectiveDumpPlanner.planIncludingRelated(
                    reviewCandidates, ConfigDumpInfoIndex.exportableNames(
                        journal.lastScannedInfo()));
                if (exportPlan.exportable().isEmpty()) {
                    informationOnUi(shell, "Работа с хранилищем",
                        "Найдены только изменения корня конфигурации или языка. "
                        + "Для них выполняется полная сверка; ожидающие "
                        + "записи сохранены без изменения.");
                    return;
                }
                if (!exportPlan.deferred().isEmpty()) {
                    informationOnUi(shell, "Работа с хранилищем",
                        "Отложено корневых изменений: "
                        + exportPlan.deferred().size()
                        + ". Их следует принять через полную сверку. "
                        + "Остальные объекты будут переданы в сравнение.");
                }
                selectedPendingNames.removeAll(exportPlan.deferred());
                selectedList.addAll(exportPlan.exportable());
                selected = topObjects(selectedList);
                phase.set("Проверка типов выборочно выгружаемых объектов 1С");
                EdtSelectiveReviewService.requireSupportedObjectTypes(selectedList);
            }
            phase.set("Проверка Git перед подготовкой сравнения");
            GitReceiveGuard.requireUnchanged(project, git);
            phase.set(fullReview
                ? (mode == ReceiveMode.FULL_REVIEW
                    ? "Повторная полная выгрузка XML по запросу"
                    : "Первоначальная полная выгрузка XML")
                : "Выборочная выгрузка объектов 1С");
            export = progress(jobProgress, 20, monitor -> {
                monitor.beginTask(fullReview
                    ? "Полная XML-выгрузка в отдельный каталог"
                    : "Выгрузка только изменённых объектов", 1);
                var runner = new SelectiveDumpRunner();
                SelectiveDumpRunner.ExportResult result = fullReview
                    ? runner.exportInitialFull(
                        new InitialFullDumpRequest(executable, transport,
                            Duration.ofHours(2), settings.infobaseUsername(),
                            settings.infobasePassword()), monitor::isCanceled)
                    : runner.export(
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
                    selectedExport.xmlDirectory(), converted, selectedList,
                    fullReview, monitor);
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
                    (fullReview
                        ? "ПОЛНАЯ СВЕРКА: сравниваются ВСЕ объекты и ресурсы "
                          + "обоих проектов штатным глобальным механизмом EDT. "
                          + "Вложенные формы, модули, подсистемы, изображения "
                          + "и объекты только одной стороны НЕ исключаются. "
                          + "Отсутствующие справа можно отметить для удаления.\n"
                        : "Подготовлено владельцев для выборочного "
                          + "сравнения: " + selected.size() + ".\n")
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
                // Watch native EDT events BEFORE the compare editor runs;
                // getStatus at partClosed may already return null after
                // EDT discards its finished merge session.
                final var nativeMergeWitness =
                    EdtPublicServiceBridge.observeIncomingMerge(nativeDescriptor);
                IEditorPart editor;
                try {
                    editor = EdtPublicServiceBridge.openEditor(nativeDescriptor);
                } catch (Exception ex) {
                    nativeMergeWitness.close();
                    throw ex;
                }
                if (editor == null || editor.getSite() == null
                        || editor.getSite().getPage() == null) {
                    nativeMergeWitness.close();
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
                    nativeMergeWitness.close();
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
                            // EDT can discard its comparison session as
                            // part of editor close. Retain the successful
                            // MERGE_PROCESS_FINISHED event observed BEFORE
                            // disposal; a later DISCARDED status must not
                            // erase evidence of native merge.
                            final var nativeEvidence = nativeMergeWitness.snapshot();
                            nativeMergeWitness.close();
                            final String nativeMergeStatus =
                                nativeEvidence.lastStatus();
                            BridgeActivator.getDefault().getLog().info(
                                "EDT comparison editor closed; native status="
                                + nativeMergeStatus
                                + "; mergeStarted=" + nativeEvidence.mergeStarted()
                                + "; mergeFinished=" + nativeEvidence.finished()
                                + "; cancelled=" + nativeEvidence.cancelled()
                                + "; sourceRevisionProven="
                                + (revisionForReview.get() > 0)
                                + "; mode=" + mode);
                            // The initial full dump may contain many thousands
                            // of files. Never compare or recursively delete it
                            // on the SWT event thread when the editor closes.
                            Job finish = new Job(
                                    "Завершение штатного объединения EDT — "
                                    + project.getName()) {
                                @Override
                                protected IStatus run(IProgressMonitor monitor) {
                                    try {
                                        GitReceiveGuard.requireSameBranchAndHead(
                                            project, git);
                                        int worktreeAfter = -1;
                                        try {
                                            worktreeAfter = GitReceiveGuard
                                                .workingTreeChangeCount(project);
                                        } catch (IOException | InterruptedException ex) {
                                            // Optional diagnostics must never
                                            // veto a confirmed native merge.
                                            BridgeActivator.getDefault().getLog()
                                                .warn("Git working-tree count unavailable: "
                                                    + ex.getClass().getSimpleName());
                                            if (ex instanceof InterruptedException) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }
                                        BridgeActivator.getDefault().getLog().info(
                                            "Native EDT merge evidence: "
                                            + "status=" + nativeMergeStatus
                                            + "; finished=" + nativeEvidence.finished()
                                            + "; cancelled=" + nativeEvidence.cancelled()
                                            + "; changedGitPaths=" + worktreeAfter
                                            + "; sourceHeadProof="
                                            + revisionForReview.get());
                                        if (nativeEvidence.finished()
                                                && worktreeAfter == 0) {
                                            BridgeActivator.getDefault().getLog().warn(
                                                "EDT reported a finished native merge "
                                                + "but no Git working-tree files changed. "
                                                + "Check whether the selected differences "
                                                + "were applied or local variants retained; "
                                                + "do not infer success from byte equality.");
                                        }
                                        // The developer has already used EDT's
                                        // NATIVE compare/merge editor. Bytewise
                                        // equality against imported XML is not
                                        // the meaning of a completed merge: EDT
                                        // can preserve intentional local values
                                        // and serialize BSL differently.
                                        //
                                        // Closing the editor alone is NOT proof
                                        // that Merge was clicked. Ask explicitly.
                                        // Do not clear ANY pending identity before
                                        // confirmation, and do not call
                                        // VerifiedReceiveContent.verifyAndClear.
                                        String revisionLabel =
                                            revisionForReview.get() > 0
                                                ? Long.toString(revisionForReview.get())
                                                : "не подтверждена";
                                        // A user confirmation or the Settings
                                        // dialog's OK is NOT proof that native
                                        // EDT executed Merge into the active
                                        // workspace. Only a completed EDT
                                        // merge lifecycle can acknowledge
                                        // incoming changes.
                                        boolean nativeFinished =
                                            nativeEvidence.finished();
                                        boolean nativeCancelled =
                                            nativeEvidence.cancelled();
                                        boolean mergeConfirmed =
                                            nativeEvidence.appliedByEdt();
                                        if (!mergeConfirmed) {
                                            BridgeActivator.getDefault().getLog().warn(
                                                "EDT editor closed without a completed "
                                                + "native merge event; status="
                                                + nativeMergeStatus
                                                + "; started="
                                                + nativeEvidence.mergeStarted()
                                                + "; cancelled=" + nativeCancelled
                                                + ". Pending changes preserved.");
                                        }

                                        GitReceiveGuard.requireSameBranchAndHead(
                                            project, git);
                                        String reason = null;
                                        long confirmedRevision = ReceiveRevisionStore.UNKNOWN;
                                        int acknowledgedCount = 0;
                                        Set<String> pendingNow = ReceivePendingStore.get(
                                            project, settings.address(), branch,
                                            intermediaryIdentity);

                                        if (!mergeConfirmed) {
                                            reason = nativeCancelled
                                                ? "EDT сообщила об отмене объединения. "
                                                    + "Ожидающие записи сохранены."
                                                : nativeEvidence.mergeStarted()
                                                    ? "EDT начала объединение, но "
                                                        + "не подтвердила его "
                                                        + "завершение. Изменения "
                                                        + "не отмечены полученными."
                                                    : "Штатная операция «Объединить» "
                                                        + "не подтверждена событиями "
                                                        + "EDT. Окно «Настройка "
                                                        + "объединения модулей» "
                                                        + "только задаёт правила; "
                                                        + "само применение выполняется "
                                                        + "в основном редакторе "
                                                        + "сравнения EDT. Записи "
                                                        + "ожидания сохранены.";
                                        } else {
                                            // Developer-reviewed EDT objects and
                                            // the repository HEAD are independent
                                            // checkpoints. A failed 1C history
                                            // TXT report must not repeatedly
                                            // present the SAME merged objects.
                                            boolean allowReview = false;
                                            boolean inventoryOnly = false;
                                            if (revisionForReview.get() > 0) {
                                                var proof = RepositoryHeadAttestation
                                                    .confirm(revisionForReview.get(),
                                                        () -> readLatestRevisionOrUnknown(
                                                            executable, transport,
                                                            settings,
                                                            Duration.ofSeconds(30),
                                                            monitor::isCanceled),
                                                        monitor::isCanceled,
                                                        Thread::sleep);
                                                if (proof.observed() > 0) {
                                                    ReceiveRevisionStore.recordObserved(
                                                        project, settings.address(),
                                                        intermediaryIdentity,
                                                        proof.observed());
                                                }
                                                switch (proof.state()) {
                                                    case CONFIRMED -> allowReview = true;
                                                    case CHANGED -> reason =
                                                        "Хранилище изменилось во время "
                                                        + "сравнения: ожидалась версия "
                                                        + revisionForReview.get()
                                                        + ", текущая "
                                                        + proof.observed()
                                                        + ". Нужна повторная сверка.";
                                                    case CANCELLED -> reason =
                                                        "Проверка актуальной версии "
                                                        + "хранилища отменена.";
                                                    case UNAVAILABLE ->
                                                        inventoryOnly = true;
                                                }
                                            } else if (revisionBeforeUpdate > 0
                                                    && observedRevision > 0
                                                    && revisionBeforeUpdate
                                                        != observedRevision) {
                                                // A POSITIVE difference between
                                                // two native HEAD reads is an
                                                // actual race, NOT a missing
                                                // report. Never downgrade a
                                                // witnessed remote change to
                                                // weak IB-inventory equality.
                                                reason = "Во время получения "
                                                    + "зафиксирована смена версии "
                                                    + "хранилища: "
                                                    + revisionBeforeUpdate
                                                    + " → " + observedRevision
                                                    + ". Требуется новое получение.";
                                            } else {
                                                inventoryOnly = true;
                                            }
                                            if (inventoryOnly && observedRevision > 0) {
                                                long now = readLatestRevisionOrUnknown(
                                                    executable, transport, settings,
                                                    Duration.ofSeconds(30),
                                                    monitor::isCanceled);
                                                if (now > 0 && now != observedRevision) {
                                                    inventoryOnly = false;
                                                    reason = "Хранилище изменилось "
                                                        + "после подготовки "
                                                        + "сравнения: было "
                                                        + observedRevision
                                                        + ", теперь " + now
                                                        + ". Списание ожиданий "
                                                        + "отменено.";
                                                }
                                            }
                                            if (inventoryOnly) {
                                                // ConfigDumpInfo is NOT a HEAD.
                                                // It proves only that the exact
                                                // source presented in native EDT
                                                // stayed unchanged in the IB.
                                                // Outgoing publish still requires
                                                // independent numeric HEAD proof.
                                                if (intermediaryInventoryUnchanged(
                                                        journal, detect, monitor)) {
                                                    allowReview = true;
                                                    reason = "Объединение подтверждено "
                                                        + "в EDT. Снимок базы-посредника "
                                                        + "не изменился. Номер версии "
                                                        + "хранилища НЕ подтверждён; "
                                                        + "отправка заблокирована "
                                                        + "до получения номера версии.";
                                                } else {
                                                    reason = "Снимок конфигурации "
                                                        + "базы-посредника изменился "
                                                        + "во время объединения. "
                                                        + "Записи ожидания сохранены.";
                                                }
                                            }
                                            if (allowReview) {
                                                var decision =
                                                    org.xander.edt.storagebridge.core
                                                        .EdtMergeAcceptance.evaluate(
                                                            pendingNow,
                                                            selectedPendingNames,
                                                            true, true);
                                                if (!inventoryOnly
                                                        && decision.allReviewed()) {
                                                    ReceiveRevisionStore.recordReviewed(
                                                        project, settings.address(),
                                                        intermediaryIdentity, branch,
                                                        revisionForReview.get());
                                                    confirmedRevision =
                                                        revisionForReview.get();
                                                }
                                                if (!decision.acknowledged().isEmpty()) {
                                                    ReceivePendingStore.acknowledgeReviewed(
                                                        project, settings.address(),
                                                        branch, intermediaryIdentity,
                                                        decision.acknowledged());
                                                }
                                                acknowledgedCount =
                                                    decision.acknowledged().size();
                                                if (!decision.allReviewed()) {
                                                    reason = "Часть изменений не "
                                                        + "входила в показанное EDT "
                                                        + "сравнение и оставлена "
                                                        + "ожидающей.";
                                                }
                                                BridgeActivator.getDefault().getLog().info(
                                                    "Native EDT review recorded; "
                                                    + "acknowledged=" + acknowledgedCount
                                                    + "; remaining="
                                                    + decision.remaining().size()
                                                    + "; headAttested="
                                                    + !inventoryOnly);
                                            }
                                        }

                                        int pendingAfter = ReceivePendingStore.get(
                                            project, settings.address(), branch,
                                            intermediaryIdentity).size();
                                        StringBuilder report = new StringBuilder(
                                            "Результат штатного объединения EDT: ")
                                            .append(nativeFinished
                                                ? "завершён штатным механизмом EDT."
                                                : (mergeConfirmed
                                                    ? "подтверждён разработчиком."
                                                    : "не подтверждён."))
                                            .append("\nЗаписей ожидания закрыто: ")
                                            .append(acknowledgedCount)
                                            .append(".\nОсталось ожидающих записей: ")
                                            .append(pendingAfter).append(".");
                                        if (confirmedRevision > 0) {
                                            report.append("\nВерсия хранилища ")
                                                .append(confirmedRevision)
                                                .append(" отмечена как обработанная.");
                                            report.append("\nЛокальные отличия, ")
                                                .append("сохранённые в EDT, ")
                                                .append("не считаются ошибкой.");
                                        } else {
                                            report.append("\nОбработка версии ")
                                                .append("хранилища целиком ")
                                                .append("не подтверждена.");
                                        }
                                        if (reason != null) {
                                            report.append("\nПричина: ")
                                                .append(reason);
                                        }
                                        informationOnUi(shell, "Результат объединения EDT",
                                            report.toString());
                                        return Status.OK_STATUS;
                                    } catch (Exception ex) {
                                        BridgeActivator.getDefault().getLog().warn(
                                            "Native EDT merge acknowledgement failed ("
                                            + ex.getClass().getSimpleName()
                                            + "); unresolved objects remain pending.");
                                        informationOnUi(shell, "Завершение объединения EDT",
                                            "Не удалось сохранить подтверждение "
                                            + "результата штатного объединения EDT. "
                                            + "Изменения, для которых не завершена "
                                            + "фиксация, остаются в журнале. "
                                            + "Номер хранилища не выдумывается. "
                                            + "Тип ошибки: "
                                            + ex.getClass().getSimpleName()
                                            + ". Подробности в журнале ошибок EDT.");
                                        return new Status(IStatus.WARNING,
                                            "org.xander.edt.storagebridge.ui",
                                            "Подтверждение результата EDT не завершено; "
                                            + "сведения о получении требуют проверки.");
                                    } finally {
                                        cleanupQuietly(
                                            retainedExport, retainedConverted);
                                        ACTIVE.remove(operationKey);
                                        ACTIVE_INTERMEDIARIES.remove(intermediaryKey);
                                    }
                                }
                            };
                            finish.setSystem(true);
                            finish.schedule();
                        }
                    }
                };
                try {
                    page.addPartListener(listener);
                    // Staging is now owned by the comparison editor listener.
                } catch (RuntimeException ex) {
                    nativeMergeWitness.close();
                    page.closeEditor(editor, false);
                    throw ex;
                }
                return null;
            });
            editorOwnsStaging = true;
            if (initialFullReview) {
                // Mark only once the native review editor is actually open,
                // not after a successful dump, conversion or user cancellation.
                journal.markInitialReviewPrepared();
            }
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
            if (current instanceof EdtSelectiveReviewService.NativeEdtImportFailure nativeFailure) {
                diagnostic.append("; import status severity=").append(nativeFailure.severity())
                    .append("; import code=").append(nativeFailure.code())
                    .append("; import nested statuses=").append(nativeFailure.descendants());
                // Native EDT exception messages remain ONLY in the local
                // diagnostic file. Never log them together with credentials.
            }
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

    /**
     * Read-only 1C ConfigDumpInfo snapshot equality to the exact source
     * staged for native EDT merge. Does NOT certify remote HEAD and MUST
     * NOT advance reviewed/applied numerical revisions.
     */
    private static boolean intermediaryInventoryUnchanged(
            ReceiveBaselineJournal journal, ChangeDetectionRequest request,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        if (monitor.isCanceled()) {
            throw new InterruptedException("Receive inventory check cancelled");
        }
        Path staged = journal.lastScannedInfo();
        if (staged == null) {
            throw new IOException("Receive source inventory is unavailable");
        }
        var expected = ConfigDumpInfoIndex.readVersions(staged);
        try (var current = new ConfigVersionSnapshotRunner().capture(
                request, monitor::isCanceled)) {
            if (monitor.isCanceled()) {
                throw new InterruptedException("Receive inventory check cancelled");
            }
            return expected.equals(
                ConfigDumpInfoIndex.readVersions(current.configDumpInfo()));
        }
    }

    /**
     * Best-effort native latest revision from the existing connected base.
     * Report syntax and encoding vary between 1C platform builds; unknown is
     * never treated as the current repository version or an applied version.
     */
    private static long readLatestRevisionOrUnknown(Path executable,
            InfobaseLocation intermediary,
            StorageProjectSettings.Snapshot settings,
            BooleanSupplier canceled) {
        return readLatestRevisionOrUnknown(
            executable, intermediary, settings, Duration.ofMinutes(5), canceled);
    }

    // The FINAL confirmation uses a shorter bounded Designer call, retried
    // on transient failures. Otherwise four native 5-minute timeouts could
    // leave the post-merge verification Job running for twenty minutes.
    private static long readLatestRevisionOrUnknown(Path executable,
            InfobaseLocation intermediary,
            StorageProjectSettings.Snapshot settings,
            Duration timeout, BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            return ReceiveRevisionStore.UNKNOWN;
        }
        try {
            return RepositoryLatestRevisionReader.latest(
                RepositoryHistoryRequest.latest(executable, intermediary,
                    timeout),
                settings.infobaseUsername(), settings.infobasePassword(),
                settings.address(), settings.username(), settings.password(),
                canceled);
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            // Neither Designer report text nor credentials may enter logs.
            BridgeActivator.getDefault().getLog().warn(
                "Native 1C repository revision is unavailable: "
                + RepositoryRevisionRefreshCoordinator.failureReason(ex)
                + " Version fields remain unverified.");
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ReceiveRevisionStore.UNKNOWN;
        }
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
        if (failure instanceof EdtSelectiveReviewService.NativeEdtImportFailure nativeFailure) {
            return "Штатный XML-импортёр EDT вернул неуспешный статус ("
                + (nativeFailure.fullReview() ? "полная" : "выборочная")
                + " выгрузка). Severity: " + nativeFailure.severity()
                + ", код: " + nativeFailure.code()
                + ", вложенных статусов: " + nativeFailure.descendants()
                + ". " + (nativeFailure.diagnosticsFile() == null
                    ? "Диагностику не удалось сохранить."
                    : "Подробный журнал импортёра: "
                      + nativeFailure.diagnosticsFile()
                      + ". Перед отправкой проверьте файл: он может содержать "
                      + "пути и имена объектов конфигурации.")
                + " Рабочий проект не менялся; ожидающие изменения сохранены. "
                + (nativeFailure.fullReview()
                    ? "Проверьте ошибки импортёра EDT."
                    : "Можно повторить операцию через «Полная сверка с хранилищем».");
        }
        if (failure instanceof EdtSelectiveReviewService.MissingEmbeddedLanguagesException mismatch) {
            return "Импортированная Configuration.mdo не содержит "
                + "всех запрошенных языков. Отсутствует: "
                + mismatch.missingCount()
                + ". Сравнение не запускалось; изменения сохранены в ожидании.";
        }
        if (failure instanceof EdtSelectiveReviewService.MissingConvertedOwnersException mismatch) {
            return "После импорта XML в EDT не найдены основные файлы .mdo "
                + "для всех запрошенных объектов. Ожидалось владельцев: "
                + mismatch.expectedOwners() + ", найдено: "
                + mismatch.matchedOwners() + ", всего основных .mdo: "
                + mismatch.primaryDescriptors() + ". Отсутствуют по типам: "
                + mismatch.missingTypes()
                + ". Объекты остаются ожидающими; сравнение не запускалось.";
        }
        if (failure instanceof EdtSelectiveReviewService.UnsupportedMetadataTypesException types) {
            return "Нет соответствия типов метаданных 1С и EDT: "
                + String.join(", ", types.types())
                + ". Конвертация остановлена до импорта. "
                + "Ожидающие изменения сохранены.";
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

    private static boolean isConfigurationRootRecord(String name) {
        return "Configuration".equals(name)
            || name.startsWith("Configuration.")
            || "Language".equals(name)
            || name.startsWith("Language.")
            || "Язык".equals(name)
            || name.startsWith("Язык.");
    }

    public static String topObject(String name) {
        if (isConfigurationRootRecord(name)) {
            return "Configuration";
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
