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

import com.seizia.chromite.ext.altibase.extension.AltibaseExecutionSettings;
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The dialog of the Object Explorer command "Execution Settings" on an object a server extension runs in the background
 * ({@link AltibaseExecutionSettings}): its execution group and order, and its TTL where it takes one.
 *
 * 설계: 테이블 TTL 대화상자({@link AltibaseTableTtlDialog})·작업 설정({@link AltibaseJobDialogs})과 같은 틀이다({@link DesignerDialogs#showDialog}의
 * OK/취소, 칸 이름 + 값, 오류는 단추 줄의 한 줄). 지금 값은 모델이 읽은 것이라 진행 대화상자 없이 채운다. 대화상자는 값만 받고 문장은 확장이
 * 만든다. 설명 문구는 두지 않는다(사용자 규칙).
 * <ul>
 *     <li>그룹·순서는 숫자만 받는 칸({@link NumberInputFilter}, 소수·부호 없음)이고 0 ~ 2147483647이어야 한다(서버가 음수를 구문 오류로, int를
 *     넘는 값을 201054로 거절).</li>
 *     <li>TTL은 확장이 단위를 줄 때만 보인다. "TTL 사용" 확인란과 기간·단위는 테이블 TTL과 같다(기간 1 이상, 0은 서버의 구문 오류).
 *     다른 길로 걸린 다른 단위는 지금 값으로 함께 보인다.</li>
 *     <li>지금 값과 같으면 {@link AltibaseTablespaceDialogs#UNCHANGED}(실행할 문장 없음 알림).</li>
 * </ul>
 * EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #156, docs/chromite/altibase.md "확장 객체의 명령 자리")
 */
final class AltibaseExecutionSettingsDialog {

    private AltibaseExecutionSettingsDialog() {
    }

    /**
     * The settings dialog of the object, filled with its current settings.
     *
     * @return the new {@link AltibaseExecutionSettings.Settings}, {@link AltibaseTablespaceDialogs#UNCHANGED} if they are
     * the current ones, or null if canceled
     */
    @Nullable
    static Object ask(@NotNull Component owner, @NotNull String title, @NotNull AltibaseExecutionSettings object) {
        AltibaseExecutionSettings.Settings current = object.getExecutionSettings();
        AltibaseExecutionSettings.Ttl currentTtl = current.ttl();

        JTextField nameField = new JTextField(object.getName(), 24);
        nameField.setEditable(false);
        // Shown as the other dialogs show a read-only value, but never focused: the first focus goes to the group
        nameField.setFocusable(false);
        JTextField groupField = new JTextField(12);
        NumberInputFilter.install(groupField, false);
        NumberInputFilter.setText(groupField, String.valueOf(current.group()));
        JTextField orderField = new JTextField(12);
        NumberInputFilter.install(orderField, false);
        NumberInputFilter.setText(orderField, String.valueOf(current.order()));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, row++, ObjectMaintenanceMessages.objmaint_exec_group, groupField);
        DesignerDialogs.addRow(form, row++, ObjectMaintenanceMessages.objmaint_exec_order, orderField);

        List<AltibaseTableTtl.Unit> units = new ArrayList<>(object.getTtlUnits());
        boolean withTtl = !units.isEmpty();
        JCheckBox ttlBox = new JCheckBox(ObjectMaintenanceMessages.objmaint_ttl_enabled);
        JTextField amountField = new JTextField(12);
        NumberInputFilter.install(amountField, false);
        if (currentTtl != null && !units.contains(currentTtl.unit())) {
            units.add(currentTtl.unit());
        }
        JComboBox<AltibaseTableTtl.Unit> unitCombo = new JComboBox<>(units.toArray(new AltibaseTableTtl.Unit[0]));
        if (withTtl) {
            if (currentTtl != null) {
                ttlBox.setSelected(true);
                NumberInputFilter.setText(amountField, String.valueOf(currentTtl.amount()));
                unitCombo.setSelectedItem(currentTtl.unit());
            } else {
                unitCombo.setSelectedItem(object.getDefaultTtlUnit());
            }
            Runnable syncTtl = () -> {
                amountField.setEnabled(ttlBox.isSelected());
                unitCombo.setEnabled(ttlBox.isSelected());
            };
            ttlBox.addItemListener(e -> syncTtl.run());
            syncTtl.run();
            DesignerDialogs.addRow(form, row++, "", ttlBox); //$NON-NLS-1$
            DesignerDialogs.addRow(form, row++, ObjectMaintenanceMessages.objmaint_ttl_amount, amountField);
            DesignerDialogs.addRow(form, row, ObjectMaintenanceMessages.objmaint_ttl_unit, unitCombo);
        }

        return DesignerDialogs.showDialog(owner, title, form, () -> {
            int group = parse(groupField.getText());
            int order = parse(orderField.getText());
            if (group < 0 || order < 0) {
                return DesignerDialogs.error(ObjectMaintenanceMessages.objmaint_exec_error_number);
            }
            AltibaseExecutionSettings.Ttl ttl = currentTtl;
            if (withTtl) {
                ttl = null;
                if (ttlBox.isSelected()) {
                    int amount = parse(amountField.getText());
                    if (amount < 1) {
                        return DesignerDialogs.error(ObjectMaintenanceMessages.objmaint_ttl_error_amount);
                    }
                    ttl = new AltibaseExecutionSettings.Ttl(amount, (AltibaseTableTtl.Unit) Objects.requireNonNull(unitCombo.getSelectedItem()));
                }
            }
            AltibaseExecutionSettings.Settings settings = new AltibaseExecutionSettings.Settings(group, order, ttl);
            return settings.equals(current) ? AltibaseTablespaceDialogs.UNCHANGED : settings;
        });
    }

    /**
     * The field's whole number, or -1 if empty or too large for the catalog's INTEGER.
     */
    private static int parse(@NotNull String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
