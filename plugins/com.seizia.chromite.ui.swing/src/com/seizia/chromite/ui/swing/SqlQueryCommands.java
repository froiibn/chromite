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

import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryTransformer;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.transformers.SQLQueryTransformerCount;
import com.seizia.chromite.model.sql.transformers.SQLQueryTransformerExpression;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * The query window's statement commands besides F5: which statements a run executes, and the statement under the caret
 * for the commands that do not run (copy, previous / next).
 *
 * 설계:
 * <ul>
 *     <li>문장은 모두 모델 파서로 찾는다: 커서 위치 문장은 {@link SQLScriptParser#extractActiveQuery}(선택이 있으면 선택한 글자를 한 문장),
 *     이전·다음은 {@link SQLScriptParser#extractNextQuery}, 커서부터는 커서 위치 문장의 시작부터 끝까지 F5와 같은 나누기
 *     ({@link SQLScriptBatches}, {@code GO} 배치 포함). SWT 편집기와 같은 함수라 빈 줄 구분, 블록, 구분자 설정이 같게 동작한다.</li>
 *     <li>실행하는 명령의 문장 찾기({@link #extractElements})는 실행 준비 Job에서 연결의 방언으로 한다(연결하기 전에는 방언을 모름).
 *     실행하지 않는 명령(복사, 이동)은 EDT에서 바로 하고, 연결되어 있지 않으면 표준 SQL 방언으로 찾는다.</li>
 *     <li>행 수와 식은 SWT와 같은 모델 변환기({@link SQLQueryTransformerCount}, {@link SQLQueryTransformerExpression})로 커서 위치
 *     문장을 바꿔 실행한다. 바꾼 글자의 {@code :name} 위치가 달라지므로 매개변수를 다시 찾는다(SWT와 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-commands.md)
 */
final class SqlQueryCommands {

    /**
     * What a run of the query window executes.
     */
    enum ExecuteMode {
        /** F5: the selection, else the whole text. */
        SCRIPT,
        /** Ctrl+Enter: the statement at the caret, or the selected text as one statement. */
        STATEMENT,
        /** Alt+P: from the statement at the caret to the end of the text. */
        FROM_CURSOR,
        /** SELECT COUNT(*) over the statement at the caret. */
        ROW_COUNT,
        /** SELECT with the selected expression, or the statement at the caret, as a column. */
        EXPRESSION
    }

    private SqlQueryCommands() {
    }

    /**
     * The elements a run executes (job thread). Offsets refer to {@code text}.
     *
     * @param offset the selection start (the caret without a selection); for {@link ExecuteMode#SCRIPT} the start of
     *               the range to execute
     * @param length the selection length, 0 without a selection; for {@link ExecuteMode#SCRIPT} the length of the range
     * @return null if there is no statement at the caret; empty if it is a script command where a query is needed (row
     * count, expression)
     * @throws DBException the statement can't be changed into a row count
     */
    @Nullable
    static List<SQLScriptElement> extractElements(
        @NotNull ExecuteMode mode,
        @NotNull DBPDataSource dataSource,
        @NotNull String text,
        int offset,
        int length
    ) throws DBException {
        if (mode == ExecuteMode.SCRIPT) {
            return SQLScriptBatches.extractQueries(dataSource, text, offset, length);
        }
        SQLParserContext parserContext = SQLScriptBatches.createParserContext(dataSource, text);
        if (mode == ExecuteMode.FROM_CURSOR) {
            // From the start of the statement the caret is in, not from the caret
            SQLScriptElement current = SQLScriptParser.extractQueryAtPos(parserContext, offset, false);
            int start = current != null ? Math.min(current.getOffset(), offset) : offset;
            List<SQLScriptElement> elements = SQLScriptBatches.extractQueries(parserContext, start, text.length() - start);
            return elements.isEmpty() ? null : elements;
        }
        SQLScriptElement active = SQLScriptParser.extractActiveQuery(parserContext, offset, length);
        if (active == null || CommonUtils.isEmptyTrimmed(active.getText())) {
            return null;
        }
        if (mode == ExecuteMode.STATEMENT) {
            return List.of(active);
        }
        if (!(active instanceof SQLQuery query)) {
            // A script command: nothing to count or evaluate
            return List.of();
        }
        SQLQueryTransformer transformer = mode == ExecuteMode.ROW_COUNT ? new SQLQueryTransformerCount() : new SQLQueryTransformerExpression();
        SQLQuery transformed = transformer.transformQuery(dataSource, parserContext.getSyntaxManager(), query);
        if (!CommonUtils.isEmpty(transformed.getParameters())) {
            // The parameters were found in the original text; their positions in the new text differ
            transformed.setParameters(SQLScriptParser.parseParametersAndVariables(parserContext, transformed.getText()));
        }
        return List.of(transformed);
    }

    /**
     * The statement at the caret, or the selected text (EDT).
     */
    @Nullable
    static SQLScriptElement getActiveStatement(@Nullable DBPDataSourceContainer container, @NotNull SqlEditorPanel.SqlTextArea textArea) {
        String text = textArea.getText();
        if (text.isBlank()) {
            return null;
        }
        int start = textArea.getSelectionStart();
        SQLScriptElement element = SQLScriptParser.extractActiveQuery(createParserContext(container, text), start, textArea.getSelectionEnd() - start);
        return element == null || CommonUtils.isEmptyTrimmed(element.getText()) ? null : element;
    }

    /**
     * Selects the statement before or after the one at the caret, as SWT did (EDT).
     *
     * @return false if there is none
     */
    static boolean selectStatement(@Nullable DBPDataSourceContainer container, @NotNull SqlEditorPanel.SqlTextArea textArea, boolean next) {
        String text = textArea.getText();
        if (text.isBlank()) {
            return false;
        }
        // The start of the selection: a statement selected by the previous move is the current one
        SQLScriptElement element = SQLScriptParser.extractNextQuery(createParserContext(container, text), textArea.getSelectionStart(), next);
        if (element == null || element.getOffset() < 0) {
            return false;
        }
        int start = Math.min(element.getOffset(), text.length());
        int end = Math.min(start + element.getLength(), text.length());
        textArea.select(start, end);
        textArea.requestFocusInWindow();
        return true;
    }

    /**
     * A parser for the editor text: the connection's dialect and settings when it is connected, else standard SQL.
     */
    @NotNull
    private static SQLParserContext createParserContext(@Nullable DBPDataSourceContainer container, @NotNull String text) {
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        if (dataSource != null) {
            return SQLScriptBatches.createParserContext(dataSource, text);
        }
        return SQLScriptParser.prepareSqlParserContext(null, BasicSQLDialect.INSTANCE,
            DBWorkbench.getPlatform().getPreferenceStore(), text);
    }
}
