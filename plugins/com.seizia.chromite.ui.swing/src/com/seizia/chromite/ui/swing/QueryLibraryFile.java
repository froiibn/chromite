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
package com.seizia.chromite.ui.swing;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Query Library 쿼리 파일 형식: SQL 파일 맨 앞의 주석 머리말에 정보를 담는다.
 *
 * <pre>
 * -- @chromite-query: 1
 * -- @id: 0d7b2c1e-...
 * -- @name: 세션 수
 * -- @description: 상태별 세션 수
 * -- @target: connection
 * -- @target-id: postgres-jdbc-19a...
 * -- @target-name: Local PostgreSQL
 * -- @param min_minutes
 * --   type: number
 * --   default: 5
 *
 * SELECT ...
 * </pre>
 *
 * 설계:
 * <ul>
 *     <li>평범한 {@code .sql} 파일이므로 어느 SQL 도구에서나 열고 실행할 수 있다(머리말은 주석). 한 줄에 항목 하나라 Git 비교가 읽기 쉽고,
 *     같은 내용은 늘 같은 순서로 쓰므로(정해진 항목 순서, 값이 없는 항목은 생략) 저장할 때마다 줄이 바뀌지 않는다.</li>
 *     <li>머리말은 파일 맨 앞에서 {@code -- @}로 시작하는 줄과 그 아래 {@code --   }(세 칸 들여쓰기)로 시작하는 하위 항목 줄이다.
 *     다른 줄이 나오면 끝나고, 바로 뒤의 빈 줄 하나는 구분 줄이라 SQL에 넣지 않는다. 값의 줄바꿈과 역슬래시는 {@code \n}, {@code \\}로 쓴다.</li>
 *     <li>모르는 항목은 원래 줄 그대로 보관했다가 머리말 끝에 다시 쓴다(새 버전의 항목, 예: 예약 실행·공유 정보를 지우지 않음).</li>
 *     <li>문자 집합·BOM·줄바꿈은 {@link SqlFileContent}로 읽고 저장할 때 되살린다(다른 도구가 만든 파일 보존). 새 파일은 UTF-8(BOM 없음), LF다:
 *     여러 사람이 Git이나 공유 폴더로 나눠 쓸 때 운영체제에 따라 바뀌지 않게 하기 위해서다.</li>
 *     <li>머리말이 없는 파일은 SQL 전체가 쿼리이고 이름은 파일 이름(확장자 제외)이다. 이 파일을 Chromite에서 저장하면 머리말이 붙는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "저장 형식")
 */
final class QueryLibraryFile {

    private static final Log log = Log.getLog(QueryLibraryFile.class);

    static final String EXTENSION = ".sql"; //$NON-NLS-1$
    static final String FORMAT_VERSION = "1"; //$NON-NLS-1$

    private static final String KEY_PREFIX = "-- @"; //$NON-NLS-1$
    private static final String SUB_PREFIX = "--   "; //$NON-NLS-1$
    private static final String MAGIC = "chromite-query"; //$NON-NLS-1$
    private static final String ID = "id"; //$NON-NLS-1$
    private static final String NAME = "name"; //$NON-NLS-1$
    private static final String DESCRIPTION = "description"; //$NON-NLS-1$
    private static final String TARGET = "target"; //$NON-NLS-1$
    private static final String TARGET_ID = "target-id"; //$NON-NLS-1$
    private static final String TARGET_NAME = "target-name"; //$NON-NLS-1$
    private static final String DATABASE = "database"; //$NON-NLS-1$
    private static final String SCHEMA = "schema"; //$NON-NLS-1$
    private static final String CREATED = "created"; //$NON-NLS-1$
    private static final String UPDATED = "updated"; //$NON-NLS-1$
    private static final String PARAM = "param"; //$NON-NLS-1$
    private static final String PARAM_LABEL = "label"; //$NON-NLS-1$
    private static final String PARAM_TYPE = "type"; //$NON-NLS-1$
    private static final String PARAM_DEFAULT = "default"; //$NON-NLS-1$
    private static final String PARAM_REQUIRED = "required"; //$NON-NLS-1$
    private static final String PARAM_CHOICE = "choice"; //$NON-NLS-1$
    private static final String PARAM_LOOKUP = "lookup"; //$NON-NLS-1$

