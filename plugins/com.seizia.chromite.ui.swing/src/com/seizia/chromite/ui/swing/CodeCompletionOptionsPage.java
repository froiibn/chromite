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
import com.seizia.chromite.model.sql.SQLModelPreferences;
import com.seizia.chromite.model.sql.SQLTableAliasInsertMode;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Code Completion, and the same page for one connection: the model settings of the names auto-complete
 * inserts (the rest of SWT's Code Completion page, {@code PrefPageSQLCompletion}).
 *
 * 설계:
 * <ul>
 *     <li>쿼리 창 자동 완성의 엔진({@code SQLQueryCompletionAnalyzer})이 연결의 설정 저장소에서 읽는다: 테이블 별칭 {@code SQLQueryCompletionTextProvider}
 *     ({@link SQLTableAliasInsertMode#fromPreferences}, 쿼리 창 파서 문맥의 저장소), 짧은 이름·전체 이름 {@link SqlCompletionAnalysis}의 완성 문맥.
 *     자동 완성을 열 때마다 읽으므로 다시 연결하지 않아도 된다.</li>
 *     <li>입력할 때 자동 완성, 지연 시간, 프로시저 제안은 Swing 자체 설정이라 General 탭에 있다. SWT 같은 페이지의 나머지 키는 예전 엔진
 *     ({@code SQLCompletionAnalyzer}, 단어 완성 {@code activate.hippie} 포함)이나 SWT 편집기만 읽어 Swing에서 효과가 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class CodeCompletionOptionsPage extends SettingsPage {

    // SQLTableAliasInsertMode order: NONE, PLAIN, EXTENDED; the examples are SQL and need no translation
    private final JComboBox<String> aliasCombo = new JComboBox<>(new String[]{
        SettingsMessages.completion_alias_none, SQLTableAliasInsertMode.PLAIN.getText(), SQLTableAliasInsertMode.EXTENDED.getText()});
    private final JCheckBox shortNamesBox = new JCheckBox(SettingsMessages.completion_short_names);
    private final JCheckBox longNamesBox = new JCheckBox(SettingsMessages.completion_long_names);

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    CodeCompletionOptionsPage(boolean global) {
        // The two name forms exclude each other: the engine uses short names first (SQLQueryCompletionTextProvider)
        shortNamesBox.addActionListener(e -> {
            if (shortNamesBox.isSelected()) {
                longNamesBox.setSelected(false);
            }
        });
        longNamesBox.addActionListener(e -> {
            if (longNamesBox.isSelected()) {
                shortNamesBox.setSelected(false);
            }
        });
        JLabel hint = new JLabel(SettingsMessages.completion_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.completion_section_names);
        row = OptionsDialog.addRow(form, row, SettingsMessages.completion_alias, aliasCombo, null);
        row = OptionsDialog.addRow(form, row, null, shortNamesBox, null);
        row = OptionsDialog.addRow(form, row, null, longNamesBox, null);
        OptionsDialog.addRow(form, row, null, hint, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.completion_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(SQLModelPreferences.SQL_PROPOSAL_INSERT_TABLE_ALIAS, SQLModelPreferences.SQL_EDITOR_PROPOSAL_SHORT_NAME,
            SQLModelPreferences.SQL_EDITOR_PROPOSAL_ALWAYS_FQ);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        aliasCombo.setSelectedIndex(SQLTableAliasInsertMode.fromPreferences(store).ordinal());
        shortNamesBox.setSelected(store.getBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_SHORT_NAME));
        longNamesBox.setSelected(store.getBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_ALWAYS_FQ));
    }

    @Override
    void save(@NotNull Writer writer) {
        // Stored as the enum name (older values "true"/"false" are read by fromPreferences)
        writer.setString(SQLModelPreferences.SQL_PROPOSAL_INSERT_TABLE_ALIAS, SQLTableAliasInsertMode.values()[aliasCombo.getSelectedIndex()].name());
        writer.setBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_SHORT_NAME, shortNamesBox.isSelected());
        writer.setBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_ALWAYS_FQ, longNamesBox.isSelected());
    }
}
