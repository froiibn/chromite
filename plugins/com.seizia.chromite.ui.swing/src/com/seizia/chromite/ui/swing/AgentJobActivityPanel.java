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
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgent;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJob;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Job Activity Monitor document tab: every SQL Server Agent job of a server with its state, last and next run, and the
 * start, stop, enable, disable, history and properties commands.
 *
 * 설계: SSMS의 작업 활동 모니터(따로 여는 창)를 이 앱의 Activity Monitor처럼 문서 탭으로 연다. 표는 모델 객체의 {@code @Property} 열을
 * 보여 주는 {@link ObjectListTable}(세션 표와 같은 부품)이고, 값은 {@code sp_help_job}로 매번 새로 읽는다. 서버 연결은 Activity Monitor와
 * 같은 {@link AdminConnection}(창 하나에 격리된 연결 하나)이고 자동 새로 고침 간격도 같은 콤보다. 명령은 탐색기와 같은 경로
 * ({@link AgentCommands})를 써서 결과 알림과 탐색기 갱신이 같다. 읽을 때마다 에이전트 서비스 상태도 다시 읽어 시작·중지 단추를 맞춘다.
 * 에이전트 객체는 읽을 때마다 연결의 현재 데이터 원본에서 다시 얻는다: 다시 연결하면 데이터 원본(과 에이전트 노드)이 새로 만들어지는데,
 * 탭은 연결마다 하나라 열린 채로 남기 때문이다(옛 객체로 상태를 읽으면 닫힌 연결이라 "알 수 없음"이 된다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 * - 2026-11-12 아이콘 단추의 도구 설명을 설명문 대신 기능 이름(동작·메뉴의 이름)과 단축키로 (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class AgentJobActivityPanel extends JPanel implements ToolDocument {

    private static final String PREF_REFRESH = "agent.activity.refreshSeconds"; //$NON-NLS-1$
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private final DBPDataSourceContainer container;
    /**
     * The agent of the connection's current data source (a reconnect makes a new one; see {@link #refresh}).
     */
    private volatile SQLServerAgent agent;
    private final AdminConnection connection;
    private final ObjectListTable<SQLServerAgentJob> table;
    private final AutoRefreshBox autoRefresh;
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JTextField searchField = new JTextField(16);
    private final Action startAction;
    private final Action stopAction;
    private final Action enableAction;
    private final Action disableAction;
    private final Action historyAction;
    private final Action propertiesAction;
    private boolean loading;

    AgentJobActivityPanel(@NotNull SQLServerAgent agent, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        super(new BorderLayout());
        this.agent = agent;
        container = agent.getDataSource().getContainer();
        connection = new AdminConnection(container);
        table = new ObjectListTable<>(SQLServerAgentJob::getJobId, container);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateActions();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedObject() != null) {
                    propertiesAction.actionPerformed(null);
                }
            }
        });

        Action refreshAction = createAction(AgentMessages.agent_refresh, this::refresh);
        startAction = createAction(AgentMessages.agent_start_job, () -> runOnSelected(job -> AgentCommands.startJob(this, job, getAgentNode())));
        stopAction = createAction(AgentMessages.agent_stop_job, () -> runOnSelected(job -> AgentCommands.stopJob(this, job, getAgentNode())));
        enableAction = createAction(AgentMessages.agent_enable, () -> runOnSelected(job -> AgentCommands.setJobEnabled(this, job, true, getAgentNode())));
        disableAction = createAction(AgentMessages.agent_disable, () -> runOnSelected(job -> AgentCommands.setJobEnabled(this, job, false, getAgentNode())));
        historyAction = createAction(AgentMessages.agent_view_history, () -> {
            SQLServerAgentJob job = table.getSelectedObject();
            if (job != null) {
                AgentCommands.openHistory(queryWindows, agent, job);
            }
        });
        propertiesAction = createAction(AgentMessages.agent_properties, () -> {
            SQLServerAgentJob job = table.getSelectedObject();
            if (job != null) {
                AgentJobDialog.open(this, agent, job, getAgentNode(), queryWindows);
                refresh();
            }
        });

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        autoRefresh = new AutoRefreshBox(PREF_REFRESH, this, () -> {
            if (!loading && connection.getContainer().isConnected()) {
                refresh();
            }
        });
        autoRefresh.setMaximumSize(autoRefresh.getPreferredSize());
        toolBar.add(autoRefresh);
        toolBar.addSeparator();
        toolBar.add(UiIcons.createToolButton(startAction, "run")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(stopAction, "stop")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createToolButton(enableAction, "check")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(disableAction, "close")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createToolButton(historyAction, "history")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(propertiesAction, "properties")); //$NON-NLS-1$
        toolBar.add(Box.createHorizontalGlue());
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AgentMessages.agent_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setMaximumSize(searchField.getPreferredSize());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }
        });
        toolBar.add(searchField);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        add(toolBar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        updateActions();
        refresh();
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable task) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
    }

    /**
     * A command on the selected job; the grid is read again when it did something.
     */
    private interface JobCommand {
        boolean run(@NotNull SQLServerAgentJob job);
    }

    private void runOnSelected(@NotNull JobCommand command) {
        SQLServerAgentJob job = table.getSelectedObject();
        if (job != null && command.run(job)) {
            refresh();
        }
    }

    /**
     * Reads the jobs and the agent state again.
     */
    void refresh() {
        loading = true;
        statusLabel.setText(connection.getContainer().isConnected() ? AgentMessages.agent_reading : AgentMessages.agent_connecting);
        connection.run("Read SQL Server Agent jobs", (monitor, session, profile) -> { //$NON-NLS-1$
            SQLServerAgent current = getCurrentAgent(monitor);
            List<SQLServerAgentJob> jobs = SQLServerAgentManager.readJobs((JDBCSession) session, current);
            current.readState(monitor);
            agent = current;
            return jobs;
        }, this::showJobs, error -> {
            loading = false;
            statusLabel.setText(ErrorText.describeLine(error));
            statusLabel.setToolTipText(ErrorText.describe(error));
            updateActions();
        });
    }

    /**
     * The agent of the data source the connection has now: after a reconnect the tab keeps working with the new
     * data source (the agent it was opened with belongs to the closed one).
     */
    @NotNull
    private SQLServerAgent getCurrentAgent(@NotNull DBRProgressMonitor monitor) {
        if (container.getDataSource() instanceof SQLServerDataSource dataSource) {
            List<SQLServerAgent> agents = dataSource.getAgent(monitor);
            if (!agents.isEmpty()) {
                return agents.get(0);
            }
        }
        return agent;
    }

    /**
     * The explorer node of the agent (read again after the commands), or null if the tree has not read it.
     */
    @Nullable
    private DBNDatabaseNode getAgentNode() {
        return DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(agent);
    }

    private void showJobs(@NotNull List<SQLServerAgentJob> jobs) {
        loading = false;
        table.setObjects(jobs, SQLServerAgentJob.class);
        String time = TIME_FORMAT.format(LocalTime.now());
        String state = agent.getServiceStateName();
        statusLabel.setText(NLS.bind(AgentMessages.agent_activity_status, new Object[] {jobs.size(), state, time}));
        statusLabel.setToolTipText(null);
        updateActions();
    }

    private void updateActions() {
        SQLServerAgentJob job = table.getSelectedObject();
        boolean supported = agent.isSupported();
        boolean running = agent.canRunJobs();
        String reason = !supported ? AgentMessages.agent_tip_not_supported : !running ? AgentMessages.agent_tip_not_running : null;
        setState(startAction, job != null && supported && running && job.hasSteps(),
            reason != null ? reason : job != null && !job.hasSteps() ? AgentMessages.agent_tip_no_steps : AgentMessages.agent_start_job_tip);
        setState(stopAction, job != null && supported && running, reason != null ? reason : AgentMessages.agent_stop_job_tip);
        setState(enableAction, job != null && supported && !job.isEnabled(), supported ? null : AgentMessages.agent_tip_not_supported);
        setState(disableAction, job != null && supported && job.isEnabled(), supported ? null : AgentMessages.agent_tip_not_supported);
        historyAction.setEnabled(job != null);
        propertiesAction.setEnabled(job != null && supported);
    }

    private static void setState(@NotNull Action action, boolean enabled, @Nullable String toolTip) {
        action.setEnabled(enabled);
        action.putValue(Action.SHORT_DESCRIPTION, toolTip);
    }

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(AgentMessages.agent_activity_title, container.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return getTitle();
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("activity-monitor"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        autoRefresh.dispose();
        connection.close();
    }
}
