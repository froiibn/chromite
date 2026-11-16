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
import com.seizia.chromite.model.admin.routines.ProcedureCallScript;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.RoutineMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execute Procedure dialog (SSMS "Execute Stored Procedure"): a value for each input parameter, then run the call in a
 * new query window or only open it there.
 *
 * 설계: 표는 디버거 시작 대화상자와 같은 {@link RoutineArgumentTable}이고 값 규칙도 같다(SQL 식). 디버거와 달리 빈 값은 NULL이다
 * (SSMS의 "Pass Null Value"를 따로 두지 않는다). OUT 매개 변수는 값을 받지 않고(칸을 고칠 수 없음) 스크립트가 실행 뒤 값을 보인다.
 * 실행 단추는 루틴을 실행하므로 기본 단추를 취소로 둔다(확인 대화상자의 취소 기본 규칙). "쿼리 창에서 열기"는 실행하지 않고 스크립트만 연다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (SWT 감사 B-S42, docs/chromite/swing-routines.md)
 */
final class ExecuteProcedureDialog extends JDialog {

    /**
     * What the user chose.
     *
     * @param execute run the script (true) or only open it
     * @param values  the value of each input parameter
     */
    record Result(boolean execute, @NotNull Map<DBSProcedureParameter, String> values) {
    }

    private final List<DBSProcedureParameter> parameters;
    private final List<String> values = new ArrayList<>();
    private final RoutineArgumentTable table;
    @Nullable
    private Result result;

    private ExecuteProcedureDialog(
        @Nullable Window owner,
        @NotNull String routineName,
        @NotNull List<DBSProcedureParameter> parameters,
        boolean capturesOutputs
    ) {
        super(owner, NLS.bind(RoutineMessages.routine_execute_title, routineName), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.parameters = parameters;
        for (int i = 0; i < parameters.size(); i++) {
            values.add(""); //$NON-NLS-1$
        }
        table = new RoutineArgumentTable(parameters, values, ProcedureCallScript::isInput);

        JPanel parametersPanel = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        if (parameters.isEmpty()) {
            parametersPanel.add(new JLabel(RoutineMessages.routine_execute_no_params), BorderLayout.NORTH);
        } else {
            String hint = RoutineMessages.routine_execute_hint;
            if (parameters.stream().anyMatch(ProcedureCallScript::isOutput)) {
                hint += "\n" + (capturesOutputs ? RoutineMessages.routine_execute_outputs : RoutineMessages.routine_execute_no_outputs); //$NON-NLS-1$
            }
            parametersPanel.add(SwingPlatformUI.createMessageComponent(hint), BorderLayout.NORTH);
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(180)));
            parametersPanel.add(scrollPane, BorderLayout.CENTER);
        }

        JButton executeButton = new JButton(RoutineMessages.routine_execute_button);
        executeButton.addActionListener(e -> accept(true));
        JButton openButton = new JButton(RoutineMessages.routine_execute_open_button);
        openButton.setToolTipText(RoutineMessages.routine_execute_open_tip);
        openButton.addActionListener(e -> accept(false));
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        buttons.add(executeButton);
        buttons.add(openButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(10)));
        content.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)));
        content.add(parametersPanel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        // Executing runs the routine: Enter must not do it
        getRootPane().setDefaultButton(cancelButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW); //$NON-NLS-1$
        pack();
        setLocationRelativeTo(owner);
        if (parameters.stream().anyMatch(ProcedureCallScript::isInput)) {
            // Start in the first value cell
            int first = 0;
            while (!ProcedureCallScript.isInput(parameters.get(first))) {
                first++;
            }
            table.changeSelection(first, RoutineArgumentTable.VALUE_COLUMN, false, false);
        }
    }

    /**
     * Shows the dialog.
     *
     * @param parameters      the parameters of the call ({@link ProcedureCallScript#getParameters})
     * @param capturesOutputs the script returns OUT values for this database
     * @return the choice and values, or null if canceled
     */
    @Nullable
    static Result show(
        @NotNull Component parent,
        @NotNull String routineName,
        @NotNull List<DBSProcedureParameter> parameters,
        boolean capturesOutputs
    ) {
        ExecuteProcedureDialog dialog = new ExecuteProcedureDialog(SwingUtilities.getWindowAncestor(parent), routineName, parameters,
            capturesOutputs);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void accept(boolean execute) {
        table.stopEditing();
        Map<DBSProcedureParameter, String> map = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            map.put(parameters.get(i), values.get(i));
        }
        result = new Result(execute, map);
        dispose();
    }
}
