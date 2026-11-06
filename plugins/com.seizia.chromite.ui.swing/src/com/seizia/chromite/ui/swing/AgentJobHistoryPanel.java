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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgent;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJob;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Job History document tab: the runs of a job (or of all jobs), each with its steps, outcome, duration and message.
 *
 * 설계: SSMS 로그 파일 뷰어의 작업 기록처럼 실행 한 번을 한 줄로 보이고, 고른 실행의 단계를 아래 표에, 고른 줄의 전체 메시지를 그 아래에
 * 보여 준다. {@code sp_help_jobhistory}는 실행 결과(단계 0) 줄과 단계 줄을 따로 돌려주므로, 기록 번호 순서로 읽으며 작업마다 결과 줄 앞에 온
 * 단계 줄을 그 실행에 묶는다. 결과 줄이 아직 없는 단계(실행 중인 작업)는 "진행 중" 실행으로 맨 위에 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentJobHistoryPanel extends JPanel implements ToolDocument {

    private static final int OUTCOME_FAILED = 0;
    private static final int OUTCOME_SUCCEEDED = 1;
    private static final int OUTCOME_IN_PROGRESS = 4;

    /**
     * One run: its outcome row (null while it runs) and its step rows in order.
     */
    private record Run(@Nullable SQLServerAgentManager.HistoryEntry outcome, @NotNull List<SQLServerAgentManager.HistoryEntry> steps) {
        @NotNull
        SQLServerAgentManager.HistoryEntry first() {
            return outcome != null ? outcome : steps.get(0);
        }

        int status() {
            return outcome != null ? outcome.runStatus() : OUTCOME_IN_PROGRESS;
        }

        long order() {
            return outcome != null ? outcome.instanceId() : Long.MAX_VALUE;
        }
    }

    private final SQLServerAgent agent;
    @Nullable
    private final SQLServerAgentJob job;
    private final AdminConnection connection;
    // Before the table models: a table reads its model while it is created
    private final List<Run> runs = new ArrayList<>();
    private final RunTableModel runModel = new RunTableModel();
    private final JTable runTable = new JTable(runModel);
    private final StepTableModel stepModel = new StepTableModel();
    private final JTable stepTable = new JTable(stepModel);
    private final JTextArea messageArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$

    /**
     * @param job a job, or null for the history of all jobs
     */
    AgentJobHistoryPanel(@NotNull SQLServerAgent agent, @Nullable SQLServerAgentJob job) {
        super(new BorderLayout());
        this.agent = agent;
        this.job = job;
        connection = new AdminConnection(agent.getDataSource().getContainer());

        configureTable(runTable);
        configureTable(stepTable);
        setWidths(stepTable, new int[] {60, 160, 150, 100, 80, 480});
        runTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                runSelected();
            }
        });
        stepTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                stepSelected();
            }
        });
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);

        Action refreshAction = new AbstractAction(AgentMessages.agent_refresh) {
            @Override
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        };
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$

        JScrollPane runScroll = scroll(runTable);
        runScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(260)));
        JScrollPane stepScroll = scroll(stepTable);
        stepScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(150)));
        JScrollPane messageScroll = new JScrollPane(messageArea);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(90)));
        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stepScroll, messageScroll);
        lower.setResizeWeight(0.6);
        lower.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, runScroll, lower);
        split.setResizeWeight(0.55);
        split.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        add(toolBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        refresh();
    }

    private void configureTable(@NotNull JTable table) {
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new OutcomeRenderer());
    }

    private static void setWidths(@NotNull JTable table, @NotNull int[] widths) {
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(widths[i]));
        }
    }

    @NotNull
    private static JScrollPane scroll(@NotNull JTable table) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    void refresh() {
        statusLabel.setText(AgentMessages.agent_reading);
        connection.run("Read SQL Server Agent job history", //$NON-NLS-1$
            (monitor, session, profile) -> SQLServerAgentManager.readHistory((JDBCSession) session, job == null ? null : job.getJobId()),
            this::showHistory, error -> {
                statusLabel.setText(ErrorText.describeLine(error));
                statusLabel.setToolTipText(ErrorText.describe(error));
            });
    }

    private void showHistory(@NotNull List<SQLServerAgentManager.HistoryEntry> entries) {
        // Oldest first: the steps of a run come before its outcome row
        List<SQLServerAgentManager.HistoryEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(SQLServerAgentManager.HistoryEntry::instanceId));
        Map<String, List<SQLServerAgentManager.HistoryEntry>> pending = new HashMap<>();
        runs.clear();
        for (SQLServerAgentManager.HistoryEntry entry : ordered) {
            List<SQLServerAgentManager.HistoryEntry> steps = pending.computeIfAbsent(entry.jobId(), id -> new ArrayList<>());
            if (entry.isJobOutcome()) {
                runs.add(new Run(entry, new ArrayList<>(steps)));
                steps.clear();
            } else {
                steps.add(entry);
            }
        }
        for (List<SQLServerAgentManager.HistoryEntry> steps : pending.values()) {
            if (!steps.isEmpty()) {
                runs.add(new Run(null, steps));
            }
        }
        runs.sort(Comparator.comparingLong(Run::order).reversed());
        runModel.fireTableStructureChanged();
        // Most of the width for the message; the structure change reset the widths
        setWidths(runTable, job == null ? new int[] {150, 160, 100, 80, 560} : new int[] {150, 100, 80, 560});
        if (!runs.isEmpty()) {
            runTable.setRowSelectionInterval(0, 0);
        } else {
            runSelected();
        }
        statusLabel.setText(runs.isEmpty() ? AgentMessages.agent_history_empty : NLS.bind(AgentMessages.agent_history_status, runs.size()));
        statusLabel.setToolTipText(null);
    }

    /**
     * Shows the steps of the selected run and the run's message (a step's message once a step is selected).
     */
    private void runSelected() {
        int row = runTable.getSelectedRow();
        Run run = row >= 0 ? runs.get(row) : null;
        stepModel.setRun(run);
        messageArea.setText(run == null ? "" : run.first().message()); //$NON-NLS-1$
        messageArea.setCaretPosition(0);
    }

    private void stepSelected() {
        int row = stepTable.getSelectedRow();
        if (row >= 0 && stepModel.run != null) {
            messageArea.setText(stepModel.run.steps().get(row).message());
            messageArea.setCaretPosition(0);
        }
    }

    @NotNull
    private String formatDate(@Nullable Timestamp date) {
        return date == null ? "" : dateFormat.format(date); //$NON-NLS-1$
    }

    @NotNull
    private static String formatDuration(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); //$NON-NLS-1$
    }

    @NotNull
    private static String firstLine(@NotNull String message) {
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    /**
     * Outcome cells get an icon (succeeded, failed, other).
     */
    private static final class OutcomeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value instanceof OutcomeCell cell ? cell.label() : value, isSelected, hasFocus, row, column);
            setIcon(value instanceof OutcomeCell cell ? UiIcons.get(switch (cell.status()) {
                case OUTCOME_SUCCEEDED -> "check"; //$NON-NLS-1$
                case OUTCOME_FAILED -> "error"; //$NON-NLS-1$
                default -> "warning"; //$NON-NLS-1$
            }) : null);
            return this;
        }
    }

    private record OutcomeCell(int status, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class RunTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return runs.size();
        }

        @Override
        public int getColumnCount() {
            return job == null ? 5 : 4;
        }

        @Override
        public String getColumnName(int column) {
            int index = job == null ? column : column == 0 ? 0 : column + 1;
            return switch (index) {
                case 0 -> AgentMessages.agent_history_column_date;
                case 1 -> AgentMessages.agent_history_column_job;
                case 2 -> AgentMessages.agent_history_column_outcome;
                case 3 -> AgentMessages.agent_history_column_duration;
                default -> AgentMessages.agent_history_column_message;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Run run = runs.get(rowIndex);
            SQLServerAgentManager.HistoryEntry first = run.first();
            int index = job == null ? columnIndex : columnIndex == 0 ? 0 : columnIndex + 1;
            return switch (index) {
                case 0 -> formatDate(first.runDate());
                case 1 -> first.jobName();
                case 2 -> new OutcomeCell(run.status(), SQLServerAgentJob.getOutcomeLabel(run.status()));
                case 3 -> run.outcome() == null ? "" : formatDuration(run.outcome().durationSeconds()); //$NON-NLS-1$
                default -> firstLine(first.message());
            };
        }
    }

    private final class StepTableModel extends AbstractTableModel {
        @Nullable
        private Run run;

        void setRun(@Nullable Run run) {
            this.run = run;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return run == null ? 0 : run.steps().size();
        }

        @Override
        public int getColumnCount() {
            return 6;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> AgentMessages.agent_history_column_step_id;
                case 1 -> AgentMessages.agent_history_column_step_name;
                case 2 -> AgentMessages.agent_history_column_date;
                case 3 -> AgentMessages.agent_history_column_outcome;
                case 4 -> AgentMessages.agent_history_column_duration;
                default -> AgentMessages.agent_history_column_message;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SQLServerAgentManager.HistoryEntry step = run == null ? null : run.steps().get(rowIndex);
            if (step == null) {
                return ""; //$NON-NLS-1$
            }
            return switch (columnIndex) {
                case 0 -> String.valueOf(step.stepId());
                case 1 -> step.stepName();
                case 2 -> formatDate(step.runDate());
                case 3 -> new OutcomeCell(step.runStatus(), SQLServerAgentJob.getOutcomeLabel(step.runStatus()));
                case 4 -> formatDuration(step.durationSeconds());
                default -> firstLine(step.message());
            };
        }
    }

    @NotNull
    @Override
    public String getTitle() {
        return job == null
            ? NLS.bind(AgentMessages.agent_history_title_all, agent.getDataSource().getContainer().getName())
            : NLS.bind(AgentMessages.agent_history_title, job.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return job == null ? getTitle() : NLS.bind(AgentMessages.agent_history_tooltip, job.getName(), agent.getDataSource().getContainer().getName());
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("history"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        connection.close();
    }
}
