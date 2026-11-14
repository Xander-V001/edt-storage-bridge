package org.xander.edt.storagebridge.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

/**
 * Local-only, bounded MCP Streamable HTTP JSON transport (handshake-era
 * protocol: 2025-06-18 / 2025-11-25). No SSE/server-initiated requests.
 *
 * Uses only java.base sockets because Eclipse/EDT OSGi target may not export
 * jdk.httpserver or a third-party MCP SDK dependency. The implementation is
 * deliberately narrow: JSON-RPC initialize, ping, tools/list, tools/call.
 */
final class BridgeMcpServer implements AutoCloseable {
    static final BridgeMcpServer INSTANCE = new BridgeMcpServer();
    private static final int MAX_HEADERS = 12 * 1024;
    private static final int MAX_BODY = 256 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 20_000;
    private static final String PROTOCOL = "2025-11-25";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private volatile ServerSocket listener;
    private volatile ThreadPoolExecutor workers;
    private volatile String bearer;
    private volatile int boundPort;

    private BridgeMcpServer() {}

    synchronized void start(int port, String token) throws IOException {
        BridgeMcpSettings.validate(port, token);
        if (isRunning()) {
            if (boundPort == port && bearer.equals(token)) return;
            throw new IllegalStateException(
                "MCP уже запущен. Остановите его перед сменой порта или токена.");
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), port), 16);
        } catch (IOException ex) {
            socket.close();
            throw new IOException("Порт MCP занят или недоступен.");
        }
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
        bearer = token;
        boundPort = port;
        workers = pool;
        listener = socket;
        Thread acceptor = new Thread(() -> accept(socket, pool), "EDT-Storage-MCP-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized boolean isRunning() {
        return listener != null && !listener.isClosed();
    }

    synchronized int port() {
        return boundPort;
    }

    synchronized boolean matches(int port, String token) {
        return isRunning() && boundPort == port && token.equals(bearer);
    }

    @Override
    public synchronized void close() {
        ServerSocket socket = listener;
        listener = null;
        bearer = null;
        boundPort = 0;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        ThreadPoolExecutor pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
    }

    private void accept(ServerSocket socket, ThreadPoolExecutor pool) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                try {
                    pool.execute(() -> serve(client));
                } catch (RuntimeException rejected) {
                    client.close();
                }
            } catch (IOException ex) {
                if (!socket.isClosed()) {
                    BridgeActivator activator = BridgeActivator.getDefault();
                    if (activator != null) {
                        activator.getLog().warn("MCP listener stopped (" +
                            ex.getClass().getSimpleName() + ")");
                    }
                }
                break;
            }
        }
        // A failed accept should not leave a misleading green status.
        synchronized (this) {
            if (listener == socket) close();
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            Request request = read(client.getInputStream());
            Response response = handle(request);
            write(client.getOutputStream(), response);
        } catch (Exception ex) {
            // Don't print raw request headers, tokens, passwords or tool arguments.
            try {
                if (!client.isClosed()) {
                    write(client.getOutputStream(),
                        new Response(400, "Bad Request", "{\"error\":\"Bad request\"}"));
                }
            } catch (IOException ignored) { }
        }
    }

    private record Request(String method, String path,
            Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }

    private record Response(int status, String reason, String body) {}

    private static Request read(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int next = input.read();
            if (next < 0) throw new IOException("Incomplete HTTP request");
            if (headers.size() >= MAX_HEADERS) throw new IOException("Oversize HTTP header");
            headers.write(next);
            state = switch (state) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : (next == '\r' ? 1 : 0);
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : 0;
                default -> 0;
            };
        }
        String raw = headers.toString(StandardCharsets.ISO_8859_1);
        String[] lines = raw.substring(0, raw.length() - 4).split("\r\n");
        if (lines.length == 0) throw new IOException("Missing request line");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1")) {
            throw new IOException("Unsupported HTTP request");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new IOException("Invalid HTTP header");
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (map.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicated HTTP header");
            }
        }
        if (map.containsKey("transfer-encoding")) {
            throw new IOException("Chunked HTTP requests not supported");
        }
        int length;
        try {
            length = Integer.parseInt(map.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid content length");
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("Body size limit");
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new IOException("Incomplete request body");
        return new Request(first[0], first[1], map,
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
        if (!authorization.startsWith("Bearer ")) return false;
        String supplied = authorization.substring("Bearer ".length());
        String expected = bearer;
        if (expected == null) return false;
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

    private static void write(OutputStream out, Response response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + response.status() + " " + response.reason() + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + body.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
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
        if (!init.containsKey("result")) throw new IOException("MCP initialize rejected");
        post(port, token, Map.of("jsonrpc", "2.0",
            "method", "notifications/initialized"), 202);
        Map<String, Object> listed = BridgeMcpJson.object(BridgeMcpJson.parse(
            post(port, token, Map.of("jsonrpc", "2.0", "id", 2,
                "method", "tools/list", "params", Map.of()), 200)));
        var result = BridgeMcpJson.object(listed.get("result"));
        if (!(result.get("tools") instanceof List<?> list) || list.isEmpty()) {
            throw new IOException("MCP tools/list returned no tools");
        }
        return "Проверка успешна: initialize и tools/list, инструментов: " + list.size();
    }

    private static String post(int port, String token, Object body,
            int expectedStatus) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
            new URL(BridgeMcpSettings.url(port)).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setDoOutput(true);
        try {
            byte[] bytes = BridgeMcpJson.stringify(body).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status != expectedStatus) {
                throw new IOException("MCP HTTP status " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
