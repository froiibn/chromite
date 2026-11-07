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

import com.seizia.chromite.ext.cubrid.CubridConstants;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;
import org.osgi.framework.FrameworkUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; CUBRID, and the same page for a CUBRID connection (SWT {@code PrefPageCubrid}).
 *
 * 설계:
 * <ul>
 *     <li>읽는 곳: {@code CubridDataSource}가 세션을 열 때({@code initializeContextState}) 서버의 DBMS_OUTPUT을 켜고 버퍼 크기를 정한다. 쿼리 창은
 *     문장마다 켜짐 여부를 읽어 메시지 탭에 보인다. 버퍼 크기와 서버 쪽 켜기는 세션을 열 때만 적용되므로 연결된 연결에서 바꾸면 다시 연결을 묻는다
 *     ({@link #isAppliedWithoutReconnect}, 다시 연결은 열린 트랜잭션을 먼저 묻는다).</li>
 *     <li>기본값(켬, 10000)은 CUBRID 번들의 설정 초기화가 넣는다. Oracle 페이지처럼 값을 읽기 전에 번들의 클래스를 불러 시작시킨다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class CubridOptionsPage extends SettingsPage {

    private final JCheckBox dbmsOutputBox = new JCheckBox(SettingsMessages.cubrid_dbms_output);
    private final JSpinner bufferSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1000));

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    CubridOptionsPage(boolean global) {
        // Starts the CUBRID bundle (lazy activation) so that its preference initializer has set the defaults
        FrameworkUtil.getBundle(CubridConstants.class);
        dbmsOutputBox.addActionListener(e -> updateEnablement());
        // Bytes without digit grouping
        bufferSizeSpinner.setEditor(new JSpinner.NumberEditor(bufferSizeSpinner, "#")); //$NON-NLS-1$
        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.cubrid_tab);
        row = OptionsDialog.addRow(form, row, null, dbmsOutputBox, null);
        OptionsDialog.addRow(form, row, SettingsMessages.cubrid_buffer, bufferSizeSpinner, SettingsMessages.content_bytes_hint);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.cubrid_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(CubridConstants.PREF_DBMS_OUTPUT, CubridConstants.PREF_DBMS_OUTPUT_BUFFER_SIZE);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        dbmsOutputBox.setSelected(store.getBoolean(CubridConstants.PREF_DBMS_OUTPUT));
        bufferSizeSpinner.setValue(Math.max(1, store.getInt(CubridConstants.PREF_DBMS_OUTPUT_BUFFER_SIZE)));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(CubridConstants.PREF_DBMS_OUTPUT, dbmsOutputBox.isSelected());
        writer.setInt(CubridConstants.PREF_DBMS_OUTPUT_BUFFER_SIZE, ((Number) bufferSizeSpinner.getValue()).intValue());
    }

    @Override
    void updateEnablement() {
        bufferSizeSpinner.setEnabled(dbmsOutputBox.isEnabled() && dbmsOutputBox.isSelected());
    }

    /**
     * The server output is switched on and its buffer sized when a session opens.
     */
    @Override
    boolean isAppliedWithoutReconnect() {
        return false;
    }
}
