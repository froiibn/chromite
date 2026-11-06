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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBValueFormatting;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.exec.plan.DBCPlan;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanNode;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanNodeKind;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.properties.ObjectAttributeDescriptor;
import org.jkiss.dbeaver.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.dbeaver.runtime.properties.PropertyCollector;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution plan tab of a query window: one tree of plan nodes per statement, with the node's properties beside it.
 *
 * 설계: SWT 편집기의 계획 보기({@code ExplainPlanViewer}의 단순 보기 {@code PlanNodesTree}, {@code SQLPlanTreeViewer})를 옮겼다.
 * <ul>
 *     <li>계획은 DBeaver 계획 모델({@link DBCPlan}, {@link DBCPlanNode})이라 DB마다 다른 화면을 만들지 않는다. 노드는
 *     {@code DBCPlan.getPlanNodes(keepOriginal)}로 받는다(SWT와 같은 옵션: 서버가 만든 구조를 그대로).</li>
 *     <li>열은 SWT 목록과 같이 노드 클래스의 {@code @Property(viewable = true)} 속성이다({@link ObjectAttributeDescriptor#extractAnnotations}).
 *     첫 열은 트리(노드 종류)이고, 같은 값을 다시 보이지 않도록 {@code nodeType} 속성은 뺀다. 문장마다 맨 위에
 *     "Query N" 행을 두고(SQL Server 계획 도구처럼 일괄 처리 대비 비용을 함께 보이는 문장 행) 그 아래에 계획 트리를 둔다.</li>
 *     <li>트리 표는 Swing에 없으므로 {@link JTable}에 펼친 행만 넣고 첫 열에 들여쓰기·펼침 화살표를 그린다. 첫 열 화살표를 누르거나
 *     행을 두 번 누르면 접고 편다. 처음에는 모두 펼친다(SWT {@code expandToLevel(10)}과 같은 의도).</li>
 *     <li>SWT와 같이 테이블 전체 읽기({@link DBCPlanNodeKind#TABLE_SCAN})는 오류 색, 인덱스 전체 읽기({@link DBCPlanNodeKind#INDEX_SCAN})는
 *     경고 색으로 행 바탕을 옅게 칠한다(테마의 상태 색을 섞어 두 테마 모두에서 읽히게).</li>
 *     <li>오른쪽 상세 표는 선택한 노드의 모든 속성이다. 노드가 {@link DBPPropertySource}이면(PostgreSQL 노드의 계획 속성 전체) 그것을,
 *     아니면 {@link PropertyCollector}로 {@code @Property} 게터를 모은다(Properties 창과 같은 방법).</li>
 *     <li>그래픽 계획 도면은 아직 없다(docs/chromite/swing-sql-editor-plus.md "미룬 일").</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 */
final class ExecutionPlanView {

    private static final Log log = Log.getLog(ExecutionPlanView.class);

    private static final String NODE_TYPE_PROPERTY = "nodeType"; //$NON-NLS-1$
    private static final int INDENT = 16;

    /**
     * The plan of one statement, or why it could not be made.
     *
     * @param query  statement text
     * @param plan   the plan, or null when it failed
     * @param error  error message when there is no plan
     * @param actual true for a plan with run-time values (the statement was executed)
     */
    record Entry(@NotNull String query, @Nullable DBCPlan plan, @Nullable String error, boolean actual) {
    }

    // Declared before the tables: their models read them while the tables are created
    private final List<Entry> entries = new ArrayList<>();
    // Visible rows of the tree; nodes and entries are compared by identity
    private final List<Row> rows = new ArrayList<>();
    private final Set<Object> collapsed = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<ObjectPropertyDescriptor> columns = List.of();
    private final JPanel component = new JPanel(new BorderLayout());
    private final JLabel headerLabel = new JLabel();
    private final PlanTableModel tableModel = new PlanTableModel();
    private final JTable table = new JTable(tableModel);
    private final DetailsModel detailsModel = new DetailsModel();
    private final JTable detailsTable = new JTable(detailsModel);

    ExecutionPlanView() {
        headerLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10)));
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Row item = rows.get(row);
                Rectangle cell = table.getCellRect(row, 0, false);
                int arrowEnd = cell.x + UIScale.scale(4 + INDENT * (item.depth + 1));
                if ((e.getClickCount() == 1 && table.columnAtPoint(e.getPoint()) == 0 && e.getX() < arrowEnd) || e.getClickCount() == 2) {
                    toggle(item);
                }
            }
        });

        detailsTable.setFillsViewportHeight(true);
        detailsTable.setShowVerticalLines(false);
        detailsTable.getTableHeader().setReorderingAllowed(false);
        detailsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, false, row, column);
                setToolTipText(value == null || value.toString().isEmpty() ? null : value.toString());
                return this;
            }
        });

        JScrollPane treeScroll = new JScrollPane(table);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane detailsScroll = new JScrollPane(detailsTable);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        // The divider starts from the preferred widths: most of the room for the tree
        treeScroll.setPreferredSize(new Dimension(UIScale.scale(900), UIScale.scale(200)));
        detailsScroll.setPreferredSize(new Dimension(UIScale.scale(320), UIScale.scale(200)));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailsScroll);
        split.setResizeWeight(0.72);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        component.add(headerLabel, BorderLayout.NORTH);
        component.add(split, BorderLayout.CENTER);
        clear();
    }

    @NotNull
    JComponent getComponent() {
        return component;
    }

    boolean hasContent() {
        return !entries.isEmpty();
    }

    /**
     * Removes the plans and shows a message instead (e.g. while the plan is being made).
     */
    void clear(@NotNull String message) {
        entries.clear();
        collapsed.clear();
        columns = List.of();
        headerLabel.setText(message);
        rebuild();
    }

    void clear() {
        clear("No execution plan. Display the estimated plan (Ctrl+L) or include the actual plan (Ctrl+M) and execute.");
    }

    void addEntry(@NotNull Entry entry) {
        entries.add(entry);
        if (entry.plan() != null) {
            collectColumns();
        }
        updateHeader();
        rebuild();
        if (table.getSelectedRow() < 0 && !rows.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
    }

    private void updateHeader() {
        boolean actual = entries.stream().anyMatch(Entry::actual);
        long failed = entries.stream().filter(e -> e.plan() == null).count();
        // A plan can hold several statements (a SQL Server batch has one plan with a root node per statement)
        long statements = failed;
        for (Entry entry : entries) {
            if (entry.plan() != null) {
                statements += Math.max(1, getRootNodes(entry.plan()).size());
            }
        }
        String text = (actual ? "Actual execution plan" : "Estimated execution plan") + " - "
            + statements + (statements == 1 ? " statement" : " statements");
        if (failed > 0) {
            text += ", " + failed + " without a plan";
        }
        headerLabel.setText(text);
    }

    /**
     * Viewable properties of every node class in the plans, in property order (SWT list columns).
     */
    private void collectColumns() {
        Map<Class<?>, Boolean> classes = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.plan() != null) {
                for (DBCPlanNode node : getRootNodes(entry.plan())) {
                    collectClasses(node, classes);
                }
            }
        }
        Map<String, ObjectPropertyDescriptor> byId = new LinkedHashMap<>();
        for (Class<?> nodeClass : classes.keySet()) {
            for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, nodeClass, null, null)) {
                if (property.isViewable() && !NODE_TYPE_PROPERTY.equals(property.getId())) {
                    byId.putIfAbsent(property.getId(), property);
                }
            }
        }
        List<ObjectPropertyDescriptor> newColumns = new ArrayList<>(byId.values());
        if (!newColumns.equals(columns)) {
            columns = newColumns;
            tableModel.fireTableStructureChanged();
            sizeColumns();
        }
    }

    private static void collectClasses(@NotNull DBCPlanNode node, @NotNull Map<Class<?>, Boolean> classes) {
        classes.putIfAbsent(node.getClass(), Boolean.TRUE);
        for (DBCPlanNode child : CommonUtils.safeCollection(node.getNested())) {
            collectClasses(child, classes);
        }
    }

    private void sizeColumns() {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            // The columns share the width of the view in these proportions; the tree column keeps room for the indentation
            column.setPreferredWidth(UIScale.scale(i == 0 ? 320 : i == columns.size() ? 200 : 110));
            column.setMinWidth(UIScale.scale(i == 0 ? 200 : 60));
        }
    }

    @NotNull
    private static List<? extends DBCPlanNode> getRootNodes(@NotNull DBCPlan plan) {
        // Keep the server's own node structure, as the SWT plan tree does
        return plan.getPlanNodes(Collections.singletonMap(DBCPlan.OPTION_KEEP_ORIGINAL, true));
    }

    //////////////////////////////////////////////////////////
    // Tree rows

    private void toggle(@NotNull Row row) {
        if (!row.expandable) {
            return;
        }
        if (!collapsed.remove(row.item)) {
            collapsed.add(row.item);
        }
        Object selected = table.getSelectedRow() >= 0 ? rows.get(table.getSelectedRow()).item : null;
        rebuild();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item == selected) {
                table.getSelectionModel().setSelectionInterval(i, i);
                break;
            }
        }
    }

    private void rebuild() {
        rows.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            List<? extends DBCPlanNode> roots = entry.plan() == null ? List.of() : getRootNodes(entry.plan());
            rows.add(new Row(entry, 0, !roots.isEmpty(), i + 1));
            if (!collapsed.contains(entry)) {
                for (DBCPlanNode root : roots) {
                    addNodeRows(root, 1);
                }
            }
        }
        tableModel.fireTableDataChanged();
        showDetails();
    }

    private void addNodeRows(@NotNull DBCPlanNode node, int depth) {
        Collection<? extends DBCPlanNode> nested = node.getNested();
        boolean expandable = !CommonUtils.isEmpty(nested);
        rows.add(new Row(node, depth, expandable, 0));
        if (expandable && !collapsed.contains(node)) {
            for (DBCPlanNode child : nested) {
                addNodeRows(child, depth + 1);
            }
        }
    }

    /**
     * A visible row: a statement ({@link Entry}) or a plan node.
     *
     * @param number one-based statement number for statement rows
     */
    private record Row(@NotNull Object item, int depth, boolean expandable, int number) {
    }

    @NotNull
    private String getTreeText(@NotNull Row row) {
        if (row.item instanceof Entry entry) {
            String firstLine = entry.query().strip().lines().findFirst().orElse("");
            String text = "Query " + row.number + ": " + CommonUtils.truncateString(firstLine, 200);
            return entry.error() != null ? text + " - " + entry.error() : text;
        }
        DBCPlanNode node = (DBCPlanNode) row.item;
        String type = node.getNodeType();
        return CommonUtils.isEmpty(type) ? CommonUtils.notEmpty(node.getNodeName()) : type;
    }

    @Nullable
    private Object getValue(@NotNull Row row, int column) {
        if (column == 0) {
            return getTreeText(row);
        }
        if (!(row.item instanceof DBCPlanNode node)) {
            return null;
        }
        ObjectPropertyDescriptor property = columns.get(column - 1);
        if (!property.getGetter().getDeclaringClass().isInstance(node)) {
            return null;
        }
        try {
            return formatValue(property.readValue(node, null, true));
        } catch (Exception e) {
            log.debug("Can't read plan node property " + property.getId(), e);
            return null;
        }
    }

    @NotNull
    private static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof DBPNamedObject named) {
            return CommonUtils.notEmpty(named.getName());
        }
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e15) {
            return String.valueOf(number.longValue());
        }
        return CommonUtils.notEmpty(DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI));
    }

    private final class PlanTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size() + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Operation" : columns.get(column - 1).getDisplayName();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return getValue(rows.get(rowIndex), columnIndex);
        }
    }

    /**
     * Tree column with indentation, arrow and icon; scan rows tinted; numbers right-aligned.
     */
    private final class CellRenderer extends DefaultTableCellRenderer {
        private Row row;
        private boolean treeColumn;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int rowIndex, int column) {
            // The renderer keeps colors set for a cell as its "unselected" colors: start from the table's colors again
            setForeground(null);
            setBackground(null);
            super.getTableCellRendererComponent(t, value, isSelected, false, rowIndex, column);
            row = rows.get(rowIndex);
            treeColumn = column == 0;
            setFont(t.getFont().deriveFont(row.item instanceof Entry ? Font.BOLD : Font.PLAIN));
            setToolTipText(value == null || value.toString().isEmpty() ? null : value.toString());
            setHorizontalAlignment(!treeColumn && isNumeric(value) ? RIGHT : LEFT);
            if (treeColumn) {
                setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4 + INDENT * (row.depth + 1)), 0, UIScale.scale(4)));
                setIcon(row.item instanceof Entry ? UiIcons.get("plan") : null); //$NON-NLS-1$
            } else {
                setIcon(null);
            }
            if (row.item instanceof Entry entry && entry.error() != null && !isSelected) {
                setForeground(UIManager.getColor("Chromite.status.error")); //$NON-NLS-1$
            }
            if (!isSelected && row.item instanceof DBCPlanNode node) {
                Color tint = switch (node.getNodeKind()) {
                    case TABLE_SCAN -> UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
                    case INDEX_SCAN -> UIManager.getColor("Chromite.status.warning"); //$NON-NLS-1$
                    default -> null;
                };
                if (tint != null) {
                    setBackground(blend(t.getBackground(), tint, 0.16f));
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (treeColumn && row != null && row.expandable) {
                // Expand arrow left of the text, in the indentation of the row
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
                    int size = UIScale.scale(4);
                    int cx = UIScale.scale(4 + INDENT * row.depth + INDENT / 2);
                    int cy = getHeight() / 2;
                    Polygon arrow = new Polygon();
                    if (collapsed.contains(row.item)) {
                        arrow.addPoint(cx - size / 2, cy - size);
                        arrow.addPoint(cx + size / 2 + 1, cy);
                        arrow.addPoint(cx - size / 2, cy + size);
                    } else {
                        arrow.addPoint(cx - size, cy - size / 2);
                        arrow.addPoint(cx + size, cy - size / 2);
                        arrow.addPoint(cx, cy + size / 2 + 1);
                    }
                    g2.fillPolygon(arrow);
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    private static boolean isNumeric(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != ',' && c != '-' && c != 'E' && c != 'e') {
                return false;
            }
        }
        return true;
    }

    @NotNull
    private static Color blend(@NotNull Color base, @NotNull Color tint, float amount) {
        return new Color(
            Math.round(base.getRed() + (tint.getRed() - base.getRed()) * amount),
            Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * amount),
            Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * amount));
    }

    //////////////////////////////////////////////////////////
    // Details

    private void showDetails() {
        int index = table.getSelectedRow();
        List<String[]> properties = new ArrayList<>();
        if (index >= 0 && index < rows.size()) {
            Object item = rows.get(index).item;
            if (item instanceof Entry entry) {
                properties.add(new String[]{"Statement", entry.query().strip()});
                if (entry.plan() != null) {
                    try {
                        String planQuery = entry.plan().getPlanQueryString();
                        if (!planQuery.equals(entry.query()) && !planQuery.equals(entry.query().strip())) {
                            properties.add(new String[]{"Plan query", planQuery});
                        }
                    } catch (Exception e) {
                        log.debug("Can't read the plan query", e);
                    }
                }
                properties.add(new String[]{"Plan", entry.actual() ? "Actual" : "Estimated"});
                if (entry.error() != null) {
                    properties.add(new String[]{"Error", entry.error()});
                }
            } else if (item instanceof DBCPlanNode node) {
                readNodeProperties(node, properties);
            }
        }
        detailsModel.setProperties(properties);
    }

    private static void readNodeProperties(@NotNull DBCPlanNode node, @NotNull List<String[]> properties) {
        DBPPropertySource source;
        if (node instanceof DBPPropertySource ownSource) {
            source = ownSource;
        } else {
            PropertyCollector collector = new PropertyCollector(node, false);
            collector.collectProperties();
            source = collector;
        }
        VoidProgressMonitor monitor = new VoidProgressMonitor();
        for (DBPPropertyDescriptor property : source.getProperties()) {
            if (property.isHidden()) {
                continue;
            }
            try {
                String value = formatValue(source.getPropertyValue(monitor, property.getId()));
                if (!value.isEmpty()) {
                    properties.add(new String[]{property.getDisplayName(), value});
                }
            } catch (Exception e) {
                log.debug("Can't read plan node property " + property.getId(), e);
            }
        }
        if (!CommonUtils.isEmpty(node.getNodeDescription())
            && properties.stream().noneMatch(p -> p[1].equals(node.getNodeDescription()))) {
            properties.add(new String[]{"Description", node.getNodeDescription()});
        }
    }

    private static final class DetailsModel extends AbstractTableModel {
        private List<String[]> properties = List.of();

        void setProperties(@NotNull List<String[]> properties) {
            this.properties = properties;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return properties.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Property" : "Value";
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return properties.get(rowIndex)[columnIndex];
        }
    }
}
