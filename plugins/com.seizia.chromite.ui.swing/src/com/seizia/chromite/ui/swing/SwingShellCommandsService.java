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
import com.seizia.chromite.model.preferences.ConfirmedShellCommandsStore;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.runtime.ui.UIServiceShellCommands;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ShellCommandMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks the user before a shell command from saved settings runs ({@link UIServiceShellCommands}): connection events,
 * the password command auth model and the data transfer "execute process" step.
 *
 * 설계: SWT 제품은 {@code com.seizia.chromite.core}의 {@code UIServiceShellCommandsImpl}({@code ConfirmShellCommandDialog})이
 * 이 일을 했는데 Swing 제품에는 그 번들이 없어서 명령이 승인 없이 실행됐다(docs/chromite/swt-parity/A-platform.md S4).
 * 그래서 같은 서비스를 {@code plugin.xml}의 {@code com.seizia.chromite.service} 확장으로 등록한다.
 * <ul>
 *     <li>대화상자: 정확한 명령(고정폭, 읽기 전용, 고칠 수 없음), 작업 폴더, 호출부가 준 문맥(프로젝트, 연결, 이벤트)을 보인다.
 *     Run Once / Always Allow / Cancel. 기본 단추는 Cancel이다(Enter나 창 닫기로 실행되지 않게).</li>
 *     <li>Always Allow: {@link ConfirmedShellCommandsStore}에 승인 범위(연결)·작업 폴더·명령으로 기억한다. 범위가 없으면
 *     (데이터 전송 단계) 이 단추를 보이지 않는다. SWT는 승인하면 항상 기억했고 연결을 가리지 않았다.</li>
 *     <li>Cancel: 예외를 던진다. 호출부는 명령을 실행하지 않고 그 작업(연결, 연결 끊기, 데이터 전송 단계)을 멈춘다.</li>
 *     <li>호출 스레드(대개 연결 Job)는 답할 때까지 기다린다. 이벤트 스레드에서 부르면 바로 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/shell-command-approval.md)
 * - 2026-11-08 문단·값 칸 만들기를 패키지 안에 공개: 초기화 SQL·SQLite 확장 승인({@link ExecutionApprovalDialog})이 같은 모양을 씀
 *   (docs/chromite/connection-lifecycle.md)
 */
public class SwingShellCommandsService implements UIServiceShellCommands {

    private static final Log log = Log.getLog(SwingShellCommandsService.class);

    private static final int MAX_COMMAND_COLUMNS = 80;
    private static final int MAX_COMMAND_ROWS = 8;
    private static final int NOTE_WIDTH = 520;

    private enum Answer {
        RUN_ONCE,
        ALWAYS,
        CANCEL
    }

    @Override
    public void validateByUser(
        @NotNull DBRShellCommand command,
        @Nullable String approvalScope,
        @NotNull Map<String, String> approvalContext
    ) throws DBException {
        String commandText = UIServiceShellCommands.getCommandText(command);
        if (commandText.isEmpty()) {
            // Nothing that could run
            return;
        }
        ConfirmedShellCommandsStore store = ConfirmedShellCommandsStore.getInstance();
        if (approvalScope != null && store.isApproved(approvalScope, command)) {
            return;
        }
        switch (ask(commandText, command.getWorkingDirectory(), approvalScope != null, approvalContext)) {
            case RUN_ONCE -> {
            }
            case ALWAYS -> store.approve(approvalScope, command);
            case CANCEL -> throw new DBException("Shell command was not approved (" + String.join(", ", approvalContext.values())
                + "): " + commandText);
        }
    }

