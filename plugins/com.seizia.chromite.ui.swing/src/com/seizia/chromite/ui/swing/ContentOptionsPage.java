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
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Binary and LOB, and the same page for one connection (the LOB and binary rows of SWT's Binary Editor
 * page, {@code PrefPageResultSetBinaries}).
 *
 * 설계:
 * <ul>
 *     <li>행과 함께 읽기: {@code JDBCContentValueHandler}가 결과를 가져올 때 연결의 설정 저장소에서 CLOB·BLOB 미리 읽기와 크기 한도를 읽는다.
 *     미리 읽지 않은 LOB은 결과가 열려 있는 동안 값을 보거나 고칠 때 읽는다.</li>
 *     <li>글자 인코딩: {@code DBValueFormatting.getDefaultBinaryFileEncoding}(연결의 저장소)이 이진 값을 글자로, 글자를 이진 값으로 바꿀 때 쓴다
 *     ({@code JDBCContentBytes}, {@code JDBCContentChars}). 비우면 시스템 기본 인코딩이다.</li>
 *     <li>메모리에 두는 크기({@code content.memory.maxsize})는 {@code JDBCContentCLOB}·{@code JDBCContentBLOB}이 플랫폼 저장소에서만 읽는다.
 *     연결별 값이 효과가 없으므로 전역 페이지에만 두고 {@link #getKeys}에도 전역일 때만 넣는다.</li>
 *     <li>모두 값을 가져오거나 바꿀 때 읽으므로 다시 연결하지 않아도 다음 실행부터 적용된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class ContentOptionsPage extends SettingsPage {

    private final boolean global;
    private final JCheckBox cacheClobBox = new JCheckBox(SettingsMessages.content_cache_clob);
    private final JCheckBox cacheBlobBox = new JCheckBox(SettingsMessages.content_cache_blob);
    private final JSpinner cacheMaxSpinner = createBytesSpinner();
    private final JSpinner memoryMaxSpinner = createBytesSpinner();
    private final JComboBox<String> encodingCombo = new JComboBox<>(Charset.availableCharsets().keySet().toArray(String[]::new));

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    ContentOptionsPage(boolean global) {
        this.global = global;
        cacheClobBox.setToolTipText(SettingsMessages.content_cache_tip);
        cacheBlobBox.setToolTipText(SettingsMessages.content_cache_tip);
        cacheClobBox.addActionListener(e -> updateEnablement());
        cacheBlobBox.addActionListener(e -> updateEnablement());
        memoryMaxSpinner.setToolTipText(SettingsMessages.content_memory_max_tip);
        // Editable: a charset name the JVM does not list is kept as typed
        encodingCombo.setEditable(true);
        encodingCombo.setToolTipText(SettingsMessages.content_encoding_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.content_section_reading);
        row = OptionsDialog.addRow(form, row, null, cacheClobBox, null);
        row = OptionsDialog.addRow(form, row, null, cacheBlobBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.content_cache_max, cacheMaxSpinner, SettingsMessages.content_bytes_hint);
        if (global) {
            row = OptionsDialog.addRow(form, row, SettingsMessages.content_memory_max, memoryMaxSpinner, SettingsMessages.content_bytes_hint);
        }
        OptionsDialog.addRow(form, row, SettingsMessages.content_encoding, encodingCombo, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    private static JSpinner createBytesSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1000));
        // Bytes without digit grouping, as the other byte and millisecond fields
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#")); //$NON-NLS-1$
        return spinner;
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.content_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        List<String> keys = new ArrayList<>(List.of(ModelPreferences.CONTENT_CACHE_CLOB, ModelPreferences.CONTENT_CACHE_BLOB,
            ModelPreferences.CONTENT_CACHE_MAX_SIZE, ModelPreferences.CONTENT_HEX_ENCODING));
        if (global) {
            keys.add(ModelPreferences.MEMORY_CONTENT_MAX_SIZE);
        }
        return keys;
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        cacheClobBox.setSelected(store.getBoolean(ModelPreferences.CONTENT_CACHE_CLOB));
        cacheBlobBox.setSelected(store.getBoolean(ModelPreferences.CONTENT_CACHE_BLOB));
        cacheMaxSpinner.setValue((int) Math.clamp(store.getLong(ModelPreferences.CONTENT_CACHE_MAX_SIZE), 0, Integer.MAX_VALUE));
        memoryMaxSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)));
        encodingCombo.setSelectedItem(store.getString(ModelPreferences.CONTENT_HEX_ENCODING));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(ModelPreferences.CONTENT_CACHE_CLOB, cacheClobBox.isSelected());
        writer.setBoolean(ModelPreferences.CONTENT_CACHE_BLOB, cacheBlobBox.isSelected());
        writer.setLong(ModelPreferences.CONTENT_CACHE_MAX_SIZE, ((Number) cacheMaxSpinner.getValue()).longValue());
        if (global) {
            writer.setInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE, ((Number) memoryMaxSpinner.getValue()).intValue());
        }
        // An empty encoding means the system default (DBValueFormatting.getDefaultBinaryFileEncoding)
        writer.setString(ModelPreferences.CONTENT_HEX_ENCODING, CommonUtils.toString(encodingCombo.getSelectedItem()).trim());
    }

    /**
     * The size limit only applies when a LOB kind is read with the row.
     */
    @Override
    void updateEnablement() {
        cacheMaxSpinner.setEnabled(cacheClobBox.isEnabled() && (cacheClobBox.isSelected() || cacheBlobBox.isSelected()));
    }
}
