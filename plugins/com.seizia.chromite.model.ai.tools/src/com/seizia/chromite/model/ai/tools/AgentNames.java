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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Object names given by the model: splitting {@code db.schema.table}, case of unquoted names, quoting for SQL.
 *
 * 설계: 이름 부분의 뜻은 DB마다 다르다. 두 부분이면 MySQL/MariaDB는 {@code db.table}(카탈로그), 나머지는 {@code schema.table}.
 * 한 부분이면 연결의 현재 스키마(MySQL은 현재 DB)를 쓴다. 따옴표 없는 이름은 드라이버가 알려 준 저장 규칙(대문자: Oracle, 소문자: PostgreSQL)으로
 * 바꾼다. SQL에 넣을 때는 드라이버 방언으로 따옴표를 붙여({@link DBUtils#getQuotedIdentifier}) 주입을 막는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AgentNames {

    private static final Pattern SAFE_TYPE = Pattern.compile(
        "(?i)[a-z][a-z0-9_ ]{0,60}(\\(\\s*(\\d+|max)\\s*(,\\s*\\d+\\s*)?\\))?(\\s*\\[\\])?( (unsigned|zerofill|with(out)? time zone|varying|character set \\w+|collate \\w+))*");

    /**
     * A resolved name; catalog/schema may be null.
     */
    public record QName(@Nullable String catalog, @Nullable String schema, @NotNull String name) {
        /**
         * Quoted name for SQL.
         */
        @NotNull
        public String quoted(@NotNull DBPDataSource dataSource) {
            StringBuilder text = new StringBuilder();
            if (catalog != null) {
                text.append(DBUtils.getQuotedIdentifier(dataSource, catalog)).append('.');
            }
            if (schema != null) {
                text.append(DBUtils.getQuotedIdentifier(dataSource, schema)).append('.');
            }
            return text.append(DBUtils.getQuotedIdentifier(dataSource, name)).toString();
        }

        @NotNull
        public String display() {
            return (catalog == null ? "" : catalog + ".") + (schema == null ? "" : schema + ".") + name;
        }
    }

    private AgentNames() {
    }

    /**
     * Splits a name and fills the default schema (or MySQL database) of the connection.
     */
    @NotNull
    public static QName resolve(@NotNull Connection connection, @NotNull DbFamily family, @NotNull String text)
        throws AIToolArgumentException, SQLException {
        List<String> parts = split(text);
        if (parts.isEmpty() || parts.size() > 3) {
            throw new AIToolArgumentException("Bad object name '" + text + "'");
        }
        DatabaseMetaData meta = connection.getMetaData();
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            normalized.add(normalize(meta, part));
        }
        boolean catalogNames = family == DbFamily.MYSQL || family == DbFamily.MARIADB;
        String name = normalized.getLast();
        String catalog = null;
        String schema = null;
        if (normalized.size() == 3) {
            catalog = normalized.get(0);
            schema = normalized.get(1);
        } else if (normalized.size() == 2) {
            if (catalogNames) {
                catalog = normalized.get(0);
            } else {
                schema = normalized.get(0);
            }
        } else if (catalogNames) {
            catalog = connection.getCatalog();
        } else {
            schema = currentSchema(connection);
        }
        return new QName(catalog, schema, name);
    }

    @Nullable
    static String currentSchema(@NotNull Connection connection) {
        try {
            return connection.getSchema();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Unquotes a quoted part; changes the case of an unquoted one as the database stores it.
     */
    @NotNull
    static String normalize(@NotNull DatabaseMetaData meta, @NotNull String part) throws SQLException {
        String p = part.strip();
        if (p.length() >= 2 && (p.startsWith("\"") && p.endsWith("\"") || p.startsWith("[") && p.endsWith("]") ||
            p.startsWith("`") && p.endsWith("`"))) {
            return p.substring(1, p.length() - 1);
        }
        if (meta.storesUpperCaseIdentifiers()) {
            return p.toUpperCase(Locale.ROOT);
        }
        if (meta.storesLowerCaseIdentifiers()) {
            return p.toLowerCase(Locale.ROOT);
        }
        return p;
    }

    @NotNull
    static List<String> split(@NotNull String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char c : text.strip().toCharArray()) {
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '`') {
                quote = c;
                current.append(c);
            } else if (c == '[') {
                quote = ']';
                current.append(c);
            } else if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * Escapes {@code _} and {@code %} for a DatabaseMetaData name pattern.
     */
    @Nullable
    static String pattern(@NotNull DatabaseMetaData meta, @Nullable String name) throws SQLException {
        if (name == null) {
            return null;
        }
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    /**
     * Checks a column type written by the model (DDL tools): a type name, optional length/precision and common modifiers.
     */
    @NotNull
    static String checkType(@NotNull String type) throws AIToolArgumentException {
        String t = type.strip();
        if (!SAFE_TYPE.matcher(t).matches()) {
            throw new AIToolArgumentException("Unsupported column type '" + type + "'");
        }
        return t;
    }

    /**
     * Checks a default value or expression written by the model: one literal or function call, no statement separators or comments.
     */
    @NotNull
    static String checkExpression(@NotNull String expression) throws AIToolArgumentException {
        String e = expression.strip();
        if (e.contains(";") || e.contains("--") || e.contains("/*")) {
            throw new AIToolArgumentException("Default expression must not contain ';' or comments");
        }
        return e;
    }
}
