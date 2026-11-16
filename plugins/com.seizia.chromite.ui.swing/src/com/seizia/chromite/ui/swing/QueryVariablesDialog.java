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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.QueryCommandMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.DBCScriptContext;
import com.seizia.chromite.model.sql.SQLScriptContext;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Query &gt; Variables…: the {@code @set} variables a query window keeps between its runs, to see, change and remove.
 *
 * 설계: SWT의 변수 패널({@code SQLVariablesPanel}, 편집기 아래 탭)을 대화상자로 옮겼다. Swing 쿼리 창의 결과 영역은 Results, Messages,
 * Execution plan, Terminal 탭을 메인 창이 나눠 쓰고, 변수는 가끔 보는 것이라 탭을 늘리지 않았다. 변수는 창의 변수 문맥
 * ({@code SqlEditorPanel}이 가진 {@link SQLScriptContext}, 실행마다 만드는 스크립트 문맥의 부모)에 있다. 실행 중에는 Job이 이 문맥을
 * 바꾸므로 메뉴가 실행 중에 이 대화상자를 열지 않고, 모달이라 열려 있는 동안 실행하지 않는다. 값은 칸에서 고치고, 고친 값은 다음 실행에 쓰인다.
 * 이름은 {@code @set}에 쓴 그대로 보인다. 찾을 때는 문맥이 정규화한다(따옴표 없는 이름은 대소문자를 가리지 않음, SWT와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-commands.md "@set 변수 유지")
 * - 2026-11-13 위 안내를 HTML 라벨 대신 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다(앱 변경 #90,
 *   docs/chromite/swing-options.md "HTML 라벨 안내")
 */
final class QueryVariablesDialog extends JDialog {

    private final SQLScriptContext variables;
    private final VariablesModel model = new VariablesModel();
    private final JTable table = new JTable(model);
    private final JButton removeButton = new JButton(QueryCommandMessages.query_variables_remove);
    private final JButton removeAllButton = new JButton(QueryCommandMessages.query_variables_remove_all);

    private QueryVariablesDialog(@NotNull Window owner, @NotNull String windowName, @NotNull SQLScriptContext variables) {
        super(owner, NLS.bind(QueryCommandMessages.query_variables_title, windowName), ModalityType.APPLICATION_MODAL);
        this.variables = variables;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Wraps at spaces only (an HTML label broke Korean words) and reports the height of its lines at the dialog's width
        JTextArea hint = new WrappingNote(QueryCommandMessages.query_variables_hint);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(180));
        table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(340));
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());

        removeButton.addActionListener(e -> remove(false));
        removeAllButton.addActionListener(e -> remove(true));
        JButton closeButton = new JButton(EditorMessages.editor_common_close);
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(removeButton);
        buttons.add(removeAllButton);
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(hint, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        model.reload();
        updateButtons();
        setSize(UIScale.scale(600), UIScale.scale(380));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the variables of a query window until the dialog is closed.
     */
    static void open(@NotNull Component parent, @NotNull String windowName, @NotNull SQLScriptContext variables) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        new QueryVariablesDialog(owner, windowName, variables).setVisible(true);
    }

    private void remove(boolean all) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        List<String> names = new ArrayList<>();
        if (all) {
            for (DBCScriptContext.VariableInfo variable : model.rows) {
                names.add(variable.name);
            }
        } else {
            for (int row : table.getSelectedRows()) {
                names.add(model.rows.get(table.convertRowIndexToModel(row)).name);
            }
        }
        names.forEach(variables::removeVariable);
        model.reload();
        updateButtons();
    }

    private void updateButtons() {
        removeButton.setEnabled(table.getSelectedRowCount() > 0);
        removeAllButton.setEnabled(model.getRowCount() > 0);
    }

    private final class VariablesModel extends AbstractTableModel {
        private final String[] columns = {QueryCommandMessages.query_variables_name, QueryCommandMessages.query_variables_value};
        private List<DBCScriptContext.VariableInfo> rows = new ArrayList<>();

        void reload() {
            rows = variables.getVariables();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DBCScriptContext.VariableInfo variable = rows.get(rowIndex);
            return columnIndex == 0 ? variable.name : CommonUtils.toString(variable.value);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            DBCScriptContext.VariableInfo variable = rows.get(rowIndex);
            // The same as @set name = value: the value is kept as text
            variables.setVariable(variable.name, CommonUtils.toString(value));
            variable.value = CommonUtils.toString(value);
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
