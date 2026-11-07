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

import com.seizia.chromite.ext.oracle.model.OraclePackage;
import com.seizia.chromite.ext.oracle.model.OracleProcedurePackaged;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.admin.routines.ProcedureCallScript;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.RoutineMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Object Explorer commands on routines: "Execute Procedure…" (SSMS Execute Stored Procedure), "Go to Source Code" of a
 * packaged Oracle routine, and opening the Modify window at compile errors.
 *
 * 설계: {@link SourceEditCommands}처럼 탐색기에는 메뉴 항목과 켜고 끄기만 둔다. 스크립트는 모델 번들이 만든다
 * ({@link ProcedureCallScript}, {@link OracleCompileErrors}). 창은 모두 일반 쿼리 창이다.
 * <ul>
 *     <li>Execute Procedure…: 매개 변수를 읽고(진행 대화상자) {@link ExecuteProcedureDialog}로 값을 받아, 루틴의 DB·스키마를 쿼리 창의 기본으로
 *     맞춘 뒤({@link SourceScripts#bindDefaultContext}, Modify와 같음) 새 쿼리 창에서 실행하거나 열기만 한다. 실행은 새 창의 세션에서
 *     F5와 같은 경로라 실행 제한·결과·Messages·트랜잭션 표시가 다른 쿼리와 같다. SWT는 {@code :이름} 매개 변수가 든 호출문을 콘솔에 열었다.</li>
 *     <li>Go to Source Code(SWT {@code PackageNavigateHandler}): 패키지의 Modify 창을 열고 그 루틴의 머리글({@code PROCEDURE 이름}, 주석 줄 제외)을
 *     본문에서 고른다. 겹친(overload) 루틴은 겹침 번호 순서의 머리글이다. 본문이 없으면 선언에서 찾는다.</li>
 *     <li>오류로 가기: 컴파일 결과의 오류에서 객체의 Modify 창을 열고, 스크립트 안의 그 단위가 시작하는 곳을 기준으로 오류 줄을 표시하고 고른 오류로 간다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Execute Procedure…(B-S42), Go to Source Code(O13), 오류로 가기(O11) (docs/chromite/swing-routines.md)
 */
final class RoutineCommands {

    private final Component parent;
    private final Supplier<DBNNode> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Action executeAction;
    private final Action goToSourceAction;
    private final JMenuItem goToSourceItem;

    RoutineCommands(@NotNull Component parent, @NotNull Supplier<DBNNode> selection, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        this.parent = parent;
        this.selection = selection;
        this.queryWindows = queryWindows;
        executeAction = createAction(RoutineMessages.routine_execute, this::execute);
        executeAction.putValue(Action.SHORT_DESCRIPTION, RoutineMessages.routine_execute_tip);
        goToSourceAction = createAction(RoutineMessages.routine_go_to_source, this::goToSource);
        goToSourceAction.putValue(Action.SHORT_DESCRIPTION, RoutineMessages.routine_go_to_source_tip);
        goToSourceItem = new JMenuItem(goToSourceAction);
    }

    @NotNull
    Action getExecuteAction() {
        return executeAction;
    }

    /**
     * The Go to Source Code item: shown only on a routine of an Oracle package (as the database-specific maintenance items).
     */
    @NotNull
    JMenuItem getGoToSourceItem() {
        return goToSourceItem;
    }

    /**
     * Enables the commands that apply to the node (EDT, no database access).
     */
    void update(@Nullable DBNNode node) {
        DBSObject object = node instanceof DBNDatabaseNode databaseNode ? databaseNode.getObject() : null;
        executeAction.setEnabled(ProcedureCallScript.canExecute(object));
        boolean packaged = object instanceof OracleProcedurePackaged;
        goToSourceAction.setEnabled(packaged && SourceScripts.canModify(((OracleProcedurePackaged) object).getParentObject()));
        goToSourceItem.setVisible(packaged);
    }

    //////////////////////////////////////////////////////////
    // Execute Procedure…

    private void execute() {
        if (!(selection.get() instanceof DBNDatabaseNode node) || !(node.getObject() instanceof DBSProcedure procedure)
            || !ProcedureCallScript.canExecute(procedure)) {
            return;
        }
        DBPDataSourceContainer container = node.getDataSourceContainer();
        String title = RoutineMessages.routine_execute_progress;
        List<DBSProcedureParameter> parameters = run(title, monitor -> ProcedureCallScript.getParameters(monitor, procedure));
        if (parameters == null) {
            return;
        }
        ExecuteProcedureDialog.Result result = ExecuteProcedureDialog.show(parent, DBUtils.getObjectFullName(procedure, DBPEvaluationContext.UI),
            parameters, ProcedureCallScript.capturesOutputs(procedure));
        if (result == null) {
            return;
        }
        String script = run(title, monitor -> {
            // The new window runs in the routine's database and schema, as a Modify window
            SourceScripts.bindDefaultContext(monitor, procedure.getParentObject() != null ? procedure.getParentObject() : procedure);
            return ProcedureCallScript.generate(monitor, procedure, result.values());
        });
        if (script != null) {
            queryWindows.openScript(container, script, 0, result.execute());
        }
    }

    //////////////////////////////////////////////////////////
    // Go to Source Code

    private void goToSource() {
        if (!(selection.get() instanceof DBNDatabaseNode node) || !(node.getObject() instanceof OracleProcedurePackaged procedure)) {
            return;
        }
        OraclePackage oraclePackage = procedure.getParentObject();
        DBNDatabaseNode packageNode = findNode(node, oraclePackage);
        if (packageNode == null || !SourceScripts.canModify(oraclePackage)) {
            return;
        }
        String script = run(RoutineMessages.routine_go_to_source, monitor -> SourceScripts.generateAlter(monitor, oraclePackage));
        if (script == null) {
            return;
        }
        int[] header = findRoutineHeader(script, oraclePackage.getName(), procedure);
        SqlEditorPanel.SourcePosition position = header == null ? null : new SqlEditorPanel.SourcePosition(header[0], header[1], List.of());
        queryWindows.openSourceScript(packageNode.getDataSourceContainer(), script, packageNode, position);
    }

    /**
     * The start and end of the routine's header in the package script (the body, else the spec), or null.
     */
    @Nullable
    private static int[] findRoutineHeader(@NotNull String script, @NotNull String packageName, @NotNull OracleProcedurePackaged procedure) {
        int start = OracleCompileErrors.findSourceStart(script, packageName, "PACKAGE BODY"); //$NON-NLS-1$
        if (start < 0) {
            start = OracleCompileErrors.findSourceStart(script, packageName, "PACKAGE"); //$NON-NLS-1$
        }
        if (start < 0) {
            return null;
        }
        // As SWT PackageNavigateHandler: "PROCEDURE name" at the start of a line that is not a comment
        String name = Pattern.quote(procedure.getName());
        Pattern pattern = Pattern.compile("(?m)^(?!\\s*--)\\s*(" + procedure.getProcedureType().name() + "\\s+\"?" + name + "\"?)(?![A-Za-z0-9_$#])", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(script);
        int wanted = procedure.getOverloadNumber() == null ? 1 : Math.max(procedure.getOverloadNumber(), 1);
        int found = 0;
        int[] first = null;
        int from = start;
        while (matcher.find(from)) {
            int[] range = {matcher.start(1), matcher.end(1)};
            if (first == null) {
                first = range;
            }
            if (++found == wanted) {
                return range;
            }
            from = matcher.end();
        }
        return first;
    }

    /**
     * The navigator node of an object among the node's parents (a packaged routine's package).
     */
    @Nullable
    private static DBNDatabaseNode findNode(@NotNull DBNNode node, @NotNull DBSObject object) {
        for (DBNNode parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() == object) {
                return databaseNode;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Go to Error

    /**
     * Opens the Modify window of a compiled object with its error lines marked and the caret at one error.
     *
     * @param errors   the object's errors
     * @param selected the error to go to
     */
    static void openModifyAtError(
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @NotNull DBNDatabaseNode node,
        @NotNull List<OracleCompileErrors.CompileError> errors,
        @NotNull OracleCompileErrors.CompileError selected
    ) {
        DBSObject object = node.getObject();
        if (object == null || !SourceScripts.canModify(object)) {
            return;
        }
        String script = run(RoutineMessages.routine_compile_go_to_error, monitor -> SourceScripts.generateAlter(monitor, object));
        if (script == null) {
            return;
        }
        List<Integer> lines = new ArrayList<>();
        int caret = 0;
        int caretEnd = 0;
        for (OracleCompileErrors.CompileError error : errors) {
            int sourceStart = OracleCompileErrors.findSourceStart(script, error.name(), error.type());
            if (sourceStart < 0) {
                continue;
            }
            int offset = OracleCompileErrors.getErrorOffset(script, sourceStart, error);
            if (error.error()) {
                lines.add(lineOf(script, offset));
            }
            if (error == selected) {
                // The rest of the line is selected: the editor's current-line color covers the error color of the caret's line
                int lineEnd = script.indexOf('\n', offset);
                caret = offset;
                caretEnd = lineEnd < 0 ? script.length() : lineEnd;
            }
        }
        queryWindows.openSourceScript(node.getDataSourceContainer(), script, node, new SqlEditorPanel.SourcePosition(caret, caretEnd, lines));
    }

    /**
     * 0-based line of an offset.
     */
    private static int lineOf(@NotNull String text, int offset) {
        int line = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Runs a task in a job, waiting in a progress dialog (it reads the database). Errors are shown; null if it failed or
     * was canceled.
     */
    @Nullable
    private static <T> T run(@NotNull String title, @NotNull DBRRunnableWithReturn<T> task) {
        try {
            return ProgressDialog.runTask(title, task);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, CoreMessages.explorer_cant_generate, e);
            return null;
        }
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable task) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
    }
}
