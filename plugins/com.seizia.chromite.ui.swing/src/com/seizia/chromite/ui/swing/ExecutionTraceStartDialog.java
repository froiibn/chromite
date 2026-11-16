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
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.TraceMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Arguments of a traced call: name, T-SQL type, direction and value (a T-SQL expression) of each parameter.
 *
 * 설계: 디버거 시작 대화상자({@link DebugStartDialog})와 같은 표 모양이지만 따로 둔다. 디버거는 attach 모드가 있고 값을 모두
 * 요구하는데, 추적은 빈 값을 "입력은 기본값, OUTPUT은 NULL"로 쓴다(T-SQL은 매개 변수를 빼면 기본값을 쓴다). 형식은 모델이 서버
 * {@code sys.parameters}에서 만든 선언 형식이라 호출이 쓰는 형식과 같다. 테이블 반환·커서 매개 변수는 값을 받을 수 없어 칸을 끈다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sqlserver-execution-trace.md)
 */
final class ExecutionTraceStartDialog extends JDialog {

    private final List<String> values = new ArrayList<>();
    private final JTable table;
    @Nullable
    private List<String> result;

    private ExecutionTraceStartDialog(
        @Nullable Window owner,
        @NotNull String routineName,
        @NotNull List<SQLServerExecutionTrace.Parameter> parameters,
        @Nullable List<String> previous
    ) {
        super(owner, NLS.bind(TraceMessages.trace_start_title, routineName), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        for (int i = 0; i < parameters.size(); i++) {
            values.add(previous != null && previous.size() == parameters.size() ? CommonUtils.notEmpty(previous.get(i)) : ""); //$NON-NLS-1$
        }
        table = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return parameters.size();
            }

            @Override
            public int getColumnCount() {
                return 4;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> TraceMessages.trace_col_name;
                    case 1 -> TraceMessages.trace_col_type;
                    case 2 -> TraceMessages.trace_col_direction;
                    default -> TraceMessages.trace_col_value;
                };
            }

            @Override
            public Object getValueAt(int row, int column) {
                SQLServerExecutionTrace.Parameter parameter = parameters.get(row);
                return switch (column) {
                    case 0 -> parameter.name();
                    case 1 -> parameter.declaration();
                    case 2 -> parameter.output() ? TraceMessages.trace_direction_output : TraceMessages.trace_direction_input;
                    default -> parameter.valueAccepted() ? values.get(row) : TraceMessages.trace_value_not_accepted;
                };
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3 && parameters.get(row).valueAccepted();
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                values.set(row, CommonUtils.toString(value));
            }
        });
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(260));

        JPanel parametersPanel = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        parametersPanel.add(new JLabel(parameters.isEmpty() ? TraceMessages.trace_start_no_params : TraceMessages.trace_start_params),
            BorderLayout.NORTH);
        if (!parameters.isEmpty()) {
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(160)));
            parametersPanel.add(scrollPane, BorderLayout.CENTER);
        }

        JButton runButton = new JButton(TraceMessages.trace_start_button);
        runButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        buttons.add(runButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(10)));
        content.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)));
        content.add(parametersPanel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(runButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW); //$NON-NLS-1$
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog.
     *
     * @param previous the values of the last run (offered again), or null
     * @return one value per parameter (empty: not given), or null if canceled
     */
    @Nullable
    static List<String> show(
        @NotNull Component parent,
        @NotNull String routineName,
        @NotNull List<SQLServerExecutionTrace.Parameter> parameters,
        @Nullable List<String> previous
    ) {
        ExecutionTraceStartDialog dialog = new ExecutionTraceStartDialog(SwingUtilities.getWindowAncestor(parent), routineName, parameters,
            previous);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void accept() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        result = new ArrayList<>(values);
        dispose();
    }
}
