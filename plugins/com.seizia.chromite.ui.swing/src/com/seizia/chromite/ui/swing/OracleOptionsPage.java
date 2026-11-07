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

import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.osgi.framework.FrameworkUtil;

import javax.swing.*;
import java.awt.*;

/**
 * Tools &gt; Options &gt; Oracle: the global Oracle settings of SWT's Oracle preference page ({@code PrefPageOracle}) that the
 * Oracle model reads from the connection's preference store.
 *
 * 설계:
 * <ul>
 *     <li>키는 Oracle 모델 상수({@link OracleConstants})다. 읽는 곳: 실행 계획 테이블 {@code OracleDataSource.getPlanTableName}
 *     (비우면 PLAN_TABLE, TOAD_PLAN_TABLE 순으로 찾음, 연결마다 처음 한 번 찾고 기억), ROWID 열 {@code OracleTable.hasRowIdPseudoAttribute},
 *     DBMS_OUTPUT {@code OracleOutputReader.isServerOutputEnabled}(쿼리 창은 문장마다 읽고, 서버의 켜기·끄기는 세션을 열 때와
 *     설정이 바뀐 뒤 그 세션의 다음 문장 전에 함), 모든 동의어 읽기
 *     {@code OracleSchema}의 동의어 조회, 스크립트 이스케이프 처리 끄기 {@code OracleSQLDialect.isDisableScriptEscapeProcessing}.</li>
 *     <li>SWT의 같은 페이지에 있던 동의어·시퀀스 메타데이터 검색과 DATE를 DATE로 보이기는 연결의 공급자 속성이라 연결 대화상자의
 *     Provider Properties 탭에서 이미 바꿀 수 있다. 여기 두지 않는다.</li>
 *     <li>기본값(ROWID, DBMS_OUTPUT, 모든 동의어, 이스케이프 끄기 모두 켬)은 Oracle 번들의 설정 초기화가 넣는다. 초기화는 번들이 시작될 때
 *     ({@code OracleActivator}가 자기 설정 노드를 읽음) 돈다. 아직 Oracle 연결을 쓰지 않아 번들이 시작되지 않았으면 기본값 대신 꺼짐이 읽히므로,
 *     값을 읽기 전에 번들의 클래스를 불러 시작시킨다(지연 활성화).</li>
 *     <li>Maximum rows처럼 플랫폼 설정 저장소에 쓴다. 연결별 값을 두지 않은 모든 Oracle 연결에 적용된다. OK에서 바뀐 값만 저장한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-options.md "Oracle")
 * - 2026-11-08 DBMS_OUTPUT 도구 설명: 열린 쿼리 창에도 다음 문장부터 적용(다시 연결할 필요 없음)
 *   (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 */
final class OracleOptionsPage extends JPanel {

    private final DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
    private final JTextField planTableField = new JTextField(20);
    private final JCheckBox rowIdBox = new JCheckBox(SettingsMessages.oracle_rowid);
    private final JCheckBox dbmsOutputBox = new JCheckBox(SettingsMessages.oracle_dbms_output);
    private final JCheckBox readAllSynonymsBox = new JCheckBox(SettingsMessages.oracle_read_all_synonyms);
    private final JCheckBox disableEscapeBox = new JCheckBox(SettingsMessages.oracle_disable_escape);

    OracleOptionsPage() {
        super(new BorderLayout());
        // Starts the Oracle bundle (lazy activation) so that its preference initializer has set the defaults
        FrameworkUtil.getBundle(OracleConstants.class);
        planTableField.setText(store.getString(OracleConstants.PREF_EXPLAIN_TABLE_NAME));
        rowIdBox.setSelected(store.getBoolean(OracleConstants.PREF_SUPPORT_ROWID));
        rowIdBox.setToolTipText(SettingsMessages.oracle_rowid_tip);
        dbmsOutputBox.setSelected(store.getBoolean(OracleConstants.PREF_DBMS_OUTPUT));
        dbmsOutputBox.setToolTipText(SettingsMessages.oracle_dbms_output_tip);
        readAllSynonymsBox.setSelected(store.getBoolean(OracleConstants.PREF_DBMS_READ_ALL_SYNONYMS));
        readAllSynonymsBox.setToolTipText(SettingsMessages.oracle_read_all_synonyms_tip);
        disableEscapeBox.setSelected(store.getBoolean(OracleConstants.PREF_DISABLE_SCRIPT_ESCAPE_PROCESSING));
        disableEscapeBox.setToolTipText(SettingsMessages.oracle_disable_escape_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.oracle_section_execution);
        row = OptionsDialog.addRow(form, row, SettingsMessages.oracle_plan_table, planTableField, SettingsMessages.oracle_plan_table_hint);
        row = OptionsDialog.addRow(form, row, null, dbmsOutputBox, null);
        row = OptionsDialog.addRow(form, row, null, disableEscapeBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.oracle_section_metadata);
        row = OptionsDialog.addRow(form, row, null, rowIdBox, null);
        OptionsDialog.addRow(form, row, null, readAllSynonymsBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
    }

    /**
     * Saves the changed values (Options dialog OK).
     */
    void apply() {
        boolean changed = false;
        String planTable = planTableField.getText().trim();
        if (!planTable.equals(store.getString(OracleConstants.PREF_EXPLAIN_TABLE_NAME))) {
            store.setValue(OracleConstants.PREF_EXPLAIN_TABLE_NAME, planTable);
            changed = true;
        }
        changed |= setBoolean(OracleConstants.PREF_SUPPORT_ROWID, rowIdBox.isSelected());
        changed |= setBoolean(OracleConstants.PREF_DBMS_OUTPUT, dbmsOutputBox.isSelected());
        changed |= setBoolean(OracleConstants.PREF_DBMS_READ_ALL_SYNONYMS, readAllSynonymsBox.isSelected());
        changed |= setBoolean(OracleConstants.PREF_DISABLE_SCRIPT_ESCAPE_PROCESSING, disableEscapeBox.isSelected());
        if (changed) {
            PrefUtils.savePreferenceStore(store);
        }
    }

    private boolean setBoolean(@NotNull String key, boolean value) {
        if (value == store.getBoolean(key)) {
            return false;
        }
        store.setValue(key, value);
        return true;
    }
}
