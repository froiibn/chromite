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

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * 테이블 디자이너의 변경 스크립트 미리 보기(SSMS "Generate Change Script"에 해당).
 *
 * 설계: SWT 객체 편집기의 저장 전 미리 보기({@code EntityEditor.showChanges}의 {@code ChangesPreviewer})와 같은 역할이다.
 * 스크립트는 디자이너가 명령 묶음에서 만들어 넘기고, 이 대화상자는 SQL 편집기와 같은 읽기 전용 글 상자로 보여 준 뒤
 * 사용자의 선택(실행, 쿼리 창으로 열기, 닫기)만 돌려준다. 저장 흐름과 Object Explorer의 개체 변경({@link #confirmChange})에서만 Execute 단추를 보여 준다.
 * 실행 단추가 있는 미리 보기는 모두 DDL·DML·작업 변경을 실행하므로, 어떤 호출이든 Cancel(실행 단추가 없으면 Close)을 기본 단추로 하고
 * 처음 포커스도 그 단추에 둔다. 습관처럼 누른 Enter로 스크립트가 실행되지 않게 하려는 것이다.
 * 결과 표 저장 확인({@link #confirmSave})은 SWT {@code SavePreviewDialog}에 해당한다. 바뀐 내용은 표에 남아 있으므로
 * 쿼리 창으로 열기는 두지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 결과 표 저장 확인({@link #confirmSave}): 머리글과 실행 단추 이름을 호출자가 정함 (docs/chromite/swing-frontend.md "저장 전 스크립트 미리 보기")
 * - 2026-11-07 Object Explorer 개체 변경 확인({@link #confirmChange}): 삭제·이름 바꾸기 스크립트, 쿼리 창으로 열기는 두고 Cancel이 기본 단추.
 *   "저장 확인" 한 플래그를 쿼리 창 단추와 기본 단추 두 플래그로 나눔, 줄바꿈이 있는 머리글은 여러 줄로 보임
 *   (docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-08 {@link #confirmChange}를 Object Explorer 유지 관리 명령과 보안 편집기도 씀(Cancel이 기본 단추) (docs/chromite/swing-security.md)
 * - 2026-11-08 {@link #show}의 실행 미리 보기(테이블 디자이너 저장, SQL Server 에이전트, 작업 스케줄러)도 Cancel이 기본 단추.
 *   기본 단추 플래그는 늘 참이 되어 지우고, Cancel에 처음 포커스를 둠 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class ScriptPreviewDialog extends JDialog {

    enum Result {
        EXECUTE,
        OPEN_IN_EDITOR,
        CLOSE
    }

    private Result result = Result.CLOSE;

    private ScriptPreviewDialog(@NotNull Component owner, @NotNull String title, @NotNull String script, boolean allowExecute) {
        this(owner, title, script, allowExecute
            ? ToolsMessages.script_preview_execute_question
            : ToolsMessages.script_preview_header, allowExecute ? ToolsMessages.script_preview_execute : null, true);
    }

    /**
     * Cancel (Close without an execute button) is always the default button and has the initial focus, so that Enter
     * pressed by habit does not run the script.
     *
     * @param header       text above the script; a header with line breaks keeps them
     * @param executeLabel label of the button that runs the script, or null to only show the script
     * @param allowOpen    show "Open in Query Window" (not for a save confirmation: the changes stay in the grid)
     */
    private ScriptPreviewDialog(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull String script,
        @NotNull String header,
        @Nullable String executeLabel,
        boolean allowOpen
    ) {
        super(SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());

        SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();
        textArea.setText(script);
        textArea.setCaretPosition(0);
        textArea.setEditable(false);
        textArea.discardAllEdits();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        textArea.applyTheme();

        // Headers of several lines: the save confirmation (connection, row counts), Object Explorer changes (DDL commit note)
        JLabel headerLabel = new JLabel(header.indexOf('\n') >= 0
            ? "<html>" + CommonUtils.escapeHtml(header) + "</html>" //$NON-NLS-1$ //$NON-NLS-2$
            : header);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JButton executeButton = new JButton(CommonUtils.notEmpty(executeLabel));
        executeButton.addActionListener(e -> close(Result.EXECUTE));
        JButton openButton = new JButton(ToolsMessages.script_preview_open);
        openButton.setToolTipText(ToolsMessages.script_preview_open_tip);
        openButton.addActionListener(e -> close(Result.OPEN_IN_EDITOR));
        JButton copyButton = new JButton(ToolsMessages.script_preview_copy);
        copyButton.addActionListener(e -> DBWorkbench.getPlatformUI().copyTextToClipboard(script, false));
        JButton cancelButton = new JButton(executeLabel != null ? ToolsMessages.button_cancel : ToolsMessages.button_close);
        cancelButton.addActionListener(e -> close(Result.CLOSE));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        buttons.add(copyButton);
        if (allowOpen) {
            buttons.add(openButton);
        }
        if (executeLabel != null) {
            buttons.add(executeButton);
        }
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(headerLabel, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(cancelButton);
        getRootPane().registerKeyboardAction(e -> close(Result.CLOSE),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(760, 480);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    private void close(@NotNull Result result) {
        this.result = result;
        dispose();
    }

    /**
     * Shows the script and waits for the user's choice. Cancel (or Close) is the default button.
     *
     * @param allowExecute show the Execute button (Save), or only show the script (Generate Script)
     */
    @NotNull
    static Result show(@NotNull Component owner, @NotNull String title, @NotNull String script, boolean allowExecute) {
        ScriptPreviewDialog dialog = new ScriptPreviewDialog(owner, title, script, allowExecute);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * Shows the script of a save that runs right after it and asks whether to save.
     *
     * @param header    what is saved where, above the script
     * @param saveLabel label of the button that saves
     * @return true if the user chose to save
     */
    static boolean confirmSave(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull String header,
        @NotNull String script,
        @NotNull String saveLabel
    ) {
        ScriptPreviewDialog dialog = new ScriptPreviewDialog(owner, title, script, header, saveLabel, false);
        dialog.setVisible(true);
        return dialog.result == Result.EXECUTE;
    }

    /**
     * Shows the script of a change to database objects (Object Explorer Delete, Rename and maintenance commands, the
     * security editors) and waits for the user's choice: execute it, open it in a query window, or close. Cancel is the
     * default button.
     *
     * @param header what runs where, above the script (may have several lines)
     */
    @NotNull
    static Result confirmChange(@NotNull Component owner, @NotNull String title, @NotNull String header, @NotNull String script) {
        ScriptPreviewDialog dialog = new ScriptPreviewDialog(owner, title, script, header, ToolsMessages.script_preview_execute, true);
        dialog.setVisible(true);
        return dialog.result;
    }
}
