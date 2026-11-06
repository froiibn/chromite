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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentActivityMonitor;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentAlert;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentAlertDefinition;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentErrorLog;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJob;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobDefinition;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobStep;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentOperator;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentOperatorDefinition;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Object Explorer commands of SQL Server Agent: New Job/Alert/Operator…, Start Job at Step…, Stop Job, Enable,
 * Disable, View History, View Job Activity…, View Agent Log, and the agent objects' Properties and Delete.
 *
 * 설계: 보안 편집기({@link SecurityCommands})처럼 탐색기({@link ObjectExplorerPanel})에는 메뉴를 붙이는 줄과 Delete·속성·두 번 클릭의
 * 갈래만 두고, 판단과 실행은 이 클래스가 한다(여러 작업이 함께 고치는 탐색기 파일의 변경을 줄이기 위해). 메뉴 항목은 고른 노드에 맞는 것만
 * 보인다(에이전트 항목이 10개라, 다른 노드에서 꺼진 채로 모두 보이면 메뉴가 너무 길어진다).
 * <ul>
 *     <li>모든 DB 호출은 진행 대화상자({@link ProgressDialog})의 작업 스레드나 Job에서 한다. 바꾸는 문은 모델
 *     ({@link SQLServerAgentManager#execute})이 격리된 연결에서 한 트랜잭션으로 실행한다.</li>
 *     <li>작업 시작·중지·사용·사용 안 함은 SSMS처럼 바로 실행하고 알림으로 결과를 알린다. 속성 창(작업·경고·운영자)과 삭제는 보안 편집기처럼
 *     실행할 스크립트를 먼저 보여 준다.</li>
 *     <li>에이전트가 멈춰 있으면 시작·중지를, 에디션에 에이전트가 없으면(Express) 바꾸는 명령을 모두 끄고 도구 설명으로 이유를 알린다.</li>
 *     <li>실행 뒤에는 SQL Server 에이전트 노드를 모델에서 다시 읽는다({@link #refreshAgent}). 다른 곳에서 새로 읽은 노드 이벤트이므로
 *     Object Explorer는 펼친 폴더를 그대로 두고 자식만 다시 그린다(F5 경로는 폴더를 접는다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentCommands {

    private static final Log log = Log.getLog(AgentCommands.class);

    private final Supplier<DBNNode> selection;
    private final Component owner;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final List<JMenuItem> items = new ArrayList<>();
    private final JMenuItem newJobItem;
    private final JMenuItem newAlertItem;
    private final JMenuItem newOperatorItem;
    private final JMenuItem startItem;
    private final JMenuItem stopItem;
    private final JMenuItem enableItem;
    private final JMenuItem disableItem;
    private final JMenuItem historyItem;
    private final JMenuItem activityItem;
    private final JMenuItem logItem;

    AgentCommands(@NotNull Supplier<DBNNode> selection, @NotNull Component owner, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        this.selection = selection;
        this.owner = owner;
        this.queryWindows = queryWindows;
        newJobItem = createItem(AgentMessages.agent_new_job, () -> newJob(selection.get()));
        newAlertItem = createItem(AgentMessages.agent_new_alert, () -> newAlert(selection.get()));
        newOperatorItem = createItem(AgentMessages.agent_new_operator, () -> newOperator(selection.get()));
        startItem = createItem(AgentMessages.agent_start_job, () -> {
            if (getObject(selection.get()) instanceof SQLServerAgentJob job) {
                startJob(owner, job, findAgentNode(selection.get()));
            }
        });
        stopItem = createItem(AgentMessages.agent_stop_job, () -> {
            if (getObject(selection.get()) instanceof SQLServerAgentJob job) {
                stopJob(owner, job, findAgentNode(selection.get()));
            }
        });
        enableItem = createItem(AgentMessages.agent_enable, () -> setEnabled(selection.get(), true));
        disableItem = createItem(AgentMessages.agent_disable, () -> setEnabled(selection.get(), false));
        historyItem = createItem(AgentMessages.agent_view_history, () -> viewHistory(selection.get()));
        activityItem = createItem(AgentMessages.agent_view_activity, () -> viewActivity(selection.get()));
        logItem = createItem(AgentMessages.agent_view_log, () -> viewLog(selection.get()));
    }

    @NotNull
    private JMenuItem createItem(@NotNull String text, @NotNull Runnable task) {
        JMenuItem item = new JMenuItem(new AbstractAction(text) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        });
        items.add(item);
        return item;
    }

    /**
     * Adds the agent items to the explorer's context menu (they are shown only on agent nodes, see {@link #update}).
     */
    void addTo(@NotNull JPopupMenu menu) {
        for (JMenuItem item : items) {
            menu.add(item);
        }
    }

    /**
     * Shows the items that apply to the selected node and enables them by the agent state.
     */
    void update(@Nullable DBNNode node) {
        for (JMenuItem item : items) {
            item.setVisible(false);
            item.setEnabled(true);
            item.setToolTipText(null);
        }
        SQLServerAgent agent = getAgent(node);
        if (agent == null) {
            return;
        }
        DBSObject object = getObject(node);
        Class<?> folderClass = node instanceof DBNDatabaseFolder folder ? folder.getChildrenClass() : null;
        boolean agentNode = object instanceof SQLServerAgent;
        boolean jobs = folderClass == SQLServerAgentJob.class;
        boolean alerts = folderClass == SQLServerAgentAlert.class;
        boolean operators = folderClass == SQLServerAgentOperator.class;
        boolean logs = folderClass == SQLServerAgentErrorLog.class;
        SQLServerAgentJob job = object instanceof SQLServerAgentJob agentJob ? agentJob : null;
        SQLServerAgentAlert alert = object instanceof SQLServerAgentAlert agentAlert ? agentAlert : null;

        newJobItem.setVisible(agentNode || jobs || job != null);
        newAlertItem.setVisible(agentNode || alerts || alert != null);
        newOperatorItem.setVisible(agentNode || operators || object instanceof SQLServerAgentOperator);
        startItem.setVisible(job != null);
        stopItem.setVisible(job != null);
        enableItem.setVisible(job != null || alert != null);
        disableItem.setVisible(job != null || alert != null);
        historyItem.setVisible(agentNode || jobs || job != null);
        activityItem.setVisible(agentNode || jobs || object instanceof SQLServerAgentActivityMonitor);
        logItem.setVisible(agentNode || logs || object instanceof SQLServerAgentErrorLog);

        if (job != null) {
            enableItem.setEnabled(!job.isEnabled());
            disableItem.setEnabled(job.isEnabled());
            if (!job.hasSteps()) {
                disable(startItem, AgentMessages.agent_tip_no_steps);
            }
        } else if (alert != null) {
            enableItem.setEnabled(!alert.isEnabled());
            disableItem.setEnabled(alert.isEnabled());
        }
        if (!agent.isSupported()) {
            for (JMenuItem item : List.of(newJobItem, newAlertItem, newOperatorItem, startItem, stopItem, enableItem, disableItem)) {
                disable(item, AgentMessages.agent_tip_not_supported);
            }
        } else if (!agent.canRunJobs()) {
            disable(startItem, AgentMessages.agent_tip_not_running);
            disable(stopItem, AgentMessages.agent_tip_not_running);
        }
    }

    private static void disable(@NotNull JMenuItem item, @NotNull String reason) {
        item.setEnabled(false);
        item.setToolTipText(reason);
    }

    //////////////////////////////////////////////////////////
    // Explorer hooks (Delete, Properties, double click, labels)

    /**
     * True for agent objects Delete applies to (jobs, alerts, operators) on a server that has an agent.
     */
    static boolean canDelete(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        SQLServerAgent agent = getAgent(node);
        return agent != null && agent.isSupported()
            && (object instanceof SQLServerAgentJob || object instanceof SQLServerAgentAlert || object instanceof SQLServerAgentOperator);
    }

    static boolean canOpenProperties(@Nullable DBNNode node) {
        return canDelete(node);
    }

    void delete(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (!canDelete(node) || object == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(owner, NLS.bind(AgentMessages.agent_delete_question, object.getName()),
            AgentMessages.agent_delete_title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        String statement;
        if (object instanceof SQLServerAgentJob job) {
            statement = SQLServerAgentJobDefinition.generateDropStatement(job.getJobId());
        } else if (object instanceof SQLServerAgentAlert alert) {
            statement = SQLServerAgentAlertDefinition.generateDropStatement(alert.getName());
        } else {
            statement = SQLServerAgentOperatorDefinition.generateDropStatement(object.getName());
        }
        String title = NLS.bind(AgentMessages.agent_delete_title_object, object.getName());
        String script = SQLServerAgentManager.formatScript(List.of(statement));
        SQLServerAgent agent = getAgent(node);
        if (agent == null || !checkEditAllowed(owner, agent, title)) {
            return;
        }
        ScriptPreviewDialog.Result choice = ScriptPreviewDialog.show(owner, NLS.bind(AgentMessages.agent_script_title, title), script, true);
        if (choice == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(agent.getDataSource().getContainer(), script, 0, false);
        } else if (choice == ScriptPreviewDialog.Result.EXECUTE
            && run(owner, title, monitor -> SQLServerAgentManager.execute(monitor, agent.getDataSource(), List.of(statement), null))) {
            refreshAgent(findAgentNode(node));
        }
    }

    void openProperties(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        DBNDatabaseNode agentNode = findAgentNode(node);
        if (!canOpenProperties(node) || agentNode == null) {
            return;
        }
        if (object instanceof SQLServerAgentJob job) {
            AgentJobDialog.open(owner, job.getAgent(), job, agentNode, queryWindows);
        } else if (object instanceof SQLServerAgentAlert alert) {
            AgentAlertDialog.open(owner, alert.getAgent(), alert, agentNode, queryWindows);
        } else if (object instanceof SQLServerAgentOperator operator) {
            AgentOperatorDialog.open(owner, operator.getAgent(), operator, agentNode, queryWindows);
        }
    }

    /**
     * Double click: opens the Job Activity Monitor, an agent log, or the Properties of a job, alert or operator.
     *
     * @return true if the node had a default command
     */
    boolean openDefault(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (object instanceof SQLServerAgentActivityMonitor) {
            viewActivity(node);
        } else if (object instanceof SQLServerAgentErrorLog) {
            viewLog(node);
        } else if (canOpenProperties(node)) {
            openProperties(node);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Secondary text of agent nodes in Object Explorer: the service state of the agent, "Disabled" for disabled jobs,
     * alerts and operators; null for other nodes.
     */
    @Nullable
    static String getSecondaryText(@NotNull DBNNode node) {
        DBSObject object = getObject(node);
        if (object instanceof SQLServerAgent agent) {
            return agent.getServiceStateName();
        }
        boolean disabled = object instanceof SQLServerAgentJob job && !job.isEnabled()
            || object instanceof SQLServerAgentAlert alert && !alert.isEnabled()
            || object instanceof SQLServerAgentOperator operator && !operator.isEnabled();
        return disabled ? AgentMessages.agent_disabled_suffix : null;
    }

    /**
     * Icon of agent nodes (the icon pack, with the service state dot on the agent node), or null for other nodes.
     */
    @Nullable
    static Icon getNodeIcon(@NotNull DBNNode node) {
        DBSObject object = getObject(node);
        if (object instanceof SQLServerAgent agent) {
            Icon icon = UiIcons.get("schedule"); //$NON-NLS-1$
            return switch (agent.getServiceState()) {
                case RUNNING -> UiIcons.withStatusDot(icon, UiIcons.Status.CONNECTED);
                case STOPPED, NOT_SUPPORTED -> UiIcons.withStatusDot(icon, UiIcons.Status.ERROR);
                case UNKNOWN -> icon;
            };
        }
        if (object instanceof SQLServerAgentJob) {
            return UiIcons.get("run"); //$NON-NLS-1$
        }
        if (object instanceof SQLServerAgentActivityMonitor) {
            return UiIcons.get("activity-monitor"); //$NON-NLS-1$
        }
        if (object instanceof SQLServerAgentAlert) {
            return UiIcons.get("warning"); //$NON-NLS-1$
        }
        if (object instanceof SQLServerAgentErrorLog) {
            return UiIcons.get("messages"); //$NON-NLS-1$
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Commands

    private void newJob(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        DBNDatabaseNode agentNode = findAgentNode(node);
        if (agent != null && agentNode != null) {
            AgentJobDialog.open(owner, agent, null, agentNode, queryWindows);
        }
    }

    private void newAlert(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        DBNDatabaseNode agentNode = findAgentNode(node);
        if (agent != null && agentNode != null) {
            AgentAlertDialog.open(owner, agent, null, agentNode, queryWindows);
        }
    }

    private void newOperator(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        DBNDatabaseNode agentNode = findAgentNode(node);
        if (agent != null && agentNode != null) {
            AgentOperatorDialog.open(owner, agent, null, agentNode, queryWindows);
        }
    }

    private void setEnabled(@Nullable DBNNode node, boolean enable) {
        DBSObject object = getObject(node);
        if (object instanceof SQLServerAgentJob job) {
            setJobEnabled(owner, job, enable, findAgentNode(node));
        } else if (object instanceof SQLServerAgentAlert alert) {
            String title = enable ? AgentMessages.agent_enable : AgentMessages.agent_disable;
            if (run(owner, title, monitor -> SQLServerAgentManager.execute(monitor, alert.getDataSource(),
                List.of(SQLServerAgentManager.enableAlertStatement(alert, enable)), null))) {
                refreshAgent(findAgentNode(node));
            }
        }
    }

    private void viewHistory(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        if (agent != null) {
            openHistory(queryWindows, agent, getObject(node) instanceof SQLServerAgentJob job ? job : null);
        }
    }

    private void viewActivity(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        if (agent != null) {
            queryWindows.openToolDocument("agent-activity:" + agent.getDataSource().getContainer().getId(), //$NON-NLS-1$
                () -> new AgentJobActivityPanel(agent, queryWindows));
        }
    }

    private void viewLog(@Nullable DBNNode node) {
        SQLServerAgent agent = getAgent(node);
        if (agent != null) {
            int archive = getObject(node) instanceof SQLServerAgentErrorLog errorLog ? errorLog.getArchiveNumber() : 0;
            ToolDocument document = queryWindows.openToolDocument("agent-log:" + agent.getDataSource().getContainer().getId(), //$NON-NLS-1$
                () -> new AgentErrorLogPanel(agent, archive));
            if (document instanceof AgentErrorLogPanel logPanel) {
                // An open log tab shows the file of the node it was opened from now
                logPanel.showArchive(archive);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Shared with the Job Activity Monitor

    static void openHistory(@NotNull ObjectExplorerPanel.QueryWindows queryWindows, @NotNull SQLServerAgent agent, @Nullable SQLServerAgentJob job) {
        String key = "agent-history:" + agent.getDataSource().getContainer().getId() + ":" + (job == null ? "" : job.getJobId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        queryWindows.openToolDocument(key, () -> new AgentJobHistoryPanel(agent, job));
    }

    /**
     * Start Job at Step…: starts a one-step job right away, otherwise asks for the step to start at (the start step first).
     *
     * @return true if the job was started
     */
    static boolean startJob(@NotNull Component owner, @NotNull SQLServerAgentJob job, @Nullable DBNDatabaseNode agentNode) {
        SQLServerAgentJobDefinition definition;
        try {
            definition = ProgressDialog.runTask(AgentMessages.agent_start_job,
                monitor -> SQLServerAgentJobDefinition.load(monitor, job.getAgent(), job.getJobId()));
        } catch (DBInterruptedException e) {
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(AgentMessages.agent_start_job, NLS.bind(AgentMessages.agent_error_read, job.getName()), e);
            return false;
        }
        List<SQLServerAgentJobStep> steps = definition.getSteps();
        if (steps.isEmpty()) {
            JOptionPane.showMessageDialog(owner, AgentMessages.agent_tip_no_steps, AgentMessages.agent_start_job, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String stepName = null;
        if (steps.size() > 1) {
            String[] names = new String[steps.size()];
            for (int i = 0; i < steps.size(); i++) {
                names[i] = NLS.bind(AgentMessages.agent_step_item, i + 1, steps.get(i).getName());
            }
            int start = Math.max(0, Math.min(steps.size() - 1, definition.getStartStepId() - 1));
            Object chosen = JOptionPane.showInputDialog(owner, NLS.bind(AgentMessages.agent_start_job_prompt, job.getName()),
                AgentMessages.agent_start_job_title, JOptionPane.PLAIN_MESSAGE, null, names, names[start]);
            if (chosen == null) {
                return false;
            }
            int index = List.of(names).indexOf(chosen);
            stepName = steps.get(index).getName();
        }
        String statement = SQLServerAgentManager.startJobStatement(job, stepName);
        if (!run(owner, AgentMessages.agent_start_job_title, monitor -> SQLServerAgentManager.execute(monitor, job.getDataSource(), List.of(statement), null))) {
            return false;
        }
        DBWorkbench.getPlatformUI().showNotification(AgentMessages.agent_start_job_title,
            NLS.bind(AgentMessages.agent_started, job.getName()), false, null);
        refreshAgent(agentNode);
        return true;
    }

    static boolean stopJob(@NotNull Component owner, @NotNull SQLServerAgentJob job, @Nullable DBNDatabaseNode agentNode) {
        if (!run(owner, AgentMessages.agent_stop_job, monitor -> SQLServerAgentManager.execute(monitor, job.getDataSource(),
            List.of(SQLServerAgentManager.stopJobStatement(job)), null))) {
            return false;
        }
        DBWorkbench.getPlatformUI().showNotification(AgentMessages.agent_stop_job,
            NLS.bind(AgentMessages.agent_stopped, job.getName()), false, null);
        refreshAgent(agentNode);
        return true;
    }

    static boolean setJobEnabled(@NotNull Component owner, @NotNull SQLServerAgentJob job, boolean enable, @Nullable DBNDatabaseNode agentNode) {
        String title = enable ? AgentMessages.agent_enable : AgentMessages.agent_disable;
        if (!run(owner, title, monitor -> SQLServerAgentManager.execute(monitor, job.getDataSource(),
            List.of(SQLServerAgentManager.enableJobStatement(job, enable)), null))) {
            return false;
        }
        DBWorkbench.getPlatformUI().showNotification(title,
            NLS.bind(enable ? AgentMessages.agent_job_enabled : AgentMessages.agent_job_disabled, job.getName()), false, null);
        refreshAgent(agentNode);
        return true;
    }

    /**
     * A change of the agent objects.
     */
    interface Change {
        void run(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    /**
     * Runs a change in a progress dialog; errors (missing permissions, agent not running) are shown.
     *
     * @return true if it succeeded
     */
    static boolean run(@NotNull Component owner, @NotNull String title, @NotNull Change change) {
        try {
            ProgressDialog.runTask(title, monitor -> {
                change.run(monitor);
                return null;
            });
            return true;
        } catch (DBInterruptedException e) {
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, AgentMessages.agent_error_apply, e);
            return false;
        }
    }

    /**
     * False (after telling the user) when the connection does not allow metadata changes.
     */
    static boolean checkEditAllowed(@NotNull Component owner, @NotNull SQLServerAgent agent, @NotNull String title) {
        if (agent.getDataSource().getContainer().hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA)) {
            return true;
        }
        JOptionPane.showMessageDialog(owner, NLS.bind(AgentMessages.agent_error_metadata_restricted,
            agent.getDataSource().getContainer().getName()), title, JOptionPane.WARNING_MESSAGE);
        return false;
    }

    /**
     * Reads the SQL Server Agent node again in the background (state, jobs, alerts, operators). The explorer shows it
     * as a refresh done elsewhere and keeps its expanded folders.
     */
    static void refreshAgent(@Nullable DBNDatabaseNode agentNode) {
        if (agentNode == null || agentNode.isDisposed()) {
            return;
        }
        new AbstractJob("Refresh SQL Server Agent") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    agentNode.refreshNode(monitor, AgentCommands.class);
                } catch (DBException e) {
                    log.debug("Refresh of SQL Server Agent failed", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    //////////////////////////////////////////////////////////
    // Node helpers

    @Nullable
    private static DBSObject getObject(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseNode databaseNode && !(node instanceof DBNDatabaseFolder) ? databaseNode.getObject() : null;
    }

    /**
     * The SQL Server Agent node at or above a node, or null outside the agent subtree.
     */
    @Nullable
    static DBNDatabaseNode findAgentNode(@Nullable DBNNode node) {
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBNDatabaseNode databaseNode && !(current instanceof DBNDatabaseFolder)
                && databaseNode.getObject() instanceof SQLServerAgent) {
                return databaseNode;
            }
        }
        return null;
    }

    @Nullable
    private static SQLServerAgent getAgent(@Nullable DBNNode node) {
        DBNDatabaseNode agentNode = findAgentNode(node);
        return agentNode == null ? null : (SQLServerAgent) agentNode.getObject();
    }
}
