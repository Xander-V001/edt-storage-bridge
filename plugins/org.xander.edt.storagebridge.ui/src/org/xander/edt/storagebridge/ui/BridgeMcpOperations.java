package org.xander.edt.storagebridge.ui;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;

/**
 * Thread-safe state of explicit MCP commands. A tool call queues work and
 * returns its ID; only a terminal phase certifies the outcome. Never infer
 * success from an Eclipse Job being scheduled or a native editor closing.
 */
final class BridgeMcpOperations {
    private static final ConcurrentHashMap<String, Operation> OPERATIONS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ACTIVE_PROJECTS =
        new ConcurrentHashMap<>();

    private BridgeMcpOperations() {}

    static final class Operation {
        final String id;
        final String project;
        final String action;
        final String projectKey;
        final String startedAt;
        volatile String state = "queued";
        volatile String phase = "queued";
        volatile String detail = "Операция ожидает выполнения EDT.";
        volatile String finishedAt = "";

        private Operation(String id, String project, String action, String projectKey) {
            this.id = id;
            this.project = project;
            this.action = action;
            this.projectKey = projectKey;
            this.startedAt = Instant.now().toString();
        }

        Map<String, Object> snapshot() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("operationId", id);
            value.put("project", project);
            value.put("action", action);
            value.put("state", state);
            value.put("phase", phase);
            value.put("detail", detail);
            value.put("startedAt", startedAt);
            value.put("finishedAt", finishedAt.isEmpty() ? null : finishedAt);
            value.put("completed", "completed".equals(state));
            value.put("requiresManualRecovery", "uncertain".equals(state));
            return value;
        }
    }

    static Operation begin(IProject project, String action) {
        String projectKey = project.getLocationURI().normalize().toString();
        String id = UUID.randomUUID().toString();
        Operation operation = new Operation(id, project.getName(), action, projectKey);
        String running = ACTIVE_PROJECTS.putIfAbsent(projectKey, id);
        if (running != null) {
            throw new IllegalStateException(
                "Для проекта уже выполняется MCP-операция: " + running);
        }
        OPERATIONS.put(id, operation);
        return operation;
    }

    static Map<String, Object> status(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("Нужно указать operationId.");
        }
        Operation operation = OPERATIONS.get(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("Неизвестный идентификатор операции.");
        }
        return operation.snapshot();
    }

    static void stage(Operation operation, String phase) {
        synchronized (operation) {
            if (terminal(operation.state)) return;
            operation.state = "running";
            operation.phase = phase;
            operation.detail = "Выполнение средствами EDT и 1С.";
        }
    }

    static void completed(Operation operation, String phase, String detail) {
        finish(operation, "completed", phase, detail);
    }

    static void failed(Operation operation, String phase, String detail,
            boolean outcomeUncertain) {
        finish(operation, outcomeUncertain ? "uncertain" : "failed", phase, detail);
    }

    private static void finish(Operation operation, String state,
            String phase, String detail) {
        synchronized (operation) {
            if (terminal(operation.state)) return;
            operation.state = state;
            operation.phase = phase;
            operation.detail = detail;
            operation.finishedAt = Instant.now().toString();
        }
        ACTIVE_PROJECTS.remove(operation.projectKey, operation.id);
    }

    private static boolean terminal(String state) {
        return "completed".equals(state) || "failed".equals(state)
            || "uncertain".equals(state);
    }
}
