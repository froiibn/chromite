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
package com.seizia.chromite.model.sql.exec;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.rules.FastPartitioner;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLBatchDelimiterElement;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLPartitionScanner;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.parser.SQLParserContext;
import org.jkiss.dbeaver.model.sql.parser.SQLParserPartitions;
import org.jkiss.dbeaver.model.sql.parser.SQLRuleManager;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits editor text into the elements that {@link SQLQueryExecuteJob} executes, sending each batch
 * ({@code GO}-separated part of the script) to the server as one query for dialects with batch delimiters.
 *
 * 설계: SSMS처럼 SQL Server 스크립트는 {@code GO} 사이의 문장들을 한 번에 서버로 보내야 같은 배치 안에서 {@code DECLARE}한 변수를
 * 이어서 쓸 수 있다. 엔진({@link SQLQueryExecuteJob})은 {@link SQLQuery} 하나를 JDBC 실행 한 번으로 보내므로,
 * 파서가 문장 단위로 나눈 결과를 배치 단위로 다시 묶어 원문 범위 그대로의 {@link SQLQuery} 하나로 만든다.
 * SQL Server를 이름으로 검사하지 않고 방언의 배치 구분자({@link SQLDialect#getBatchDelimiters()}, 구문 관리자가 설정
 * {@code SCRIPT_IGNORE_NATIVE_DELIMITER}까지 반영한 값)가 있는지로 판단해 같은 구분자를 쓰는 방언(Sybase 등)도 같은 경로를 쓴다.
 * UI와 무관하고 단위 테스트할 수 있도록 Swing 번들이 아니라 실행 엔진 옆(model.sql)에 두었다.
 * 파서 문맥은 SWT 편집기({@code SQLEditorBase})와 같은 방식으로 만든다(연결의 설정 저장소, 연결별 규칙, 문서 분할기).
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 F단계: {@link #createParserContext}를 공개해 Swing 편집기의 자동완성이 실행과 같은 파서 문맥을 쓰게 함
 *   (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 */
public final class SQLScriptBatches {

    /**
     * {@code GO 3}: the batch is executed three times, as in SSMS / sqlcmd.
     */
    private static final Pattern REPEAT_COUNT_PATTERN = Pattern.compile("^\\S+\\s+(\\d+)\\s*$");

    private SQLScriptBatches() {
    }

    /**
     * Parses {@code [offset, offset + length)} of {@code text} with the dialect and settings of the data source
     * and returns the elements to execute: one {@link SQLQuery} per batch for dialects with batch delimiters,
     * one per statement otherwise. Control commands ({@code @set} etc.) are kept as separate elements.
     * Offsets of the returned elements refer to {@code text}.
     */
    @NotNull
    public static List<SQLScriptElement> extractQueries(@NotNull DBPDataSource dataSource, @NotNull String text, int offset, int length) {
        return extractQueries(createParserContext(dataSource, text), offset, length);
    }

    /**
     * Same as {@link #extractQueries(DBPDataSource, String, int, int)} for an existing parser context.
     */
    @NotNull
    public static List<SQLScriptElement> extractQueries(@NotNull SQLParserContext context, int offset, int length) {
        if (context.getSyntaxManager().getBatchDelimiters().length == 0) {
            return SQLScriptParser.extractScriptQueries(context, offset, length, true, false, true);
        }
        List<SQLScriptElement> elements = SQLScriptParser.extractScriptQueries(context, offset, length, true, false, true, true);
        return groupBatches(context, elements);
    }

    /**
     * Merges the queries between batch delimiters into one query each. The delimiters themselves are dropped
     * (the engine only executes queries and control commands); a delimiter with a repeat count ({@code GO 2})
     * repeats its batch. Empty batches (leading or consecutive delimiters) produce nothing.
     *
     * @param elements elements extracted with {@code includeBatchDelimiterElements = true}
     */
    @NotNull
    public static List<SQLScriptElement> groupBatches(@NotNull SQLParserContext context, @NotNull List<SQLScriptElement> elements) {
        List<SQLScriptElement> result = new ArrayList<>();
        List<SQLQuery> batch = new ArrayList<>();
        for (SQLScriptElement element : elements) {
            if (element instanceof SQLBatchDelimiterElement delimiter) {
                addBatch(context, batch, getRepeatCount(delimiter), result);
            } else if (element instanceof SQLQuery query) {
                batch.add(query);
            } else {
                // Control command: runs on its own, between the batches before and after it
                addBatch(context, batch, 1, result);
                result.add(element);
            }
        }
        addBatch(context, batch, 1, result);
        return result;
    }

    private static void addBatch(
        @NotNull SQLParserContext context,
        @NotNull List<SQLQuery> batch,
        int repeatCount,
        @NotNull List<SQLScriptElement> result
    ) {
        if (batch.isEmpty()) {
            return;
        }
        SQLQuery batchQuery;
        if (batch.size() == 1) {
            batchQuery = batch.getFirst();
        } else {
            // The original text from the first statement to the end of the last one, with the delimiters and
            // comments between the statements, exactly as SSMS sends it
            int start = batch.getFirst().getOffset();
            SQLQuery last = batch.getLast();
            int end = last.getOffset() + last.getLength();
            String text;
            try {
                text = context.getDocument().get(start, end - start);
            } catch (BadLocationException e) {
                throw new IllegalStateException("Batch range " + start + ".." + end + " is outside of the script", e);
            }
            batchQuery = new SQLQuery(context.getDataSource(), text, start, end - start);
            batchQuery.setParameters(SQLScriptParser.parseParametersAndVariables(context, start, end - start));
        }
        for (int i = 0; i < repeatCount; i++) {
            result.add(batchQuery);
        }
        batch.clear();
    }

    private static int getRepeatCount(@NotNull SQLBatchDelimiterElement delimiter) {
        Matcher matcher = REPEAT_COUNT_PATTERN.matcher(delimiter.getText().trim());
        if (matcher.matches()) {
            try {
                return Math.max(1, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException e) {
                // Too large: run once rather than fail
                return 1;
            }
        }
        return 1;
    }

    /**
     * Parser context like the one of the SWT SQL editor (SQLEditorBase): syntax and rules of the data source's
     * dialect and connection settings, and the SQL partitioner that the parser uses to skip comments and strings.
     * Also used by the Swing editor's code completion, which needs the partitions (comments, strings) and the
     * statement at the caret.
     */
    @NotNull
    public static SQLParserContext createParserContext(@NotNull DBPDataSource dataSource, @NotNull String text) {
        SQLDialect dialect = dataSource.getSQLDialect();
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, dataSource.getContainer().getPreferenceStore());
        SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
        ruleManager.loadRules(dataSource.getContainer(), false);

        Document document = new Document(text);
        FastPartitioner partitioner = new FastPartitioner(
            new SQLPartitionScanner(dataSource, dialect, ruleManager),
            SQLParserPartitions.SQL_CONTENT_TYPES
        );
        partitioner.connect(document);
        document.setDocumentPartitioner(SQLParserPartitions.SQL_PARTITIONING, partitioner);

        SQLParserContext context = new SQLParserContext(dataSource, syntaxManager, ruleManager, document);
        context.setPreferenceStore(dataSource.getContainer().getPreferenceStore());
        return context;
    }
}
