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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Query Library의 "Library Roots…": 라이브러리 루트(폴더) 목록을 고치는 대화상자.
 *
 * 설계: 루트마다 이름, 폴더, 읽기 전용 여부를 정한다. 개인 루트(작업 공간의 {@code QueryLibrary}) 외에 네트워크 공유 폴더나 Git 작업 사본을
 * 더해 여러 사람이 같은 쿼리를 쓴다(docs/chromite/swing-query-library.md "공유 설계"). 루트를 목록에서 빼도 폴더의 파일은 지우지 않는다.
 * 루트가 하나도 없으면 개인 루트를 다시 넣는다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "저장 위치와 루트")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 ④ 공유: 쿼리 파일에 비밀번호·토큰을 넣지 않는다는 안내(공유 루트는 폴더에 접근하는 모든 사람이 읽음)
 *   (docs/chromite/swing-query-library.md "공유")
 */
final class QueryLibraryRootsDialog extends JDialog {

    private static final String[] COLUMNS = {ToolsMessages.library_field_name, ToolsMessages.library_field_folder,
        ToolsMessages.library_roots_column_read_only};

    private final List<QueryLibrary.Root> roots;
    private final JTable table;
    private boolean accepted;

    private QueryLibraryRootsDialog(@NotNull Window owner, @NotNull List<QueryLibrary.Root> current) {
        super(owner, ToolsMessages.library_roots_title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        roots = new ArrayList<>(current);
        RootsModel model = new RootsModel();
        table = new JTable(model);
        table.setRowHeight(Math.max(table.getRowHeight(), 26));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);

        JButton addButton = new JButton(ToolsMessages.library_roots_add);
        addButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(ToolsMessages.library_roots_choose_title);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File folder = chooser.getSelectedFile();
                roots.add(new QueryLibrary.Root(folder.getName(), folder.toPath(), false));
                model.fireTableDataChanged();
            }
        });
        JButton removeButton = new JButton(ToolsMessages.library_roots_remove);
        removeButton.setToolTipText(ToolsMessages.library_roots_remove_tip);
        removeButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                if (table.isEditing()) {
                    table.getCellEditor().cancelCellEditing();
                }
                roots.remove(row);
                model.fireTableDataChanged();
            }
        });
        JButton defaultButton = new JButton(ToolsMessages.library_roots_add_default);
        defaultButton.setToolTipText(ToolsMessages.library_roots_add_default_tip);
        defaultButton.addActionListener(e -> {
            QueryLibrary.Root defaultRoot = QueryLibrary.getDefaultRoot();
            if (roots.stream().noneMatch(r -> r.path().equals(defaultRoot.path()))) {
                roots.addFirst(defaultRoot);
                model.fireTableDataChanged();
            }
        });
        JPanel tableButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tableButtons.add(addButton);
        tableButtons.add(removeButton);
        tableButtons.add(defaultButton);

        JLabel hint = new JLabel(ToolsMessages.library_roots_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        // Shared roots are read by everyone with access to the folder: say what never goes into a query file
        JLabel secretsNote = new JLabel(ToolsMessages.library_roots_secrets_note, UiIcons.get("warning"), SwingConstants.LEADING); //$NON-NLS-1$
        secretsNote.setIconTextGap(6);
        JPanel notes = new JPanel(new BorderLayout(0, 6));
        notes.add(hint, BorderLayout.NORTH);
        notes.add(secretsNote, BorderLayout.CENTER);

        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            accepted = true;
            dispose();
        });
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(tableButtons, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 200));
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(notes, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * @return the new root list, or null if canceled
     */
    @Nullable
    static List<QueryLibrary.Root> open(@NotNull Component parent, @NotNull List<QueryLibrary.Root> current) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        QueryLibraryRootsDialog dialog = new QueryLibraryRootsDialog(owner != null ? owner : JOptionPane.getRootFrame(), current);
        dialog.setVisible(true);
        if (!dialog.accepted) {
            return null;
        }
        return dialog.roots.isEmpty() ? List.of(QueryLibrary.getDefaultRoot()) : dialog.roots;
    }

    private final class RootsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return roots.size();
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
            return column == 2 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            QueryLibrary.Root root = roots.get(row);
            return switch (column) {
                case 0 -> root.name();
                case 1 -> root.path().toString();
                default -> root.readOnly();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            QueryLibrary.Root root = roots.get(row);
            QueryLibrary.Root changed = switch (column) {
                case 0 -> CommonUtils.toString(value).isBlank() ? root : new QueryLibrary.Root(CommonUtils.toString(value).strip(), root.path(), root.readOnly());
                case 1 -> {
                    try {
                        yield CommonUtils.toString(value).isBlank() ? root : new QueryLibrary.Root(root.name(), Path.of(CommonUtils.toString(value).strip()), root.readOnly());
                    } catch (InvalidPathException e) {
                        yield root;
                    }
                }
                default -> new QueryLibrary.Root(root.name(), root.path(), Boolean.TRUE.equals(value));
            };
            roots.set(row, changed);
            fireTableRowsUpdated(row, row);
        }
    }
}
