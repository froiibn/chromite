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
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * 테이블 디자이너의 변경 스크립트 미리 보기(SSMS "Generate Change Script"에 해당).
 *
 * 설계: SWT 객체 편집기의 저장 전 미리 보기({@code EntityEditor.showChanges}의 {@code ChangesPreviewer})와 같은 역할이다.
 * 스크립트는 디자이너가 명령 묶음에서 만들어 넘기고, 이 대화상자는 SQL 편집기와 같은 읽기 전용 글 상자로 보여 준 뒤
 * 사용자의 선택(실행, 쿼리 창으로 열기, 닫기)만 돌려준다. 저장 흐름에서만 Execute 단추를 보여 준다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class ScriptPreviewDialog extends JDialog {

    enum Result {
        EXECUTE,
        OPEN_IN_EDITOR,
        CLOSE
    }

    private Result result = Result.CLOSE;

    private ScriptPreviewDialog(@NotNull Component owner, @NotNull String title, @NotNull String script, boolean allowExecute) {
        super(SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());

        SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();
        textArea.setText(script);
        textArea.setCaretPosition(0);
        textArea.setEditable(false);
        textArea.discardAllEdits();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        textArea.applyTheme();

        JLabel header = new JLabel(allowExecute
            ? ToolsMessages.script_preview_execute_question
            : ToolsMessages.script_preview_header);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JButton executeButton = new JButton(ToolsMessages.script_preview_execute);
        executeButton.addActionListener(e -> close(Result.EXECUTE));
        JButton openButton = new JButton(ToolsMessages.script_preview_open);
        openButton.setToolTipText(ToolsMessages.script_preview_open_tip);
        openButton.addActionListener(e -> close(Result.OPEN_IN_EDITOR));
        JButton copyButton = new JButton(ToolsMessages.script_preview_copy);
        copyButton.addActionListener(e -> DBWorkbench.getPlatformUI().copyTextToClipboard(script, false));
        JButton cancelButton = new JButton(allowExecute ? ToolsMessages.button_cancel : ToolsMessages.button_close);
        cancelButton.addActionListener(e -> close(Result.CLOSE));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        buttons.add(copyButton);
        buttons.add(openButton);
        if (allowExecute) {
            buttons.add(executeButton);
        }
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(allowExecute ? executeButton : cancelButton);
        getRootPane().registerKeyboardAction(e -> close(Result.CLOSE),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(760, 480);
        setLocationRelativeTo(owner);
    }

    private void close(@NotNull Result result) {
        this.result = result;
        dispose();
    }

    /**
     * Shows the script and waits for the user's choice.
     *
     * @param allowExecute show the Execute button (Save), or only show the script (Generate Script)
     */
    @NotNull
    static Result show(@NotNull Component owner, @NotNull String title, @NotNull String script, boolean allowExecute) {
        ScriptPreviewDialog dialog = new ScriptPreviewDialog(owner, title, script, allowExecute);
        dialog.setVisible(true);
        return dialog.result;
    }
}
