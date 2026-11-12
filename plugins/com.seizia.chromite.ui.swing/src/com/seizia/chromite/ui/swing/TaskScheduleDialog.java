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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolSettings;
import com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Schedule… of a saved task (Database Tasks): when it runs, on or off, whether it runs on this computer, and the answers to
 * what a hand run of the task asks every time.
 *
 * 설계:
 * <ul>
 *     <li>When 칸은 쿼리의 Schedule…과 같은 부품({@link ScheduleWhenPanel})이다.</li>
 *     <li>네이티브 도구 작업은 실행할 명령줄을 보이고 "예약 실행은 이 명령을 묻지 않고 실행한다"고 한 줄로 적는다. 일정을 켜는 것이
 *     그 명령을 무인으로 실행하는 데 대한 동의이므로(사용자 결정 2026-11-12) 무엇에 동의하는지 보여야 한다. 명령줄을 읽으려면 작업 설정을
 *     처리기처럼 읽어야 해서(연결함) 열 때 진행 대화상자에서 읽는다.</li>
 *     <li>예약 실행이 실패할 수 있는 이유(연결 없음, 저장되지 않은 암호, 로컬 클라이언트 없음, 수동 커밋 세션의 SQL 스크립트, 열린 트랜잭션,
 *     "파일이 있으면 묻기")를 연결하지 않고 알 수 있는 만큼 미리 보인다({@link TaskScheduler#getWarnings}).</li>
 *     <li>확인: 손으로 실행할 때마다 묻는 것(복원 확인, 대상 비우기·다시 만들기, 스크립트 목록, 끝나고 실행할 명령)을 OK에서 같은 문구로 한 번
 *     묻는다(취소가 기본). 답은 동의 지문으로 예약과 함께 저장된다. 이미 켜져 있고 작업이 바뀌지 않았으면 다시 묻지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #104, docs/chromite/task-scheduling.md "화면")
 */
final class TaskScheduleDialog extends JDialog {

    private static final Log log = Log.getLog(TaskScheduleDialog.class);
    // Width of the notes and the command line (unscaled pixels)
    private static final int NOTE_WIDTH = 440;

    private final DBTTask task;
    private final JCheckBox enabledBox = new JCheckBox(DatabaseTasksMessages.tasks_schedule_enabled);
    private final JCheckBox activeBox = new JCheckBox(ToolsMessages.schedule_active);
    private final ScheduleWhenPanel when;
    private final JLabel errorLabel = new JLabel(" "); //$NON-NLS-1$
    private final boolean wasOn;

    private TaskScheduleDialog(@NotNull Window owner, @NotNull DBTTask task, @Nullable String command) {
        super(owner, NLS.bind(ToolsMessages.schedule_title, task.getName()), ModalityType.APPLICATION_MODAL);
        this.task = task;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        ScheduleTiming timing = TaskScheduler.readTiming(task);
        wasOn = timing != null && timing.isEnabled() && TaskScheduler.isConsentValid(task);
        when = new ScheduleWhenPanel(timing != null ? timing : new ScheduleTiming());
        enabledBox.setSelected(timing == null || timing.isEnabled());
        activeBox.setSelected(timing == null || TaskScheduler.isActiveHere(task));
        activeBox.setToolTipText(DatabaseTasksMessages.tasks_schedule_active_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(form, row++, null, enabledBox);
        addRow(form, row++, ToolsMessages.schedule_when, when);
        addRow(form, row++, null, activeBox);
        if (command != null) {
            JTextArea commandArea = new JTextArea(command);
            commandArea.setEditable(false);
            commandArea.setLineWrap(true);
            commandArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            commandArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            fitWidth(commandArea);
            JPanel commandPanel = new JPanel(new BorderLayout(0, 4));
            commandPanel.add(note(DatabaseTasksMessages.tasks_schedule_command_note), BorderLayout.NORTH);
            commandPanel.add(commandArea, BorderLayout.CENTER);
            addRow(form, row++, null, commandPanel);
        }
        List<String> warnings = TaskScheduler.getWarnings(task);
        if (!warnings.isEmpty()) {
            StringBuilder text = new StringBuilder(DatabaseTasksMessages.tasks_schedule_warnings);
            for (String warning : warnings) {
                text.append("\n- ").append(warning); //$NON-NLS-1$
            }
            JTextArea warningArea = note(text.toString());
            warningArea.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.warningForeground"); //$NON-NLS-1$
            addRow(form, row++, null, warningArea);
        }
        addRow(form, row, null, note(DatabaseTasksMessages.tasks_schedule_note));

        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> accept());
        JButton removeButton = new JButton(ToolsMessages.schedule_remove);
        removeButton.setEnabled(timing != null);
        removeButton.addActionListener(e -> save(null));
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(removeButton, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the dialog of the task; a native tool task's command line is read first (this connects).
     */
    static void open(@NotNull Component parent, @NotNull DBTTask task) {
        String command = null;
        if (NativeBackupRestore.isNativeToolTask(task.getType())) {
            try {
                command = ProgressDialog.runTask(NLS.bind(DatabaseTasksMessages.tasks_schedule_reading, task.getName()), monitor -> {
                    AbstractNativeToolSettings<?> settings = NativeBackupRestore.loadTaskSettings(monitor, task);
                    // The handler takes the connection's local client when it runs; the preview needs it now
                    if (settings.getClientHome() == null) {
                        settings.setClientHome(NativeBackupRestore.findConnectionClient(settings.getDataSourceContainer()));
                    }
                    try {
                        return NativeBackupRestore.getCommandLine(settings);
                    } catch (Exception e) {
                        throw new DBException(e.getMessage(), e);
                    }
                });
                String pattern = describeOutputPattern(task);
                if (pattern != null) {
                    command += "\n" + pattern; //$NON-NLS-1$
                }
            } catch (DBInterruptedException e) {
                return;
            } catch (DBException e) {
                log.debug("Can't read the task's command line", e);
                command = ErrorText.describe(e);
            }
        }
        Window owner = SwingUtilities.getWindowAncestor(parent);
        TaskScheduleDialog dialog = new TaskScheduleDialog(owner != null ? owner : JOptionPane.getRootFrame(), task, command);
        dialog.setVisible(true);
    }

    /**
     * The saved output folder and file name pattern of a backup, with its variables as they are ("${timestamp}"): the command
     * line above shows them filled in for now.
     */
    @Nullable
    private static String describeOutputPattern(@NotNull DBTTask task) {
        // Saved by the tool settings with a prefix ("export.outputFilePattern")
        String pattern = findProperty(task, "outputFilePattern"); //$NON-NLS-1$
        if (CommonUtils.isEmpty(pattern)) {
            return null;
        }
        String folder = findProperty(task, "outputFolder"); //$NON-NLS-1$
        return "# " + (CommonUtils.isEmpty(folder) ? pattern : folder + java.io.File.separator + pattern); //$NON-NLS-1$
    }

    /**
     * A wrapped note of the form's width.
     */
    @NotNull
    private static JTextArea note(@NotNull String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        fitWidth(area);
        return area;
    }

    /**
     * Gives a wrapping text area the form's width and the height of its text wrapped at that width, before the dialog is
     * packed: otherwise its height follows the width the layout gives it later, and the packed dialog cut it.
     */
    private static void fitWidth(@NotNull JTextArea area) {
        int width = UIScale.scale(NOTE_WIDTH);
        area.setSize(width, Short.MAX_VALUE);
        Dimension size = new Dimension(width, area.getPreferredSize().height);
        area.setPreferredSize(size);
        // GridBagLayout lays every cell out at its minimum size when the window is a pixel smaller than preferred (display
        // scale rounding): a smaller text area would keep its wrapping and cut the text
        area.setMinimumSize(size);
    }

    @Nullable
    private static String findProperty(@NotNull DBTTask task, @NotNull String suffix) {
        for (java.util.Map.Entry<String, Object> property : task.getProperties().entrySet()) {
            if (property.getKey().endsWith(suffix)) {
                return CommonUtils.toString(property.getValue());
            }
        }
        return null;
    }

    private void accept() {
        ScheduleTiming timing = new ScheduleTiming();
        timing.setEnabled(enabledBox.isSelected());
        if (!when.apply(timing)) {
            errorLabel.setText(ToolsMessages.schedule_error_time);
            return;
        }
        if (timing.isEnabled() && !wasOn) {
            // What a hand run asks every time is answered once here
            List<String> confirmations = TaskScheduler.getConfirmations(task);
            if (!confirmations.isEmpty() && !SwingPlatformUI.confirmChange(this,
                NLS.bind(DatabaseTasksMessages.tasks_schedule_confirm, task.getName(), String.join("\n\n", confirmations)), //$NON-NLS-1$
                DatabaseTasksMessages.tasks_schedule_confirm_title, DatabaseTasksMessages.tasks_schedule_confirm_button,
                JOptionPane.WARNING_MESSAGE)) {
                return;
            }
        }
        save(timing);
    }

    /**
     * @param timing the schedule, or null to remove it
     */
    private void save(@Nullable ScheduleTiming timing) {
        try {
            TaskScheduler.saveSchedule(task, timing, activeBox.isSelected());
        } catch (DBException e) {
            log.debug("Can't save the schedule", e);
            ErrorDialog.show(this, task.getName(), ErrorText.describe(e), e);
            return;
        }
        dispose();
    }

    private static void addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 8, 10);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(control, c);
    }
}
