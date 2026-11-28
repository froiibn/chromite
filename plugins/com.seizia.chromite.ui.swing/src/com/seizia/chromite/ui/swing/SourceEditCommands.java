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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SourceEditMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Object Explorer 오른쪽 클릭 메뉴의 개체 정의 편집 명령: "Modify", "Script as &gt; ALTER To &gt; New Query Editor Window / Clipboard",
 * 폴더의 "New View… / New Procedure… / New Function…".
 *
 * 설계: SSMS의 Modify처럼 객체의 정의를 실행할 수 있는 변경 스크립트로 일반 쿼리 창에 연다. 스크립트는 {@link SourceScripts}가
 * 진행 대화상자({@link ProgressDialog})의 작업 스레드에서 만들고, 창은 메인 창({@link ObjectExplorerPanel.QueryWindows#openSourceScript})이 연다.
 * 창은 보통 쿼리 창이므로 실행(F5)·저장·닫을 때 저장 확인이 다른 쿼리 창과 같고, 실행이 끝날 때마다 객체(Modify) 또는 폴더(New)를
 * 다시 읽는다. 메뉴 구성 요소를 이 클래스에 모아 여러 기능이 함께 고치는 {@link ObjectExplorerPanel}의 변경을 작은 연결 지점으로 줄였다.
 * 항목은 고른 노드의 종류에 해당할 때만 보이고, 해당하지만 지금 할 수 없으면 꺼진다({@link #update}, {@link ContextMenus}).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-source-editor.md)
 * - 2026-11-08 창을 열 때 보일 위치 없음(null)을 넘김: openSourceScript가 위치를 받게 됨 (docs/chromite/swing-routines.md)
 * - 2026-11-16 해당하는 노드에서만 보임(앱 변경 #137, docs/chromite/context-menus.md)
 * - 2026-11-22 오류 창의 첫 줄: 권한 오류면 권한 문구(앱 변경 #159, docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-28 스크립트를 만들기 전에 주 세션의 열린 트랜잭션을 물음({@link SourceScripts#confirmBindDefaultContext}, 다른 DB로 옮길 때만)
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 다른 DB로 옮기기 전 트랜잭션 질문")
 */
final class SourceEditCommands {

    private final Supplier<DBNNode> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Action modifyAction;
    private final JMenu alterToMenu;
    private final Action newViewAction;
    private final Action newProcedureAction;
    private final Action newFunctionAction;

    /**
     * @param selection    the selected Object Explorer node (or null)
     * @param queryWindows opens the query windows
     */
    SourceEditCommands(@NotNull Supplier<DBNNode> selection, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        this.selection = selection;
        this.queryWindows = queryWindows;
        modifyAction = createAction(SourceEditMessages.source_modify, () -> scriptAlter(false));
        modifyAction.putValue(Action.SHORT_DESCRIPTION, SourceEditMessages.source_modify_tip);
        alterToMenu = new JMenu(SourceEditMessages.source_alter_to);
        alterToMenu.add(createAction(CoreMessages.explorer_new_query_window, () -> scriptAlter(false)));
        alterToMenu.add(createAction(CoreMessages.explorer_clipboard, () -> scriptAlter(true)));
        newViewAction = createNewAction(SourceEditMessages.source_new_view, SourceEditMessages.source_new_view_title,
            SourceScripts.NewObjectKind.VIEW);
        newProcedureAction = createNewAction(SourceEditMessages.source_new_procedure, SourceEditMessages.source_new_procedure_title,
            SourceScripts.NewObjectKind.PROCEDURE);
        newFunctionAction = createNewAction(SourceEditMessages.source_new_function, SourceEditMessages.source_new_function_title,
            SourceScripts.NewObjectKind.FUNCTION);
    }

    @NotNull
    Action getModifyAction() {
        return modifyAction;
    }

    /**
     * The "ALTER To" submenu of "Script as".
     */
    @NotNull
    JMenu getAlterToMenu() {
        return alterToMenu;
    }

    @NotNull
    Action getNewViewAction() {
        return newViewAction;
    }

    @NotNull
    Action getNewProcedureAction() {
        return newProcedureAction;
    }

    @NotNull
    Action getNewFunctionAction() {
        return newFunctionAction;
    }

    /**
     * Shows the commands that apply to the node and enables those possible now (EDT, no database access).
     */
    void update(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        boolean appliesModify = SourceScripts.appliesModify(object);
        boolean canModify = SourceScripts.canModify(object);
        ContextMenus.set(modifyAction, appliesModify, canModify);
        ContextMenus.set(alterToMenu, appliesModify, canModify);
        // The template kinds depend on the folder and the driver only
        Set<SourceScripts.NewObjectKind> kinds = SourceScripts.getNewKinds(node);
        ContextMenus.set(newViewAction, kinds.contains(SourceScripts.NewObjectKind.VIEW), true);
        ContextMenus.set(newProcedureAction, kinds.contains(SourceScripts.NewObjectKind.PROCEDURE), true);
        ContextMenus.set(newFunctionAction, kinds.contains(SourceScripts.NewObjectKind.FUNCTION), true);
    }

    @Nullable
    private static DBSObject getObject(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseNode databaseNode && !(databaseNode instanceof DBNDataSource) && !(node instanceof DBNDatabaseFolder)
            ? databaseNode.getObject() : null;
    }

    /**
     * Modify / Script as ALTER: the ALTER script in a new query window that refreshes the object after each run, or the clipboard.
     */
    private void scriptAlter(boolean toClipboard) {
        DBNNode node = selection.get();
        DBSObject object = getObject(node);
        if (!(node instanceof DBNDatabaseNode objectNode) || object == null || !SourceScripts.canModify(object)) {
            return;
        }
        DBPDataSourceContainer container = objectNode.getDataSourceContainer();
        String title = toClipboard ? SourceEditMessages.source_script_alter : SourceEditMessages.source_modify;
        if (!SourceScripts.confirmBindDefaultContext(null, object)) {
            return;
        }
        String script = generate(title, monitor -> SourceScripts.generateAlter(monitor, object));
        if (script == null) {
            return;
        }
        if (toClipboard) {
            DBWorkbench.getPlatformUI().copyTextToClipboard(script, false);
        } else {
            queryWindows.openSourceScript(container, script, objectNode, null);
        }
    }

    @NotNull
    private Action createNewAction(@NotNull String name, @NotNull String title, @NotNull SourceScripts.NewObjectKind kind) {
        Action action = createAction(name, () -> {
            if (selection.get() instanceof DBNDatabaseFolder folder && SourceScripts.getNewKinds(folder).contains(kind)
                && folder.getValueObject() instanceof DBSObject container
                && SourceScripts.confirmBindDefaultContext(null, container)) {
                String script = generate(title, monitor -> SourceScripts.generateNew(monitor, folder, kind));
                if (script != null) {
                    queryWindows.openSourceScript(folder.getDataSourceContainer(), script, folder, null);
                }
            }
        });
        action.putValue(Action.SHORT_DESCRIPTION, SourceEditMessages.source_new_tip);
        return action;
    }

    /**
     * Generates a script in a job, waiting in a progress dialog (it reads the database). Errors are shown; null if it
     * failed or was canceled.
     */
    @Nullable
    private static String generate(@NotNull String title, @NotNull DBRRunnableWithReturn<String> generator) {
        try {
            return ProgressDialog.runTask(title, generator);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, ObjectScripts.errorMessage(e, CoreMessages.explorer_cant_generate), e);
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