    private QueryLibraryFile() {
    }

    @NotNull
    static QueryLibraryItem read(@NotNull Path file) throws IOException {
        return parse(SqlFileContent.read(file).text(), file);
    }

    /**
     * Parses file text ({@code \n} line breaks).
     */
    @NotNull
    static QueryLibraryItem parse(@NotNull String text, @NotNull Path file) {
        String fileName = file.getFileName().toString();
        String stem = fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? fileName.substring(0, fileName.length() - EXTENSION.length()) : fileName;
        QueryLibraryItem item = new QueryLibraryItem(stem);
        item.setFile(file);

        String[] lines = text.split("\n", -1); //$NON-NLS-1$
        int index = 0;
        QueryLibraryParameter parameter = null;
        // Sub-lines of a block this version does not know are kept with it
        boolean inUnknownBlock = false;
        boolean header = false;
        while (index < lines.length) {
            String line = lines[index];
            if (line.startsWith(KEY_PREFIX)) {
                parameter = null;
                inUnknownBlock = false;
                String entry = line.substring(KEY_PREFIX.length());
                int colon = entry.indexOf(':');
                String key = (colon >= 0 ? entry.substring(0, colon) : entry).trim();
                String value = colon >= 0 ? unescape(entry.substring(colon + 1).trim()) : ""; //$NON-NLS-1$
                if (key.startsWith(PARAM + " ") && colon < 0) { //$NON-NLS-1$
                    parameter = new QueryLibraryParameter(key.substring(PARAM.length()).trim());
                    item.getParameters().add(parameter);
                } else if (!readKey(item, key, value)) {
                    item.getExtraHeader().add(line);
                    inUnknownBlock = colon < 0;
                }
                header = true;
            } else if (header && line.startsWith(SUB_PREFIX)) {
                if (parameter != null) {
                    String entry = line.substring(SUB_PREFIX.length());
                    int colon = entry.indexOf(':');
                    String key = colon >= 0 ? entry.substring(0, colon).trim() : entry.trim();
                    String value = colon >= 0 ? unescape(entry.substring(colon + 1).trim()) : ""; //$NON-NLS-1$
                    if (!readParameterKey(parameter, key, value)) {
                        item.getExtraHeader().add(line);
                    }
                } else if (inUnknownBlock) {
                    item.getExtraHeader().add(line);
                }
            } else {
                break;
            }
            index++;
        }
        item.setHasHeader(header);
        if (header && index < lines.length && lines[index].isEmpty()) {
            // The blank line that separates the header from the SQL
            index++;
        }
        item.setSql(String.join("\n", List.of(lines).subList(Math.min(index, lines.length), lines.length))); //$NON-NLS-1$
        return item;
    }

