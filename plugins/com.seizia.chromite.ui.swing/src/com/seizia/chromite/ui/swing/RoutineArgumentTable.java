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
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.ui.swing.internal.DebugMessages;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.util.List;
import java.util.function.Predicate;

/**
 * The argument table of a routine: name, type, mode and a value cell per parameter.
 *
 * 설계: 프로시저 디버거의 시작 대화상자({@link DebugStartDialog})와 Execute Procedure 대화상자({@link ExecuteProcedureDialog})가 같은 표를 쓴다.
 * 디버거 대화상자에 있던 표 모델을 이 클래스로 옮겼다(값 규칙도 같다: SQL 식). 값 칸을 고칠 수 있는지는 부르는 쪽이 정한다
 * (디버거: attach 모드가 아닐 때, 실행: 입력 매개 변수만). 값은 부르는 쪽의 목록에 바로 쓴다.
 * 형식 칸은 매개 변수 자신의 길이·정밀도를 보인다. 디버거 표가 쓰던 데이터 형식의 이름({@code getParameterType()})은 SQL Server에서
 * 형식의 기본값(nvarchar(4000), decimal(38,38))이라 선언과 달랐다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: {@link DebugStartDialog}의 표를 옮겨 Execute Procedure와 함께 씀 (docs/chromite/swing-routines.md)
 */
final class RoutineArgumentTable extends JTable {

    static final int VALUE_COLUMN = 3;

    /**
     * @param values   the value of each parameter, written when a cell is edited
     * @param editable whether the value of a parameter can be edited now
     */
    RoutineArgumentTable(
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull List<String> values,
        @NotNull Predicate<DBSProcedureParameter> editable
    ) {
        super(new AbstractTableModel() {
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
                    // The parameter's own length and precision (SQL Server nvarchar(max), decimal(10,2)), not its data type's defaults
                    case 1 -> parameter instanceof DBSTypedObject typed ? typed.getFullTypeName()
                        : parameter.getParameterType() == null ? "" : parameter.getParameterType().getFullTypeName(); //$NON-NLS-1$
                    case 2 -> parameter.getParameterKind().getTitle();
                    default -> values.get(row);
                };
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == VALUE_COLUMN && editable.test(parameters.get(row));
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                values.set(row, CommonUtils.toString(value));
            }
        });
        setSurrendersFocusOnKeystroke(true);
        putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        getColumnModel().getColumn(VALUE_COLUMN).setPreferredWidth(UIScale.scale(260));
    }

    /**
     * Ends the cell edit in progress, keeping its value.
     */
    void stopEditing() {
        if (isEditing()) {
            getCellEditor().stopCellEditing();
        }
    }
}
