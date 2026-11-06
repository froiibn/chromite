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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads tool arguments. Models send numbers as strings and lists as JSON or comma-separated text; all are accepted.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AgentArgs {

    private AgentArgs() {
    }

    @Nullable
    public static String getString(@NotNull Map<String, Object> args, @NotNull String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @NotNull
    public static String require(@NotNull Map<String, Object> args, @NotNull String name) throws AIToolArgumentException {
        String value = getString(args, name);
        if (value == null) {
            throw new AIToolArgumentException("Argument '" + name + "' is required");
        }
        return value;
    }

    public static int getInt(@NotNull Map<String, Object> args, @NotNull String name, int defaultValue) {
        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return (int) Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static boolean getBoolean(@NotNull Map<String, Object> args, @NotNull String name, boolean defaultValue) {
        Object value = args.get(name);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return CommonUtils.toBoolean(value);
    }

    /**
     * A list argument: a JSON array or comma-separated text.
     */
    @NotNull
    public static List<String> getList(@NotNull Map<String, Object> args, @NotNull String name) {
        String text = getString(args, name);
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            try {
                for (JsonElement element : JsonParser.parseString(trimmed).getAsJsonArray()) {
                    result.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
                }
                return result;
            } catch (RuntimeException e) {
                // fall back to commas
            }
        }
        for (String part : trimmed.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    /**
     * A JSON argument (object or array), given as JSON text or already decoded by the MCP layer.
     */
    @Nullable
    public static JsonElement getJson(@NotNull Map<String, Object> args, @NotNull String name) throws AIToolArgumentException {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new AIToolArgumentException("Argument '" + name + "' must be JSON: " + e.getMessage());
        }
    }
}
