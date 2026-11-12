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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
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
 * - 2026-11-06 Connections·Parameters 목록이 한 줄로 줄고 빈 띠가 생기던 문제: 창이 선호 크기보다 1px만 작아도(화면 배율 반올림)
 *   GridBagLayout이 모든 칸을 최소 크기로 놓는데 스크롤 창의 최소 높이가 한 줄이었다. 두 목록에 최소 높이와 세로 가중치를 줌
 *   (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-12 Parameters 목록의 두 열을 머리글과 값의 폭으로({@link #sizeParameterColumns}): 표가 폭을 반씩 나눠 영어 머리글이
 *   "Value for scheduled r…"로 잘렸다(앱 변경 #81, docs/chromite/swing-query-library.md "예약 실행")
 */
final class QueryScheduleDialog extends JDialog {

    // Room around a Parameters column's text (cell margins and the header's sort area)
    private static final int COLUMN_PADDING = 12;

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
        new ConditionChoice(ToolsMessages.schedule_condition_always, null, null),
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_rows, ">"), "rows", ">"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_rows, ">="), "rows", ">="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_rows, "="), "rows", "="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, ">"), "first", ">"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, ">="), "first", ">="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, "<"), "first", "<"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, "<="), "first", "<="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, "="), "first", "="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ConditionChoice(NLS.bind(ToolsMessages.schedule_choice_first, "!="), "first", "!="), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    /**
     * A connection that can be checked: one of this computer, or a target of another computer that was not found.
     */
    private record TargetChoice(@NotNull QueryLibrarySchedule.Target target, @Nullable DBPDataSourceContainer container) {
        @Override
        public String toString() {
            return container != null ? container.getName() : NLS.bind(ToolsMessages.library_connection_not_found, target.name());
        }
    }

    private final JCheckBox enabledBox = new JCheckBox(ToolsMessages.schedule_enabled);
    private final JRadioButton intervalButton = new JRadioButton(ToolsMessages.schedule_every);
    private final JRadioButton dailyButton = new JRadioButton(ToolsMessages.schedule_daily);
    private final JRadioButton weekdaysButton = new JRadioButton(ToolsMessages.schedule_weekdays);
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 10000, 1));
    private final JComboBox<String> unitCombo = new JComboBox<>(new String[]{ToolsMessages.schedule_unit_minutes, ToolsMessages.schedule_unit_hours});
    private final JTextField dailyField = new JTextField(5);
    private final JTextField weekdaysField = new JTextField(5);
    private final List<JCheckBox> targetBoxes = new ArrayList<>();
    private final List<TargetChoice> targetChoices = new ArrayList<>();
    private final DefaultTableModel parametersModel = new DefaultTableModel(
        new Object[]{ToolsMessages.schedule_column_parameter, ToolsMessages.schedule_column_value}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }
    };
    private final JComboBox<ConditionChoice> conditionCombo = new JComboBox<>(CONDITIONS);
    private final JTextField conditionValueField = new JTextField(8);
    private final JCheckBox activeBox = new JCheckBox(ToolsMessages.schedule_active);
    private final JLabel errorLabel = new JLabel(" ");
    private final JTable parametersTable = new JTable(parametersModel);
    @Nullable
    private QueryLibrarySchedule result;
    private boolean removed;

    private QueryScheduleDialog(@NotNull Window owner, @NotNull QueryLibraryItem item, boolean active) {
        super(owner, NLS.bind(ToolsMessages.schedule_title, item.getName()), ModalityType.APPLICATION_MODAL);
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
        activeBox.setToolTipText(ToolsMessages.schedule_active_tip);

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
        // GridBagLayout lays every cell out at its minimum size as soon as the form is a little smaller than preferred,
        // and a scroll pane's minimum is one line: keep a few rows
        targetsScroll.setMinimumSize(new Dimension(160, 80));

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
        parametersScroll.setPreferredSize(new Dimension(Math.max(260, sizeParameterColumns()), 90));
        parametersScroll.setMinimumSize(new Dimension(160, 70));

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
        conditionCombo.setToolTipText(ToolsMessages.schedule_condition_tip);

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
        addRow(form, row++, null, enabledBox, 0);
        addRow(form, row++, ToolsMessages.schedule_when, when, 0);
        // The two lists take the extra height of a larger dialog; the other rows keep theirs
        addRow(form, row++, ToolsMessages.schedule_connections, targetsScroll, 1);
        addRow(form, row++, ToolsMessages.schedule_parameters, parametersScroll, 1);
        addRow(form, row++, ToolsMessages.schedule_alert_when, conditionPanel, 0);
        addRow(form, row, null, activeBox, 0);

        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> accept());
        JButton removeButton = new JButton(ToolsMessages.schedule_remove);
        removeButton.setEnabled(item.getSchedule() != null);
        removeButton.addActionListener(e -> {
            result = null;
            removed = true;
            dispose();
        });
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

    /**
     * Gives each Parameters column the width of its header and values (the table splits its width in equal halves, which
     * cut the longer header: "Value for scheduled r…" in English).
     *
     * @return the width of the list that shows both columns whole, with a vertical scroll bar
     */
    private int sizeParameterColumns() {
        int total = 0;
        TableCellRenderer headerRenderer = parametersTable.getTableHeader().getDefaultRenderer();
        for (int column = 0; column < parametersTable.getColumnCount(); column++) {
            TableColumn tableColumn = parametersTable.getColumnModel().getColumn(column);
            int width = headerRenderer.getTableCellRendererComponent(parametersTable, tableColumn.getHeaderValue(), false, false, -1, column)
                .getPreferredSize().width;
            for (int row = 0; row < parametersTable.getRowCount(); row++) {
                width = Math.max(width, parametersTable.prepareRenderer(parametersTable.getCellRenderer(row, column), row, column)
                    .getPreferredSize().width);
            }
            width += COLUMN_PADDING;
            tableColumn.setPreferredWidth(width);
            total += width;
        }
        return total + UIManager.getInt("ScrollBar.width") + COLUMN_PADDING; //$NON-NLS-1$
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

    /**
     * @param weighty share of the extra height; a control that gets some fills its cell in both directions
     */
    private static void addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, double weighty) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 8, 10);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.fill = weighty > 0 ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.weighty = weighty;
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
            errorLabel.setText(ToolsMessages.schedule_error_time);
            return;
        }
        for (int i = 0; i < targetBoxes.size(); i++) {
            if (targetBoxes.get(i).isSelected()) {
                schedule.getTargets().add(targetChoices.get(i).target());
            }
        }
        if (schedule.getTargets().isEmpty()) {
            errorLabel.setText(ToolsMessages.schedule_error_connections);
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
                errorLabel.setText(ToolsMessages.schedule_error_condition);
                return;
            }
            schedule.setCondition(new QueryLibrarySchedule.Condition(choice.subject(), choice.operator(), value));
        }
        result = schedule;
        dispose();
    }
}
