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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentAlert;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentAlertDefinition;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
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
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Alert properties / New Alert (SQL Server Agent): an event alert raised by an error number or a severity, its response
 * (run a job, e-mail operators) and options.
 *
 * 설계: SSMS 새 경고 창의 세 페이지(일반, 응답, 옵션)를 따른다. 형식은 "SQL Server 이벤트 경고"만 만든다. 다른 형식(성능 조건, WMI)의
 * 기존 경고는 이름·사용·응답·옵션만 고치고 이벤트 칸은 끈다. 문은 모델({@link SQLServerAgentAlertDefinition})이 만든다.
 * 심각도는 번호(001–025)로 고른다(SSMS의 심각도 설명 문구는 남긴 것).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentAlertDialog extends AgentObjectDialog {

    private static final int MAX_SEVERITY = 25;

    @Nullable
    private final SQLServerAgentAlertDefinition original;
    private final JTextField nameField = new JTextField();
    private final JCheckBox enabledCheck = new JCheckBox(AgentMessages.agent_enabled);
    private final JComboBox<String> databaseCombo;
    private final JRadioButton messageRadio = new JRadioButton(AgentMessages.agent_alert_error_number);
    private final JRadioButton severityRadio = new JRadioButton(AgentMessages.agent_alert_severity);
    private final JSpinner messageSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JComboBox<String> severityCombo = new JComboBox<>();
    private final JCheckBox jobCheck = new JCheckBox(AgentMessages.agent_alert_execute_job);
    private final JComboBox<String> jobCombo = new JComboBox<>();
    private final JCheckBox notifyCheck = new JCheckBox(AgentMessages.agent_alert_notify_operators);
    private final DefaultTableModel operatorModel = new DefaultTableModel(
        new Object[] {AgentMessages.agent_alert_column_operator, AgentMessages.agent_alert_column_email}, 0) {
        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1 && notifyCheck.isSelected();
        }
    };
    private final JCheckBox includeTextCheck = new JCheckBox(AgentMessages.agent_alert_include_text);
    private final JSpinner delayMinutes = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JSpinner delaySeconds = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));

    private AgentAlertDialog(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentAlert alert,
        @Nullable SQLServerAgentAlertDefinition original,
        @NotNull SQLServerAgentManager.Lookups lookups,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, alert == null ? AgentMessages.agent_alert_new_title : NLS.bind(AgentMessages.agent_alert_title, alert.getName()),
            agent, agentNode, queryWindows);
        this.original = original;
        SQLServerAgentAlertDefinition values = original != null ? original : SQLServerAgentAlertDefinition.newAlert();
        boolean eventAlert = alert == null || alert.getType() == SQLServerAgentAlert.TYPE_EVENT;

        // General
        nameField.setText(values.getName());
        enabledCheck.setSelected(values.isEnabled());
        databaseCombo = createChoice(AgentMessages.agent_alert_all_databases, lookups.databases(), values.getDatabaseName());
        for (int severity = 1; severity <= MAX_SEVERITY; severity++) {
            severityCombo.addItem(String.format("%03d", severity)); //$NON-NLS-1$
        }
        ButtonGroup raisedBy = new ButtonGroup();
        raisedBy.add(messageRadio);
        raisedBy.add(severityRadio);
        if (values.getMessageId() > 0) {
            messageRadio.setSelected(true);
            messageSpinner.setValue(values.getMessageId());
        } else {
            severityRadio.setSelected(true);
            severityCombo.setSelectedIndex(Math.max(0, Math.min(MAX_SEVERITY, values.getSeverity()) - 1));
        }
        messageRadio.addActionListener(e -> updateControls());
        severityRadio.addActionListener(e -> updateControls());
        // Error numbers without digit grouping
        messageSpinner.setEditor(new JSpinner.NumberEditor(messageSpinner, "#")); //$NON-NLS-1$
        JLabel typeField = new JLabel(eventAlert ? AgentMessages.agent_alert_type_event : AgentMessages.agent_alert_type_other);

        DatabaseTaskDialog.FormPanel general = new DatabaseTaskDialog.FormPanel();
        general.addRow(AgentMessages.agent_name, nameField);
        general.addFull(enabledCheck);
        general.addRow(AgentMessages.agent_alert_type, typeField);
        general.addSection(AgentMessages.agent_alert_section_event);
        general.addRow(AgentMessages.agent_alert_database, databaseCombo);
        general.addFull(new JLabel(AgentMessages.agent_alert_raised_by));
        general.addRow(messageRadio.getText(), messageSpinner);
        general.addRow(severityRadio.getText(), severityCombo);
        general.finish();
        // The radio buttons are the row labels' buttons: replace the plain labels by them
        replaceLabel(general, messageRadio, messageSpinner);
        replaceLabel(general, severityRadio, severityCombo);
        if (!eventAlert) {
            for (JComponent component : List.of(databaseCombo, messageRadio, severityRadio, messageSpinner, severityCombo)) {
                component.setEnabled(false);
            }
        }

        // Response
        for (String job : lookups.jobs()) {
            jobCombo.addItem(job);
        }
        if (values.getJobName() != null) {
            if (!lookups.jobs().contains(values.getJobName())) {
                jobCombo.addItem(values.getJobName());
            }
            jobCombo.setSelectedItem(values.getJobName());
            jobCheck.setSelected(true);
        }
        jobCheck.setEnabled(jobCombo.getItemCount() > 0);
        jobCheck.addActionListener(e -> updateControls());
        for (String operator : lookups.operators()) {
            operatorModel.addRow(new Object[] {operator, values.getEmailOperators().contains(operator)});
        }
        for (String operator : values.getEmailOperators()) {
            if (!lookups.operators().contains(operator)) {
                operatorModel.addRow(new Object[] {operator, true});
            }
        }
        notifyCheck.setSelected(!values.getEmailOperators().isEmpty());
        notifyCheck.addActionListener(e -> updateControls());
        JTable operatorTable = new JTable(operatorModel);
        operatorTable.setFillsViewportHeight(true);
        operatorTable.setShowGrid(false);
        operatorTable.getColumnModel().getColumn(1).setMaxWidth(120);
        JScrollPane operatorScroll = new JScrollPane(operatorTable);
        operatorScroll.setPreferredSize(new Dimension(300, 160));

        DatabaseTaskDialog.FormPanel response = new DatabaseTaskDialog.FormPanel();
        response.addFull(jobCheck);
        response.addRow(AgentMessages.agent_alert_job, jobCombo);
        response.addFull(notifyCheck);
        if (lookups.operators().isEmpty() && values.getEmailOperators().isEmpty()) {
            notifyCheck.setEnabled(false);
            response.addFull(FormPanel.note(AgentMessages.agent_alert_no_operators));
        }
        response.addFiller(operatorScroll);

        // Options
        includeTextCheck.setSelected(values.isIncludeEventDescriptionInEmail());
        delayMinutes.setValue(values.getDelayBetweenResponses() / 60);
        delaySeconds.setValue(values.getDelayBetweenResponses() % 60);
        DatabaseTaskDialog.FormPanel options = new DatabaseTaskDialog.FormPanel();
        options.addSection(AgentMessages.agent_alert_section_include);
        options.addFull(includeTextCheck);
        options.addSection(AgentMessages.agent_alert_section_delay);
        options.addRow(AgentMessages.agent_alert_delay_minutes, delayMinutes);
        options.addRow(AgentMessages.agent_alert_delay_seconds, delaySeconds);
        options.finish();

        updateControls();
        initDialog(List.of(
            new Page(AgentMessages.agent_page_general, general),
            new Page(AgentMessages.agent_page_response, response),
            new Page(AgentMessages.agent_page_options, options)));
    }

    /**
     * Reads the alert's notifications and the dialog values, then opens the dialog.
     */
    static void open(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentAlert alert,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        Object[] loaded;
        try {
            loaded = ProgressDialog.runTask(AgentMessages.agent_progress_read, monitor -> new Object[] {
                SQLServerAgentManager.readLookups(monitor, agent),
                alert == null ? null : SQLServerAgentAlertDefinition.load(monitor, alert)
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(alert == null ? AgentMessages.agent_alert_new_title : alert.getName(),
                NLS.bind(AgentMessages.agent_error_read, alert == null ? AgentMessages.agent_alert_new_title : alert.getName()), e);
            return;
        }
        new AgentAlertDialog(owner, agent, alert, (SQLServerAgentAlertDefinition) loaded[1], (SQLServerAgentManager.Lookups) loaded[0],
            agentNode, queryWindows).open();
    }

    /**
     * Puts a radio button where a form row has its label.
     */
    private static void replaceLabel(@NotNull JPanel form, @NotNull JRadioButton radio, @NotNull JComponent control) {
        for (Component component : form.getComponents()) {
            if (component instanceof JLabel label && label.getLabelFor() == control) {
                GridBagLayout layout = (GridBagLayout) form.getLayout();
                GridBagConstraints constraints = layout.getConstraints(label);
                form.remove(label);
                form.add(radio, constraints);
                return;
            }
        }
    }

    private void updateControls() {
        boolean eventEnabled = databaseCombo.isEnabled();
        messageSpinner.setEnabled(eventEnabled && messageRadio.isSelected());
        severityCombo.setEnabled(eventEnabled && severityRadio.isSelected());
        jobCombo.setEnabled(jobCheck.isSelected());
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            return AgentMessages.agent_error_no_name;
        }
        if (databaseCombo.isEnabled() && messageRadio.isSelected() && ((Number) messageSpinner.getValue()).intValue() <= 0) {
            return AgentMessages.agent_alert_error_no_number;
        }
        return null;
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        SQLServerAgentAlertDefinition edited = original != null ? original.copy() : SQLServerAgentAlertDefinition.newAlert();
        edited.setName(nameField.getText().trim());
        edited.setEnabled(enabledCheck.isSelected());
        if (databaseCombo.isEnabled()) {
            edited.setDatabaseName(getChoice(databaseCombo));
            if (messageRadio.isSelected()) {
                edited.setMessageId(((Number) messageSpinner.getValue()).intValue());
            } else {
                edited.setSeverity(severityCombo.getSelectedIndex() + 1);
            }
        }
        edited.setJobName(jobCheck.isSelected() ? (String) jobCombo.getSelectedItem() : null);
        edited.getEmailOperators().clear();
        if (notifyCheck.isSelected()) {
            for (int row = 0; row < operatorModel.getRowCount(); row++) {
                if (Boolean.TRUE.equals(operatorModel.getValueAt(row, 1))) {
                    edited.getEmailOperators().add((String) operatorModel.getValueAt(row, 0));
                }
            }
        }
        edited.setIncludeEventDescriptionInEmail(includeTextCheck.isSelected());
        edited.setDelayBetweenResponses(((Number) delayMinutes.getValue()).intValue() * 60 + ((Number) delaySeconds.getValue()).intValue());
        return edited.generateStatements(original);
    }
}
