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
        // Native 1C -getChanges also PRODUCES the incremental XML payload.
        // Ownership transfers to export only when that patch reaches EDT;
        // otherwise the temporary native source is closed in finally.
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
                pendingChoice = choiceOnUi(shell, "Как получить изменения?",
                    pendingDescription,
                    "Обновить и сравнить", "Повторно сравнить старое", "Отмена");
                if (pendingChoice != 0 && pendingChoice != 1) {
                    return;
                }
                if (pendingChoice == 1
                        && latestPendingRevision > stagedPendingRevision
                        && stagedPendingRevision > 0
                        && !confirmOnUi(shell, "Сравнение старой версии",
                            "Будет повторно открыт только сохранённый снимок "
                            + stagedPendingRevision + ". Новая версия "
                            + latestPendingRevision + " НЕ будет получена в EDT. "
                            + "Продолжить именно старое сравнение?",
                            "Сравнить старое")) {
                    return;
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
                            ? "Полная сверка с хранилищем"
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
            final long revisionBeforeUpdate =
                !retryPendingOnly && !recovery
                    ? readLatestRevisionOrUnknown(
                        executable, transport, settings, jobProgress::isCanceled)
                    : ReceiveRevisionStore.UNKNOWN;

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
                    if (lastScanned == null) {
                        // No proved baseline: compare the entire configuration,
                        // but do not put EVERY metadata identity into pending.
                        // Reviewed HEAD requires the finished EDT merge and
                        // explicit developer confirmation, not a fake delta.
                        discovered = RemoteReceiveScopePlanner.fullInventory(
                            null, remote.configDumpInfo());
                    } else {
                        // Even when the user requested a FULL comparison,
                        // discover actual changed identities using native 1C.
                        // Full review widens only the EDT comparison scope,
                        // never the persisted pending identity list.
                        phase.set("Штатное определение изменений платформой 1С");
                        monitor.subTask("Конфигуратор 1С формирует список изменений");
                        // Prefer the platform's one-pass XML patch. If 1C
                        // produced an authoritative nonempty changes.txt but
                        // the patch itself is unusable, retain those names and
                        // use the separately documented -listFile transport.
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
            if (!retryPendingOnly && !recovery
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
                    + "«Полную сверку с хранилищем».");
                return;
            }
            // A platform patch may contain only separately developed
            // resources, not an entire metadata owner. We pass it directly
            // to EDT's native importer and NEVER manufacture a -listFile.
            //
            // An earlier PENDING review can be replayed with the native
            // -listFile transport over EXACT known 1C names. It does not
            // require a 23k-object full XML dump. Deletions, unknown types
            // and truly unproven recovery still require a separately
            // approved full review.
            boolean requiresFull = fullReview
                || recovery || changeScopeRequiresFull.get()
                || !absentFromRemote.isEmpty();
            if (requiresFull && !fullReview) {
                boolean accept = confirmOnUi(shell,
                    "Требуется полное сравнение EDT",
                    "Штатная инкрементальная выгрузка не покрывает "
                    + "весь состав ожидающих изменений.\n"
                    + "Осталось изменений от предыдущих получений: "
                    + (hadUnresolvedBefore ? "да" : "нет") + ".\n"
                    + "Удалённых или отсутствующих в источнике записей: "
                    + absentFromRemote.size() + ".\n\n"
                    + "Полная выгрузка будет сделана только для "
                    + "подготовки сравнения EDT. База-посредник второй "
                    + "раз из хранилища не обновляется.\n"
                    + "Отмена сохранит все изменения в журнале.",
                    "Полная сверка");
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
                var ready = nativePatch.get();
                if (ready != null && !hadUnresolvedBefore && !retryPendingOnly) {
                    // Fast path: retain the exact native one-pass XML patch.
                    export = new SelectiveDumpRunner.ExportResult(
                        ready.workingRoot(), ready.xmlDirectory());
                    nativePatch.set(null);
                } else if (nativePatchFailure.get() != null
                        || retryPendingOnly || hadUnresolvedBefore) {
                    // Fallback and retained PENDING: use the documented
                    // -listFile transport for the union of exactly known
                    // current/previous native changed identities.
                    phase.set("Штатная выборочная выгрузка 1С по списку изменений");
                    // The existing native listFile planner expands changed
                    // owners with their independently developed descendants
                    // from the CURRENT complete ConfigDumpInfo inventory.
                    // Unsupported Configuration/Language roots are NOT
                    // silently discarded or marked reviewed.
                    // The full 1C inventory also includes embedded and
                    // independently developed resources without their own
                    // configVersion token. Do not accidentally omit those
                    // related Form/Template/Module transport units.
                    var listPlan = SelectiveDumpPlanner.planIncludingRelated(
                        selectedList, allCurrentMetadata);
                    if (!listPlan.deferred().isEmpty()) {
                        throw new IOException(
                            "Выборочная выгрузка содержит "
                            + listPlan.deferred().size()
                            + " изменений корневой конфигурации/языков, "
                            + "которые EDT не может импортировать отдельно. "
                            + "Они сохранены в PENDING; требуется "
                            + "явно подтверждённая полная сверка.");
                    }
                    var transportNames = listPlan.exportable();
                    var unusedPatch = nativePatch.getAndSet(null);
                    if (unusedPatch != null) {
                        unusedPatch.close();
                    }
                    var selective = new SelectiveDumpRequest(
                        executable, transport, transportNames,
                        Duration.ofMinutes(30),
                        settings.infobaseUsername(), settings.infobasePassword());
                    export = progress(jobProgress, 20, monitor ->
                        new SelectiveDumpRunner().export(
                            selective, monitor::isCanceled));
                } else {
                    throw new IOException("Native 1C incremental XML was lost");
                }
            }
            // Converted EDT files, like native 1C XML, stay OUTSIDE Git.
            phase.set("Создание временных файлов сравнения EDT");
            conversionRoot = Files.createTempDirectory("edt-storage-edt-delta-");
            Path converted = Files.createDirectory(conversionRoot.resolve("edt"));
            // The native patch may contain an external property without
            // its owner descriptor. Let EDT's OWN importer decide if the
            // patch is usable. Only if EDT refuses it, offer a separately
            // confirmed full source. Never re-run -listFile or invent XML.
            final AtomicReference<SelectiveDumpRunner.ExportResult> stagedExport =
                new AtomicReference<>(export);
            final AtomicReference<Path> stagedConversion =
                new AtomicReference<>(conversionRoot);
            final AtomicBoolean effectiveFull =
                new AtomicBoolean(fullNativeComparison);
            GitReceiveGuard.requireUnchanged(project, git);

            phase.set("Конвертация объектов и подготовка сравнения EDT");
            var nativeDescriptor = progress(jobProgress, 25, monitor -> {
                monitor.beginTask("Подготовка штатного сравнения EDT", 2);
                try {
                    var descriptor = EdtPublicServiceBridge.prepare(
                        project, stagedExport.get().xmlDirectory(), converted,
                        selectedList, effectiveFull.get(), monitor);
                    monitor.worked(2);
                    return descriptor;
                } catch (EdtSelectiveReviewService.NativeEdtImportFailure
                        | EdtSelectiveReviewService.MissingConvertedOwnersException
                        | EdtSelectiveReviewService.MissingEmbeddedLanguagesException ex) {
                    if (effectiveFull.get()) {
                        throw ex;
                    }
                    boolean useFull = confirmOnUi(shell,
                        "EDT не смогла открыть выборочную выгрузку 1С",
                        "Конфигуратор 1С сформировал изменения штатно, "
                        + "но EDT не смогла преобразовать их в отдельный "
                        + "источник для сравнения. Принудительно дополнять "
                        + "XML или создавать собственный список объектов "
                        + "плагин не будет.\n\n"
                        + "Можно выгрузить конфигурацию полностью и "
                        + "открыть штатное сравнение EDT. "
                        + "База-посредник из хранилища повторно "
                        + "не обновляется. Операция может занять долгое время.",
                        "Полная сверка");
                    if (!useFull) {
                        // User declined the expensive fallback. This is a
                        // cancellation, not a corrupted project or a failed
                        // repository update. Pending changes remain intact.
                        throw new InterruptedException(
                            "Полная сверка отклонена пользователем");
                    }
                    // No EDT editor has opened; old converted data and
                    // the platform patch can be safely released here.
                    cleanupQuietly(stagedExport.get(), stagedConversion.get());
                    stagedExport.set(null);
                    stagedConversion.set(null);
                    try {
                        phase.set("Полная выгрузка после отказа импортёра EDT");
                        var fullSource = new SelectiveDumpRunner()
                            .exportInitialFull(
                                new InitialFullDumpRequest(executable, transport,
                                    Duration.ofHours(2),
                                    settings.infobaseUsername(),
                                    settings.infobasePassword()),
                                monitor::isCanceled);
                        stagedExport.set(fullSource);
                        var tempRoot = Files.createTempDirectory(
                            "edt-storage-edt-global-");
                        stagedConversion.set(tempRoot);
                        Path fullTarget = Files.createDirectory(
                            tempRoot.resolve("edt"));
                        effectiveFull.set(true);
                        var descriptor = EdtPublicServiceBridge.prepare(
                            project, fullSource.xmlDirectory(), fullTarget,
                            List.of(), true, monitor);
                        monitor.worked(2);
                        return descriptor;
                    } catch (Exception failure) {
                        cleanupQuietly(stagedExport.get(), stagedConversion.get());
                        stagedExport.set(null);
                        stagedConversion.set(null);
                        throw failure;
                    }
                }
            });
            // Persist ownership before any later work can fail. On a failed
            // fallback, references are cleared; otherwise the final stage
            // is retained until EDT's native merge reaches a terminal state.
            export = stagedExport.get();
            conversionRoot = stagedConversion.get();
            final Set<String> actualSelected = effectiveFull.get()
                ? Set.of() : selected;
            GitReceiveGuard.requireUnchanged(project, git);

            // Selection in this dialog means "show in comparison", NOT
            // "safe to accept remotely". Native EDT two-way comparison
            // automatically prechecks differences for taking the other side.
            // Force a separate, explicit acknowledgement before opening it.
            if (!confirmOnUi(shell,
                    "Проверка перед объединением EDT",
                    (effectiveFull.get()
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
                                            boolean allowReview = false;
                                            boolean inventoryOnly = false;
                                            if (revisionForReview.get() > 0) {
                                                finishStage.set(
                                                    "проверка версии хранилища после Merge");
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
                                                finishStage.set(
                                                    "повторный снимок базы-посредника после Merge");
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
                                                if (pendingNow.isEmpty() && effectiveFull.get()
                                                        && !inventoryOnly) {
                                                    // There is no object-level native delta
                                                    // awaiting acceptance. Native EDT has
                                                    // already finished a GLOBAL merge.
                                                    // Verify that the full native inventory
                                                    // made it into the EDT source, but DO NOT
                                                    // compare 6000+ owner byte trees and infer
                                                    // rejected changes from .mdo/.distr bytes.
                                                    finishStage.set(
                                                        "проверка полноты источника EDT");
                                                    Set<String> nativeNames =
                                                        ConfigDumpInfoIndex.allMetadataNames(
                                                            journal.lastScannedInfo());
                                                    Set<String> importedOwners =
                                                        EdtSelectiveReviewService
                                                            .primaryDescriptors(
                                                                retainedConvertedEdt).keySet();
                                                    boolean complete =
                                                        EdtSelectiveReviewService
                                                            .fullImportCoversNativeInventory(
                                                                nativeNames,
                                                                unindexedNativeNames.get(),
                                                                importedOwners);
                                                    if (!complete) {
                                                        reason = "Штатный импорт EDT не "
                                                            + "представил все объекты метаданных "
                                                            + "из полного снимка 1С. Версия "
                                                            + "не отмечена; нужна диагностика "
                                                            + "импорта, а не повторное "
                                                            + "побайтовое сравнение.";
                                                    } else {
                                                        finishStage.set(
                                                            "последняя проверка HEAD 1С");
                                                        long finalHead =
                                                            readLatestRevisionOrUnknown(
                                                                executable, transport, settings,
                                                                Duration.ofSeconds(30),
                                                                monitor::isCanceled);
                                                        if (finalHead != revisionForReview.get()) {
                                                            if (finalHead > 0) {
                                                                ReceiveRevisionStore.recordObserved(
                                                                    project, settings.address(),
                                                                    intermediaryIdentity,
                                                                    finalHead);
                                                            }
                                                            reason = "Версия хранилища "
                                                                + "изменилась или недоступна "
                                                                + "при итоговой проверке. "
                                                                + "Результат EDT сохранён, "
                                                                + "но отметка REVIEWED "
                                                                + "не изменена.";
                                                        } else {
                                                            finishStage.set(
                                                                "запись рассмотренной версии");
                                                            GitReceiveGuard
                                                                .requireSameBranchAndHead(
                                                                    project, postMergeGit);
                                                            ReceiveCompletionRecorder
                                                                .reviewedGlobalNativeMerge(
                                                                    project, settings.address(),
                                                                    intermediaryIdentity, branch,
                                                                    finalHead, journal,
                                                                    initialFullReview);
                                                            confirmedRevision = finalHead;
                                                            reason = "Штатное полное объединение "
                                                                + "EDT завершено. Все входящие "
                                                                + "изменения рассмотрены; "
                                                                + "REVIEWED = " + finalHead
                                                                + ". Побайтовое совпадение "
                                                                + "конфигураций не проверялось "
                                                                + "и APPLIED не изменялся.";
                                                        }
                                                    }
                                                } else if (!effectiveFull.get()
                                                        && !inventoryOnly) {
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
                                                        reason = "Часть ожидающих изменений "
                                                            + "не вошла в открытое выборочное "
                                                            + "сравнение EDT ("
                                                            + notPresented.size()
                                                            + "). Версия не отмечена. "
                                                            + "Для остальных объектов можно "
                                                            + "выполнить отдельное выборочное "
                                                            + "сравнение.";
                                                    } else {
                                                        boolean accepted = confirmOnUi(shell,
                                                            "Завершить выборочное получение 1С",
                                                            "EDT подтвердила завершение "
                                                            + "штатного Merge. Были показаны "
                                                            + pendingNow.size()
                                                            + " ожидающих изменений 1С.\n\n"
                                                            + "Подтвердите, что вы рассмотрели "
                                                            + "ВСЕ предъявленные изменения. "
                                                            + "Оставлять свои локальные варианты "
                                                            + "можно: версия будет отмечена "
                                                            + "РАССМОТРЕННОЙ, не полностью "
                                                            + "применённой.\n\n"
                                                            + "Плагин не определяет выбор "
                                                            + "отдельных галочек по различию "
                                                            + "байтов и не будет выдумывать "
                                                            + "список KEPT_LOCAL.");
                                                        if (accepted) {
                                                            finishStage.set(
                                                                "последняя проверка HEAD 1С");
                                                            long finalHead =
                                                                readLatestRevisionOrUnknown(
                                                                    executable, transport,
                                                                    settings,
                                                                    Duration.ofSeconds(30),
                                                                    monitor::isCanceled);
                                                            if (finalHead !=
                                                                    revisionForReview.get()) {
                                                                if (finalHead > 0) {
                                                                    ReceiveRevisionStore.recordObserved(
                                                                        project,
                                                                        settings.address(),
                                                                        intermediaryIdentity,
                                                                        finalHead);
                                                                }
                                                                reason = "Версия хранилища "
                                                                    + "изменилась или недоступна "
                                                                    + "после Merge. REVIEWED "
                                                                    + "не изменена.";
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
                                                                            pendingNow);
                                                                confirmedRevision = finalHead;
                                                                reason = "Выборочное "
                                                                    + "объединение завершено; "
                                                                    + "REVIEWED = " + finalHead
                                                                    + ". Ожидающих изменений: 0. "
                                                                    + "APPLIED не менялся.";
                                                            }
                                                        } else {
                                                            reason = "Подтверждение выборочного "
                                                                + "объединения отменено; "
                                                                + "PENDING сохранён.";
                                                        }
                                                    }
                                                } else {
                                                // A finished native EDT MERGE is
                                                // NOT proof that each checkbox
                                                // was selected. Never erase a
                                                // committed local change merely
                                                // because its owner was in the
                                                // comparison scope. Audit the
                                                // resulting EDT files against
                                                // the native imported source.
                                                finishStage.set(
                                                    "проверка файлов после Merge EDT");
                                                Path stageSrc = retainedConvertedEdt.resolve("src");
                                                Path projectSrc = project.getLocation()
                                                    .toFile().toPath().resolve("src");
                                                BridgeActivator.getDefault().getLog().info(
                                                    "Post-merge audit roots: stageSrcIsDirectory="
                                                    + Files.isDirectory(stageSrc)
                                                    + "; stageSrcIsLink="
                                                    + Files.isSymbolicLink(stageSrc)
                                                    + "; projectSrcIsDirectory="
                                                    + Files.isDirectory(projectSrc)
                                                    + "; projectSrcIsLink="
                                                    + Files.isSymbolicLink(projectSrc));
                                                Set<String> fullyImportedOwners =
                                                    effectiveFull.get()
                                                        ? EdtSelectiveReviewService
                                                            .primaryDescriptors(
                                                                retainedConvertedEdt).keySet()
                                                        : Set.of();
                                                // No pending journal mutation may happen
                                                // until Git's branch and HEAD have remained
                                                // stable for this specific post-merge audit.
                                                GitReceiveGuard.requireSameBranchAndHead(
                                                    project, postMergeGit);
                                                var verified = VerifiedReceiveContent
                                                    .inspect(
                                                        project,
                                                        settings.address(),
                                                        branch,
                                                        intermediaryIdentity,
                                                        actualSelected,
                                                        selectedPendingNames,
                                                        absentFromRemote,
                                                        fullyImportedOwners,
                                                        retainedConvertedEdt,
                                                        retainedExport.xmlDirectory());
                                                // This is only a candidate count; inspect
                                                // must not clear any pending objects yet.
                                                boolean allSourceVerified =
                                                    verified.remainingPending() == 0
                                                    && verified.identicalOwners()
                                                        == verified.checkedOwners();
                                                // Decide once, from audited source content
                                                // and exact pending identities. The whole
                                                // configuration inventory is not a delta.
                                                GitReceiveGuard.requireSameBranchAndHead(
                                                    project, postMergeGit);
                                                Set<String> remainingAfterAudit =
                                                    new java.util.LinkedHashSet<>(
                                                        ReceivePendingStore.get(
                                                            project, settings.address(),
                                                            branch, intermediaryIdentity));
                                                remainingAfterAudit.removeAll(
                                                    verified.verifiedNames());
                                                Set<String> presented = new java.util.LinkedHashSet<>(
                                                    selectedPendingNames);
                                                if (effectiveFull.get()) {
                                                    presented.addAll(absentFromRemote);
                                                }
                                                // A global full EDT audit can take minutes.
                                                // Do not rely solely on a HEAD read from
                                                // BEFORE that audit: 1C may have advanced
                                                // again while the project's files were read.
                                                boolean finalHeadProven = !inventoryOnly;
                                                if (finalHeadProven) {
                                                    finishStage.set(
                                                        "последняя проверка HEAD перед записью");
                                                    long finalHead = readLatestRevisionOrUnknown(
                                                        executable, transport, settings,
                                                        Duration.ofSeconds(30),
                                                        monitor::isCanceled);
                                                    if (finalHead > 0
                                                            && finalHead != revisionForReview.get()) {
                                                        ReceiveRevisionStore.recordObserved(
                                                            project, settings.address(),
                                                            intermediaryIdentity, finalHead);
                                                    }
                                                    if (finalHead != revisionForReview.get()) {
                                                        finalHeadProven = false;
                                                        reason = finalHead > 0
                                                            ? "Хранилище обновилось до версии "
                                                                + finalHead
                                                                + " во время проверки EDT. "
                                                                + "Прежний снимок не объявляется "
                                                                + "актуальным. Новая версия "
                                                                + "требует отдельного получения."
                                                            : "Не удалось повторно проверить HEAD "
                                                                + "перед записью результата. "
                                                                + "Версия не фиксируется.";
                                                    }
                                                }
                                                // The incremental index intentionally omits
                                                // Metadata without configVersion (e.g. object
                                                // attributes). A GLOBAL APPLIED proof must
                                                // include those names and verify their EDT
                                                // owner, otherwise an unversioned object could
                                                // disappear without being counted.
                                                Set<String> allNativeMetadata =
                                                    effectiveFull.get()
                                                        ? ConfigDumpInfoIndex.allMetadataNames(
                                                            journal.lastScannedInfo())
                                                        : currentInventory;
                                                boolean fullSourceInventoryCovered =
                                                    !effectiveFull.get()
                                                    || EdtSelectiveReviewService
                                                        .fullImportCoversNativeInventory(
                                                            allNativeMetadata,
                                                            unindexedNativeNames.get(),
                                                            fullyImportedOwners);
                                                boolean entireProjectAudited =
                                                    effectiveFull.get()
                                                        && fullSourceInventoryCovered
                                                        && verified.checkedOwners() > 0;
                                                var completion = ReceiveCompletionPolicy.decide(
                                                    mergeConfirmed, finalHeadProven,
                                                    entireProjectAudited,
                                                    fullSourceInventoryCovered,
                                                    allSourceVerified,
                                                    verified.checkedOwners(),
                                                    remainingAfterAudit, presented);
                                                finishStage.set(
                                                    "фиксация результата проверки EDT");
                                                switch (completion) {
                                                    case REVIEWED_EXACT_SCOPE -> {
                                                        GitReceiveGuard.requireSameBranchAndHead(
                                                            project, postMergeGit);
                                                        acknowledgedCount =
                                                            ReceiveCompletionRecorder.reviewedExactScope(
                                                                project, settings.address(),
                                                                intermediaryIdentity, branch,
                                                                revisionForReview.get(),
                                                                verified.verifiedNames());
                                                        confirmedRevision =
                                                            revisionForReview.get();
                                                        reason = "Все изменения в выборочном "
                                                            + "сравнении применены, версия "
                                                            + confirmedRevision + " рассмотрена. "
                                                            + "Полное совпадение проекта "
                                                            + "не установлено.";
                                                    }
                                                    case FULLY_APPLIED -> {
                                                        GitReceiveGuard.requireSameBranchAndHead(
                                                            project, postMergeGit);
                                                        acknowledgedCount =
                                                            ReceiveCompletionRecorder.fullyApplied(
                                                                project, settings.address(),
                                                                intermediaryIdentity, branch,
                                                                revisionForReview.get(),
                                                                verified.verifiedNames(),
                                                                journal, initialFullReview);
                                                        confirmedRevision =
                                                            revisionForReview.get();
                                                        reason = "Версия " + confirmedRevision
                                                            + " полностью применена и сверена.";
                                                    }
                                                    case REQUIRE_USER_CONFIRMATION -> {
                                                        boolean accepted = confirmOnUi(shell,
                                                            "Подтвердить результат объединения EDT",
                                                            "Объединение завершено, но итоговый "
                                                            + "проект не совпадает с хранилищем. "
                                                            + "Причиной могут быть намеренно "
                                                            + "сохранённые локальные доработки.\n\n"
                                                            + "Все изменения версии "
                                                            + revisionForReview.get()
                                                            + " действительно рассмотрены? "
                                                            + "Подтверждайте только если вы "
                                                            + "завершили выбор в EDT.\n\n"
                                                            + "Версия будет помечена как "
                                                            + "РАССМОТРЕННАЯ, а не полностью "
                                                            + "применённая.");
                                                        if (accepted) {
                                                            GitReceiveGuard.requireSameBranchAndHead(
                                                                project, postMergeGit);
                                                            acknowledgedCount =
                                                                ReceiveCompletionRecorder
                                                                    .reviewedWithLocalDifferences(
                                                                        project, settings.address(),
                                                                        intermediaryIdentity, branch,
                                                                        revisionForReview.get(),
                                                                        verified.verifiedNames(),
                                                                        remainingAfterAudit,
                                                                        presented, journal,
                                                                        initialFullReview);
                                                            confirmedRevision =
                                                                revisionForReview.get();
                                                            reason = "Версия "
                                                                + confirmedRevision
                                                                + " рассмотрена. Локальные "
                                                                + "отличия сохранены.";
                                                        } else {
                                                            reason = "Разбор изменений не "
                                                                + "подтверждён. Версия не отмечена.";
                                                        }
                                                    }
                                                    case PARTIAL -> {
                                                        GitReceiveGuard.requireSameBranchAndHead(
                                                            project, postMergeGit);
                                                        acknowledgedCount =
                                                            ReceiveCompletionRecorder.partial(
                                                                project, settings.address(),
                                                                intermediaryIdentity, branch,
                                                                verified.verifiedNames());
                                                        reason = "Часть изменений отсутствовала "
                                                            + "в открытом сравнении EDT. "
                                                            + "Подтверждены только точно "
                                                            + "совпавшие объекты; версия "
                                                            + "не отмечена.";
                                                    }
                                                    case UNPROVEN_SOURCE -> {
                                                        // NEVER remove pending identities
                                                        // when the staged 1C source may be
                                                        // behind a newer native repository HEAD.
                                                        // Content equality with a stale source
                                                        // is not proof that remote changes
                                                        // were accepted.
                                                        acknowledgedCount = 0;
                                                        if (reason == null) {
                                                            reason = "Связь исходного снимка "
                                                                + "с текущей версией 1С "
                                                                + "не доказана. Журнал ожидающих "
                                                                + "изменений оставлен без правок.";
                                                        }
                                                    }
                                                    case SOURCE_INCOMPLETE -> {
                                                        // Full XML source was not fully
                                                        // materialized by the EDT importer.
                                                        // Never let a partial native source
                                                        // certify the repository HEAD.
                                                        reason = "Полная выгрузка 1С "
                                                            + "не полностью представлена "
                                                            + "объектами временного EDT-проекта. "
                                                            + "Версия не отмечена, журнал "
                                                            + "ожидающих изменений сохранён.";
                                                    }
                                                    case AUDIT_INCOMPLETE -> {
                                                        // Zero imported owners is never
                                                        // evidence of successful comparison.
                                                        reason = "Проверка файлов EDT не нашла "
                                                            + "ни одного объекта метаданных. "
                                                            + "Версия не отмечена, журнал "
                                                            + "ожидающих изменений сохранён.";
                                                    }
                                                    case NOT_MERGED -> {
                                                        reason = "EDT не подтвердила Merge. "
                                                            + "Ничего не отмечено.";
                                                    }
                                                }
                                                if (!allSourceVerified) {
                                                    if (reason == null) {
                                                        reason = "EDT выполнила Merge, "
                                                            + "но часть объектов не совпадает "
                                                            + "с полученной конфигурацией 1С.";
                                                    }
                                                    if (verified.diagnosticFile() != null) {
                                                        reason += "\nПодробности в локальном отчёте: "
                                                            + verified.diagnosticFile();
                                                    }
                                                }
                                                BridgeActivator.getDefault().getLog().info(
                                                    "Native EDT merge audited; "
                                                    + "contentVerified="
                                                    + verified.confirmedPending()
                                                    + "; remaining="
                                                    + verified.remainingPending()
                                                    + "; fullyMatchingOwners="
                                                    + verified.identicalOwners()
                                                    + "/" + verified.checkedOwners()
                                                    + "; headAttested="
                                                    + !inventoryOnly);
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
                                        StringBuilder report = new StringBuilder(
                                            "Штатное объединение EDT: ")
                                            .append(nativeFinished
                                                ? "завершено."
                                                : "не подтверждено.")
                                            .append("\nПодтверждено записей учёта: ")
                                            .append(acknowledgedCount)
                                            .append(".\nОжидают проверки: ")
                                            .append(pendingAfter).append(".")
                                            .append("\nСознательно сохранены локальные версии объектов: ")
                                            .append(retainedLocally.size()).append(".");
                                        if (effectiveFull.get()) {
                                            report.append("\nПолная сверка: записи учёта "
                                                + "могут охватывать весь каталог метаданных. "
                                                + "Это не число НОВЫХ изменений.");
                                        }
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
            return "Штатная 1С сообщила об изменённых объектах ("
                + stage.reportedChanges() + "), но не сформировала "
                + "полноценную выборочную XML-выгрузку. "
                + "ConfigDumpInfo.xml в выгрузке: "
                + (stage.manifestPresent() ? "есть" : "отсутствует")
                + ". Сравнение EDT не запускалось, версия не записана. "
                + (stage.diagnosticsDirectory() == null
                    ? "Локальные диагностические файлы сохранить не удалось. "
                    : "Локальные диагностические файлы: "
                      + stage.diagnosticsDirectory()
                      + " (1c-log.txt, changes.txt, stdout.txt, stderr.txt). "
                      + "Перед передачей проверьте их на конфиденциальные данные. ")
                + "Полную выгрузку плагин автоматически не запускает.";
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
