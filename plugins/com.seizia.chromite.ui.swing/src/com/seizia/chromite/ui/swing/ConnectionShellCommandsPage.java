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
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionEventType;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Connect 대화상자 Options의 Shell Commands 탭: 연결 이벤트(연결 전·후, 끊기 전·후)마다 실행할 셸 명령.
 *
 * 설계: SWT {@code ConnectionPageShellCommands}와 같은 값({@link DBPConnectionConfiguration#setEvent}, {@link DBRShellCommand})을 고친다.
 * <ul>
 *     <li>왼쪽 목록: 이벤트 네 가지와 켜짐 확인란. 오른쪽: 고른 이벤트의 명령(여러 줄), 작업 폴더, 끝날 때까지 기다리기와 시간 제한(ms, -1은 끝까지),
 *     연결을 끊을 때 프로세스 끝내기, 실행 뒤 멈춤(ms). SWT의 "프로세스 패널 보이기"는 Swing에 프로세스 보기가 없어 칸을 두지 않고 저장된 값을 그대로 둔다.</li>
 *     <li>승인(#99, docs/chromite/shell-command-approval.md): 여기서 입력하거나 고친 명령도 실행할 때 승인 대화상자를 거친다. SWT는 이 화면에서 새로 입력한
 *     명령을 승인 기록에 바로 넣었지만, Chromite는 승인을 실행 시점 한 곳에서만 받는다. 그래서 저장된 명령이든 방금 입력한 명령이든 처음 실행할 때
 *     정확한 명령을 보고 Run Once / Always Allow / Cancel을 고른다(Always Allow를 고르면 다음부터 묻지 않는다).</li>
 *     <li>바뀌지 않은 연결을 저장해도 "바뀜"이 되지 않도록, 값이 같은 이벤트는 연결 설정에 다시 쓰지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-conn-advanced.md "셸 명령")
 */
final class ConnectionShellCommandsPage extends JPanel {

    private static final int NOTE_WIDTH = 300;

    /**
     * The events' commands being edited (a copy of each saved one, or a new one once its check box is turned on).
     */
    private final Map<DBPConnectionEventType, DBRShellCommand> commands = new EnumMap<>(DBPConnectionEventType.class);
    private final DefaultListModel<DBPConnectionEventType> eventModel = new DefaultListModel<>();
    private final JList<DBPConnectionEventType> eventList = new JList<>(eventModel);
    private final JTextArea commandArea = new JTextArea(4, 40);
    private final JTextField workingDirectoryField = new JTextField();
    private final JComponent workingDirectoryRow;
    private final JCheckBox waitCheck = new JCheckBox(ConnectionMessages.shell_wait_finish);
    private final JSpinner waitTimeoutSpinner = createSpinner(DBRShellCommand.WAIT_PROCESS_TIMEOUT_FOREVER,
        DBRShellCommand.WAIT_PROCESS_TIMEOUT_FOREVER, Integer.MAX_VALUE);
    private final JCheckBox terminateCheck = new JCheckBox(ConnectionMessages.shell_terminate_at_disconnect);
    private final JSpinner pauseSpinner = createSpinner(0, 0, Integer.MAX_VALUE);
    private final JButton clearButton = new JButton(ConnectionMessages.shell_clear);
    private final Runnable changeListener;
    private boolean loading;

    ConnectionShellCommandsPage(@NotNull Runnable changeListener) {
        super(new BorderLayout(12, 0));
        this.changeListener = changeListener;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        for (DBPConnectionEventType type : DBPConnectionEventType.values()) {
            eventModel.addElement(type);
        }
        eventList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        eventList.setCellRenderer(new EventRenderer());
        eventList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        // The check box of a row: a click on it (or Space) turns the event's command on or off
        eventList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = eventList.locationToIndex(e.getPoint());
                if (index >= 0 && eventList.getCellBounds(index, index).contains(e.getPoint())
                    && e.getX() - eventList.getCellBounds(index, index).x < new JCheckBox().getPreferredSize().width) {
                    toggle(eventModel.get(index));
                }
            }
        });
        eventList.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "chromite.toggleEvent"); //$NON-NLS-1$ //$NON-NLS-2$
        eventList.getActionMap().put("chromite.toggleEvent", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DBPConnectionEventType type = eventList.getSelectedValue();
                if (type != null) {
                    toggle(type);
                }
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JLabel(ConnectionMessages.shell_event), BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(eventList);
        listScroll.setPreferredSize(new Dimension(190, 120));
        left.add(listScroll, BorderLayout.CENTER);
        add(left, BorderLayout.WEST);

        // The theme's monospaced font, like the bootstrap query editor
        commandArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        commandArea.setLineWrap(true);
        commandArea.setToolTipText(ConnectionMessages.shell_command_tip + " " + getVariableNames()); //$NON-NLS-1$
        workingDirectoryRow = NetworkHandlerPanel.createPathField(workingDirectoryField, ConnectionMessages.shell_working_directory, true);
        waitTimeoutSpinner.setToolTipText(ConnectionMessages.shell_wait_timeout_tip);
        pauseSpinner.setToolTipText(ConnectionMessages.shell_pause_tip);
        terminateCheck.setToolTipText(ConnectionMessages.shell_terminate_at_disconnect_tip);
        DocumentListener documentListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fieldChanged();
            }
        };
        commandArea.getDocument().addDocumentListener(documentListener);
        workingDirectoryField.getDocument().addDocumentListener(documentListener);
        waitCheck.addActionListener(e -> fieldChanged());
        terminateCheck.addActionListener(e -> fieldChanged());
        waitTimeoutSpinner.addChangeListener(e -> fieldChanged());
        pauseSpinner.addChangeListener(e -> fieldChanged());
        clearButton.addActionListener(e -> clearSelected());

        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        JScrollPane commandScroll = new JScrollPane(commandArea);
        commandScroll.setPreferredSize(new Dimension(320, 90));
        // The form gets less than its preferred height in the Options tabs: the command box keeps a few lines
        commandScroll.setMinimumSize(new Dimension(200, 80));
        form.addRow(ConnectionMessages.shell_command, commandScroll);
        form.addRow(ConnectionMessages.shell_working_directory, workingDirectoryRow);
        form.addRow((JLabel) null, waitCheck);
        form.addRow(ConnectionMessages.shell_wait_timeout, ConnectionSettingsPanel.leftAligned(waitTimeoutSpinner));
        form.addRow((JLabel) null, terminateCheck);
        form.addRow(ConnectionMessages.shell_pause, ConnectionSettingsPanel.leftAligned(pauseSpinner));
        form.addRow((JLabel) null, ConnectionSettingsPanel.leftAligned(clearButton));
        // Wrapped: an HTML label reports its wrapped height
        JLabel note = new JLabel("<html><body style='width: " + NOTE_WIDTH + "px'>" + ConnectionMessages.shell_approval_note + "</body></html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        note.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        note.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
        form.addGroupGap();
        form.addRow((JLabel) null, note);
        form.addFiller();
        // Scrolled like the Connection Properties page: the Options tabs can be lower than the form
        JScrollPane formScroll = new JScrollPane(form.getPanel(),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        add(formScroll, BorderLayout.CENTER);
    }

    void loadSettings(@NotNull DataSourceDescriptor dataSource) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        for (DBPConnectionEventType type : DBPConnectionEventType.values()) {
            DBRShellCommand command = configuration.getEvent(type);
            if (command != null) {
                commands.put(type, new DBRShellCommand(command));
            }
        }
        eventList.setSelectedIndex(0);
        showSelected();
    }

    /**
     * Writes the events whose command differs from the target's (ConnectionPageShellCommands.saveSettings).
     */
    void saveSettings(@NotNull DataSourceDescriptor target) {
        DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
        for (DBPConnectionEventType type : DBPConnectionEventType.values()) {
            DBRShellCommand command = commands.get(type);
            if (!Objects.equals(configuration.getEvent(type), command)) {
                configuration.setEvent(type, command == null ? null : new DBRShellCommand(command));
            }
        }
    }

    /**
     * Whether any event runs a command (a check mark on the tab).
     */
    boolean isUsed() {
        return commands.values().stream().anyMatch(command -> command.isEnabled() && !command.isBlank());
    }

    private void toggle(@NotNull DBPConnectionEventType type) {
        DBRShellCommand command = commands.get(type);
        if (command == null) {
            command = new DBRShellCommand(""); //$NON-NLS-1$
            command.setEnabled(true);
            commands.put(type, command);
        } else {
            command.setEnabled(!command.isEnabled());
        }
        eventList.setSelectedValue(type, true);
        eventList.repaint();
        showSelected();
        changeListener.run();
    }

    private void clearSelected() {
        DBPConnectionEventType type = eventList.getSelectedValue();
        if (type != null) {
            commands.remove(type);
            eventList.repaint();
            showSelected();
            changeListener.run();
        }
    }

    /**
     * Shows the selected event's command; the fields are editable only while its check box is on (SWT selectEventType).
     */
    private void showSelected() {
        DBPConnectionEventType type = eventList.getSelectedValue();
        DBRShellCommand command = type == null ? null : commands.get(type);
        boolean editable = command != null && command.isEnabled();
        loading = true;
        try {
            commandArea.setText(command == null ? "" : CommonUtils.notEmpty(command.getCommand())); //$NON-NLS-1$
            workingDirectoryField.setText(command == null ? "" : CommonUtils.notEmpty(command.getWorkingDirectory())); //$NON-NLS-1$
            waitCheck.setSelected(command != null && command.isWaitProcessFinish());
            waitTimeoutSpinner.setValue(command == null ? DBRShellCommand.WAIT_PROCESS_TIMEOUT_FOREVER
                : Math.max(DBRShellCommand.WAIT_PROCESS_TIMEOUT_FOREVER, command.getWaitProcessTimeoutMs()));
            terminateCheck.setSelected(command != null && command.isTerminateAtDisconnect());
            pauseSpinner.setValue(command == null ? 0 : Math.max(0, command.getPauseAfterExecute()));
        } finally {
            loading = false;
        }
        commandArea.setEnabled(editable);
        commandArea.setEditable(editable);
        setEnabledDeep(workingDirectoryRow, editable);
        waitCheck.setEnabled(editable);
        waitTimeoutSpinner.setEnabled(editable && waitCheck.isSelected());
        terminateCheck.setEnabled(editable);
        pauseSpinner.setEnabled(editable);
        clearButton.setEnabled(command != null);
    }

    private void fieldChanged() {
        if (loading) {
            return;
        }
        DBPConnectionEventType type = eventList.getSelectedValue();
        DBRShellCommand command = type == null ? null : commands.get(type);
        if (command == null) {
            return;
        }
        command.setCommand(commandArea.getText());
        command.setWorkingDirectory(CommonUtils.nullIfEmpty(workingDirectoryField.getText().trim()));
        command.setWaitProcessFinish(waitCheck.isSelected());
        command.setWaitProcessTimeoutMs((Integer) waitTimeoutSpinner.getValue());
        command.setTerminateAtDisconnect(terminateCheck.isSelected());
        command.setPauseAfterExecute((Integer) pauseSpinner.getValue());
        waitTimeoutSpinner.setEnabled(waitCheck.isSelected());
        eventList.repaint();
        changeListener.run();
    }

    @NotNull
    private static String getVariableNames() {
        StringBuilder names = new StringBuilder();
        for (String[] variable : DBPConnectionConfiguration.INTERNAL_CONNECT_VARIABLES) {
            names.append(names.isEmpty() ? "" : ", ").append("${").append(variable[0]).append('}'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return names.toString();
    }

    private static void setEnabledDeep(@NotNull Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledDeep(child, enabled);
            }
        }
    }

    @NotNull
    private static JSpinner createSpinner(int value, int minimum, int maximum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, 1));
        // Plain numbers without digit grouping
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#"); //$NON-NLS-1$
        editor.getTextField().setColumns(7);
        spinner.setEditor(editor);
        return spinner;
    }

    /**
     * An event row: its check box (on when the command is on) and the event's name.
     */
    private final class EventRenderer extends JCheckBox implements ListCellRenderer<DBPConnectionEventType> {
        @Override
        public Component getListCellRendererComponent(
            JList<? extends DBPConnectionEventType> list,
            @Nullable DBPConnectionEventType value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            DBRShellCommand command = value == null ? null : commands.get(value);
            setText(value == null ? "" : value.getTitle()); //$NON-NLS-1$
            setSelected(command != null && command.isEnabled());
            setOpaque(true);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
