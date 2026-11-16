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
import org.jkiss.dbeaver.DBException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic SQL of one tool, per database family: resource {@code diag/<toolId>.sql} next to this class.
 *
 * 설계: 상태·성능 도구는 대부분 DB 종류별 시스템 뷰 쿼리라서 Java 코드가 아니라 SQL 자원으로 둔다. DB 종류를 더하거나 쿼리를 고칠 때 SQL만 보면 된다.
 * 형식(줄 맨 앞의 주석 지시어):
 * <pre>
 * -- &#64;family: sqlserver            이어지는 문장들의 DB 종류 (DbFamily ID)
 * -- &#64;requires: VIEW SERVER STATE  권한이 없어 실패하면 오류와 함께 알려 줄 필요 권한
 * -- &#64;note: ...                    결과에 덧붙일 설명(예: 이 DB에는 누적 대기 통계가 없음)
 * -- &#64;statement: sessions          문장 이름(결과 JSON의 키). 생략하면 "rows"
 * -- &#64;maxCellChars: 200000         이 문장의 칸 한도(원문·계획 XML처럼 긴 값)
 * -- &#64;limit: 1000                  이 문장의 기본 행 한도(도구의 limit 인수가 없을 때)
 * </pre>
 * 문장 안의 {@code ${이름}}은 도구 인수 값을 묶는 {@code ?}가 된다(식별자는 쓰지 않음: SQL 주입 방지). {@code ${limit}}는 행 한도.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "설계 결정과 이유")
 */
public final class DiagnosticScript {

    private static final Pattern DIRECTIVE = Pattern.compile("^--\\s*@(\\w+)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");
    private static final Map<String, DiagnosticScript> cache = new ConcurrentHashMap<>();

    /**
     * One statement of a family block.
     *
     * @param sql        SQL with {@code ?} placeholders
     * @param parameters argument names in placeholder order
     */
    public record Statement(
        @NotNull String name,
        @NotNull String sql,
        @NotNull List<String> parameters,
        @Nullable Integer maxCellChars,
        @Nullable Integer limit
    ) {
    }

    /**
     * The statements of one family.
     */
    public record Block(
        @NotNull DbFamily family,
        @NotNull List<Statement> statements,
        @NotNull List<String> requires,
        @NotNull List<String> notes
    ) {
    }

    private final Map<DbFamily, Block> blocks;

    private DiagnosticScript(@NotNull Map<DbFamily, Block> blocks) {
        this.blocks = blocks;
    }

    /**
     * The script of a tool, or null when the tool has no diagnostic SQL.
     */
    @Nullable
    public static DiagnosticScript load(@NotNull String toolId) throws DBException {
        DiagnosticScript cached = cache.get(toolId);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = DiagnosticScript.class.getResourceAsStream("diag/" + toolId + ".sql")) { //$NON-NLS-1$ //$NON-NLS-2$
            if (in == null) {
                return null;
            }
            DiagnosticScript script = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            cache.put(toolId, script);
            return script;
        } catch (IOException e) {
            throw new DBException("Can't read diagnostic SQL of " + toolId, e);
        }
    }

    /**
     * The block for a family, following {@link DbFamily#lookupChain()}.
     */
    @Nullable
    public Block getBlock(@NotNull DbFamily family) {
        for (DbFamily candidate : family.lookupChain()) {
            Block block = blocks.get(candidate);
            if (block != null) {
                return block;
            }
        }
        return null;
    }

    @NotNull
    public List<DbFamily> getFamilies() {
        return List.copyOf(blocks.keySet());
    }

    @NotNull
    static DiagnosticScript parse(@NotNull String text) throws DBException {
        Map<DbFamily, Block> blocks = new EnumMap<>(DbFamily.class);
        DbFamily family = null;
        List<Statement> statements = null;
        List<String> requires = null;
        List<String> notes = null;
        String statementName = null;
        Integer maxCellChars = null;
        Integer limit = null;
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\\R")) {
            Matcher m = DIRECTIVE.matcher(line);
            if (m.matches()) {
                String key = m.group(1);
                String value = m.group(2);
                if (key.equals("family") || key.equals("statement")) {
                    if (statements != null) {
                        addStatement(statements, statementName, sql, maxCellChars, limit);
                    }
                    sql.setLength(0);
                    maxCellChars = null;
                    limit = null;
                    statementName = null;
                }
                switch (key) {
                    case "family" -> {
                        family = DbFamily.fromId(value);
                        if (family == null) {
                            throw new DBException("Unknown family in diagnostic SQL: " + value);
                        }
                        statements = new ArrayList<>();
                        requires = new ArrayList<>();
                        notes = new ArrayList<>();
                        blocks.put(family, new Block(family, statements, requires, notes));
                    }
                    case "statement" -> statementName = value;
                    case "requires" -> {
                        if (requires != null) {
                            requires.add(value);
                        }
                    }
                    case "note" -> {
                        if (notes != null) {
                            notes.add(value);
                        }
                    }
                    case "maxCellChars" -> maxCellChars = Integer.parseInt(value);
                    case "limit" -> limit = Integer.parseInt(value);
                    default -> throw new DBException("Unknown directive in diagnostic SQL: " + key);
                }
                continue;
            }
            if (family != null) {
                sql.append(line).append('\n');
            }
        }
        if (statements != null) {
            addStatement(statements, statementName, sql, maxCellChars, limit);
        }
        return new DiagnosticScript(blocks);
    }

    private static void addStatement(
        @NotNull List<Statement> statements,
        @Nullable String name,
        @NotNull StringBuilder sql,
        @Nullable Integer maxCellChars,
        @Nullable Integer limit
    ) {
        String text = sql.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        List<String> parameters = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder bound = new StringBuilder();
        while (m.find()) {
            parameters.add(m.group(1));
            m.appendReplacement(bound, "?");
        }
        m.appendTail(bound);
        statements.add(new Statement(name == null ? "rows" : name, bound.toString(), parameters, maxCellChars, limit));
    }
}
