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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobStep;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * New Job Step / Job Step Properties: the step's name, type, database and command (General) and what happens after it
 * (Advanced: on success, on failure, retries).
 *
 * 설계: SSMS 작업 단계 창의 두 페이지(일반, 고급)를 탭으로 둔다. 명령 칸은 SQL 편집기와 같은 글 상자라 T-SQL 강조가 된다. 형식은
 * T-SQL, 운영 체제(CmdExec), PowerShell을 고를 수 있고 데이터베이스는 T-SQL에서만 쓴다. 다른 형식(SSIS 등)의 기존 단계는 형식을 그대로 둔다.
 * "단계로 이동"은 단계 번호(목록의 위치)이므로 작업 창이 단계를 지우거나 옮기면 그 창이 번호를 고친다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentJobStepDialog extends JDialog {

    private static final String SUBSYSTEM_CMDEXEC = "CmdExec"; //$NON-NLS-1$
    private static final String SUBSYSTEM_POWERSHELL = "PowerShell"; //$NON-NLS-1$

    /**
     * One "after the step" choice: an action and, for "go to step", its step id.
     */
    private record ActionChoice(int action, int stepId, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One step type.
     */
    private record SubsystemChoice(@NotNull String subsystem, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final SQLServerAgentJobStep step;
    private boolean accepted;
    private final JTextField nameField = new JTextField();
    private final JComboBox<SubsystemChoice> typeCombo = new JComboBox<>();
    private final JComboBox<String> databaseCombo = new JComboBox<>();
    private final SqlEditorPanel.SqlTextArea commandText = new SqlEditorPanel.SqlTextArea();
    private final JComboBox<ActionChoice> successCombo = new JComboBox<>();
    private final JComboBox<ActionChoice> failureCombo = new JComboBox<>();
    private final JSpinner retryAttempts = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JSpinner retryInterval = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JTabbedPane tabs = new JTabbedPane();

    /**
     * @param stepId    1-based position of the step in the job
     * @param allSteps  the job's steps (for "go to step"), with this one at {@code stepId - 1}
     * @param databases databases of the server
     */
    private AgentJobStepDialog(
        @NotNull Component owner,
        @NotNull SQLServerAgentJobStep step,
        int stepId,
        @NotNull List<SQLServerAgentJobStep> allSteps,
        @NotNull List<String> databases,
        boolean isNew
    ) {
        super(SwingUtilities.getWindowAncestor(owner),
            isNew ? AgentMessages.agent_step_new_title : NLS.bind(AgentMessages.agent_step_title, step.getName()), ModalityType.APPLICATION_MODAL);
        this.step = step;
        setIconImages(ChromiteIcons.getWindowIcons());

        // General
        nameField.setText(step.getName());
        List<SubsystemChoice> types = new ArrayList<>(List.of(
            new SubsystemChoice(SQLServerAgentJobStep.SUBSYSTEM_TSQL, AgentMessages.agent_step_type_tsql),
            new SubsystemChoice(SUBSYSTEM_CMDEXEC, AgentMessages.agent_step_type_cmdexec),
            new SubsystemChoice(SUBSYSTEM_POWERSHELL, AgentMessages.agent_step_type_powershell)));
        SubsystemChoice current = null;
        for (SubsystemChoice type : types) {
            if (type.subsystem().equalsIgnoreCase(step.getSubsystem())) {
                current = type;
            }
        }
        if (current == null) {
            current = new SubsystemChoice(step.getSubsystem(), step.getSubsystem());
            types.add(current);
        }
        for (SubsystemChoice type : types) {
            typeCombo.addItem(type);
        }
        typeCombo.setSelectedItem(current);
        for (String database : databases) {
            databaseCombo.addItem(database);
        }
        String database = CommonUtils.isEmpty(step.getDatabaseName()) ? "master" : step.getDatabaseName(); //$NON-NLS-1$
        if (!databases.contains(database)) {
            databaseCombo.addItem(database);
        }
        databaseCombo.setSelectedItem(database);
        typeCombo.addActionListener(e -> databaseCombo.setEnabled(isTransactSql()));
        databaseCombo.setEnabled(isTransactSql());
        commandText.setText(step.getCommand());
        commandText.setCaretPosition(0);
        commandText.discardAllEdits();
        RTextScrollPane commandScroll = new RTextScrollPane(commandText, true);
        commandText.applyTheme();

        JPanel general = new JPanel(new GridBagLayout());
        general.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 8);
        addRow(general, c, AgentMessages.agent_step_name, nameField);
        addRow(general, c, AgentMessages.agent_step_type, typeCombo);
        addRow(general, c, AgentMessages.agent_step_database, databaseCombo);
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        general.add(new JLabel(AgentMessages.agent_step_command), c);
        c.gridy++;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        general.add(commandScroll, c);

        // Advanced
        List<ActionChoice> successChoices = createActionChoices(stepId, allSteps);
        List<ActionChoice> failureChoices = createActionChoices(stepId, allSteps);
        for (ActionChoice choice : successChoices) {
            successCombo.addItem(choice);
        }
        for (ActionChoice choice : failureChoices) {
            failureCombo.addItem(choice);
        }
        select(successCombo, step.getOnSuccessAction(), step.getOnSuccessStepId());
        select(failureCombo, step.getOnFailAction(), step.getOnFailStepId());
        retryAttempts.setValue(step.getRetryAttempts());
        retryInterval.setValue(step.getRetryInterval());
        JPanel advanced = new JPanel(new GridBagLayout());
        advanced.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints a = new GridBagConstraints();
        a.anchor = GridBagConstraints.WEST;
        a.insets = new Insets(0, 0, 8, 8);
        addRow(advanced, a, AgentMessages.agent_step_on_success, successCombo);
        addRow(advanced, a, AgentMessages.agent_step_retry_attempts, retryAttempts);
        addRow(advanced, a, AgentMessages.agent_step_retry_interval, retryInterval);
        addRow(advanced, a, AgentMessages.agent_step_on_failure, failureCombo);
        a.gridx = 0;
        a.weighty = 1;
        advanced.add(Box.createGlue(), a);

        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        tabs.addTab(AgentMessages.agent_page_general, general);
        tabs.addTab(AgentMessages.agent_page_advanced, advanced);

        JButton okButton = new JButton(AgentMessages.agent_ok);
        okButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(AgentMessages.agent_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(760, 560);
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(getOwner());
    }

    /**
     * Edits a step in place.
     *
     * @return true if the user accepted the changes
     */
    static boolean edit(
        @NotNull Component owner,
        @NotNull SQLServerAgentJobStep step,
        int stepId,
        @NotNull List<SQLServerAgentJobStep> allSteps,
        @NotNull List<String> databases,
        boolean isNew
    ) {
        AgentJobStepDialog dialog = new AgentJobStepDialog(owner, step, stepId, allSteps, databases, isNew);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    /**
     * Text of an "after the step" action for the steps list of the job dialog.
     */
    @NotNull
    static String describeAction(int action, int stepId) {
        return switch (action) {
            case SQLServerAgentJobStep.ACTION_QUIT_SUCCESS -> AgentMessages.agent_step_action_quit_success;
            case SQLServerAgentJobStep.ACTION_QUIT_FAILURE -> AgentMessages.agent_step_action_quit_failure;
            case SQLServerAgentJobStep.ACTION_GO_TO_STEP -> NLS.bind(AgentMessages.agent_step_action_goto_short, stepId);
            default -> AgentMessages.agent_step_action_next;
        };
    }

    /**
     * Label of a step type for the steps list of the job dialog.
     */
    @NotNull
    static String describeSubsystem(@NotNull String subsystem) {
        if (SQLServerAgentJobStep.SUBSYSTEM_TSQL.equalsIgnoreCase(subsystem)) {
            return AgentMessages.agent_step_type_tsql;
        } else if (SUBSYSTEM_CMDEXEC.equalsIgnoreCase(subsystem)) {
            return AgentMessages.agent_step_type_cmdexec;
        } else if (SUBSYSTEM_POWERSHELL.equalsIgnoreCase(subsystem)) {
            return AgentMessages.agent_step_type_powershell;
        }
        return subsystem;
    }

    @NotNull
    private static List<ActionChoice> createActionChoices(int stepId, @NotNull List<SQLServerAgentJobStep> allSteps) {
        List<ActionChoice> choices = new ArrayList<>();
        choices.add(new ActionChoice(SQLServerAgentJobStep.ACTION_NEXT_STEP, 0, AgentMessages.agent_step_action_next));
        choices.add(new ActionChoice(SQLServerAgentJobStep.ACTION_QUIT_SUCCESS, 0, AgentMessages.agent_step_action_quit_success));
        choices.add(new ActionChoice(SQLServerAgentJobStep.ACTION_QUIT_FAILURE, 0, AgentMessages.agent_step_action_quit_failure));
        for (int i = 0; i < allSteps.size(); i++) {
            if (i + 1 != stepId) {
                choices.add(new ActionChoice(SQLServerAgentJobStep.ACTION_GO_TO_STEP, i + 1,
                    NLS.bind(AgentMessages.agent_step_action_goto, i + 1, allSteps.get(i).getName())));
            }
        }
        return choices;
    }

    private static void select(@NotNull JComboBox<ActionChoice> combo, int action, int stepId) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            ActionChoice choice = combo.getItemAt(i);
            if (choice.action() == action && (action != SQLServerAgentJobStep.ACTION_GO_TO_STEP || choice.stepId() == stepId)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private static void addRow(@NotNull JPanel panel, @NotNull GridBagConstraints c, @NotNull String label, @NotNull JComponent control) {
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(control);
        panel.add(labelComponent, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(control, c);
        c.gridy++;
    }

    private boolean isTransactSql() {
        return typeCombo.getSelectedItem() instanceof SubsystemChoice choice
            && SQLServerAgentJobStep.SUBSYSTEM_TSQL.equalsIgnoreCase(choice.subsystem());
    }

    private void accept() {
        String problem = null;
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            problem = AgentMessages.agent_step_error_no_name;
        } else if (CommonUtils.isEmptyTrimmed(commandText.getText())) {
            problem = AgentMessages.agent_step_error_no_command;
        }
        if (problem != null) {
            tabs.setSelectedIndex(0);
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        step.setName(nameField.getText().trim());
        if (typeCombo.getSelectedItem() instanceof SubsystemChoice choice) {
            step.setSubsystem(choice.subsystem());
        }
        step.setDatabaseName(isTransactSql() ? (String) databaseCombo.getSelectedItem() : null);
        step.setCommand(commandText.getText());
        ActionChoice success = (ActionChoice) successCombo.getSelectedItem();
        ActionChoice failure = (ActionChoice) failureCombo.getSelectedItem();
        if (success != null) {
            step.setOnSuccessAction(success.action());
            step.setOnSuccessStepId(success.stepId());
        }
        if (failure != null) {
            step.setOnFailAction(failure.action());
            step.setOnFailStepId(failure.stepId());
        }
        step.setRetryAttempts(((Number) retryAttempts.getValue()).intValue());
        step.setRetryInterval(((Number) retryInterval.getValue()).intValue());
        accepted = true;
        dispose();
    }
}
