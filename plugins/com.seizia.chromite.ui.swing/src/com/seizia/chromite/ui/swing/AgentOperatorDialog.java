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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentOperator;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentOperatorDefinition;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Operator properties / New Operator (SQL Server Agent): name, enabled, e-mail name.
 *
 * 설계: SSMS 새 운영자 창의 일반 페이지에서 쓰이는 칸(이름, 사용, 전자 메일 이름)만 둔다. 호출기와 net send는 SQL Server가 더 이상
 * 쓰지 않는 기능이라 넣지 않았다. 문은 모델({@link SQLServerAgentOperatorDefinition})이 만든다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentOperatorDialog extends AgentObjectDialog {

    @Nullable
    private final SQLServerAgentOperatorDefinition original;
    private final JTextField nameField = new JTextField();
    private final JCheckBox enabledCheck = new JCheckBox(AgentMessages.agent_enabled);
    private final JTextField emailField = new JTextField();

    private AgentOperatorDialog(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentOperator operator,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, operator == null ? AgentMessages.agent_operator_new_title : NLS.bind(AgentMessages.agent_operator_title, operator.getName()),
            agent, agentNode, queryWindows);
        original = operator == null ? null : SQLServerAgentOperatorDefinition.of(operator);
        SQLServerAgentOperatorDefinition values = original != null ? original : SQLServerAgentOperatorDefinition.newOperator();
        nameField.setText(values.getName());
        enabledCheck.setSelected(values.isEnabled());
        emailField.setText(CommonUtils.notEmpty(values.getEmailAddress()));

        DatabaseTaskDialog.FormPanel general = new DatabaseTaskDialog.FormPanel();
        general.addRow(AgentMessages.agent_name, nameField);
        general.addFull(enabledCheck);
        general.addSection(AgentMessages.agent_operator_section_notification);
        general.addRow(AgentMessages.agent_operator_email, emailField);
        general.finish();
        initDialog(List.of(new Page(AgentMessages.agent_page_general, general)));
        // Few fields: the smallest size of the task dialogs
        setSize(getMinimumSize());
        setLocationRelativeTo(getOwner());
    }

    static void open(
        @NotNull Component owner,
        @NotNull SQLServerAgent agent,
        @Nullable SQLServerAgentOperator operator,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        new AgentOperatorDialog(owner, agent, operator, agentNode, queryWindows).open();
    }

    @Nullable
    @Override
    protected String validateInput() {
        return CommonUtils.isEmptyTrimmed(nameField.getText()) ? AgentMessages.agent_error_no_name : null;
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        SQLServerAgentOperatorDefinition edited = original != null ? original.copy() : SQLServerAgentOperatorDefinition.newOperator();
        edited.setName(nameField.getText().trim());
        edited.setEnabled(enabledCheck.isSelected());
        edited.setEmailAddress(CommonUtils.nullIfEmpty(emailField.getText().trim()));
        return edited.generateStatements(original);
    }
}
