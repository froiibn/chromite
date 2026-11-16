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

import com.seizia.chromite.model.admin.security.PermissionGrid;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Permission editor: a list of rows (objects of a principal, or grantees of an object) and, for the selected row, its privileges
 * with Grant / With Grant / Deny check boxes.
 *
 * 설계: SSMS의 "보안 개체"(로그인·사용자 속성)와 "사용 권한"(테이블 속성) 페이지가 같은 모양이다: 위 목록에서 대상을 고르면 아래 표에
 * 그 대상의 권한 종류가 한 줄씩 나오고 허용·권한 부여·거부 확인란을 고친다. 두 페이지가 같은 부품을 쓰도록 행 키를 형식 매개변수로 두었다.
 * 확인란 규칙도 SSMS와 같다: With Grant를 켜면 Grant도 켜지고 Deny가 꺼진다, Grant를 끄면 With Grant도 꺼진다, Deny를 켜면 둘 다 꺼진다.
 * 값은 {@link PermissionGrid}에 바로 쓰고, SQL은 저장할 때 원본과 비교해 만든다(이 패널은 SQL을 모른다).
 * SWT의 {@code PostgresPermissionsEditor}(ext.postgresql.ui)는 위에 객체 트리, 오른쪽에 권한 확인란 표를 두는 같은 구성이다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 *
 * @param <K> row key
 */
final class PermissionEditorPanel<K> extends JPanel {

    private final PermissionGrid<K> grid;
    private final Function<K, String> nameProvider;
    @Nullable
    private final Function<K, String> typeProvider;
    private final Function<K, List<String>> privilegeProvider;
    private final boolean denySupported;
    private Predicate<K> rowFilter = row -> true;
    private final List<K> rows = new ArrayList<>();
    private final RowsModel rowsModel = new RowsModel();
    private final PrivilegesModel privilegesModel = new PrivilegesModel();
    private final JTable rowsTable = new JTable(rowsModel);
    private final JTable privilegesTable = new JTable(privilegesModel);
    private final JLabel privilegesLabel = new JLabel(SecurityMessages.security_permissions_none);
    private final JButton addButton;
    private final JButton removeButton = new JButton(SecurityMessages.security_remove);

