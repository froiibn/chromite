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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentErrorLog;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SQL Server Agent error log document tab: the lines of the current agent log or of an archive, with a filter.
 *
 * 설계: SSMS 로그 파일 뷰어의 에이전트 로그에 해당한다. {@code xp_readerrorlog <보관 번호>, 2}로 읽고(모델
 * {@link SQLServerAgentManager#readErrorLog}), 보관 파일은 탐색기의 오류 로그 폴더와 같은 목록에서 고른다. 오류·경고 줄은 아이콘으로 구분한다.
 * 이 프로시저는 sysadmin(또는 securityadmin) 권한이 필요하므로 권한이 없으면 상태 줄에 이유가 보인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentErrorLogPanel extends JPanel implements ToolDocument {

    private static final int LEVEL_ERROR = 1;
    private static final int LEVEL_WARNING = 2;

    private final SQLServerAgent agent;
    private final AdminConnection connection;
    private final JComboBox<SQLServerAgentErrorLog> archiveCombo = new JComboBox<>();
    private final JTextField searchField = new JTextField(18);
    // Before the model and the table: the table reads the row count while it is created
    private List<SQLServerAgentManager.LogEntry> entries = new ArrayList<>();
    private final LogTableModel model = new LogTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<LogTableModel> sorter = new TableRowSorter<>(model);
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$
    private int archive;

    /**
     * @param archive the log to show first (0 the current log)
     */
    AgentErrorLogPanel(@NotNull SQLServerAgent agent, int archive) {
        super(new BorderLayout());
        this.agent = agent;
        this.archive = archive;
        connection = new AdminConnection(agent.getDataSource().getContainer());

        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setDefaultRenderer(Object.class, new LevelRenderer());
        // Date and type keep their width; the message takes the rest
        table.getColumnModel().getColumn(0).setMinWidth(UIScale.scale(160));
        table.getColumnModel().getColumn(0).setMaxWidth(UIScale.scale(200));
        table.getColumnModel().getColumn(1).setMinWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(1).setMaxWidth(UIScale.scale(140));
        table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(800));

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
        toolBar.addSeparator();
        toolBar.add(new JLabel(AgentMessages.agent_log_file));
        toolBar.add(Box.createHorizontalStrut(UIScale.scale(6)));
        archiveCombo.addActionListener(e -> {
            if (archiveCombo.getSelectedItem() instanceof SQLServerAgentErrorLog log && log.getArchiveNumber() != this.archive) {
                this.archive = log.getArchiveNumber();
                refresh();
            }
        });
        toolBar.add(archiveCombo);
        toolBar.add(Box.createHorizontalGlue());
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AgentMessages.agent_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setMaximumSize(searchField.getPreferredSize());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
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
        loadArchives();
        refresh();
    }

    /**
     * Fills the log file combo from the agent's Error Logs folder.
     */
    private void loadArchives() {
        connection.run("List SQL Server Agent error logs", (monitor, session, profile) -> agent.getErrorLogs(monitor), logs -> { //$NON-NLS-1$
            archiveCombo.removeAllItems();
            for (SQLServerAgentErrorLog log : logs) {
                archiveCombo.addItem(log);
                if (log.getArchiveNumber() == archive) {
                    archiveCombo.setSelectedItem(log);
                }
            }
        }, error -> {
            // The combo stays empty: the current log is still read
        });
    }

    /**
     * Shows another log file (an archive node opened while this tab was open).
     */
    void showArchive(int archiveNumber) {
        if (archiveNumber == archive) {
            return;
        }
        archive = archiveNumber;
        for (int i = 0; i < archiveCombo.getItemCount(); i++) {
            if (archiveCombo.getItemAt(i).getArchiveNumber() == archiveNumber) {
                archiveCombo.setSelectedIndex(i);
            }
        }
        refresh();
    }

    void refresh() {
        statusLabel.setText(AgentMessages.agent_reading);
        int requested = archive;
        connection.run("Read SQL Server Agent error log", //$NON-NLS-1$
            (monitor, session, profile) -> SQLServerAgentManager.readErrorLog((JDBCSession) session, requested), loaded -> {
                entries = loaded;
                model.fireTableDataChanged();
                updateStatus();
            }, error -> {
                entries = new ArrayList<>();
                model.fireTableDataChanged();
                statusLabel.setText(ErrorText.describeLine(error));
                statusLabel.setToolTipText(ErrorText.describe(error));
            });
    }

    private void applyFilter() {
        String text = searchField.getText().trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends LogTableModel, ? extends Integer> entry) {
                return entries.get(entry.getIdentifier()).text().toLowerCase(Locale.ROOT).contains(text);
            }
        });
        updateStatus();
    }

    private void updateStatus() {
        statusLabel.setText(NLS.bind(AgentMessages.agent_log_status, table.getRowCount(), entries.size()));
        statusLabel.setToolTipText(null);
    }

    @NotNull
    private static String levelLabel(int level) {
        return switch (level) {
            case LEVEL_ERROR -> AgentMessages.agent_log_level_error;
            case LEVEL_WARNING -> AgentMessages.agent_log_level_warning;
            default -> AgentMessages.agent_log_level_information;
        };
    }

    private final class LogTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> AgentMessages.agent_history_column_date;
                case 1 -> AgentMessages.agent_log_column_type;
                default -> AgentMessages.agent_history_column_message;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SQLServerAgentManager.LogEntry entry = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> entry.date() == null ? "" : dateFormat.format(entry.date()); //$NON-NLS-1$
                case 1 -> levelLabel(entry.level());
                default -> entry.text();
            };
        }
    }

    /**
     * Error and warning lines get an icon in the type column.
     */
    private final class LevelRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setIcon(null);
            if (column == 1) {
                int level = entries.get(table.convertRowIndexToModel(row)).level();
                setIcon(UiIcons.get(level == LEVEL_ERROR ? "error" : level == LEVEL_WARNING ? "warning" : "info")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return this;
        }
    }

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(AgentMessages.agent_log_title, agent.getDataSource().getContainer().getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return getTitle();
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("messages"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        connection.close();
    }
}
