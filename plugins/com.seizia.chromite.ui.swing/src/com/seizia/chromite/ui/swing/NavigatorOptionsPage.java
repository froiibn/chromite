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
 * Tools &gt; Options &gt; Object Explorer: the sort settings of SWT's Navigator page ({@code PrefPageDatabaseNavigator}).
 *
 * 설계:
 * <ul>
 *     <li>개체 탐색기는 자식을 읽을 때 {@code DBNUtils.filterNavigableChildren}으로 정렬하고({@link ObjectExplorerPanel}), 그 정렬이 플랫폼 저장소의
 *     이름순 정렬·대소문자 무시·폴더 먼저를 읽는다. 이미 펼친 노드는 다시 읽어야(새로 고침, 다시 펼침) 바뀐다.</li>
 *     <li>SWT 같은 페이지의 "빈 폴더 자리 표시"({@code navigator.show.folder.placeholders})는 프로젝트 리소스 노드({@code DBNProjectDesktop})에만
 *     쓰인다. Swing 개체 탐색기는 루트에 서버만 보이고 리소스 노드를 보이지 않아 효과가 없으므로 두지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class NavigatorOptionsPage extends SettingsPage {

    private final JCheckBox sortAlphabeticallyBox = new JCheckBox(SettingsMessages.nav_sort_alpha);
    private final JCheckBox ignoreCaseBox = new JCheckBox(SettingsMessages.nav_sort_ignore_case);
    private final JCheckBox foldersFirstBox = new JCheckBox(SettingsMessages.nav_folders_first);

    NavigatorOptionsPage() {
        sortAlphabeticallyBox.setToolTipText(SettingsMessages.nav_sort_alpha_tip);
        sortAlphabeticallyBox.addActionListener(e -> updateEnablement());
        JLabel hint = new JLabel(SettingsMessages.nav_refresh_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.nav_section_sort);
        row = OptionsDialog.addRow(form, row, null, sortAlphabeticallyBox, null);
        row = OptionsDialog.addRow(form, row, null, ignoreCaseBox, null);
        row = OptionsDialog.addRow(form, row, null, foldersFirstBox, null);
        OptionsDialog.addRow(form, row, null, hint, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        load(DBWorkbench.getPlatform().getPreferenceStore());
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.nav_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.NAVIGATOR_SORT_ALPHABETICALLY, ModelPreferences.NAVIGATOR_SORT_IGNORE_CASE,
            ModelPreferences.NAVIGATOR_SORT_FOLDERS_FIRST);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        sortAlphabeticallyBox.setSelected(store.getBoolean(ModelPreferences.NAVIGATOR_SORT_ALPHABETICALLY));
        ignoreCaseBox.setSelected(store.getBoolean(ModelPreferences.NAVIGATOR_SORT_IGNORE_CASE));
        foldersFirstBox.setSelected(store.getBoolean(ModelPreferences.NAVIGATOR_SORT_FOLDERS_FIRST));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(ModelPreferences.NAVIGATOR_SORT_ALPHABETICALLY, sortAlphabeticallyBox.isSelected());
        writer.setBoolean(ModelPreferences.NAVIGATOR_SORT_IGNORE_CASE, ignoreCaseBox.isSelected());
        writer.setBoolean(ModelPreferences.NAVIGATOR_SORT_FOLDERS_FIRST, foldersFirstBox.isSelected());
    }

    /**
     * Ignoring case only applies to the sort by name.
     */
    @Override
    void updateEnablement() {
        ignoreCaseBox.setEnabled(sortAlphabeticallyBox.isSelected());
    }
}
