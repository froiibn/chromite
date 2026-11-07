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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks before a connection runs code that comes from its saved settings: the H2 initialization SQL
 * ({@link SwingConnectionInitService}) and the SQLite native extensions ({@link SwingSqliteExtensionConfirmation}).
 *
 * 설계: SWT 제품은 두 경우 모두 {@code ExecutionConfirmationDialog}(연결, 실행할 내용, 추가 정보, 허용 단추)를 썼다. Swing은
 * 셸 명령 승인({@link SwingShellCommandsService}, #99)과 같은 모양과 규칙을 따른다.
 * <ul>
 *     <li>보이는 것: 무엇을 실행하는지 고칠 수 없는 고정폭 칸으로 그대로(줄을 바꾸지 않음), 연결 이름과 URL 같은 문맥, 허용하면 무엇을
 *     기억하는지와 취소하면 무엇이 멈추는지.</li>
 *     <li>단추: 허용 / 취소. 기본 단추와 처음 초점은 취소다(Enter나 창 닫기로 실행되지 않게, 사용자 규칙 "실행 확인의 기본 단추").</li>
 *     <li>부르는 쪽은 대개 연결 Job이다. 답할 때까지 기다리고, 이벤트 스레드에서 부르면 바로 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "초기화 SQL과 SQLite 확장 승인")
 */
final class ExecutionApprovalDialog {

    private static final Log log = Log.getLog(ExecutionApprovalDialog.class);

    private static final int MAX_COLUMNS = 80;
    private static final int MAX_ROWS = 8;

    private ExecutionApprovalDialog() {
    }

    /**
     * Asks Allow / Cancel; waits for the answer when called outside the event dispatch thread.
     *
     * @param context   rows shown as "label: value" (connection, URL)
     * @param execution what runs, by label: shown exactly, in a fixed-width read-only box each
     * @param notes     paragraphs below the boxes (what is remembered, what Cancel does)
     * @return true when the user chose Allow
     */
    static boolean ask(
        @NotNull String title,
        @NotNull String message,
        @NotNull String allowLabel,
        @NotNull Map<String, String> context,
        @NotNull Map<String, String> execution,
        @NotNull String... notes
    ) {
        AtomicBoolean allowed = new AtomicBoolean();
        Runnable task = () -> {
            Object[] options = {allowLabel, CoreMessages.common_cancel};
            int answer = JOptionPane.showOptionDialog(
                // The focused window, so that the question appears over the dialog that started the connection
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
                createMessage(message, context, execution, notes),
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]);
            allowed.set(answer == 0);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                log.error("Error asking for approval: " + title, e.getTargetException());
                return false;
            }
        }
        return allowed.get();
    }

    @NotNull
    private static JComponent createMessage(
        @NotNull String message,
        @NotNull Map<String, String> context,
        @NotNull Map<String, String> execution,
        @NotNull String[] notes
    ) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 10, 0);
        panel.add(SwingShellCommandsService.createNote(message), gc);

        gc.gridwidth = 1;
        gc.insets = new Insets(0, 0, 4, 10);
        for (Map.Entry<String, String> row : context.entrySet()) {
            gc.gridy++;
            gc.gridx = 0;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel(row.getKey() + ":"), gc); //$NON-NLS-1$
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(SwingShellCommandsService.createValue(row.getValue()), gc);
        }

        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        for (Map.Entry<String, String> box : execution.entrySet()) {
            gc.gridy++;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weighty = 0;
            gc.insets = new Insets(8, 0, 4, 0);
            panel.add(new JLabel(box.getKey() + ":"), gc); //$NON-NLS-1$
            gc.gridy++;
            gc.fill = GridBagConstraints.BOTH;
            gc.weighty = 1;
            gc.insets = new Insets(0, 0, 4, 0);
            panel.add(createCodeBox(box.getValue()), gc);
        }

        gc.weighty = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(6, 0, 2, 0);
        for (String note : notes) {
            gc.gridy++;
            panel.add(SwingShellCommandsService.createNote(note), gc);
            gc.insets = new Insets(0, 0, 2, 0);
        }
        return panel;
    }

    /**
     * The code exactly as it runs: fixed width, no wrapping, read-only.
     */
    @NotNull
    private static JComponent createCodeBox(@NotNull String text) {
        String[] lines = text.split("\\R", -1); //$NON-NLS-1$
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        int rows = Math.min(lines.length, MAX_ROWS);
        if (width > MAX_COLUMNS) {
            // Room for the horizontal scroll bar, so that it does not cover the last line
            rows++;
        }
        JTextArea area = new JTextArea(text, rows, Math.min(Math.max(width, 20), MAX_COLUMNS));
        area.setEditable(false);
        area.setFont(EditorFont.get());
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        // GridBagLayout falls back to minimum sizes when the panel gets less than its preferred height
        scroll.setMinimumSize(scroll.getPreferredSize());
        return scroll;
    }
}
