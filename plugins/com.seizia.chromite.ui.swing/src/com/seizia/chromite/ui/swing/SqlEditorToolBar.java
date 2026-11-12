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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.ui.swing.internal.ToolbarMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 메인 창 둘째 줄의 SQL Editor 도구 막대(SSMS): 활성 쿼리 창에 적용한다.
 * [서버 표시 (연결 변경)] │ [DB/스키마 ▾] │ [▶ Execute] [■] │ [예상 계획] [실제 계획] │ [Commit] [Roll Back] [● 커밋 방식] │ [Comment] [Uncomment] │
 * [Decrease Indent] [Increase Indent] │ [Format SQL] │ [Query History].
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-07): SSMS처럼 메인 창 위에 도구 막대 두 줄(Standard, SQL Editor)을 두고 쿼리 창마다의 도구 막대를 없앤다.
 *     편집기 영역이 한 줄 높아지고, 단추가 늘 같은 자리에 있다.</li>
 *     <li>실행·중지·예상 계획·실제 계획 단추는 메인 창 Query 메뉴와 같은 {@link Action}을 쓴다. 그래서 켜짐과 단축키(F5, Alt+Break, Ctrl+L,
 *     Ctrl+M)가 메뉴와 같고, 켜짐은 메인 창이 활성 쿼리 창({@link SqlEditorPanel#canExecute()} 등)으로 정한다. 단추의 도구 설명은
 *     쿼리 창 도구 막대에 있던 것(단축키 포함)이다. 실제 계획은 메뉴의 확인 표시와 같은 동작의 선택 상태({@link Action#SELECTED_KEY})다.</li>
 *     <li>Commit / Roll Back 단추도 Query 메뉴와 같은 동작이다(SWT 메인 도구 막대의 커밋·롤백 단추). 활성 쿼리 창에 열린 트랜잭션이 있을 때만 켜진다.</li>
 *     <li>커밋 방식 단추(B-S18, SWT 도구 막대의 자동 커밋 토글)는 Query &gt; Auto-Commit과 같은 동작이다. 지금 방식을 상태 줄과 같은 점(자동 커밋 초록,
 *     수동 커밋 빨강)과 글자로 보이고, 누르면 다른 방식으로 바꾼다({@link CommitModeSwitch}). Swing 아이콘 팩에 트랜잭션 방식 아이콘이 없어
 *     상태 줄의 점을 쓴다. 글자가 지금 방식을 말하므로 눌림 상태(토글 단추)는 쓰지 않는다.</li>
 *     <li>서버 표시와 DB 콤보는 쿼리 창의 것이다({@link SqlEditorPanel#getServerIndicator()}, {@link SqlEditorPanel#getSchemaCombo()}):
 *     상태·리스너·켜짐이 창마다의 상태라 한 쌍을 두고 바꿔 끼우는 대신, 활성 창의 것을 이 막대의 자리에 끼운다(결과 탭·쿼리 상태 줄과 같은 방식).
 *     그래서 탭을 바꾸면 서버·DB가 따라온다. 쿼리 창이 없으면 같은 폭의 빈 표시와 빈 콤보(꺼짐)를 끼워 막대가 움직이지 않게 한다. 끼울 때는
 *     빼낸 것의 위치(index)에 넣으므로 막대 안 순서와 상관없다.</li>
 *     <li>서버 이름은 고를 수 없는 표시다(앱 변경 #69, 사용자 결정 2026-11-12, {@link QueryWindowServerIndicator}).
 *     예전 연결 콤보는 클릭이나 방향키 하나로 같은 글을 다른 서버에서 돌게 했다. 창의 서버는 표시 오른쪽 끝의 연결 변경 단추(앱 변경 #73)로만
 *     바뀐다. 단추는 Query &gt; Change Connection…과 같은 동작 하나이고 이 막대가 하나를 만들어 활성 창의 표시에 옮겨 끼운다
 *     ({@link QueryWindowServerIndicator#setChangeButton}): 쉬는 창의 표시가 단추를 가지고 있으면 그 창이 다시 그릴 때 활성 창의 단추를
 *     빼앗기 때문에 빼낸 표시에서는 단추를 거둔다.</li>
 *     <li>서버 표시와 DB 콤보가 맨 앞이다(사용자 결정 2026-11-08): 무엇에 실행할지(서버, DB/스키마)를 본 뒤 실행한다. 좁은 창에서는 둘이 먼저 줄고
 *     넘치는 단추는 뒤쪽(Query History)부터 메뉴로 가므로 실행 단추는 둘이 최소 폭이 되어도 남는다. 서버 표시는 포커스를 받지 않으므로
 *     키보드 Tab은 DB/스키마 콤보부터다.</li>
 *     <li>편집 명령(주석, 들여쓰기, 서식)과 Query History는 이 막대의 동작이고, 활성 쿼리 창이 없으면 꺼진다. 편집 명령은
 *     Edit &gt; Advanced와 같은 명령({@link SqlEditorPanel.SqlTextArea#runCommand})이다.</li>
 *     <li>활성 문서가 쿼리 창이 아니거나 문서가 없어도 막대는 보이고 모든 컨트롤이 꺼진다(SSMS).</li>
 *     <li>모양은 주 도구 막대와 같다(캔버스 위, 투명, {@link OverflowToolBar}로 좁은 창에서 콤보를 먼저 줄이고 넘치는 단추는 » 메뉴).
 *     여백은 28px 콤보가 들어가는 {@code size.toolbar.editor}라 주 도구 막대({@code size.toolbar.main})와 같은 높이(밀도 B 32)다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 쿼리 창 도구 막대를 메인 창 둘째 줄로 옮김(사용자 결정) (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-07 Commit / Roll Back 단추 (docs/chromite/transactions.md)
 * - 2026-11-08 커밋 방식 단추(활성 쿼리 창 세션의 자동/수동 커밋 전환, B-S18) (docs/chromite/transactions.md "커밋 방식 전환")
 * - 2026-11-08 실제 계획을 주지 않는 연결에서는 실제 계획 단추가 꺼지고 도구 설명이 이유를 말함({@link #setActualPlanUnsupported})
 *   (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 * - 2026-11-08 연결·DB 콤보를 맨 앞으로, 연결 콤보 폭 260 → 220(앱 변경 #3, 사용자 결정) (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-09 도구 설명의 키 글자를 단축키에서 만듦({@link UiKeys}, 두 키 조합은 {@code SqlTextArea.getChordText}, 앱 변경 #24) (docs/chromite/shortcuts.md)
 * - 2026-11-12 Actual Plan·커밋 방식 글자 단추에 도구 단추 여백({@link UiIcons#useToolButtonMargin}): UI 글꼴 13의 글자 줄(18)이 아이콘(16)보다
 *   높아 단추가 26이 되던 것을 아이콘 단추와 같은 24로 (앱 변경 #67, docs/chromite/design-spec.md "적용 현황 — 운영체제 UI 글꼴 13")
 * - 2026-11-12 연결 콤보 자리에 활성 창의 서버 표시, 빈 연결 콤보({@code emptyConnectionCombo})를 빈 서버 표시로(앱 변경 #69)
 *   (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 * - 2026-11-12 활성 창의 서버 표시에 연결 변경 단추(Query &gt; Change Connection…과 같은 동작, 앱 변경 #73)
 *   (docs/chromite/swing-frontend.md "쿼리 창의 연결 변경")
 */
final class SqlEditorToolBar {

    private final JToolBar toolBar = new OverflowToolBar();
    /**
     * Shown in the server and picker places while no query window is active.
     */
    private final QueryWindowServerIndicator emptyServerIndicator = new QueryWindowServerIndicator(null);
    private final JComboBox<Object> emptySchemaCombo = createEmptyCombo(SqlEditorPanel.SCHEMA_PICKER_WIDTH);
    private final List<Action> editorActions = new ArrayList<>();
    private QueryWindowServerIndicator serverIndicator = emptyServerIndicator;
    private JComponent schemaCombo = emptySchemaCombo;
    // Query > Change Connection…, in the active window's server indicator
    private final JButton changeConnectionButton;
    private final JButton commitModeButton;
    private final JToggleButton actualPlanButton;
    @Nullable
    private SqlEditorPanel editor;

    /**
     * @param changeConnectionAction Query &gt; Change Connection…
     * @param executeAction       Query &gt; Execute
     * @param cancelAction        Query &gt; Cancel Executing Query
     * @param estimatedPlanAction Query &gt; Display Estimated Execution Plan
     * @param actualPlanAction    Query &gt; Include Actual Execution Plan (with {@link Action#SELECTED_KEY})
     * @param commitAction        Query &gt; Commit
     * @param rollbackAction      Query &gt; Roll Back
     * @param commitModeAction    Query &gt; Auto-Commit (with {@link Action#SELECTED_KEY})
     * @param openQueryHistory    Query &gt; Query History
     */
    SqlEditorToolBar(
        @NotNull Action changeConnectionAction,
        @NotNull Action executeAction,
        @NotNull Action cancelAction,
        @NotNull Action estimatedPlanAction,
        @NotNull Action actualPlanAction,
        @NotNull Action commitAction,
        @NotNull Action rollbackAction,
        @NotNull Action commitModeAction,
        @NotNull Runnable openQueryHistory
    ) {
        // On the Islands canvas like the standard tool bar; size.toolbar.editor fits the size.control 28 combos
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.EDITOR_TOOL_BAR_PADDING));
        // No icon of its own in the packs: the server icon of the Select Server button, which chooses a window's server too
        changeConnectionButton = UiIcons.createActionButton(changeConnectionAction, "connection"); //$NON-NLS-1$
        changeConnectionButton.getAccessibleContext().setAccessibleName(String.valueOf(changeConnectionAction.getValue(Action.NAME)));
        // The pickers first (user decision 2026-11-08): what the run applies to, then the run
        toolBar.add(serverIndicator);
        toolBar.addSeparator();
        toolBar.add(schemaCombo);
        toolBar.addSeparator();
        toolBar.add(createButton(UiIcons.createToolButton(executeAction, "run"), EditorMessages.editor_sql_execute_tip)); //$NON-NLS-1$
        toolBar.add(createButton(UiIcons.createActionButton(cancelAction, "stop"), EditorMessages.editor_sql_cancel_tip)); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createButton(UiIcons.createActionButton(estimatedPlanAction, "plan"), //$NON-NLS-1$
            UiKeys.format(EditorMessages.editor_sql_estimated_plan_tip, estimatedPlanAction)));
        actualPlanButton = createActualPlanButton(actualPlanAction);
        toolBar.add(actualPlanButton);
        toolBar.addSeparator();
        // The shortcut differs by platform (MainFrame), so the tool tip takes it from the action
        toolBar.add(createButton(UiIcons.createActionButton(commitAction, "commit"), //$NON-NLS-1$
            UiKeys.format(EditorMessages.editor_sql_commit_tip, commitAction)));
        toolBar.add(createButton(UiIcons.createActionButton(rollbackAction, "rollback"), //$NON-NLS-1$
            UiKeys.format(EditorMessages.editor_sql_rollback_tip, rollbackAction)));
        commitModeButton = createCommitModeButton(commitModeAction);
        toolBar.add(commitModeButton);
        toolBar.addSeparator();
        // The editor's two-key chords (SqlEditorPanel.SqlTextArea)
        toolBar.add(createCommandButton(ToolbarMessages.toolbar_comment, NLS.bind(ToolbarMessages.toolbar_comment_tip, SqlEditorPanel.SqlTextArea.getChordText(KeyEvent.VK_C)), "comment", //$NON-NLS-1$
            SqlEditorPanel.SqlTextArea.Command.COMMENT));
        toolBar.add(createCommandButton(ToolbarMessages.toolbar_uncomment, NLS.bind(ToolbarMessages.toolbar_uncomment_tip, SqlEditorPanel.SqlTextArea.getChordText(KeyEvent.VK_U)), "uncomment", //$NON-NLS-1$
            SqlEditorPanel.SqlTextArea.Command.UNCOMMENT));
        toolBar.addSeparator();
        toolBar.add(createCommandButton(ToolbarMessages.toolbar_outdent, ToolbarMessages.toolbar_outdent_tip, "outdent", SqlEditorPanel.SqlTextArea.Command.OUTDENT)); //$NON-NLS-1$
        toolBar.add(createCommandButton(ToolbarMessages.toolbar_indent, ToolbarMessages.toolbar_indent_tip, "indent", SqlEditorPanel.SqlTextArea.Command.INDENT)); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createCommandButton(ToolbarMessages.toolbar_format, NLS.bind(ToolbarMessages.toolbar_format_tip, SqlEditorPanel.SqlTextArea.getChordText(KeyEvent.VK_D)), "format", //$NON-NLS-1$
            SqlEditorPanel.SqlTextArea.Command.FORMAT));
        toolBar.addSeparator();
        Action historyAction = createEditorAction(ToolbarMessages.toolbar_query_history, ToolbarMessages.toolbar_query_history_tip, openQueryHistory);
        toolBar.add(UiIcons.createActionButton(historyAction, "history")); //$NON-NLS-1$
        setActiveEditor(null);
    }

    @NotNull
    JComponent getComponent() {
        return toolBar;
    }

    /**
     * The active document changed: puts that query window's server indicator and picker into the tool bar, or the empty ones when the active
     * document is not a query window. Execute, Stop and the plan buttons follow the Query menu actions.
     */
    /**
     * The Actual Plan button's tool tip: its shortcut, or why it is off on the active query window's connection. Called after
     * the action's description changed, which the button also takes.
     */
    void setActualPlanUnsupported(boolean unsupported) {
        actualPlanButton.setToolTipText(unsupported ? EditorMessages.editor_sql_actual_plan_unsupported
            : UiKeys.format(EditorMessages.editor_sql_actual_plan_tip, actualPlanButton.getAction()));
    }

    void setActiveEditor(@Nullable SqlEditorPanel editor) {
        this.editor = editor;
        QueryWindowServerIndicator indicator = editor != null ? editor.getServerIndicator() : emptyServerIndicator;
        if (indicator != serverIndicator) {
            // One button: an inactive window's indicator would take it back when it shows its server again
            serverIndicator.setChangeButton(null);
            if (editor != null) {
                MainFrame.updateLookAndFeel(changeConnectionButton);
                indicator.setChangeButton(changeConnectionButton);
            }
        }
        serverIndicator = replace(serverIndicator, indicator);
        schemaCombo = replace(schemaCombo, editor != null ? editor.getSchemaCombo() : emptySchemaCombo);
        for (Action action : editorActions) {
            action.setEnabled(editor != null);
        }
        toolBar.revalidate();
        toolBar.repaint();
    }

    /**
     * Puts a picker or server indicator into the place of another one. The ones out of the tool bar (inactive query
     * windows, the empty ones) missed theme switches, so their look and feel is brought up to date first.
     */
    @NotNull
    private <C extends JComponent> C replace(@NotNull JComponent current, @NotNull C replacement) {
        if (current != replacement) {
            MainFrame.updateLookAndFeel(replacement);
            int index = toolBar.getComponentIndex(current);
            toolBar.remove(current);
            toolBar.add(replacement, index);
        }
        return replacement;
    }

    /**
     * Tool tip with the shortcut: the menu action's own description is written for the menu, which shows the shortcut.
     */
    @NotNull
    private static JButton createButton(@NotNull JButton button, @NotNull String toolTip) {
        button.setToolTipText(toolTip);
        return button;
    }

    /**
     * Icon and short text ("Actual Plan") next to the estimated plan button, pressed while the active query window
     * includes the actual plan.
     */
    @NotNull
    private static JToggleButton createActualPlanButton(@NotNull Action actualPlanAction) {
        JToggleButton button = new JToggleButton(actualPlanAction);
        button.setHideActionText(true);
        button.setText(EditorMessages.editor_sql_actual_plan);
        button.setIcon(UiIcons.get("plan")); //$NON-NLS-1$
        button.setToolTipText(UiKeys.format(EditorMessages.editor_sql_actual_plan_tip, actualPlanAction));
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        // As high as the icon buttons beside it: the text line is higher than the icon
        UiIcons.useToolButtonMargin(button);
        return button;
    }

    /**
     * The commit mode of the active query window's session as icon and text, the status line's dot and words; a click
     * switches to the other mode. Its tool tip is the action's (what the click does).
     */
    @NotNull
    private static JButton createCommitModeButton(@NotNull Action commitModeAction) {
        JButton button = new JButton(commitModeAction);
        button.setHideActionText(true);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        // As high as the icon buttons beside it: the text line is higher than the icon
        UiIcons.useToolButtonMargin(button);
        return button;
    }

    /**
     * Shows the active query window's commit mode on the commit mode button (auto-commit = true); null shows the
     * connection's mode as unknown (no window, not connected).
     */
    void setCommitMode(@Nullable Boolean autoCommit) {
        commitModeButton.setText(autoCommit == null ? LifecycleMessages.commit_mode_unknown
            : autoCommit ? LifecycleMessages.commit_mode_auto : LifecycleMessages.commit_mode_manual);
        commitModeButton.setIcon(autoCommit == null ? null : UiIcons.statusDot(autoCommit ? UiIcons.Status.CONNECTED : UiIcons.Status.ERROR));
    }

    /**
     * Icon button of an editing command on the active query window's text (the text area gets the focus back).
     */
    @NotNull
    private JButton createCommandButton(@NotNull String name, @NotNull String toolTip, @NotNull String iconId,
                                        @NotNull SqlEditorPanel.SqlTextArea.Command command) {
        return UiIcons.createActionButton(createEditorAction(name, toolTip, () -> {
            if (editor != null) {
                editor.getTextArea().requestFocusInWindow();
                editor.getTextArea().runCommand(command);
            }
        }), iconId);
    }

    /**
     * An action of this tool bar that needs an active query window.
     */
    @NotNull
    private Action createEditorAction(@NotNull String name, @NotNull String toolTip, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, toolTip);
        editorActions.add(action);
        return action;
    }

    @NotNull
    private static JComboBox<Object> createEmptyCombo(int width) {
        JComboBox<Object> combo = new JComboBox<>();
        combo.setEnabled(false);
        SqlEditorPanel.sizePicker(combo, width);
        return combo;
    }
}
