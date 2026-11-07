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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.objects.ObjectMaintenanceActions;
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNEvent;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Object Explorer commands Truncate Table…, Enable Trigger, Disable Trigger, Refresh Materialized View and Compile on the
 * selected objects.
 *
 * 설계: {@link ExplorerObjectCommands}(삭제·이름 바꾸기)와 같은 나눔이다. 탐색기에는 메뉴 항목과 갱신 호출만 두고, 문 만들기·실행은 모델 번들의
 * {@link ObjectMaintenanceActions}(모델 도구 처리기의 SQL)가 맡는다. 흐름도 같다: 확인 → 스크립트 미리 보기({@link ScriptPreviewDialog#confirmChange},
 * 취소가 기본 단추) → 열린 트랜잭션 질문({@link ExitTransactionCheck#confirmObjectChange}, 사용자 규칙 "트랜잭션 안전") → 실행.
 * <ul>
 *     <li>TRUNCATE는 #120의 규칙(쿼리 창에서 TRUNCATE는 늘 확인)을 따라 미리 보기 전에 경고 확인(Truncate / 취소, 취소가 기본)을 한 번 더 한다.
 *     행이 모두 지워지고 되돌릴 수 없다는 것과, DDL을 바로 커밋하는 DB(MySQL·MariaDB·Oracle, {@link ObjectMaintenanceActions#isDdlCommitting})는
 *     암시적 커밋이라 롤백할 수 없다는 것을 적는다. 여러 테이블이면 목록을 보인다.</li>
 *     <li>트리거·구체화된 뷰는 데이터를 지우지 않으므로 미리 보기 창이 확인이다(삭제 미리 보기와 같은 머리글).</li>
 *     <li>메뉴 항목은 선택한 객체가 모두 그 명령을 지원하고 한 연결에 있을 때만 보인다(SQL Server 에이전트 메뉴와 같은 방식).
 *     트리거는 모델이 읽은 상태가 이미 그 상태면 그 항목을 끈다.</li>
 *     <li>트리거를 바꾼 뒤에는 트리거 폴더를 다시 읽어 상태(속성)가 맞게 한다(SWT 도구의 {@code needsRefreshOnFinish}).</li>
 *     <li>Compile(Oracle 저장 코드·뷰·형식, Altibase 뷰·프로시저·패키지): 실행 뒤 객체의 유효 상태를 다시 읽고, Oracle은 컴파일 오류를
 *     {@link CompileErrorsDialog}로 보인다(오류에서 Modify 창으로 간다). 오류가 없으면 성공을 알린다(SWT {@code CompileHandler}와 같음).
 *     미리 보기와 열린 트랜잭션 질문은 다른 명령과 같다(Oracle·Altibase의 ALTER … COMPILE도 DDL이라 암시적으로 커밋한다).</li>
 *     <li>제한: 읽기 전용 연결과 메타데이터 변경 제한이면 실행하지 않고 알린다({@link ExplorerObjectCommands}와 같은 판단).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-object-actions.md "유지 관리 명령")
 * - 2026-11-08 Compile 명령과 컴파일 결과(SWT 감사 O11, A6), Altibase TRUNCATE·구체화된 뷰 새로 고침은 모델 쪽 지원으로 켜짐(A5)
 *   (docs/chromite/swing-routines.md)
 */
final class ObjectMaintenanceCommands {

    private static final Log log = Log.getLog(ObjectMaintenanceCommands.class);

    /**
     * Rows of the table list in the truncate confirmation before it scrolls.
     */
    private static final int LIST_ROWS = 10;

    private final ObjectExplorerPanel explorer;
    private final Supplier<List<DBNNode>> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Map<ObjectMaintenanceActions.Action, JMenuItem> items = new EnumMap<>(ObjectMaintenanceActions.Action.class);

    ObjectMaintenanceCommands(
        @NotNull ObjectExplorerPanel explorer,
        @NotNull Supplier<List<DBNNode>> selection,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        this.explorer = explorer;
        this.selection = selection;
        this.queryWindows = queryWindows;
        addItem(ObjectMaintenanceActions.Action.TRUNCATE, ObjectMaintenanceMessages.objmaint_truncate,
            ObjectMaintenanceMessages.objmaint_truncate_tip);
        addItem(ObjectMaintenanceActions.Action.ENABLE_TRIGGER, ObjectMaintenanceMessages.objmaint_enable_trigger,
            ObjectMaintenanceMessages.objmaint_enable_trigger_tip);
        addItem(ObjectMaintenanceActions.Action.DISABLE_TRIGGER, ObjectMaintenanceMessages.objmaint_disable_trigger,
            ObjectMaintenanceMessages.objmaint_disable_trigger_tip);
        addItem(ObjectMaintenanceActions.Action.REFRESH_MATERIALIZED_VIEW, ObjectMaintenanceMessages.objmaint_refresh_mview,
            ObjectMaintenanceMessages.objmaint_refresh_mview_tip);
        addItem(ObjectMaintenanceActions.Action.COMPILE, ObjectMaintenanceMessages.objmaint_compile, ObjectMaintenanceMessages.objmaint_compile_tip);
    }

    private void addItem(@NotNull ObjectMaintenanceActions.Action action, @NotNull String label, @NotNull String tip) {
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(tip);
        item.addActionListener(e -> run(action));
        item.setVisible(false);
        items.put(action, item);
    }

    void addTo(@NotNull JPopupMenu menu) {
        for (JMenuItem item : items.values()) {
            menu.add(item);
        }
    }

    /**
     * Shows the items that apply to the whole selection. EDT, model data in memory only.
     */
    void update(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = getObjects(nodes);
        for (Map.Entry<ObjectMaintenanceActions.Action, JMenuItem> entry : items.entrySet()) {
            ObjectMaintenanceActions.Action action = entry.getKey();
            boolean visible = objects != null;
            boolean enabled = false;
            if (objects != null) {
                for (DBSObject object : objects) {
                    if (!ObjectMaintenanceActions.supports(action, object)) {
                        visible = false;
                        break;
                    }
                    enabled |= changesState(action, object);
                }
            }
            entry.getValue().setVisible(visible);
            entry.getValue().setEnabled(visible && enabled);
        }
    }

    /**
     * False only for a trigger whose state (as read) already is the one the command sets.
     */
    private static boolean changesState(@NotNull ObjectMaintenanceActions.Action action, @NotNull DBSObject object) {
        Boolean enabled = ObjectMaintenanceActions.isTriggerEnabled(object);
        return switch (action) {
            case ENABLE_TRIGGER -> !Boolean.TRUE.equals(enabled);
            case DISABLE_TRIGGER -> !Boolean.FALSE.equals(enabled);
            default -> true;
        };
    }

    private void run(@NotNull ObjectMaintenanceActions.Action action) {
        List<DBNNode> nodes = selection.get();
        List<DBSObject> objects = getObjects(nodes);
        if (objects == null) {
            return;
        }
        objects.removeIf(object -> !ObjectMaintenanceActions.supports(action, object) || !changesState(action, object));
        if (objects.isEmpty()) {
            return;
        }
        String title = getTitle(action);
        DBPDataSourceContainer container = getContainer(objects);
        Window window = SwingUtilities.getWindowAncestor(explorer);
        if (container == null) {
            JOptionPane.showMessageDialog(window, NavigatorMessages.navigator_delete_different_connections, title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (container.isConnectionReadOnly() || objects.stream().anyMatch(DBUtils::isReadOnly)) {
            JOptionPane.showMessageDialog(window, SwingPlatformUI.createMessageComponent(
                NLS.bind(NavigatorMessages.navigator_change_restricted, container.getName())), title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<ObjectMaintenanceActions.Statement> statements;
        boolean[] ddlCommits = new boolean[1];
        try {
            statements = ProgressDialog.runTask(NavigatorMessages.navigator_change_progress, monitor -> {
                ddlCommits[0] = ObjectMaintenanceActions.isDdlCommitting(monitor, objects.getFirst());
                return ObjectMaintenanceActions.generateScript(monitor, action, objects);
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
            return;
        }
        if (action == ObjectMaintenanceActions.Action.TRUNCATE && !confirmTruncate(window, objects, container, ddlCommits[0])) {
            return;
        }
        StringBuilder script = new StringBuilder();
        for (ObjectMaintenanceActions.Statement statement : statements) {
            script.append(statement.sql()).append(";\n"); //$NON-NLS-1$
        }
        String header = NLS.bind(ddlCommits[0]
            ? NavigatorMessages.navigator_change_preview_header_ddl_commit
            : NavigatorMessages.navigator_change_preview_header, container.getName());
        ScriptPreviewDialog.Result result = ScriptPreviewDialog.confirmChange(explorer, title, header, script.toString());
        if (result == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(container, script.toString(), 0, false);
            return;
        }
        if (result != ScriptPreviewDialog.Result.EXECUTE || !ExitTransactionCheck.confirmObjectChange(explorer, container)) {
            return;
        }
        // The nodes are looked up before executing (the folder of a changed trigger is read again afterwards)
        Set<DBNDatabaseNode> refreshNodes = new LinkedHashSet<>();
        if (action == ObjectMaintenanceActions.Action.ENABLE_TRIGGER || action == ObjectMaintenanceActions.Action.DISABLE_TRIGGER) {
            for (DBNNode node : nodes) {
                if (node.getParentNode() instanceof DBNDatabaseNode parent) {
                    refreshNodes.add(parent);
                }
            }
        }
        boolean executed = false;
        try {
            ProgressDialog.runTask(NavigatorMessages.navigator_change_execute_progress, monitor -> {
                ObjectMaintenanceActions.execute(monitor, statements);
                return null;
            });
            executed = true;
        } catch (DBInterruptedException e) {
            // Canceled between statements: the statements before stay executed
        } catch (ObjectMaintenanceActions.ExecutionException e) {
            DBWorkbench.getPlatformUI().showError(title, NLS.bind(ObjectMaintenanceMessages.objmaint_error_statement,
                DBUtils.getObjectFullName(e.getStatement().object(), DBPEvaluationContext.UI), e.getStatement().sql()), e);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
        }
        // Also after a failure: statements of other databases before it may have run
        refreshNodes.forEach(this::refresh);
        if (action == ObjectMaintenanceActions.Action.COMPILE) {
            showCompileResult(nodes, objects, executed);
        }
    }

    /**
     * After Compile: reads the objects' state again (also after a failure, an object before it may have been compiled)
     * and, when the compile ran, shows the Oracle compile errors or that it succeeded. Other databases report compile
     * errors as the statement's error (shown already).
     */
    private void showCompileResult(@NotNull List<DBNNode> nodes, @NotNull List<DBSObject> objects, boolean executed) {
        String title = getTitle(ObjectMaintenanceActions.Action.COMPILE);
        List<CompileErrorsDialog.Row> rows = new ArrayList<>();
        try {
            ProgressDialog.runTask(ObjectMaintenanceMessages.objmaint_compile_reading, monitor -> {
                ObjectMaintenanceActions.refreshObjectStates(monitor, objects);
                for (DBNNode node : nodes) {
                    if (node instanceof DBNDatabaseNode databaseNode && objects.contains(databaseNode.getObject())) {
                        String name = DBUtils.getObjectFullName(databaseNode.getObject(), DBPEvaluationContext.UI);
                        for (OracleCompileErrors.CompileError error
                            : ObjectMaintenanceActions.readCompileErrors(monitor, List.of(databaseNode.getObject()))) {
                            rows.add(new CompileErrorsDialog.Row(databaseNode, name, error));
                        }
                    }
                }
                return null;
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
            return;
        }
        if (!executed) {
            return;
        }
        if (rows.isEmpty()) {
            String message = objects.size() == 1
                ? NLS.bind(ObjectMaintenanceMessages.objmaint_compile_done_one, DBUtils.getObjectFullName(objects.getFirst(), DBPEvaluationContext.UI))
                : NLS.bind(ObjectMaintenanceMessages.objmaint_compile_done_many, objects.size());
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(explorer), message, title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long failed = rows.stream().map(CompileErrorsDialog.Row::node).distinct().count();
        CompileErrorsDialog.show(explorer, title, NLS.bind(ObjectMaintenanceMessages.objmaint_compile_errors, rows.size(), failed),
            rows, queryWindows);
    }

    /**
     * The TRUNCATE warning: what is emptied, that it cannot be undone and, where TRUNCATE commits implicitly, that it
     * cannot be rolled back. Cancel is the default button.
     */
    private static boolean confirmTruncate(
        @NotNull Window window,
        @NotNull List<DBSObject> tables,
        @NotNull DBPDataSourceContainer container,
        boolean ddlCommits
    ) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        String question = tables.size() == 1
            ? NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_question_one,
                DBUtils.getObjectFullName(tables.getFirst(), DBPEvaluationContext.UI), container.getName())
            : NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_question_many, tables.size(), container.getName());
        panel.add(new JLabel(question), BorderLayout.NORTH);
        if (tables.size() > 1) {
            DefaultListModel<String> model = new DefaultListModel<>();
            for (DBSObject table : tables) {
                model.addElement(DBUtils.getObjectFullName(table, DBPEvaluationContext.UI));
            }
            JList<String> list = new JList<>(model);
            list.setVisibleRowCount(Math.min(LIST_ROWS, tables.size()));
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
        }
        String note = ddlCommits
            ? NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_note_commit, container.getDriver().getName())
            : ObjectMaintenanceMessages.objmaint_truncate_note;
        panel.add(SwingPlatformUI.createMessageComponent(note), BorderLayout.SOUTH);
        Object[] buttons = {ObjectMaintenanceMessages.objmaint_truncate_button, CoreMessages.common_cancel};
        // Cancel is the default button: Enter must not empty a table
        int answer = JOptionPane.showOptionDialog(window, panel, getTitle(ObjectMaintenanceActions.Action.TRUNCATE),
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, buttons, buttons[1]);
        return answer == 0;
    }

    @NotNull
    private static String getTitle(@NotNull ObjectMaintenanceActions.Action action) {
        return switch (action) {
            case TRUNCATE -> ObjectMaintenanceMessages.objmaint_truncate_title;
            case ENABLE_TRIGGER -> ObjectMaintenanceMessages.objmaint_enable_trigger_title;
            case DISABLE_TRIGGER -> ObjectMaintenanceMessages.objmaint_disable_trigger_title;
            case REFRESH_MATERIALIZED_VIEW -> ObjectMaintenanceMessages.objmaint_refresh_mview_title;
            case COMPILE -> ObjectMaintenanceMessages.objmaint_compile_title;
        };
    }

    /**
     * Reads the folder again: through Object Explorer when it shows it (as F5), otherwise in the navigator model only.
     */
    private void refresh(@NotNull DBNDatabaseNode node) {
        if (explorer.refreshNavigatorNode(node)) {
            return;
        }
        try {
            ProgressDialog.runTask(NavigatorMessages.navigator_change_execute_progress, monitor -> node.refreshNode(monitor, DBNEvent.FORCE_REFRESH));
        } catch (DBException e) {
            log.debug("Refresh of " + node.getNodeDisplayName() + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The database objects of the nodes, or null if a node is not one.
     */
    @Nullable
    private static List<DBSObject> getObjects(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            if (!(node instanceof DBNDatabaseItem item) || item.getObject() == null) {
                return null;
            }
            objects.add(item.getObject());
        }
        return objects.isEmpty() ? null : objects;
    }

    /**
     * The connection of all objects, or null if they belong to several.
     */
    @Nullable
    private static DBPDataSourceContainer getContainer(@NotNull List<DBSObject> objects) {
        DBPDataSourceContainer container = null;
        for (DBSObject object : objects) {
            DBPDataSource dataSource = object.getDataSource();
            if (dataSource == null || container != null && container != dataSource.getContainer()) {
                return null;
            }
            container = dataSource.getContainer();
        }
        return container;
    }
}
