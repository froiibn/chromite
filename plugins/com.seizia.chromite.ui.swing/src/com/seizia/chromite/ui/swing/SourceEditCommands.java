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
 * 항목은 다른 탐색기 메뉴처럼 늘 보이고, 고른 노드에 맞지 않으면 꺼진다({@link #update}).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-source-editor.md)
 * - 2026-11-08 창을 열 때 보일 위치 없음(null)을 넘김: openSourceScript가 위치를 받게 됨 (docs/chromite/swing-routines.md)
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
     * Enables the commands that apply to the node (EDT, no database access).
     */
    void update(@Nullable DBNNode node) {
        boolean canModify = SourceScripts.canModify(getObject(node));
        modifyAction.setEnabled(canModify);
        alterToMenu.setEnabled(canModify);
        Set<SourceScripts.NewObjectKind> kinds = SourceScripts.getNewKinds(node);
        newViewAction.setEnabled(kinds.contains(SourceScripts.NewObjectKind.VIEW));
        newProcedureAction.setEnabled(kinds.contains(SourceScripts.NewObjectKind.PROCEDURE));
        newFunctionAction.setEnabled(kinds.contains(SourceScripts.NewObjectKind.FUNCTION));
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
            if (selection.get() instanceof DBNDatabaseFolder folder && SourceScripts.getNewKinds(folder).contains(kind)) {
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
