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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.search.DataSearch;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSView;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.DataSearchMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Search Data 문서 탭: 값 하나를 스키마의 모든 테이블이나 고른 테이블의 행에서 찾고, 맞는 행을 쿼리 창에서 연다.
 *
 * 설계: SWT의 데이터 검색({@code SearchDataPage}, {@code SearchDataQuery}, Search 대화상자의 한 페이지)을 Find Objects
 * ({@link ObjectSearchPanel})와 같은 문서 탭으로 옮겼다. 찾기는 모델 계층의 {@link DataSearch}(매개 변수 바인딩, 테이블당 행 수 제한,
 * 자동 커밋인 자기 연결)가 하고, 이 패널은 범위·옵션·결과와 작업(Job) 관리만 한다.
 * <ul>
 *     <li>범위: Object Explorer에서 연다. 스키마·카탈로그·"Tables" 폴더 하나를 고르면 그 안의 테이블과 뷰를, 같은 부모의 테이블 여러 개를
 *     고르면 그 테이블들을 보인다({@link #findScope}). 데이터베이스 전체(스키마를 담는 노드)는 범위로 받지 않는다: 한 번에 모든 스키마를
 *     훑으면 운영 DB에 부담이 크고, SWT도 대화상자에서 고른 컨테이너만 찾았다. 목록에서 체크한 것만 찾는다. 뷰는 기본으로 체크하지 않는다
 *     (뷰의 쿼리가 무거울 수 있고 같은 행이 원본 테이블에서도 나온다).</li>
 *     <li>찾기: 테이블마다 차례로 찾고, 테이블의 결과를 바로 표에 더한다. 한 테이블의 오류는 상태 줄의 문제 목록에 남기고 다음 테이블로 간다.
 *     중지는 작업을 취소하고, 작업 취소는 실행 중인 문장도 취소한다.</li>
 *     <li>결과: 테이블·열·행 수(행 수 제한에 닿으면 "100+")·첫 값. 두 번 누르거나 Enter를 누르면 그 열의 조건만 넣은 SELECT
 *     ({@link ObjectScripts#generateFilteredSelect}, Select Top과 같은 행 수 제한)를 새 쿼리 창에서 바로 실행한다. 쿼리 창은
 *     사용자의 SQL이므로 값은 SQL 안에 글자로 들어간다.</li>
 *     <li>범위가 같으면(같은 연결과 같은 범위 이름) 이미 열린 탭을 보인다({@link ObjectExplorerPanel.QueryWindows#openToolDocument}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-search.md)
 */
final class DataSearchPanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(DataSearchPanel.class);
    private static final int DEFAULT_ROWS_PER_TABLE = 100;

    /**
     * What a tab searches: the tables of a parent (schema, catalog, "Tables" folder), or chosen tables.
     *
     * @param name      the full name shown in the form ("chr_data_search.ds", "ds (3)")
     * @param shortName the name in the tab title, so that two search tabs can be told apart ("ds")
     * @param parent    schema, catalog or folder whose tables are read when the tab opens; null with {@code tables}
     * @param tables    the chosen tables; null with {@code parent}
     */
    record Scope(@NotNull DBPDataSourceContainer container, @NotNull String name, @NotNull String shortName, @Nullable DBSObject parent,
                 @Nullable List<DBSEntity> tables) {

        @NotNull
        String key() {
            return "data-search:" + container.getId() + ":" + name; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final class TableRow {
        final DBSEntity table;
        final boolean view;
        boolean checked;

        TableRow(@NotNull DBSEntity table) {
            this.table = table;
            this.view = table instanceof DBSView || table instanceof DBSTable dbsTable && dbsTable.isView();
            this.checked = !view;
        }
    }

    private record ResultRow(@NotNull DBSEntity table, @NotNull DataSearch.ColumnHit hit) {
    }

    private final Scope scope;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final JTextField textField = new JTextField(24);
    private final JCheckBox caseSensitiveCheck = new JCheckBox(DataSearchMessages.datasearch_case_sensitive);
    private final JCheckBox numbersCheck = new JCheckBox(DataSearchMessages.datasearch_numbers, true);
    private final JCheckBox longTextCheck = new JCheckBox(DataSearchMessages.datasearch_long_text);
    private final JSpinner rowsSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_ROWS_PER_TABLE, 1, 100000, 10));
    private final JButton searchButton = new JButton(DataSearchMessages.datasearch_button);
    private final JLabel statusLabel = new JLabel(" ");
    private final TableListModel tableModel = new TableListModel();
    private final JTable tableList = new JTable(tableModel);
    private final ResultModel resultModel = new ResultModel();
    private final JTable resultTable = new JTable(resultModel) {
        @Override
        public void updateUI() {
            super.updateUI();
            // The renderer is not in the component tree: a new one takes the colors of the new theme
            ResultRenderer renderer = new ResultRenderer();
            setDefaultRenderer(Object.class, renderer);
            setDefaultRenderer(Integer.class, renderer);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row >= 0 && resultModel.get(convertRowIndexToModel(row)).hit().column() == null) {
                return DataSearchMessages.datasearch_other_columns_tip;
            }
            return DataSearchMessages.datasearch_result_tip;
        }
    };
    private final Action openAction = new AbstractAction(DataSearchMessages.datasearch_open_rows) {
        @Override
        public void actionPerformed(ActionEvent e) {
            openSelected();
        }
    };
    @Nullable
    private AbstractJob job;
    private int serial;

    DataSearchPanel(@NotNull Scope scope, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        super(new BorderLayout());
        this.scope = scope;
        this.queryWindows = queryWindows;

        textField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DataSearchMessages.datasearch_placeholder);
        textField.addActionListener(e -> startSearch());
        searchButton.addActionListener(e -> {
            if (job != null) {
                stopJob();
            } else {
                startSearch();
            }
        });
        caseSensitiveCheck.setToolTipText(DataSearchMessages.datasearch_case_sensitive_tip);
        numbersCheck.setToolTipText(DataSearchMessages.datasearch_numbers_tip);
        longTextCheck.setToolTipText(DataSearchMessages.datasearch_long_text_tip);
        rowsSpinner.setToolTipText(DataSearchMessages.datasearch_rows_per_table_tip);
        for (JCheckBox check : List.of(caseSensitiveCheck, numbersCheck, longTextCheck)) {
            check.setOpaque(false);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), UIScale.scale(12), UIScale.scale(6), UIScale.scale(12)));
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(UIScale.scale(4), 0, UIScale.scale(4), UIScale.scale(10));
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.weightx = 1;
        fieldConstraints.anchor = GridBagConstraints.WEST;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), 0);

        JPanel findRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        findRow.setOpaque(false);
        findRow.add(textField);
        findRow.add(searchButton);
        form.add(new JLabel(DataSearchMessages.datasearch_label_find), labelConstraints);
        form.add(findRow, fieldConstraints);

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(10), 0));
        optionsRow.setOpaque(false);
        optionsRow.add(caseSensitiveCheck);
        optionsRow.add(numbersCheck);
        optionsRow.add(longTextCheck);
        JLabel rowsLabel = new JLabel(DataSearchMessages.datasearch_rows_per_table);
        rowsLabel.setLabelFor(rowsSpinner);
        optionsRow.add(rowsLabel);
        optionsRow.add(rowsSpinner);
        form.add(new JLabel(DataSearchMessages.datasearch_label_options), labelConstraints);
        form.add(optionsRow, fieldConstraints);

        form.add(new JLabel(DataSearchMessages.datasearch_label_scope), labelConstraints);
        form.add(new JLabel(NLS.bind(DataSearchMessages.datasearch_scope_text, scope.name(), scope.container().getName())), fieldConstraints);

        tableList.setShowGrid(false);
        tableList.setIntercellSpacing(new Dimension(0, 0));
        tableList.setFillsViewportHeight(true);
        tableList.getColumnModel().getColumn(0).setMaxWidth(UIScale.scale(32));
        tableList.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(200));
        tableList.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(60));
        JScrollPane tableScroll = new JScrollPane(tableList);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel checkButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(4)));
        checkButtons.setOpaque(false);
        JButton allButton = new JButton(DataSearchMessages.datasearch_select_all);
        allButton.addActionListener(e -> tableModel.setAllChecked(true));
        JButton noneButton = new JButton(DataSearchMessages.datasearch_select_none);
        noneButton.addActionListener(e -> tableModel.setAllChecked(false));
        checkButtons.add(allButton);
        checkButtons.add(noneButton);
        JPanel tablesPanel = new JPanel(new BorderLayout());
        tablesPanel.add(tableScroll, BorderLayout.CENTER);
        tablesPanel.add(checkButtons, BorderLayout.SOUTH);

        resultTable.setAutoCreateRowSorter(true);
        resultTable.setShowGrid(false);
        resultTable.setIntercellSpacing(new Dimension(0, 0));
        resultTable.setFillsViewportHeight(true);
        resultTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(200));
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(160));
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(80));
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(360));
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                // The context menu acts on the row under the mouse
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        resultTable.setRowSelectionInterval(row, row);
                    }
                }
            }
        });
        JPopupMenu resultMenu = new JPopupMenu();
        resultMenu.add(openAction);
        resultTable.setComponentPopupMenu(resultMenu);
        resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "chromite.openRows"); //$NON-NLS-1$ //$NON-NLS-2$
        resultTable.getActionMap().put("chromite.openRows", openAction); //$NON-NLS-1$
        JScrollPane resultScroll = new JScrollPane(resultTable);
        resultScroll.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tablesPanel, resultScroll);
        split.setDividerLocation(UIScale.scale(280));
        split.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        add(form, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        if (scope.tables() != null) {
            tableModel.set(scope.tables());
            // Chosen tables are searched even when they are views
            tableModel.setAllChecked(true);
        } else {
            loadTables();
        }
        SwingUtilities.invokeLater(textField::requestFocusInWindow);
    }

    /**
     * The scope for the nodes selected in Object Explorer, or null when Search Data does not apply to them. Called on the
     * EDT: it looks at types only and never reads metadata.
     */
    @Nullable
    static Scope findScope(@NotNull List<DBNNode> nodes) {
        if (nodes.isEmpty() || !(nodes.getFirst() instanceof DBNDatabaseNode first) || first instanceof DBNDataSource) {
            return null;
        }
        DBPDataSourceContainer container = first.getDataSourceContainer();
        DBSObject object = first.getObject();
        if (nodes.size() == 1 && !isTable(object) && DataTransferObjects.isDataContainerParent(object)) {
            DBSObject schema = object instanceof DBNDatabaseFolder folder ? folder.getParentObject() : object;
            String name = object instanceof DBNDatabaseFolder folder
                ? DBUtils.getObjectFullName(schema, DBPEvaluationContext.UI) + " / " + folder.getNodeDisplayName() //$NON-NLS-1$
                : DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
            return new Scope(container, name, schema.getName(), object, null);
        }
        // Tables of one parent: one database, so that one connection of the search reaches them all (PostgreSQL)
        List<DBSEntity> tables = new ArrayList<>();
        for (DBNNode node : nodes) {
            if (!(node instanceof DBNDatabaseNode databaseNode) || !isTable(databaseNode.getObject())
                || databaseNode.getObject().getParentObject() != object.getParentObject()) {
                return null;
            }
            tables.add((DBSEntity) databaseNode.getObject());
        }
        DBSObject parent = Objects.requireNonNull(object.getParentObject());
        String name = tables.size() == 1
            ? DBUtils.getObjectFullName(object, DBPEvaluationContext.UI)
            : DBUtils.getObjectFullName(parent, DBPEvaluationContext.UI) + " (" + tables.size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        return new Scope(container, name, tables.size() == 1 ? object.getName() : parent.getName(), null, tables);
    }

    private static boolean isTable(@Nullable DBSObject object) {
        return object instanceof DBSEntity && object instanceof DBSDataContainer;
    }

    private void loadTables() {
        DBSObject parent = Objects.requireNonNull(scope.parent());
        int current = ++serial;
        statusLabel.setText(DataSearchMessages.datasearch_loading_tables);
        searchButton.setEnabled(false);
        job = new AbstractJob("Read tables for data search") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    if (!scope.container().isConnected()) {
                        DBUtils.initDataSource(monitor, scope.container(), null);
                    }
                    List<DBSEntity> tables = new ArrayList<>();
                    for (DBSDataContainer container : DataTransferObjects.readDataContainers(monitor, parent)) {
                        tables.add((DBSEntity) container);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (current == serial) {
                            job = null;
                            searchButton.setEnabled(true);
                            tableModel.set(tables);
                            statusLabel.setText(tables.isEmpty() ? DataSearchMessages.datasearch_no_tables
                                : NLS.bind(DataSearchMessages.datasearch_tables_loaded, tables.size()));
                        }
                    });
                } catch (Throwable e) {
                    log.debug("Can't read the tables of " + parent.getName(), e);
                    SwingUtilities.invokeLater(() -> {
                        if (current == serial) {
                            job = null;
                            searchButton.setEnabled(true);
                            statusLabel.setText(e instanceof DBInterruptedException ? " " : ErrorText.describeLine(e)); //$NON-NLS-1$
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void startSearch() {
        String text = textField.getText().trim();
        if (text.isEmpty()) {
            statusLabel.setText(DataSearchMessages.datasearch_enter_text);
            textField.requestFocusInWindow();
            return;
        }
        List<DBSEntity> tables = tableModel.getChecked();
        if (tables.isEmpty()) {
            statusLabel.setText(DataSearchMessages.datasearch_select_tables);
            return;
        }
        DataSearch.Options options = new DataSearch.Options(text, caseSensitiveCheck.isSelected(), numbersCheck.isSelected(),
            longTextCheck.isSelected(), (Integer) rowsSpinner.getValue());
        int current = ++serial;
        resultModel.clear();
        statusLabel.setToolTipText(null);
        searchButton.setText(DataSearchMessages.datasearch_button_stop);
        job = new AbstractJob("Search data") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> problems = new ArrayList<>();
                int searched = 0;
                String fatal = null;
                try {
                    if (!scope.container().isConnected()) {
                        DBUtils.initDataSource(monitor, scope.container(), null);
                    }
                    if (!scope.container().isConnected()) {
                        throw new DBException(DataSearchMessages.datasearch_not_connected);
                    }
                    try (DataSearch search = DataSearch.open(monitor, tables.getFirst(), options)) {
                        for (int i = 0; i < tables.size() && !monitor.isCanceled(); i++) {
                            DBSEntity table = tables.get(i);
                            String status = NLS.bind(DataSearchMessages.datasearch_searching, table.getName(), i + 1, tables.size());
                            monitor.subTask(status);
                            SwingUtilities.invokeLater(() -> {
                                if (current == serial) {
                                    statusLabel.setText(status);
                                }
                            });
                            try {
                                DataSearch.TableHit hit = search.search(monitor, table);
                                searched++;
                                if (hit == null) {
                                    continue;
                                }
                                for (DataSearch.ColumnProblem problem : hit.problems()) {
                                    problems.add(table.getName() + "." + problem.column() + ": " + ErrorText.describeLine(problem.error())); //$NON-NLS-1$ //$NON-NLS-2$
                                }
                                List<ResultRow> rows = new ArrayList<>();
                                for (DataSearch.ColumnHit columnHit : hit.columns()) {
                                    rows.add(new ResultRow(table, columnHit));
                                }
                                SwingUtilities.invokeLater(() -> {
                                    if (current == serial) {
                                        resultModel.add(rows);
                                    }
                                });
                            } catch (Throwable e) {
                                if (monitor.isCanceled()) {
                                    break;
                                }
                                log.debug("Data search failed in " + table.getName(), e);
                                problems.add(table.getName() + ": " + ErrorText.describeLine(e)); //$NON-NLS-1$
                            }
                        }
                    }
                } catch (DataSearch.SharedTransactionException e) {
                    fatal = DataSearchMessages.datasearch_shared_transaction;
                } catch (Throwable e) {
                    if (!monitor.isCanceled()) {
                        log.debug("Data search failed", e);
                        fatal = ErrorText.describeLine(e);
                    }
                }
                boolean canceled = monitor.isCanceled();
                int searchedTables = searched;
                String error = fatal;
                SwingUtilities.invokeLater(() -> {
                    if (current == serial) {
                        searchFinished(canceled, searchedTables, error, problems);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void searchFinished(boolean canceled, int searched, @Nullable String error, @NotNull List<String> problems) {
        job = null;
        searchButton.setText(DataSearchMessages.datasearch_button);
        String text;
        if (error != null) {
            text = error;
        } else if (resultModel.getRowCount() == 0) {
            text = NLS.bind(DataSearchMessages.datasearch_none_found, searched);
        } else {
            text = NLS.bind(DataSearchMessages.datasearch_found, new Object[]{resultModel.getRowCount(), resultModel.getTableCount(), searched});
        }
        if (canceled) {
            text = NLS.bind(DataSearchMessages.datasearch_stopped, text);
        }
        if (!problems.isEmpty()) {
            text += " · " + NLS.bind(DataSearchMessages.datasearch_problems, problems.size(), String.join("; ", problems)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        statusLabel.setText(text);
        statusLabel.setToolTipText(problems.isEmpty() ? (error != null ? error : null) : String.join("\n", problems)); //$NON-NLS-1$
    }

    private void stopJob() {
        if (job != null) {
            job.cancel();
        }
    }

    /**
     * Opens the rows of the selected result in a new query window and runs it (a SELECT with that column's condition).
     */
    private void openSelected() {
        int viewRow = resultTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        ResultRow row = resultModel.get(resultTable.convertRowIndexToModel(viewRow));
        int maxRows = ObjectExplorerPanel.SELECT_TOP_ROWS;
        String script;
        try {
            script = ProgressDialog.runTask(DataSearchMessages.datasearch_open_rows,
                monitor -> ObjectScripts.generateFilteredSelect(monitor, row.table(), row.hit().filter(), maxRows));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(DataSearchMessages.datasearch_open_rows, DataSearchMessages.datasearch_open_failed, e);
            return;
        }
        queryWindows.openScript(scope.container(), script, maxRows, true);
    }

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(DataSearchMessages.datasearch_tab_title, scope.shortName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(DataSearchMessages.datasearch_tooltip, NLS.bind(DataSearchMessages.datasearch_scope_text, scope.name(), scope.container().getName()));
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("search"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        stopJob();
        serial++;
    }

    /**
     * The tables of the scope with a check box each.
     */
    private static final class TableListModel extends AbstractTableModel {
        private final List<TableRow> rows = new ArrayList<>();

        void set(@NotNull List<DBSEntity> tables) {
            rows.clear();
            for (DBSEntity table : tables) {
                rows.add(new TableRow(table));
            }
            fireTableDataChanged();
        }

        void setAllChecked(boolean checked) {
            for (TableRow row : rows) {
                row.checked = checked;
            }
            fireTableDataChanged();
        }

        @NotNull
        List<DBSEntity> getChecked() {
            List<DBSEntity> checked = new ArrayList<>();
            for (TableRow row : rows) {
                if (row.checked) {
                    checked.add(row.table);
                }
            }
            return checked;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> ""; //$NON-NLS-1$
                case 1 -> DataSearchMessages.datasearch_tables_column_name;
                default -> DataSearchMessages.datasearch_tables_column_type;
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            TableRow tableRow = rows.get(row);
            return switch (column) {
                case 0 -> tableRow.checked;
                case 1 -> tableRow.table.getName();
                default -> tableRow.view ? DataSearchMessages.datasearch_type_view : DataSearchMessages.datasearch_type_table;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                rows.get(row).checked = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }
    }

    private static final class ResultModel extends AbstractTableModel {
        private static final String[] COLUMNS = {DataSearchMessages.datasearch_column_table, DataSearchMessages.datasearch_column_column,
            DataSearchMessages.datasearch_column_rows, DataSearchMessages.datasearch_column_value};
        private final List<ResultRow> rows = new ArrayList<>();

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        void add(@NotNull List<ResultRow> added) {
            if (added.isEmpty()) {
                return;
            }
            int first = rows.size();
            rows.addAll(added);
            fireTableRowsInserted(first, rows.size() - 1);
        }

        @NotNull
        ResultRow get(int row) {
            return rows.get(row);
        }

        int getTableCount() {
            Set<DBSEntity> tables = new HashSet<>();
            for (ResultRow row : rows) {
                tables.add(row.table());
            }
            return tables.size();
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
        public Class<?> getColumnClass(int column) {
            // Rows sort as numbers; "100+" is shown by the renderer
            return column == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ResultRow result = rows.get(row);
            DataSearch.ColumnHit hit = result.hit();
            return switch (column) {
                case 0 -> result.table().getName();
                case 1 -> hit.column() == null ? DataSearchMessages.datasearch_other_columns : hit.column().getName();
                case 2 -> hit.rows();
                default -> hit.sample() == null ? "" : hit.sample(); //$NON-NLS-1$
            };
        }
    }

    /**
     * The table column has the table icon; the rows column shows "100+" when the row limit was reached.
     */
    private final class ResultRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            DataSearch.ColumnHit hit = resultModel.get(table.convertRowIndexToModel(row)).hit();
            Object shown = table.convertColumnIndexToModel(column) == 2 && hit.more()
                ? NLS.bind(DataSearchMessages.datasearch_rows_more, value) : value;
            super.getTableCellRendererComponent(table, shown, isSelected, false, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(6)));
            setIcon(table.convertColumnIndexToModel(column) == 0 ? UiIcons.get("table") : null); //$NON-NLS-1$
            return this;
        }
    }
}
