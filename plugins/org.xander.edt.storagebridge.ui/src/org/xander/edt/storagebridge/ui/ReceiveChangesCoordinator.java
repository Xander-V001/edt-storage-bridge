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
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.eclipse.ui.dialogs.ListDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.xander.edt.storagebridge.core.ReceiveConfirmationTexts;
import org.xander.edt.storagebridge.core.ReceivePendingExplanation;
import org.xander.edt.storagebridge.core.ReceiveReviewScope;
import org.xander.edt.storagebridge.core.ReceiveCompletionPolicy;
import org.xander.edt.storagebridge.core.ReceiveAttemptLedger.Phase;
import org.xander.edt.storagebridge.edt.EdtDeltaComparisonService;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;
import org.xander.edt.storagebridge.edt.EdtPublicServiceBridge;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;
import org.xander.edt.storagebridge.platform.SelectiveDumpPlanner;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.InitialFullDumpRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRunner;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;
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
    /**
     * TEMPORARILY DISABLED (21.10.2026): duplicate checkbox dialog shown
     * AFTER native EDT Merge. Cancel left a false PENDING queue, while OK
     * could falsely imply all changes accepted. Retain the original dialog
     * and method for rollback until session observation is proven in EDT 2026.2.
     * Never replace it with byte-level EDT resource comparison.
     */
    private static final boolean LEGACY_KEEP_LOCAL_DIALOG_ENABLED = false;
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
                ? "1С: Получить полную конфигурацию из хранилища — "
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
        // One native -update/-getChanges run produces XML and changed names.
        // Supplement XML in place for EDT, then transfer cleanup ownership.
        AtomicReference<ChangedObjectsProbe.NativeExport> nativePatch =
            new AtomicReference<>();
        // A real -getChanges report can survive a failed platform XML patch.
        // Its names may then be transported using documented -listFile;
        // never rebuild change identities from ConfigDumpInfo tokens.
        AtomicReference<ChangedObjectsProbe.NativeChanges> nativeChangeReport =
            new AtomicReference<>();
        AtomicReference<ChangedObjectsProbe.NativeStageIncomplete> nativePatchFailure =
            new AtomicReference<>();
        Path conversionRoot = null;
        boolean editorOwnsStaging = false;
        AtomicReference<String> phase = new AtomicReference<>("Проверка настроек");
        AtomicBoolean updateAttempted = new AtomicBoolean(false);
        // Native exit=0 is not yet a verified post-update base inventory.
        AtomicBoolean updateNeedsSnapshot = new AtomicBoolean(false);
        // Recovery may be proven without a second 1C update when the
        // complete native inventory still matches an already reviewed
        // and revision-attested previous source snapshot.
        AtomicBoolean recoveredSameReviewedSource = new AtomicBoolean(false);
        // A real native repository revision, NEVER a ConfigDumpInfo token.
        // Unknown revision must not block the existing XML receiving flow.
        AtomicLong revisionForReview = new AtomicLong(ReceiveRevisionStore.UNKNOWN);
        // A successful object comparison does not establish the native
        // repository revision. Preserve the exact missing proof reason for
        // the user instead of reporting that they failed to review objects.
        AtomicReference<String> revisionProofIssue = new AtomicReference<>();
        // Native 1C decides new/modified objects. The plugin only decides
        // whether the same native XML patch can be compared safely by EDT.
        AtomicBoolean changeScopeRequiresFull = new AtomicBoolean(false);
        // Native -getChanges may mention independently developed properties
        // not in the versioned inventory. Keep these exact 1C names instead
        // of classifying them as deleted or fabricating parents.
        AtomicReference<Set<String>> unindexedNativeNames =
            new AtomicReference<>(Set.of());
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
            // A pending-only retry and a fresh repository update are different
            // operations. Cancel means CANCEL, never an implicit update.
            final boolean hadUnresolvedBefore = !ReceivePendingStore.get(
                project, settings.address(), branch, intermediaryIdentity).isEmpty();
            // Capture PRE-update source checkpoint before any new SCANNED
            // certificate replaces the previous metadata manifest.
            final long previouslyReviewed = ReceiveRevisionStore.reviewed(
                project, settings.address(), intermediaryIdentity, branch);
            final long previouslyScanned = journal.scannedRevision();
            boolean pendingAvailable = mode == ReceiveMode.INCREMENTAL
                && !recovery && !firstInventory && hadUnresolvedBefore;
            int pendingChoice = -1;
            long stagedPendingRevision = ReceiveRevisionStore.UNKNOWN;
            long latestPendingRevision = ReceiveRevisionStore.UNKNOWN;
            if (pendingAvailable) {
                stagedPendingRevision = journal.scannedRevision();
                latestPendingRevision = readLatestRevisionOrUnknown(
                    executable, transport, settings, Duration.ofSeconds(30),
                    jobProgress::isCanceled);
                String pendingDescription = ReceiveConfirmationTexts.pendingChoices(
                    settings.address(), settings.infobaseAddress(), fullReview);
                if (stagedPendingRevision > 0 && latestPendingRevision > stagedPendingRevision) {
                    pendingDescription += "\n\nВНИМАНИЕ: хранилище уже на версии "
                        + latestPendingRevision + ", а сохранённый снимок — "
                        + stagedPendingRevision + ". Для получения НОВОЙ версии "
                        + "выбирайте «Обновить и сравнить». "
                        + "Повторное сравнение НИЧЕГО не получает из хранилища.";
                } else if (latestPendingRevision < 1) {
                    pendingDescription += "\n\nТекущую версию хранилища пока "
                        + "не удалось проверить. «Повторно сравнить» "
                        + "не получает новых изменений.";
                }
                // An update to latest is the primary action; reopening an
                // old pending stage must never be mistaken for that update.
                // Old pending XML can be replayed only while its saved
                // native HEAD is still the current repository HEAD. The
                // checkpoint store cannot attest an older version against
                // a newer/unknown HEAD; do not offer a dead-end choice.
                boolean canReplayAttestedPending =
                    stagedPendingRevision > 0
                    && stagedPendingRevision == latestPendingRevision;
                if (canReplayAttestedPending) {
                    pendingChoice = choiceOnUi(shell, "Как получить изменения?",
                        pendingDescription,
                        "Обновить и сравнить", "Повторно сравнить", "Отмена");
                    if (pendingChoice != 0 && pendingChoice != 1) {
                        return;
                    }
                } else {
                    pendingChoice = choiceOnUi(shell, "Как получить изменения?",
                        pendingDescription
                        + "\n\nСтарую очередь нельзя подтвердить при другой "
                        + "или неизвестной актуальной версии хранилища. "
                        + "Для продолжения требуется новое получение.",
                        "Обновить и сравнить", "Отмена");
                    if (pendingChoice != 0) {
                        return;
                    }
                }
            }

            boolean canRetryPendingOnly = pendingChoice == 1;
            boolean freshUpdateApproved = pendingChoice == 0;
            BridgeActivator.getDefault().getLog().info(
                "Receive route: mode=" + mode
                + "; retryPendingOnly=" + canRetryPendingOnly
                + "; fullReview=" + fullReview
                + "; pendingStageVersion=" + stagedPendingRevision
                + "; pendingLatestVersion=" + latestPendingRevision);
            if (canRetryPendingOnly) {
                boolean sameInventory = progress(jobProgress, 5, monitor -> {
                    phase.set("Чтение снимка базы перед повторной выгрузкой");
                    monitor.beginTask("Контроль базы-посредника (только чтение)", 2);
                    try (var snapshot = new ConfigVersionSnapshotRunner()
                            .capture(detect, monitor::isCanceled)) {
                        monitor.worked(1);
                        phase.set("Сопоставление с сохранённым составом базы");
                        // A repeat-only operation requires the SAME native
                        // source manifest, byte-for-byte. Never compare
                        // the platform's opaque id/configVersion ourselves.
                        boolean unchanged = Files.mismatch(previousInventory,
                            snapshot.configDumpInfo()) == -1;
                        monitor.worked(1);
                        return unchanged;
                    }
                });
                if (!sameInventory) {
                    // A changed staging base cannot be reused against the old
                    // pending identities. Offer a NEW, explicitly approved
                    // update; never silently load the wrong snapshot.
                    freshUpdateApproved = confirmOnUi(shell,
                        "База 1С изменилась",
                        ReceiveConfirmationTexts.changedIntermediary(
                            settings.address(), settings.infobaseAddress()),
                        "Обновить и сравнить");
                    if (!freshUpdateApproved) {
                        return;
                    }
                    canRetryPendingOnly = false;
                }
            }
            final boolean retryPendingOnly = canRetryPendingOnly;
            phase.set("Подтверждение получения из хранилища");
            if (!retryPendingOnly && !freshUpdateApproved
                    && (!recovery || resumePrepared)
                    && !confirmOnUi(shell,
                        mode == ReceiveMode.FULL_REVIEW
                            ? "Получить полную конфигурацию из хранилища"
                            : "Получить изменения из хранилища",
                        ReceiveConfirmationTexts.freshReceive(
                            settings.address(), settings.infobaseAddress(),
                            fullReview, mode == ReceiveMode.FULL_REVIEW),
                        "Обновить и сравнить")) {
                return;
            }

            // For an applied-version claim, require the native repository
            // HEAD to be unchanged across a FRESH Designer receive. If a
            // concurrent commit arrives while Designer updates the base,
            // the staged XML cannot prove it includes that newer revision.
            // Recovery/pending-only retries have no such provenance proof.
            // SNAPSHOT_READY means Designer has NOT started the mutating
            // update. On safe resume, read HEAD before starting it, exactly
            // like a normal fresh receive. Without this proof the completed
            // recovery can never advance REVIEWED.
            final boolean freshNativeUpdate = !retryPendingOnly
                && (!recovery || resumePrepared);
            final long revisionBeforeUpdate =
                freshNativeUpdate
                    ? readLatestRevisionOrUnknown(
                        executable, transport, settings, jobProgress::isCanceled)
                    : ReceiveRevisionStore.UNKNOWN;

            // Do not spend minutes updating the intermediary and merging in
            // EDT if the native repository HEAD cannot be proved at all.
            // Unknown HEAD must never be replaced with a ConfigDumpInfo token.
            if (freshNativeUpdate && revisionBeforeUpdate < 1) {
                informationOnUi(shell, "Версия хранилища недоступна",
                    "1С не подтвердила номер последней версии хранилища. "
                    + "Обновление базы-посредника и сравнение EDT НЕ запускались. "
                    + "Проверьте чтение штатного отчёта хранилища в настройках.");
                return;
            }
            if (freshNativeUpdate
                    && previouslyReviewed > revisionBeforeUpdate) {
                informationOnUi(shell, "Версия хранилища уменьшилась",
                    "Ранее рассмотрена версия " + previouslyReviewed
                    + ", а текущее хранилище сообщает "
                    + revisionBeforeUpdate + ". Автоматическое получение "
                    + "заблокировано: возможно, выбрано другое хранилище.");
                return;
            }
            if (!retryPendingOnly) {
            // The PRE-update ConfigDumpInfo belongs to the intermediary only.
            // It is NEVER the baseline of the current EDT project. The first
            // scan must inventory the entire remote configuration; later scans
            // use the last remote snapshot recorded FOR THIS EDT/GIT BRANCH.
            progress(jobProgress, pendingChoice == 0 ? 40 : 45, monitor -> {
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
                            if (Files.mismatch(journal.path(),
                                    current.configDumpInfo()) != -1) {
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
                    if (recovery && lastScanned != null
                            && previouslyReviewed > 0
                            && previouslyReviewed == previouslyScanned) {
                        recoveredSameReviewedSource.set(
                            Files.mismatch(lastScanned,
                                remote.configDumpInfo()) == -1);
                    }
                    boolean selectAllRemote = fullReview || lastScanned == null;
                    phase.set(selectAllRemote
                        ? (mode == ReceiveMode.FULL_REVIEW
                            ? "Принудительная полная сверка со всем хранилищем"
                            : "Первичная сверка со всем хранилищем")
                        : "Поиск изменений со времени предыдущей сверки EDT");
                    monitor.subTask(selectAllRemote
                        ? "Проверка всех актуальных объектов хранилища"
                        : "Поиск версий объектов, изменённых после предыдущего получения");
                    // The platform itself generates ConfigDumpInfo.xml
                    // (-configDumpInfoOnly) AND determines incremental
                    // changes (-getChanges -configDumpInfoForChanges).
                    // The plugin never edits either native version file.
                    // Compare native NAME SETS only to recognize removals.
                    // Internal 1C configVersion/id values are not our diff API.
                    RemoteReceiveScopePlanner.Changes discovered;
                    if (selectAllRemote) {
                        // The explicit FULL comparison is independent of the
                        // incremental dump and change-list commands. Avoid
                        // letting failures in -getChanges veto a valid native
                        // global EDT comparison. FULL mode imports ALL remote
                        // metadata; no invented per-object pending identities.
                        discovered = RemoteReceiveScopePlanner.fullInventory(
                            lastScanned, remote.configDumpInfo());
                    } else {
                        // Even when the user requested a FULL comparison,
                        // discover actual changed identities using native 1C.
                        // Full review widens only the EDT comparison scope,
                        // never the persisted pending identity list.
                        phase.set("Штатное определение изменений платформой 1С");
                        monitor.subTask("Конфигуратор 1С формирует список изменений");
                        // Keep the native -update XML patch. A separate
                        // -getChanges supplies exact changed identities;
                        // -listFile supplements unchanged EDT dependencies.
                        // This NEVER repeats RepositoryUpdateCfg.
                        ChangedObjectsProbe.NativeChanges nativeDelta;
                        try {
                            var nativeDump = new ChangedObjectsProbe().exportNative(
                                detect, lastScanned, canceled);
                            nativePatch.set(nativeDump);
                            nativeDelta = nativeDump.changes();
                        } catch (ChangedObjectsProbe.NativeStageIncomplete incomplete) {
                            if (incomplete.nativeChanges().fullDump()) {
                                throw incomplete;
                            }
                            if (incomplete.nativeChanges().exactNames().isEmpty()) {
                                // Designer wrote a truly EMPTY change list,
                                // but no incremental XML version manifest.
                                // That is NOT a successful XML export.
                                // However, there is no XML to import if
                                // TWO complete 1C-created ConfigDumpInfo
                                // inventories are byte-identical. Only then
                                // may the receiver evaluate its separate
                                // REVIEWED/SCANNED + native HEAD no-delta
                                // certificates; never invent a missing file.
                                if (!incomplete.emptyDeltaMissingOnlyManifest()
                                        || Files.mismatch(lastScanned,
                                            remote.configDumpInfo()) != -1) {
                                    throw incomplete;
                                }
                                BridgeActivator.getDefault().getLog().warn(
                                    "Native 1C changed-object report is empty "
                                    + "without incremental XML manifest; "
                                    + "independent full 1C inventory unchanged. "
                                    + "No source XML will be imported.");
                            } else {
                                BridgeActivator.getDefault().getLog().warn(
                                    "Native XML patch incomplete: using "
                                    + "documented selective -listFile for "
                                    + incomplete.reportedChanges()
                                    + " native changed identities");
                            }
                            nativePatchFailure.set(incomplete);
                            nativeDelta = incomplete.nativeChanges();
                        }
                        nativeChangeReport.set(nativeDelta);
                        // The ConfigDumpInfo.xml IN A PATCH may list
                        // ONLY the patch's exported objects. It must NEVER
                        // be treated as a full configuration snapshot.
                        // Compare two complete, platform-created
                        // -configDumpInfoOnly outputs instead. This reads
                        // metadata only, not the full XML configuration.
                        try (var afterPatch = new ConfigVersionSnapshotRunner()
                                .capture(detect, canceled)) {
                            if (Files.mismatch(remote.configDumpInfo(),
                                    afterPatch.configDumpInfo()) != -1) {
                                throw new IOException(
                                    "База-посредник изменилась во время выгрузки 1С");
                            }
                        }
                        discovered = RemoteReceiveScopePlanner.reconcileNative(
                            lastScanned, remote.configDumpInfo(), nativeDelta);
                        BridgeActivator.getDefault().getLog().info(
                            "Native getChanges: changed="
                            + nativeDelta.exactNames().size()
                            + "; fullDump=" + nativeDelta.fullDump());
                    }
                    Set<String> remoteNames = discovered.pendingIdentities();
                    changeScopeRequiresFull.set(
                        discovered.requiresFullNativeComparison());
                    unindexedNativeNames.set(discovered.unindexed());
                    BridgeActivator.getDefault().getLog().info(
                        "Repository delta: changed=" + discovered.present().size()
                        + "; removed=" + discovered.removed().size()
                        + "; unindexedNativeChanges="
                        + discovered.unindexed().size()
                        + "; fullNeeded=" + changeScopeRequiresFull.get());
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
            BridgeActivator.getDefault().getLog().info(
                "Receive source attestation: before=" + revisionBeforeUpdate
                + "; after=" + observedRevision
                + "; stagedForReview=" + revisionForReview.get()
                + "; pendingRetry=" + retryPendingOnly
                + "; fullReview=" + fullReview);
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

            // If the native repository HEAD moved while Designer was
            // updating the intermediary, this staged source cannot honestly
            // claim the new HEAD. Never spend another long EDT merge on it.
            // The intermediary scan and exact pending delta have already
            // been safely journaled; a fresh receive is the next action.
            if (freshNativeUpdate
                    && revisionBeforeUpdate > 0 && observedRevision > 0
                    && revisionBeforeUpdate != observedRevision) {
                BridgeActivator.getDefault().getLog().warn(
                    "Receive stopped before EDT compare: repository HEAD moved"
                    + "; before=" + revisionBeforeUpdate
                    + "; after=" + observedRevision
                    + "; native staged inventory remains journaled.");
                informationOnUi(shell, "Версия хранилища изменилась",
                    "Пока 1С обновляла базу-посредник, хранилище перешло "
                    + "с версии " + revisionBeforeUpdate + " на "
                    + observedRevision + ".\n\n"
                    + "Сравнение EDT НЕ запущено: нельзя выдавать "
                    + "устаревший снимок за актуальное обновление.\n\n"
                    + "Для получения текущей версии запустите "
                    + "«Получить изменения» → «Обновить и сравнить». "
                    + "Ни один объект EDT в этой попытке не применялся.");
                return;
            }
            // Without a proven native repository revision, EDT must not
            // waste a merge session which cannot be recorded as REVIEWED.
            // The exact pending identities are already journaled.
            if (revisionForReview.get() < 1) {
                informationOnUi(shell, "Версия хранилища не подтверждена",
                    (revisionProofIssue.get() != null
                        ? revisionProofIssue.get() : "1С не вернула подтверждённую версию.")
                    + "\nИзменения сохранены для повторной сверки. "
                    + "Сравнение EDT пока не запускалось.");
                return;
            }
            phase.set("Загрузка ожидающих обработки объектов");
            Set<String> pending = ReceivePendingStore.get(
                project, settings.address(), branch, intermediaryIdentity);
            // Pending identifiers describe changes in the source 1C storage,
            // NOT changes already presented in the native EDT merge editor.
            // In particular, ConfigDumpInfo Configuration/Language entries
            // are deliberately deferred from SELECTIVE XML conversion.
            Set<String> currentInventory = ConfigDumpInfoIndex.exportableNames(
                journal.lastScannedInfo());
            // Availability is about whether a metadata identity exists in
            // the current native 1C model, not whether it has its OWN
            // configVersion token. Independently developed unversioned
            // children must be eligible for a later selective retry.
            Set<String> allCurrentMetadata = ConfigDumpInfoIndex.allMetadataNames(
                journal.lastScannedInfo());
            Set<String> reviewCandidates = RemoteReceiveScopePlanner
                .availableInFullReview(pending, allCurrentMetadata);
            var notInSource = new LinkedHashSet<>(pending);
            notInSource.removeAll(reviewCandidates);
            // These exact names were emitted by 1C itself in this receive,
            // even though they have no independent ConfigDumpInfo token.
            notInSource.removeAll(unindexedNativeNames.get());
            final Set<String> absentFromRemote = Set.copyOf(notInSource);

            if (pending.isEmpty() && !fullReview
                    && !changeScopeRequiresFull.get()) {
                // 1C can advance HEAD for a repository comment/metadata-only
                // commit without changing any configuration object. The
                // native patch must be truly empty; an empty user-facing
                // pending set alone is NOT enough to acknowledge the HEAD.
                var delta = nativeChangeReport.get();
                boolean emptyNativeDelta = delta != null
                    && !delta.fullDump()
                    && delta.exactNames().isEmpty();
                boolean canAdvance = !retryPendingOnly
                    && ((!recovery
                        && ReceiveCompletionPolicy.canAcknowledgeEmptyNativeDelta(
                            previouslyReviewed, previouslyScanned,
                            revisionBeforeUpdate, revisionForReview.get(),
                            observedRevision, emptyNativeDelta, true))
                        || (recovery
                            && ReceiveCompletionPolicy.canAcknowledgeRecoveredEmptyDelta(
                                previouslyReviewed, previouslyScanned,
                                observedRevision,
                                recoveredSameReviewedSource.get(),
                                emptyNativeDelta, true)));
                if (canAdvance) {
                    GitReceiveGuard.requireUnchanged(project, git);
                    long finalHead = readLatestRevisionOrUnknown(
                        executable, transport, settings,
                        Duration.ofSeconds(30), jobProgress::isCanceled);
                    if (finalHead == observedRevision) {
                        ReceiveCompletionRecorder.reviewedNoChanges(
                            project, settings.address(), intermediaryIdentity,
                            branch, observedRevision);
                        BridgeActivator.getDefault().getLog().info(
                            "Native no-delta receive: reviewedRevision="
                            + observedRevision + "; previousReviewed="
                            + previouslyReviewed + "; no EDT merge necessary");
                        informationOnUi(shell, "Версия хранилища проверена",
                            "1С подтвердила отсутствие изменений объектов"
                            + (recovery
                                ? " после восстановления предыдущей попытки. "
                                : ". ")
                            + "Обработанная версия хранилища: "
                            + observedRevision + ".\nСравнение EDT не требуется. "
                            + "Существующие локальные изменения не затрагивались.");
                        return;
                    }
                }
                informationOnUi(shell, "Нет изменений для сравнения EDT",
                    "Штатная 1С не сообщила изменений объектов, но новая "
                    + "обработанная версия не может быть подтверждена "
                    + "по сохранённой исходной точке.\n"
                    + "Для установления подтверждённой базы выполните "
                    + "«Получить полную конфигурацию из хранилища».");
                return;
            }
            // Native 1C decides changes; full review is an explicit mode.
            boolean requiresFull = fullReview
                || recovery || changeScopeRequiresFull.get()
                || !absentFromRemote.isEmpty();
            if (requiresFull && !fullReview) {
                // Diagnostic ONLY. Do not widen/narrow native comparison or
                // bypass EDT/1C's FullDump request. Previously this warning
                // listed only PENDING and deletions, so a perfectly legitimate
                // FullDump from Designer looked like an unexplained fallback.
                var reported = nativeChangeReport.get();
                boolean platformFullDump =
                    reported != null && reported.fullDump();
                boolean rootOrLanguageChange = reported != null
                    && reported.exactNames().stream().anyMatch(name ->
                        "Configuration".equals(name)
                        || name.startsWith("Configuration.")
                        || "Language".equals(name)
                        || name.startsWith("Language.")
                        || "Язык".equals(name)
                        || name.startsWith("Язык."));
                StringBuilder whyFull = new StringBuilder();
                if (platformFullDump) {
                    whyFull.append("• Платформа 1С вернула FullDump вместо "
                        + "выборочного списка изменений. Это прямое "
                        + "требование полного экспорта.\n");
                }
                if (rootOrLanguageChange) {
                    whyFull.append("• В штатном списке изменений есть "
                        + "Configuration или Language: требуется "
                        + "полное сравнение EDT.\n");
                }
                if (recovery) {
                    whyFull.append("• Восстанавливается незавершённая "
                        + "попытка получения.\n");
                }
                if (!absentFromRemote.isEmpty()) {
                    whyFull.append("• Отсутствуют в текущем составе "
                        + "хранилища записи: ")
                        .append(absentFromRemote.size()).append(".\n");
                }
                if (whyFull.isEmpty()) {
                    // Defensive diagnostic: preserve existing safe fallback.
                    whyFull.append("• Текущий состав изменений требует "
                        + "глобального сравнения EDT.\n");
                }
                BridgeActivator.getDefault().getLog().info(
                    "Full comparison required: nativeFullDump="
                    + platformFullDump + "; rootOrLanguageChange="
                    + rootOrLanguageChange + "; recovery=" + recovery
                    + "; missingFromRemote=" + absentFromRemote.size());
                boolean accept = confirmOnUi(shell,
                    "Требуется полное сравнение EDT",
                    "Почему требуется полное сравнение:\n"
                    + whyFull + "\n"
                    + "Осталось изменений от предыдущих получений: "
                    + (hadUnresolvedBefore ? "да" : "нет") + ".\n"
                    + "Удалённых или отсутствующих в источнике записей: "
                    + absentFromRemote.size() + ".\n\n"
                    + "Полная выгрузка будет сделана только для "
                    + "подготовки сравнения EDT. База-посредник второй "
                    + "раз из хранилища не обновляется.\n"
                    + "Отмена сохранит все изменения в журнале.",
                    "Полное сравнение");
                if (!accept) {
                    return;
                }
            }
            final boolean fullNativeComparison = requiresFull;
            final Set<String> deferredDuringReview = Set.of();
            var sourceCovered = new LinkedHashSet<>(
                ReceiveReviewScope.eligible(pending, allCurrentMetadata,
                    deferredDuringReview, fullNativeComparison));
            // The exact names listed by native -getChanges can include
            // embedded properties which do not have their own version token.
            sourceCovered.addAll(unindexedNativeNames.get());
            // A full EDT comparison includes objects removed from the remote
            // side; the developer may review those deletions explicitly.
            if (fullNativeComparison) {
                sourceCovered.addAll(absentFromRemote);
            }
            final Set<String> selectedPendingNames = Set.copyOf(sourceCovered);
            List<String> selectedList = new ArrayList<>();
            Set<String> selected;
            if (fullNativeComparison) {
                selected = Set.of();
            } else {
                var delta = nativeChangeReport.get();
                if ((delta == null && !retryPendingOnly)
                        || (delta != null && delta.fullDump())) {
                    throw new IOException(
                        "Нет достоверного списка изменений 1С");
                }
                var exactNames = new java.util.LinkedHashSet<String>();
                if (delta != null) {
                    exactNames.addAll(delta.exactNames());
                }
                // Previously saved PENDING identities are sourced from the
                // native report, not fabricated from the full inventory.
                // The intermediary snapshot was independently verified.
                if (hadUnresolvedBefore || retryPendingOnly) {
                    exactNames.addAll(pending);
                }
                selectedList.addAll(exactNames);
                if (selectedList.isEmpty()) {
                    // The version-only native export is not a merge source.
                    informationOnUi(shell, "Новых изменений нет",
                        "1С не обнаружила изменений относительно "
                        + "сохранённого снимка.");
                    return;
                }
                selected = topObjects(selectedList);
                EdtSelectiveReviewService.requireSupportedObjectTypes(
                    selectedList);
            }

            phase.set("Проверка Git перед подготовкой сравнения");
            GitReceiveGuard.requireUnchanged(project, git);
            phase.set(fullNativeComparison
                ? "Полная XML-выгрузка для сравнения EDT"
                : "Проверка штатной инкрементальной выгрузки 1С");
            if (fullNativeComparison) {
                // The incremental native patch, if any, is not relevant to a
                // full review and must be closed before the heavy full dump.
                var unused = nativePatch.getAndSet(null);
                if (unused != null) {
                    unused.close();
                }
                export = progress(jobProgress, 20, monitor -> {
                    monitor.beginTask("Полная XML-выгрузка в отдельный каталог", 1);
                    var result = new SelectiveDumpRunner().exportInitialFull(
                        new InitialFullDumpRequest(executable, transport,
                            Duration.ofHours(2), settings.infobaseUsername(),
                            settings.infobasePassword()), monitor::isCanceled);
                    monitor.worked(1);
                    return result;
                });
            } else {
                // Follow 1C-Company/GitConverter: preserve the XML produced
                // by native -update; complete it IN PLACE using -listFile for
                // separately developed dependencies not already in the delta.
                var plan = SelectiveDumpPlanner.planIncludingRelated(
                    selectedList, allCurrentMetadata);
                if (!plan.deferred().isEmpty() || plan.exportable().isEmpty()) {
                    throw new IOException(
                        "Корневые изменения нельзя обработать выборочно. "
                        + "Используйте отдельную команду полной сверки.");
                }
                var original = nativeChangeReport.get();
                boolean canReuseNativePatch = nativePatch.get() != null
                    && nativePatchFailure.get() == null
                    && !retryPendingOnly && !hadUnresolvedBefore;
                if (canReuseNativePatch) {
                    // The unchanged siblings, parent descriptors and import
                    // context are supplemental; changed items are already
                    // written by the native -update.
                    var supplement = SelectiveDumpPlanner
                        .supplementsForNativePatch(plan, allCurrentMetadata,
                            original.exactNames());
                    var patch = nativePatch.get();
                    phase.set("Довыгрузка зависимых объектов для импорта EDT");
                    if (!supplement.isEmpty()) {
                        new SelectiveDumpRunner().supplement(
                            new SelectiveDumpRequest(executable, transport,
                                supplement, Duration.ofMinutes(30),
                                settings.infobaseUsername(),
                                settings.infobasePassword()),
                            patch.xmlDirectory(), jobProgress::isCanceled);
                    }
                    // GitConverter completes the root in a SEPARATE export:
                    // Configuration.xml, Ext, then languages from that root.
                    // Do not insert Configuration into the ordinary owner list.
                    new SelectiveDumpRunner().completeNativeRoot(
                        new SelectiveDumpRequest(executable, transport,
                            List.of("Configuration"), Duration.ofMinutes(30),
                            settings.infobaseUsername(),
                            settings.infobasePassword()),
                        patch.xmlDirectory(), jobProgress::isCanceled);
                    // Transfer ownership of the COMPLETE staged native XML;
                    // no second whole-owner export discards the patch.
                    export = new SelectiveDumpRunner.ExportResult(
                        patch.workingRoot(), patch.xmlDirectory());
                    nativePatch.set(null);
                } else {
                    // A previous unresolved batch cannot be reconstructed
                    // solely from today's native patch. Export those exact
                    // pending owners once with all current dependencies.
                    var patch = nativePatch.getAndSet(null);
                    if (patch != null) {
                        patch.close();
                    }
                    phase.set("Выборочная выгрузка накопленных изменений 1С");
                    var request = new SelectiveDumpRequest(
                        executable, transport, plan.exportable(),
                        Duration.ofMinutes(30),
                        settings.infobaseUsername(), settings.infobasePassword());
                    export = new SelectiveDumpRunner().export(
                        request, jobProgress::isCanceled);
                    try {
                        new SelectiveDumpRunner().completeNativeRoot(
                            request, export.xmlDirectory(),
                            jobProgress::isCanceled);
                    } catch (IOException | InterruptedException failure) {
                        export.close();
                        export = null;
                        throw failure;
                    }
                }
                if (!intermediaryInventoryUnchanged(journal, detect, jobProgress)) {
                    export.close();
                    export = null;
                    throw new IOException(
                        "База-посредник изменилась во время выборочной выгрузки 1С");
                }
            }
            // Converted EDT files, like native 1C XML, stay OUTSIDE Git.
            phase.set("Создание временных файлов сравнения EDT");
            conversionRoot = Files.createTempDirectory("edt-storage-edt-delta-");
            Path converted = Files.createDirectory(conversionRoot.resolve("edt"));
            // EDT imports the native XML source without custom conversion.
            final AtomicReference<SelectiveDumpRunner.ExportResult> stagedExport =
                new AtomicReference<>(export);
            final AtomicReference<Path> stagedConversion =
                new AtomicReference<>(conversionRoot);
            final boolean effectiveFull = fullNativeComparison;
            // Exactly one read-only native -listFile retry if an otherwise
            // valid 1C incremental PATCH lacks the owner's files required
            // by the native EDT XML importer. Do not loop or re-run the
            // repository update. A previously listFile-based transport
            // must never be retried indefinitely.
            // Incoming source already used the complete-owner -listFile
            // transport. Retrying identical XML would not repair it.
            GitReceiveGuard.requireUnchanged(project, git);

            phase.set("Конвертация объектов и подготовка сравнения EDT");
            var nativeDescriptor = progress(jobProgress, 25, monitor -> {
                monitor.beginTask("Подготовка штатного сравнения EDT", 2);
                try {
                    var descriptor = EdtPublicServiceBridge.prepare(
                        project, stagedExport.get().xmlDirectory(), converted,
                        selectedList, effectiveFull, monitor);
                    monitor.worked(2);
                    return descriptor;
                } catch (EdtSelectiveReviewService.NativeEdtImportFailure
                        | EdtSelectiveReviewService.MissingConvertedOwnersException
                        | EdtSelectiveReviewService.MissingEmbeddedLanguagesException failure) {
                    if (effectiveFull) {
                        // Full mode invokes only the native FULL importer;
                        // report its actual failure, never a selective hint.
                        throw failure;
                    }
                    // A failed selective EDT import is a diagnosis, not an
                    // invitation to secretly run a FULL dump or repeat the
                    // same source export. Keep PENDING and let the developer
                    // explicitly launch Full Review when appropriate.
                    throw new IOException(
                        "EDT не смогла импортировать штатную выборочную XML-выгрузку. "
                        + "Ожидающие изменения сохранены. "
                        + "Исправьте причину импорта либо отдельно запустите "
                        + "«Получить полную конфигурацию из хранилища».", failure);
                }
            });
            // Keep staged native XML until EDT's merge reaches a terminal
            // state; failures before opening the editor release it promptly.
            export = stagedExport.get();
            conversionRoot = stagedConversion.get();
            final Set<String> actualSelected = effectiveFull
                ? Set.of() : selected;
            GitReceiveGuard.requireUnchanged(project, git);

            // Selection in this dialog means "show in comparison", NOT
            // "safe to accept remotely". Native EDT two-way comparison
            // automatically prechecks differences for taking the other side.
            // Force a separate, explicit acknowledgement before opening it.
            if (!confirmOnUi(shell,
                    "Проверка перед объединением EDT",
                    (effectiveFull
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
            // The stage contains an actual EDT project at <stage>/edt/src.
            // Keep the parent root only for cleanup, never for verification.
            final Path retainedConvertedEdt = conversionRoot.resolve("edt");
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
                            // Native EDT Merge is ASYNCHRONOUS.
                            // partClosed() is a SWT editor lifecycle event,
                            // NOT proof that the native merge has finished.
                            // Keep the source stage and listener until EDT
                            // emits a terminal merge status in a worker Job.
                            var atEditorClose = nativeMergeWitness.snapshot();
                            BridgeActivator.getDefault().getLog().info(
                                "EDT comparison editor closed; native status="
                                + atEditorClose.lastStatus()
                                + "; mergeStarted=" + atEditorClose.mergeStarted()
                                + "; mergeFinished=" + atEditorClose.finished()
                                + "; cancelled=" + atEditorClose.cancelled()
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
                                    // No delete/no new receive until the EDT
                                    // native engine reports a terminal event.
                                    // Otherwise a still-running merge could
                                    // read files already deleted by our job.
                                    boolean nativeTerminal = false;
                                    // The EDT merge and the POST-merge
                                    // attestation are separate operations.
                                    // Preserve the failing phase without
                                    // logging external paths or credentials.
                                    AtomicReference<String> finishStage =
                                        new AtomicReference<>("ожидание завершения EDT");
                                    try {
                                        var nativeEvidence =
                                            nativeMergeWitness.awaitAfterEditorClose(
                                                5_000L, 15L * 60_000L,
                                                monitor::isCanceled);
                                        final String nativeMergeStatus =
                                            nativeEvidence.lastStatus();
                                        nativeTerminal =
                                            nativeEvidence.canReleaseStaging();
                                        BridgeActivator.getDefault().getLog().info(
                                            "EDT after close: status="
                                            + nativeMergeStatus
                                            + "; mergeStarted="
                                            + nativeEvidence.mergeStarted()
                                            + "; mergeFinished="
                                            + nativeEvidence.finished()
                                            + "; cancelled="
                                            + nativeEvidence.cancelled()
                                            + "; discarded="
                                            + nativeEvidence.discarded()
                                            + "; stagingSafeToRelease="
                                            + nativeTerminal);
                                        if (!nativeTerminal) {
                                            // IMPORTANT: preserve staged
                                            // XML/EDT resources AND active
                                            // receive lock; a native async
                                            // merge may still be running.
                                            informationOnUi(shell,
                                                "EDT не подтвердила завершение Merge",
                                                "После закрытия редактора EDT "
                                                + "не сообщила о завершении "
                                                + "или отмене объединения. "
                                                + "Временная выгрузка сохранена; "
                                                + "повторное получение заблокировано "
                                                + "до диагностики или перезапуска EDT. "
                                                + "Код проекта не изменялся "
                                                + "самим плагином.");
                                            return new Status(IStatus.WARNING,
                                                BridgeActivator.PLUGIN_ID,
                                                "Native EDT merge completion unknown; "
                                                + "staging deliberately retained");
                                        }
                                        finishStage.set("проверка Git после Merge");
                                        final GitReceiveGuard.Baseline postMergeGit =
                                            GitReceiveGuard.requireSafePostMergeGit(
                                                project, git);
                                        BridgeActivator.getDefault().getLog().info(
                                            "Post-merge Git: branchStable=true"
                                            + "; headAdvancedOnBranch="
                                            + !postMergeGit.head().equalsIgnoreCase(git.head()));
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
                                            project, postMergeGit);
                                        String reason = null;
                                        long confirmedRevision = ReceiveRevisionStore.UNKNOWN;
                                        int acknowledgedCount = 0;
                                        int archivedPreviousUnknown = 0;
                                        finishStage.set("чтение журнала ожидающих изменений");
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
                                            // Native HEAD was verified both before
                                            // and after updating the intermediary.
                                            // REVIEWED refers to that staged version,
                                            // not to any later repository commit.
                                            // Outgoing checks live HEAD independently.
                                            boolean allowReview =
                                                revisionForReview.get() > 0;
                                            if (allowReview) {
                                                if (pendingNow.isEmpty() && effectiveFull) {
                                                    // Designer exported the complete source,
                                                    // EDT imported it successfully, and a
                                                    // GLOBAL native MERGE finished. A custom
                                                    // scan of top-level .mdo files does not
                                                    // prove child resource completeness and
                                                    // must not veto the native EDT result.
                                                    finishStage.set("запись рассмотренной версии");
                                                    long finalHead = revisionForReview.get();
                                                    archivedPreviousUnknown =
                                                        ReceiveCompletionRecorder.reviewedGlobalNativeMerge(
                                                            project, settings.address(),
                                                            intermediaryIdentity, branch,
                                                            finalHead, journal, initialFullReview);
                                                    confirmedRevision = finalHead;
                                                    reason = "Полное сравнение EDT завершено; "
                                                        + "REVIEWED = " + finalHead
                                                        + ". APPLIED не менялся.";
                                                } else {
                                                    // EDT's finished native MERGE is the
                                                    // merge evidence, not Files.mismatch
                                                    // on separately serialized .mdo/.distr.
                                                    // Confirm the user's review of the
                                                    // complete SELECTIVE scope explicitly.
                                                    var notPresented =
                                                        new java.util.LinkedHashSet<>(pendingNow);
                                                    notPresented.removeAll(
                                                        selectedPendingNames);
                                                    if (!notPresented.isEmpty()) {
                                                        reason = "Не все ожидающие изменения "
                                                            + "вошли в сравнение EDT ("
                                                            + notPresented.size()
                                                            + "). PENDING сохранён.";
                                                    } else {
                                                        // Native EDT merge status confirms
                                                        // completion, not its individual
                                                        // checkboxes. An explicit per-identity
                                                        // user decision is required to preserve
                                                        // genuine KEPT_LOCAL without byte audits.
                                                        if (!LEGACY_KEEP_LOCAL_DIALOG_ENABLED) {
                                                            // Native Merge itself is the acceptance
                                                            // boundary. Per-object choices cannot
                                                            // yet be proven: record UNKNOWN, never
                                                            // silently assume "accepted".
                                                            long finalHead = revisionForReview.get();
                                                            if (!intermediaryInventoryUnchanged(
                                                                    journal, detect, monitor)) {
                                                                reason = "Конфигурация базы-посредника "
                                                                    + "изменилась во время EDT Merge. "
                                                                    + "PENDING сохранён, REVIEWED не менялся.";
                                                            } else {
                                                                finishStage.set(
                                                                    "фиксация рассмотренной версии");
                                                                GitReceiveGuard
                                                                    .requireSameBranchAndHead(
                                                                        project, postMergeGit);
                                                                acknowledgedCount =
                                                                    ReceiveCompletionRecorder
                                                                        .reviewedNativeSelectiveUnknown(
                                                                            project,
                                                                            settings.address(),
                                                                            intermediaryIdentity,
                                                                            branch, finalHead,
                                                                            pendingNow);
                                                                if (effectiveFull && initialFullReview) {
                                                                    journal.markInitialReviewPrepared();
                                                                }
                                                                confirmedRevision = finalHead;
                                                                reason = "EDT Merge завершён; REVIEWED = "
                                                                    + finalHead
                                                                    + ". Индивидуальные решения EDT "
                                                                    + "учтены как UNKNOWN. APPLIED не менялся.";
                                                            }
                                                        } else {
                                                        // Legacy second checklist retained for
                                                        // controlled rollback; NOT currently shown.
                                                        Set<String> keptLocal =
                                                            chooseKeptLocalOnUi(shell, pendingNow);
                                                        if (keptLocal != null) {
                                                            long finalHead = revisionForReview.get();
                                                            if (!intermediaryInventoryUnchanged(
                                                                    journal, detect, monitor)) {
                                                                reason = "Конфигурация базы-посредника "
                                                                    + "изменилась во время EDT Merge. "
                                                                    + "PENDING сохранён, REVIEWED не менялся."; 
                                                            } else {
                                                                finishStage.set(
                                                                    "фиксация рассмотренной версии");
                                                                GitReceiveGuard
                                                                    .requireSameBranchAndHead(
                                                                        project, postMergeGit);
                                                                acknowledgedCount =
                                                                    ReceiveCompletionRecorder
                                                                        .reviewedNativeSelective(
                                                                            project,
                                                                            settings.address(),
                                                                            intermediaryIdentity,
                                                                            branch, finalHead,
                                                                            pendingNow, keptLocal);
                                                                if (effectiveFull
                                                                        && initialFullReview) {
                                                                    journal.markInitialReviewPrepared();
                                                                }
                                                                confirmedRevision = finalHead;
                                                                reason = (effectiveFull
                                                                        ? "Полное " : "Выборочное ")
                                                                    + "объединение завершено; "
                                                                    + "REVIEWED = " + finalHead
                                                                    + ". Оставлено локально: "
                                                                    + keptLocal.size()
                                                                    + ". Ожидающих изменений: 0. "
                                                                    + "APPLIED не менялся."; 
                                                            }
                                                        } else {
                                                            reason = "Подтверждение выборочного "
                                                                + "объединения отменено; "
                                                                + "PENDING сохранён.";
                                                        }
                                                        } // legacy dialog disabled
                                                    }
                                                }
                                            }
                                        }

                                        finishStage.set("чтение оставшихся изменений");
                                        Set<String> remainingPending =
                                            ReceivePendingStore.get(
                                                project, settings.address(),
                                                branch, intermediaryIdentity);
                                        int pendingAfter = remainingPending.size();
                                        Set<String> retainedLocally =
                                            ReceivePendingStore.keptLocal(
                                                project, settings.address(),
                                                branch, intermediaryIdentity);
                                        int unknownChoices = ReceivePendingStore.unknownChoices(
                                            project, settings.address(), branch,
                                            intermediaryIdentity).size();
                                        StringBuilder report = new StringBuilder(
                                            "Штатное объединение EDT: ")
                                            .append(nativeFinished
                                                ? "завершено."
                                                : "не подтверждено.");
                                        if (!effectiveFull) {
                                            report.append("\nПодтверждено записей учёта: ")
                                                .append(acknowledgedCount).append(".");
                                        }
                                        report.append("\nОжидают проверки: ")
                                            .append(pendingAfter).append(".")
                                            .append("\nРанее подтверждённые сохранённые локальные версии объектов: ")
                                            .append(retainedLocally.size()).append(".")
                                            .append("\nТекущие выборочные решения EDT "
                                                + "с неизвестным исходом: ")
                                            .append(unknownChoices).append(".");
                                        if (archivedPreviousUnknown > 0) {
                                            report.append("\nСтарые UNKNOWN повторно "
                                                + "рассмотрены полной сверкой и "
                                                + "сохранены в истории: ")
                                                .append(archivedPreviousUnknown)
                                                .append(". Это НЕ подтверждение их "
                                                    + "принятия; APPLIED не менялся.");
                                        }
                                        // Native observation is advisory, not a
                                        // per-object choice receipt or APPLIED proof.
                                        report.append("\nНаблюдение настроек EDT: ")
                                            .append(nativeMergeWitness.choiceSummary());
                                        if (pendingAfter > 0) {
                                            report.append(
                                                ReceivePendingExplanation.format(
                                                    remainingPending,
                                                    deferredDuringReview,
                                                    absentFromRemote));
                                        }
                                        if (confirmedRevision > 0) {
                                            report.append("\nВерсия хранилища ")
                                                .append(confirmedRevision)
                                                .append(" отмечена как обработанная.");
                                        } else if (pendingAfter > 0) {
                                            report.append("\nВерсия хранилища "
                                                + "пока не отмечена как полностью "
                                                + "разобранная из-за перечисленных "
                                                + "изменений.");
                                        } else {
                                            report.append("\nВсе записи разобраны, "
                                                + "но номер версии хранилища "
                                                + "ещё не подтверждён.");
                                        }
                                        if (reason != null) {
                                            report.append("\n").append(reason);
                                        }
                                        // TEMPORARY NATIVE EVENT TRACE, not a
                                        // second choice/acknowledgement step:
                                        // the old ListSelectionDialog remains
                                        // disabled and unchanged below. This
                                        // scrollable read-only list lets us
                                        // verify what EDT actually emitted per
                                        // node and which flags were captured.
                                        // The dialog cannot influence REVIEWED,
                                        // PENDING, KEPT_LOCAL or native Merge.
                                        if (!effectiveFull && nativeFinished) {
                                            showObservedEdtChoicesOnUi(shell,
                                                nativeMergeWitness.choiceRows(2000),
                                                nativeMergeWitness.choiceSummary());
                                        }
                                        informationOnUi(shell, "Результат объединения EDT",
                                            report.toString());
                                        return Status.OK_STATUS;
                                    } catch (Exception ex) {
                                        // The previous implementation logged
                                        // ONLY 'IOException', so the same vague
                                        // error appeared for a locked 1C base,
                                        // an absent EDT resource and a journal
                                        // persistence failure. Log sanitized
                                        // own stack frames + the exact stage.
                                        logFailure("Завершение EDT: "
                                            + finishStage.get(), ex);
                                        String cause = ex instanceof
                                                GitReceiveGuard.PostMergeGitMismatch
                                            ? ex.getMessage()
                                            : ex instanceof
                                                ConfigVersionSnapshotRunner.SnapshotFailure
                                                ? safeReason(ex)
                                                : "Тип: " + ex.getClass().getSimpleName()
                                                    + "; место: " + ownFailureSite(ex);
                                        informationOnUi(shell, "Завершение объединения EDT",
                                            "EDT завершила операцию, но не удалось "
                                            + "закончить проверку её результата.\n"
                                            + "Этап: " + finishStage.get() + ".\n"
                                            + cause + ".\n"
                                            + "Уже применённые изменения EDT "
                                            + "не откатываются. Неподтверждённые "
                                            + "изменения не считаются принятыми.\n"
                                            + "Подробности: вид «Журнал ошибок» EDT, "
                                            + "запись Storage receive failure.");
                                        return new Status(IStatus.WARNING,
                                            "org.xander.edt.storagebridge.ui",
                                            "Подтверждение результата EDT не завершено; "
                                            + "сведения о получении требуют проверки.");
                                    } finally {
                                        nativeMergeWitness.close();
                                        if (nativeTerminal) {
                                            cleanupQuietly(
                                                retainedExport, retainedConverted);
                                            ACTIVE.remove(operationKey);
                                            ACTIVE_INTERMEDIARIES.remove(intermediaryKey);
                                        } else {
                                            BridgeActivator.getDefault().getLog().warn(
                                                "Receive native merge state UNKNOWN; "
                                                + "staging and operation guard retained "
                                                + "until a controlled EDT restart.");
                                        }
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
                var leftoverPatch = nativePatch.getAndSet(null);
                if (leftoverPatch != null) {
                    try {
                        leftoverPatch.close();
                    } catch (IOException cleanupError) {
                        BridgeActivator.getDefault().getLog().warn(
                            "Failed to remove unused native 1C patch stage");
                    }
                }
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

    /**
     * LEGACY DISABLED: the second selection dialog is intentionally kept
     * for rollback while native session choice observation is validated.
     * Repeating EDT decisions here caused ambiguous PENDING after Cancel.
     *
     * AFTER native EDT confirmed a completed selective Merge, ask the
     * developer to identify exactly which presented native 1C metadata
     * identities they intentionally kept local, including partial BSL/form
     * merges. A node unchecked here means the user accepts it as reviewed
     * against the incoming source. Do not infer from byte mismatches.
     *
     * null = Cancel: keep every PENDING and DO NOT advance REVIEWED.
     * This UI is local and does not send metadata names to logs or servers.
     */
    /**
     * TEMPORARY DEBUG VIEW (2026-10-21): display the *actual observed*
     * IComparisonSession node events, current native checkbox states and
     * merge rules; never ask the developer to choose objects a second time.
     * This dialog is purely informational. A native tree event can be EDT's
     * own recalculation rather than a user click. Initial states and the last
     * click before Merge may be missing, so the trace NEVER proves APPLIED.
     */
    private static void showObservedEdtChoicesOnUi(Shell shell,
            List<String> observedRows, String summary) throws Exception {
        uiCall(shell, active -> {
            List<String> rows = observedRows.isEmpty()
                ? List.of("EDT не передала ни одного пригодного изменения "
                    + "узлов. Проверить подписку и статус сессии.")
                : observedRows;
            var dialog = new ListDialog(active);
            dialog.setTitle("Выбор EDT — перехваченные события (только просмотр)");
            dialog.setMessage("Флаги и правила взяты из штатной модели EDT. "
                + "Это НЕ повторное подтверждение и НЕ доказательство "
                + "фактического принятия. " + summary);
            dialog.setContentProvider(ArrayContentProvider.getInstance());
            dialog.setLabelProvider(new LabelProvider());
            dialog.setInput(rows);
            dialog.open();
            return null;
        });
    }

    private static Set<String> chooseKeptLocalOnUi(Shell shell,
            Set<String> presented) throws Exception {
        return uiCall(shell, active -> {
            var ordered = presented.stream().sorted().toList();
            var dialog = new ListSelectionDialog(active, ordered,
                ArrayContentProvider.getInstance(), new LabelProvider(),
                "EDT завершила Merge. Отметьте объекты, для которых вы "
                + "сознательно сохранили локальный вариант полностью или "
                + "частично (в том числе процедуры модулей). "
                + "Неотмеченные считаются рассмотренными и принятыми. "
                + "После ОК будет записана версия REVIEWED, но не APPLIED. "
                + "Отмена оставит весь PENDING без изменений.");
            dialog.setTitle("Результат выборочного Merge — оставлено локально");
            if (dialog.open() != Window.OK) {
                return null;
            }
            Object[] chosen = dialog.getResult();
            if (chosen == null) {
                throw new IOException("EDT review selection returned no result");
            }
            var locallyKept = new LinkedHashSet<String>();
            for (Object item : chosen) {
                if (!(item instanceof String name)
                        || !presented.contains(name)) {
                    throw new IOException("Invalid native review identity");
                }
                locallyKept.add(name);
            }
            return Set.copyOf(locallyKept);
        });
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
        return confirmOnUi(shell, title, message, "Продолжить");
    }

    /** Concrete action names are safer than OK for a mutating Designer call. */
    private static boolean confirmOnUi(Shell shell, String title,
            String message, String action) throws Exception {
        return choiceOnUi(shell, title, message, action, "Отмена") == 0;
    }

    /** 0 = repeat read-only, 1 = confirmed fresh update, 2/-1 = cancel. */
    private static int choiceOnUi(Shell shell, String title,
            String message, String... buttons) throws Exception {
        return uiCall(shell, active -> new MessageDialog(active, title,
            null, message, MessageDialog.QUESTION, buttons, 0).open());
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
     * Location only, never the arbitrary exception message: native 1C/EDT
     * messages may contain private filesystem paths or credentials.
     */
    private static String ownFailureSite(Throwable failure) {
        for (Throwable current = failure; current != null;
                current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith(
                        "org.xander.edt.storagebridge.")
                        && frame.getLineNumber() > 0) {
                    String className = frame.getClassName();
                    int separator = className.lastIndexOf('.');
                    return className.substring(separator + 1) + "."
                        + frame.getMethodName() + ":"
                        + frame.getLineNumber();
                }
            }
        }
        return "код EDT/1С, подробности в журнале";
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
            // Include vetted own error reasons. Arbitrary Java/Designer
            // exception messages may contain paths or account credentials.
            String ownMessage = current.getMessage();
            if (ownMessage != null
                    && (ownMessage.equals("EDT source root is missing or unsafe")
                        || ownMessage.equals("Full EDT import has no primary metadata descriptors")
                        || ownMessage.equals("EDT project is not available for verification")
                        || ownMessage.equals("Invalid receive staging root"))) {
                diagnostic.append("; internalReason=").append(ownMessage);
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
        try (var current = new ConfigVersionSnapshotRunner().capture(
                request, monitor::isCanceled)) {
            if (monitor.isCanceled()) {
                throw new InterruptedException("Receive inventory check cancelled");
            }
            // Strict native snapshot identity, not a Java interpretation
            // of opaque 1C internal per-object version tokens.
            return Files.mismatch(staged, current.configDumpInfo()) == -1;
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
        if (failure instanceof ChangedObjectsProbe.NativeStageIncomplete stage) {
            String nativeIssue = stage.reportedChanges() == 0
                ? "1С вернула ПУСТОЙ список изменений, но инкрементальная "
                  + "XML-выгрузка не соответствует ожидаемому результату. "
                  + "По независимому полному снимку 1С отсутствие изменений "
                  + "не подтверждено; нельзя считать версию обработанной. "
                : "1С вернула " + stage.reportedChanges()
                  + " изменённых объектов, но их инкрементальная XML-выгрузка "
                  + "непригодна для EDT. "
                  + "Нужно проверить журнал платформы и отдельный "
                  + "выборочный транспорт -listFile. ";
            return nativeIssue
                + "ConfigDumpInfo.xml: "
                + (stage.manifestPresent() ? "есть" : "отсутствует")
                + "; XML-ресурсы: "
                + (stage.metadataPresent() ? "есть" : "отсутствуют")
                + ". Сравнение EDT не запускалось, REVIEWED не менялся. "
                + (stage.diagnosticsDirectory() == null
                    ? "Локальные диагностические файлы сохранить не удалось. "
                    : "Локальные диагностические файлы: "
                      + stage.diagnosticsDirectory()
                      + " (1c-log.txt, changes.txt, stdout.txt, stderr.txt). "
                      + "Перед передачей проверьте их на конфиденциальные данные. ")
                + "Полную выгрузку плагин автоматически не запускает.";
        }
        if (failure instanceof RepositoryUpdateRunner.UpdateFailure update) {
            return "Команда обновления конфигурации базы-посредника 1С "
                + "завершилась ошибкой. Состояние ИБ может быть изменено: "
                + "повторный запуск без восстановления состояния запрещён. "
                + (update.diagnosticsDirectory() == null
                    ? "Локальную диагностику сохранить не удалось."
                    : "Отчёты Конфигуратора сохранены локально: "
                      + update.diagnosticsDirectory()
                      + " (designer.log, stdout.txt, stderr.txt). "
                      + "Проверьте их на конфиденциальные данные перед передачей.");
        }
        if (failure instanceof SelectiveDumpRunner.DumpFailure dump) {
            return "Конфигуратор 1С не выполнил XML-выгрузку. "
                + "Код завершения: " + dump.exitCode()
                + ". Диагностика: " + dump.diagnosticsDirectory()
                + " (designer.txt, stdout.txt, objects.txt при выборочной выгрузке). "
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
                    : "Можно повторить операцию через «Получить полную конфигурацию из хранилища».");
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
                    || reason.equals("Config version snapshot timed out")
                    || reason.equals("Config version snapshot cancelled")
                    || reason.equals("Version-only command produced unexpected configuration files")
                    || reason.equals("ConfigDumpInfo.xml missing or too large")
                    || reason.equals("Unknown ConfigDumpInfo.xml schema")
                    || reason.equals("Cannot parse ConfigDumpInfo version list")
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
