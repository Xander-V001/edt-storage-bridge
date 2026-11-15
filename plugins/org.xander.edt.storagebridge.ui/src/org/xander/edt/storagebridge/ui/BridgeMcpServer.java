package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Loopback-only MCP Streamable HTTP transport.
 *
 * Use the JDK HTTP server for HTTP framing, chunked requests and connection
 * handling (as used by other Eclipse MCP integrations). Keep our independent
 * JSON-RPC dispatcher and seven Storage Bridge tools: no third-party code.
 * Protocol support is the sessionless 2025-11-25-compatible JSON response
 * subset; there is no SSE stream or server-initiated call.
 */
final class BridgeMcpServer implements AutoCloseable {
    static final BridgeMcpServer INSTANCE = new BridgeMcpServer();
    private static final int MAX_HEADERS = 12 * 1024;
    private static final int MAX_BODY = 256 * 1024;
    private static final String PROTOCOL = "2025-11-25";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private volatile HttpServer listener;
    private volatile ThreadPoolExecutor workers;
    private volatile String bearer;
    private volatile int boundPort;

    private BridgeMcpServer() {}

    synchronized void start(int port, String token) throws IOException {
        BridgeMcpSettings.validate(port, token);
        if (isRunning()) {
            if (boundPort == port && token.equals(bearer)) return;
            throw new IllegalStateException(
                "MCP уже запущен. Остановите его перед сменой порта или токена.");
        }

        // Do not use InetAddress.getLoopbackAddress() here: its selected
        // address family may be IPv6, while the advertised URL is IPv4.
        HttpServer server = HttpServer.create(new InetSocketAddress(
            InetAddress.getByName("127.0.0.1"), port), 16);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "EDT-Storage-MCP-client-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
        try {
            server.createContext("/mcp", this::serve);
            server.setExecutor(pool);
            bearer = token;
            boundPort = port;
            server.start();
            workers = pool;
            listener = server;
        } catch (RuntimeException ex) {
            // Never leave a half-started listener or worker pool behind.
            server.stop(0);
            pool.shutdownNow();
            bearer = null;
            boundPort = 0;
            throw ex;
        }
    }

    synchronized boolean isRunning() {
        return listener != null;
    }

    synchronized int port() {
        return boundPort;
    }

    synchronized boolean requiresAuthentication() {
        return isRunning() && bearer != null && !bearer.isEmpty();
    }

    synchronized boolean matches(int port, String token) {
        return isRunning() && boundPort == port && token.equals(bearer);
    }

