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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Query &gt; Query History: the statements executed in query windows ({@link QueryHistory}), newest first.
 *
 * 설계: 하나만 여는 모달 아닌 창이다(편집기를 쓰면서 옆에 둔다). 찾기 글자(문장, 연결, DB 이름에서 대소문자 무시), 연결, 기간으로 거른다.
 * 아래 미리 보기는 읽기 전용 SQL 편집 영역이다. 두 번 누르기·Enter·Open in Editor는 기록의 연결로 새 쿼리 창을 열어 문장을 넣고,
 * Execute는 열고 바로 실행한다(편집기를 여는 일은 메인 창이 한다: {@link Opener}). 기록이 바뀌면(다른 창에서 실행) 목록을 다시 채운다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class QueryHistoryDialog extends JDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$
    private static final String ALL_CONNECTIONS = EditorMessages.editor_history_all_connections;

    /**
     * Opens a statement in a new query window (main window).
     */
    interface Opener {
        /**
         * @param container the recorded connection, or null when it no longer exists
         */
        void openHistoryQuery(@Nullable DBPDataSourceContainer container, @NotNull String query, boolean execute);
    }

    private enum Period {
        TODAY(EditorMessages.editor_history_period_today, 0), WEEK(EditorMessages.editor_history_period_week, 7),
        MONTH(EditorMessages.editor_history_period_month, 30), ALL(EditorMessages.editor_history_period_all, -1);

        private final String label;
        private final int days;

        Period(@NotNull String label, int days) {
            this.label = label;
            this.days = days;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Nullable
    private static QueryHistoryDialog instance;

    private final Opener opener;
    private final JTextField searchField = new JTextField(24);
    private final JComboBox<String> connectionCombo = new JComboBox<>();
    private final JComboBox<Period> periodCombo = new JComboBox<>(Period.values());
    private final HistoryModel model = new HistoryModel();
    private final JTable table = new JTable(model);
    private final SqlEditorPanel.SqlTextArea preview = new SqlEditorPanel.SqlTextArea();
    private final JLabel countLabel = new JLabel();
    private final JButton openButton = new JButton(EditorMessages.editor_history_open);
    private final JButton executeButton = new JButton(EditorMessages.editor_common_execute);
    private final Runnable historyListener = () -> SwingUtilities.invokeLater(this::reload);
    private boolean updatingCombo;

    private QueryHistoryDialog(@NotNull Frame owner, @NotNull Opener opener) {
        super(owner, EditorMessages.editor_history_title, false);
        this.opener = opener;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, EditorMessages.editor_history_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
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
            }
        });
        connectionCombo.addActionListener(e -> {
            if (!updatingCombo) {
                applyFilter();
            }
        });
        periodCombo.setSelectedItem(Period.ALL);
        periodCombo.addActionListener(e -> applyFilter());
        JButton clearButton = new JButton(EditorMessages.editor_history_clear);
        clearButton.addActionListener(e -> clearHistory());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filters.add(searchField);
        filters.add(connectionCombo);
        filters.add(periodCombo);
        JPanel top = new JPanel(new BorderLayout());
        top.add(filters, BorderLayout.WEST);
        top.add(clearButton, BorderLayout.EAST);

        table.setAutoCreateRowSorter(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new HistoryRenderer());
        // Time, connection, database, duration, rows and status keep room for their text; the statement takes the rest
        int[] widths = {170, 170, 150, 90, 70, 120, 480};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(widths[i]));
            if (i < widths.length - 1) {
                table.getColumnModel().getColumn(i).setMinWidth(UIScale.scale(Math.min(widths[i], 120)));
            }
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectionChanged();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && table.rowAtPoint(e.getPoint()) >= 0) {
                    openSelected(false);
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.open"); //$NON-NLS-1$
        table.getActionMap().put("chromite.open", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected(false);
            }
        });

        preview.setEditable(false);
        preview.setHighlightCurrentLine(false);
        JScrollPane previewScroll = new JScrollPane(preview);
        preview.applyTheme();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), previewScroll);
        split.setResizeWeight(0.7);
        split.setContinuousLayout(true);

        openButton.setToolTipText(EditorMessages.editor_history_open_tip);
        openButton.addActionListener(e -> openSelected(false));
        executeButton.setToolTipText(EditorMessages.editor_history_execute_tip);
        executeButton.addActionListener(e -> openSelected(true));
        JButton closeButton = new JButton(EditorMessages.editor_common_close);
        closeButton.addActionListener(e -> dispose());
        countLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(openButton);
        buttons.add(executeButton);
        buttons.add(closeButton);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(countLabel, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        QueryHistory.addListener(historyListener);
        reload();
        setSize(1100, 640);
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the window (one per application).
     */
    static void open(@NotNull Frame owner, @NotNull Opener opener) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new QueryHistoryDialog(owner, opener);
        }
        instance.setVisible(true);
        instance.toFront();
        instance.searchField.requestFocusInWindow();
    }

    @Override
    public void dispose() {
        QueryHistory.removeListener(historyListener);
        if (instance == this) {
            instance = null;
        }
        super.dispose();
    }

    private void reload() {
        List<QueryHistory.Entry> entries = QueryHistory.getEntries();
        model.setAll(entries);
        // Connection filter: the connection names in the history
        Object selected = connectionCombo.getSelectedItem();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (QueryHistory.Entry entry : entries) {
            if (!CommonUtils.isEmpty(entry.connectionName())) {
                names.add(entry.connectionName());
            }
        }
        updatingCombo = true;
        try {
            DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();
            comboModel.addElement(ALL_CONNECTIONS);
            names.forEach(comboModel::addElement);
            comboModel.setSelectedItem(selected != null && (selected.equals(ALL_CONNECTIONS) || names.contains(selected.toString()))
                ? selected : ALL_CONNECTIONS);
            connectionCombo.setModel(comboModel);
        } finally {
            updatingCombo = false;
        }
        applyFilter();
    }

    private void applyFilter() {
        String search = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String connection = connectionCombo.getSelectedItem() instanceof String name && !name.equals(ALL_CONNECTIONS) ? name : null;
        Period period = (Period) periodCombo.getSelectedItem();
        long since = Long.MIN_VALUE;
        if (period != null && period.days >= 0) {
            since = LocalDate.now().minusDays(period.days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        QueryHistory.Entry selected = getSelectedEntry();
        List<QueryHistory.Entry> visible = new ArrayList<>();
        for (QueryHistory.Entry entry : model.all) {
            if (entry.time() < since || (connection != null && !connection.equals(entry.connectionName()))) {
                continue;
            }
            if (!search.isEmpty() && !contains(entry.query(), search) && !contains(entry.connectionName(), search)
                && !contains(entry.database(), search)) {
                continue;
            }
            visible.add(entry);
        }
        model.setVisible(visible);
        int index = selected == null ? -1 : visible.indexOf(selected);
        if (index < 0 && !visible.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            table.getSelectionModel().setSelectionInterval(index, index);
        }
        selectionChanged();
        countLabel.setText(NLS.bind(model.all.size() == 1 ? EditorMessages.editor_history_count_one : EditorMessages.editor_history_count_many,
            new Object[]{visible.size(), model.all.size(), QueryHistory.getRetentionDays()}));
    }

    private static boolean contains(@Nullable String text, @NotNull String search) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(search);
    }

    @Nullable
    private QueryHistory.Entry getSelectedEntry() {
        int row = table.getSelectedRow();
        return row >= 0 && row < model.visible.size() ? model.visible.get(row) : null;
    }

    private void selectionChanged() {
        QueryHistory.Entry entry = getSelectedEntry();
        preview.setDataSourceContainer(entry == null ? null : findConnection(entry.connectionId()));
        preview.setText(entry == null ? "" : entry.error() == null ? entry.query() : entry.query() + "\n\n-- " + entry.error().replace("\n", "\n-- ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        preview.setCaretPosition(0);
        openButton.setEnabled(entry != null);
        executeButton.setEnabled(entry != null);
    }

    private void openSelected(boolean execute) {
        QueryHistory.Entry entry = getSelectedEntry();
        if (entry == null) {
            return;
        }
        DBPDataSourceContainer container = findConnection(entry.connectionId());
        opener.openHistoryQuery(container, entry.query(), execute && container != null);
    }

    private void clearHistory() {
        if (JOptionPane.showConfirmDialog(this, EditorMessages.editor_history_clear_confirm, EditorMessages.editor_history_clear_title,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
            QueryHistory.clear();
        }
    }

    /**
     * The connection with the recorded id in any project of the workspace.
     */
    @Nullable
    static DBPDataSourceContainer findConnection(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceContainer container = project.getDataSourceRegistry().getDataSource(id);
            if (container != null) {
                return container;
            }
        }
        return null;
    }

    @NotNull
    static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.2f s", millis / 1000.0); //$NON-NLS-1$
    }

    private static final class HistoryModel extends AbstractTableModel {
        private static final String[] COLUMNS = {EditorMessages.editor_history_column_time, EditorMessages.editor_history_column_connection,
            EditorMessages.editor_history_column_database, EditorMessages.editor_history_column_duration, EditorMessages.editor_history_column_rows,
            EditorMessages.editor_history_column_status, EditorMessages.editor_history_column_statement};
        private List<QueryHistory.Entry> all = List.of();
        private List<QueryHistory.Entry> visible = List.of();

        void setAll(@NotNull List<QueryHistory.Entry> entries) {
            all = entries;
        }

        void setVisible(@NotNull List<QueryHistory.Entry> entries) {
            visible = entries;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            QueryHistory.Entry entry = visible.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> TIME_FORMAT.format(Instant.ofEpochMilli(entry.time()).atZone(ZoneId.systemDefault()));
                case 1 -> CommonUtils.notEmpty(entry.connectionName());
                case 2 -> CommonUtils.notEmpty(entry.database());
                case 3 -> formatDuration(entry.durationMs());
                case 4 -> entry.rows() < 0 ? "" : String.valueOf(entry.rows());
                case 5 -> entry.status();
                default -> entry.query().strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
            };
        }
    }

    /**
     * Status with its icon, numbers right-aligned, the statement on one line.
     */
    private static final class HistoryRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            setIcon(null);
            setHorizontalAlignment(column == 3 || column == 4 ? RIGHT : LEFT);
            if (value instanceof QueryHistory.Status status) {
                setText(switch (status) {
                    case SUCCESS -> EditorMessages.editor_history_status_success;
                    case ERROR -> EditorMessages.editor_history_status_error;
                    case CANCELED -> EditorMessages.editor_history_status_canceled;
                });
                setIcon(UiIcons.get(switch (status) {
                    case SUCCESS -> "check"; //$NON-NLS-1$
                    case ERROR -> "error"; //$NON-NLS-1$
                    case CANCELED -> "warning"; //$NON-NLS-1$
                }));
            }
            setToolTipText(column == 6 && value != null ? CommonUtils.truncateString(value.toString(), 500) : null);
            return this;
        }
    }
}
