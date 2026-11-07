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

import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Metadata, and the same page for one connection (SWT {@code PrefPageMetaData}).
 *
 * 설계:
 * <ul>
 *     <li>키와 읽는 곳: 대소문자 구분 이름 {@code DBObjectNameCaseTransformer}(만드는 개체 이름을 DDL에 그대로 씀), DDL에 추가 정보
 *     {@code DBStructUtils}와 PostgreSQL·SQL Server DDL 생성기, 서버 쪽 개체 필터 {@code MySQLCatalog}(지원하는 데이터베이스만: 메타데이터 쿼리에
 *     필터를 넣음), 결과·SQL 편집기에서 테이블 정보 읽지 않기 {@code DataSourceDescriptor.isExtraMetadataReadEnabled}(자동완성
 *     {@link SqlCompletionAnalysis}, 결과 열의 테이블·키 찾기 {@code DBExecUtils.bindAttributes}: 켜면 결과 편집이 안 될 수 있음), 비싼 속성 읽기 {@code DataSourcePropertyFilter}(개체 목록 {@link ObjectListTable}).</li>
 *     <li>SWT 같은 페이지의 "메타데이터용 별도 연결"은 연결 대화상자 Connection Properties에서 연결별로 이미 정한다. 여기 두지 않는다.</li>
 *     <li>모두 그 작업을 할 때 연결의 설정 저장소에서 읽는다. 다시 연결하지 않아도 되지만, 이미 읽어 둔 개체 목록은 새로 고쳐야 바뀐다(서버 쪽 필터).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "Metadata")
 */
final class MetadataOptionsPage extends SettingsPage {

    private final JCheckBox caseSensitiveBox = new JCheckBox(SettingsMessages.metadata_case_sensitive);
    private final JCheckBox extraDdlInfoBox = new JCheckBox(SettingsMessages.metadata_extra_ddl_info);
    private final JCheckBox serverSideFiltersBox = new JCheckBox(SettingsMessages.metadata_server_side_filters);
    private final JCheckBox disableExtraReadBox = new JCheckBox(SettingsMessages.metadata_disable_extra_read);
    private final JCheckBox expensivePropertiesBox = new JCheckBox(SettingsMessages.metadata_expensive_properties);

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    MetadataOptionsPage(boolean global) {
        caseSensitiveBox.setToolTipText(SettingsMessages.metadata_case_sensitive_tip);
        extraDdlInfoBox.setToolTipText(SettingsMessages.metadata_extra_ddl_info_tip);
        serverSideFiltersBox.setToolTipText(SettingsMessages.metadata_server_side_filters_tip);
        disableExtraReadBox.setToolTipText(SettingsMessages.metadata_disable_extra_read_tip);
        expensivePropertiesBox.setToolTipText(SettingsMessages.metadata_expensive_properties_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.metadata_section_ddl);
        row = OptionsDialog.addRow(form, row, null, caseSensitiveBox, null);
        row = OptionsDialog.addRow(form, row, null, extraDdlInfoBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.metadata_section_reading);
        row = OptionsDialog.addRow(form, row, null, serverSideFiltersBox, null);
        row = OptionsDialog.addRow(form, row, null, expensivePropertiesBox, null);
        OptionsDialog.addRow(form, row, null, disableExtraReadBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.metadata_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.META_CASE_SENSITIVE, ModelPreferences.META_EXTRA_DDL_INFO,
            ModelPreferences.META_USE_SERVER_SIDE_FILTERS, ModelPreferences.META_DISABLE_EXTRA_READ,
            ModelPreferences.READ_EXPENSIVE_PROPERTIES);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        caseSensitiveBox.setSelected(store.getBoolean(ModelPreferences.META_CASE_SENSITIVE));
        extraDdlInfoBox.setSelected(store.getBoolean(ModelPreferences.META_EXTRA_DDL_INFO));
        serverSideFiltersBox.setSelected(store.getBoolean(ModelPreferences.META_USE_SERVER_SIDE_FILTERS));
        disableExtraReadBox.setSelected(store.getBoolean(ModelPreferences.META_DISABLE_EXTRA_READ));
        expensivePropertiesBox.setSelected(store.getBoolean(ModelPreferences.READ_EXPENSIVE_PROPERTIES));
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(ModelPreferences.META_CASE_SENSITIVE, caseSensitiveBox.isSelected());
        writer.setBoolean(ModelPreferences.META_EXTRA_DDL_INFO, extraDdlInfoBox.isSelected());
        writer.setBoolean(ModelPreferences.META_USE_SERVER_SIDE_FILTERS, serverSideFiltersBox.isSelected());
        writer.setBoolean(ModelPreferences.META_DISABLE_EXTRA_READ, disableExtraReadBox.isSelected());
        writer.setBoolean(ModelPreferences.READ_EXPENSIVE_PROPERTIES, expensivePropertiesBox.isSelected());
    }
}
