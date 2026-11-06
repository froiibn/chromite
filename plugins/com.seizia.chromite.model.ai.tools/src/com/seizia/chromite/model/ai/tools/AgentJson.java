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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

/**
 * JSON results of agent tools: result sets, values and size limits.
 *
 * 설계:
 * <ul>
 *     <li>표는 기본으로 행마다 객체({@code {"열": 값}})다. 모델이 열 이름과 값을 바로 짝지어 읽어 실수가 적다. 많은 행을 보낼 때는
 *     {@code format="arrays"}(열 목록 + 배열 행)로 크기를 줄인다.</li>
 *     <li>값: 수·참거짓은 JSON 수·참거짓, 날짜·시각은 ISO 문자열, 이진 값은 앞부분 16진수, LOB·긴 문자열은 칸 한도에서 자른다.
 *     정밀도를 잃지 않게 {@link BigDecimal}은 그대로 JSON 수로 쓴다.</li>
 *     <li>결과 전체 크기는 대략의 글자 수로 센다(행마다 JSON 문자열 길이). 한도를 넘으면 그 행에서 멈추고 {@code truncated.bytes}를 켠다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AgentJson {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int BINARY_PREVIEW_BYTES = 64;

    private AgentJson() {
    }

    @NotNull
    public static String toText(@NotNull JsonElement json) {
        return GSON.toJson(json);
    }

    /**
     * Reads a result set into {@code {columns, rows, rowCount, hasMore, truncated}}.
     *
     * @param objects rows as objects (true) or arrays (false)
     */
    @NotNull
    public static JsonObject readResultSet(
        @NotNull ResultSet rs,
        @NotNull AgentLimits limits,
        boolean objects
    ) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        String[] names = new String[count];
        int[] types = new int[count];
        JsonArray columns = new JsonArray();
        for (int i = 0; i < count; i++) {
            String label = meta.getColumnLabel(i + 1);
            names[i] = label == null || label.isEmpty() ? "col" + (i + 1) : label;
            types[i] = meta.getColumnType(i + 1);
            if (objects) {
                columns.add(names[i]);
            } else {
                JsonObject column = new JsonObject();
                column.addProperty("name", names[i]);
                column.addProperty("type", meta.getColumnTypeName(i + 1));
                columns.add(column);
            }
        }
        JsonArray rows = new JsonArray();
        int cutCells = 0;
        long size = 0;
        boolean bytesCut = false;
        boolean hasMore = false;
        while (rs.next()) {
            if (rows.size() >= limits.maxRows()) {
                hasMore = true;
                break;
            }
            JsonElement row;
            if (objects) {
                JsonObject object = new JsonObject();
                for (int i = 0; i < count; i++) {
                    Value value = readValue(rs, i + 1, types[i], limits.maxCellChars());
                    cutCells += value.cut ? 1 : 0;
                    // Duplicate labels (two "name" columns) would overwrite each other
                    String key = object.has(names[i]) ? names[i] + "_" + (i + 1) : names[i];
                    object.add(key, value.json);
                }
                row = object;
            } else {
                JsonArray array = new JsonArray();
                for (int i = 0; i < count; i++) {
                    Value value = readValue(rs, i + 1, types[i], limits.maxCellChars());
                    cutCells += value.cut ? 1 : 0;
                    array.add(value.json);
                }
                row = array;
            }
            size += GSON.toJson(row).length();
            if (size > limits.maxBytes()) {
                bytesCut = true;
                hasMore = true;
                break;
            }
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("columns", columns);
        result.add("rows", rows);
        result.addProperty("rowCount", rows.size());
        result.addProperty("hasMore", hasMore);
        if (hasMore || cutCells > 0) {
            JsonObject truncated = new JsonObject();
            truncated.addProperty("rows", hasMore && !bytesCut);
            truncated.addProperty("cells", cutCells);
            truncated.addProperty("bytes", bytesCut);
            result.add("truncated", truncated);
        }
        return result;
    }

    /**
     * One value as JSON, cut at the cell limit.
     */
    record Value(@NotNull JsonElement json, boolean cut) {
    }

    @NotNull
    static Value readValue(@NotNull ResultSet rs, int index, int sqlType, int maxChars) throws SQLException {
        Object value;
        switch (sqlType) {
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
                Reader reader = rs.getCharacterStream(index);
                if (reader == null) {
                    return new Value(JsonNull.INSTANCE, false);
                }
                try (reader) {
                    char[] buffer = new char[maxChars + 1];
                    int read = 0;
                    int n;
                    while (read < buffer.length && (n = reader.read(buffer, read, buffer.length - read)) > 0) {
                        read += n;
                    }
                    boolean cut = read > maxChars;
                    return new Value(new JsonPrimitive(new String(buffer, 0, Math.min(read, maxChars)) + (cut ? "..." : "")), cut);
                } catch (java.io.IOException e) {
                    throw new SQLException("Can't read a text value", e);
                }
            }
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                byte[] bytes = rs.getBytes(index);
                if (bytes == null) {
                    return new Value(JsonNull.INSTANCE, false);
                }
                return binary(bytes);
            }
            default -> value = rs.getObject(index);
        }
        return toJson(value, maxChars);
    }

    @NotNull
    static Value toJson(@Nullable Object value, int maxChars) throws SQLException {
        if (value == null) {
            return new Value(JsonNull.INSTANCE, false);
        }
        if (value instanceof Boolean b) {
            return new Value(new JsonPrimitive(b), false);
        }
        if (value instanceof BigDecimal || value instanceof BigInteger || value instanceof Long || value instanceof Integer ||
            value instanceof Short || value instanceof Byte) {
            return new Value(new JsonPrimitive((Number) value), false);
        }
        if (value instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? new Value(new JsonPrimitive(d.toString()), false) : new Value(new JsonPrimitive(d), false);
        }
        if (value instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? new Value(new JsonPrimitive(f.toString()), false) : new Value(new JsonPrimitive(f), false);
        }
        if (value instanceof byte[] bytes) {
            return binary(bytes);
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            String text = clob.getSubString(1, (int) Math.min(length, maxChars));
            return new Value(new JsonPrimitive(length > maxChars ? text + "..." : text), length > maxChars);
        }
        if (value instanceof Blob blob) {
            return binary(blob.getBytes(1, (int) Math.min(blob.length(), BINARY_PREVIEW_BYTES + 1)));
        }
        if (value instanceof SQLXML xml) {
            return text(xml.getString(), maxChars);
        }
        if (value instanceof Array array) {
            Object[] items = (Object[]) array.getArray();
            JsonArray json = new JsonArray();
            boolean cut = false;
            for (Object item : items) {
                Value v = toJson(item, maxChars);
                cut |= v.cut;
                json.add(v.json);
            }
            return new Value(json, cut);
        }
        if (value instanceof Date || value instanceof TemporalAccessor) {
            return new Value(new JsonPrimitive(value.toString()), false);
        }
        return text(value.toString(), maxChars);
    }

    @NotNull
    private static Value text(@Nullable String text, int maxChars) {
        if (text == null) {
            return new Value(JsonNull.INSTANCE, false);
        }
        if (text.length() > maxChars) {
            return new Value(new JsonPrimitive(text.substring(0, maxChars) + "..."), true);
        }
        return new Value(new JsonPrimitive(text), false);
    }

    @NotNull
    private static Value binary(@NotNull byte[] bytes) {
        boolean cut = bytes.length > BINARY_PREVIEW_BYTES;
        byte[] shown = cut ? java.util.Arrays.copyOf(bytes, BINARY_PREVIEW_BYTES) : bytes;
        return new Value(new JsonPrimitive("0x" + HexFormat.of().formatHex(shown).toUpperCase() + (cut ? "..." : "")), cut);
    }

    /**
     * Lower-cases the column names of a table read with objects=true (columns and row keys).
     */
    public static void lowerCaseColumns(@NotNull JsonObject table) {
        JsonArray columns = table.getAsJsonArray("columns");
        if (columns != null) {
            JsonArray lower = new JsonArray();
            for (JsonElement c : columns) {
                lower.add(c.isJsonPrimitive() ? new JsonPrimitive(c.getAsString().toLowerCase(java.util.Locale.ROOT)) : c);
            }
            table.add("columns", lower);
        }
        JsonArray rows = table.getAsJsonArray("rows");
        if (rows != null) {
            JsonArray lowerRows = new JsonArray();
            for (JsonElement r : rows) {
                if (!r.isJsonObject()) {
                    lowerRows.add(r);
                    continue;
                }
                JsonObject row = new JsonObject();
                for (Map.Entry<String, JsonElement> e : r.getAsJsonObject().entrySet()) {
                    row.add(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
                }
                lowerRows.add(row);
            }
            table.add("rows", lowerRows);
        }
    }

    /**
     * {@code {"error": message}} plus optional hint.
     */
    @NotNull
    public static JsonObject error(@NotNull String message, @Nullable String hint) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        if (hint != null) {
            json.addProperty("hint", hint);
        }
        return json;
    }
}
