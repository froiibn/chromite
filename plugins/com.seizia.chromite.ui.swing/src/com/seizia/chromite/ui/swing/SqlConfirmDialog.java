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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실행하기 전에 실행할 SQL을 읽기 전용으로 보여 주고 Execute/Cancel을 묻는 대화상자.
 *
 * 설계: 쿼리 창의 위험한 문 확인({@link SqlExecutionSafety})과 {@code @ai} 명령이 만든 SQL의 실행 확인({@link SwingSqlService})이 함께 쓴다.
 * 두 경우 모두 사용자가 무엇을 승인하는지 볼 수 있어야 하므로 문구만 보이는 {@link JOptionPane} 대신 SQL 편집기와 같은 읽기 전용 글 상자를 둔다.
 * {@link ScriptPreviewDialog}는 고정된 머리말과 "쿼리 창으로 열기" 단추가 있는 스크립트 미리 보기라 질문 문구와 경고 아이콘을 넣을 수 없어 따로 만들었다.
 * 데이터를 잃을 수 있는 질문이므로 기본 단추와 Esc는 Cancel이다(SWT {@code ConfirmationDialog}, {@code UIServiceSQLImpl}의 기본 응답 No와 같다).
 * 호출 스레드는 대개 실행 준비 Job이나 실행 엔진 Job이라 {@link #confirm}은 EDT에서 대화상자를 띄우고 기다린다.
 * 기다리다 끊기거나 대화상자가 실패하면 실행하지 않는다(안전 쪽 실패).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-exec-safety.md)
 */
final class SqlConfirmDialog extends JDialog {

    private static final Log log = Log.getLog(SqlConfirmDialog.class);

    private boolean confirmed;

    private SqlConfirmDialog(
        @Nullable Window owner,
        @NotNull String title,
        @NotNull String message,
        @NotNull String sql,
        boolean warning
    ) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();
        textArea.setText(sql);
        textArea.setCaretPosition(0);
        textArea.setEditable(false);
        textArea.discardAllEdits();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        textArea.applyTheme();

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        Icon icon = UIManager.getIcon(warning ? "OptionPane.warningIcon" : "OptionPane.questionIcon"); //$NON-NLS-1$ //$NON-NLS-2$
        if (icon != null) {
            header.add(new JLabel(icon), BorderLayout.WEST);
        }
        header.add(SwingPlatformUI.createMessageComponent(message), BorderLayout.CENTER);

        JButton executeButton = new JButton(EditorMessages.editor_common_execute);
        executeButton.addActionListener(e -> close(true));
        JButton cancelButton = new JButton(EditorMessages.editor_common_cancel);
        cancelButton.addActionListener(e -> close(false));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        buttons.add(executeButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(cancelButton);
        getRootPane().registerKeyboardAction(e -> close(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(760, 480);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    private void close(boolean confirmed) {
        this.confirmed = confirmed;
        dispose();
    }

    /**
     * Shows the SQL and waits for the answer. Can be called from any thread.
     *
     * @param parent  a component of the window to stay on top of, or {@code null} for the focused window
     * @param warning warning icon (the statements may lose data) instead of the question icon
     * @return {@code true} only if the user chose Execute
     */
    static boolean confirm(
        @Nullable Component parent,
        @NotNull String title,
        @NotNull String message,
        @NotNull String sql,
        boolean warning
    ) {
        AtomicBoolean result = new AtomicBoolean();
        Runnable show = () -> {
            SqlConfirmDialog dialog = new SqlConfirmDialog(getOwnerWindow(parent), title, message, sql, warning);
            dialog.setVisible(true);
            result.set(dialog.confirmed);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
            return result.get();
        }
        try {
            SwingUtilities.invokeAndWait(show);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for the execution confirmation");
            return false;
        } catch (InvocationTargetException e) {
            log.error("Execution confirmation failed", e.getTargetException());
            return false;
        }
        return result.get();
    }

    @Nullable
    private static Window getOwnerWindow(@Nullable Component parent) {
        if (parent instanceof Window window) {
            return window;
        }
        if (parent != null) {
            Window window = SwingUtilities.getWindowAncestor(parent);
            if (window != null) {
                return window;
            }
        }
        Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (activeWindow != null) {
            return activeWindow;
        }
        for (Frame frame : Frame.getFrames()) {
            if (frame.isVisible()) {
                return frame;
            }
        }
        return null;
    }
}
