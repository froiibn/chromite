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
import com.seizia.chromite.debug.DBGConstants;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DebugMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Start dialog of the procedure debugger: run the routine with arguments, or wait for another session's call.
 *
 * 설계: 쿼리 창의 매개 변수 대화상자({@link SqlParameterDialog})는 쿼리 글({@code SQLQuery})의 {@code :name} 매개 변수에 묶여 있어
 * 루틴 인수에 쓸 수 없다. 값을 SQL 식으로 받는 규칙(문자열은 따옴표, NULL)은 그 대화상자와 SWT 디버거 구성 패널
 * ({@code PostgreDebugPanelFunction})과 같게 했다. 표는 SWT 패널처럼 이름·형식·방향·값이다. SWT 패널에서는 막아 두었던
 * attach(Global) 모드를 켰다: 모델이 지원하고 서버에서 확인했다(docs/chromite/procedure-debugger.md "pldbgapi 동작").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 */
final class DebugStartDialog extends JDialog {

    private final List<DBSProcedureParameter> parameters;
    private final List<String> values = new ArrayList<>();
    private final JRadioButton localButton = new JRadioButton(DebugMessages.debug_start_mode_local, true);
    private final JRadioButton attachButton = new JRadioButton(DebugMessages.debug_start_mode_attach);
    private final JTextField processField = new JTextField(8);
    private final JTable table;
    @Nullable
    private Map<String, Object> result;

    private DebugStartDialog(
        @Nullable Window owner,
        @NotNull String routineName,
        @NotNull List<DBSProcedureParameter> parameters,
        @Nullable Map<String, Object> previous
    ) {
        super(owner, NLS.bind(DebugMessages.debug_start_title, routineName), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.parameters = parameters;
        Object previousValues = previous == null ? null : previous.get(DBGConstants.ATTR_PARAMETERS);
        for (int i = 0; i < parameters.size(); i++) {
            values.add(previousValues instanceof List<?> list && list.size() == parameters.size() ? CommonUtils.toString(list.get(i)) : ""); //$NON-NLS-1$
        }
        if (previous != null && DBGConstants.ATTACH_KIND_GLOBAL.equals(previous.get(DBGConstants.ATTR_ATTACH_KIND))) {
            attachButton.setSelected(true);
            processField.setText(CommonUtils.toString(previous.get(DBGConstants.ATTR_ATTACH_PROCESS)));
        }
        ButtonGroup group = new ButtonGroup();
        group.add(localButton);
        group.add(attachButton);

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
                    case 0 -> DebugMessages.debug_col_name;
                    case 1 -> DebugMessages.debug_col_type;
                    case 2 -> DebugMessages.debug_col_mode;
                    default -> DebugMessages.debug_col_value;
                };
            }

            @Override
            public Object getValueAt(int row, int column) {
                DBSProcedureParameter parameter = parameters.get(row);
                return switch (column) {
                    case 0 -> parameter.getName();
                    case 1 -> parameter.getParameterType() == null ? "" : parameter.getParameterType().getFullTypeName(); //$NON-NLS-1$
                    case 2 -> parameter.getParameterKind().getTitle();
                    default -> values.get(row);
                };
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3 && localButton.isSelected();
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                values.set(row, CommonUtils.toString(value));
            }
        });
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(260));

        JPanel modePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.WEST;
        modePanel.add(localButton, gc);
        gc.gridy++;
        modePanel.add(attachButton, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        gc.insets = new Insets(0, UIScale.scale(24), 0, UIScale.scale(6));
        modePanel.add(new JLabel(DebugMessages.debug_start_pid), gc);
        gc.gridx = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        modePanel.add(processField, gc);

        JPanel parametersPanel = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        parametersPanel.add(new JLabel(parameters.isEmpty() ? DebugMessages.debug_start_no_params : DebugMessages.debug_start_params),
            BorderLayout.NORTH);
        if (!parameters.isEmpty()) {
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(UIScale.scale(560), UIScale.scale(160)));
            parametersPanel.add(scrollPane, BorderLayout.CENTER);
        }

        JButton startButton = new JButton(DebugMessages.debug_start_button);
        startButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        buttons.add(startButton);
        buttons.add(cancelButton);

        localButton.addActionListener(e -> updateEnablement());
        attachButton.addActionListener(e -> updateEnablement());
        updateEnablement();

        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(10)));
        content.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)));
        content.add(modePanel, BorderLayout.NORTH);
        content.add(parametersPanel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(startButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW); //$NON-NLS-1$
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog.
     *
     * @param parameters the routine's input parameters, in order
     * @param previous   the configuration of the last start (its answers are offered again), or null
     * @return the debug configuration ({@link DBGConstants} keys), or null if canceled
     */
    @Nullable
    static Map<String, Object> show(
        @NotNull Component parent,
        @NotNull String routineName,
        @NotNull List<DBSProcedureParameter> parameters,
        @Nullable Map<String, Object> previous
    ) {
        DebugStartDialog dialog = new DebugStartDialog(SwingUtilities.getWindowAncestor(parent), routineName, parameters, previous);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void updateEnablement() {
        processField.setEnabled(attachButton.isSelected());
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        table.setEnabled(localButton.isSelected());
    }

    private void accept() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        Map<String, Object> configuration = new HashMap<>();
        if (attachButton.isSelected()) {
            String process = processField.getText().trim();
            if (!process.isEmpty() && !CommonUtils.isNumber(process)) {
                JOptionPane.showMessageDialog(this, DebugMessages.debug_start_pid_invalid, getTitle(), JOptionPane.WARNING_MESSAGE);
                return;
            }
            configuration.put(DBGConstants.ATTR_ATTACH_KIND, DBGConstants.ATTACH_KIND_GLOBAL);
            configuration.put(DBGConstants.ATTR_ATTACH_PROCESS, process);
        } else {
            for (int i = 0; i < parameters.size(); i++) {
                if (CommonUtils.isEmptyTrimmed(values.get(i))) {
                    JOptionPane.showMessageDialog(this, NLS.bind(DebugMessages.debug_start_value_missing, parameters.get(i).getName()),
                        getTitle(), JOptionPane.WARNING_MESSAGE);
                    table.changeSelection(i, 3, false, false);
                    return;
                }
            }
            configuration.put(DBGConstants.ATTR_ATTACH_KIND, DBGConstants.ATTACH_KIND_LOCAL);
        }
        // Kept in attach mode too, so the next start offers them again
        configuration.put(DBGConstants.ATTR_PARAMETERS, new ArrayList<>(values));
        result = configuration;
        dispose();
    }
}
