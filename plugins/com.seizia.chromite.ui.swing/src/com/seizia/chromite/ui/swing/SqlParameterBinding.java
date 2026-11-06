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

import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;

import java.util.List;

/**
 * 한 번의 실행에서 쿼리 매개변수 값을 문장에 바인딩하는 방법(job 스레드에서 불림).
 *
 * 설계: 파서가 나눈 문장({@link SQLScriptElement})을 실행 엔진에 넘기기 전에 {@link #prepare}가 바꾸고(예: {@code :name} → {@code ?}),
 * 엔진이 문장을 만들 때 {@link SQLQueryExecuteHandler#hasBoundParameters}/{@link SQLQueryExecuteHandler#bindParameters}로
 * {@link #isBound}/{@link #bind}를 부른다. 실행 창({@link SqlEditorPanel})과 예약 실행이 같은 바인딩을 쓴다.
 * 구현은 {@link QueryParameterBinder}.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 {@link #getHistoryText}: 바인딩한 쿼리가 Query History에 {@code ?}로 남아 다시 열거나 실행할 수 없던 문제
 *   (docs/chromite/swing-query-library.md "매개변수 바인딩")
 */
interface SqlParameterBinding {

    /**
     * No parameters: the elements run as they are.
     */
    SqlParameterBinding NONE = new SqlParameterBinding() {
        @NotNull
        @Override
        public List<SQLScriptElement> prepare(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext context,
            @NotNull List<SQLScriptElement> elements
        ) {
            return elements;
        }

        @Override
        public boolean isBound(@NotNull SQLQuery query) {
            return false;
        }

        @Override
        public void bind(@NotNull DBCStatement statement, @NotNull SQLQuery query) {
        }
    };

    /**
     * Rewrites the parsed elements before they run (job thread).
     *
     * @throws DBException a value can't be used (e.g. a number that does not parse); the run stops with this error
     */
    @NotNull
    List<SQLScriptElement> prepare(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull List<SQLScriptElement> elements
    ) throws DBException;

    /**
     * Whether the engine must prepare the query and call {@link #bind} (the engine passes a copy of a query returned
     * by {@link #prepare}, which keeps its {@link SQLQuery#getData() data}).
     */
    boolean isBound(@NotNull SQLQuery query);

    void bind(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException;

    /**
     * The text of an executed query for the query history: a bound query with its values written in as SQL literals
     * (its own text has {@code ?} markers, which can't be run again from the history).
     */
    @NotNull
    default String getHistoryText(@NotNull SQLQuery query) {
        return query.getText();
    }
}
