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

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Makes the estimated plans of statements with the connection's {@link DBCQueryPlanner}, in a job.
 *
 * 설계: SWT의 {@code ExplainPlanViewer.ExplainPlanService.evaluate}와 같은 순서다. 문장마다 실행 문맥에서 UTIL 세션을 열고
 * {@link DBExecUtils#tryExecuteRecover}(끊긴 연결 다시 잇기) 안에서 {@code planQueryExecution}을 부른다. 계획기 설정 대화상자
 * ({@code DBEObjectConfigurator}, SWT UI 플러그인에만 있음)는 쓰지 않고 기본 설정을 넘긴다.
 * 실제 계획은 계획기로 구하지 않는다: 실행하면서 받는다({@code SQLActualPlanProvider}, {@link SqlEditorPanel}).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 계획기의 ANALYZE로 문장을 다시 실행하는 실제 계획(PostgreSQL)을 지움: 사용자 결정으로 실제 계획은 문장을 한 번만 실행하는
 *   연결에서만 보인다 (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 */
final class SqlPlanExplainer {

    private static final Log log = Log.getLog(SqlPlanExplainer.class);

    private SqlPlanExplainer() {
    }

    /**
     * Explains the queries one by one; each plan (or error) is passed to {@code consumer} in the EDT, then {@code done}.
     */
    static void explain(
        @NotNull DBCExecutionContext context,
        @NotNull DBCQueryPlanner planner,
        @NotNull List<SQLQuery> queries,
        @NotNull Consumer<ExecutionPlanView.Entry> consumer,
        @NotNull Runnable done
    ) {
        new AbstractJob("Estimated execution plan") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    for (SQLQuery query : queries) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        ExecutionPlanView.Entry entry = explain(monitor, context, planner, query.getText());
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
        @NotNull String query
    ) {
        DBCQueryPlannerConfiguration configuration = new DBCQueryPlannerConfiguration();
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
            return new ExecutionPlanView.Entry(query, null, ErrorText.describe(e), false);
        }
        if (plan[0] == null) {
            // tryExecuteRecover returns without an error when it was interrupted
            return new ExecutionPlanView.Entry(query, null, EditorMessages.editor_plan_canceled, false);
        }
        return new ExecutionPlanView.Entry(query, plan[0], null, false);
    }

    /**
     * The planner of a connected data source, or null when it has none.
     */
    @Nullable
    static DBCQueryPlanner getPlanner(@Nullable DBPDataSource dataSource) {
        return dataSource == null ? null : DBUtils.getAdapter(DBCQueryPlanner.class, dataSource);
    }
}
