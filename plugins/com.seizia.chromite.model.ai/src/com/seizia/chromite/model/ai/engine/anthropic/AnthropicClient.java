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
package com.seizia.chromite.model.ai.engine.anthropic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.AIImageAttachment;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.AIMessageType;
import com.seizia.chromite.model.ai.AIUsage;
import com.seizia.chromite.model.ai.engine.AIEngineResponse;
import com.seizia.chromite.model.ai.engine.AIEngineResponseChunk;
import com.seizia.chromite.model.ai.engine.AIEngineResponseConsumer;
import com.seizia.chromite.model.ai.engine.AbstractHttpAIClient;
import com.seizia.chromite.model.ai.utils.AIHttpUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client of the Anthropic Messages API ({@code POST /v1/messages}) with tool use.
 *
 * 설계:
 * <ul>
 *     <li>요청·응답을 DTO 클래스 대신 {@link JsonObject}로 만든다. 쓰는 필드가 적고, 요청 조립({@link #buildRequest})과 응답 해석
 *     ({@link #parseResponse})을 네트워크 없이 시험할 수 있게 정적 함수로 두었다(API 키가 없는 PC에서도 요청 모양을 확인).</li>
 *     <li>메시지 변환: SYSTEM → 최상위 {@code system}. 도구 호출 메시지({@link AIMessage#getFunctionCall()})는 assistant의
 *     {@code tool_use} 블록과 user의 {@code tool_result} 블록 한 쌍이 된다. Messages API는 user/assistant가 번갈아 와야 하므로
 *     같은 역할이 이어지면 내용 블록을 한 메시지로 합친다.</li>
 *     <li>{@code tool_use_id}는 응답에서 받은 값을 {@link AIFunctionCall#getMessageMetadata()}에 넣어 다음 요청의 {@code tool_result}에 그대로 쓴다
 *     ({@code AIFunctionCall}의 주석이 말하는 Anthropic 요구 사항).</li>
 *     <li>스트리밍은 SSE({@code event:}/{@code data:} 줄). 글자 조각은 바로 넘기고, {@code tool_use}의 {@code input_json_delta}는 모아서
 *     블록이 끝날 때 도구 호출 하나로 넘긴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "엔진")
 */
public class AnthropicClient extends AbstractHttpAIClient {

    private static final Log log = Log.getLog(AnthropicClient.class);
    private static final Gson GSON = new Gson();

    public static final String API_VERSION = "2023-06-01"; //$NON-NLS-1$
    public static final String HEADER_API_KEY = "x-api-key"; //$NON-NLS-1$
    public static final String HEADER_API_VERSION = "anthropic-version"; //$NON-NLS-1$
    public static final String TOOL_USE_ID = "tool_use_id"; //$NON-NLS-1$

    private final String baseUrl;
    private final String apiKey;

    public AnthropicClient(@NotNull String baseUrl, @NotNull String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Model IDs from {@code GET /v1/models}.
     */
    @NotNull
    public List<String> loadModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        HttpRequest request = newRequestBuilder()
            .uri(AIHttpUtils.resolve(baseUrl, "v1/models?limit=100")) //$NON-NLS-1$
            .GET()
            .timeout(timeout)
            .build();
        JsonObject json = JsonParser.parseString(client.send(monitor, request)).getAsJsonObject();
        List<String> ids = new ArrayList<>();
        JsonArray data = json.getAsJsonArray("data");
        if (data != null) {
            for (JsonElement model : data) {
                ids.add(model.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    @NotNull
    public AIEngineResponse createMessage(@NotNull DBRProgressMonitor monitor, @NotNull JsonObject body) throws DBException {
        HttpRequest request = newRequestBuilder()
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .uri(AIHttpUtils.resolve(baseUrl, "v1/messages")) //$NON-NLS-1$
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .timeout(timeout)
            .build();
        return parseResponse(JsonParser.parseString(client.send(monitor, request)).getAsJsonObject());
    }

    public void createMessageStream(
        @NotNull JsonObject body,
        @NotNull AIEngineResponseConsumer listener
    ) throws DBException {
        body.addProperty("stream", true);
        HttpRequest request = newRequestBuilder()
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .uri(AIHttpUtils.resolve(baseUrl, "v1/messages")) //$NON-NLS-1$
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .timeout(timeout)
            .build();
        StreamState state = new StreamState(listener);
        client.sendAsync(request, state::onLine, listener::error, listener::completeBlock);
    }

    /**
     * Request body of {@code POST /v1/messages}.
     */
    @NotNull
    public static JsonObject buildRequest(
        @NotNull List<AIMessage> messages,
        @Nullable List<AIFunctionDescriptor> functions,
        @NotNull String model,
        int maxTokens,
        @Nullable Double temperature
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        if (temperature != null) {
            body.addProperty("temperature", temperature);
        }
        StringBuilder system = new StringBuilder();
        JsonArray apiMessages = new JsonArray();
        for (AIMessage message : messages) {
            AIFunctionCall call = message.getFunctionCall();
            if (call != null) {
                String toolUseId = getToolUseId(call);
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use");
                toolUse.addProperty("id", toolUseId);
                toolUse.addProperty("name", call.getFunctionName());
                toolUse.add("input", GSON.toJsonTree(call.getArguments()));
                addBlock(apiMessages, "assistant", toolUse);
                if (message.getRole() == AIMessageType.FUNCTION) {
                    JsonObject toolResult = new JsonObject();
                    toolResult.addProperty("type", "tool_result");
                    toolResult.addProperty(TOOL_USE_ID, toolUseId);
                    toolResult.addProperty("content", CommonUtils.notEmpty(message.getContent()));
                    if (message.getFunctionResult() != null && message.getFunctionResult().getException() != null) {
                        toolResult.addProperty("is_error", true);
                    }
                    addBlock(apiMessages, "user", toolResult);
                }
                continue;
            }
            switch (message.getRole()) {
                case SYSTEM -> {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(message.getContent());
                }
                case USER, ASSISTANT -> {
                    String role = message.getRole() == AIMessageType.USER ? "user" : "assistant";
                    for (AIImageAttachment image : message.getImages()) {
                        JsonObject source = new JsonObject();
                        source.addProperty("type", "base64");
                        source.addProperty("media_type", image.mediaType());
                        source.addProperty("data", image.data());
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "image");
                        block.add("source", source);
                        addBlock(apiMessages, role, block);
                    }
                    if (!CommonUtils.isEmpty(message.getContent())) {
                        JsonObject text = new JsonObject();
                        text.addProperty("type", "text");
                        text.addProperty("text", message.getContent());
                        addBlock(apiMessages, role, text);
                    }
                }
                default -> {
                    // Local messages (warnings, errors, confirmations) are never sent
                }
            }
        }
        if (!system.isEmpty()) {
            body.addProperty("system", system.toString());
        }
        body.add("messages", apiMessages);
        if (!CommonUtils.isEmpty(functions)) {
            JsonArray tools = new JsonArray();
            for (AIFunctionDescriptor function : functions) {
                tools.add(toTool(function));
            }
            body.add("tools", tools);
        }
        return body;
    }

    /**
     * One {@code tools[]} entry: name, description and the JSON schema of the parameters.
     */
    @NotNull
    public static JsonObject toTool(@NotNull AIFunctionDescriptor function) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (AIFunctionParameter parameter : function.getParameters()) {
            JsonObject property = new JsonObject();
            property.addProperty("type", parameter.getType());
            if (parameter.getDescription() != null) {
                property.addProperty("description", parameter.getDescription());
            }
            String[] validValues = parameter.getValidValues();
            if (validValues != null && validValues.length > 0) {
                JsonArray values = new JsonArray();
                for (String value : validValues) {
                    values.add(value);
                }
                property.add("enum", values);
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
        JsonObject tool = new JsonObject();
        tool.addProperty("name", function.getFullId());
        tool.addProperty("description", CommonUtils.notEmpty(function.getAiDescription()));
        tool.add("input_schema", schema);
        return tool;
    }

    /**
     * A non-streaming response: the first tool use if there is one (the chat runs one call per turn, as with Ollama),
     * otherwise the text.
     */
    @NotNull
    public static AIEngineResponse parseResponse(@NotNull JsonObject json) {
        AIUsage usage = parseUsage(json.getAsJsonObject("usage"));
        StringBuilder text = new StringBuilder();
        JsonArray content = json.getAsJsonArray("content");
        if (content != null) {
            for (JsonElement element : content) {
                JsonObject block = element.getAsJsonObject();
                String type = block.get("type").getAsString();
                if ("tool_use".equals(type)) {
                    return new AIEngineResponse(toFunctionCall(
                        block.get("id").getAsString(),
                        block.get("name").getAsString(),
                        block.getAsJsonObject("input")
                    ), usage);
                }
                if ("text".equals(type)) {
                    text.append(block.get("text").getAsString());
                }
            }
        }
        return new AIEngineResponse(AIMessageType.ASSISTANT, List.of(text.toString()), usage);
    }

    @NotNull
    static AIFunctionCall toFunctionCall(@NotNull String id, @NotNull String name, @Nullable JsonObject input) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, JsonElement> e : input.entrySet()) {
                arguments.put(e.getKey(), toJava(e.getValue()));
            }
        }
        return new AIFunctionCall(name, arguments, Map.of(TOOL_USE_ID, id));
    }

    @Nullable
    private static Object toJava(@NotNull JsonElement value) {
        if (value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            if (value.getAsJsonPrimitive().isNumber()) {
                Number number = value.getAsNumber();
                double d = number.doubleValue();
                return d == Math.rint(d) && Math.abs(d) < Long.MAX_VALUE ? (Object) number.longValue() : (Object) d;
            }
            return value.getAsString();
        }
        // Nested objects and arrays stay JSON text; tools parse them (e.g. rows of insertRows)
        return GSON.toJson(value);
    }

    @Nullable
    private static AIUsage parseUsage(@Nullable JsonObject usage) {
        if (usage == null) {
            return null;
        }
        return new AIUsage(
            getInt(usage, "input_tokens"),
            getInt(usage, "cache_read_input_tokens"),
            getInt(usage, "output_tokens"),
            0
        );
    }

    private static int getInt(@NotNull JsonObject object, @NotNull String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    @NotNull
    private static String getToolUseId(@NotNull AIFunctionCall call) {
        Map<String, String> metadata = call.getMessageMetadata();
        String id = metadata == null ? null : metadata.get(TOOL_USE_ID);
        // Calls made by another engine earlier in the chat have no Anthropic ID; any unique ID pairs use and result
        return CommonUtils.isEmpty(id) ? "toolu_" + call.getId().toString().replace("-", "") : id; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addBlock(@NotNull JsonArray messages, @NotNull String role, @NotNull JsonObject block) {
        JsonObject last = messages.isEmpty() ? null : messages.get(messages.size() - 1).getAsJsonObject();
        if (last != null && role.equals(last.get("role").getAsString())) {
            last.getAsJsonArray("content").add(block);
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        JsonArray content = new JsonArray();
        content.add(block);
        message.add("content", content);
        messages.add(message);
    }

    @NotNull
    private HttpRequest.Builder newRequestBuilder() {
        return HttpRequest.newBuilder()
            .header(HEADER_API_KEY, apiKey)
            .header(HEADER_API_VERSION, API_VERSION);
    }

    @NotNull
    @Override
    protected DBException mapHttpError(int statusCode, @NotNull String body) {
        log.debug("Anthropic request failed: " + statusCode + ", " + body);
        return new DBException("Anthropic request failed: " + AIHttpUtils.parseOpenAIStyleErrorMessage(statusCode, body));
    }

    /**
     * Server-sent events of one streamed message.
     */
    static final class StreamState {
        private final AIEngineResponseConsumer listener;
        private String toolId;
        private String toolName;
        private final StringBuilder toolInput = new StringBuilder();
        private int inputTokens;
        private int cachedTokens;

        StreamState(@NotNull AIEngineResponseConsumer listener) {
            this.listener = listener;
        }

        void onLine(@NotNull String line) {
            if (!line.startsWith("data:")) {
                return;
            }
            try {
                JsonObject event = JsonParser.parseString(line.substring(5).trim()).getAsJsonObject();
                String type = event.get("type").getAsString();
                switch (type) {
                    case "message_start" -> {
                        JsonObject usage = event.getAsJsonObject("message").getAsJsonObject("usage");
                        if (usage != null) {
                            inputTokens = getInt(usage, "input_tokens");
                            cachedTokens = getInt(usage, "cache_read_input_tokens");
                        }
                    }
                    case "content_block_start" -> {
                        JsonObject block = event.getAsJsonObject("content_block");
                        if ("tool_use".equals(block.get("type").getAsString())) {
                            toolId = block.get("id").getAsString();
                            toolName = block.get("name").getAsString();
                            toolInput.setLength(0);
                        }
                    }
                    case "content_block_delta" -> {
                        JsonObject delta = event.getAsJsonObject("delta");
                        String deltaType = delta.get("type").getAsString();
                        if ("text_delta".equals(deltaType)) {
                            listener.nextChunk(new AIEngineResponseChunk(List.of(delta.get("text").getAsString())));
                        } else if ("input_json_delta".equals(deltaType)) {
                            toolInput.append(delta.get("partial_json").getAsString());
                        }
                    }
                    case "content_block_stop" -> {
                        if (toolId != null) {
                            JsonObject input = toolInput.isEmpty()
                                ? new JsonObject()
                                : JsonParser.parseString(toolInput.toString()).getAsJsonObject();
                            listener.nextChunk(new AIEngineResponseChunk(toFunctionCall(toolId, toolName, input)));
                            toolId = null;
                            toolName = null;
                        }
                    }
                    case "message_delta" -> {
                        JsonObject usage = event.getAsJsonObject("usage");
                        if (usage != null) {
                            listener.usage(new AIUsage(inputTokens, cachedTokens, getInt(usage, "output_tokens"), 0));
                        }
                    }
                    case "error" -> {
                        JsonObject error = event.getAsJsonObject("error");
                        listener.error(new DBException("Anthropic error: " +
                            (error == null ? line : error.get("message").getAsString())));
                    }
                    default -> {
                        // ping, message_stop
                    }
                }
            } catch (RuntimeException e) {
                listener.error(e);
            }
        }
    }
}
