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

import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Multi-selection list with a filter: "Add Objects…" (securables) and "Add Users or Roles…" (grantees).
 *
 * 설계: SSMS의 "개체 추가"·"사용자 또는 역할 선택" 대화상자는 종류를 고른 뒤 찾아보기 창을 따로 여는 두 단계지만, 여기서는 목록을 한 번에 읽어
 * 이름 필터로 좁히는 한 단계로 했다(대상 수가 탐색기 한 연결의 객체 수라 한 번에 읽어도 된다). 이미 들어 있는 항목은 목록에서 뺀다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 *
 * @param <T> item type
 */
final class SecurityPickerDialog<T> extends JDialog {

    private final List<T> items;
    private final Function<T, String> nameProvider;
    @Nullable
    private final Function<T, String> typeProvider;
    private final List<T> visible = new ArrayList<>();
    private final ItemsModel model = new ItemsModel();
    private final JTable table = new JTable(model);
    private final JTextField filterField = new JTextField(24);
    private List<T> result = List.of();

    private SecurityPickerDialog(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull List<T> items,
        @NotNull Function<T, String> nameProvider,
        @Nullable Function<T, String> typeProvider
    ) {
        super(SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.items = items;
        this.nameProvider = nameProvider;
        this.typeProvider = typeProvider;

        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    accept();
                }
            }
        });
        filterField.getDocument().addDocumentListener(new DocumentListener() {
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

        JPanel filterPanel = new JPanel(new BorderLayout(6, 0));
        filterPanel.add(new JLabel(SecurityMessages.security_pick_filter), BorderLayout.WEST);
        filterPanel.add(filterField, BorderLayout.CENTER);

        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(filterPanel, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        applyFilter();
        setSize(560, 460);
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the items and returns the selected ones (empty if canceled).
     *
     * @param typeProvider type column, or null for a name-only list
     */
    @NotNull
    static <T> List<T> pick(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull List<T> items,
        @NotNull Function<T, String> nameProvider,
        @Nullable Function<T, String> typeProvider
    ) {
        SecurityPickerDialog<T> dialog = new SecurityPickerDialog<>(owner, title, items, nameProvider, typeProvider);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void applyFilter() {
        String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (T item : items) {
            if (filter.isEmpty() || nameProvider.apply(item).toLowerCase(Locale.ROOT).contains(filter)) {
                visible.add(item);
            }
        }
        model.fireTableDataChanged();
    }

    private void accept() {
        List<T> selected = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            selected.add(visible.get(row));
        }
        result = selected;
        dispose();
    }

    private final class ItemsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return visible.size();
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
            T item = visible.get(rowIndex);
            return columnIndex == 0 || typeProvider == null ? nameProvider.apply(item) : typeProvider.apply(item);
        }
    }
}
