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
import com.seizia.chromite.ext.oracle.model.OracleSchedulerJob;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerJobDefinition;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerManager;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * New Job / Job Properties of the Oracle Scheduler: name, job type (PL/SQL block or stored procedure) and action,
 * enabled, auto drop, comments; start date, repeat interval (with the calendar helper and the next run dates) and end date.
 *
 * 설계: 문은 모델({@link OracleSchedulerJobDefinition})이 만들고, 확인·미리 보기·실행은 {@link SchedulerScriptDialog}가 한다. 창을 열기 전에
 * 진행 대화상자에서 작업의 현재 값과 스키마의 프로시저 목록을 읽는다. 프로그램이나 이름 있는 일정을 쓰는 작업은 그 칸을 읽기 전용으로
 * 두고 이유를 적는다. "다음 실행 날짜"는 {@code DBMS_SCHEDULER.EVALUATE_CALENDAR_STRING}으로 서버가 계산한 날짜를 보여 주어 달력 식을
 * 저장 전에 확인하게 한다(아무것도 바꾸지 않는 호출이다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
final class OracleJobDialog extends SchedulerScriptDialog {

    private static final int NEXT_RUN_COUNT = 5;

    private record TypeChoice(@NotNull String type, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final OracleSchema schema;
    private final OracleSchedulerJobDefinition definition;
    private final JTextField nameField = new JTextField();
    private final JComboBox<TypeChoice> typeCombo = new JComboBox<>();
    private final SqlEditorPanel.SqlTextArea blockText = new SqlEditorPanel.SqlTextArea();
    private final JComboBox<String> procedureCombo = new JComboBox<>();
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionPanel = new JPanel(actionCards);
    private final JCheckBox enabledCheck = new JCheckBox(SchedulerMessages.scheduler_enabled_check);
    private final JCheckBox autoDropCheck = new JCheckBox(SchedulerMessages.oracle_auto_drop);
    private final JTextField commentsField = new JTextField();
    private final JTextField startField = new JTextField();
    private final JTextField repeatField = new JTextField();
    private final JTextField endField = new JTextField();
    private final JTextArea nextRunsArea = new JTextArea(NEXT_RUN_COUNT, 30);

    private OracleJobDialog(
        @NotNull Component owner,
        @NotNull OracleSchema schema,
        @NotNull OracleSchedulerJobDefinition definition,
        @NotNull List<String> procedures,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, definition.isNew() ? NLS.bind(SchedulerMessages.oracle_job_new_title, schema.getName())
                : NLS.bind(SchedulerMessages.oracle_job_title, definition.getName()),
            SchedulerCommands.oracle(schema.getDataSource()), refreshNode, queryWindows);
        this.schema = schema;
        this.definition = definition;

        // General
        nameField.setText(definition.getName());
        nameField.setEditable(definition.isNew());
        typeCombo.addItem(new TypeChoice(OracleSchedulerJobDefinition.TYPE_PLSQL_BLOCK, SchedulerMessages.oracle_type_plsql));
        typeCombo.addItem(new TypeChoice(OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE, SchedulerMessages.oracle_type_procedure));
        boolean actionEditable = definition.isActionEditable();
        if (!actionEditable) {
            typeCombo.addItem(new TypeChoice(definition.getJobType(), definition.getJobType()));
        }
        for (int i = 0; i < typeCombo.getItemCount(); i++) {
            if (typeCombo.getItemAt(i).type().equals(definition.getJobType())) {
                typeCombo.setSelectedIndex(i);
            }
        }
        typeCombo.setEnabled(actionEditable);
        procedureCombo.setEditable(true);
        for (String procedure : procedures) {
            procedureCombo.addItem(procedure);
        }
        boolean procedureType = OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE.equals(definition.getJobType());
        procedureCombo.setSelectedItem(procedureType ? definition.getJobAction() : procedures.isEmpty() ? "" : procedures.get(0)); //$NON-NLS-1$
        procedureCombo.setEnabled(actionEditable);
        blockText.setText(procedureType ? "" : definition.getJobAction()); //$NON-NLS-1$
        blockText.setCaretPosition(0);
        blockText.discardAllEdits();
        blockText.setEditable(actionEditable);
        RTextScrollPane blockScroll = new RTextScrollPane(blockText, true);
        blockText.applyTheme();
        DatabaseTaskDialog.FormPanel procedurePanel = new DatabaseTaskDialog.FormPanel();
        procedurePanel.addRow(SchedulerMessages.oracle_procedure, procedureCombo);
        procedurePanel.addFull(DatabaseTaskDialog.FormPanel.note(SchedulerMessages.oracle_procedure_note));
        procedurePanel.finish();
        actionPanel.add(blockScroll, OracleSchedulerJobDefinition.TYPE_PLSQL_BLOCK);
        actionPanel.add(procedurePanel, OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE);
        typeCombo.addActionListener(e -> showActionCard());
        showActionCard();
        enabledCheck.setSelected(definition.isEnabled());
        autoDropCheck.setSelected(definition.isAutoDrop());
        commentsField.setText(CommonUtils.notEmpty(definition.getComments()));

        DatabaseTaskDialog.FormPanel general = new DatabaseTaskDialog.FormPanel();
        general.addRow(SchedulerMessages.scheduler_name, nameField);
        general.addRow(SchedulerMessages.oracle_schema, new JLabel(schema.getName()));
        general.addRow(SchedulerMessages.oracle_job_type, typeCombo);
        if (!actionEditable) {
            general.addFull(DatabaseTaskDialog.FormPanel.note(definition.getProgramName() != null
                ? NLS.bind(SchedulerMessages.oracle_program_note, definition.getProgramName())
                : NLS.bind(SchedulerMessages.oracle_type_note, definition.getJobType())));
        }
        general.addRow(SchedulerMessages.scheduler_comments, commentsField);
        general.addFull(enabledCheck);
        general.addFull(autoDropCheck);
        general.addFull(new JLabel(SchedulerMessages.oracle_action));
        general.addFiller(actionPanel);

        // Schedule
        boolean scheduleEditable = definition.isScheduleEditable();
        startField.setText(CommonUtils.notEmpty(definition.getStartDate()));
        repeatField.setText(CommonUtils.notEmpty(definition.getRepeatInterval()));
        endField.setText(CommonUtils.notEmpty(definition.getEndDate()));
        for (JTextField field : List.of(startField, endField)) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.oracle_date_placeholder);
            field.setEditable(scheduleEditable);
        }
        repeatField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.oracle_repeat_placeholder);
        repeatField.setEditable(scheduleEditable);
        JButton buildButton = new JButton(SchedulerMessages.scheduler_build);
        buildButton.setToolTipText(SchedulerMessages.scheduler_build_tip);
        buildButton.setEnabled(scheduleEditable);
        buildButton.addActionListener(e -> {
            String built = ScheduleBuilderDialog.show(this, ScheduleBuilderDialog.Syntax.ORACLE_CALENDAR);
            if (built != null) {
                repeatField.setText(built);
            }
        });
        JPanel repeatPanel = new JPanel(new BorderLayout(6, 0));
        repeatPanel.setOpaque(false);
        repeatPanel.add(repeatField, BorderLayout.CENTER);
        repeatPanel.add(buildButton, BorderLayout.EAST);
        nextRunsArea.setEditable(false);
        nextRunsArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        JButton nextRunsButton = new JButton(SchedulerMessages.oracle_next_runs);
        nextRunsButton.setToolTipText(SchedulerMessages.oracle_next_runs_tip);
        nextRunsButton.addActionListener(e -> showNextRuns());

        DatabaseTaskDialog.FormPanel schedulePage = new DatabaseTaskDialog.FormPanel();
        if (!scheduleEditable) {
            schedulePage.addFull(DatabaseTaskDialog.FormPanel.note(NLS.bind(SchedulerMessages.oracle_schedule_note, definition.getScheduleName())));
        }
        schedulePage.addRow(SchedulerMessages.scheduler_start_date, startField);
        schedulePage.addRow(SchedulerMessages.oracle_repeat_interval, repeatPanel);
        schedulePage.addRow(SchedulerMessages.scheduler_end_date, endField);
        schedulePage.addFull(DatabaseTaskDialog.FormPanel.note(SchedulerMessages.oracle_schedule_help));
        JPanel nextRunsRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        nextRunsRow.setOpaque(false);
        nextRunsRow.add(nextRunsButton);
        schedulePage.addFull(nextRunsRow);
        schedulePage.addFull(new JScrollPane(nextRunsArea));
        schedulePage.finish();

        initDialog(List.of(new Page(SchedulerMessages.scheduler_page_general, general), new Page(SchedulerMessages.scheduler_page_schedule, schedulePage)));
    }

    /**
     * Reads the job (and the schema's procedures) in a progress dialog, then shows the dialog.
     *
     * @param job the job to edit, or null for a new job
     */
    static void open(
        @NotNull Component owner,
        @NotNull OracleSchema schema,
        @Nullable OracleSchedulerJob job,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        String title = job == null ? NLS.bind(SchedulerMessages.oracle_job_new_title, schema.getName()) : NLS.bind(SchedulerMessages.oracle_job_title, job.getName());
        OracleSchedulerJobDefinition definition;
        List<String> procedures;
        try {
            Object[] loaded = ProgressDialog.runTask(title, monitor -> new Object[] {
                job == null ? new OracleSchedulerJobDefinition(schema) : OracleSchedulerJobDefinition.load(monitor, job),
                OracleSchedulerManager.readProcedures(monitor, schema)});
            definition = (OracleSchedulerJobDefinition) loaded[0];
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) loaded[1];
            procedures = names;
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, SchedulerMessages.scheduler_error_read, e);
            return;
        }
        new OracleJobDialog(owner, schema, definition, procedures, refreshNode, queryWindows).open();
    }

    private void showActionCard() {
        TypeChoice choice = (TypeChoice) typeCombo.getSelectedItem();
        boolean procedure = choice != null && OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE.equals(choice.type());
        actionCards.show(actionPanel, procedure ? OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE : OracleSchedulerJobDefinition.TYPE_PLSQL_BLOCK);
    }

    /**
     * Next run dates of the repeat interval, computed by the server.
     */
    private void showNextRuns() {
        String interval = repeatField.getText().trim();
        if (interval.isEmpty()) {
            nextRunsArea.setText(SchedulerMessages.oracle_next_runs_once);
            return;
        }
        String start = startField.getText().trim();
        try {
            List<String> dates = ProgressDialog.runTask(SchedulerMessages.oracle_next_runs,
                monitor -> OracleSchedulerManager.evaluateCalendar(monitor, schema.getDataSource(), interval, start, NEXT_RUN_COUNT));
            nextRunsArea.setText(dates.isEmpty() ? SchedulerMessages.oracle_next_runs_none : String.join("\n", dates)); //$NON-NLS-1$
        } catch (DBInterruptedException e) {
            // Canceled: keep what was shown
        } catch (DBException e) {
            nextRunsArea.setText(ErrorText.describe(e));
        }
        nextRunsArea.setCaretPosition(0);
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (definition.isNew() && CommonUtils.isEmptyTrimmed(nameField.getText())) {
            return SchedulerMessages.scheduler_error_no_name;
        }
        if (definition.isActionEditable() && CommonUtils.isEmptyTrimmed(getAction())) {
            return SchedulerMessages.oracle_error_no_action;
        }
        return null;
    }

    @Nullable
    @Override
    protected String getDeniedReason() {
        OracleSchedulerManager.Access access = SchedulerCommands.getOracleAccess(schema.getDataSource());
        if (access == null) {
            return null;
        }
        if (definition.isNew() ? !access.canCreate(schema.getName()) : !access.canAlter(schema.getName())) {
            return NLS.bind(definition.isNew() ? SchedulerMessages.scheduler_tip_oracle_create : SchedulerMessages.scheduler_tip_oracle_alter, schema.getName());
        }
        return null;
    }

    @NotNull
    private String getAction() {
        TypeChoice choice = (TypeChoice) typeCombo.getSelectedItem();
        if (choice != null && OracleSchedulerJobDefinition.TYPE_STORED_PROCEDURE.equals(choice.type())) {
            Object selected = procedureCombo.getEditor().getItem();
            return selected == null ? "" : selected.toString().trim(); //$NON-NLS-1$
        }
        return blockText.getText();
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        if (definition.isNew()) {
            definition.setName(OracleSchedulerManager.normalizeName(nameField.getText()));
        }
        if (definition.isActionEditable()) {
            TypeChoice choice = (TypeChoice) typeCombo.getSelectedItem();
            if (choice != null) {
                definition.setJobType(choice.type());
            }
            definition.setJobAction(getAction());
        }
        if (definition.isScheduleEditable()) {
            definition.setStartDate(startField.getText());
            definition.setRepeatInterval(repeatField.getText());
            definition.setEndDate(endField.getText());
        }
        definition.setEnabled(enabledCheck.isSelected());
        definition.setAutoDrop(autoDropCheck.isSelected());
        definition.setComments(commentsField.getText().trim());
        return definition.generateStatements();
    }
}