    @NotNull
    private static Answer ask(
        @NotNull String commandText,
        @Nullable String workingDirectory,
        boolean canRemember,
        @NotNull Map<String, String> approvalContext
    ) {
        AtomicInteger choice = new AtomicInteger(-1);
        Object[] options = canRemember
            ? new Object[]{ShellCommandMessages.shell_cmd_run_once, ShellCommandMessages.shell_cmd_always, CoreMessages.common_cancel}
            : new Object[]{ShellCommandMessages.shell_cmd_run_once, CoreMessages.common_cancel};
        Runnable task = () -> choice.set(JOptionPane.showOptionDialog(
            // Parent: the focused window, so that the question appears over the dialog that started the connection
            KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
            createMessage(commandText, workingDirectory, canRemember, approvalContext),
            ShellCommandMessages.shell_cmd_title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[options.length - 1]));
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Answer.CANCEL;
            } catch (InvocationTargetException e) {
                log.error("Error asking for shell command approval", e.getTargetException());
                return Answer.CANCEL;
            }
        }
        return switch (choice.get()) {
            case 0 -> Answer.RUN_ONCE;
            case 1 -> canRemember ? Answer.ALWAYS : Answer.CANCEL;
            default -> Answer.CANCEL;
        };
    }

    @NotNull
    private static JComponent createMessage(
        @NotNull String commandText,
        @Nullable String workingDirectory,
        boolean canRemember,
        @NotNull Map<String, String> approvalContext
    ) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 10, 0);
        panel.add(createNote(ShellCommandMessages.shell_cmd_message), gc);

        Map<String, String> rows = new LinkedHashMap<>(approvalContext);
        if (!CommonUtils.isEmpty(workingDirectory)) {
            rows.put(ShellCommandMessages.shell_cmd_working_directory, workingDirectory);
        }
        gc.gridwidth = 1;
        gc.insets = new Insets(0, 0, 4, 10);
        for (Map.Entry<String, String> row : rows.entrySet()) {
            gc.gridy++;
            gc.gridx = 0;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel(row.getKey() + ":"), gc); //$NON-NLS-1$
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(createValue(row.getValue()), gc);
        }

        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.insets = new Insets(8, 0, 4, 0);
        panel.add(new JLabel(ShellCommandMessages.shell_cmd_command + ":"), gc); //$NON-NLS-1$

        String[] lines = commandText.split("\\R", -1); //$NON-NLS-1$
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        int commandRows = Math.min(lines.length, MAX_COMMAND_ROWS);
        if (width > MAX_COMMAND_COLUMNS) {
            // Room for the horizontal scroll bar, so that it does not cover the last line (no wrapping: lines stay exact)
            commandRows++;
        }
        JTextArea commandArea = new JTextArea(commandText, commandRows, Math.min(Math.max(width, 20), MAX_COMMAND_COLUMNS));
        commandArea.setEditable(false);
        commandArea.setFont(EditorFont.get());
        commandArea.setCaretPosition(0);
        gc.gridy++;
        gc.fill = GridBagConstraints.BOTH;
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 10, 0);
        JScrollPane commandScroll = new JScrollPane(commandArea);
        // GridBagLayout falls back to minimum sizes when the panel gets less than its preferred height; the command must
        // stay fully visible
        commandScroll.setMinimumSize(commandScroll.getPreferredSize());
        panel.add(commandScroll, gc);

        gc.weighty = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 2, 0);
        if (canRemember) {
            gc.gridy++;
            panel.add(createNote(ShellCommandMessages.shell_cmd_always_note), gc);
        }
        gc.gridy++;
        panel.add(createNote(ShellCommandMessages.shell_cmd_cancel_note), gc);
        return panel;
    }

    /**
     * A paragraph wrapped at a fixed width. An HTML label reports its wrapped height before layout; a wrapping JTextArea
     * does not, which made the dialog too short and squeezed the command box.
     */
    @NotNull
    static JLabel createNote(@NotNull String text) {
        // The NLS texts contain no markup; escape anyway so that they are shown as written
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        return new JLabel("<html><body style='width: " + NOTE_WIDTH + "px'>" + escaped + "</body></html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Context values (connection name, path) can be long: a borderless read-only field the user can select and copy.
     */
    @NotNull
    static JTextField createValue(@NotNull String value) {
        JTextField field = new JTextField(value);
        field.setEditable(false);
        field.setBorder(null);
        field.setOpaque(false);
        field.setColumns(Math.min(Math.max(value.length(), 20), MAX_COMMAND_COLUMNS));
        field.setCaretPosition(0);
        return field;
    }
}
