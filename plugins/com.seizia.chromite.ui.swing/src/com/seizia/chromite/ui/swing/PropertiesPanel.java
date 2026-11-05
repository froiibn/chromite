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
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBValueFormatting;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSWrapper;
import org.jkiss.dbeaver.runtime.properties.PropertyCollector;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Properties 도구 창: Object Explorer에서 선택한 노드의 이름, 종류, 설명과 객체 속성을 보여 준다.
 *
 * 설계: SWT 버전의 속성 보기와 같은 모델을 쓴다({@code NavigatorAdapterFactory}의 {@code IPropertySource} 변환).
 * 노드가 감싼 객체({@link DBSWrapper#getObject()}, 아니면 노드 자체)가 {@link DBPPropertySource}면 그것을,
 * 아니면 {@link PropertyCollector}로 {@code @Property} 게터를 모은다. 둘 다 UI에 의존하지 않는다.
 * <ul>
 *     <li>속성 값 읽기는 게으른 속성(DB 조회가 필요한 값)을 포함하므로 백그라운드 Job에서 {@link DBRProgressMonitor}를 넘겨 읽는다.
 *     선택이 바뀌면 이전 Job을 취소하고, 늦게 끝난 이전 결과는 번호로 걸러 버린다.</li>
 *     <li>읽기 전용이다. 속성 편집은 객체 편집기 단계에서 다룬다.</li>
 *     <li>값은 객체면 이름, 배열·컬렉션이면 쉼표로 이은 이름, 나머지는 {@link DBValueFormatting}의 기본 표시 문자열로 보여 준다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 */
final class PropertiesPanel extends JPanel {

    private static final Log log = Log.getLog(PropertiesPanel.class);

    private final JLabel nameLabel = new JLabel();
    private final JLabel typeLabel = new JLabel();
    private final JLabel descriptionLabel = new JLabel();
    private final PropertyTableModel tableModel = new PropertyTableModel();
    private final JTable table = new JTable(tableModel);

    private int loadSerial;
    @Nullable
    private AbstractJob loadJob;

    PropertiesPanel() {
        super(new BorderLayout());

        nameLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4"); //$NON-NLS-1$
        typeLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        typeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        JPanel headerPanel = new JPanel(new GridLayout(0, 1, 0, 2));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        headerPanel.add(nameLabel);
        headerPanel.add(typeLabel);
        headerPanel.add(descriptionLabel);

        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new PropertyCellRenderer());
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; showVerticalLines: true"); //$NON-NLS-1$

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(headerPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        showNode(null);
    }

    /**
     * Shows the properties of a navigator node, or clears the panel for {@code null}.
     */
    void showNode(@Nullable DBNNode node) {
        int serial = ++loadSerial;
        if (loadJob != null) {
            loadJob.cancel();
            loadJob = null;
        }
        if (node == null) {
            nameLabel.setText("No selection");
            typeLabel.setText(" ");
            descriptionLabel.setText(" ");
            tableModel.setRows(List.of());
            return;
        }
        nameLabel.setText(CommonUtils.notEmpty(node.getNodeDisplayName()));
        typeLabel.setText(CommonUtils.notEmpty(node.getNodeTypeLabel()));
        String description = node.getNodeDescription();
        descriptionLabel.setText(CommonUtils.isEmpty(description) ? " " : description);
        descriptionLabel.setToolTipText(CommonUtils.isEmpty(description) ? null : description);
        tableModel.setRows(List.of(new PropertyRow("Loading…", "", null, false))); //$NON-NLS-1$

        loadJob = new AbstractJob("Read properties of " + node.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<PropertyRow> rows;
                try {
                    rows = readProperties(monitor, node);
                } catch (Throwable e) {
                    log.debug("Error reading properties of " + node.getNodeDisplayName(), e);
                    rows = List.of(new PropertyRow("Error", CommonUtils.getAllExceptionMessages(e), null, false));
                }
                if (!monitor.isCanceled()) {
                    List<PropertyRow> finalRows = rows;
                    SwingUtilities.invokeLater(() -> {
                        if (serial == loadSerial) {
                            tableModel.setRows(finalRows);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.schedule();
    }

    /**
     * Collects the property rows, grouped by category in the order the categories first appear.
     */
    @NotNull
    private static List<PropertyRow> readProperties(@NotNull DBRProgressMonitor monitor, @NotNull DBNNode node) {
        Object object = node instanceof DBSWrapper wrapper && wrapper.getObject() != null ? wrapper.getObject() : node;
        DBPPropertySource propertySource;
        if (object instanceof DBPPropertySource ownSource) {
            propertySource = ownSource;
        } else {
            PropertyCollector collector = new PropertyCollector(node, object, false);
            collector.collectProperties();
            propertySource = collector;
        }

        Map<String, List<PropertyRow>> categories = new LinkedHashMap<>();
        for (DBPPropertyDescriptor property : propertySource.getProperties()) {
            if (monitor.isCanceled()) {
                break;
            }
            if (property.isHidden()) {
                continue;
            }
            Object value = propertySource.getPropertyValue(monitor, property.getId());
            categories.computeIfAbsent(CommonUtils.notEmpty(property.getCategory()), c -> new ArrayList<>())
                .add(new PropertyRow(property.getDisplayName(), formatValue(value), property.getDescription(), false));
        }

        List<PropertyRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<PropertyRow>> category : categories.entrySet()) {
            // Uncategorized properties come first without a heading
            if (!category.getKey().isEmpty()) {
                rows.add(new PropertyRow(category.getKey(), "", null, true));
            }
            rows.addAll(category.getValue());
        }
        if (rows.isEmpty()) {
            rows.add(new PropertyRow("No properties", "", null, false));
        }
        return rows;
    }

    @NotNull
    private static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof DBPNamedObject namedObject) {
            return CommonUtils.notEmpty(namedObject.getName());
        }
        if (value instanceof Object[] array) {
            return Stream.of(array).map(PropertiesPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PropertiesPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        return CommonUtils.notEmpty(DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI));
    }

    private record PropertyRow(@NotNull String name, @NotNull String value, @Nullable String description, boolean category) {
    }

    private static final class PropertyTableModel extends AbstractTableModel {
        private List<PropertyRow> rows = List.of();

        void setRows(@NotNull List<PropertyRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @NotNull
        PropertyRow getRow(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            PropertyRow row = rows.get(rowIndex);
            return columnIndex == 0 ? row.name() : row.value();
        }
    }

    /**
     * Category rows are bold; property names and values show their description / full value as a tooltip.
     */
    private static final class PropertyCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table,
            Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column
        ) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            PropertyRow propertyRow = ((PropertyTableModel) table.getModel()).getRow(table.convertRowIndexToModel(row));
            Font font = table.getFont();
            setFont(propertyRow.category() ? font.deriveFont(Font.BOLD) : font);
            String tooltip = column == 0 ? propertyRow.description() : propertyRow.value();
            setToolTipText(CommonUtils.isEmpty(tooltip) ? null : tooltip);
            return this;
        }
    }
}