    /**
     * @param typeProvider type column of the rows, or null for a name-only list
     * @param addLabel     label of the button that adds rows
     * @param addAction    adds rows (the panel shows them after {@link #refresh})
     */
    PermissionEditorPanel(
        @NotNull PermissionGrid<K> grid,
        @NotNull String rowsTitle,
        @NotNull Function<K, String> nameProvider,
        @Nullable Function<K, String> typeProvider,
        @NotNull Function<K, List<String>> privilegeProvider,
        boolean denySupported,
        @NotNull String addLabel,
        @NotNull Runnable addAction
    ) {
        super(new BorderLayout(0, 8));
        this.grid = grid;
        this.nameProvider = nameProvider;
        this.typeProvider = typeProvider;
        this.privilegeProvider = privilegeProvider;
        this.denySupported = denySupported;
        // The tables were created by the field initializers, before the column counts were known
        rowsModel.fireTableStructureChanged();
        privilegesModel.fireTableStructureChanged();

        rowsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rowsTable.setFillsViewportHeight(true);
        rowsTable.getTableHeader().setReorderingAllowed(false);
        rowsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectionChanged();
            }
        });
        privilegesTable.setFillsViewportHeight(true);
        privilegesTable.getTableHeader().setReorderingAllowed(false);
        privilegesTable.setRowSelectionAllowed(false);
        for (int column = 1; column < privilegesModel.getColumnCount(); column++) {
            privilegesTable.getColumnModel().getColumn(column).setMaxWidth(110);
            privilegesTable.getColumnModel().getColumn(column).setPreferredWidth(90);
        }

        addButton = new JButton(addLabel);
        addButton.addActionListener(e -> {
            addAction.run();
            refresh();
        });
        removeButton.addActionListener(e -> removeSelected());
        removeButton.setEnabled(false);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.add(new JLabel(rowsTitle), BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(addButton);
        buttons.add(removeButton);
        topHeader.add(buttons, BorderLayout.EAST);
        top.add(topHeader, BorderLayout.NORTH);
        top.add(new JScrollPane(rowsTable), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(privilegesLabel, BorderLayout.NORTH);
        bottom.add(new JScrollPane(privilegesTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        refresh();
    }

    /**
     * Shows only the rows that pass the filter (SQL Server: the objects of the selected database).
     */
    void setRowFilter(@NotNull Predicate<K> rowFilter) {
        this.rowFilter = rowFilter;
        refresh();
    }

    void setAddEnabled(boolean enabled) {
        addButton.setEnabled(enabled);
    }

    /**
     * Reads the rows of the grid again and keeps the selection.
     */
    void refresh() {
        K selected = getSelectedRow();
        rows.clear();
        for (K row : grid.getRows()) {
            if (rowFilter.test(row)) {
                rows.add(row);
            }
        }
        rowsModel.fireTableDataChanged();
        int index = selected == null ? -1 : rows.indexOf(selected);
        if (index < 0 && !rows.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            rowsTable.setRowSelectionInterval(index, index);
        }
        selectionChanged();
    }

    /**
     * Selects a row (after adding it).
     */
    void select(@NotNull K row) {
        int index = rows.indexOf(row);
        if (index >= 0) {
            rowsTable.setRowSelectionInterval(index, index);
            rowsTable.scrollRectToVisible(rowsTable.getCellRect(index, 0, true));
        }
    }

    @Nullable
    private K getSelectedRow() {
        int index = rowsTable.getSelectedRow();
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    private void selectionChanged() {
        K row = getSelectedRow();
        privilegesModel.setRow(row);
        privilegesLabel.setText(row == null
            ? SecurityMessages.security_permissions_none
            : NLS.bind(SecurityMessages.security_permissions_for, nameProvider.apply(row)));
        removeButton.setEnabled(row != null);
    }

    /**
     * Removes the row: its privileges are revoked on save (a row that was only added just disappears).
     */
    private void removeSelected() {
        K row = getSelectedRow();
        if (row != null) {
            grid.removeRow(row);
            refresh();
        }
    }

    private final class RowsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return typeProvider == null ? 1 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? SecurityMessages.security_column_name : SecurityMessages.security_column_type;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            K row = rows.get(rowIndex);
            return columnIndex == 0 || typeProvider == null ? nameProvider.apply(row) : typeProvider.apply(row);
        }
    }

    private final class PrivilegesModel extends AbstractTableModel {
        private static final int COLUMN_GRANT = 1;
        private static final int COLUMN_WITH_GRANT = 2;
        private static final int COLUMN_DENY = 3;

        @Nullable
        private K row;
        private List<String> privileges = List.of();

        void setRow(@Nullable K row) {
            this.row = row;
            this.privileges = row == null ? List.of() : mergePrivileges(row);
            fireTableDataChanged();
        }

        /**
         * The privileges of the object type plus any other privilege the row has (shown so that it can be revoked).
         */
        @NotNull
        private List<String> mergePrivileges(@NotNull K row) {
            List<String> names = new ArrayList<>(privilegeProvider.apply(row));
            for (String granted : grid.getStates(row).keySet()) {
                if (!names.contains(granted)) {
                    names.add(granted);
                }
            }
            return names;
        }

        @Override
        public int getRowCount() {
            return privileges.size();
        }

        @Override
        public int getColumnCount() {
            return denySupported ? 4 : 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> SecurityMessages.security_column_permission;
                case COLUMN_GRANT -> SecurityMessages.security_column_grant;
                case COLUMN_WITH_GRANT -> SecurityMessages.security_column_with_grant;
                default -> SecurityMessages.security_column_deny;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? String.class : Boolean.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex > 0 && row != null;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            String privilege = privileges.get(rowIndex);
            if (columnIndex == 0 || row == null) {
                return privilege;
            }
            PermissionGrid.State state = grid.get(row, privilege);
            return switch (columnIndex) {
                case COLUMN_GRANT -> state == PermissionGrid.State.GRANT || state == PermissionGrid.State.GRANT_WITH;
                case COLUMN_WITH_GRANT -> state == PermissionGrid.State.GRANT_WITH;
                default -> state == PermissionGrid.State.DENY;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (row == null) {
                return;
            }
            boolean on = Boolean.TRUE.equals(value);
            String privilege = privileges.get(rowIndex);
            PermissionGrid.State state = switch (columnIndex) {
                case COLUMN_GRANT -> on ? PermissionGrid.State.GRANT : PermissionGrid.State.NONE;
                case COLUMN_WITH_GRANT -> on ? PermissionGrid.State.GRANT_WITH : PermissionGrid.State.GRANT;
                default -> on ? PermissionGrid.State.DENY : PermissionGrid.State.NONE;
            };
            grid.set(row, privilege, state);
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
