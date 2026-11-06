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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentException;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Common part of the SQL Server Agent property dialogs (job, alert, operator): OK shows the script, then runs it.
 *
 * 설계: SSMS의 작업·경고·운영자 속성 창은 데이터베이스 작업 창과 같은 틀(페이지 선택, 연결, 진행률, 스크립트 단추)이라
 * {@link DatabaseTaskDialog}를 그대로 쓴다. 바꾸는 방식은 보안 편집기와 같다: 확인을 누르면 실행할 스크립트를 먼저 보여 주고
 * ({@link ScriptPreviewDialog}: 실행 / 쿼리 창으로 열기 / 복사 / 취소) 실행을 고르면 모델이 한 트랜잭션으로 실행한다. 스크립트 단추는
 * 실행 없이 같은 스크립트를 새 쿼리 창이나 클립보드에 낸다. 성공하면 SSMS처럼 창을 닫고 SQL Server 에이전트 노드를 다시 읽는다.
 * 실패하면 메시지 페이지에 오류와 실패한 문을 남기고 창은 열어 둔다(고쳐서 다시 실행).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
abstract class AgentObjectDialog extends DatabaseTaskDialog {

    protected final SQLServerAgent agent;
    private final DBNDatabaseNode agentNode;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    @Nullable
    private List<String> statements;

    AgentObjectDialog(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull SQLServerAgent agent,
        @Nullable DBNDatabaseNode agentNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, title, agent.getDataSource().getContainer());
        this.agent = agent;
        this.agentNode = agentNode;
        this.queryWindows = queryWindows;
    }

    /**
     * The statements that make the change from the current input (empty when nothing changed).
     */
    @NotNull
    protected abstract List<String> buildStatements();

    @Override
    protected boolean confirmRun() {
        List<String> built = buildStatements();
        if (built.isEmpty()) {
            // Nothing changed: OK just closes, as in the property dialogs this one follows
            dispose();
            return false;
        }
        if (!AgentCommands.checkEditAllowed(this, agent, getTitle())) {
            return false;
        }
        String script = SQLServerAgentManager.formatScript(built);
        ScriptPreviewDialog.Result choice = ScriptPreviewDialog.show(this, NLS.bind(AgentMessages.agent_script_title, getTitle()), script, true);
        if (choice == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(agent.getDataSource().getContainer(), script, 0, false);
            dispose();
            return false;
        }
        if (choice != ScriptPreviewDialog.Result.EXECUTE) {
            return false;
        }
        statements = built;
        return true;
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        List<String> run = statements == null ? buildStatements() : statements;
        return monitor -> SQLServerAgentManager.execute(monitor, agent.getDataSource(), run, this::appendMessage);
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return AgentMessages.agent_success;
    }

    @Override
    protected void runSucceeded() {
        AgentCommands.refreshAgent(agentNode);
        dispose();
    }

    @NotNull
    @Override
    protected String getErrorText(@NotNull Throwable error) {
        String text = super.getErrorText(error);
        if (error instanceof SQLServerAgentException agentError && agentError.getStatement() != null) {
            return text + "\n\n" + NLS.bind(AgentMessages.agent_failed_statement, agentError.getStatement()); //$NON-NLS-1$
        }
        return text;
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        return createSqlScriptActions(queryWindows, () -> {
            List<String> built = buildStatements();
            return built.isEmpty() ? "-- " + AgentMessages.agent_no_changes + "\n" : SQLServerAgentManager.formatScript(built); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    /**
     * Combo items with a "none" entry first (its value is null).
     */
    @NotNull
    static JComboBox<String> createChoice(@NotNull String noneLabel, @NotNull List<String> values, @Nullable String selected) {
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem(noneLabel);
        for (String value : values) {
            combo.addItem(value);
        }
        if (selected != null) {
            if (!values.contains(selected)) {
                combo.addItem(selected);
            }
            combo.setSelectedItem(selected);
        } else {
            combo.setSelectedIndex(0);
        }
        return combo;
    }

    /**
     * Value of a {@link #createChoice} combo: null for the "none" entry.
     */
    @Nullable
    static String getChoice(@NotNull JComboBox<String> combo) {
        return combo.getSelectedIndex() <= 0 ? null : (String) combo.getSelectedItem();
    }
}
