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

import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBValueFormatting;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 테이블 디자이너의 표: 객체 목록(행 = 열·키·인덱스, 열 = 속성)과 한 객체의 속성 시트(행 = 속성)를 같은 편집기로 보여 준다.
 *
 * 설계: SWT 목록 편집({@code ItemListControl.CellEditingSupport}, {@code PropertyEditorUtils.createPropertyEditor})처럼
 * 칸의 편집기를 속성 설명자({@link ObjectPropertyDescriptor})로 고른다. 불리언은 확인란, 값 목록이 있는 속성
 * ({@link ObjectPropertyDescriptor#getPossibleValues}: 데이터 형식, 열거형 등)은 콤보(직접 입력을 허용하면 입력 가능),
 * 그 밖에는 글자 칸이다. 콤보는 목록의 객체 자체를 값으로 넘기고, 입력한 글자는 {@code PropertySourceEditable}이 이름으로 객체를 찾는다.
 * 값은 표가 읽지 않는다: 디자이너가 작업 스레드에서 읽어 둔 값(게으른 속성 포함)을 모델이 갖고 있고,
 * 편집 결과도 모델이 디자이너에 넘겨 작업 스레드에서 명령으로 만든다({@link EditHandler}).
 * 모양은 결과 표와 같은 가로선(Chromite.Table.horizontalLineColor)이고, 저장하지 않은 새 객체·고친 객체의 행은 결과 표의 추가·변경 색이다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 */
final class DesignerTable extends JTable {

    private static final Log log = Log.getLog(DesignerTable.class);

    private static final int ROW_HEIGHT = 24;

    /**
     * Receives a cell edit (on the EDT); it makes the command in a background task.
     */
    interface EditHandler {
        void edit(@NotNull DBSObject object, @Nullable ObjectPropertyDescriptor property, @Nullable Object value);
    }

    /**
     * Model of a designer table: which object and property a cell shows.
     */
    abstract static class BindingModel extends AbstractTableModel {
        @Nullable
        abstract DBSObject getObject(int row, int column);

        /**
         * The property of a cell; null for cells that are not properties (names in the sheet, the key flag).
         */
        @Nullable
        abstract ObjectPropertyDescriptor getProperty(int row, int column);

        /**
         * The row is a new or changed object (row color).
         */
        abstract RowState getRowState(int row);
    }

    enum RowState {
        NORMAL,
        NEW,
        CHANGED
    }

    private final BindingModel model;
    private final DefaultTableCellRenderer textRenderer = new TextRenderer();

    DesignerTable(@NotNull BindingModel model) {
        super(model);
        this.model = model;
        setRowHeight(UIScale.scale(ROW_HEIGHT));
        setShowVerticalLines(true);
        setShowHorizontalLines(false);
        setIntercellSpacing(new Dimension(1, 0));
        setFillsViewportHeight(true);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        getTableHeader().setReorderingAllowed(false);
        if (getTableHeader().getDefaultRenderer() instanceof JLabel headerRenderer) {
            headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        }
        // Typing does not start editing (unbound shortcuts would open an editor first); F2, Enter or a double click does
        putClientProperty("JTable.autoStartsEdit", Boolean.FALSE); //$NON-NLS-1$
        putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Color color = UIManager.getColor("Chromite.Table.horizontalLineColor"); //$NON-NLS-1$
        if (color == null) {
            return;
        }
        g.setColor(color);
        for (int row = 0; row < getRowCount(); row++) {
            Rectangle cell = getCellRect(row, 0, true);
            int y = cell.y + cell.height - 1;
            g.drawLine(0, y, getWidth(), y);
        }
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        int modelRow = convertRowIndexToModel(row);
        int modelColumn = convertColumnIndexToModel(column);
        return model.isCellEditable(modelRow, modelColumn);
    }

    @Override
    public TableCellRenderer getCellRenderer(int row, int column) {
        Object value = getValueAt(row, column);
        if (value instanceof Boolean) {
            TableCellRenderer booleanRenderer = getDefaultRenderer(Boolean.class);
            return (table, cellValue, isSelected, hasFocus, r, c) -> {
                Component component = booleanRenderer.getTableCellRendererComponent(table, cellValue, isSelected, hasFocus, r, c);
                applyRowBackground(component, isSelected, r);
                return component;
            };
        }
        return textRenderer;
    }

    @Override
    public TableCellEditor getCellEditor(int row, int column) {
        int modelRow = convertRowIndexToModel(row);
        int modelColumn = convertColumnIndexToModel(column);
        Object value = model.getValueAt(modelRow, modelColumn);
        ObjectPropertyDescriptor property = model.getProperty(modelRow, modelColumn);
        DBSObject object = model.getObject(modelRow, modelColumn);
        if (value instanceof Boolean || property != null && property.isBoolean()) {
            JCheckBox checkBox = new JCheckBox();
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
            return new DefaultCellEditor(checkBox);
        }
        if (property != null && object != null) {
            Object[] possibleValues = null;
            try {
                possibleValues = property.getPossibleValues(object);
            } catch (Throwable e) {
                log.debug("Can't read the values of " + property.getId(), e);
            }
            if (possibleValues != null && possibleValues.length > 0) {
                return createComboEditor(possibleValues, property.allowCustomValue());
            }
        }
        JTextField field = new JTextField();
        DefaultCellEditor editor = new DefaultCellEditor(field) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object cellValue, boolean isSelected, int r, int c) {
                return super.getTableCellEditorComponent(table, formatValue(cellValue), isSelected, r, c);
            }
        };
        editor.setClickCountToStart(2);
        return editor;
    }

    /**
     * Combo editor of a property with a value list. A fixed list holds the property's objects (their names show);
     * an editable list holds the names, and a typed name that matches an item is given back as that item.
     */
    @NotNull
    private TableCellEditor createComboEditor(@NotNull Object[] possibleValues, boolean editable) {
        JComboBox<Object> combo = new JComboBox<>();
        Map<String, Object> byName = new HashMap<>();
        if (!editable) {
            combo.addItem(null);
        }
        for (Object item : possibleValues) {
            String name = formatValue(item);
            byName.put(name, item);
            combo.addItem(editable ? name : item);
        }
        combo.setEditable(editable);
        combo.setMaximumRowCount(20);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value == null ? " " : formatValue(value), index, isSelected, cellHasFocus);
            }
        });
        DefaultCellEditor editor = new DefaultCellEditor(combo) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
                if (editable) {
                    combo.setSelectedItem(formatValue(value));
                } else {
                    combo.setSelectedItem(value == null ? null : byName.getOrDefault(formatValue(value), value));
                }
                return combo;
            }

            @Override
            public Object getCellEditorValue() {
                Object value = editable ? combo.getEditor().getItem() : combo.getSelectedItem();
                if (value instanceof String text && byName.containsKey(text.trim())) {
                    return byName.get(text.trim());
                }
                return value;
            }
        };
        editor.setClickCountToStart(2);
        return editor;
    }

    /**
     * Display text of a property value, as the Properties window shows it.
     */
    @NotNull
    static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof DBPNamedObject namedObject) {
            return CommonUtils.notEmpty(namedObject.getName());
        }
        if (value instanceof Object[] array) {
            return Stream.of(array).map(DesignerTable::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(DesignerTable::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        return CommonUtils.notEmpty(DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI));
    }

    private void applyRowBackground(@NotNull Component component, boolean isSelected, int row) {
        if (isSelected) {
            return;
        }
        Color background = switch (model.getRowState(convertRowIndexToModel(row))) {
            case NEW -> UIManager.getColor("Chromite.Table.addedBackground"); //$NON-NLS-1$
            case CHANGED -> UIManager.getColor("Chromite.Table.changedBackground"); //$NON-NLS-1$
            case NORMAL -> null;
        };
        component.setBackground(background != null ? background : getBackground());
        if (component instanceof JComponent jComponent) {
            jComponent.setOpaque(true);
        }
    }

    private final class TextRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, formatValue(value), isSelected, hasFocus, row, column);
            int modelRow = convertRowIndexToModel(row);
            int modelColumn = convertColumnIndexToModel(column);
            boolean editable = model.isCellEditable(modelRow, modelColumn);
            Color secondary = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
            setForeground(isSelected || editable || secondary == null ? table.getForeground() : secondary);
            if (isSelected) {
                setForeground(UIManager.getColor("Table.selectionForeground")); //$NON-NLS-1$
            }
            setToolTipText(CommonUtils.isEmpty(getText()) ? null : getText());
            applyRowBackground(this, isSelected, row);
            return this;
        }
    }

    //////////////////////////////////////////////////////////
    // Models

    /**
     * Rows are objects, columns are properties; an optional first column is the primary key flag.
     */
    static final class GridModel extends BindingModel {
        static final String KEY_COLUMN = "PK"; //$NON-NLS-1$

        private final TableDesignSession session;
        private final EditHandler editHandler;
        private final boolean keyColumn;
        private List<DBSObject> objects = List.of();
        private List<ObjectPropertyDescriptor> properties = List.of();
        private Map<DBSObject, Object[]> values = Map.of();
        private Set<DBSObject> keyObjects = Set.of();
        private Set<?> editedObjects = Set.of();

        GridModel(@NotNull TableDesignSession session, @NotNull EditHandler editHandler, boolean keyColumn) {
            this.session = session;
            this.editHandler = editHandler;
            this.keyColumn = keyColumn;
        }

        void setContent(@NotNull List<DBSObject> objects, @NotNull List<ObjectPropertyDescriptor> properties,
                        @NotNull Map<DBSObject, Object[]> values, @NotNull Set<DBSObject> keyObjects, @NotNull Set<?> editedObjects) {
            boolean structureChanged = !properties.equals(this.properties);
            this.objects = objects;
            this.properties = properties;
            this.values = values;
            this.keyObjects = keyObjects;
            this.editedObjects = editedObjects;
            if (structureChanged) {
                fireTableStructureChanged();
            } else {
                fireTableDataChanged();
            }
        }

        @NotNull
        List<DBSObject> getObjects() {
            return objects;
        }

        private int getPropertyIndex(int column) {
            return keyColumn ? column - 1 : column;
        }

        @Override
        public int getRowCount() {
            return objects.size();
        }

        @Override
        public int getColumnCount() {
            return properties.size() + (keyColumn ? 1 : 0);
        }

        @Override
        public String getColumnName(int column) {
            int index = getPropertyIndex(column);
            return index < 0 ? KEY_COLUMN : properties.get(index).getDisplayName();
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBSObject object = objects.get(row);
            int index = getPropertyIndex(column);
            if (index < 0) {
                return keyObjects.contains(object);
            }
            Object[] rowValues = values.get(object);
            return rowValues == null ? null : rowValues[index];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            int index = getPropertyIndex(column);
            if (index < 0) {
                return !session.isReadOnly();
            }
            return session.isEditable(objects.get(row), properties.get(index));
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (sameValue(getValueAt(row, column), value)) {
                return;
            }
            editHandler.edit(objects.get(row), getProperty(row, column), value);
        }

        @Nullable
        @Override
        DBSObject getObject(int row, int column) {
            return row < objects.size() ? objects.get(row) : null;
        }

        @Nullable
        @Override
        ObjectPropertyDescriptor getProperty(int row, int column) {
            int index = getPropertyIndex(column);
            return index < 0 ? null : properties.get(index);
        }

        @Override
        RowState getRowState(int row) {
            DBSObject object = objects.get(row);
            if (!object.isPersisted()) {
                return RowState.NEW;
            }
            return editedObjects.contains(object) ? RowState.CHANGED : RowState.NORMAL;
        }
    }

    /**
     * Property sheet of one object: property name | value.
     */
    static final class SheetModel extends BindingModel {
        private final TableDesignSession session;
        private final EditHandler editHandler;
        @Nullable
        private DBSObject object;
        private List<ObjectPropertyDescriptor> properties = new ArrayList<>();
        private Object[] values = new Object[0];

        SheetModel(@NotNull TableDesignSession session, @NotNull EditHandler editHandler) {
            this.session = session;
            this.editHandler = editHandler;
        }

        void setContent(@Nullable DBSObject object, @NotNull List<ObjectPropertyDescriptor> properties, @NotNull Object[] values) {
            this.object = object;
            this.properties = properties;
            this.values = values;
            fireTableDataChanged();
        }

        @Nullable
        DBSObject getShownObject() {
            return object;
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
        public Object getValueAt(int row, int column) {
            return column == 0 ? properties.get(row).getDisplayName() : values[row];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1 && object != null && session.isEditable(object, properties.get(row));
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (object != null && column == 1 && !sameValue(values[row], value)) {
                editHandler.edit(object, properties.get(row), value);
            }
        }

        @Nullable
        @Override
        DBSObject getObject(int row, int column) {
            return column == 1 ? object : null;
        }

        @Nullable
        @Override
        ObjectPropertyDescriptor getProperty(int row, int column) {
            return column == 1 && row < properties.size() ? properties.get(row) : null;
        }

        @Override
        RowState getRowState(int row) {
            return RowState.NORMAL;
        }

        @Nullable
        String getDescription(int row) {
            return row >= 0 && row < properties.size() ? properties.get(row).getDescription() : null;
        }
    }

    /**
     * An edit that leaves the shown text as it was is not a change (the text editor returns a string for numbers).
     */
    private static boolean sameValue(@Nullable Object oldValue, @Nullable Object newValue) {
        return oldValue == newValue || formatValue(oldValue).equals(formatValue(newValue));
    }
}
