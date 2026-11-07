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

import com.seizia.chromite.ext.altibase.AltibaseConstants;
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
 * Tools &gt; Options &gt; Altibase, and the same page for an Altibase connection (SWT {@code PrefPageAltibase}).
 *
 * 설계:
 * <ul>
 *     <li>읽는 곳: PRINT 출력 {@code AltibaseDataSource}(쿼리 창이 문장마다 서버 출력을 읽을 때), 실행 계획 들여쓰기
 *     {@code AltibasePlanNode.getPlanString}. 둘 다 연결의 설정 저장소에서 그 작업을 할 때 읽으므로 다시 연결하지 않아도 된다.</li>
 *     <li>기본값(둘 다 켬)은 Altibase 번들의 설정 초기화가 넣는다. 번들이 아직 시작되지 않았으면 꺼짐으로 읽히므로 Oracle 페이지처럼 값을 읽기 전에
 *     번들의 클래스를 불러 시작시킨다.</li>
 *     <li>두지 않은 것: 실행 계획 방식({@code altibase.explain.plan.type})의 "EXPLAIN PLAN = ON"은 계획을 얻으려고 문장을 실제로 실행한다
 *     ({@code AltibaseExecutionPlan}: SELECT가 아니면 {@code execute}). 계획 보기가 데이터를 바꿀 수 있어 확인 절차 없이 고를 수 있게 두지 않는다.
 *     기본값(ONLY, 실행하지 않음)으로 동작한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class AltibaseOptionsPage extends SettingsPage {

    private final JCheckBox dbmsOutputBox = new JCheckBox(SettingsMessages.altibase_dbms_output);
    private final JCheckBox planPrefixBox = new JCheckBox(SettingsMessages.altibase_plan_prefix);

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    AltibaseOptionsPage(boolean global) {
        // Starts the Altibase bundle (lazy activation) so that its preference initializer has set the defaults
        FrameworkUtil.getBundle(AltibaseConstants.class);
        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.altibase_tab);
        row = OptionsDialog.addRow(form, row, null, dbmsOutputBox, null);
        OptionsDialog.addRow(form, row, null, planPrefixBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.altibase_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(AltibaseConstants.PREF_DBMS_OUTPUT, AltibaseConstants.PREF_PLAN_PREFIX);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        dbmsOutputBox.setSelected(store.getBoolean(AltibaseConstants.PREF_DBMS_OUTPUT));
        planPrefixBox.setSelected(store.getBoolean(AltibaseConstants.PREF_PLAN_PREFIX));
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(AltibaseConstants.PREF_DBMS_OUTPUT, dbmsOutputBox.isSelected());
        writer.setBoolean(AltibaseConstants.PREF_PLAN_PREFIX, planPrefixBox.isSelected());
    }
}
