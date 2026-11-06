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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.sql.SQLParametersProviderBase;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.registry.SQLQueryParameterRegistry;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Asks for the values of the parameters of a query ({@code :name}, {@code ${var}}) before it is executed.
 *
 * 설계: SWT 편집기의 {@code SQLEditorParametersProvider} + {@code SQLQueryParameterBindDialog}를 옮겼다.
 * <ul>
 *     <li>{@link Provider}는 엔진의 매개변수 제공자({@link SQLParametersProviderBase})다. 스크립트 변수나 기본값으로 채워지지 않은
 *     매개변수가 있을 때만 엔진이 부르고(작업 스레드), 대화상자는 EDT에서 열고 기다린다.</li>
 *     <li>값은 SWT와 같이 SQL 글자로 문장에 들어간다(문자열은 따옴표로 감싸야 한다). 이름이 같은 매개변수는 한 줄로 묶는다.
 *     마지막 값은 DBeaver의 {@link SQLQueryParameterRegistry}(작업 공간의 {@code parameter-bindings.xml})에 저장되어 다음에 미리 채워진다.</li>
 *     <li>단추: Execute(값을 넣고 실행), Ignore(매개변수를 무시하고 문장 그대로 실행 — 이 스크립트 끝까지), Cancel(실행 중지). SWT와 같은 세 가지다.</li>
 *     <li>쿼리 라이브러리의 매개변수 입력과 합칠 수 있다(docs/chromite/swing-sql-editor-plus.md "합칠 수 있는 것").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class SqlParameterDialog extends JDialog {

    private static final Log log = Log.getLog(SqlParameterDialog.class);

    enum Result {
        EXECUTE, IGNORE, CANCEL
    }

    private final Map<String, List<SQLQueryParameter>> parametersByName = new LinkedHashMap<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private final JTable table;
    private Result result = Result.CANCEL;

    private SqlParameterDialog(@Nullable Window owner, @NotNull SQLQuery query, @NotNull List<SQLQueryParameter> parameters) {
        super(owner, EditorMessages.editor_param_title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Parameters with the same name are one row; rows in the order the names first appear
        for (SQLQueryParameter parameter : parameters) {
            String key = parameter.isNamed() ? parameter.getName() : "?" + (parameter.getOrdinalPosition() + 1); //$NON-NLS-1$
            parametersByName.computeIfAbsent(key, k -> new ArrayList<>()).add(parameter);
        }
        SQLQueryParameterRegistry registry = SQLQueryParameterRegistry.getInstance();
        for (Map.Entry<String, List<SQLQueryParameter>> entry : parametersByName.entrySet()) {
            names.add(entry.getKey());
            SQLQueryParameter first = entry.getValue().getFirst();
            String value = first.getValue();
            if (value == null && first.isNamed()) {
                SQLQueryParameterRegistry.ParameterInfo saved = registry.getParameter(first.getName());
                value = saved != null ? saved.value : null;
            }
            values.add(CommonUtils.notEmpty(value));
        }

        table = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return names.size();
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public String getColumnName(int column) {
                return column == 0 ? EditorMessages.editor_common_name : EditorMessages.editor_common_value;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                return columnIndex == 0 ? names.get(rowIndex) : values.get(rowIndex);
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return columnIndex == 1;
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                values.set(rowIndex, CommonUtils.toString(aValue));
            }
        });
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        table.setRowHeight(Math.max(table.getRowHeight(), 24));

        SqlEditorPanel.SqlTextArea queryText = new SqlEditorPanel.SqlTextArea();
        queryText.setText(query.getText());
        queryText.setEditable(false);
        queryText.setCaretPosition(0);
        JScrollPane queryScroll = new JScrollPane(queryText);
        queryText.applyTheme();
        queryScroll.setPreferredSize(new Dimension(480, 110));

        JLabel hint = new JLabel(EditorMessages.editor_param_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JButton executeButton = new JButton(EditorMessages.editor_common_execute);
        executeButton.addActionListener(e -> close(Result.EXECUTE));
        JButton ignoreButton = new JButton(EditorMessages.editor_param_ignore);
        ignoreButton.setToolTipText(EditorMessages.editor_param_ignore_tip);
        ignoreButton.addActionListener(e -> close(Result.IGNORE));
        JButton cancelButton = new JButton(EditorMessages.editor_common_cancel);
        cancelButton.addActionListener(e -> close(Result.CANCEL));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(executeButton);
        buttons.add(ignoreButton);
        buttons.add(cancelButton);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(480, Math.min(260, 40 + names.size() * 26)));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(tableScroll, BorderLayout.CENTER);
        center.add(hint, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(queryScroll, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(executeButton);
        getRootPane().registerKeyboardAction(e -> close(Result.CANCEL),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> {
            if (!names.isEmpty()) {
                table.changeSelection(0, 1, false, false);
                table.editCellAt(0, 1);
                Component editor = table.getEditorComponent();
                if (editor != null) {
                    editor.requestFocusInWindow();
                }
            }
        });
    }

    private void close(@NotNull Result result) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        this.result = result;
        dispose();
    }

    /**
     * Opens the dialog (EDT) and, on Execute, assigns the values to the parameters and saves them.
     */
    @NotNull
    static Result open(@Nullable Component parent, @NotNull SQLQuery query, @NotNull List<SQLQueryParameter> parameters) {
        SqlParameterDialog dialog = new SqlParameterDialog(parent == null ? null : SwingUtilities.getWindowAncestor(parent), query, parameters);
        dialog.setVisible(true);
        if (dialog.result == Result.EXECUTE) {
            SQLQueryParameterRegistry registry = SQLQueryParameterRegistry.getInstance();
            for (int i = 0; i < dialog.names.size(); i++) {
                String value = dialog.values.get(i);
                for (SQLQueryParameter parameter : dialog.parametersByName.get(dialog.names.get(i))) {
                    parameter.setValue(value);
                    parameter.setVariableSet(!CommonUtils.isEmpty(value));
                    if (parameter.isNamed()) {
                        registry.setParameter(parameter.getName(), value);
                    }
                }
            }
            registry.save();
        }
        return dialog.result;
    }

    /**
     * Parameter provider of the execution engine; asks in the EDT and waits.
     */
    static final class Provider extends SQLParametersProviderBase {
        private final Component parent;

        Provider(@NotNull Component parent) {
            this.parent = parent;
        }

        @Nullable
        @Override
        protected Boolean collectAndAssignVariables(
            @NotNull SQLScriptContext scriptContext,
            @NotNull SQLQuery sqlStatement,
            @NotNull List<SQLQueryParameter> parameters,
            @NotNull Supplier<DBDDataReceiver> dataReceiverSupplier
        ) {
            Result[] result = {Result.CANCEL};
            try {
                SwingUtilities.invokeAndWait(() -> result[0] = open(parent, sqlStatement, parameters));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                log.error("Parameter dialog failed", e.getTargetException());
                return false;
            }
            switch (result[0]) {
                case EXECUTE -> {
                    // Values are remembered for the rest of the script, as in the SWT editor
                    for (SQLQueryParameter parameter : parameters) {
                        if (parameter.isNamed()) {
                            if (scriptContext.hasVariable(parameter.getName())) {
                                scriptContext.setVariable(parameter.getName(), parameter.getValue());
                            } else if (scriptContext.hasVariable(parameter.getVarName())) {
                                scriptContext.setVariable(parameter.getVarName(), parameter.getValue());
                            } else {
                                scriptContext.setParameterDefaultValue(parameter.getName(), parameter.getValue());
                            }
                        }
                    }
                    return true;
                }
                case IGNORE -> {
                    scriptContext.setIgnoreParameters(true);
                    return null;
                }
                default -> {
                    return false;
                }
            }
        }
    }
}
