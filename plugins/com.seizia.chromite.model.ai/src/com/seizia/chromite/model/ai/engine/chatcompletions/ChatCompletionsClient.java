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
package com.seizia.chromite.model.ai.engine.chatcompletions;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;

import java.net.HttpURLConnection;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP client of the OpenAI-compatible Chat Completions API ({@code POST chat/completions}, {@code GET models}) with streaming and
 * tool calls.
 *
 * 설계:
 * <ul>
 *     <li>OpenAI 엔진은 Responses API({@code /v1/responses})를 쓰지만 vLLM은 Chat Completions가 기본이고 Responses 지원은 버전마다 다르다.
 *     Gemini의 OpenAI 호환 엔드포인트도 Chat Completions다. 그래서 OpenAI 엔진을 고치지 않고 이 클라이언트를 따로 두었다
 *     (docs/chromite/ai-agent.md "Chat Completions 엔진").</li>
 *     <li>요청·응답은 Anthropic 클라이언트처럼 {@link JsonObject}로 다루고, 요청 조립({@link #buildRequest})과 스트림 해석({@link StreamState})은
 *     네트워크 없이 시험할 수 있게 나눴다.</li>
 *     <li>도구 호출: 응답의 {@code tool_calls}를 {@link AIFunctionCall}로 바꾸고 {@code id}는 메타데이터({@value #TOOL_CALL_ID})에 두어 다음 요청의
 *     assistant {@code tool_calls}와 {@code tool} 메시지의 {@code tool_call_id}에 그대로 쓴다. 도구 호출에 붙어 온 {@code extra_content}
 *     (Gemini 3의 thought signature)도 메타데이터에 JSON 글로 두었다가 그대로 돌려보낸다. 없으면 Gemini 3가 다음 차례를 거부한다.</li>
 *     <li>스트리밍 도구 호출은 조각({@code index}, {@code id}, {@code function.arguments} 일부)으로 온다. {@code index}가 없거나 같은 {@code index}에
 *     다른 {@code id}가 오는 서버(호환 계층)도 있어, 새 {@code id}가 오면 새 호출로 본다. 응답이 끝날 때 모은 호출을 넘긴다.</li>
 *     <li>속성 없는 {@code object} 매개변수(예 runLibraryQuery의 {@code parameters})는 JSON 글을 받는 {@code string}으로 보낸다. Gemini는 속성 없는
 *     OBJECT 스키마를 거부하고, 도구는 JSON 글을 받는다({@code AgentArgs.getJson}, Anthropic 엔진도 중첩 값을 JSON 글로 넘김).</li>
 *     <li>도구 호출을 받지 않는 서버(vLLM을 {@code --enable-auto-tool-choice} 없이 띄운 경우)는 400으로 거부한다. 그 응답을
 *     "이 서버는 도구 호출을 지원하지 않음"과 제공자의 안내({@link ChatCompletionsProperties#getToolCallHint})로 바꾼다({@link #mapHttpError}).
 *     요청에 {@code tool_choice: auto}를 늘 넣어 서버 기본값에 따라 오류가 숨지 않게 했다.</li>
 *     <li>temperature는 값이 있을 때만 넣고({@link ChatCompletionsProperties#getRequestTemperature}), {@code max_tokens}는 넣지 않는다(서버 기본값).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: vLLM·Google Gemini 제공자 (app round 2 #34, #35)
 * - 2026-11-12 모델 목록에서 vLLM의 {@code max_model_len}(모델의 컨텍스트 크기)도 읽음 (앱 변경 #64)
 */
public class ChatCompletionsClient extends AbstractHttpAIClient {

    private static final Log log = Log.getLog(ChatCompletionsClient.class);
    private static final Gson GSON = new Gson();

    public static final String TOOL_CALL_ID = "tool_call_id"; //$NON-NLS-1$
    public static final String EXTRA_CONTENT = "extra_content"; //$NON-NLS-1$

    private static final String DATA_PREFIX = "data:"; //$NON-NLS-1$
    private static final String DONE = "[DONE]"; //$NON-NLS-1$
    private static final String TYPE_FUNCTION = "function"; //$NON-NLS-1$
    // Texts of servers that reject tool calls: vLLM ("auto" tool choice requires --enable-auto-tool-choice and --tool-call-parser
    // to be set) and generic "tools are not supported" answers
    private static final String[] TOOLS_UNSUPPORTED_MARKERS = {
        "enable-auto-tool-choice", "tool-call-parser", "tools are not supported", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "does not support tools", "tool calling is not supported", "function calling is not supported" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    private final String baseUrl;
    @Nullable
    private final String apiKey;
    private final String providerName;
    @Nullable
    private final String toolCallHint;

    public ChatCompletionsClient(
        @NotNull String baseUrl,
        @Nullable String apiKey,
        @NotNull String providerName,
        @Nullable String toolCallHint
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = CommonUtils.isEmptyTrimmed(apiKey) ? null : apiKey;
        this.providerName = providerName;
        this.toolCallHint = toolCallHint;
    }

    /**
     * A model of {@code GET models}.
     *
     * @param contextSize the model's context window in tokens when the server tells it (vLLM {@code max_model_len}), or null
     */
    public record ModelInfo(@NotNull String id, @Nullable Integer contextSize) {
    }

    /**
     * Models from {@code GET models}.
     */
    @NotNull
    public List<ModelInfo> loadModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        HttpRequest request = newRequestBuilder()
            .uri(AIHttpUtils.resolve(baseUrl, "models")) //$NON-NLS-1$
            .GET()
            .timeout(timeout)
            .build();
        JsonElement json = JsonParser.parseString(client.send(monitor, request));
        List<ModelInfo> models = new ArrayList<>();
        JsonArray data = json.isJsonObject() ? json.getAsJsonObject().getAsJsonArray("data") : null; //$NON-NLS-1$
        if (data != null) {
            for (JsonElement model : data) {
                JsonObject object = model.getAsJsonObject();
                JsonElement id = object.get("id"); //$NON-NLS-1$
                if (id != null && !id.isJsonNull()) {
                    int contextSize = getInt(object, "max_model_len"); //$NON-NLS-1$
                    models.add(new ModelInfo(id.getAsString(), contextSize > 0 ? contextSize : null));
                }
            }
        }
        return models;
    }

    @NotNull
    public AIEngineResponse createCompletion(@NotNull DBRProgressMonitor monitor, @NotNull JsonObject body) throws DBException {
        String response = client.send(monitor, newPostRequest(body));
        return parseResponse(JsonParser.parseString(response).getAsJsonObject());
    }

    public void createCompletionStream(@NotNull JsonObject body, @NotNull AIEngineResponseConsumer listener) throws DBException {
        body.addProperty("stream", true); //$NON-NLS-1$
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true); //$NON-NLS-1$
        body.add("stream_options", streamOptions); //$NON-NLS-1$
        StreamState state = new StreamState(listener, providerName);
        client.sendAsync(newPostRequest(body), state::onLine, state::onError, state::onComplete);
    }

    /**
     * Request body of {@code POST chat/completions} (without the streaming fields).
     */
    @NotNull
    public static JsonObject buildRequest(
        @NotNull List<AIMessage> messages,
        @Nullable List<AIFunctionDescriptor> functions,
        @NotNull String model,
        @Nullable Double temperature
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model); //$NON-NLS-1$
        if (temperature != null) {
            body.addProperty("temperature", temperature); //$NON-NLS-1$
        }
        // Chat templates of many self-hosted models take one system message, first
        StringBuilder system = new StringBuilder();
        JsonArray apiMessages = new JsonArray();
        for (AIMessage message : messages) {
            AIFunctionCall call = message.getFunctionCall();
            if (call != null) {
                String callId = getToolCallId(call);
                JsonObject assistant = new JsonObject();
                assistant.addProperty("role", "assistant"); //$NON-NLS-1$ //$NON-NLS-2$
                JsonArray toolCalls = new JsonArray();
                toolCalls.add(toToolCall(call, callId));
                assistant.add("tool_calls", toolCalls); //$NON-NLS-1$
                apiMessages.add(assistant);
                if (message.getRole() == AIMessageType.FUNCTION) {
                    JsonObject result = new JsonObject();
                    result.addProperty("role", "tool"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty(TOOL_CALL_ID, callId);
                    // The name is optional in OpenAI's format; Gemini's endpoint uses it for the function response
                    result.addProperty("name", call.getFunctionName()); //$NON-NLS-1$
                    result.addProperty("content", CommonUtils.notEmpty(message.getContent())); //$NON-NLS-1$
                    apiMessages.add(result);
                }
                continue;
            }
            switch (message.getRole()) {
                case SYSTEM -> {
                    if (!system.isEmpty()) {
                        system.append("\n\n"); //$NON-NLS-1$
                    }
                    system.append(message.getContent());
                }
                case USER -> apiMessages.add(toUserMessage(message));
                case ASSISTANT -> {
                    if (!CommonUtils.isEmpty(message.getContent())) {
                        JsonObject assistant = new JsonObject();
                        assistant.addProperty("role", "assistant"); //$NON-NLS-1$ //$NON-NLS-2$
                        assistant.addProperty("content", message.getContent()); //$NON-NLS-1$
                        apiMessages.add(assistant);
                    }
                }
                default -> {
                    // Local messages (warnings, errors, confirmations) are never sent
                }
            }
        }
        if (!system.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system"); //$NON-NLS-1$ //$NON-NLS-2$
            systemMessage.addProperty("content", system.toString()); //$NON-NLS-1$
            JsonArray withSystem = new JsonArray();
            withSystem.add(systemMessage);
            withSystem.addAll(apiMessages);
            apiMessages = withSystem;
        }
        body.add("messages", apiMessages); //$NON-NLS-1$
        if (!CommonUtils.isEmpty(functions)) {
            JsonArray tools = new JsonArray();
            for (AIFunctionDescriptor function : functions) {
                tools.add(toTool(function));
            }
            body.add("tools", tools); //$NON-NLS-1$
            body.addProperty("tool_choice", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return body;
    }

    @NotNull
    private static JsonObject toUserMessage(@NotNull AIMessage message) {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user"); //$NON-NLS-1$ //$NON-NLS-2$
        if (message.getImages().isEmpty()) {
            user.addProperty("content", CommonUtils.notEmpty(message.getContent())); //$NON-NLS-1$
            return user;
        }
        JsonArray content = new JsonArray();
        if (!CommonUtils.isEmpty(message.getContent())) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
            text.addProperty("text", message.getContent()); //$NON-NLS-1$
            content.add(text);
        }
        for (AIImageAttachment image : message.getImages()) {
            JsonObject url = new JsonObject();
            url.addProperty("url", "data:" + image.mediaType() + ";base64," + image.data()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            JsonObject part = new JsonObject();
            part.addProperty("type", "image_url"); //$NON-NLS-1$ //$NON-NLS-2$
            part.add("image_url", url); //$NON-NLS-1$
            content.add(part);
        }
        user.add("content", content); //$NON-NLS-1$
        return user;
    }

    @NotNull
    private static JsonObject toToolCall(@NotNull AIFunctionCall call, @NotNull String callId) {
        JsonObject function = new JsonObject();
        function.addProperty("name", call.getFunctionName()); //$NON-NLS-1$
        function.addProperty("arguments", GSON.toJson(call.getArguments())); //$NON-NLS-1$
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", callId); //$NON-NLS-1$
        toolCall.addProperty("type", TYPE_FUNCTION); //$NON-NLS-1$
        toolCall.add(TYPE_FUNCTION, function);
        Map<String, String> metadata = call.getMessageMetadata();
        String extra = metadata == null ? null : metadata.get(EXTRA_CONTENT);
        if (!CommonUtils.isEmpty(extra)) {
            try {
                toolCall.add(EXTRA_CONTENT, JsonParser.parseString(extra));
            } catch (RuntimeException e) {
                log.debug("Tool call extra content is not JSON, not sent", e);
            }
        }
        return toolCall;
    }

    /**
     * One {@code tools[]} entry: {@code {type: function, function: {name, description, parameters}}}.
     */
    @NotNull
    public static JsonObject toTool(@NotNull AIFunctionDescriptor function) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (AIFunctionParameter parameter : function.getParameters()) {
            JsonObject property = new JsonObject();
            String description = parameter.getDescription();
            if ("object".equals(parameter.getType())) { //$NON-NLS-1$
                // No property list: JSON text (see the class comment)
                property.addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
                description = (description == null ? "" : description + " ") + "(JSON object text)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } else {
                property.addProperty("type", parameter.getType()); //$NON-NLS-1$
            }
            if (description != null) {
                property.addProperty("description", description); //$NON-NLS-1$
            }
            String[] validValues = parameter.getValidValues();
            if (validValues != null && validValues.length > 0) {
                JsonArray values = new JsonArray();
                for (String value : validValues) {
                    values.add(value);
                }
                property.add("enum", values); //$NON-NLS-1$
            }
            properties.add(parameter.getName(), property);
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.add("properties", properties); //$NON-NLS-1$
        if (!required.isEmpty()) {
            schema.add("required", required); //$NON-NLS-1$
        }
        JsonObject definition = new JsonObject();
        definition.addProperty("name", function.getFullId()); //$NON-NLS-1$
        definition.addProperty("description", CommonUtils.notEmpty(function.getAiDescription())); //$NON-NLS-1$
        definition.add("parameters", schema); //$NON-NLS-1$
        JsonObject tool = new JsonObject();
        tool.addProperty("type", TYPE_FUNCTION); //$NON-NLS-1$
        tool.add(TYPE_FUNCTION, definition);
        return tool;
    }

    /**
     * A non-streaming response: the first tool call if there is one (as the Anthropic engine), otherwise the text.
     */
    @NotNull
    public static AIEngineResponse parseResponse(@NotNull JsonObject json) throws DBException {
        AIUsage usage = parseUsage(json.get("usage")); //$NON-NLS-1$
        JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$
        if (choices == null || choices.isEmpty()) {
            return new AIEngineResponse(AIMessageType.ASSISTANT, List.of(""), usage); //$NON-NLS-1$
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message"); //$NON-NLS-1$
        JsonArray toolCalls = message == null ? null : getArray(message, "tool_calls"); //$NON-NLS-1$
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JsonObject toolCall = toolCalls.get(0).getAsJsonObject();
            JsonObject function = toolCall.getAsJsonObject(TYPE_FUNCTION);
            return new AIEngineResponse(toFunctionCall(
                getString(toolCall, "id"), //$NON-NLS-1$
                getString(function, "name"), //$NON-NLS-1$
                getString(function, "arguments"), //$NON-NLS-1$
                toolCall.get(EXTRA_CONTENT)
            ), usage);
        }
        String text = message == null ? null : getString(message, "content"); //$NON-NLS-1$
        return new AIEngineResponse(AIMessageType.ASSISTANT, List.of(CommonUtils.notEmpty(text)), usage);
    }

    /**
     * A tool call of the response; the arguments are JSON text.
     */
    @NotNull
    static AIFunctionCall toFunctionCall(
        @Nullable String id,
        @Nullable String name,
        @Nullable String argumentsJson,
        @Nullable JsonElement extraContent
    ) throws DBException {
        if (CommonUtils.isEmpty(name)) {
            throw new DBException("The model returned a tool call without a function name");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (!CommonUtils.isEmptyTrimmed(argumentsJson)) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(argumentsJson);
            } catch (RuntimeException e) {
                throw new DBException("The model returned arguments of tool '" + name + "' that are not JSON: " +
                    CommonUtils.truncateString(argumentsJson, 200));
            }
            if (!parsed.isJsonObject()) {
                throw new DBException("The model returned arguments of tool '" + name + "' that are not a JSON object");
            }
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                arguments.put(e.getKey(), toJava(e.getValue()));
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (!CommonUtils.isEmpty(id)) {
            metadata.put(TOOL_CALL_ID, id);
        }
        if (extraContent != null && !extraContent.isJsonNull()) {
            metadata.put(EXTRA_CONTENT, GSON.toJson(extraContent));
        }
        return new AIFunctionCall(name, arguments, metadata);
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
    private static AIUsage parseUsage(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject usage = element.getAsJsonObject();
        int cached = 0;
        JsonElement details = usage.get("prompt_tokens_details"); //$NON-NLS-1$
        if (details != null && details.isJsonObject()) {
            cached = getInt(details.getAsJsonObject(), "cached_tokens"); //$NON-NLS-1$
        }
        return new AIUsage(getInt(usage, "prompt_tokens"), cached, getInt(usage, "completion_tokens"), 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int getInt(@NotNull JsonObject object, @NotNull String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? 0 : value.getAsInt();
    }

    @Nullable
    private static String getString(@Nullable JsonObject object, @NotNull String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    @Nullable
    private static JsonArray getArray(@NotNull JsonObject object, @NotNull String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonArray() ? null : value.getAsJsonArray();
    }

    @NotNull
    private static String getToolCallId(@NotNull AIFunctionCall call) {
        Map<String, String> metadata = call.getMessageMetadata();
        String id = metadata == null ? null : metadata.get(TOOL_CALL_ID);
        // Calls made by another engine earlier in the chat (or returned without an ID) get one that pairs call and result
        return CommonUtils.isEmpty(id) ? "call_" + call.getId().toString().replace("-", "") : id; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    private HttpRequest newPostRequest(@NotNull JsonObject body) throws DBException {
        return newRequestBuilder()
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .uri(AIHttpUtils.resolve(baseUrl, "chat/completions")) //$NON-NLS-1$
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .timeout(timeout)
            .build();
    }

    @NotNull
    private HttpRequest.Builder newRequestBuilder() {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (apiKey != null) {
            builder.header(HttpConstants.HEADER_AUTHORIZATION, "Bearer " + apiKey); //$NON-NLS-1$
        }
        return builder;
    }

    @NotNull
    @Override
    protected DBException mapHttpError(int statusCode, @NotNull String body) {
        log.debug(providerName + " request failed: " + statusCode + ", " + body);
        String message = AIHttpUtils.parseOpenAIStyleErrorMessage(statusCode, body);
        if (isToolsUnsupported(statusCode, body)) {
            return new ToolCallsUnsupportedException(describeToolsUnsupported(providerName, toolCallHint, message));
        }
        return new DBException(providerName + " request failed: " + message);
    }

    /**
     * Whether an error response says that the server does not take tool calls.
     */
    static boolean isToolsUnsupported(int statusCode, @NotNull String body) {
        if (statusCode != HttpURLConnection.HTTP_BAD_REQUEST) {
            return false;
        }
        String text = body.toLowerCase(Locale.ROOT);
        for (String marker : TOOLS_UNSUPPORTED_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    static String describeToolsUnsupported(@NotNull String providerName, @Nullable String hint, @NotNull String serverMessage) {
        StringBuilder text = new StringBuilder();
        text.append("This ").append(providerName).append(" server does not support tool calls, which Chromite Agent needs to work with ")
            .append("databases.");
        if (hint != null) {
            text.append(' ').append(hint).append('.');
        }
        text.append(" Server message: ").append(serverMessage);
        return text.toString();
    }

    /**
     * Server-sent events of one streamed completion.
     */
    static final class StreamState {
        private final AIEngineResponseConsumer listener;
        private final String providerName;
        private final List<ToolCallBuilder> toolCalls = new ArrayList<>();
        private final Map<Integer, ToolCallBuilder> toolCallsByIndex = new LinkedHashMap<>();
        private boolean failed;

        StreamState(@NotNull AIEngineResponseConsumer listener, @NotNull String providerName) {
            this.listener = listener;
            this.providerName = providerName;
        }

        void onLine(@NotNull String line) {
            if (failed || !line.startsWith(DATA_PREFIX)) {
                return;
            }
            String data = line.substring(DATA_PREFIX.length()).trim();
            if (data.isEmpty() || DONE.equals(data)) {
                return;
            }
            try {
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                JsonElement error = chunk.get("error"); //$NON-NLS-1$
                if (error != null && !error.isJsonNull()) {
                    fail(new DBException(providerName + " error: " + describeError(error)));
                    return;
                }
                AIUsage usage = parseUsage(chunk.get("usage")); //$NON-NLS-1$
                if (usage != null) {
                    listener.usage(usage);
                }
                JsonArray choices = getArray(chunk, "choices"); //$NON-NLS-1$
                if (choices == null || choices.isEmpty()) {
                    return;
                }
                JsonElement delta = choices.get(0).getAsJsonObject().get("delta"); //$NON-NLS-1$
                if (delta == null || !delta.isJsonObject()) {
                    return;
                }
                String content = getString(delta.getAsJsonObject(), "content"); //$NON-NLS-1$
                if (!CommonUtils.isEmpty(content)) {
                    listener.nextChunk(new AIEngineResponseChunk(List.of(content)));
                }
                JsonArray deltaCalls = getArray(delta.getAsJsonObject(), "tool_calls"); //$NON-NLS-1$
                if (deltaCalls != null) {
                    for (JsonElement deltaCall : deltaCalls) {
                        addToolCallDelta(deltaCall.getAsJsonObject());
                    }
                }
            } catch (RuntimeException e) {
                fail(e);
            }
        }

        private void addToolCallDelta(@NotNull JsonObject delta) {
            String id = getString(delta, "id"); //$NON-NLS-1$
            JsonElement indexElement = delta.get("index"); //$NON-NLS-1$
            Integer index = indexElement == null || indexElement.isJsonNull() ? null : indexElement.getAsInt();
            ToolCallBuilder builder = index == null ? null : toolCallsByIndex.get(index);
            if (builder == null && index == null && !toolCalls.isEmpty()) {
                builder = toolCalls.getLast();
            }
            // A new ID starts a new call, also when a server repeats the index or sends none
            if (builder == null || (!CommonUtils.isEmpty(id) && !CommonUtils.isEmpty(builder.id) && !id.equals(builder.id))) {
                builder = new ToolCallBuilder();
                toolCalls.add(builder);
                if (index != null) {
                    toolCallsByIndex.put(index, builder);
                }
            }
            if (!CommonUtils.isEmpty(id)) {
                builder.id = id;
            }
            JsonElement function = delta.get(TYPE_FUNCTION);
            if (function != null && function.isJsonObject()) {
                String name = getString(function.getAsJsonObject(), "name"); //$NON-NLS-1$
                if (!CommonUtils.isEmpty(name)) {
                    builder.name = name;
                }
                String arguments = getString(function.getAsJsonObject(), "arguments"); //$NON-NLS-1$
                if (arguments != null) {
                    builder.arguments.append(arguments);
                }
            }
            JsonElement extra = delta.get(EXTRA_CONTENT);
            if (extra != null && !extra.isJsonNull()) {
                builder.extraContent = extra;
            }
        }

        void onError(@NotNull Throwable error) {
            fail(error);
        }

        /**
         * End of the response: the collected tool calls go to the listener before the completion.
         */
        void onComplete() {
            if (!failed) {
                try {
                    for (ToolCallBuilder builder : toolCalls) {
                        listener.nextChunk(new AIEngineResponseChunk(
                            toFunctionCall(builder.id, builder.name, builder.arguments.toString(), builder.extraContent)));
                    }
                } catch (DBException | RuntimeException e) {
                    fail(e);
                }
            }
            listener.completeBlock();
        }

        private void fail(@NotNull Throwable error) {
            if (!failed) {
                failed = true;
                listener.error(error);
            }
        }

        @NotNull
        private static String describeError(@NotNull JsonElement error) {
            if (error.isJsonObject()) {
                String message = getString(error.getAsJsonObject(), "message"); //$NON-NLS-1$
                if (message != null) {
                    return message;
                }
            }
            return error.isJsonPrimitive() ? error.getAsString() : GSON.toJson(error);
        }
    }

    /**
     * The server rejected a request because it does not take tool calls.
     */
    public static final class ToolCallsUnsupportedException extends DBException {
        ToolCallsUnsupportedException(@NotNull String message) {
            super(message);
        }
    }

    private static final class ToolCallBuilder {
        @Nullable
        private String id;
        @Nullable
        private String name;
        private final StringBuilder arguments = new StringBuilder();
        @Nullable
        private JsonElement extraContent;
    }
}
