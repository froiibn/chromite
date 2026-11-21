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
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;

/**
 * The TTL of a new table in the table designer's "Table" tab, where the connection's server offers a table TTL for a
 * table that does not exist yet ({@link AltibaseTableTtl#forNewTable}): use TTL, column, amount and unit.
 *
 * 설계: 디자이너에서 테이블 단위 설정이 있는 곳은 "테이블" 탭(테이블 속성 시트)이라 그 아래에 붙인다. 칸은 Object Explorer의 "TTL 설정"
 * 대화상자({@link AltibaseTableTtlDialog})와 같다(같은 문구, {@link NumberInputFilter}, 같은 기본 단위). 값은 명령 없이 새 테이블 객체에
 * 넣고({@link AltibaseTableTtl#setNewTableChange}), 테이블 관리자가 스크립트를 만들 때마다 CREATE TABLE 끝에 절로 붙인다. 그래서 스크립트
 * 미리 보기·저장·열린 트랜잭션 질문·오류 창은 디자이너의 기존 흐름 그대로다. 설명 문구는 두지 않는다(사용자 규칙).
 * <ul>
 *     <li>새 테이블에만 있다. 저장되어 기존 테이블이 되면 숨긴다: 기존 테이블의 TTL은 "TTL 설정" 한 길로만 바꾼다(두 번째 길을 두지
 *     않음).</li>
 *     <li>기준 컬럼은 디자이너에 지금 있는 컬럼 가운데 확장이 TTL 컬럼으로 받는 것(날짜)만. 표를 다시 읽을 때마다 목록을 새로 만들어
 *     이름을 바꾼 컬럼은 새 이름으로 보이고, 지운 컬럼이나 날짜가 아니게 된 컬럼을 골라 두었으면 선택이 비워진다(다른 컬럼으로 옮기지
 *     않음). 비워진 채 저장하면 "날짜 컬럼을 고르십시오."로 막는다.</li>
 *     <li>잘못된 기간(빈 칸, 0, int 범위 밖)은 저장·스크립트 보기에서 막고 입력은 그대로 둔다({@link #check}).</li>
 * </ul>
 * EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #155, docs/chromite/swing-table-designer.md "새 테이블의 TTL")
 */
final class AltibaseNewTableTtlPart {

    /**
     * A column in the column combo, named as it is now (a renamed new column shows its new name).
     */
    private record ColumnChoice(@NotNull DBSEntityAttribute column) {
        @Override
        public String toString() {
            return column.getName();
        }
    }

    private final DBSEntity table;
    private final AltibaseTableTtl ttl;
    private final JPanel component = new JPanel(new BorderLayout());
    private final JCheckBox enabledBox = new JCheckBox(ObjectMaintenanceMessages.objmaint_ttl_enabled);
    private final JComboBox<ColumnChoice> columnCombo = new JComboBox<>();
    private final JTextField amountField = new JTextField(12);
    private final JComboBox<AltibaseTableTtl.Unit> unitCombo;
    // The combo is being rebuilt: its events are not the user's choice
    private boolean updating;

    private AltibaseNewTableTtlPart(@NotNull DBSEntity table, @NotNull AltibaseTableTtl ttl) {
        this.table = table;
        this.ttl = ttl;
        unitCombo = new JComboBox<>(ttl.getUnits().toArray(new AltibaseTableTtl.Unit[0]));
        unitCombo.setSelectedItem(ttl.getDefaultUnit());
        NumberInputFilter.install(amountField, false);

        enabledBox.addItemListener(e -> {
            if (enabledBox.isSelected() && columnCombo.getSelectedItem() == null && columnCombo.getItemCount() > 0) {
                columnCombo.setSelectedIndex(0);
            }
            syncEnabled();
            store();
        });
        columnCombo.addActionListener(e -> store());
        unitCombo.addActionListener(e -> store());
        amountField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                store();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                store();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                store();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, "", enabledBox); //$NON-NLS-1$
        DesignerDialogs.addRow(form, 1, ObjectMaintenanceMessages.objmaint_ttl_column, columnCombo);
        DesignerDialogs.addRow(form, 2, ObjectMaintenanceMessages.objmaint_ttl_amount, amountField);
        DesignerDialogs.addRow(form, 3, ObjectMaintenanceMessages.objmaint_ttl_unit, unitCombo);
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)));
        component.add(new JSeparator(), BorderLayout.NORTH);
        // At the fields' own width, not stretched over the whole tab
        component.add(form, BorderLayout.WEST);
        syncEnabled();
    }

    /**
     * The part for the designer's table, or null when its connection gives no TTL to a new table (another server or
     * database, the extension bundle left out of the product) or the table exists.
     */
    @Nullable
    static AltibaseNewTableTtlPart create(@NotNull DBSEntity table) {
        AltibaseTableTtl ttl = AltibaseTableTtl.forNewTable(table);
        return ttl == null ? null : new AltibaseNewTableTtlPart(table, ttl);
    }

    @NotNull
    JComponent getComponent() {
        return component;
    }

    /**
     * The designer's columns were read again; null once the table is saved (the part is hidden).
     */
    void setColumns(@Nullable List<DBSEntityAttribute> columns) {
        if (columns == null) {
            component.setVisible(false);
            AltibaseTableTtl.setNewTableChange(table, null);
            return;
        }
        ColumnChoice selected = (ColumnChoice) columnCombo.getSelectedItem();
        updating = true;
        try {
            columnCombo.removeAllItems();
            ColumnChoice keep = null;
            for (DBSEntityAttribute column : columns) {
                if (ttl.isTtlColumn(column)) {
                    ColumnChoice choice = new ColumnChoice(column);
                    columnCombo.addItem(choice);
                    if (selected != null && selected.column() == column) {
                        keep = choice;
                    }
                }
            }
            // A removed column, or one that is no longer a date, leaves no choice rather than another column
            columnCombo.setSelectedItem(keep);
        } finally {
            updating = false;
        }
        syncEnabled();
        store();
    }

    /**
     * Why the TTL can't be saved as entered (focusing the field), or null if it can.
     */
    @Nullable
    String check() {
        if (!component.isVisible() || !enabledBox.isSelected()) {
            return null;
        }
        if (columnCombo.getSelectedItem() == null) {
            columnCombo.requestFocusInWindow();
            return ObjectMaintenanceMessages.objmaint_ttl_error_column;
        }
        if (parseAmount(amountField.getText()) < 1) {
            amountField.requestFocusInWindow();
            return ObjectMaintenanceMessages.objmaint_ttl_error_amount;
        }
        return null;
    }

    private void syncEnabled() {
        boolean on = enabledBox.isSelected();
        // Nothing to choose from: no TTL can be turned on (it can still be turned off)
        enabledBox.setEnabled(on || columnCombo.getItemCount() > 0);
        columnCombo.setEnabled(on);
        amountField.setEnabled(on);
        unitCombo.setEnabled(on);
    }

    /**
     * Puts the TTL as entered into the new table, or none while it is off or incomplete ({@link #check} refuses the save).
     */
    private void store() {
        if (updating) {
            return;
        }
        AltibaseTableTtl.Change change = null;
        ColumnChoice column = (ColumnChoice) columnCombo.getSelectedItem();
        long amount = parseAmount(amountField.getText());
        if (enabledBox.isSelected() && column != null && amount >= 1) {
            change = new AltibaseTableTtl.Change(column.column(), amount, (AltibaseTableTtl.Unit) unitCombo.getSelectedItem());
        }
        AltibaseTableTtl.setNewTableChange(table, change);
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
