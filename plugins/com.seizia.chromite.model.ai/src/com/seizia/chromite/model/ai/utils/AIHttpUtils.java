/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.ai.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.json.JSONUtils;
import org.jkiss.utils.CommonUtils;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP helpers of the AI engines: URL resolution and error texts.
 *
 * 설계: 엔진마다 HTTP 오류를 이 클래스의 {@link #parseOpenAIStyleErrorMessage}로 글로 바꾼다. 화면에는 이 글만 보이므로, 응답 본문의 문구가
 * 무엇이 잘못됐는지 말하지 않으면(모르는 모델의 404) 상태의 뜻을 덧붙인다.
 *
 * 변경 이력:
 * - 2026-11-08 404 응답의 글에 "not found"가 없으면 덧붙임: 없는 모델 이름의 오류가 "model: claude-x"로만 보였다
 *   (docs/chromite/ai-agent.md "실제 API 확인", 후속 39)
 */
public final class AIHttpUtils {
    private static final Log log = Log.getLog(AIHttpUtils.class);

    private static final int MAX_ERROR_TEXT_LENGTH = 300;
    private static final Pattern HTML_TITLE_PATTERN =
        Pattern.compile("<title>\\s*(.*?)\\s*</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AIHttpUtils() {
    }

    /**
     * Resolves URI from base and paths
     */
    public static URI resolve(String base, String... paths) throws DBException {
        try {
            // RFC 3986 relative-reference resolution treats the segment after the last "/" in
            // the base as a file name, not a directory, so resolving a relative path against a
            // base without a trailing slash replaces that last segment instead of appending to
            // it - e.g. "http://host/v1".resolve("models") -> "http://host/models", silently
            // dropping "/v1". Self-hosted OpenAI-compatible endpoints (Ollama, vLLM, LM Studio,
            // ...) are commonly configured without the trailing slash, which turns every request
            // into a 404. Only normalize when there is a relative path to resolve against -
            // callers that pass an already-complete URL as `base` with no extra `paths` rely on
            // it being used exactly as given.
            String normalizedBase = (paths.length > 0 && !base.endsWith("/")) ? base + "/" : base;
            URI uri = new URI(normalizedBase);
            for (String path : paths) {
                uri = uri.resolve(path);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new DBException("Incorrect URI", e);
        }
    }

    /**
     * Parses an OpenAI-style error message from a JSON string.
     * Extracts the "message" field from the "error" or root object of the JSON structure.
     * If the parsing fails or no suitable message field is found, a short status description is returned (the HTTP status
     * with the page title or the start of the body).
     * A "not found" response (HTTP 404) says so when its message does not: providers answer an unknown model with only the
     * model name (Anthropic {@code "model: claude-x"}) or with "does not exist" (OpenAI).
     *
     * @param statusCode HTTP status code of the failed response, 0 if unknown
     * @param body       the response body, expected to be an OpenAI-style JSON error
     * @return the extracted error message if present, otherwise a short status description
     */
    @NotNull
    public static String parseOpenAIStyleErrorMessage(int statusCode, @NotNull String body) {
        String message = parseErrorMessage(statusCode, body);
        if (statusCode == HttpURLConnection.HTTP_NOT_FOUND && !message.toLowerCase(Locale.ROOT).contains("not found")) {
            return message + " (not found, HTTP 404)";
        }
        return message;
    }

    @NotNull
    private static String parseErrorMessage(int statusCode, @NotNull String body) {
        try {
            JsonElement errorResponse = JSONUtils.GSON.fromJson(body, JsonElement.class);
            if (errorResponse != null && errorResponse.isJsonObject()) {
                if (errorResponse.getAsJsonObject().has("error")) {
                    JsonElement errorElement = errorResponse.getAsJsonObject().get("error");
                    if (errorElement.isJsonObject() && errorElement.getAsJsonObject().has("message")) {
                        JsonElement messageElement = errorElement.getAsJsonObject().get("message");
                        if (messageElement.isJsonPrimitive() && messageElement.getAsJsonPrimitive().isString()) {
                            return messageElement.getAsString();
                        }
                    }
                }
                if (errorResponse.getAsJsonObject().has("message")) {
                    JsonElement messageElement = errorResponse.getAsJsonObject().get("message");
                    if (messageElement.isJsonPrimitive() && messageElement.getAsJsonPrimitive().isString()) {
                        return messageElement.getAsString();
                    }
                }
                if (errorResponse.getAsJsonObject().has("detail")) {
                    JsonElement detailElement = errorResponse.getAsJsonObject().get("detail");
                    if (detailElement.isJsonPrimitive() && detailElement.getAsJsonPrimitive().isString()) {
                        return detailElement.getAsString();
                    }
                }
            }
        } catch (JsonSyntaxException e) {
            log.debug("Failed to parse error response: " + e.getMessage());
        }
        return describeNonJsonError(statusCode, body);
    }

    @NotNull
    private static String describeNonJsonError(int statusCode, @NotNull String body) {
        String status = statusCode > 0 ? "HTTP " + statusCode : "Unexpected server response";
        String text = body.trim();
        Matcher title = HTML_TITLE_PATTERN.matcher(text);
        if (title.find()) {
            text = title.group(1).replaceAll("&[#a-zA-Z0-9]+;", " ");
        } else if (text.startsWith("<")) {
            text = "";
        }
        text = CommonUtils.truncateString(text.replaceAll("\\s+", " ").trim(), MAX_ERROR_TEXT_LENGTH);
        return CommonUtils.isEmpty(text) ? status : status + " (" + text + ")";
    }
}
