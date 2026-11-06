/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.model.ai.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolCallResult;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.agent.AIToolClient;
import com.seizia.chromite.model.ai.agent.AIToolExecutor;
import com.seizia.chromite.model.ai.tools.AgentJson;
import com.seizia.chromite.model.runtime.BlockCanceler;
import com.seizia.chromite.model.runtime.DBRBlockingObject;
import com.seizia.chromite.model.runtime.DefaultProgressMonitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local MCP server: Streamable HTTP on 127.0.0.1, bearer token, the agent tool catalog through {@link AIToolExecutor}.
 *
 * 설계(docs/chromite/ai-agent.md "로컬 MCP 서버"):
 * <ul>
 *     <li>전송: {@code POST /mcp}에 JSON-RPC 2.0, 응답은 {@code application/json} 한 건. 서버가 먼저 보낼 알림이 없으므로 SSE 스트림을 열지 않는다
 *     (Streamable HTTP는 서버가 JSON 응답을 고를 수 있다). {@code GET}은 405, {@code DELETE}는 세션을 지운다.</li>
 *     <li>버전 협상: 클라이언트가 지원 버전 중 하나를 요청하면 그 버전, 아니면 {@value #DEFAULT_PROTOCOL_VERSION}. 쓰는 기능(tools, prompts, ping, 취소 알림)은
 *     세 버전에서 같다.</li>
 *     <li>보안: 루프백 주소에만 묶고 원격 주소도 확인한다. 토큰은 상수 시간 비교. DNS rebinding 방어로 {@code Host}와 {@code Origin}을
 *     {@code 127.0.0.1:<포트>}/{@code localhost:<포트>}만 허용한다. 본문은 {@value #MAX_BODY} 바이트까지.</li>
 *     <li>도구 호출은 채팅과 같은 실행기를 쓴다({@link AIToolClient#MCP}): 정책, 확인(앱의 대화상자, 없으면 거부), 트랜잭션 안전, 감사.</li>
 *     <li>취소: {@code notifications/cancelled}의 requestId로 그 호출의 모니터를 취소하고 실행 중인 문장(모니터 블록)을 취소한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AIMcpServer {

    private static final Log log = Log.getLog(AIMcpServer.class);

    public static final String PATH = "/mcp"; //$NON-NLS-1$
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18"; //$NON-NLS-1$
    static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String HEADER_SESSION = "Mcp-Session-Id"; //$NON-NLS-1$
    private static final String HEADER_PROTOCOL = "MCP-Protocol-Version"; //$NON-NLS-1$
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int THREADS = 8;

    private static final AIMcpServer instance = new AIMcpServer();

    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private String token;
    private volatile String lastError;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, DefaultProgressMonitor> running = new ConcurrentHashMap<>();

    private AIMcpServer() {
    }

    @NotNull
    public static AIMcpServer getInstance() {
        return instance;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /**
     * Port the server listens on, or -1 when stopped.
     */
    public synchronized int getPort() {
        return server == null ? -1 : port;
    }

    /**
     * Why the last start failed (port in use, ...), or null.
     */
    @Nullable
    public String getLastError() {
        return lastError;
    }

    /**
     * Starts, stops or restarts the server to match the settings.
     */
    public synchronized void applySettings() {
        boolean enabled = AIMcpSettings.isEnabled();
        int wantedPort = AIMcpSettings.getPort();
        if (server != null && (!enabled || wantedPort != port)) {
            stop();
        }
        if (enabled && server == null) {
            start(wantedPort);
        }
    }

    private void start(int newPort) {
        try {
            token = AIMcpSettings.getToken();
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), newPort), 16);
            executor = Executors.newFixedThreadPool(THREADS, runnable -> {
                Thread thread = new Thread(runnable, "Chromite MCP"); //$NON-NLS-1$
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(executor);
            httpServer.createContext(PATH, this::handle);
            httpServer.start();
            server = httpServer;
            port = newPort;
            lastError = null;
            log.info("Chromite MCP server listening on " + AIMcpSettings.getUrl());
        } catch (IOException | DBException e) {
            lastError = e.getMessage();
            log.error("Can't start the Chromite MCP server on port " + newPort, e);
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sessions.clear();
        running.values().forEach(AIMcpServer::cancel);
        running.clear();
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(@NotNull HttpExchange exchange) throws IOException {
        try {
            InetSocketAddress remote = exchange.getRemoteAddress();
            if (remote == null || !remote.getAddress().isLoopbackAddress()) {
                send(exchange, 403, null);
                return;
            }
            if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host")) ||
                !isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                send(exchange, 403, null);
                return;
            }
            if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                send(exchange, 401, null);
                return;
            }
            String protocol = exchange.getRequestHeaders().getFirst(HEADER_PROTOCOL);
            if (protocol != null && !SUPPORTED_VERSIONS.contains(protocol)) {
                send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Unsupported MCP-Protocol-Version " + protocol));
                return;
            }
            String session = exchange.getRequestHeaders().getFirst(HEADER_SESSION);
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    // ok
                }
                case "DELETE" -> {
                    if (session != null) {
                        sessions.remove(session);
                    }
                    send(exchange, 200, null);
                    return;
                }
                default -> {
                    exchange.getResponseHeaders().add("Allow", "POST, DELETE");
                    send(exchange, 405, null);
                    return;
                }
            }
            if (session != null && !sessions.contains(session)) {
                send(exchange, 404, error(JsonNull.INSTANCE, -32001, "Unknown or expired session"));
                return;
            }
            byte[] body = readBody(exchange.getRequestBody());
            if (body == null) {
                send(exchange, 413, error(JsonNull.INSTANCE, -32600, "Request too large"));
                return;
            }
            JsonElement request;
            try {
                request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                send(exchange, 400, error(JsonNull.INSTANCE, -32700, "Parse error"));
                return;
            }
            if (request.isJsonArray()) {
                JsonArray responses = new JsonArray();
                for (JsonElement item : request.getAsJsonArray()) {
                    JsonObject response = dispatch(item, exchange);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                send(exchange, responses.isEmpty() ? 202 : 200, responses.isEmpty() ? null : responses);
            } else {
                JsonObject response = dispatch(request, exchange);
                send(exchange, response == null ? 202 : 200, response);
            }
        } catch (Throwable e) {
            log.error("MCP request failed", e);
            send(exchange, 500, error(JsonNull.INSTANCE, -32603, "Internal error"));
        } finally {
            exchange.close();
        }
    }

    private boolean isAllowedHost(@Nullable String host) {
        return host != null && (host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port));
    }

    private boolean isAllowedOrigin(@Nullable String origin) {
        // No Origin: a non-browser client (Claude Code, curl). A browser page always sends one and must be this server itself
        return origin == null || origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port);
    }

    private boolean isAuthorized(@Nullable String header) {
        if (header == null || !header.startsWith("Bearer ") || token == null) {
            return false;
        }
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.US_ASCII));
    }

    @Nullable
    private static byte[] readBody(@NotNull InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY + 1);
        return body.length > MAX_BODY ? null : body;
    }

    private static void send(@NotNull HttpExchange exchange, int status, @Nullable JsonElement json) throws IOException {
        if (json == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = AgentJson.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------ JSON-RPC

    // Returns the response, or null for notifications
    @Nullable
    private JsonObject dispatch(@NotNull JsonElement element, @NotNull HttpExchange exchange) {
        if (!element.isJsonObject()) {
            return error(JsonNull.INSTANCE, -32600, "Invalid request");
        }
        JsonObject request = element.getAsJsonObject();
        JsonElement id = request.get("id");
        String method = request.has("method") ? request.get("method").getAsString() : null;
        JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
        if (method == null) {
            // A response to a server request; the server sends none
            return null;
        }
        boolean notification = id == null || id.isJsonNull();
        try {
            switch (method) {
                case "initialize" -> {
                    String sessionId = UUID.randomUUID().toString();
                    sessions.add(sessionId);
                    exchange.getResponseHeaders().set(HEADER_SESSION, sessionId);
                    return result(id, initialize(params));
                }
                case "notifications/initialized" -> {
                    return null;
                }
                case "notifications/cancelled" -> {
                    JsonElement requestId = params.get("requestId");
                    if (requestId != null) {
                        DefaultProgressMonitor monitor = running.get(requestId.toString());
                        if (monitor != null) {
                            cancel(monitor);
                        }
                    }
                    return null;
                }
                case "ping" -> {
                    return result(id, new JsonObject());
                }
                case "tools/list" -> {
                    return result(id, listTools());
                }
                case "tools/call" -> {
                    return result(id, callTool(id, params));
                }
                case "prompts/list" -> {
                    return result(id, AIAgentPlaybooks.list());
                }
                case "prompts/get" -> {
                    JsonObject prompt = AIAgentPlaybooks.get(params);
                    return prompt == null ? error(id, -32602, "Unknown prompt") : result(id, prompt);
                }
                default -> {
                    return notification ? null : error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (Throwable e) {
            log.debug("MCP method " + method + " failed", e);
            return notification ? null : error(id, -32603, e.getMessage() == null ? "Internal error" : e.getMessage());
        }
    }

    @NotNull
    private static JsonObject initialize(@NotNull JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        JsonObject prompts = new JsonObject();
        prompts.addProperty("listChanged", false);
        capabilities.add("prompts", prompts);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "chromite");
        serverInfo.addProperty("title", "Chromite Database Workbench");
        serverInfo.addProperty("version", AIMcpServer.class.getPackage().getImplementationVersion() == null
            ? "1.0" : AIMcpServer.class.getPackage().getImplementationVersion());
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", AIAgentPlaybooks.INSTRUCTIONS);
        return result;
    }

    @NotNull
    private static JsonObject listTools() {
        JsonArray tools = new JsonArray();
        for (AIFunctionDescriptor tool : AIToolCatalog.getTools(AIFunctionPurpose.MCP)) {
            tools.add(toolJson(tool));
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    @NotNull
    static JsonObject toolJson(@NotNull AIFunctionDescriptor tool) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (AIFunctionParameter parameter : tool.getParameters()) {
            JsonObject property = new JsonObject();
            property.addProperty("type", parameter.getType());
            if (parameter.getDescription() != null) {
                property.addProperty("description", parameter.getDescription());
            }
            String[] values = parameter.getValidValues();
            if (values != null && values.length > 0) {
                JsonArray array = new JsonArray();
                for (String value : values) {
                    array.add(value);
                }
                property.add("enum", array);
            }
            properties.add(parameter.getName(), property);
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        JsonObject annotations = new JsonObject();
        annotations.addProperty("title", tool.getName());
        annotations.addProperty("readOnlyHint", tool.getAccess() == AIToolAccess.READ);
        annotations.addProperty("destructiveHint", tool.getAccess() == AIToolAccess.DANGEROUS);
        annotations.addProperty("openWorldHint", false);
        JsonObject json = new JsonObject();
        json.addProperty("name", tool.getId());
        json.addProperty("title", tool.getName());
        json.addProperty("description", tool.getAiDescription() + " [" + tool.getAccess().name() + "]");
        json.add("inputSchema", schema);
        json.add("annotations", annotations);
        return json;
    }

    @NotNull
    private JsonObject callTool(@Nullable JsonElement id, @NotNull JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : params.getAsJsonObject("arguments").entrySet()) {
                arguments.put(e.getKey(), toJava(e.getValue()));
            }
        }
        DefaultProgressMonitor monitor = new DefaultProgressMonitor(new NullProgressMonitor());
        String key = id == null ? UUID.randomUUID().toString() : id.toString();
        running.put(key, monitor);
        AIToolCallResult result;
        try {
            result = AIToolExecutor.execute(monitor, AIToolClient.MCP, name, arguments, null);
        } finally {
            running.remove(key);
        }
        JsonObject json = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result.text());
        content.add(text);
        json.add("content", content);
        try {
            JsonElement structured = JsonParser.parseString(result.text());
            if (structured.isJsonObject()) {
                json.add("structuredContent", structured);
            }
        } catch (RuntimeException e) {
            // plain text result
        }
        json.addProperty("isError", result.isError());
        return json;
    }

    // Nested objects/arrays stay JSON text, as the tools read JSON arguments from text
    @Nullable
    private static Object toJava(@NotNull JsonElement value) {
        if (value.isJsonNull()) {
            return null;
        }
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsBigDecimal();
            }
            return primitive.getAsString();
        }
        return AgentJson.GSON.toJson(value);
    }

    private static void cancel(@NotNull DefaultProgressMonitor monitor) {
        monitor.getNestedMonitor().setCanceled(true);
        List<DBRBlockingObject> blocks = monitor.getActiveBlocks();
        if (blocks != null) {
            for (DBRBlockingObject block : List.copyOf(blocks)) {
                try {
                    BlockCanceler.cancelBlock(monitor, block);
                } catch (DBException e) {
                    log.debug("Can't cancel an MCP tool statement", e);
                }
            }
        }
    }

    @NotNull
    private static JsonObject result(@Nullable JsonElement id, @NotNull JsonElement result) {
        JsonObject json = new JsonObject();
        json.addProperty("jsonrpc", "2.0");
        json.add("id", id == null ? JsonNull.INSTANCE : id);
        json.add("result", result);
        return json;
    }

    @NotNull
    private static JsonObject error(@Nullable JsonElement id, int code, @NotNull String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject json = new JsonObject();
        json.addProperty("jsonrpc", "2.0");
        json.add("id", id == null ? JsonNull.INSTANCE : id);
        json.add("error", error);
        return json;
    }
}
