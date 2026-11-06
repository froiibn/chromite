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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 쿼리 편집기의 "Parameters…": SQL에서 찾은 매개변수마다 표시 이름, 형식, 기본값, 필수, 선택 목록/조회 쿼리를 정하는 표.
 *
 * 설계: 목록은 지금 SQL에서 찾은 매개변수({@link QueryParameterBinder#detect})다. SQL에서 빠진 매개변수의 정의는 버린다
 * (파일에 쓸모없는 정의가 남지 않게). 선택 목록은 {@code |}로 나눠 입력하고 파일에는 한 줄에 하나({@code choice:})로 쓴다.
 * 조회 쿼리는 첫 열을 값 목록으로 쓴다. {@code ${name}} 변수는 "SQL text"로 표시한다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class QueryParameterDefinitionsDialog extends JDialog {

    private static final String[] COLUMNS = {ToolsMessages.param_definitions_column_parameter, ToolsMessages.param_definitions_column_label,
        ToolsMessages.param_definitions_column_type, ToolsMessages.param_definitions_column_default, ToolsMessages.param_definitions_column_required,
        ToolsMessages.param_definitions_column_choices};

    private final List<QueryLibraryParameter> parameters = new ArrayList<>();
    private final Map<String, Boolean> kinds;
    private final JTable table;
    private boolean accepted;

    private QueryParameterDefinitionsDialog(@NotNull Window owner, @NotNull List<QueryLibraryParameter> parameters, @NotNull Map<String, Boolean> kinds) {
        super(owner, ToolsMessages.param_definitions_title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.parameters.addAll(parameters);
        this.kinds = kinds;

        table = new JTable(new DefinitionsModel());
        table.setRowHeight(Math.max(table.getRowHeight(), 26));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.setDefaultEditor(QueryLibraryParameter.Type.class, new DefaultCellEditor(new JComboBox<>(QueryLibraryParameter.Type.values())));
        table.setDefaultRenderer(QueryLibraryParameter.Type.class, (t, value, selected, focus, row, column) -> {
            Component c = t.getDefaultRenderer(String.class).getTableCellRendererComponent(t,
                value instanceof QueryLibraryParameter.Type type ? type.getLabel() : value, selected, focus, row, column);
            return c;
        });
        int[] widths = {120, 140, 110, 100, 70, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JLabel hint = new JLabel(parameters.isEmpty()
            ? ToolsMessages.param_definitions_hint_none
            : ToolsMessages.param_definitions_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

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

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(820, Math.min(360, 60 + 28 * Math.max(3, parameters.size()))));
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(hint, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Edits the definitions of the parameters found in the SQL.
     *
     * @param current the query's definitions (not changed)
     * @param kinds   parameters found in the SQL ({@link QueryParameterBinder#detect})
     * @return the new definitions (one per parameter of the SQL), or null if canceled
     */
    @Nullable
    static List<QueryLibraryParameter> open(@NotNull Component parent, @NotNull List<QueryLibraryParameter> current, @NotNull Map<String, Boolean> kinds) {
        List<QueryLibraryParameter> parameters = merge(current, kinds);
        Window owner = SwingUtilities.getWindowAncestor(parent);
        QueryParameterDefinitionsDialog dialog = new QueryParameterDefinitionsDialog(owner != null ? owner : JOptionPane.getRootFrame(), parameters, kinds);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.parameters : null;
    }

    /**
     * One definition per parameter of the SQL, in order of use: a copy of the existing definition, or a plain one.
     */
    @NotNull
    static List<QueryLibraryParameter> merge(@NotNull List<QueryLibraryParameter> current, @NotNull Map<String, Boolean> kinds) {
        List<QueryLibraryParameter> result = new ArrayList<>();
        for (String name : kinds.keySet()) {
            QueryLibraryParameter existing = null;
            for (QueryLibraryParameter parameter : current) {
                if (parameter.getName().equalsIgnoreCase(name)) {
                    existing = parameter;
                    break;
                }
            }
            QueryLibraryParameter parameter = existing != null ? existing.copy() : new QueryLibraryParameter(name);
            parameter.setName(name);
            result.add(parameter);
        }
        return result;
    }

    private final class DefinitionsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return parameters.size();
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
            return switch (column) {
                case 2 -> QueryLibraryParameter.Type.class;
                case 4 -> Boolean.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column > 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            QueryLibraryParameter parameter = parameters.get(row);
            return switch (column) {
                case 0 -> Boolean.FALSE.equals(kinds.get(parameter.getName()))
                    ? NLS.bind(ToolsMessages.param_definitions_text_parameter, "${" + parameter.getName() + "}") //$NON-NLS-1$ //$NON-NLS-2$
                    : ":" + parameter.getName(); //$NON-NLS-1$
                case 1 -> CommonUtils.notEmpty(parameter.getLabel());
                case 2 -> parameter.getType();
                case 3 -> CommonUtils.notEmpty(parameter.getDefaultValue());
                case 4 -> parameter.isRequired();
                default -> parameter.getType() == QueryLibraryParameter.Type.LOOKUP
                    ? CommonUtils.notEmpty(parameter.getLookupSql()) : String.join("|", parameter.getChoices()); //$NON-NLS-1$
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            QueryLibraryParameter parameter = parameters.get(row);
            switch (column) {
                case 1 -> parameter.setLabel(CommonUtils.toString(value).trim());
                case 2 -> {
                    if (value instanceof QueryLibraryParameter.Type type) {
                        String extra = (String) getValueAt(row, 5);
                        parameter.setType(type);
                        setExtra(parameter, extra);
                    }
                }
                case 3 -> parameter.setDefaultValue(CommonUtils.toString(value).trim());
                case 4 -> parameter.setRequired(Boolean.TRUE.equals(value));
                case 5 -> setExtra(parameter, CommonUtils.toString(value).trim());
                default -> {
                }
            }
            fireTableRowsUpdated(row, row);
        }

        /**
         * The last column is the choice list or the lookup query, depending on the type; the other one is cleared.
         */
        private void setExtra(@NotNull QueryLibraryParameter parameter, @NotNull String extra) {
            parameter.getChoices().clear();
            parameter.setLookupSql(null);
            if (parameter.getType() == QueryLibraryParameter.Type.LOOKUP) {
                parameter.setLookupSql(extra);
            } else if (parameter.getType() == QueryLibraryParameter.Type.CHOICE) {
                for (String choice : extra.split("\\|")) { //$NON-NLS-1$
                    if (!choice.isBlank()) {
                        parameter.getChoices().add(choice.trim());
                    }
                }
            }
        }
    }
}
