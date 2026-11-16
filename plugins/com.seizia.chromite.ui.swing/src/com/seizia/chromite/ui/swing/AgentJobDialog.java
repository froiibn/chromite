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

import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgent;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJob;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobDefinition;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobStep;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentSchedule;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Job Properties / New Job (SQL Server Agent): General, Steps, Schedules and Notifications pages.
 *
 * 설계: SSMS 작업 속성 창의 페이지 구성을 따른다(대상·경고 페이지는 남긴 것). 편집은 읽은 정의의 복사본({@link SQLServerAgentJobDefinition#copy})에
 * 하고, 확인을 누르면 모델이 원본과의 차이로 문을 만든다({@link AgentObjectDialog}가 스크립트를 보여 주고 실행).
 * 단계 목록에서 단계를 넣거나 지우거나 옮기면 다른 단계의 "단계로 이동" 번호와 시작 단계를 함께 고친다(번호가 단계의 위치이므로).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentJobDialog extends AgentObjectDialog {

    private static final int[] LEVELS = {
        SQLServerAgentJobDefinition.LEVEL_ON_SUCCESS, SQLServerAgentJobDefinition.LEVEL_ON_FAILURE, SQLServerAgentJobDefinition.LEVEL_ON_COMPLETION};

    @Nullable
    private final SQLServerAgentJobDefinition original;
    private final SQLServerAgentJobDefinition edited;
    private final SQLServerAgentManager.Lookups lookups;

    private final JTextField nameField = new JTextField();
    private final JComboBox<String> ownerCombo = new JComboBox<>();
    private final JComboBox<String> categoryCombo = new JComboBox<>();
    private final JTextArea descriptionArea = new JTextArea(5, 30);
    private final JCheckBox enabledCheck = new JCheckBox(AgentMessages.agent_enabled);
    private final StepTableModel stepModel = new StepTableModel();
    private final JTable stepTable = new JTable(stepModel);
    private final JComboBox<String> startStepCombo = new JComboBox<>();
    private final ScheduleTableModel scheduleModel = new ScheduleTableModel();
    private final JTable scheduleTable = new JTable(scheduleModel);
    private final JCheckBox emailCheck = new JCheckBox(AgentMessages.agent_job_notify_email);
    private final JComboBox<String> emailOperatorCombo = new JComboBox<>();
    private final JComboBox<String> emailLevelCombo = createLevelCombo();
    private final JCheckBox eventLogCheck = new JCheckBox(AgentMessages.agent_job_notify_eventlog);
    private final JComboBox<String> eventLogLevelCombo = createLevelCombo();
    private final JCheckBox deleteCheck = new JCheckBox(AgentMessages.agent_job_notify_delete);
    private final JComboBox<String> deleteLevelCombo = createLevelCombo();

    private AgentJobDialog(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentJobDefinition original,
        @NotNull SQLServerAgentManager.Lookups lookups,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, original == null ? AgentMessages.agent_job_new_title : NLS.bind(AgentMessages.agent_job_title, original.getName()),
            agent, agentNode, queryWindows);
        this.original = original;
        this.lookups = lookups;
        edited = original != null ? original.copy() : SQLServerAgentJobDefinition.newJob(lookups.currentLogin());
        initDialog(List.of(
            new Page(AgentMessages.agent_page_general, createGeneralPage()),
            new Page(AgentMessages.agent_page_steps, createStepsPage()),
            new Page(AgentMessages.agent_page_schedules, createSchedulesPage()),
            new Page(AgentMessages.agent_page_notifications, createNotificationsPage())));
    }

    /**
     * Reads the job definition (for an existing job) and the dialog values, then opens the dialog.
     *
     * @param job the job to edit, or null for a new job
     */
    static void open(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentJob job,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        String what = job == null ? AgentMessages.agent_job_new_title : job.getName();
        Object[] loaded;
        try {
            loaded = ProgressDialog.runTask(AgentMessages.agent_progress_read, monitor -> new Object[] {
                SQLServerAgentManager.readLookups(monitor, agent),
                job == null ? null : SQLServerAgentJobDefinition.load(monitor, agent, job.getJobId())
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(what, NLS.bind(AgentMessages.agent_error_read, what), e);
            return;
        }
        new AgentJobDialog(owner, agent, (SQLServerAgentJobDefinition) loaded[1], (SQLServerAgentManager.Lookups) loaded[0],
            agentNode, queryWindows).open();
    }

    //////////////////////////////////////////////////////////
    // Pages

    @NotNull
    private JComponent createGeneralPage() {
        nameField.setText(edited.getName());
        ownerCombo.setEditable(true);
        for (String login : lookups.logins()) {
            ownerCombo.addItem(login);
        }
        ownerCombo.setSelectedItem(edited.getOwner());
        for (String category : lookups.categories()) {
            categoryCombo.addItem(category);
        }
        if (!lookups.categories().contains(edited.getCategory())) {
            categoryCombo.addItem(edited.getCategory());
        }
        categoryCombo.setSelectedItem(edited.getCategory());
        descriptionArea.setText(edited.getDescription());
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        enabledCheck.setSelected(edited.isEnabled());

        FormPanel general = new FormPanel();
        general.addRow(AgentMessages.agent_name, nameField);
        general.addRow(AgentMessages.agent_job_owner, ownerCombo);
        general.addRow(AgentMessages.agent_job_category, categoryCombo);
        general.addFull(new JLabel(AgentMessages.agent_job_description));
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setPreferredSize(new Dimension(300, 110));
        general.addFull(descriptionScroll);
        general.addFull(enabledCheck);
        general.finish();
        return general;
    }

    @NotNull
    private JComponent createStepsPage() {
        stepTable.setFillsViewportHeight(true);
        stepTable.setShowGrid(false);
        stepTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stepTable.getColumnModel().getColumn(0).setMaxWidth(60);
        stepTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && stepTable.getSelectedRow() >= 0) {
                    editStep();
                }
            }
        });
        JButton newButton = new JButton(AgentMessages.agent_button_new);
        newButton.addActionListener(e -> addStep(edited.getSteps().size()));
        JButton insertButton = new JButton(AgentMessages.agent_button_insert);
        insertButton.addActionListener(e -> addStep(Math.max(0, stepTable.getSelectedRow())));
        JButton editButton = new JButton(AgentMessages.agent_button_edit);
        editButton.addActionListener(e -> editStep());
        JButton deleteButton = new JButton(AgentMessages.agent_button_delete);
        deleteButton.addActionListener(e -> deleteStep());
        JButton upButton = new JButton(AgentMessages.agent_button_move_up);
        upButton.addActionListener(e -> moveStep(-1));
        JButton downButton = new JButton(AgentMessages.agent_button_move_down);
        downButton.addActionListener(e -> moveStep(1));
        Runnable updateButtons = () -> {
            int row = stepTable.getSelectedRow();
            insertButton.setEnabled(row >= 0);
            editButton.setEnabled(row >= 0);
            deleteButton.setEnabled(row >= 0);
            upButton.setEnabled(row > 0);
            downButton.setEnabled(row >= 0 && row < edited.getSteps().size() - 1);
        };
        stepTable.getSelectionModel().addListSelectionListener(e -> updateButtons.run());
        stepModel.addTableModelListener(e -> updateButtons.run());
        updateButtons.run();
        updateStartStepCombo();

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JButton button : List.of(newButton, insertButton, editButton, deleteButton, upButton, downButton)) {
            buttons.add(button);
        }
        FormPanel steps = new FormPanel();
        steps.addFull(new JLabel(AgentMessages.agent_job_steps_list));
        steps.addFiller(new JScrollPane(stepTable));
        steps.addFull(buttons);
        steps.addRow(AgentMessages.agent_job_start_step, startStepCombo);
        return steps;
    }

    @NotNull
    private JComponent createSchedulesPage() {
        scheduleTable.setFillsViewportHeight(true);
        scheduleTable.setShowGrid(false);
        scheduleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scheduleTable.getColumnModel().getColumn(1).setMaxWidth(80);
        scheduleTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        scheduleTable.getColumnModel().getColumn(2).setPreferredWidth(420);
        scheduleTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && scheduleTable.getSelectedRow() >= 0) {
                    editSchedule();
                }
            }
        });
        JButton newButton = new JButton(AgentMessages.agent_button_new);
        newButton.addActionListener(e -> {
            SQLServerAgentSchedule schedule = new SQLServerAgentSchedule();
            if (AgentScheduleDialog.edit(this, schedule, true)) {
                edited.getSchedules().add(schedule);
                scheduleModel.fireTableDataChanged();
            }
        });
        JButton editButton = new JButton(AgentMessages.agent_button_edit);
        editButton.addActionListener(e -> editSchedule());
        JButton removeButton = new JButton(AgentMessages.agent_button_remove);
        removeButton.addActionListener(e -> {
            int row = scheduleTable.getSelectedRow();
            if (row >= 0) {
                edited.getSchedules().remove(row);
                scheduleModel.fireTableDataChanged();
            }
        });
        Runnable updateButtons = () -> {
            editButton.setEnabled(scheduleTable.getSelectedRow() >= 0);
            removeButton.setEnabled(scheduleTable.getSelectedRow() >= 0);
        };
        scheduleTable.getSelectionModel().addListSelectionListener(e -> updateButtons.run());
        scheduleModel.addTableModelListener(e -> updateButtons.run());
        updateButtons.run();

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(newButton);
        buttons.add(editButton);
        buttons.add(removeButton);
        FormPanel schedules = new FormPanel();
        schedules.addFull(new JLabel(AgentMessages.agent_job_schedules_list));
        schedules.addFiller(new JScrollPane(scheduleTable));
        schedules.addFull(buttons);
        return schedules;
    }

    @NotNull
    private JComponent createNotificationsPage() {
        for (String operator : lookups.operators()) {
            emailOperatorCombo.addItem(operator);
        }
        String operator = edited.getNotifyEmailOperator();
        if (operator != null && !lookups.operators().contains(operator)) {
            emailOperatorCombo.addItem(operator);
        }
        if (operator != null) {
            emailOperatorCombo.setSelectedItem(operator);
        }
        emailCheck.setSelected(operator != null && edited.getNotifyLevelEmail() != SQLServerAgentJobDefinition.LEVEL_NEVER);
        emailCheck.setEnabled(emailOperatorCombo.getItemCount() > 0);
        if (!emailCheck.isEnabled()) {
            emailCheck.setToolTipText(AgentMessages.agent_job_no_operators);
        }
        selectLevel(emailLevelCombo, edited.getNotifyLevelEmail());
        eventLogCheck.setSelected(edited.getNotifyLevelEventlog() != SQLServerAgentJobDefinition.LEVEL_NEVER);
        selectLevel(eventLogLevelCombo, edited.getNotifyLevelEventlog());
        deleteCheck.setSelected(edited.getDeleteLevel() != SQLServerAgentJobDefinition.LEVEL_NEVER);
        selectLevel(deleteLevelCombo, edited.getDeleteLevel());
        Runnable update = () -> {
            emailOperatorCombo.setEnabled(emailCheck.isSelected());
            emailLevelCombo.setEnabled(emailCheck.isSelected());
            eventLogLevelCombo.setEnabled(eventLogCheck.isSelected());
            deleteLevelCombo.setEnabled(deleteCheck.isSelected());
        };
        for (JCheckBox check : List.of(emailCheck, eventLogCheck, deleteCheck)) {
            check.addActionListener(e -> update.run());
        }
        update.run();

        FormPanel notifications = new FormPanel();
        notifications.addFull(new JLabel(AgentMessages.agent_job_notify_header));
        notifications.addFull(emailCheck);
        notifications.addRow(AgentMessages.agent_job_notify_operator, emailOperatorCombo);
        notifications.addRow(AgentMessages.agent_job_notify_when, emailLevelCombo);
        if (!emailCheck.isEnabled()) {
            notifications.addFull(FormPanel.note(AgentMessages.agent_job_no_operators));
        }
        notifications.addFull(eventLogCheck);
        notifications.addRow(AgentMessages.agent_job_notify_when, eventLogLevelCombo);
        notifications.addFull(deleteCheck);
        notifications.addRow(AgentMessages.agent_job_notify_when, deleteLevelCombo);
        notifications.finish();
        return notifications;
    }

    @NotNull
    private static JComboBox<String> createLevelCombo() {
        return new JComboBox<>(new String[] {
            AgentMessages.agent_job_level_success, AgentMessages.agent_job_level_failure, AgentMessages.agent_job_level_completion});
    }

    private static void selectLevel(@NotNull JComboBox<String> combo, int level) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == level) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        // Never: the default of a newly checked box is "when the job fails"
        combo.setSelectedIndex(1);
    }

    private static int getLevel(@NotNull JCheckBox check, @NotNull JComboBox<String> combo) {
        return check.isSelected() ? LEVELS[Math.max(0, combo.getSelectedIndex())] : SQLServerAgentJobDefinition.LEVEL_NEVER;
    }

    //////////////////////////////////////////////////////////
    // Steps

    private void addStep(int index) {
        SQLServerAgentJobStep step = new SQLServerAgentJobStep();
        List<SQLServerAgentJobStep> steps = edited.getSteps();
        // The dialog lists the other steps by their positions after the insert
        steps.add(index, step);
        renumberReferences(index + 1, 1, -1);
        if (AgentJobStepDialog.edit(this, step, index + 1, steps, lookups.databases(), true)) {
            if (steps.size() == 1) {
                edited.setStartStepId(1);
            } else if (edited.getStartStepId() >= index + 1) {
                edited.setStartStepId(edited.getStartStepId() + 1);
            }
        } else {
            steps.remove(index);
            renumberReferences(index + 2, -1, -1);
        }
        stepModel.fireTableDataChanged();
        updateStartStepCombo();
        selectRow(stepTable, steps.indexOf(step));
    }

    private void editStep() {
        int row = stepTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        SQLServerAgentJobStep edit = edited.getSteps().get(row).copy();
        List<SQLServerAgentJobStep> steps = edited.getSteps();
        if (AgentJobStepDialog.edit(this, edit, row + 1, steps, lookups.databases(), false)) {
            steps.set(row, edit);
            stepModel.fireTableDataChanged();
            updateStartStepCombo();
            selectRow(stepTable, row);
        }
    }

    private void deleteStep() {
        int row = stepTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        List<SQLServerAgentJobStep> steps = edited.getSteps();
        int deletedId = row + 1;
        steps.remove(row);
        for (SQLServerAgentJobStep step : steps) {
            // Jumps to the deleted step go on with the next step (success) or fail the job (failure)
            if (step.getOnSuccessAction() == SQLServerAgentJobStep.ACTION_GO_TO_STEP && step.getOnSuccessStepId() == deletedId) {
                step.setOnSuccessAction(SQLServerAgentJobStep.ACTION_NEXT_STEP);
                step.setOnSuccessStepId(0);
            }
            if (step.getOnFailAction() == SQLServerAgentJobStep.ACTION_GO_TO_STEP && step.getOnFailStepId() == deletedId) {
                step.setOnFailAction(SQLServerAgentJobStep.ACTION_QUIT_FAILURE);
                step.setOnFailStepId(0);
            }
        }
        renumberReferences(deletedId + 1, -1, -1);
        if (edited.getStartStepId() > deletedId || edited.getStartStepId() > steps.size()) {
            edited.setStartStepId(Math.max(1, edited.getStartStepId() - 1));
        }
        stepModel.fireTableDataChanged();
        updateStartStepCombo();
        selectRow(stepTable, Math.min(row, steps.size() - 1));
    }

    private void moveStep(int delta) {
        int row = stepTable.getSelectedRow();
        int target = row + delta;
        List<SQLServerAgentJobStep> steps = edited.getSteps();
        if (row < 0 || target < 0 || target >= steps.size()) {
            return;
        }
        SQLServerAgentJobStep moved = steps.remove(row);
        steps.add(target, moved);
        // The two steps swap their numbers: swap the references to them too
        int a = row + 1;
        int b = target + 1;
        renumberReferences(a, 0, b);
        if (edited.getStartStepId() == a) {
            edited.setStartStepId(b);
        } else if (edited.getStartStepId() == b) {
            edited.setStartStepId(a);
        }
        stepModel.fireTableDataChanged();
        updateStartStepCombo();
        selectRow(stepTable, target);
    }

    /**
     * Fixes the "go to step" numbers after the list changed.
     *
     * @param from  step numbers from this one on are shifted by {@code shift} (insert, delete)
     * @param shift +1 or -1, or 0 to swap {@code from} and {@code swapWith}
     * @param swapWith the other number of a swap, -1 otherwise
     */
    private void renumberReferences(int from, int shift, int swapWith) {
        for (SQLServerAgentJobStep step : edited.getSteps()) {
            if (step.getOnSuccessAction() == SQLServerAgentJobStep.ACTION_GO_TO_STEP) {
                step.setOnSuccessStepId(renumber(step.getOnSuccessStepId(), from, shift, swapWith));
            }
            if (step.getOnFailAction() == SQLServerAgentJobStep.ACTION_GO_TO_STEP) {
                step.setOnFailStepId(renumber(step.getOnFailStepId(), from, shift, swapWith));
            }
        }
    }

    private static int renumber(int stepId, int from, int shift, int swapWith) {
        if (shift == 0) {
            return stepId == from ? swapWith : stepId == swapWith ? from : stepId;
        }
        return stepId >= from ? stepId + shift : stepId;
    }

    private void updateStartStepCombo() {
        startStepCombo.removeAllItems();
        List<SQLServerAgentJobStep> steps = edited.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            startStepCombo.addItem(NLS.bind(AgentMessages.agent_step_item, i + 1, steps.get(i).getName()));
        }
        if (!steps.isEmpty()) {
            startStepCombo.setSelectedIndex(Math.max(0, Math.min(steps.size() - 1, edited.getStartStepId() - 1)));
        }
        startStepCombo.setEnabled(!steps.isEmpty());
    }

    private void editSchedule() {
        int row = scheduleTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        SQLServerAgentSchedule edit = edited.getSchedules().get(row).copy();
        if (AgentScheduleDialog.edit(this, edit, false)) {
            edited.getSchedules().set(row, edit);
            scheduleModel.fireTableDataChanged();
            selectRow(scheduleTable, row);
        }
    }

    private static void selectRow(@NotNull JTable table, int row) {
        if (row >= 0 && row < table.getRowCount()) {
            table.setRowSelectionInterval(row, row);
        }
    }

    //////////////////////////////////////////////////////////
    // Result

    @Nullable
    @Override
    protected String validateInput() {
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            return AgentMessages.agent_error_no_name;
        }
        if (ownerCombo.getSelectedItem() == null || CommonUtils.isEmptyTrimmed(ownerCombo.getSelectedItem().toString())) {
            return AgentMessages.agent_job_error_no_owner;
        }
        return null;
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        edited.setName(nameField.getText().trim());
        Object owner = ownerCombo.getEditor().getItem();
        edited.setOwner(owner == null ? "" : owner.toString().trim()); //$NON-NLS-1$
        edited.setCategory(CommonUtils.notEmpty((String) categoryCombo.getSelectedItem()));
        edited.setDescription(descriptionArea.getText());
        edited.setEnabled(enabledCheck.isSelected());
        if (startStepCombo.getSelectedIndex() >= 0) {
            edited.setStartStepId(startStepCombo.getSelectedIndex() + 1);
        }
        int emailLevel = getLevel(emailCheck, emailLevelCombo);
        edited.setNotifyLevelEmail(emailLevel);
        edited.setNotifyEmailOperator(emailLevel == SQLServerAgentJobDefinition.LEVEL_NEVER ? null : (String) emailOperatorCombo.getSelectedItem());
        edited.setNotifyLevelEventlog(getLevel(eventLogCheck, eventLogLevelCombo));
        edited.setDeleteLevel(getLevel(deleteCheck, deleteLevelCombo));
        return original == null ? edited.generateCreateStatements() : edited.generateAlterStatements(original);
    }

    //////////////////////////////////////////////////////////
    // Table models

    private final class StepTableModel extends AbstractTableModel {
        private final String[] columns = {AgentMessages.agent_job_column_step, AgentMessages.agent_column_name,
            AgentMessages.agent_job_column_type, AgentMessages.agent_job_column_on_success, AgentMessages.agent_job_column_on_failure};

        @Override
        public int getRowCount() {
            return edited.getSteps().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SQLServerAgentJobStep step = edited.getSteps().get(rowIndex);
            return switch (columnIndex) {
                case 0 -> String.valueOf(rowIndex + 1);
                case 1 -> step.getName();
                case 2 -> AgentJobStepDialog.describeSubsystem(step.getSubsystem());
                case 3 -> AgentJobStepDialog.describeAction(step.getOnSuccessAction(), step.getOnSuccessStepId());
                default -> AgentJobStepDialog.describeAction(step.getOnFailAction(), step.getOnFailStepId());
            };
        }
    }

    private final class ScheduleTableModel extends AbstractTableModel {
        private final String[] columns = {AgentMessages.agent_column_name, AgentMessages.agent_enabled, AgentMessages.agent_job_column_description};

        @Override
        public int getRowCount() {
            return edited.getSchedules().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SQLServerAgentSchedule schedule = edited.getSchedules().get(rowIndex);
            return switch (columnIndex) {
                case 0 -> schedule.getName();
                case 1 -> schedule.isEnabled();
                default -> AgentScheduleDialog.describe(schedule);
            };
        }
    }
}
