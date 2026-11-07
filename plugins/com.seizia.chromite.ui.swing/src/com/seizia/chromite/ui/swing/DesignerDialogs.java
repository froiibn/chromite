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

import com.seizia.chromite.ui.swing.design.TableDesignerDriver;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 테이블 디자이너에서 키·인덱스·외래 키를 추가할 때 띄우는 대화상자.
 *
 * 설계: SWT의 {@code EditConstraintPage}, {@code EditIndexPage}, {@code EditForeignKeyPage}(ui.editors.entity, SWT)가 받는 값과 같은 값을 받는다.
 * 대화상자는 고른 값만 돌려주고, 모델 객체를 만들고 채우는 일은 {@link TableDesignSession}(공통 API와 드라이버 구현)이 한다.
 * 키·인덱스 열은 체크한 순서가 키 열 순서다(# 열에 보임). 외래 키는 참조할 테이블과 그 고유 키를 고르면 키의 열마다 이 테이블의 열을 고른다
 * (참조 열이 customer.id면 customer_id, 없으면 다른 테이블의 같은 이름 열을 미리 고름). 참조 테이블의 키는 고를 때 작업 스레드에서 읽는다({@link ProgressDialog}).
 * 규칙 목록은 SWT 드라이버 설정기와 같은 다섯 가지(NO ACTION, CASCADE, RESTRICT, SET NULL, SET DEFAULT)다. 드라이버가 받지 않는 규칙
 * (Oracle의 RESTRICT·SET DEFAULT·ON UPDATE)은 드라이버 구현이 추가할 때 거절한다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 폼 도우미({@link #addRow}, {@link #showDialog}, {@link #error})를 패키지 안에 열어 새 개체 대화상자({@link NewObjectDialogs})도 씀
 *   (docs/chromite/swing-create-objects.md)
 */
final class DesignerDialogs {

    private static final DBSForeignKeyModifyRule[] MODIFY_RULES = {
        DBSForeignKeyModifyRule.NO_ACTION,
        DBSForeignKeyModifyRule.CASCADE,
        DBSForeignKeyModifyRule.RESTRICT,
        DBSForeignKeyModifyRule.SET_NULL,
        DBSForeignKeyModifyRule.SET_DEFAULT
    };

    /**
     * Index the user chose.
     */
    record IndexSpec(@NotNull String name, boolean unique, @NotNull List<TableDesignerDriver.IndexColumn> columns) {
    }

    /**
     * Foreign key the user chose; an empty name lets the manager generate one.
     */
    record ForeignKeySpec(@NotNull String name, @NotNull DBSEntityConstraint referencedKey, @NotNull DBSForeignKeyModifyRule deleteRule,
                          @NotNull DBSForeignKeyModifyRule updateRule, @NotNull List<TableDesignerDriver.ForeignKeyColumn> columns) {
    }

    private DesignerDialogs() {
    }

    //////////////////////////////////////////////////////////
    // Key

    /**
     * @param names suggested names by key type
     */
    @Nullable
    static TableDesignSession.KeySpec editKey(@NotNull Component owner, @NotNull List<DBSEntityAttribute> columns,
                                              @NotNull List<DBSEntityConstraintType> types, @NotNull Map<DBSEntityConstraintType, String> names) {
        JTextField nameField = new JTextField(24);
        JComboBox<DBSEntityConstraintType> typeCombo = new JComboBox<>(types.toArray(new DBSEntityConstraintType[0]));
        typeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBSEntityConstraintType type ? type.getLocalizedName() : value,
                    index, isSelected, cellHasFocus);
            }
        });
        ColumnChoiceModel columnModel = new ColumnChoiceModel(columns, false);
        JTable columnTable = createChoiceTable(columnModel);
        JTextArea expressionArea = new JTextArea(4, 40);
        expressionArea.setLineWrap(true);
        JScrollPane columnsScroll = new JScrollPane(columnTable);
        JScrollPane expressionScroll = new JScrollPane(expressionArea);
        CardLayout cards = new CardLayout();
        JPanel detail = new JPanel(cards);
        detail.add(columnsScroll, "columns"); //$NON-NLS-1$
        detail.add(expressionScroll, "expression"); //$NON-NLS-1$

        Runnable typeChanged = () -> {
            DBSEntityConstraintType type = (DBSEntityConstraintType) typeCombo.getSelectedItem();
            boolean check = type == DBSEntityConstraintType.CHECK;
            cards.show(detail, check ? "expression" : "columns"); //$NON-NLS-1$ //$NON-NLS-2$
            String oldSuggestion = names.values().stream().filter(n -> n.equals(nameField.getText())).findFirst().orElse(null);
            if (nameField.getText().isEmpty() || oldSuggestion != null) {
                nameField.setText(CommonUtils.notEmpty(names.get(type)));
            }
        };
        typeCombo.addActionListener(e -> typeChanged.run());
        typeChanged.run();

        JPanel form = new JPanel(new GridBagLayout());
        addRow(form, 0, ToolsMessages.designer_dialog_type, typeCombo);
        addRow(form, 1, ToolsMessages.designer_label_name, nameField);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(form, BorderLayout.NORTH);
        JLabel detailLabel = new JLabel(ToolsMessages.designer_dialog_key_columns);
        JPanel detailPanel = new JPanel(new BorderLayout(0, 4));
        detailPanel.add(detailLabel, BorderLayout.NORTH);
        detailPanel.add(detail, BorderLayout.CENTER);
        content.add(detailPanel, BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(480, 360));

        return showDialog(owner, ToolsMessages.designer_add_key, content, () -> {
            DBSEntityConstraintType type = (DBSEntityConstraintType) typeCombo.getSelectedItem();
            if (type == null) {
                return error(ToolsMessages.designer_dialog_error_key_type);
            }
            if (nameField.getText().trim().isEmpty()) {
                return error(ToolsMessages.designer_dialog_error_key_name);
            }
            if (type == DBSEntityConstraintType.CHECK) {
                if (expressionArea.getText().trim().isEmpty()) {
                    return error(ToolsMessages.designer_dialog_error_check);
                }
                return new TableDesignSession.KeySpec(nameField.getText().trim(), type, List.of(), expressionArea.getText().trim());
            }
            if (columnModel.getChosen().isEmpty()) {
                return error(ToolsMessages.designer_dialog_error_columns);
            }
            return new TableDesignSession.KeySpec(nameField.getText().trim(), type, columnModel.getChosen(), null);
        });
    }

    //////////////////////////////////////////////////////////
    // Index

    /**
     * @param names suggested index names by first column ({@code null} key: no column yet)
     */
    @Nullable
    static IndexSpec editIndex(@NotNull Component owner, @NotNull List<DBSEntityAttribute> columns, @NotNull Map<DBSEntityAttribute, String> names) {
        JTextField nameField = new JTextField(names.get(null), 24);
        JCheckBox uniqueCheck = new JCheckBox(ToolsMessages.designer_dialog_unique);
        ColumnChoiceModel columnModel = new ColumnChoiceModel(columns, true);
        columnModel.addTableModelListener(e -> {
            // The suggested name follows the first column until the user types another name
            List<DBSEntityAttribute> chosen = columnModel.getChosen();
            if (names.containsValue(nameField.getText())) {
                nameField.setText(names.get(chosen.isEmpty() ? null : chosen.getFirst()));
            }
        });
        JTable columnTable = createChoiceTable(columnModel);
        JComboBox<String> orderCombo = new JComboBox<>(new String[]{ColumnChoiceModel.ASC, ColumnChoiceModel.DESC});
        columnTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(orderCombo));

        JPanel form = new JPanel(new GridBagLayout());
        addRow(form, 0, ToolsMessages.designer_label_name, nameField);
        addRow(form, 1, "", uniqueCheck);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(form, BorderLayout.NORTH);
        JPanel columnsPanel = new JPanel(new BorderLayout(0, 4));
        columnsPanel.add(new JLabel(ToolsMessages.designer_dialog_index_columns), BorderLayout.NORTH);
        columnsPanel.add(new JScrollPane(columnTable), BorderLayout.CENTER);
        content.add(columnsPanel, BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(480, 360));

        return showDialog(owner, ToolsMessages.designer_add_index, content, () -> {
            if (nameField.getText().trim().isEmpty()) {
                return error(ToolsMessages.designer_dialog_error_index_name);
            }
            if (columnModel.getChosen().isEmpty()) {
                return error(ToolsMessages.designer_dialog_error_columns);
            }
            List<TableDesignerDriver.IndexColumn> indexColumns = new ArrayList<>();
            for (DBSEntityAttribute column : columnModel.getChosen()) {
                indexColumns.add(new TableDesignerDriver.IndexColumn(column, columnModel.isAscending(column)));
            }
            return new IndexSpec(nameField.getText().trim(), uniqueCheck.isSelected(), indexColumns);
        });
    }

    //////////////////////////////////////////////////////////
    // Foreign key

    @Nullable
    static ForeignKeySpec editForeignKey(@NotNull Component owner, @NotNull DBSEntity table, @NotNull List<DBSEntityAttribute> columns,
                                         @NotNull List<DBSEntity> referenceTables) {
        JTextField nameField = new JTextField(24);
        nameField.setToolTipText(ToolsMessages.designer_dialog_generated_name_tip);
        JComboBox<DBSEntity> tableCombo = new JComboBox<>(referenceTables.toArray(new DBSEntity[0]));
        tableCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBSEntity entity
                    ? DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI) : " ", index, isSelected, cellHasFocus);
            }
        });
        JComboBox<DBSEntityConstraint> keyCombo = new JComboBox<>();
        keyCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBSEntityConstraint key
                    ? key.getName() + " (" + key.getConstraintType().getLocalizedName() + ")" : ToolsMessages.designer_dialog_no_unique_key,
                    index, isSelected, cellHasFocus);
            }
        });
        JComboBox<DBSForeignKeyModifyRule> deleteCombo = createRuleCombo();
        JComboBox<DBSForeignKeyModifyRule> updateCombo = createRuleCombo();
        MappingModel mappingModel = new MappingModel();
        JTable mappingTable = new JTable(mappingModel);
        mappingTable.setRowHeight(24);
        mappingTable.getTableHeader().setReorderingAllowed(false);
        JComboBox<Object> ownCombo = new JComboBox<>();
        ownCombo.addItem(MappingModel.NONE);
        for (DBSEntityAttribute column : columns) {
            ownCombo.addItem(column);
        }
        ownCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBSEntityAttribute a ? a.getName() : value, index, isSelected, cellHasFocus);
            }
        });
        mappingTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(ownCombo));

        Map<DBSEntity, List<DBSEntityConstraint>> keysByTable = new HashMap<>();
        Map<DBSEntityConstraint, List<DBSEntityAttribute>> columnsByKey = new HashMap<>();
        keyCombo.addActionListener(e -> {
            DBSEntityConstraint key = (DBSEntityConstraint) keyCombo.getSelectedItem();
            List<DBSEntityAttribute> refColumns = key == null ? List.of() : columnsByKey.getOrDefault(key, List.of());
            mappingModel.setReferencedColumns(refColumns, columns);
        });
        tableCombo.addActionListener(e -> {
            DBSEntity refTable = (DBSEntity) tableCombo.getSelectedItem();
            keyCombo.removeAllItems();
            if (refTable == null) {
                return;
            }
            List<DBSEntityConstraint> keys = keysByTable.get(refTable);
            if (keys == null) {
                try {
                    keys = ProgressDialog.runTask(NLS.bind(ToolsMessages.designer_dialog_progress_read_keys, refTable.getName()), monitor -> {
                        List<DBSEntityConstraint> found = TableDesignSession.readUniqueKeys(monitor, refTable);
                        for (DBSEntityConstraint key : found) {
                            columnsByKey.put(key, TableDesignSession.readKeyColumns(monitor, key));
                        }
                        return found;
                    });
                } catch (DBInterruptedException ex) {
                    keys = List.of();
                } catch (DBException ex) {
                    DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_add_foreign_key,
                        NLS.bind(ToolsMessages.designer_dialog_error_read_keys, refTable.getName()), ex);
                    keys = List.of();
                }
                keysByTable.put(refTable, keys);
            }
            for (DBSEntityConstraint key : keys) {
                keyCombo.addItem(key);
            }
            if (keys.isEmpty()) {
                mappingModel.setReferencedColumns(List.of(), columns);
            }
        });
        // Start with another table than the edited one when there is one (the usual case)
        for (DBSEntity candidate : referenceTables) {
            if (candidate != table) {
                tableCombo.setSelectedItem(candidate);
                break;
            }
        }
        if (tableCombo.getSelectedIndex() < 0 && !referenceTables.isEmpty()) {
            tableCombo.setSelectedIndex(0);
        }

        JPanel form = new JPanel(new GridBagLayout());
        addRow(form, 0, ToolsMessages.designer_label_name, nameField);
        addRow(form, 1, ToolsMessages.designer_dialog_referenced_table, tableCombo);
        addRow(form, 2, ToolsMessages.designer_dialog_referenced_key, keyCombo);
        addRow(form, 3, ToolsMessages.designer_dialog_on_delete, deleteCombo);
        addRow(form, 4, ToolsMessages.designer_dialog_on_update, updateCombo);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(form, BorderLayout.NORTH);
        JPanel mappingPanel = new JPanel(new BorderLayout(0, 4));
        mappingPanel.add(new JLabel(ToolsMessages.designer_dialog_columns), BorderLayout.NORTH);
        mappingPanel.add(new JScrollPane(mappingTable), BorderLayout.CENTER);
        content.add(mappingPanel, BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(520, 420));

        return showDialog(owner, ToolsMessages.designer_add_foreign_key, content, () -> {
            DBSEntityConstraint key = (DBSEntityConstraint) keyCombo.getSelectedItem();
            if (key == null) {
                return error(ToolsMessages.designer_dialog_error_no_key);
            }
            List<TableDesignerDriver.ForeignKeyColumn> pairs = mappingModel.getPairs();
            if (pairs == null) {
                return error(ToolsMessages.designer_dialog_error_mapping);
            }
            return new ForeignKeySpec(nameField.getText().trim(), key, (DBSForeignKeyModifyRule) deleteCombo.getSelectedItem(),
                (DBSForeignKeyModifyRule) updateCombo.getSelectedItem(), pairs);
        });
    }

    @NotNull
    private static JComboBox<DBSForeignKeyModifyRule> createRuleCombo() {
        JComboBox<DBSForeignKeyModifyRule> combo = new JComboBox<>(MODIFY_RULES);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBSForeignKeyModifyRule rule ? rule.getName() : value,
                    index, isSelected, cellHasFocus);
            }
        });
        return combo;
    }

    //////////////////////////////////////////////////////////
    // Helpers

    /**
     * A validation message instead of a result (the dialog stays open).
     */
    private record ValidationError(@NotNull String message) {
    }

    @NotNull
    static Object error(@NotNull String message) {
        return new ValidationError(message);
    }

    static void addRow(@NotNull JPanel form, int row, @NotNull String label, @NotNull JComponent field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        labelConstraints.insets = new Insets(0, 0, 6, 10);
        form.add(new JLabel(label), labelConstraints);
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(0, 0, 6, 0);
        form.add(field, fieldConstraints);
    }

    /**
     * Modal OK/Cancel dialog; OK runs the validator, which returns the result or a {@link ValidationError}.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static <T> T showDialog(@NotNull Component owner, @NotNull String title, @NotNull JComponent form, @NotNull Supplier<Object> validator) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setIconImages(ChromiteIcons.getWindowIcons());
        Object[] result = new Object[1];
        JLabel errorLabel = new JLabel(" ");
        Color errorColor = UIManager.getColor("Component.error.focusedBorderColor"); //$NON-NLS-1$
        if (errorColor != null) {
            errorLabel.setForeground(errorColor);
        }
        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> {
            Object value = validator.get();
            if (value instanceof ValidationError validationError) {
                errorLabel.setText(validationError.message());
                return;
            }
            result[0] = value;
            dialog.dispose();
        });
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        south.add(errorLabel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return (T) result[0];
    }

    @NotNull
    private static JTable createChoiceTable(@NotNull ColumnChoiceModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setMaxWidth(40);
        return table;
    }

    /**
     * Table columns with a check box; the check order is the key order.
     */
    private static final class ColumnChoiceModel extends AbstractTableModel {
        static final String ASC = "ASC"; //$NON-NLS-1$
        static final String DESC = "DESC"; //$NON-NLS-1$

        private final List<DBSEntityAttribute> columns;
        private final boolean withOrder;
        private final List<DBSEntityAttribute> chosen = new ArrayList<>();
        private final Map<DBSEntityAttribute, String> orders = new HashMap<>();

        ColumnChoiceModel(@NotNull List<DBSEntityAttribute> columns, boolean withOrder) {
            this.columns = columns;
            this.withOrder = withOrder;
        }

        @NotNull
        List<DBSEntityAttribute> getChosen() {
            return new ArrayList<>(chosen);
        }

        boolean isAscending(@NotNull DBSEntityAttribute column) {
            return !DESC.equals(orders.get(column));
        }

        @Override
        public int getRowCount() {
            return columns.size();
        }

        @Override
        public int getColumnCount() {
            return withOrder ? 4 : 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "";
                case 1 -> "#";
                case 2 -> ToolsMessages.designer_dialog_column;
                default -> ToolsMessages.designer_dialog_order;
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBSEntityAttribute attribute = columns.get(row);
            int position = chosen.indexOf(attribute);
            return switch (column) {
                case 0 -> position >= 0;
                case 1 -> position >= 0 ? String.valueOf(position + 1) : "";
                case 2 -> attribute.getName();
                default -> orders.getOrDefault(attribute, ASC);
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 || column == 3;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            DBSEntityAttribute attribute = columns.get(row);
            if (column == 0) {
                if (Boolean.TRUE.equals(value) && !chosen.contains(attribute)) {
                    chosen.add(attribute);
                } else if (!Boolean.TRUE.equals(value)) {
                    chosen.remove(attribute);
                }
                fireTableDataChanged();
            } else if (column == 3 && value instanceof String order) {
                orders.put(attribute, order);
                fireTableCellUpdated(row, column);
            }
        }
    }

    /**
     * Referenced key columns and the chosen columns of the edited table.
     */
    private static final class MappingModel extends AbstractTableModel {
        static final String NONE = "(choose)"; //$NON-NLS-1$

        private List<DBSEntityAttribute> referencedColumns = List.of();
        private final List<Object> ownColumns = new ArrayList<>();

        void setReferencedColumns(@NotNull List<DBSEntityAttribute> referenced, @NotNull List<DBSEntityAttribute> candidates) {
            referencedColumns = referenced;
            ownColumns.clear();
            for (DBSEntityAttribute refColumn : referenced) {
                // Preselect "<table>_<column>" (customer_id for customer.id), else a column of the same name in another table
                String prefixed = refColumn.getParentObject().getName() + "_" + refColumn.getName(); //$NON-NLS-1$
                Object match = candidates.stream().filter(c -> c.getName().equalsIgnoreCase(prefixed)).findFirst()
                    .map(Object.class::cast)
                    .orElseGet(() -> candidates.stream()
                        .filter(c -> c.getName().equalsIgnoreCase(refColumn.getName()) && c.getParentObject() != refColumn.getParentObject())
                        .findFirst().map(Object.class::cast).orElse(NONE));
                ownColumns.add(match);
            }
            fireTableDataChanged();
        }

        /**
         * The column pairs, or null if a referenced column has no column chosen.
         */
        @Nullable
        List<TableDesignerDriver.ForeignKeyColumn> getPairs() {
            if (referencedColumns.isEmpty()) {
                return null;
            }
            List<TableDesignerDriver.ForeignKeyColumn> pairs = new ArrayList<>();
            for (int i = 0; i < referencedColumns.size(); i++) {
                if (!(ownColumns.get(i) instanceof DBSEntityAttribute own)) {
                    return null;
                }
                pairs.add(new TableDesignerDriver.ForeignKeyColumn(own, referencedColumns.get(i)));
            }
            return pairs;
        }

        @Override
        public int getRowCount() {
            return referencedColumns.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? ToolsMessages.designer_dialog_referenced_column : ToolsMessages.designer_dialog_column;
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (column == 0) {
                return referencedColumns.get(row).getName();
            }
            Object own = ownColumns.get(row);
            return own instanceof DBSEntityAttribute attribute ? attribute.getName() : own;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 1 && value != null) {
                ownColumns.set(row, value);
                fireTableCellUpdated(row, column);
            }
        }
    }
}
