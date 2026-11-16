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
import com.seizia.chromite.registry.timezone.TimezoneRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.RuntimeUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Connections: the global connection settings of SWT's Connections and General pages
 * ({@code PrefPageConnectionsGeneral}, the time zone of {@code PrefPageGeneral}).
 *
 * 설계:
 * <ul>
 *     <li>모두 모델이 플랫폼 설정 저장소에서만 읽는 키라 연결 대화상자에는 넣지 않는다.</li>
 *     <li>위험한 드라이버 매개 변수 거부({@code connection.sanitizeDriverProperties}): {@code JDBCDataSource.validateConnectionProperties}가
 *     연결할 때 읽고, 데이터 원본이 {@code JDBCConnectionValidator}를 줄 때만 검사한다. 지금 주는 곳은 MySQL·MariaDB({@code MySQLConnectionValidator})다.</li>
 *     <li>두지 않은 것: "셸 명령" 인증 방식 허용({@code connection.shellAuthModelEnabled})은 모델이 그 방식을 목록에 넣게 하지만, Swing 연결
 *     대화상자는 Login·Password 칸만으로 설정이 끝나는 방식만 보인다({@link ConnectionAuthModels}: 셸 명령 방식은 명령·작업 폴더 칸이 필요해 뺌).
 *     켜도 고를 수 없어 효과가 없다. Swing에 그 방식의 입력 화면이 생길 때 같이 둔다.</li>
 *     <li>Windows 인증서 저장소({@code connections.useWinTrustStoreType}): {@code ChromiteSwingApplication.useWindowsTrustStore}가 플랫폼을 만들기
 *     전에 한 번 읽는다(JDK가 기본 SSL 문맥을 한 번만 만듦). 다시 시작해야 적용되고 Windows에서만 보인다.</li>
 *     <li>클라이언트 시간대({@code java.client.timezone}): 지금 읽는 곳은 ClickHouse 데이터 원본(연결할 때)이다. 앱 전체의 시간대를 시작할 때 바꾸는
 *     처리(SWT {@code TimezoneRegistry.overrideTimezone}, A-S12)는 B9 2부의 일이라 여기서는 키만 쓴다. 실행 중에 JVM 기본 시간대를 바꾸지 않는다
 *     (열린 결과와 기록의 시간이 섞인다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class ConnectionsOptionsPage extends SettingsPage {

    private final JCheckBox sanitizeBox = new JCheckBox(SettingsMessages.conn_sanitize);
    private final JCheckBox winTrustStoreBox = new JCheckBox(SettingsMessages.conn_win_trust_store);
    private final JComboBox<String> timezoneCombo = new JComboBox<>();

    ConnectionsOptionsPage() {
        sanitizeBox.setToolTipText(SettingsMessages.conn_sanitize_tip);
        winTrustStoreBox.setToolTipText(SettingsMessages.conn_win_trust_store_tip);
        // The first item is the default (no override), then "Zone (UTC+hh:mm)" as SWT lists them
        timezoneCombo.addItem(NLS.bind(SettingsMessages.conn_timezone_default, TimezoneRegistry.getUserDefaultTimezone()));
        for (String name : TimezoneRegistry.getTimezoneNames()) {
            timezoneCombo.addItem(name);
        }

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.conn_section_security);
        row = OptionsDialog.addRow(form, row, null, sanitizeBox, null);
        if (RuntimeUtils.isWindows()) {
            row = OptionsDialog.addRow(form, row, null, winTrustStoreBox, SettingsMessages.conn_restart_hint);
        }
        row = OptionsDialog.addSection(form, row, SettingsMessages.conn_section_time);
        OptionsDialog.addRow(form, row, SettingsMessages.conn_timezone, timezoneCombo, SettingsMessages.conn_timezone_hint);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        load(DBWorkbench.getPlatform().getPreferenceStore());
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.conn_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.CONNECTION_SANITIZE_DRIVER_PROPERTIES, ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE,
            ModelPreferences.CLIENT_TIMEZONE);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        sanitizeBox.setSelected(store.getBoolean(ModelPreferences.CONNECTION_SANITIZE_DRIVER_PROPERTIES));
        winTrustStoreBox.setSelected(store.getBoolean(ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE));
        String timezone = store.getString(ModelPreferences.CLIENT_TIMEZONE);
        timezoneCombo.setSelectedIndex(0);
        if (!CommonUtils.isEmpty(timezone) && !DBConstants.DEFAULT_TIMEZONE.equals(timezone)) {
            for (int i = 1; i < timezoneCombo.getItemCount(); i++) {
                if (TimezoneRegistry.extractTimezoneId(timezoneCombo.getItemAt(i)).equals(timezone)) {
                    timezoneCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(ModelPreferences.CONNECTION_SANITIZE_DRIVER_PROPERTIES, sanitizeBox.isSelected());
        if (RuntimeUtils.isWindows()) {
            writer.setBoolean(ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE, winTrustStoreBox.isSelected());
        }
        int index = timezoneCombo.getSelectedIndex();
        writer.setString(ModelPreferences.CLIENT_TIMEZONE, index <= 0
            ? DBConstants.DEFAULT_TIMEZONE : TimezoneRegistry.extractTimezoneId(timezoneCombo.getItemAt(index)));
    }
}
