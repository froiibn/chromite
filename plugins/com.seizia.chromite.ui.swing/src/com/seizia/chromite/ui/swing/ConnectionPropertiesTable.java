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
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.meta.IPropertyValueListProvider;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.runtime.properties.PropertySourceCustom;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 연결 속성 표(드라이버 속성, 공급자 속성). 범주 · 이름 · 값 세 열의 편집 가능한 표다.
 *
 * 설계: 값은 모델 계층의 {@link PropertySourceCustom}에 그대로 둔다. SWT의 {@code ConnectionPropertiesControl}
 * ({@code PropertyTreeViewer})도 같은 객체를 쓰므로, 저장할 때 어떤 값이 남는지(원래 값 + 바꾼 값,
 * {@link PropertySourceCustom#getPropertyValues()})가 SWT 버전과 같다.
 * <ul>
 *     <li>값 편집기: 가능한 값 목록이 있으면 편집 가능한 콤보, 불리언이면 true/false 콤보, 나머지는 글자 입력.
 *     값을 지우면 기본값으로 되돌린다.</li>
 *     <li>사용자 속성: Add로 이름을 받아 사용자 속성({@link #USER_PROPERTIES_CATEGORY}) 범주에 더하고, 그 범주의 속성만 Remove로 지울 수 있다
 *     (SWT와 같은 규칙). 사용자 속성을 받지 않는 표(공급자 속성)에는 두 단추가 없다.</li>
 *     <li>암호 속성({@code password} 기능)과 이름에 password·token이 들어간 글자 속성은 값을 가린다(SWT {@code isHidePropertyValue}와
 *     같은 규칙에 암호 기능을 더함). 표 모델이 가린 글자를 내주므로 복사에도 실제 값이 가지 않고, 편집은 가린 입력 칸
 *     ({@link JPasswordField})이 속성의 실제 값을 받아서 한다.</li>
 * </ul>
 * 트리 대신 표를 쓴 것은 MVP 범위("간단한 편집 가능한 표")에 맞추기 위해서다. 범주는 첫 열로 보여 주고 범주 순으로 정렬한다.
 * 데이터 내보내기·가져오기 마법사도 형식(처리기) 속성을 같은 {@link PropertySourceCustom}으로 이 표에서 고친다(SWT도 두 곳 모두 {@code PropertyTreeViewer}).
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 데이터 전송: 처리기 속성 편집에도 씀 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 데이터 형식(Options &gt; Data Formats)의 형식 속성 편집에도 씀. 값이 바뀌면 알림({@link #addValueListener}, 견본 갱신)
 *   (docs/chromite/swing-options.md "Data Formats")
 * - 2026-11-08 암호 기능 속성도 가림, 가리기를 표 모델로 옮김(복사 보호), 비밀 값은 가린 입력 칸으로 편집
 *   (docs/chromite/password-handling.md)
 */
final class ConnectionPropertiesTable extends JPanel {

    /**
     * Category of the properties the user added; shown in the table only, never saved (SWT: a localized message too).
     */
    static final String USER_PROPERTIES_CATEGORY = ConnectionMessages.properties_user_category;

    private static final String[] COLUMNS = {
        ConnectionMessages.properties_column_category, ConnectionMessages.properties_column_property, ConnectionMessages.properties_column_value};
    private static final int VALUE_COLUMN = 2;

    private final boolean userPropertiesAllowed;
    private final PropertiesModel model = new PropertiesModel();
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");
    @Nullable
    private PropertySourceCustom source;
    private final List<DBPPropertyDescriptor> rows = new ArrayList<>();
    private Action addAction;
    private Action removeAction;
    private Action resetAction;

    ConnectionPropertiesTable(boolean userPropertiesAllowed) {
        super(new BorderLayout(0, 4));
        this.userPropertiesAllowed = userPropertiesAllowed;

        table = new JTable(model) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                return column == VALUE_COLUMN ? createValueEditor(rows.get(row)) : super.getCellEditor(row, column);
            }

            @Override
            public String getToolTipText(@NotNull java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                if (row < 0) {
                    return null;
                }
                DBPPropertyDescriptor property = rows.get(row);
                String description = property.getDescription();
                return CommonUtils.isEmpty(description) ? property.getId() : property.getId() + ": " + description;
            }
        };
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.setDefaultRenderer(Object.class, new ValueRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateActions());

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(createToolBar(), BorderLayout.SOUTH);
        updateActions();
    }

    /**
     * Shows the properties of a source; {@code null} shows the given message instead (e.g. while loading).
     */
    void setSource(@Nullable PropertySourceCustom source, @Nullable String message) {
        stopEditing();
        this.source = source;
        rows.clear();
        if (source != null) {
            for (DBPPropertyDescriptor property : source.getProperties()) {
                if (!property.isHidden()) {
                    rows.add(property);
                }
            }
            sortRows();
        }
        model.fireTableDataChanged();
        statusLabel.setText(CommonUtils.isEmpty(message) ? " " : message);
        // Long messages (driver errors) are cut off in the label
        statusLabel.setToolTipText(CommonUtils.isEmpty(message) ? null : message);
        updateActions();
    }

    @Nullable
    PropertySourceCustom getSource() {
        return source;
    }

    void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    /**
     * Called after a value is edited or reset, or another source is shown (e.g. to update a preview).
     */
    void addValueListener(@NotNull Runnable listener) {
        model.addTableModelListener(e -> listener.run());
    }

    @NotNull
    private JComponent createToolBar() {
        JPanel bar = new JPanel(new BorderLayout());
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        if (userPropertiesAllowed) {
            addAction = createAction(ConnectionMessages.common_add, ConnectionMessages.properties_add_tip, this::addUserProperty);
            removeAction = createAction(ConnectionMessages.common_remove, ConnectionMessages.properties_remove_tip, this::removeUserProperty);
            toolBar.add(addAction);
            toolBar.add(removeAction);
        }
        resetAction = createAction(ConnectionMessages.properties_reset, ConnectionMessages.properties_reset_tip, this::resetProperty);
        toolBar.add(resetAction);
        bar.add(toolBar, BorderLayout.WEST);
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        bar.add(statusLabel, BorderLayout.CENTER);
        return bar;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String description, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }

    private void updateActions() {
        DBPPropertyDescriptor selected = getSelectedProperty();
        if (addAction != null) {
            addAction.setEnabled(source != null);
            removeAction.setEnabled(selected != null && USER_PROPERTIES_CATEGORY.equals(selected.getCategory()));
        }
        resetAction.setEnabled(selected != null && source != null && source.isPropertySet(selected.getId()));
    }

    @Nullable
    private DBPPropertyDescriptor getSelectedProperty() {
        int row = table.getSelectedRow();
        return row >= 0 && row < rows.size() ? rows.get(row) : null;
    }

    private void addUserProperty() {
        if (source == null) {
            return;
        }
        stopEditing();
        String name = JOptionPane.showInputDialog(this, ConnectionMessages.properties_add_prompt, ConnectionMessages.properties_add_title,
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        String id = name.trim();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId().equals(id)) {
                // Existing property: select it instead of adding a duplicate
                selectRow(i);
                return;
            }
        }
        DBPPropertyDescriptor property = new PropertyDescriptor(
            USER_PROPERTIES_CATEGORY, id, id, null, String.class, false, null, null, true);
        source.addProperty(property);
        rows.add(property);
        sortRows();
        model.fireTableDataChanged();
        int row = rows.indexOf(property);
        selectRow(row);
        table.editCellAt(row, VALUE_COLUMN);
    }

    private void removeUserProperty() {
        DBPPropertyDescriptor property = getSelectedProperty();
        if (source == null || property == null || !USER_PROPERTIES_CATEGORY.equals(property.getCategory())) {
            return;
        }
        stopEditing();
        // PropertySourceCustom has no "remove property": dropping its value removes it from the saved values
        source.resetPropertyValueToDefault(property.getId());
        rows.remove(property);
        model.fireTableDataChanged();
        updateActions();
    }

    private void resetProperty() {
        DBPPropertyDescriptor property = getSelectedProperty();
        if (source == null || property == null) {
            return;
        }
        stopEditing();
        source.resetPropertyValueToDefault(property.getId());
        model.fireTableDataChanged();
        updateActions();
    }

    private void selectRow(int row) {
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
    }

    private void sortRows() {
        // User properties last, as in the SWT tree
        rows.sort(Comparator
            .comparing((DBPPropertyDescriptor p) -> USER_PROPERTIES_CATEGORY.equals(p.getCategory()))
            .thenComparing(p -> CommonUtils.notEmpty(p.getCategory()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(p -> CommonUtils.notEmpty(p.getDisplayName()), String.CASE_INSENSITIVE_ORDER));
    }

    @NotNull
    private TableCellEditor createValueEditor(@NotNull DBPPropertyDescriptor property) {
        Object[] possibleValues = null;
        if (property instanceof IPropertyValueListProvider<?> provider) {
            @SuppressWarnings("unchecked")
            IPropertyValueListProvider<Object> valueProvider = (IPropertyValueListProvider<Object>) provider;
            possibleValues = valueProvider.getPossibleValues(source);
        }
        Class<?> dataType = property.getDataType();
        if (ArrayUtils.isEmpty(possibleValues) && (dataType == Boolean.class || dataType == boolean.class)) {
            possibleValues = new Object[]{Boolean.TRUE.toString(), Boolean.FALSE.toString()};
        }
        if (!ArrayUtils.isEmpty(possibleValues)) {
            JComboBox<Object> comboBox = new JComboBox<>(possibleValues);
            comboBox.setEditable(true);
            return new DefaultCellEditor(comboBox);
        }
        if (isHiddenValue(property)) {
            // The table holds the masked text; the masked field edits the real value
            return new DefaultCellEditor(new JPasswordField()) {
                @Override
                public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
                    Object realValue = source == null ? null : source.getPropertyValue(null, property.getId());
                    return super.getTableCellEditorComponent(table, realValue, isSelected, row, column);
                }
            };
        }
        return new DefaultCellEditor(new JTextField());
    }

    /**
     * Whether the value is a secret: a {@code password} feature, or a text property whose name has "password" or
     * "token" in it.
     */
    private static boolean isHiddenValue(@NotNull DBPPropertyDescriptor property) {
        if (property.hasFeature(DBConstants.PROP_FEATURE_PASSWORD)) {
            return true;
        }
        Class<?> dataType = property.getDataType();
        if (dataType != null && !String.class.isAssignableFrom(dataType)) {
            return false;
        }
        String name = CommonUtils.notEmpty(property.getId()).toLowerCase(Locale.ENGLISH);
        return name.contains("password") || name.contains("token"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private final class PropertiesModel extends AbstractTableModel {
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
        public boolean isCellEditable(int row, int column) {
            return column == VALUE_COLUMN && source != null && rows.get(row).isEditable(source);
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBPPropertyDescriptor property = rows.get(row);
            return switch (column) {
                case 0 -> CommonUtils.notEmpty(property.getCategory());
                case 1 -> CommonUtils.notEmpty(property.getDisplayName());
                default -> source == null ? null : maskHiddenValue(property, source.getPropertyValue(null, property.getId()));
            };
        }

        /**
         * A secret's value is masked here, not in the renderer, so that it does not reach the clipboard (Copy reads
         * the model) either.
         */
        @Nullable
        private Object maskHiddenValue(@NotNull DBPPropertyDescriptor property, @Nullable Object value) {
            return value != null && isHiddenValue(property) && !value.toString().isEmpty() ? ObjectListTable.MASKED_VALUE : value;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != VALUE_COLUMN || source == null) {
                return;
            }
            DBPPropertyDescriptor property = rows.get(row);
            String text = value == null ? "" : value.toString();
            if (text.isEmpty()) {
                source.resetPropertyValueToDefault(property.getId());
            } else {
                source.setPropertyValue(null, property.getId(), text);
            }
            fireTableRowsUpdated(row, row);
            updateActions();
        }
    }

    /**
     * Marks properties that differ from their defaults in bold (secret values come masked from the model).
     */
    private final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            DBPPropertyDescriptor property = rows.get(table.convertRowIndexToModel(row));
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean set = source != null && source.isPropertySet(property.getId());
            setFont(table.getFont().deriveFont(set && column != 0 ? Font.BOLD : Font.PLAIN));
            return this;
        }
    }
}
