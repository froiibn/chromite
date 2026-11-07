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

import com.seizia.chromite.ext.mssql.model.SQLServerProcedure;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.ui.swing.internal.TraceMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

/**
 * "Trace Execution…" of a SQL Server procedure: Object Explorer's context menu and the Query menu of a query window opened
 * by Modify.
 *
 * 설계: 디버거의 Debug…({@link DebugCommands})와 같은 자리, 같은 방식이다. 메뉴는 늘 보이고 추적할 수 없는 노드에서는 꺼진다
 * ({@link SQLServerExecutionTrace#canTrace}). 열 때 진행 대화상자에서 루틴의 정의와 매개 변수를 읽고, 같은 루틴의 추적 탭이 열려
 * 있으면 그 탭을 보인다(열쇠 = 연결 + 데이터베이스 ID + 개체 ID). 메뉴를 고르는 것은 "실행해서 추적"하라는 뜻이라 탭을 열거나 보인 뒤
 * 바로 인수 대화상자를 연다. 권한이 없으면 서버 관리자가 실행할 GRANT 문을 사용자 언어로 보인다({@link #describeError}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sqlserver-execution-trace.md)
 */
final class ExecutionTraceCommands {

    private ExecutionTraceCommands() {
    }

    /**
     * True if the object is a SQL Server procedure that can be traced (EDT, no database access).
     */
    static boolean canTrace(@Nullable DBSObject object) {
        return SQLServerExecutionTrace.canTrace(object);
    }

    /**
     * The "Trace Execution…" action; it traces the procedure the supplier gives (enabled by the caller with {@link #canTrace}).
     */
    @NotNull
    static Action createAction(@NotNull Component parent, @NotNull Supplier<DBSObject> routine, @NotNull ObjectExplorerPanel.QueryWindows windows) {
        Action action = new AbstractAction(TraceMessages.trace_menu) {
            @Override
            public void actionPerformed(ActionEvent e) {
                DBSObject object = routine.get();
                if (canTrace(object)) {
                    open(parent, (SQLServerProcedure) object, windows);
                }
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, TraceMessages.trace_menu_tip);
        return action;
    }

    /**
     * Opens (or shows) the trace tab of the procedure and asks for the arguments of a run.
     */
    static void open(@NotNull Component parent, @NotNull SQLServerProcedure routine, @NotNull ObjectExplorerPanel.QueryWindows windows) {
        String routineName = DBUtils.getObjectFullName(routine, DBPEvaluationContext.UI);
        SQLServerExecutionTrace.Target target;
        try {
            target = ProgressDialog.runTask(TraceMessages.trace_progress_open, monitor -> SQLServerExecutionTrace.readTarget(monitor, routine));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            ErrorDialog.show(parent, NLS.bind(TraceMessages.trace_error_open, routineName), e.getMessage(), e);
            return;
        }
        String key = "trace:" + routine.getDataSource().getContainer().getId() + ':' //$NON-NLS-1$
            + target.routine().key().databaseId() + ':' + target.routine().key().objectId();
        ToolDocument document = windows.openToolDocument(key, () -> new ExecutionTracePanel(routine, target));
        if (document instanceof ExecutionTracePanel panel) {
            // After the tab is shown: the dialog belongs to it
            SwingUtilities.invokeLater(panel::run);
        }
    }

    /**
     * The message of a trace error; a missing permission becomes a notice with the statements an administrator runs.
     */
    @NotNull
    static String describeError(@NotNull DBException error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof SQLServerExecutionTrace.PrivilegeException privilege) {
                return NLS.bind(TraceMessages.trace_error_privileges, privilege.getLoginName(), String.join("\n", privilege.getStatements())); //$NON-NLS-1$
            }
        }
        return error.getMessage();
    }
}