    private static boolean readKey(@NotNull QueryLibraryItem item, @NotNull String key, @NotNull String value) {
        switch (key) {
            case MAGIC -> {
                // Format version: nothing depends on it yet
            }
            case ID -> item.setId(CommonUtils.nullIfEmpty(value));
            case NAME -> {
                if (!value.isBlank()) {
                    item.setName(value);
                }
            }
            case DESCRIPTION -> item.setDescription(value);
            case TARGET -> item.setTargetMode(QueryLibraryItem.TargetMode.fromId(value));
            case TARGET_ID -> item.setTarget(value, item.getTargetName());
            case TARGET_NAME -> item.setTarget(item.getTargetId(), value);
            case DATABASE -> item.setDatabase(value);
            case SCHEMA -> item.setSchema(value);
            case CREATED -> item.setCreated(parseTime(value));
            case UPDATED -> item.setUpdated(parseTime(value));
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean readParameterKey(@NotNull QueryLibraryParameter parameter, @NotNull String key, @NotNull String value) {
        switch (key) {
            case PARAM_LABEL -> parameter.setLabel(value);
            case PARAM_TYPE -> parameter.setType(QueryLibraryParameter.Type.fromId(value));
            case PARAM_DEFAULT -> parameter.setDefaultValue(value);
            case PARAM_REQUIRED -> parameter.setRequired(CommonUtils.toBoolean(value) || value.isEmpty());
            case PARAM_CHOICE -> parameter.getChoices().add(value);
            case PARAM_LOOKUP -> parameter.setLookupSql(value);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static OffsetDateTime parseTime(@NotNull String value) {
        try {
            return value.isEmpty() ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            log.debug("Bad time in query file: " + value);
            return null;
        }
    }

    /**
     * The file text of a query (header, a blank line, the SQL), {@code \n} line breaks.
     */
    @NotNull
    static String format(@NotNull QueryLibraryItem item) {
        StringBuilder text = new StringBuilder();
        appendKey(text, MAGIC, FORMAT_VERSION);
        appendKey(text, ID, item.getId());
        appendKey(text, NAME, item.getName());
        appendKey(text, DESCRIPTION, item.getDescription());
        appendKey(text, TARGET, item.getTargetMode().getId());
        appendKey(text, TARGET_ID, item.getTargetId());
        appendKey(text, TARGET_NAME, item.getTargetName());
        appendKey(text, DATABASE, item.getDatabase());
        appendKey(text, SCHEMA, item.getSchema());
        appendKey(text, CREATED, item.getCreated() != null ? item.getCreated().toString() : null);
        appendKey(text, UPDATED, item.getUpdated() != null ? item.getUpdated().toString() : null);
        for (QueryLibraryParameter parameter : item.getParameters()) {
            if (parameter.isPlain()) {
                continue;
            }
            text.append(KEY_PREFIX).append(PARAM).append(' ').append(parameter.getName()).append('\n');
            appendSubKey(text, PARAM_LABEL, parameter.getLabel());
            if (parameter.getType() != QueryLibraryParameter.Type.TEXT) {
                appendSubKey(text, PARAM_TYPE, parameter.getType().getId());
            }
            appendSubKey(text, PARAM_DEFAULT, parameter.getDefaultValue());
            if (parameter.isRequired()) {
                appendSubKey(text, PARAM_REQUIRED, "true"); //$NON-NLS-1$
            }
            for (String choice : parameter.getChoices()) {
                appendSubKey(text, PARAM_CHOICE, choice);
            }
            appendSubKey(text, PARAM_LOOKUP, parameter.getLookupSql());
        }
        for (String line : item.getExtraHeader()) {
            text.append(line).append('\n');
        }
        text.append('\n').append(item.getSql());
        return text.toString();
    }

    /**
     * Writes the query to its file, keeping the format (charset, BOM, line separator) of an existing file.
     */
    static void write(@NotNull QueryLibraryItem item, @NotNull Path file) throws IOException {
        SqlFileContent format = Files.isRegularFile(file) ? SqlFileContent.read(file)
            : new SqlFileContent("", StandardCharsets.UTF_8, false, "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        format.withText(format(item)).write(file);
        item.setFile(file);
        item.setHasHeader(true);
    }

    private static void appendKey(@NotNull StringBuilder text, @NotNull String key, @Nullable String value) {
        if (!CommonUtils.isEmpty(value)) {
            text.append(KEY_PREFIX).append(key).append(": ").append(escape(value)).append('\n'); //$NON-NLS-1$
        }
    }

    private static void appendSubKey(@NotNull StringBuilder text, @NotNull String key, @Nullable String value) {
        if (!CommonUtils.isEmpty(value)) {
            text.append(SUB_PREFIX).append(key).append(": ").append(escape(value)).append('\n'); //$NON-NLS-1$
        }
    }

    @NotNull
    static String escape(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    @NotNull
    static String unescape(@NotNull String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                result.append(next == 'n' ? '\n' : next);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * A file name for a query or folder name: characters that file systems refuse become {@code _}, trailing dots and
     * spaces (refused by Windows) are dropped, and the name is kept short enough for deep folder trees.
     */
    @NotNull
    static String toFileName(@NotNull String name) {
        String result = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        if (result.length() > 120) {
            result = result.substring(0, 120).strip();
        }
        while (result.endsWith(".") || result.endsWith(" ")) { //$NON-NLS-1$ //$NON-NLS-2$
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "query" : result; //$NON-NLS-1$
    }
}
