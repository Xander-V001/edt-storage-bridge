package org.xander.edt.storagebridge.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/**
 * MCP adapter: only existing, public plugin workflows. No Designer write
 * commands, no direct repository commit, no hidden auto-merge.
 */
final class BridgeMcpTools {
    private static final Set<String> UI_REQUESTS = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.Semaphore LIVE_REVISION_LIMIT =
        new java.util.concurrent.Semaphore(1);

    private BridgeMcpTools() {}

    static List<Map<String, Object>> definitions() {
        return List.of(
            tool("list_projects", "Открытые проекты EDT",
                "Список открытых проектов рабочей области. Не изменяет файлы.",
                false, false),
            tool("get_storage_status", "Состояние синхронизации",
                "Для проекта: адрес хранилища, ветка Git, обнаруженная и обработанная версии, количество необработанных объектов. Пароли не возвращаются.",
                true, false),
            tool("get_pending_changes", "Ожидающие объекты",
                "Прочитать текущие имена объектов, ожидающих разбора после получения из хранилища.",
                true, false),
            tool("check_repository_revision", "Проверить версию хранилища",
                "Через существующий механизм отчёта 1С прочитать актуальный номер версии. Только чтение, может быть длительным.",
                true, false),
            tool("request_receive_changes", "Получить изменения из хранилища",
                "Запросить в EDT запуск существующего выборочного получения. Требует подтверждения в окне EDT; результат объединения не подтверждается автоматически.",
                true, true),
            tool("request_full_review", "Полная сверка с хранилищем",
                "Запросить в EDT запуск существующей полной сверки. Требует подтверждения в окне EDT; без автоматического объединения.",
                true, true),
            tool("request_publish_changes", "Поместить изменения в хранилище",
                "Запросить существующий путь помещения. Только после явного подтверждения пользователя в EDT: дальнейший workflow может выполнять запись в хранилище.",
                true, true)
        );
    }

