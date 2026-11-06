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

import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.exec.plan.DBCQueryPlannerConfiguration;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryType;

import javax.swing.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Makes the plans of statements with the connection's {@link DBCQueryPlanner}, in a job.
 *
 * 설계: SWT의 {@code ExplainPlanViewer.ExplainPlanService.evaluate}와 같은 순서다. 문장마다 실행 문맥에서 UTIL 세션을 열고
 * {@link DBExecUtils#tryExecuteRecover}(끊긴 연결 다시 잇기) 안에서 {@code planQueryExecution}을 부른다. 계획기 설정 대화상자
 * ({@code DBEObjectConfigurator}, SWT UI 플러그인에만 있음)는 쓰지 않고 기본 설정을 넘긴다.
 * <ul>
 *     <li>실제 계획을 계획기로 구할 때는 {@value #ANALYZE_PARAMETER} 설정을 켠다. PostgreSQL 계획기
 *     ({@code PostgreQueryPlaner.PARAM_ANALYSE})가 이 키를 읽어 {@code EXPLAIN (ANALYZE)}로 문장을 실제로 실행하고 되돌린다(롤백).
 *     이 키를 모르는 계획기(MySQL/MariaDB {@code EXPLAIN FORMAT=JSON})는 예상 계획을 돌려주므로, 실제인지는 계획기가 실행한 계획 문장에
 *     {@code ANALYZE}가 있는지로 판단해 화면에 그대로 밝힌다.</li>
 *     <li>실제 계획을 위해 문장을 다시 실행하는 것은 데이터를 바꾸지 않는 SELECT에만 한다({@link #isReadOnlySelect}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class SqlPlanExplainer {

    private static final Log log = Log.getLog(SqlPlanExplainer.class);

    /**
     * Planner configuration key that asks for run-time values (PostgreSQL planner: {@code EXPLAIN (ANALYZE)}).
     */
    static final String ANALYZE_PARAMETER = "ANALYZE"; //$NON-NLS-1$

    private SqlPlanExplainer() {
    }

    /**
     * Whether a statement only reads data, so that planning it with run-time values (which executes it) is harmless.
     */
    static boolean isReadOnlySelect(@NotNull SQLQuery query) {
        return query.getType() == SQLQueryType.SELECT && !query.isModifying();
    }

    /**
     * Explains the queries one by one; each plan (or error) is passed to {@code consumer} in the EDT, then {@code done}.
     *
     * @param analyze ask the planner for run-time values (executes the statements)
     */
    static void explain(
        @NotNull DBCExecutionContext context,
        @NotNull DBCQueryPlanner planner,
        @NotNull List<SQLQuery> queries,
        boolean analyze,
        @NotNull Consumer<ExecutionPlanView.Entry> consumer,
        @NotNull Runnable done
    ) {
        new AbstractJob(analyze ? "Actual execution plan" : "Estimated execution plan") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    for (SQLQuery query : queries) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        ExecutionPlanView.Entry entry = explain(monitor, context, planner, query.getText(), analyze);
                        SwingUtilities.invokeLater(() -> consumer.accept(entry));
                    }
                } finally {
                    SwingUtilities.invokeLater(done);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @NotNull
    private static ExecutionPlanView.Entry explain(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull DBCQueryPlanner planner,
        @NotNull String query,
        boolean analyze
    ) {
        DBCQueryPlannerConfiguration configuration = new DBCQueryPlannerConfiguration();
        if (analyze) {
            configuration.getParameters().put(ANALYZE_PARAMETER, true);
        }
        DBCPlan[] plan = new DBCPlan[1];
        try {
            DBPDataSource dataSource = context.getDataSource();
            DBExecUtils.tryExecuteRecover(monitor, dataSource, param -> {
                try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Explain query")) {
                    plan[0] = planner.planQueryExecution(session, query, configuration);
                }
            });
        } catch (Throwable e) {
            log.debug("Can't explain query", e);
            return new ExecutionPlanView.Entry(query, null, ErrorText.describe(e), analyze);
        }
        if (plan[0] == null) {
            // tryExecuteRecover returns without an error when it was interrupted
            return new ExecutionPlanView.Entry(query, null, EditorMessages.editor_plan_canceled, analyze);
        }
        return new ExecutionPlanView.Entry(query, plan[0], null, analyze && isActual(plan[0]));
    }

    /**
     * Whether the planner ran the statement (its plan query asks for ANALYZE).
     */
    private static boolean isActual(@Nullable DBCPlan plan) {
        if (plan == null) {
            return false;
        }
        try {
            return plan.getPlanQueryString().toUpperCase(Locale.ENGLISH).contains(ANALYZE_PARAMETER);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The planner of a connected data source, or null when it has none.
     */
    @Nullable
    static DBCQueryPlanner getPlanner(@Nullable DBPDataSource dataSource) {
        return dataSource == null ? null : DBUtils.getAdapter(DBCQueryPlanner.class, dataSource);
    }
}
