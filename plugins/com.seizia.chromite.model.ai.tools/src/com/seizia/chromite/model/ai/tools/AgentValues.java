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
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binds JSON values from the model to the column types of a table (row editing, CSV import, table copy).
 *
 * 설계: 모델이 보내는 값은 JSON의 문자열·수·참거짓뿐이다. 그대로 {@code setObject}하면 PostgreSQL은 문자열을 정수 열에 넣지 않는다.
 * 그래서 대상 열의 JDBC 형식({@code SELECT * ... WHERE 1=0}의 메타데이터)을 먼저 읽고, 수·날짜·참거짓은 그 형식의 Java 값으로 바꿔 묶는다.
 * 드라이버 전용 형식(uuid, json 등: {@link Types#OTHER})은 문자열을 형식 지정과 함께 넘겨 드라이버가 바꾸게 한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class AgentValues {

    /**
     * Column name (as the database spells it) → JDBC type, in table order.
     */
    record TableColumns(@NotNull Map<String, Integer> types) {
        /**
         * The database spelling of a column name given by the model (case-insensitive).
         */
        @NotNull
        String column(@NotNull String name) throws AIToolArgumentException {
            if (types.containsKey(name)) {
                return name;
            }
            for (String column : types.keySet()) {
                if (column.equalsIgnoreCase(name)) {
                    return column;
                }
            }
            throw new AIToolArgumentException("Column '" + name + "' not found; columns: " + String.join(", ", types.keySet()));
        }

        int type(@NotNull String column) {
            return types.getOrDefault(column, Types.VARCHAR);
        }
    }

    private AgentValues() {
    }

    @NotNull
    static TableColumns columns(@NotNull Connection connection, @NotNull String quotedTable) throws SQLException {
        Map<String, Integer> types = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quotedTable + " WHERE 1 = 0")) { //$NON-NLS-1$ //$NON-NLS-2$
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                types.put(meta.getColumnName(i), meta.getColumnType(i));
            }
        }
        return new TableColumns(types);
    }

    static void bind(@NotNull PreparedStatement statement, int index, @Nullable Object value, int sqlType) throws SQLException {
        if (value instanceof JsonElement json) {
            value = json.isJsonNull() ? null : json.isJsonPrimitive() ? json.getAsJsonPrimitive().getAsString() : json.toString();
        }
        if (value == null) {
            statement.setNull(index, sqlType == Types.OTHER ? Types.OTHER : sqlType);
            return;
        }
        String text = value.toString();
        try {
            switch (sqlType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    statement.setLong(index, new BigDecimal(text.trim()).longValueExact());
                case Types.DECIMAL, Types.NUMERIC -> statement.setBigDecimal(index, new BigDecimal(text.trim()));
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> statement.setDouble(index, Double.parseDouble(text.trim()));
                case Types.BIT, Types.BOOLEAN -> statement.setBoolean(index, parseBoolean(text));
                case Types.DATE -> statement.setObject(index, LocalDate.parse(text.trim().substring(0, Math.min(10, text.trim().length()))));
                case Types.TIME -> statement.setObject(index, LocalTime.parse(text.trim()));
                case Types.TIMESTAMP -> statement.setObject(index, parseTimestamp(text.trim()));
                case Types.TIMESTAMP_WITH_TIMEZONE -> statement.setObject(index, OffsetDateTime.parse(text.trim()));
                case Types.OTHER -> statement.setObject(index, text, Types.OTHER);
                default -> statement.setString(index, text);
            }
        } catch (NumberFormatException | ArithmeticException | java.time.format.DateTimeParseException e) {
            throw new SQLException("Value '" + (text.length() > 100 ? text.substring(0, 100) : text) + "' does not fit column type " +
                sqlType + ": " + e.getMessage(), e);
        }
    }

    private static boolean parseBoolean(@NotNull String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("1") || t.equals("t") || t.equals("y") || t.equals("yes");
    }

    @NotNull
    private static LocalDateTime parseTimestamp(@NotNull String text) {
        String t = text.replace(' ', 'T');
        if (t.length() == 10) {
            return LocalDate.parse(t).atStartOfDay();
        }
        if (t.endsWith("Z") || t.matches(".*[+-]\\d\\d:\\d\\d$")) {
            return OffsetDateTime.parse(t).toLocalDateTime();
        }
        return LocalDateTime.parse(t);
    }

    /**
     * Column sets of the primary key and the unique indexes of a table.
     */
    @NotNull
    static List<Set<String>> uniqueKeys(@NotNull Connection connection, @NotNull AgentNames.QName table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<Set<String>> keys = new ArrayList<>();
        Set<String> pk = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (rs.next()) {
                pk.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        if (!pk.isEmpty()) {
            keys.add(pk);
        }
        Map<String, Set<String>> unique = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(table.catalog(), table.schema(), table.name(), true, true)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index != null && column != null && !rs.getBoolean("NON_UNIQUE")) {
                    unique.computeIfAbsent(index, k -> new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        keys.addAll(unique.values());
        return keys;
    }

    /**
     * The key object as an ordered map of database column names.
     */
    @NotNull
    static Map<String, JsonElement> keyColumns(@NotNull JsonObject key, @NotNull TableColumns columns) throws AIToolArgumentException {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : key.entrySet()) {
            result.put(columns.column(e.getKey()), e.getValue());
        }
        if (result.isEmpty()) {
            throw new AIToolArgumentException("The key must name at least one column");
        }
        return result;
    }
}
