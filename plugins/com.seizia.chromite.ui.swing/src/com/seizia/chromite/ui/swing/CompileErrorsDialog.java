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
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.ui.swing.internal.RoutineMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile errors of the objects compiled from Object Explorer (Oracle), with a way to open the object's Modify
 * window at an error.
 *
 * 설계: SWT Compile 명령은 오류 목록을 오류 대화상자로 보이고, 열려 있는 객체 편집기면 첫 오류 줄로 옮겼다({@code CompileHandler}).
 * 여러 객체는 {@code OracleCompilerDialog}가 객체마다 결과를 보였다. Swing에는 객체 편집기가 없어 오류를 표(객체, 형식, 줄, 열, 메시지)로 보이고
 * "오류로 가기"(두 번 클릭)로 그 객체의 Modify 창을 열어 오류 줄을 표시한다({@link RoutineCommands#openModifyAtError}).
 * 줄·열은 저장된 원본 기준이다(Modify 창에서는 스크립트 줄로 바꿔 보인다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (SWT 감사 O11, docs/chromite/swing-routines.md)
 */
final class CompileErrorsDialog extends JDialog {

    /**
     * An error and the navigator node of its object.
     */
    record Row(@NotNull DBNDatabaseNode node, @NotNull String objectName, @NotNull OracleCompileErrors.CompileError error) {
    }

    private final List<Row> rows;
    private final JTable table;
    private final ObjectExplorerPanel.QueryWindows queryWindows;

    private CompileErrorsDialog(
        @Nullable Window owner,
        @NotNull String title,
        @NotNull String message,
        @NotNull List<Row> rows,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.rows = rows;
        this.queryWindows = queryWindows;
        table = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return 5;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> RoutineMessages.routine_compile_col_object;
                    case 1 -> RoutineMessages.routine_compile_col_type;
                    case 2 -> RoutineMessages.routine_compile_col_line;
                    case 3 -> RoutineMessages.routine_compile_col_column;
                    default -> RoutineMessages.routine_compile_col_message;
                };
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 2 || column == 3 ? Integer.class : String.class;
            }

            @Override
            public Object getValueAt(int row, int column) {
                Row item = rows.get(row);
                OracleCompileErrors.CompileError error = item.error();
                return switch (column) {
                    case 0 -> item.objectName();
                    case 1 -> error.type();
                    case 2 -> error.line();
                    case 3 -> error.position();
                    default -> (error.error() ? "" : RoutineMessages.routine_compile_warning_prefix) + error.text(); //$NON-NLS-1$
                };
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(180));
        table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(60));
        table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(70));
        table.getColumnModel().getColumn(4).setPreferredWidth(UIScale.scale(420));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    goToError();
                }
            }
        });

        JButton goButton = new JButton(RoutineMessages.routine_compile_go_to_error);
        goButton.setToolTipText(RoutineMessages.routine_compile_go_to_error_tip);
        goButton.addActionListener(e -> goToError());
        goButton.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> goButton.setEnabled(table.getSelectedRow() >= 0));
        JButton closeButton = new JButton(RoutineMessages.routine_close);
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        buttons.add(goButton);
        buttons.add(closeButton);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(UIScale.scale(820), UIScale.scale(220)));
        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        content.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)));
        content.add(new JLabel(message), BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW); //$NON-NLS-1$
        pack();
        setLocationRelativeTo(owner);
        if (!rows.isEmpty()) {
            table.changeSelection(0, 0, false, false);
        }
    }

    static void show(
        @NotNull Component parent,
        @NotNull String title,
        @NotNull String message,
        @NotNull List<Row> rows,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        new CompileErrorsDialog(SwingUtilities.getWindowAncestor(parent), title, message, rows, queryWindows).setVisible(true);
    }

    private void goToError() {
        int index = table.getSelectedRow();
        if (index < 0) {
            return;
        }
        Row selected = rows.get(table.convertRowIndexToModel(index));
        List<OracleCompileErrors.CompileError> objectErrors = new ArrayList<>();
        for (Row row : rows) {
            if (row.node() == selected.node()) {
                objectErrors.add(row.error());
            }
        }
        dispose();
        RoutineCommands.openModifyAtError(queryWindows, selected.node(), objectErrors, selected.error());
    }
}
