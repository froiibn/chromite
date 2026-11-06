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
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectReference;
import org.jkiss.dbeaver.model.struct.DBSObjectType;
import org.jkiss.dbeaver.model.struct.DBSStructContainer;
import org.jkiss.dbeaver.model.struct.DBSStructureAssistant;
import org.jkiss.dbeaver.model.struct.DBSWrapper;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSView;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Find Objects 문서 탭: 이름으로 테이블·뷰·프로시저·열을 여러 연결에서 찾고, 결과를 Object Explorer에서 고른다.
 *
 * 설계: SWT의 메타데이터 검색({@code SearchMetadataPage}, {@code SearchMetadataQuery})과 같은 모델 API를 쓴다:
 * 연결마다 {@link DBSStructureAssistant#findObjectsByMask}로 찾고, 찾은 참조를 {@code resolveObject}로 객체로 바꾼 뒤
 * 탐색기 노드({@link DBNModel#getNodeByObject})로 보인다. 화면 배치는 SSMS의 Object Explorer Details 검색처럼 문서 탭이다.
 * <ul>
 *     <li>이름 조건은 SWT와 같다: Contains는 {@code %이름%}, Starts with는 {@code 이름%}, Like는 입력을 그대로 LIKE 패턴으로 쓴다.
 *     대소문자 구분은 {@code ObjectsSearchParams#setCaseSensitive}다.</li>
 *     <li>객체 종류: SWT는 연결 하나의 지원 종류 목록({@code getSupportedObjectTypes})을 보이지만, 여러 연결을 한 번에 찾으므로
 *     종류를 묶음(테이블, 뷰, 프로시저·함수, 열, 그 밖)으로 보이고 연결마다 지원 종류를 그 묶음에 나눈다(종류 클래스로 판단).
 *     예를 들어 SQL Server의 "User table"은 테이블, PostgreSQL의 "Table column"은 열이다.</li>
 *     <li>범위: 탭을 열 때 Object Explorer에서 고른 노드가 스키마·데이터베이스 아래면 그 컨테이너({@link DBSStructContainer})로 좁힐 수 있다
 *     (SWT도 고른 노드의 컨테이너를 {@code setParentObject}로 넘긴다). 다른 연결에는 범위가 없다.</li>
 *     <li>연결되지 않은 연결은 찾기 전에 연결한다(SQL 편집기와 같이 {@link DBUtils#initDataSource}).
 *     연결마다 결과를 다 찾으면 바로 표에 더한다. 최대 결과 수는 연결마다 {@value #MAX_RESULTS}개다.</li>
 *     <li>결과를 두 번 누르거나 Enter를 누르면 Object Explorer가 그 노드까지 펼쳐 고른다({@code ObjectExplorerPanel#revealNode}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class ObjectSearchPanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(ObjectSearchPanel.class);
    private static final int MAX_RESULTS = 500;

    /**
     * Object type groups shown as check boxes; a database type belongs to the first group whose class it is.
     */
    enum TypeGroup {
        VIEWS(ConnectionMessages.search_type_views),
        TABLES(ConnectionMessages.search_type_tables),
        PROCEDURES(ConnectionMessages.search_type_procedures),
        COLUMNS(ConnectionMessages.search_type_columns),
        OTHER(ConnectionMessages.search_type_other);

        private final String label;

        TypeGroup(@NotNull String label) {
            this.label = label;
        }

        @NotNull
        static TypeGroup of(@NotNull DBSObjectType type) {
            Class<?> typeClass = type.getTypeClass();
            if (typeClass == null) {
                return OTHER;
            }
            if (DBSView.class.isAssignableFrom(typeClass)) {
                return VIEWS;
            }
            if (DBSTable.class.isAssignableFrom(typeClass) || DBSEntity.class.isAssignableFrom(typeClass)) {
                return TABLES;
            }
            if (DBSProcedure.class.isAssignableFrom(typeClass)) {
                return PROCEDURES;
            }
            if (DBSEntityAttribute.class.isAssignableFrom(typeClass)) {
                return COLUMNS;
            }
            return OTHER;
        }
    }

    private enum MatchType {
        CONTAINS(ConnectionMessages.search_match_contains),
        STARTS_WITH(ConnectionMessages.search_match_starts_with),
        LIKE(ConnectionMessages.search_match_like);

        private final String label;

        MatchType(@NotNull String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record Result(@NotNull DBNDatabaseNode node, @NotNull String type, @NotNull String fullName, @NotNull String connection) {
    }

    private final Consumer<DBNNode> revealer;
    private final JTextField nameField = new JTextField(18);
    private final JComboBox<MatchType> matchCombo = new JComboBox<>(MatchType.values());
    private final JCheckBox caseSensitiveCheck = new JCheckBox(ConnectionMessages.search_case_sensitive);
    private final Map<TypeGroup, JCheckBox> typeChecks = new LinkedHashMap<>();
    private final Map<DBPDataSourceContainer, JCheckBox> connectionChecks = new LinkedHashMap<>();
    private final JCheckBox scopeCheck = new JCheckBox();
    @Nullable
    private final DBSObject scopeObject;
    private final JButton searchButton = new JButton(ConnectionMessages.search_button);
    private final JLabel statusLabel = new JLabel(" ");
    private final ResultModel resultModel = new ResultModel();
    private final JTable resultTable = new JTable(resultModel) {
        @Override
        public void updateUI() {
            super.updateUI();
            // The renderer is not in the component tree: a new one takes the colors of the new theme
            setDefaultRenderer(Object.class, new ResultRenderer());
        }
    };
    @Nullable
    private AbstractJob searchJob;
    private int searchSerial;

    /**
     * @param selectedNode node selected in Object Explorer: its connection is searched, and its schema or database is
     *                     offered as the scope
     * @param revealer     selects a result in Object Explorer
     */
    ObjectSearchPanel(@Nullable DBNNode selectedNode, @NotNull Consumer<DBNNode> revealer) {
        super(new BorderLayout());
        this.revealer = revealer;
        DBPDataSourceContainer selectedContainer = selectedNode instanceof DBNDatabaseNode databaseNode ? databaseNode.getDataSourceContainer() : null;
        scopeObject = findScope(selectedNode);

        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.search_name_placeholder);
        nameField.addActionListener(e -> startSearch());
        searchButton.addActionListener(e -> {
            if (searchJob != null) {
                stopSearch();
            } else {
                startSearch();
            }
        });
        caseSensitiveCheck.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), UIScale.scale(12), UIScale.scale(6), UIScale.scale(12)));
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(UIScale.scale(4), 0, UIScale.scale(4), UIScale.scale(10));
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.weightx = 1;
        fieldConstraints.anchor = GridBagConstraints.WEST;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), 0);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        nameRow.setOpaque(false);
        nameRow.add(nameField);
        nameRow.add(matchCombo);
        nameRow.add(caseSensitiveCheck);
        nameRow.add(searchButton);
        form.add(new JLabel(ConnectionMessages.search_label_name), labelConstraints);
        form.add(nameRow, fieldConstraints);

        JPanel typesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(10), 0));
        typesRow.setOpaque(false);
        for (TypeGroup group : TypeGroup.values()) {
            JCheckBox check = new JCheckBox(group.label, group != TypeGroup.OTHER);
            check.setOpaque(false);
            typeChecks.put(group, check);
        }
        // Reading order: tables first
        for (TypeGroup group : List.of(TypeGroup.TABLES, TypeGroup.VIEWS, TypeGroup.PROCEDURES, TypeGroup.COLUMNS, TypeGroup.OTHER)) {
            typesRow.add(typeChecks.get(group));
        }
        form.add(new JLabel(ConnectionMessages.search_label_types), labelConstraints);
        form.add(typesRow, fieldConstraints);

        JPanel connectionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(10), 0));
        connectionsRow.setOpaque(false);
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                // No icon: on a check box it would replace the check mark
                JCheckBox check = new JCheckBox(container.getName(),
                    selectedContainer == null ? container.isConnected() : container == selectedContainer);
                check.setOpaque(false);
                check.setToolTipText(container.isConnected() ? container.getDriver().getName()
                    : NLS.bind(ConnectionMessages.search_connects_when_searching, container.getDriver().getName()));
                connectionChecks.put(container, check);
                connectionsRow.add(check);
            }
        }
        form.add(new JLabel(ConnectionMessages.search_label_connections), labelConstraints);
        form.add(connectionsRow, fieldConstraints);

        if (scopeObject != null) {
            scopeCheck.setText(NLS.bind(ConnectionMessages.search_only_in, DBUtils.getObjectFullName(scopeObject, DBPEvaluationContext.UI),
                scopeObject.getDataSource().getContainer().getName()));
            scopeCheck.setSelected(true);
            scopeCheck.setOpaque(false);
            form.add(new JLabel(ConnectionMessages.search_label_scope), labelConstraints);
            form.add(scopeCheck, fieldConstraints);
        }

        resultTable.setRowSorter(new TableRowSorter<>(resultModel));
        resultTable.setShowGrid(false);
        resultTable.setIntercellSpacing(new Dimension(0, 0));
        resultTable.setFillsViewportHeight(true);
        resultTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(220));
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(120));
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(360));
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(160));
        resultTable.setToolTipText(ConnectionMessages.search_result_tip);
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    revealSelected();
                }
            }
        });
        resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "chromite.reveal"); //$NON-NLS-1$ //$NON-NLS-2$
        resultTable.getActionMap().put("chromite.reveal", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                revealSelected();
            }
        });
        JScrollPane resultScroll = new JScrollPane(resultTable);
        resultScroll.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        add(form, BorderLayout.NORTH);
        add(resultScroll, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(nameField::requestFocusInWindow);
    }

    /**
     * The schema or database (struct container) of the selected node or of its nearest ancestor, as the SWT search page.
     */
    @Nullable
    private static DBSObject findScope(@Nullable DBNNode node) {
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBSWrapper wrapper && wrapper.getObject() instanceof DBSStructContainer container
                && !(wrapper.getObject() instanceof DBPDataSource)) {
                return (DBSObject) container;
            }
        }
        return null;
    }

    private void startSearch() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            nameField.requestFocusInWindow();
            return;
        }
        Set<TypeGroup> groups = EnumSet.noneOf(TypeGroup.class);
        typeChecks.forEach((group, check) -> {
            if (check.isSelected()) {
                groups.add(group);
            }
        });
        List<DBPDataSourceContainer> containers = new ArrayList<>();
        connectionChecks.forEach((container, check) -> {
            if (check.isSelected()) {
                containers.add(container);
            }
        });
        if (groups.isEmpty() || containers.isEmpty()) {
            statusLabel.setText(groups.isEmpty() ? ConnectionMessages.search_select_type : ConnectionMessages.search_select_connection);
            return;
        }
        MatchType matchType = (MatchType) matchCombo.getSelectedItem();
        String mask = switch (matchType == null ? MatchType.CONTAINS : matchType) {
            case CONTAINS -> (name.startsWith("%") ? "" : "%") + name + (name.endsWith("%") ? "" : "%"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case STARTS_WITH -> name.endsWith("%") ? name : name + "%"; //$NON-NLS-1$ //$NON-NLS-2$
            case LIKE -> name;
        };
        boolean caseSensitive = caseSensitiveCheck.isSelected();
        DBSObject scope = scopeObject != null && scopeCheck.isSelected() ? scopeObject : null;

        int serial = ++searchSerial;
        resultModel.clear();
        searchButton.setText(ConnectionMessages.search_button_stop);
        statusLabel.setText(ConnectionMessages.search_searching);
        searchJob = new AbstractJob("Find objects") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> problems = new ArrayList<>();
                for (DBPDataSourceContainer container : containers) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    try {
                        List<Result> results = search(monitor, container, mask, matchType == MatchType.LIKE, caseSensitive, groups,
                            scope != null && scope.getDataSource().getContainer() == container ? scope : null);
                        SwingUtilities.invokeLater(() -> {
                            if (serial == searchSerial) {
                                resultModel.add(results);
                                statusLabel.setText(NLS.bind(ConnectionMessages.search_searching_count, resultModel.getRowCount()));
                            }
                        });
                    } catch (Throwable e) {
                        log.debug("Error searching " + container.getName(), e);
                        problems.add(container.getName() + ": " + CommonUtils.notEmpty(e.getMessage()).replaceAll("\\s+", " ")); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                boolean canceled = monitor.isCanceled();
                SwingUtilities.invokeLater(() -> {
                    if (serial == searchSerial) {
                        searchFinished(canceled, problems);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        searchJob.schedule();
    }

    @NotNull
    private static List<Result> search(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull String mask,
        boolean likeCondition,
        boolean caseSensitive,
        @NotNull Set<TypeGroup> groups,
        @Nullable DBSObject scope
    ) throws DBException {
        if (!container.isConnected()) {
            DBUtils.initDataSource(monitor, container, null);
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null || !container.isConnected()) {
            // Shown in the status line, like the next one
            throw new DBException(ConnectionMessages.search_not_connected);
        }
        DBSStructureAssistant<?> assistant = DBUtils.getAdapter(DBSStructureAssistant.class, dataSource);
        if (assistant == null) {
            throw new DBException(NLS.bind(ConnectionMessages.search_not_supported, container.getDriver().getName()));
        }
        List<DBSObjectType> types = new ArrayList<>();
        for (DBSObjectType type : assistant.getSupportedObjectTypes()) {
            if (groups.contains(TypeGroup.of(type))) {
                types.add(type);
            }
        }
        if (types.isEmpty()) {
            return List.of();
        }
        DBSStructureAssistant.ObjectsSearchParams params = new DBSStructureAssistant.ObjectsSearchParams(
            types.toArray(new DBSObjectType[0]), mask);
        params.setParentObject(scope);
        params.setCaseSensitive(caseSensitive);
        params.setMaxResults(MAX_RESULTS);
        params.setGlobalSearch(true);
        params.setLikeCondition(likeCondition);

        DBNModel navigatorModel = DBWorkbench.getPlatform().getNavigatorModel();
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<DBSObjectReference> references = ((DBSStructureAssistant) assistant).findObjectsByMask(
            monitor, DBUtils.getDefaultContext(dataSource, true), params);
        List<Result> results = new ArrayList<>();
        for (DBSObjectReference reference : references) {
            if (monitor.isCanceled()) {
                break;
            }
            try {
                DBSObject object = reference.resolveObject(monitor);
                DBNDatabaseNode node = navigatorModel.getNodeByObject(monitor, object, false);
                if (node != null) {
                    // The node's type (e.g. "View"): the search type can be broader (PostgreSQL finds views as tables)
                    String type = CommonUtils.isEmpty(node.getNodeTypeLabel()) ? reference.getObjectType().getTypeName() : node.getNodeTypeLabel();
                    results.add(new Result(node, CommonUtils.notEmpty(type),
                        DBUtils.getObjectFullName(object, DBPEvaluationContext.UI), container.getName()));
                }
            } catch (DBException e) {
                log.debug("Can't resolve " + reference.getName(), e);
            }
        }
        return results;
    }

    private void stopSearch() {
        if (searchJob != null) {
            searchJob.cancel();
        }
    }

    private void searchFinished(boolean canceled, @NotNull List<String> problems) {
        searchJob = null;
        searchButton.setText(ConnectionMessages.search_button);
        int count = resultModel.getRowCount();
        String text = count == 0 ? ConnectionMessages.search_none_found
            : NLS.bind(count == 1 ? ConnectionMessages.search_found_one : ConnectionMessages.search_found_many, count);
        if (canceled) {
            text = NLS.bind(ConnectionMessages.search_stopped, text);
        }
        if (!problems.isEmpty()) {
            text += " · " + String.join("; ", problems);
        }
        statusLabel.setText(text);
        statusLabel.setToolTipText(problems.isEmpty() ? null : String.join("\n", problems));
    }

    private void revealSelected() {
        int viewRow = resultTable.getSelectedRow();
        if (viewRow >= 0) {
            revealer.accept(resultModel.get(resultTable.convertRowIndexToModel(viewRow)).node());
        }
    }

    @NotNull
    @Override
    public String getTitle() {
        return ConnectionMessages.search_title;
    }

    @NotNull
    @Override
    public String getToolTip() {
        return ConnectionMessages.search_tooltip;
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("search"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        stopSearch();
        searchSerial++;
    }

    private static final class ResultModel extends AbstractTableModel {
        private static final String[] COLUMNS = {ConnectionMessages.search_column_name, ConnectionMessages.search_column_type,
            ConnectionMessages.search_column_full_name, ConnectionMessages.search_column_connection};
        private final List<Result> results = new ArrayList<>();

        void clear() {
            results.clear();
            fireTableDataChanged();
        }

        void add(@NotNull List<Result> added) {
            if (added.isEmpty()) {
                return;
            }
            int first = results.size();
            results.addAll(added);
            fireTableRowsInserted(first, results.size() - 1);
        }

        @NotNull
        Result get(int row) {
            return results.get(row);
        }

        @Override
        public int getRowCount() {
            return results.size();
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
        public Object getValueAt(int row, int column) {
            Result result = results.get(row);
            return switch (column) {
                case 0 -> result.node().getNodeDisplayName();
                case 1 -> result.type();
                case 2 -> result.fullName();
                default -> result.connection();
            };
        }
    }

    /**
     * The name column has the object's navigator icon.
     */
    private final class ResultRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(6)));
            if (column == 0) {
                setIcon(NodeIcons.getNodeIcon(resultModel.get(table.convertRowIndexToModel(row)).node(), false));
            } else {
                setIcon(null);
            }
            return this;
        }
    }
}
