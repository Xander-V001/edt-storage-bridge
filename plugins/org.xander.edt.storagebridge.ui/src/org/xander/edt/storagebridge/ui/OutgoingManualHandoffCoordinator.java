package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.xander.edt.storagebridge.core.OutgoingWholeObjectWritePlan;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.DesignerInfobaseAuth;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * Manual outgoing mode: prepare a complete candidate CF by applying qualified
 * EDT XML ONLY to a disposable 1C file infobase. The original intermediary
 * is read with DumpCfg, never changed by this coordinator. Native Lock,
 * Load-to-intermediary and RepositoryCommit are deliberately absent here.
 *
 * The developer reviews candidate.cf using Designer's interactive
 * "Compare, merge with configuration from file" command and does the
 * subsequent repository placement personally. A manual placement is NEVER
 * recorded as a successful automatic publish.
 */
public final class OutgoingManualHandoffCoordinator {
    private static final String PLUGIN = BridgeActivator.PLUGIN_ID;
    private static final Duration TIMEOUT = Duration.ofHours(2);
    private static final long LOG_LIMIT = 1024 * 1024;

    private OutgoingManualHandoffCoordinator() {}

    /** Invoked from export preview, on SWT thread, only when mode is manual. */
    public static void prepare(Shell shell, IProject project,
            StorageProjectSettings.Snapshot settings,
            GitReceiveGuard.Baseline reviewedGit, long reviewedRevision,
            OutgoingWholeObjectWritePlan approvedPlan, Path exportedXmlRoot) {
        Objects.requireNonNull(shell);
        Objects.requireNonNull(project);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(reviewedGit);
        Objects.requireNonNull(approvedPlan);
        Objects.requireNonNull(exportedXmlRoot);
        if (shell.isDisposed()) return;
        if (!settings.manualOutgoing()) {
            MessageDialog.openWarning(shell, "Ручное помещение",
                "Ручной режим выключен в настройках проекта.");
            return;
        }

        final AutoCloseable lease;
        try {
            lease = ReceiveChangesCoordinator.acquireOutgoingReview(project, settings);
        } catch (IOException ex) {
            MessageDialog.openWarning(shell, "Подготовка ручного помещения",
                "Для этого EDT-проекта или базы-посредника уже выполняется операция.");
            return;
        }
        Display display = shell.getDisplay();
        Job job = new Job("1С: Подготовка .cf для ручного помещения — "
                + project.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                Path root = null;
                String stage = "Проверка источника";
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var kind = settings.infobaseType()
                        == StorageProjectSettings.InfobaseType.FILE
                            ? InfobaseLocation.Kind.FILE
                            : InfobaseLocation.Kind.SERVER;
                    Path exe = Path.of(settings.platformExecutable()).toAbsolutePath();
                    if (!Files.isRegularFile(exe, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("1C executable missing");
                    }
                    var infobase = new InfobaseLocation(kind,
                        settings.infobaseAddress());
                    String source = settings.infobaseType().name() + ":"
                        + settings.infobaseAddress().trim();
                    var journal = new ReceiveBaselineJournal(
                        project, settings.address(), reviewedGit.branch(), source);
                    if (ReceiveRevisionStore.reviewed(project, settings.address(),
                            source, reviewedGit.branch()) != reviewedRevision
                            || !ReceivePendingStore.get(project, settings.address(),
                                reviewedGit.branch(), source).isEmpty()
                            || journal.scannedRevision() != reviewedRevision
                            || journal.lastScannedInfo() == null) {
                        throw new IOException("Incoming repository revision not reviewed");
                    }
                    var expectedInventory = ConfigDumpInfoIndex.readVersions(
                        journal.lastScannedInfo());
                    var probe = new ChangeDetectionRequest(exe, infobase,
                        Duration.ofMinutes(30), settings.infobaseUsername(),
                        settings.infobasePassword());
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(settings, exe, infobase, reviewedRevision, monitor);
                    requireInventory(probe, expectedInventory, monitor);
                    approvedPlan.verifyFiles(exportedXmlRoot);

                    root = BridgeActivator.getDefault().getStateLocation()
                        .toFile().toPath().resolve("outgoing-manual-handoff")
                        .resolve(UUID.randomUUID().toString());
                    Files.createDirectories(root);
                    Path baselineCf = root.resolve("baseline.cf");
                    Path candidateCf = root.resolve("candidate.cf");
                    Path scratch = root.resolve("temporary-infobase");
                    Path loadList = root.resolve("load-list.txt");
                    approvedPlan.writeScopes(root.resolve("scope-lock-not-used.xml"),
                        root.resolve("scope-commit-not-used.xml"), loadList);
                    Files.deleteIfExists(root.resolve("scope-lock-not-used.xml"));
                    Files.deleteIfExists(root.resolve("scope-commit-not-used.xml"));
                    if (!Files.isRegularFile(loadList, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Qualified native load scope missing");
                    }

                    // DumpCfg is read-only for the ORIGINAL intermediary.
                    // No LoadCfg/LoadConfigFromFiles ever targets that infobase.
                    stage = "Сохранение конфигурации посредника в .cf";
                    monitor.subTask(stage);
                    runNative(args(exe, "DESIGNER", infobase,
                        settings.infobaseUsername(), settings.infobasePassword(),
                        "/DumpCfg", baselineCf.toString()),
                        root, "baseline-dump", settings, monitor);
                    requireFile(baselineCf);

                    stage = "Создание временной базы из исходной конфигурации";
                    monitor.subTask(stage);
                    Files.createDirectory(scratch);
                    runNative(List.of(exe.toString(), "CREATEINFOBASE",
                        "File=\"" + scratch.toString() + "\"",
                        "/UseTemplate", baselineCf.toString()),
                        root, "create-scratch", settings, monitor);
                    if (!Files.isRegularFile(scratch.resolve("1Cv8.1CD"),
                            LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Temporary file infobase not created");
                    }
                    var scratchIb = new InfobaseLocation(
                        InfobaseLocation.Kind.FILE, scratch.toString());

                    stage = "Загрузка XML EDT ТОЛЬКО во временную базу";
                    monitor.subTask(stage);
                    runNative(args(exe, "DESIGNER", scratchIb,
                        "", "", "/LoadConfigFromFiles",
                        exportedXmlRoot.toString(), "-Format", "Hierarchical",
                        "-listFile", loadList.toString()),
                        root, "load-scratch", settings, monitor);

                    stage = "Формирование candidate.cf";
                    monitor.subTask(stage);
                    runNative(args(exe, "DESIGNER", scratchIb,
                        "", "", "/DumpCfg", candidateCf.toString()),
                        root, "dump-candidate", settings, monitor);
                    requireFile(candidateCf);

                    stage = "Повторная проверка Git, версии и посредника";
                    GitReceiveGuard.requireUnchanged(project, reviewedGit);
                    requireHead(settings, exe, infobase, reviewedRevision, monitor);
                    requireInventory(probe, expectedInventory, monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    // No action here asserts the candidate was merged, nor
                    // advances REVIEWED/APPLIED or an outgoing checkpoint.
                    Files.writeString(root.resolve("README.txt"),
                        "РУЧНОЕ ПОМЕЩЕНИЕ В ХРАНИЛИЩЕ\n"
                        + "candidate.cf: подготовленная конфигурация для "
                        + "сравнения/объединения вручную.\n"
                        + "Версия источника: " + reviewedRevision + "\n"
                        + "Git HEAD: " + reviewedGit.head() + "\n"
                        + "Конфигуратор → Конфигурация → Сравнить, объединить "
                        + "с конфигурацией из файла → candidate.cf.\n"
                        + "После выбора изменений и ручного помещения "
                        + "в хранилище проверьте его версию отдельно.\n"
                        + "Плагин НЕ выполнял Lock, Load или Commit "
                        + "в рабочей базе-посреднике.\n",
                        StandardCharsets.UTF_8);

                    final Path ready = candidateCf;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        boolean open = MessageDialog.openQuestion(shell,
                            "Конфигурация для ручного помещения готова",
                            "Подготовлен файл:\n" + ready + "\n\n"
                            + "Открыть штатный Конфигуратор 1С для "
                            + "РУЧНОГО сравнения и объединения?\n\n"
                            + "В Конфигураторе выбери «Конфигурация → "
                            + "Сравнить, объединить с конфигурацией из файла», "
                            + "укажи candidate.cf, самостоятельно выбери "
                            + "изменения и помести их в хранилище. "
                            + "Плагин НЕ выполнит Lock/Load/Commit.");
                        if (open) {
                            try {
                                startInteractiveDesigner(settings, exe, infobase);
                            } catch (IOException ex) {
                                MessageDialog.openWarning(shell,
                                    "Не удалось открыть Конфигуратор",
                                    "candidate.cf уже подготовлен:\n" + ready
                                    + "\nОткрой Конфигуратор самостоятельно.");
                                Platform.getLog(OutgoingManualHandoffCoordinator.class)
                                    .log(new Status(IStatus.ERROR, PLUGIN,
                                        "Manual Designer GUI launch failed", ex));
                            }
                        }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    Platform.getLog(OutgoingManualHandoffCoordinator.class)
                        .log(new Status(IStatus.ERROR, PLUGIN,
                            "Manual candidate preparation failed at " + stage, ex));
                    Path failureFolder = root;
                    String failedAt = stage;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!shell.isDisposed()) MessageDialog.openWarning(shell,
                            "Ручное помещение не подготовлено",
                            "Ошибка на этапе: " + failedAt + ".\n"
                            + "База-посредник и хранилище не изменялись "
                            + "командами помещения.\n"
                            + (failureFolder == null ? "" :
                                "Рабочие файлы: " + failureFolder + "\n")
                            + "Подробнее: Error Log EDT.");
                    });
                    return new Status(IStatus.ERROR, PLUGIN,
                        "Manual candidate preparation failed");
                } finally {
                    try {
                        lease.close();
                    } catch (Exception ex) {
                        Platform.getLog(OutgoingManualHandoffCoordinator.class)
                            .log(new Status(IStatus.WARNING, PLUGIN,
                                "Manual outgoing lease release failed", ex));
                    }
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static List<String> args(Path exe, String mode,
            InfobaseLocation infobase, String username, String password,
            String... extra) {
        var result = new ArrayList<String>();
        result.add(exe.toString());
        result.add(mode);
        result.add(infobase.switchName());
        result.add(infobase.address());
        DesignerInfobaseAuth.addTo(result, username, password);
        result.add("/DisableStartupDialogs");
        result.add("/DisableStartupMessages");
        result.add("/DisableUnrecoverableErrorMessage");
        result.addAll(List.of(extra));
        return result;
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) == 0) {
            throw new IOException("Expected nonempty candidate configuration file");
        }
    }

    /** Execute one native step, never in a shell, with bounded output. */
    private static void runNative(List<String> command, Path folder,
            String stage, StorageProjectSettings.Snapshot settings,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        if (monitor.isCanceled()) throw new InterruptedException();
        Path log = folder.resolve(stage + ".raw-log");
        Path stdout = folder.resolve(stage + ".raw-stdout");
        var commandWithLog = new ArrayList<>(command);
        commandWithLog.add("/Out");
        commandWithLog.add(log.toString());
        Process process = null;
        try {
            process = new ProcessBuilder(commandWithLog)
                .directory(folder.toFile())
                .redirectErrorStream(true)
                .redirectOutput(stdout.toFile()).start();
            long started = System.nanoTime();
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (monitor.isCanceled()) throw new InterruptedException();
                if (System.nanoTime() - started > TIMEOUT.toNanos()) {
                    throw new IOException("Native candidate stage timed out");
                }
                if ((Files.exists(log) && Files.size(log) > LOG_LIMIT)
                        || (Files.exists(stdout) && Files.size(stdout) > LOG_LIMIT)) {
                    throw new IOException("Native output exceeds safety limit");
                }
            }
            if (monitor.isCanceled()) throw new InterruptedException();
            if (process.exitValue() != 0) {
                throw new IOException("Native candidate stage exited nonzero");
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            // Raw Designer output may contain connection/credential strings.
            // Keep only a bounded redacted copy and securely discard the raw
            // diagnostic as best effort. Never serialize process arguments.
            for (Path raw : List.of(log, stdout)) {
                if (!Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    String data = Files.size(raw) <= LOG_LIMIT
                        ? Files.readString(raw, StandardCharsets.UTF_8)
                        : "(native output truncated)";
                    for (String secret : List.of(settings.password(),
                            settings.infobasePassword(),
                            settings.address(), settings.infobaseAddress())) {
                        if (secret != null && !secret.isBlank()) {
                            data = data.replace(secret, "[REDACTED]");
                        }
                    }
                    Files.writeString(folder.resolve(stage + "-"
                        + (raw.equals(log) ? "designer" : "stdout") + ".txt"),
                        data, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // Diagnostic cleanup must not mask the original failure.
                } finally {
                    Files.deleteIfExists(raw);
                }
            }
        }
    }

    private static void startInteractiveDesigner(
            StorageProjectSettings.Snapshot settings, Path executable,
            InfobaseLocation infobase) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add(executable.toString());
        arguments.add("DESIGNER");
        arguments.add(infobase.switchName());
        arguments.add(infobase.address());
        DesignerInfobaseAuth.addTo(arguments, settings.infobaseUsername(),
            settings.infobasePassword());
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    private static void requireInventory(ChangeDetectionRequest request,
            java.util.Map<String, ConfigDumpInfoIndex.Version> expected,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        try (var snapshot = new ConfigVersionSnapshotRunner()
                .capture(request, monitor::isCanceled)) {
            if (!expected.equals(ConfigDumpInfoIndex.readVersions(
                    snapshot.configDumpInfo()))) {
                throw new IOException("Intermediary differs from reviewed source");
            }
        }
    }

    private static void requireHead(StorageProjectSettings.Snapshot settings,
            Path exe, InfobaseLocation infobase, long revision,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        long head = RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(exe, infobase, Duration.ofMinutes(5)),
            settings.infobaseUsername(), settings.infobasePassword(),
            settings.address(), settings.username(), settings.password(),
            monitor::isCanceled);
        if (head != revision) {
            throw new IOException("Repository revision changed since outgoing review");
        }
    }
}
