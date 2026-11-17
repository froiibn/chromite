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

import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The settings dialog of the Object Explorer command "TTL" on an Altibase-family table whose server offers a TTL
 * ({@link AltibaseTableTtl}): the table's TTL column, amount and unit, or no TTL.
 *
 * 설계: 테이블스페이스 설정 대화상자({@link AltibaseTablespaceDialogs})와 같은 틀이다: 지금 값을 진행 대화상자에서 읽어 채우고
 * ({@link NewObjectDialogs#load}), {@link DesignerDialogs#showDialog}의 OK/취소, 칸 이름 + 값, 오류는 단추 줄의 한 줄. 대화상자는 값만
 * 받고 문장은 서버 확장이 만든다. 그 뒤 미리 보기·열린 트랜잭션 질문·실행은 {@link ObjectMaintenanceCommands}의 공통 흐름이다.
 * 설명 문구는 두지 않는다(사용자 규칙).
 * <ul>
 *     <li>"TTL 사용" 확인란: 끄면 다른 칸이 꺼지고 TTL이 있던 테이블에서는 해제 문장이 된다. 앱의 다른 설정 대화상자가 켜고 끄는 것을
 *     확인란으로 하므로(자동 확장) 같은 모양을 골랐다.</li>
 *     <li>기준 컬럼은 확장이 TTL 컬럼으로 받는 컬럼(날짜·시간)만 보인다. 다른 길로 다른 종류의 컬럼에 TTL이 걸려 있으면 그 컬럼도 넣어
 *     지금 값이 보이고 "바뀐 것 없음"이 맞게 한다. 고를 컬럼이 없고 TTL도 없으면 확인란이 꺼진 채 바꿀 수 없다(OK는 "실행할 문장 없음").</li>
 *     <li>기간은 숫자만 받는 칸({@link NumberInputFilter}, 소수·부호 없음)이고 1 이상의 정수여야 한다(0은 서버의 구문 오류).</li>
 *     <li>단위는 확장이 고르게 하는 것만(TTL이 없으면 확장이 정한 기본 단위), 다만 다른 길로 걸린 다른 단위(예: 주)는 지금 값으로 함께 보인다.</li>
 *     <li>지금 값과 같으면 {@link AltibaseTablespaceDialogs#UNCHANGED}(실행할 문장 없음 알림).</li>
 * </ul>
 * EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #152, docs/chromite/altibase.md "테이블 TTL 자리")
 */
final class AltibaseTableTtlDialog {

    /**
     * A column in the column combo.
     */
    private record ColumnChoice(@NotNull DBSEntityAttribute column) {
        @Override
        public String toString() {
            return column.getName();
        }
    }

    /**
     * What the dialog reads before it opens.
     */
    private record Current(@Nullable AltibaseTableTtl.Setting setting, @NotNull List<? extends DBSEntityAttribute> columns) {
    }

    private AltibaseTableTtlDialog() {
    }

    /**
     * The TTL dialog of the table, filled with its current TTL (read in a progress dialog).
     *
     * @return the change, {@link AltibaseTablespaceDialogs#UNCHANGED} if the values are the current ones, or null if
     * canceled or the current TTL could not be read
     */
    @Nullable
    static Object ask(@NotNull Component owner, @NotNull String title, @NotNull DBSTable table, @NotNull AltibaseTableTtl ttl) {
        Current current = NewObjectDialogs.load(title, monitor -> {
            List<? extends DBSEntityAttribute> columns = table.getAttributes(monitor);
            return new Current(ttl.readSetting(monitor, table), columns == null ? List.of() : columns);
        });
        if (current == null) {
            return null;
        }
        AltibaseTableTtl.Setting setting = current.setting();

        JComboBox<ColumnChoice> columnCombo = new JComboBox<>();
        ColumnChoice currentColumn = null;
        for (DBSEntityAttribute column : current.columns()) {
            boolean isCurrent = setting != null && column.getName().equals(setting.column());
            if (ttl.isTtlColumn(column) || isCurrent) {
                ColumnChoice choice = new ColumnChoice(column);
                columnCombo.addItem(choice);
                if (isCurrent) {
                    currentColumn = choice;
                }
            }
        }
        List<AltibaseTableTtl.Unit> units = new ArrayList<>(ttl.getUnits());
        AltibaseTableTtl.Unit currentUnit = setting == null ? null : ttl.getUnit(setting.unitCode());
        if (currentUnit != null && !units.contains(currentUnit)) {
            units.add(currentUnit);
        }
        JComboBox<AltibaseTableTtl.Unit> unitCombo = new JComboBox<>(units.toArray(new AltibaseTableTtl.Unit[0]));
        JTextField amountField = new JTextField(12);
        NumberInputFilter.install(amountField, false);
        JCheckBox enabledBox = new JCheckBox(ObjectMaintenanceMessages.objmaint_ttl_enabled);

        if (setting != null) {
            enabledBox.setSelected(true);
            if (currentColumn != null) {
                columnCombo.setSelectedItem(currentColumn);
            }
            NumberInputFilter.setText(amountField, String.valueOf(setting.amount()));
            if (currentUnit != null) {
                unitCombo.setSelectedItem(currentUnit);
            }
        } else {
            unitCombo.setSelectedItem(ttl.getDefaultUnit());
            // Nothing to choose from: the table can't take a TTL
            enabledBox.setEnabled(columnCombo.getItemCount() > 0);
        }
        Runnable syncEnabled = () -> {
            boolean on = enabledBox.isSelected();
            columnCombo.setEnabled(on);
            amountField.setEnabled(on);
            unitCombo.setEnabled(on);
        };
        enabledBox.addItemListener(e -> syncEnabled.run());
        syncEnabled.run();

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, "", enabledBox); //$NON-NLS-1$
        DesignerDialogs.addRow(form, 1, ObjectMaintenanceMessages.objmaint_ttl_column, columnCombo);
        DesignerDialogs.addRow(form, 2, ObjectMaintenanceMessages.objmaint_ttl_amount, amountField);
        DesignerDialogs.addRow(form, 3, ObjectMaintenanceMessages.objmaint_ttl_unit, unitCombo);

        return DesignerDialogs.showDialog(owner, title, form, () -> {
            if (!enabledBox.isSelected()) {
                return setting == null ? AltibaseTablespaceDialogs.UNCHANGED : AltibaseTableTtl.Change.remove();
            }
            ColumnChoice column = (ColumnChoice) columnCombo.getSelectedItem();
            if (column == null) {
                return DesignerDialogs.error(ObjectMaintenanceMessages.objmaint_ttl_error_column);
            }
            long amount = parseAmount(amountField.getText());
            if (amount < 1) {
                return DesignerDialogs.error(ObjectMaintenanceMessages.objmaint_ttl_error_amount);
            }
            AltibaseTableTtl.Unit unit = (AltibaseTableTtl.Unit) Objects.requireNonNull(unitCombo.getSelectedItem());
            if (setting != null && column.column().getName().equals(setting.column()) && amount == setting.amount()
                && unit.code().equals(setting.unitCode())) {
                return AltibaseTablespaceDialogs.UNCHANGED;
            }
            return new AltibaseTableTtl.Change(column.column(), amount, unit);
        });
    }

    /**
     * The amount as a whole number, or 0 if empty or too large for the catalog's INTEGER.
     */
    private static long parseAmount(@NotNull String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