    @Override
    public synchronized void close() {
        HttpServer server = listener;
        listener = null;
        bearer = null;
        boundPort = 0;
        if (server != null) server.stop(0);
        ThreadPoolExecutor pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = handle(read(exchange));
            } catch (HttpRequestFailure ex) {
                response = errorHttp(ex.status, ex.message);
            } catch (RuntimeException ex) {
                // Never disclose tool arguments, bearer or configuration.
                response = errorHttp(500, "Internal Server Error");
            }
            write(exchange, response);
        }
    }

    private static final class HttpRequestFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        final String message;

        HttpRequestFailure(int status, String message) {
            super(message);
            this.status = status;
            this.message = message;
        }
    }

    private record Request(String method, String path,
            Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }

    private static Request read(HttpExchange exchange) throws IOException {
        Headers source = exchange.getRequestHeaders();
        Map<String, String> headers = new LinkedHashMap<>();
        int size = 0;
        for (var entry : source.entrySet()) {
            if (entry.getValue() == null || entry.getValue().size() != 1) {
                // Ambiguous authentication, Origin or Host is never admitted.
                throw new HttpRequestFailure(400, "Bad Request");
            }
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().get(0);
            size += key.length() + value.length() + 4;
            if (size > MAX_HEADERS) {
                throw new HttpRequestFailure(431, "Request Header Fields Too Large");
            }
            if (headers.putIfAbsent(key, value) != null) {
                throw new HttpRequestFailure(400, "Bad Request");
            }
        }

        String path = exchange.getRequestURI().getRawPath();
        if (path == null || path.length() > MAX_HEADERS) {
            throw new HttpRequestFailure(414, "URI Too Long");
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength.trim());
                if (length < 0) throw new HttpRequestFailure(400, "Bad Request");
                if (length > MAX_BODY) {
                    throw new HttpRequestFailure(413, "Payload Too Large");
                }
            } catch (NumberFormatException ex) {
                throw new HttpRequestFailure(400, "Bad Request");
            }
        }
        // HttpServer decodes Transfer-Encoding: chunked and supports standard
        // HTTP clients. Only a bounded number of bytes is retained.
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            throw new HttpRequestFailure(413, "Payload Too Large");
        }
        return new Request(exchange.getRequestMethod(), path, headers,
            new String(body, StandardCharsets.UTF_8));
    }

    private Response handle(Request request) {
        // Protect against DNS rebinding even though the socket binds to loopback.
        String host = request.header("host").toLowerCase(Locale.ROOT);
        String origin = request.header("origin").toLowerCase(Locale.ROOT);
        String expectedPort = ":" + port();
        boolean validHost = host.equals("127.0.0.1" + expectedPort)
            || host.equals("localhost" + expectedPort);
        boolean validOrigin = origin.isEmpty()
            || origin.equals("http://127.0.0.1" + expectedPort)
            || origin.equals("http://localhost" + expectedPort);
        if (!validHost || !validOrigin) return errorHttp(403, "Forbidden");
        if (!request.path().equals("/mcp")) return errorHttp(404, "Not Found");
        if (!authorized(request.header("authorization"))) {
            return errorHttp(401, "Unauthorized");
        }
        if (request.method().equals("GET")) {
            // No server-to-client SSE stream is implemented.
            return errorHttp(405, "Method Not Allowed");
        }
        if (request.method().equals("OPTIONS")) {
            // Local non-browser clients do not require CORS preflight; the
            // existing strict Host/Origin check already ran above.
            return new Response(204, "No Content", "");
        }
        if (!request.method().equals("POST")) return errorHttp(405, "Method Not Allowed");
        String type = request.header("content-type").toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            return errorHttp(415, "Unsupported Media Type");
        }
        String accept = request.header("accept").toLowerCase(Locale.ROOT);
        if (!accept.contains("application/json") && !accept.contains("*/*")) {
            return errorHttp(406, "Not Acceptable");
        }
        try {
            Object parsed;
            try {
                parsed = BridgeMcpJson.parse(request.body());
            } catch (RuntimeException ex) {
                return json(jsonError(null, -32700, "Parse error"));
            }
            Map<String, Object> call;
            try {
                call = BridgeMcpJson.object(parsed);
            } catch (IllegalArgumentException ex) {
                return json(jsonError(null, -32600, "Invalid Request"));
            }
            Object id = call.get("id");
            boolean isNotification = !call.containsKey("id");
            if (!"2.0".equals(call.get("jsonrpc"))
                    || !(call.get("method") instanceof String method)
                    || (call.containsKey("id")
                        && !(id instanceof String || id instanceof Number))) {
                return json(jsonError(id, -32600, "Invalid Request"));
            }
            if (isNotification) {
                return new Response(202, "Accepted", "");
            }
            return json(dispatch(method, call, id));
        } catch (RuntimeException ex) {
            return json(jsonError(null, -32603, "Internal error"));
        }
    }

    private boolean authorized(String authorization) {
        String expected = bearer;
        if (expected == null) return false; // closed listener
        // Only the loopback socket can reach this handler; when the user has
        // not set a token, local MCP clients can connect by URL alone.
        if (expected.isEmpty()) return true;
        if (!authorization.startsWith("Bearer ")) return false;
        String supplied = authorization.substring("Bearer ".length());
        return MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> dispatch(
            String method, Map<String, Object> call, Object id) {
        if (method.equals("initialize")) {
            Map<String, Object> args;
            try {
                args = BridgeMcpJson.object(call.get("params"));
            } catch (IllegalArgumentException ex) {
                return jsonError(id, -32602, "Invalid initialize parameters");
            }
            if (!(args.get("protocolVersion") instanceof String requested)) {
                return jsonError(id, -32602, "Missing protocolVersion");
            }
            String negotiated = List.of("2025-11-25", "2025-06-18", "2025-03-26")
                .contains(requested) ? requested : PROTOCOL;
            return jsonResult(id, Map.of(
                "protocolVersion", negotiated,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "edt-storage-bridge", "version", "0.1.0"),
                "instructions", "Инструменты receive_changes и publish_changes запускают "
                    + "автоматические операции без диалогов в EDT. "
                    + "Получите operationId и вызывайте get_operation_status "
                    + "до завершения. При failed/uncertain нельзя считать изменения "
                    + "применёнными. Перед помещением проверяются Git, HEAD и PENDING."));
        }
        if (method.equals("ping")) return jsonResult(id, Map.of());
        if (method.equals("tools/list")) {
            return jsonResult(id, Map.of("tools", BridgeMcpTools.definitions()));
        }
        if (method.equals("tools/call")) {
            try {
                Map<String, Object> params = BridgeMcpJson.object(call.get("params"));
                String name = BridgeMcpJson.string(params.get("name"));
                Map<String, Object> args = params.get("arguments") == null
                    ? Map.of() : BridgeMcpJson.object(params.get("arguments"));
                Object value = BridgeMcpTools.execute(name, args);
                return toolResult(id, value, false);
            } catch (IllegalArgumentException ex) {
                return toolResult(id, Map.of("error", ex.getMessage()), true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return toolResult(id, Map.of("error", "Операция прервана."), true);
            } catch (Exception ex) {
                // Never return paths, passwords or process command-line arguments.
                return toolResult(id, Map.of("error",
                    "Операция не выполнена. Проверьте настройки и журнал EDT.",
                    "type", ex.getClass().getSimpleName()), true);
            }
        }
        return jsonError(id, -32601, "Method not found");
    }

    private static Map<String, Object> toolResult(Object id, Object value, boolean failed) {
        return jsonResult(id, Map.of(
            "isError", failed,
            "content", List.of(Map.of("type", "text",
                "text", BridgeMcpJson.stringify(value)))));
    }

    private static Map<String, Object> jsonResult(Object id, Object result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        map.put("result", result);
        return map;
    }

    private static Map<String, Object> jsonError(Object id, int code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        map.put("error", Map.of("code", code, "message", message));
        return map;
    }

    private static Response json(Object value) {
        return new Response(200, "OK", BridgeMcpJson.stringify(value));
    }

    private static Response errorHttp(int status, String text) {
        return new Response(status, text, "{\"error\":\"" + text + "\"}");
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (response.status() == 405) {
            headers.set("Allow", "POST, OPTIONS");
        }
        if (response.status() == 202 || response.status() == 204) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Actual protocol probe: initialize, notification, tools/list over HTTP. */
    String probe() throws IOException {
        if (!isRunning()) throw new IllegalStateException("MCP не запущен.");
        int port = port();
        String token = bearer;
        if (token == null) throw new IllegalStateException("MCP не запущен.");
        Map<String, Object> init = BridgeMcpJson.object(BridgeMcpJson.parse(
            post(port, token, Map.of("jsonrpc", "2.0", "id", 1,
                "method", "initialize", "params",
                Map.of("protocolVersion", PROTOCOL,
                    "clientInfo", Map.of("name", "edt-mcp-self-test", "version", "1.0"),
                    "capabilities", Map.of())), 200)));
        Map<String, Object> handshake;
        try {
            handshake = BridgeMcpJson.object(init.get("result"));
        } catch (IllegalArgumentException ex) {
            throw new IOException("MCP initialize не вернул корректный результат.");
        }
        if (!PROTOCOL.equals(handshake.get("protocolVersion"))) {
            throw new IOException("MCP сообщил неподдерживаемую версию протокола.");
        }
        post(port, token, Map.of("jsonrpc", "2.0",
            "method", "notifications/initialized"), 202);
        Map<String, Object> listed = BridgeMcpJson.object(BridgeMcpJson.parse(
            post(port, token, Map.of("jsonrpc", "2.0", "id", 2,
                "method", "tools/list", "params", Map.of()), 200)));
        var result = BridgeMcpJson.object(listed.get("result"));
        if (!(result.get("tools") instanceof List<?> list) || list.isEmpty()) {
            throw new IOException("MCP tools/list вернул пустой список.");
        }
        var toolNames = new java.util.HashSet<String>();
        try {
            for (Object entry : list) {
                toolNames.add(BridgeMcpJson.string(
                    BridgeMcpJson.object(entry).get("name")));
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("MCP tools/list вернул повреждённое описание.");
        }
        if (!toolNames.containsAll(List.of("list_projects", "get_storage_status",
                "get_pending_changes", "check_repository_revision",
                "receive_changes", "publish_changes", "get_operation_status"))) {
            throw new IOException("MCP tools/list не содержит все семь инструментов.");
        }
        // Exercise a read-only tool, not just tool discovery. This never starts
        // 1C and never changes EDT projects.
        Map<String, Object> invoked = BridgeMcpJson.object(BridgeMcpJson.parse(
            post(port, token, Map.of("jsonrpc", "2.0", "id", 3,
                "method", "tools/call", "params", Map.of(
                    "name", "list_projects", "arguments", Map.of())), 200)));
        Map<String, Object> toolResult;
        try {
            toolResult = BridgeMcpJson.object(invoked.get("result"));
        } catch (IllegalArgumentException ex) {
            throw new IOException("MCP tools/call не вернул результат.");
        }
        if (!Boolean.FALSE.equals(toolResult.get("isError"))
                || !(toolResult.get("content") instanceof List<?>)) {
            throw new IOException("MCP list_projects не выполнился.");
        }
        // Exercise a real chunked request: clients may omit Content-Length.
        Map<String, Object> chunkedPing = BridgeMcpJson.object(BridgeMcpJson.parse(
            post(port, token, Map.of("jsonrpc", "2.0", "id", 6,
                "method", "ping", "params", Map.of()), 200, true)));
        if (!chunkedPing.containsKey("result")) {
            throw new IOException("MCP HTTP chunked ping не вернул результат.");
        }
        if (!token.isEmpty()) {
            // A wrong bearer must be rejected before executing the tool.
            String wrongToken = (token.charAt(0) == '0' ? "1" : "0")
                + token.substring(1);
            post(port, wrongToken, Map.of("jsonrpc", "2.0", "id", 4,
                "method", "tools/list", "params", Map.of()), 401);
        }
        return "Проверка успешна: initialize, список " + list.size()
            + " инструментов, list_projects; локальный доступ "
            + (token.isEmpty() ? "без токена." : "с защитой токеном.");
    }

    private static String post(int port, String token, Object body,
            int expectedStatus) throws IOException {
        return post(port, token, body, expectedStatus, false);
    }

    private static String post(int port, String token, Object body,
            int expectedStatus, boolean chunked) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
            new URL(BridgeMcpSettings.url(port)).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setDoOutput(true);
        try {
            byte[] bytes = BridgeMcpJson.stringify(body).getBytes(StandardCharsets.UTF_8);
            if (chunked) {
                connection.setChunkedStreamingMode(512);
            } else {
                connection.setFixedLengthStreamingMode(bytes.length);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status != expectedStatus) {
                throw new IOException("MCP HTTP status " + status);
            }
            // HttpURLConnection exposes 4xx bodies via getErrorStream().
            // A negative authorization test is successful only for HTTP 401.
            InputStream responseStream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
            if (responseStream == null) return "";
            try (InputStream input = responseStream) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
