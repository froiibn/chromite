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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.semantics.SQLObjectOperationRecognizer;
import com.seizia.chromite.registry.confirmation.ConfirmationConstants;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.PrefUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 쿼리 창(F5)의 실행 전 안전 확인: 연결의 "스크립트 실행 제한"과 데이터를 잃을 수 있는 문(WHERE 없는 UPDATE/DELETE, TRUNCATE, DROP)의 확인.
 *
 * 설계:
 * <ul>
 *     <li>실행 제한: SWT {@code SQLEditor.processQueries}처럼 연결(또는 연결 종류)이 {@link DBPDataSourcePermission#PERMISSION_EXECUTE_SCRIPTS}를
 *     막으면 쿼리 창의 실행을 모두 거절한다. Swing 쿼리 창에는 문장 실행과 스크립트 실행의 구분이 없고(F5 하나, 선택 또는 전체),
 *     문장 수로 나누면 위험한 문도 한 문장씩 실행할 수 있어 제한이 의미가 없으므로 문장 수와 관계없이 막는다. 연결하기 전에 EDT에서 검사한다.</li>
 *     <li>위험한 문: 판단은 모델의 파서 도우미({@link SQLQuery#isDeleteUpdateDangerous()}, {@link SQLQuery#isTruncate()},
 *     {@link SQLQuery#isDropDangerous()})를 쓴다. JSQLParser가 읽지 못하는 DROP(DATABASE, USER 등)은 SWT 편집기처럼 LSM 파서로 찾는다
 *     ({@link #isDrop}). TRUNCATE는 WHERE 없는 DELETE와 같이 모든 행을 지우고 MySQL·MariaDB·Oracle에서는 되돌릴 수 없어
 *     같은 확인(위험한 SQL)으로 묻는다. 연결의 방언으로 나눈 문장이 필요하므로 실행 준비 Job에서 나눈 뒤에 검사하고,
 *     실행 전체에서 한 번만 묻고 위험한 문 목록(줄 번호, 종류, 문장)을 보여 준다(SWT는 문장마다 물었다).
 *     SQL Server처럼 배치 구분자({@code GO})가 있는 방언은 실행 요소가 배치 단위로 합쳐져 있어({@link SQLScriptBatches}) 그대로 파서에 넘기면
 *     여러 문장이라 판단하지 못한다. 그래서 그런 방언은 배치를 다시 문장으로 나눠 검사한다.</li>
 *     <li>설정: SWT와 같은 설정 키({@link ConfirmationConstants#CONFIRM_DANGER_SQL_KEY}, {@link ConfirmationConstants#CONFIRM_DROP_SQL_KEY})를
 *     플랫폼 설정 저장소에 두고 값의 뜻도 SWT 확인 대화상자와 같게 한다. SWT의 "다시 묻지 않음"은 {@code always}(또는 {@code never})를 저장했고
 *     두 값 모두 묻지 않고 실행했으며, 그 밖의 값(비어 있음, {@code prompt})은 물었다. 새 키를 만들지 않은 것은 모델에 이미 있는 상수와
 *     확인 등록({@code model.sql}의 {@code com.seizia.chromite.confirmations})을 그대로 쓰기 위해서다. 기본값은 묻기이고
 *     Options 대화상자(General ▸ Query editor)에서 켜고 끈다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-07 DROP DATABASE 등 JSQLParser가 읽지 못하는 DROP을 놓치던 것을 고침(LSM 파서로도 찾음), TRUNCATE도 물음
 *   (docs/chromite/swing-sql-exec-safety.md "DROP 판단의 근본 원인")
 */
final class SqlExecutionSafety {

    // Values of the SWT confirmation toggle (JFace MessageDialogWithToggle) stored under the confirmation keys
    private static final String ALWAYS = "always"; //$NON-NLS-1$
    private static final String NEVER = "never"; //$NON-NLS-1$
    private static final String PROMPT = "prompt"; //$NON-NLS-1$

    private SqlExecutionSafety() {
    }

    static boolean isConfirmDangerousSql() {
        return isAsking(ConfirmationConstants.CONFIRM_DANGER_SQL_KEY);
    }

    static void setConfirmDangerousSql(boolean confirm) {
        setAsking(ConfirmationConstants.CONFIRM_DANGER_SQL_KEY, confirm);
    }

    static boolean isConfirmDropSql() {
        return isAsking(ConfirmationConstants.CONFIRM_DROP_SQL_KEY);
    }

    static void setConfirmDropSql(boolean confirm) {
        setAsking(ConfirmationConstants.CONFIRM_DROP_SQL_KEY, confirm);
    }

    private static boolean isAsking(@NotNull String key) {
        String value = getPreferences().getString(key);
        return !ALWAYS.equals(value) && !NEVER.equals(value);
    }

    private static void setAsking(@NotNull String key, boolean ask) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(key, ask ? PROMPT : ALWAYS);
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    /**
     * EDT, before connecting. Tells the user and returns {@code false} when the connection or its connection type
     * restricts executing SQL ("Restrict script execute").
     */
    static boolean checkExecutePermission(@NotNull Component parent, @NotNull DBPDataSourceContainer container) {
        if (container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS)) {
            return true;
        }
        JOptionPane.showMessageDialog(parent,
            SwingPlatformUI.createMessageComponent(NLS.bind(EditorMessages.editor_sql_execution_restricted,
                container.getName(), DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS.getLabel())),
            EditorMessages.editor_sql_execution_restricted_title, JOptionPane.ERROR_MESSAGE);
        return false;
    }

    /**
     * Job thread, after the text was split into the elements to execute. Asks once for all UPDATE/DELETE statements
     * without a WHERE clause, TRUNCATE and DROP statements of the run (as far as the confirmations are turned on).
     *
     * @param text     the editor text that the offsets of the elements refer to (line numbers)
     * @return {@code true} to execute, {@code false} if the user canceled
     */
    static boolean confirmDangerousStatements(
        @NotNull Component parent,
        @NotNull DBPDataSource dataSource,
        @NotNull String text,
        @NotNull List<SQLScriptElement> elements
    ) {
        boolean deleteUpdate = isConfirmDangerousSql();
        boolean drop = isConfirmDropSql();
        if (!deleteUpdate && !drop) {
            return true;
        }
        List<String> findings = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dataSource.getSQLDialect(), dataSource.getContainer().getPreferenceStore());
        for (Statement statement : getStatements(dataSource, syntaxManager, elements)) {
            SQLQuery query = statement.query();
            String kind;
            if (drop && isDrop(dataSource, syntaxManager, query)) {
                kind = NLS.bind(EditorMessages.editor_sql_danger_drop, lineOf(text, statement.offset()));
            } else if (deleteUpdate && query.isDeleteUpdateDangerous()) {
                kind = NLS.bind(EditorMessages.editor_sql_danger_no_where, lineOf(text, statement.offset()), query.getType().name());
            } else if (deleteUpdate && query.isTruncate()) {
                kind = NLS.bind(EditorMessages.editor_sql_danger_truncate, lineOf(text, statement.offset()));
            } else {
                continue;
            }
            findings.add(kind);
            if (!sql.isEmpty()) {
                sql.append("\n\n"); //$NON-NLS-1$
            }
            sql.append("-- ").append(kind).append('\n').append(query.getText().strip()); //$NON-NLS-1$
        }
        if (findings.isEmpty()) {
            return true;
        }
        String message = findings.size() == 1 ? EditorMessages.editor_sql_danger_message_one
            : NLS.bind(EditorMessages.editor_sql_danger_message_many, findings.size());
        return SqlConfirmDialog.confirm(parent, EditorMessages.editor_sql_danger_title, message, sql.toString(), true);
    }

    /**
     * A DROP of any object. JSQLParser ({@link SQLQuery#isDropDangerous()}) cannot read a DROP whose object kind is one
     * of its keywords (DATABASE, USER, TABLESPACE, PROCEDURE, TRIGGER, ...); the LSM parser reads those, as the SWT
     * editor did ({@link SQLObjectOperationRecognizer}). The LSM parser runs only when JSQLParser failed: a statement
     * it read is not a DROP unless it said so, and long scripts are mostly such statements.
     */
    private static boolean isDrop(@NotNull DBPDataSource dataSource, @NotNull SQLSyntaxManager syntaxManager, @NotNull SQLQuery query) {
        return query.isDropDangerous()
            || query.getParseError() != null && SQLObjectOperationRecognizer.isDropStatement(dataSource.getSQLDialect(), syntaxManager, query.getText());
    }

    /**
     * A statement and its offset in the editor text.
     */
    private record Statement(@NotNull SQLQuery query, int offset) {
    }

    /**
     * The single statements of the elements. Batches of dialects with batch delimiters ({@link SQLScriptBatches}) are
     * split again, because the SQL parser recognizes one statement only. A repeated batch ({@code GO 2}) is the same
     * element several times and is checked once.
     */
    @NotNull
    private static List<Statement> getStatements(
        @NotNull DBPDataSource dataSource,
        @NotNull SQLSyntaxManager syntaxManager,
        @NotNull List<SQLScriptElement> elements
    ) {
        boolean batches = syntaxManager.getBatchDelimiters().length > 0;
        Set<SQLQuery> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Statement> statements = new ArrayList<>();
        for (SQLScriptElement element : elements) {
            if (!(element instanceof SQLQuery query) || !seen.add(query)) {
                continue;
            }
            if (!batches) {
                statements.add(new Statement(query, query.getOffset()));
                continue;
            }
            String batchText = query.getText();
            for (SQLScriptElement part : SQLScriptParser.extractScriptQueries(
                SQLScriptBatches.createParserContext(dataSource, batchText), 0, batchText.length(), true, false, false)) {
                if (part instanceof SQLQuery statement) {
                    statements.add(new Statement(statement, query.getOffset() + statement.getOffset()));
                }
            }
        }
        return statements;
    }

    private static int lineOf(@NotNull String text, int offset) {
        int line = 1;
        int end = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
