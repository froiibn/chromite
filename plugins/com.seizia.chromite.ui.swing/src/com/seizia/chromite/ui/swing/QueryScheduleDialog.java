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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 쿼리의 "Schedule…": 예약 실행 정의(언제, 어느 서버, 매개변수 값, 알림 조건, 사용 여부)와 이 PC에서 돌릴지를 정한다.
 *
 * 설계: cron 문법 없이 세 가지 형태(N분/N시간마다, 매일 HH:mm, 평일 HH:mm)만 고른다. 서버는 여러 개를 고를 수 있다(같은 쿼리를 서버마다 실행).
 * 매개변수 값은 SQL에서 찾은 매개변수마다 하나이고, 비우면 정의의 기본값이다. 정의는 파일에, "Active on this computer"는 이 PC의 설정에 저장한다
 * (docs/chromite/swing-query-library.md "예약 실행").
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 */
final class QueryScheduleDialog extends JDialog {

    /**
     * Condition choices: label and the subject/operator of the file's condition text.
     */
    private record ConditionChoice(@NotNull String label, @Nullable String subject, @Nullable String operator) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final ConditionChoice[] CONDITIONS = {
        new ConditionChoice("Always (no alert)", null, null),
        new ConditionChoice("Row count >", "rows", ">"), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("Row count >=", "rows", ">="), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("Row count =", "rows", "="), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value >", "first", ">"), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value >=", "first", ">="), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value <", "first", "<"), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value <=", "first", "<="), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value =", "first", "="), //$NON-NLS-1$ //$NON-NLS-2$
        new ConditionChoice("First value !=", "first", "!="), //$NON-NLS-1$ //$NON-NLS-2$
    };

    /**
     * A connection that can be checked: one of this computer, or a target of another computer that was not found.
     */
    private record TargetChoice(@NotNull QueryLibrarySchedule.Target target, @Nullable DBPDataSourceContainer container) {
        @Override
        public String toString() {
            return container != null ? container.getName() : target.name() + " (not found)";
        }
    }

    private final JCheckBox enabledBox = new JCheckBox("Run this query on a schedule");
    private final JRadioButton intervalButton = new JRadioButton("Every");
    private final JRadioButton dailyButton = new JRadioButton("Daily at");
    private final JRadioButton weekdaysButton = new JRadioButton("Weekdays at");
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 10000, 1));
    private final JComboBox<String> unitCombo = new JComboBox<>(new String[]{"minutes", "hours"});
    private final JTextField dailyField = new JTextField(5);
    private final JTextField weekdaysField = new JTextField(5);
    private final List<JCheckBox> targetBoxes = new ArrayList<>();
    private final List<TargetChoice> targetChoices = new ArrayList<>();
    private final DefaultTableModel parametersModel = new DefaultTableModel(new Object[]{"Parameter", "Value for scheduled runs"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }
    };
    private final JComboBox<ConditionChoice> conditionCombo = new JComboBox<>(CONDITIONS);
    private final JTextField conditionValueField = new JTextField(8);
    private final JCheckBox activeBox = new JCheckBox("Active on this computer");
    private final JLabel errorLabel = new JLabel(" ");
    private final JTable parametersTable = new JTable(parametersModel);
    @Nullable
    private QueryLibrarySchedule result;
    private boolean removed;

    private QueryScheduleDialog(@NotNull Window owner, @NotNull QueryLibraryItem item, boolean active) {
        super(owner, "Schedule - " + item.getName(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        QueryLibrarySchedule schedule = item.getSchedule() != null ? item.getSchedule() : new QueryLibrarySchedule();

        ButtonGroup group = new ButtonGroup();
        group.add(intervalButton);
        group.add(dailyButton);
        group.add(weekdaysButton);
        enabledBox.setSelected(item.getSchedule() == null || schedule.isEnabled());
        switch (schedule.getKind()) {
            case INTERVAL -> intervalButton.setSelected(true);
            case DAILY -> dailyButton.setSelected(true);
            case WEEKDAYS -> weekdaysButton.setSelected(true);
        }
        boolean hours = schedule.getIntervalMinutes() % 60 == 0;
        intervalSpinner.setValue(hours ? schedule.getIntervalMinutes() / 60 : schedule.getIntervalMinutes());
        unitCombo.setSelectedIndex(hours ? 1 : 0);
        dailyField.setText(schedule.getTime().toString());
        weekdaysField.setText(schedule.getTime().toString());
        dailyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "hh:mm"); //$NON-NLS-1$
        weekdaysField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "hh:mm"); //$NON-NLS-1$
        activeBox.setSelected(active || item.getSchedule() == null);
        activeBox.setToolTipText("The schedule is saved in the query file; whether it runs is set on each computer");

        // Connections: those of this computer, plus targets of another computer that are not found here
        JPanel targetsPanel = new JPanel();
        targetsPanel.setLayout(new BoxLayout(targetsPanel, BoxLayout.Y_AXIS));
        List<QueryLibrarySchedule.Target> targets = new ArrayList<>(schedule.getTargets());
        if (item.getSchedule() == null && item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION && item.getTargetName() != null) {
            targets.add(new QueryLibrarySchedule.Target(item.getTargetId(), item.getTargetName()));
        }
        List<DBPDataSourceContainer> connections = QueryLibraryEditor.getConnections();
        for (DBPDataSourceContainer container : connections) {
            boolean checked = false;
            for (QueryLibrarySchedule.Target target : targets) {
                if (container == QueryLibraryEditor.findConnection(target.id(), target.name())) {
                    checked = true;
                }
            }
            addTarget(targetsPanel, new TargetChoice(new QueryLibrarySchedule.Target(container.getId(), container.getName()), container), checked);
        }
        for (QueryLibrarySchedule.Target target : targets) {
            if (QueryLibraryEditor.findConnection(target.id(), target.name()) == null) {
                addTarget(targetsPanel, new TargetChoice(target, null), true);
            }
        }
        JScrollPane targetsScroll = new JScrollPane(targetsPanel);
        targetsScroll.setPreferredSize(new Dimension(260, 110));

        for (String name : QueryParameterBinder.detect(item.getSql()).keySet()) {
            String value = schedule.getParameterValues().get(name);
            if (value == null) {
                QueryLibraryParameter definition = item.findParameter(name);
                value = definition != null ? CommonUtils.notEmpty(definition.getDefaultValue()) : ""; //$NON-NLS-1$
            }
            parametersModel.addRow(new Object[]{name, value});
        }
        parametersTable.setRowHeight(Math.max(parametersTable.getRowHeight(), 24));
        parametersTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        JScrollPane parametersScroll = new JScrollPane(parametersTable);
        parametersScroll.setPreferredSize(new Dimension(260, 90));

        QueryLibrarySchedule.Condition condition = schedule.getCondition();
        if (condition != null) {
            for (ConditionChoice choice : CONDITIONS) {
                if (condition.subject().equals(choice.subject()) && condition.operator().equals(choice.operator())) {
                    conditionCombo.setSelectedItem(choice);
                }
            }
            conditionValueField.setText(condition.value());
        } else {
            conditionValueField.setText("0"); //$NON-NLS-1$
        }
        conditionCombo.addActionListener(e -> updateEnabled());
        conditionCombo.setToolTipText("Show a notification and mark the query when a run meets the condition");

        JPanel when = new JPanel(new GridBagLayout());
        addCell(when, intervalButton, 0, 0);
        addCell(when, intervalSpinner, 1, 0);
        addCell(when, unitCombo, 2, 0);
        addCell(when, dailyButton, 0, 1);
        addCell(when, dailyField, 1, 1);
        addCell(when, weekdaysButton, 0, 2);
        addCell(when, weekdaysField, 1, 2);
        JPanel conditionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        conditionPanel.add(conditionCombo);
        conditionPanel.add(conditionValueField);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(form, row++, null, enabledBox);
        addRow(form, row++, "When", when);
        addRow(form, row++, "Connections", targetsScroll);
        addRow(form, row++, "Parameters", parametersScroll);
        addRow(form, row++, "Alert when", conditionPanel);
        addRow(form, row, null, activeBox);

        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> accept());
        JButton removeButton = new JButton("Remove Schedule");
        removeButton.setEnabled(item.getSchedule() != null);
        removeButton.addActionListener(e -> {
            result = null;
            removed = true;
            dispose();
        });
        JButton cancelButton = new JButton("Cancel");
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
        updateEnabled();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * The edited schedule.
     *
     * @param schedule the new schedule, or null if it was removed
     * @param active   whether it runs on this computer
     */
    record Result(@Nullable QueryLibrarySchedule schedule, boolean active) {
    }

    /**
     * @param active whether the query's schedule runs on this computer now
     * @return the result, or null if canceled
     */
    @Nullable
    static Result open(@NotNull Component parent, @NotNull QueryLibraryItem item, boolean active) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        QueryScheduleDialog dialog = new QueryScheduleDialog(owner != null ? owner : JOptionPane.getRootFrame(), item, active);
        dialog.setVisible(true);
        if (dialog.removed) {
            return new Result(null, false);
        }
        return dialog.result != null ? new Result(dialog.result, dialog.activeBox.isSelected()) : null;
    }

    private void addTarget(@NotNull JPanel panel, @NotNull TargetChoice choice, boolean checked) {
        JCheckBox box = new JCheckBox(choice.toString(), checked);
        targetBoxes.add(box);
        targetChoices.add(choice);
        panel.add(box);
    }

    private void updateEnabled() {
        ConditionChoice choice = (ConditionChoice) conditionCombo.getSelectedItem();
        conditionValueField.setEnabled(choice != null && choice.subject() != null);
    }

    private static void addCell(@NotNull JPanel panel, @NotNull JComponent component, int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 6);
        panel.add(component, c);
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

    private void accept() {
        if (parametersTable.isEditing()) {
            parametersTable.getCellEditor().stopCellEditing();
        }
        QueryLibrarySchedule schedule = new QueryLibrarySchedule();
        schedule.setEnabled(enabledBox.isSelected());
        try {
            if (intervalButton.isSelected()) {
                schedule.setKind(QueryLibrarySchedule.Kind.INTERVAL);
                int amount = ((Number) intervalSpinner.getValue()).intValue();
                schedule.setIntervalMinutes(unitCombo.getSelectedIndex() == 1 ? amount * 60 : amount);
            } else if (dailyButton.isSelected()) {
                schedule.setKind(QueryLibrarySchedule.Kind.DAILY);
                schedule.setTime(LocalTime.parse(dailyField.getText().trim()));
            } else {
                schedule.setKind(QueryLibrarySchedule.Kind.WEEKDAYS);
                schedule.setTime(LocalTime.parse(weekdaysField.getText().trim()));
            }
        } catch (DateTimeParseException e) {
            errorLabel.setText("Enter the time as hh:mm, for example 08:30.");
            return;
        }
        for (int i = 0; i < targetBoxes.size(); i++) {
            if (targetBoxes.get(i).isSelected()) {
                schedule.getTargets().add(targetChoices.get(i).target());
            }
        }
        if (schedule.getTargets().isEmpty()) {
            errorLabel.setText("Choose at least one connection.");
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < parametersModel.getRowCount(); i++) {
            values.put(String.valueOf(parametersModel.getValueAt(i, 0)), CommonUtils.toString(parametersModel.getValueAt(i, 1)).trim());
        }
        schedule.getParameterValues().putAll(values);
        ConditionChoice choice = (ConditionChoice) conditionCombo.getSelectedItem();
        if (choice != null && choice.subject() != null) {
            String value = conditionValueField.getText().trim();
            if (value.isEmpty()) {
                errorLabel.setText("Enter the value of the condition.");
                return;
            }
            schedule.setCondition(new QueryLibrarySchedule.Condition(choice.subject(), choice.operator(), value));
        }
        result = schedule;
        dispose();
    }
}
