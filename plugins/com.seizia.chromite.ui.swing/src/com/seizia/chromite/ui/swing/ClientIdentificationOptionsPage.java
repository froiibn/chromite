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
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Client Identification, and the same page for one connection (SWT {@code PrefPageConnectionClient}).
 *
 * 설계:
 * <ul>
 *     <li>클라이언트 이름: 연결할 때 드라이버에 넘기는 애플리케이션 이름(서버의 세션 목록에 보임). 끄기 {@code META_CLIENT_NAME_DISABLE}는
 *     PostgreSQL·MySQL·SQL Server·Oracle·DB2·HANA·Athena·범용 JDBC 데이터 원본이 읽고, 바꾸기와 값은 {@code DBUtils.getClientApplicationName}이 읽는다
 *     (변수 {@code ${context.name}}, {@code ${context.id}}와 연결 변수).</li>
 *     <li>연결 매개 변수의 변수 처리 {@code CONNECT_USE_ENV_VARS}: 연결할 때 {@code DataSourceDescriptor}가 연결 정보의 {@code ${...}}를 푼다.</li>
 *     <li>모두 세션을 열 때 읽으므로 연결된 연결에서 바꾸면 다시 연결을 묻는다({@link #isAppliedWithoutReconnect}). 다시 연결은 열린 트랜잭션을 먼저 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "Client Identification")
 */
final class ClientIdentificationOptionsPage extends SettingsPage {

    private final JCheckBox disableBox = new JCheckBox(SettingsMessages.client_disable);
    private final JCheckBox overrideBox = new JCheckBox(SettingsMessages.client_override);
    private final JTextField nameField = new JTextField(30);
    private final JCheckBox variablesBox = new JCheckBox(SettingsMessages.client_use_variables);

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    ClientIdentificationOptionsPage(boolean global) {
        disableBox.setToolTipText(SettingsMessages.client_disable_tip);
        disableBox.addActionListener(e -> updateEnablement());
        overrideBox.addActionListener(e -> updateEnablement());
        nameField.setToolTipText(NLS.bind(SettingsMessages.client_name_tip,
            GeneralUtils.variablePattern(DBConstants.VAR_CONTEXT_NAME), GeneralUtils.variablePattern(DBConstants.VAR_CONTEXT_ID)));
        variablesBox.setToolTipText(SettingsMessages.client_use_variables_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.client_section_name);
        row = OptionsDialog.addRow(form, row, null, disableBox, null);
        row = OptionsDialog.addRow(form, row, null, overrideBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.client_name, nameField, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.client_section_parameters);
        OptionsDialog.addRow(form, row, null, variablesBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.client_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.META_CLIENT_NAME_DISABLE, ModelPreferences.META_CLIENT_NAME_OVERRIDE,
            ModelPreferences.META_CLIENT_NAME_VALUE, ModelPreferences.CONNECT_USE_ENV_VARS);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        disableBox.setSelected(store.getBoolean(ModelPreferences.META_CLIENT_NAME_DISABLE));
        overrideBox.setSelected(store.getBoolean(ModelPreferences.META_CLIENT_NAME_OVERRIDE));
        nameField.setText(store.getString(ModelPreferences.META_CLIENT_NAME_VALUE));
        variablesBox.setSelected(store.getBoolean(ModelPreferences.CONNECT_USE_ENV_VARS));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(ModelPreferences.META_CLIENT_NAME_DISABLE, disableBox.isSelected());
        writer.setBoolean(ModelPreferences.META_CLIENT_NAME_OVERRIDE, overrideBox.isSelected());
        writer.setString(ModelPreferences.META_CLIENT_NAME_VALUE, nameField.getText().trim());
        writer.setBoolean(ModelPreferences.CONNECT_USE_ENV_VARS, variablesBox.isSelected());
    }

    /**
     * The name is only sent with the identification on, and only used when it replaces the default name.
     */
    @Override
    void updateEnablement() {
        boolean enabled = disableBox.isEnabled() && !disableBox.isSelected();
        overrideBox.setEnabled(enabled);
        nameField.setEnabled(enabled && overrideBox.isSelected());
    }

    /**
     * The name is sent when a session opens, and the variables are resolved when the connection opens.
     */
    @Override
    boolean isAppliedWithoutReconnect() {
        return false;
    }
}
