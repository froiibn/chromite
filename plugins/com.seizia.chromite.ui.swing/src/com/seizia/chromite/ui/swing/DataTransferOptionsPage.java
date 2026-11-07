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
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.tools.transfer.database.MappingNameCase;
import com.seizia.chromite.tools.transfer.database.MappingReplaceMechanism;
import com.seizia.chromite.tools.transfer.internal.DTActivator;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Data Transfer, and the same page for one target connection: the name and type mapping of new tables
 * and columns (SWT {@code PrefPageDataTransfer}).
 *
 * 설계:
 * <ul>
 *     <li>읽는 곳: 이름 대소문자와 공백 처리 {@code DatabaseTransferUtils.getTransformedName}(데이터베이스 복사 매핑 {@link DatabaseCopyMappings},
 *     전송이 만드는 테이블·열), 최대 형식 길이 {@code DatabaseMappingAttribute}·{@code StreamDataImporterColumnInfo}(가져오기). 모두 <b>대상</b> 연결의
 *     설정 저장소가 키를 자기 값으로 가지면 그 값을, 아니면 데이터 전송 번들의 저장소({@code DTActivator})를 읽는다. 그래서 전역 값은 플랫폼 저장소가
 *     아니라 그 번들 저장소에 쓴다({@link #getGlobalStore}).</li>
 *     <li>연결 페이지에서 읽을 때 연결이 자기 값을 갖지 않은 키는 번들 저장소의 값을 보인다(연결 저장소의 부모는 플랫폼 저장소라 그대로 읽으면 0이 된다).</li>
 *     <li>값의 번호는 모델 열거형의 선택 번호다({@link MappingNameCase#getCaseBySelectionId}). 콤보의 순서가 그 번호다.</li>
 *     <li>전송할 때 읽으므로 다시 연결하지 않아도 된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class DataTransferOptionsPage extends SettingsPage {

    private final JComboBox<String> nameCaseCombo = new JComboBox<>();
    private final JComboBox<String> replaceCombo = new JComboBox<>();
    private final JSpinner maxLengthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1000));

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    DataTransferOptionsPage(boolean global) {
        // Combo index = the model's selection id
        for (int i = 0; i < MappingNameCase.values().length; i++) {
            nameCaseCombo.addItem(MappingNameCase.getCaseBySelectionId(i).getName());
        }
        for (int i = 0; i < MappingReplaceMechanism.values().length; i++) {
            replaceCombo.addItem(MappingReplaceMechanism.getCaseBySelectionId(i).getName());
        }
        // Lengths without digit grouping
        maxLengthSpinner.setEditor(new JSpinner.NumberEditor(maxLengthSpinner, "#")); //$NON-NLS-1$
        JLabel hint = new JLabel(SettingsMessages.transfer_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.transfer_section_names);
        row = OptionsDialog.addRow(form, row, SettingsMessages.transfer_name_case, nameCaseCombo, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.transfer_replace, replaceCombo, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.transfer_max_length, maxLengthSpinner, SettingsMessages.transfer_max_length_hint);
        OptionsDialog.addRow(form, row, null, hint, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(getGlobalStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.transfer_tab;
    }

    @NotNull
    @Override
    DBPPreferenceStore getGlobalStore() {
        return DTActivator.getDefault().getPreferences();
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(DTConstants.PREF_NAME_CASE_MAPPING, DTConstants.PREF_REPLACE_MAPPING, DTConstants.PREF_MAX_TYPE_LENGTH);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        nameCaseCombo.setSelectedIndex(Math.clamp(getInt(store, DTConstants.PREF_NAME_CASE_MAPPING), 0, nameCaseCombo.getItemCount() - 1));
        replaceCombo.setSelectedIndex(Math.clamp(getInt(store, DTConstants.PREF_REPLACE_MAPPING), 0, replaceCombo.getItemCount() - 1));
        maxLengthSpinner.setValue(Math.max(1, getInt(store, DTConstants.PREF_MAX_TYPE_LENGTH)));
    }

    /**
     * The store's own value, else the global one: the model falls back to the data transfer bundle's store, not to the
     * parent of a connection's store.
     */
    private int getInt(@NotNull DBPPreferenceStore store, @NotNull String key) {
        return store.contains(key) ? store.getInt(key) : getGlobalStore().getInt(key);
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setInt(DTConstants.PREF_NAME_CASE_MAPPING, nameCaseCombo.getSelectedIndex());
        writer.setInt(DTConstants.PREF_REPLACE_MAPPING, replaceCombo.getSelectedIndex());
        writer.setInt(DTConstants.PREF_MAX_TYPE_LENGTH, ((Number) maxLengthSpinner.getValue()).intValue());
    }
}