    private static Map<String, Object> tool(String name, String title,
            String description, boolean requiresProject, boolean modifiesState) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (requiresProject) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("project", Map.of("type", "string", "description",
                "Точное имя открытого проекта в рабочей области EDT"));
            if (name.equals("get_pending_changes")) {
                properties.put("offset", Map.of("type", "integer", "minimum", 0));
                properties.put("limit", Map.of("type", "integer", "minimum", 1,
                    "maximum", 200));
            }
            schema.put("properties", properties);
            schema.put("required", List.of("project"));
        } else {
            schema.put("properties", Map.of());
        }
        schema.put("additionalProperties", false);
        return Map.of(
            "name", name,
            "title", title,
            "description", description,
            "inputSchema", schema,
            "annotations", Map.of(
                "readOnlyHint", !modifiesState,
                "destructiveHint", modifiesState,
                "idempotentHint", !modifiesState,
                "openWorldHint", false));
    }

    static Object execute(String name, Map<String, Object> args) throws Exception {
        return switch (name) {
            case "list_projects" -> listProjects();
            case "get_storage_status" -> status(project(args));
            case "get_pending_changes" -> pending(project(args), args);
            case "check_repository_revision" -> liveRevision(project(args));
            case "request_receive_changes" ->
                requestUi(project(args), "receive", "выборочное получение изменений");
            case "request_full_review" ->
                requestUi(project(args), "full", "полную сверку с хранилищем");
            case "request_publish_changes" ->
                requestUi(project(args), "publish", "помещение изменений в хранилище");
            default -> throw new IllegalArgumentException("Неизвестный MCP-инструмент.");
        };
    }

    private static IProject project(Map<String, Object> args) {
        Object raw = args.get("project");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("Нужно указать точное имя проекта EDT.");
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.exists() || !project.isAccessible()) {
            throw new IllegalArgumentException("Проект не существует или закрыт.");
        }
        return project;
    }

    private static List<String> listProjects() {
        List<String> names = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isAccessible()) names.add(project.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static StorageProjectSettings.Snapshot configured(IProject project)
            throws StorageException {
        var settings = StorageProjectSettings.load(project);
        if (settings.address().isBlank() || settings.infobaseAddress().isBlank()
                || settings.platformExecutable().isBlank()) {
            throw new IllegalArgumentException(
                "Подключение не настроено. Откройте окно «Работа с хранилищем» в EDT.");
        }
        return settings;
    }

    private static String branch(IProject project) {
        try {
            return GitReceiveGuard.currentBranch(project);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static String intermediary(StorageProjectSettings.Snapshot settings) {
        return settings.infobaseType().name() + ":"
            + settings.infobaseAddress().trim();
    }

    private static Map<String, Object> status(IProject project) throws Exception {
        StorageProjectSettings.Snapshot settings = configured(project);
        String gitBranch = branch(project);
        String identity = intermediary(settings);
        long observed = ReceiveRevisionStore.observed(project, settings.address(), identity);
        long reviewed = gitBranch.isBlank() ? ReceiveRevisionStore.UNKNOWN
            : ReceiveRevisionStore.reviewed(
                project, settings.address(), identity, gitBranch);
        int pendingCount = gitBranch.isBlank() ? -1
            : ReceivePendingStore.get(
                project, settings.address(), gitBranch, identity).size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project.getName());
        result.put("repository", settings.address());
        result.put("infobaseType", settings.infobaseType().name());
        result.put("branch", gitBranch);
        result.put("observedRevision", observed > 0 ? observed : null);
        result.put("reviewedRevision", reviewed > 0 ? reviewed : null);
        result.put("pendingCount", pendingCount >= 0 ? pendingCount : null);
        result.put("canPublish", false);
        result.put("publishNote", "Прямой бесконтрольный commit через MCP запрещён. Доступен запрос существующего сценария с подтверждением в EDT.");
        return result;
    }

    private static Object pending(IProject project, Map<String, Object> args)
            throws Exception {
        StorageProjectSettings.Snapshot settings = configured(project);
        String gitBranch = branch(project);
        if (gitBranch.isBlank()) {
            throw new IllegalArgumentException("Не удалось определить текущую ветку Git.");
        }
        List<String> objects = new ArrayList<>(ReceivePendingStore.get(
            project, settings.address(), gitBranch, intermediary(settings)));
        Collections.sort(objects);
        int offset = integerArgument(args.get("offset"), 0, 0, Integer.MAX_VALUE);
        int limit = integerArgument(args.get("limit"), 100, 1, 200);
        int start = Math.min(offset, objects.size());
        int end = (int) Math.min((long) objects.size(), (long) start + limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project.getName());
        result.put("branch", gitBranch);
        result.put("count", objects.size());
        result.put("offset", start);
        result.put("objects", objects.subList(start, end));
        result.put("hasMore", end < objects.size());
        result.put("nextOffset", end < objects.size() ? end : null);
        return result;
    }

    private static int integerArgument(Object raw, int def, int min, int max) {
        if (raw == null) return def;
        if (!(raw instanceof Number number)
                || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("offset/limit должны быть целыми.");
        }
        int value = number.intValue();
        if (value < min || value > max) {
            throw new IllegalArgumentException("offset/limit вне диапазона.");
        }
        return value;
    }

    private static Object liveRevision(IProject project) throws Exception {
        if (!LIVE_REVISION_LIMIT.tryAcquire()) {
            throw new IllegalArgumentException("Проверка версии через MCP уже выполняется.");
        }
        try {
        StorageProjectSettings.Snapshot settings = configured(project);
        var kind = settings.infobaseType() == StorageProjectSettings.InfobaseType.FILE
            ? InfobaseLocation.Kind.FILE : InfobaseLocation.Kind.SERVER;
        long version = RepositoryLatestRevisionReader.latest(
            RepositoryHistoryRequest.latest(
                Path.of(settings.platformExecutable()).toAbsolutePath(),
                new InfobaseLocation(kind, settings.infobaseAddress()),
                Duration.ofMinutes(5)),
            settings.infobaseUsername(), settings.infobasePassword(),
            settings.address(), settings.username(), settings.password(),
            Thread.currentThread()::isInterrupted);
        return Map.of("project", project.getName(), "latestRevision", version,
            "checkpointChanged", false);
        } finally {
            LIVE_REVISION_LIMIT.release();
        }
    }

    private static Object requestUi(IProject project, String mode, String action) {
        String key = project.getName() + ":" + mode;
        if (!UI_REQUESTS.add(key)) {
            throw new IllegalArgumentException(
                "Запрос для этого проекта уже ожидает подтверждения в EDT.");
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            UI_REQUESTS.remove(key);
            throw new IllegalStateException("Графический интерфейс EDT недоступен.");
        }
        try {
            display.asyncExec(() -> {
                try {
                    IWorkbenchWindow window =
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null || window.getShell().isDisposed()
                            || !project.isAccessible()) return;
                    Shell shell = window.getShell();
                    boolean approved = MessageDialog.openQuestion(shell,
                        "Запрос MCP — EDT Storage Bridge",
                        "MCP-клиент запросил " + action + " для проекта «"
                        + project.getName() + "».\n\nВыполнить команду в EDT?"
                        + "\n\nЭто не подтверждает принятие изменений или их помещение.");
                    if (!approved) return;
                    StorageProjectSettings.Snapshot settings = configured(project);
                    switch (mode) {
                        case "receive" -> ReceiveChangesCoordinator.receive(
                            shell, project, settings,
                            ReceiveChangesCoordinator.ReceiveMode.INCREMENTAL);
                        case "full" -> ReceiveChangesCoordinator.receive(
                            shell, project, settings,
                            ReceiveChangesCoordinator.ReceiveMode.FULL_REVIEW);
                        case "publish" -> OutgoingReadinessCoordinator.check(
                            shell, project, settings);
                        default -> throw new IllegalArgumentException("Invalid MCP operation");
                    }
                } catch (Exception ex) {
                    IWorkbenchWindow window =
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window != null && !window.getShell().isDisposed()) {
                        MessageDialog.openError(window.getShell(), "Ошибка MCP",
                            "Не удалось запустить существующую команду. "
                            + "Проверьте настройки и журнал EDT.");
                    }
                } finally {
                    UI_REQUESTS.remove(key);
                }
            });
        } catch (RuntimeException ex) {
            UI_REQUESTS.remove(key);
            throw ex;
        }
        return Map.of("state", "awaiting_user_confirmation_in_edt",
            "project", project.getName(),
            "operation", mode,
            "completed", false,
            "note", "Команда будет запущена только после подтверждения в EDT. "
                + "Этот ответ не является результатом выполнения.");
    }
}
