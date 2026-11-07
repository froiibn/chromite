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
import com.seizia.chromite.model.data.order.OrderingStrategy;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Query Results, and the same page for one connection: the model settings of result sets (row limit,
 * fetch size, binary and number display, the save preview, reading and sorting).
 *
 * 설계:
 * <ul>
 *     <li>모든 키를 실행 엔진·값 서식·결과 표가 연결의 설정 저장소에서 읽는다: Maximum rows {@code SQLQueryExecuteJob}·{@code SqlEditorPanel},
 *     가져오기 크기 {@code DBExecUtils.setStatementFetchSize}, 이진 값 {@code DBValueFormatting}, 날짜/시간·숫자 형식
 *     {@code DataSourceDescriptor.isUseNative*Format}, 저장 전 스크립트 {@link ResultSetGrid#PREF_CONFIRM_BEFORE_SAVE}. 그래서 연결별 값이 효과가 있고
 *     ({@link SettingsPage}), 결과를 가져올 때마다 읽으므로 다시 연결하지 않아도 다음 실행부터 보인다(표는 가져올 때 글자로 바꿔 둔다).</li>
 *     <li>이 행들은 원래 General 탭의 Query Results 절에 있었다. 연결 대화상자에서 같은 페이지를 쓰려고 탭으로 옮겼다. 문구 키는 그대로 쓴다.</li>
 *     <li>읽기와 정렬 절(SWT Data Editor 페이지의 나머지): 행 수 제한을 SQL에 넣기 {@code DBUtils}(쿼리 변환기), 끝까지 내리면 더 읽기
 *     {@link ResultSetViewerPanel#isAutoFetchNextSegment}, 열 정렬 위치 {@link OrderingStrategy}({@link ResultSetViewerPanel}의 서버·표 안 정렬
 *     선택), 필터할 때 감싸기 {@code StandardSQLDialectQueryGenerator}·{@code SQLSemanticProcessor}, 레이블 대신 열 이름
 *     {@code DBDAttributeBindingMeta}, 중첩 값 펼치기 {@code ComplexTypeAttributeTransformer} 등. 모두 연결의 설정 저장소에서 읽는다.</li>
 *     <li>두지 않은 것: 정렬 정책({@code resultset.order.policy}, 기본 키로 정렬)은 서버 정렬일 때 테이블 자체를 읽는 경로({@code JDBCTable.readData})만
 *     쓴다. Swing 결과 표는 늘 쿼리 창의 쿼리를 다시 읽으므로 효과가 없다. 참조 값 설명 열 패턴은 사전(참조 값 고르기) 화면이 Swing에 없어 두지 않았다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: General 탭 Query Results 절의 모델 키를 옮기고 연결별 값을 둠 (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 읽기와 정렬 절: SQL에 행 수 제한, 끝까지 내리면 더 읽기, 열 정렬 위치, 필터할 때 감싸기, 열 이름, 중첩 값
 *   (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class ResultSetOptionsPage extends SettingsPage {

    private final JSpinner maxRowsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 100));
    private final JCheckBox fetchSizeBox = new JCheckBox(SettingsMessages.results_use_fetch_size);
    private final JComboBox<String> binaryFormatCombo = new JComboBox<>(new String[]{
        SettingsMessages.results_binary_string, SettingsMessages.results_binary_hex, SettingsMessages.results_binary_base64});
    private final JSpinner binaryMaxLengthSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 16));
    private final JCheckBox nativeDateTimeBox = new JCheckBox(CoreMessages.options_native_datetime);
    private final JCheckBox nativeNumericBox = new JCheckBox(SettingsMessages.results_native_numeric);
    private final JCheckBox scientificNumericBox = new JCheckBox(SettingsMessages.results_scientific_numeric);
    private final JCheckBox confirmBeforeSaveBox = new JCheckBox(CoreMessages.options_confirm_before_save);
    private final JCheckBox maxRowsSqlBox = new JCheckBox(SettingsMessages.results_max_rows_sql);
    private final JCheckBox autoFetchBox = new JCheckBox(SettingsMessages.results_autofetch);
    // OrderingStrategy order: SMART, CLIENT_SIDE, SERVER_SIDE
    private final JComboBox<String> orderModeCombo = new JComboBox<>(new String[]{
        SettingsMessages.results_order_smart, SettingsMessages.results_order_client, SettingsMessages.results_order_server});
    private final JCheckBox forceSubselectBox = new JCheckBox(SettingsMessages.results_force_subselect);
    private final JCheckBox ignoreLabelBox = new JCheckBox(SettingsMessages.results_ignore_label);
    private final JCheckBox transformComplexBox = new JCheckBox(SettingsMessages.results_transform_complex);

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    ResultSetOptionsPage(boolean global) {
        // Read by the engine for every statement (DBExecUtils.setStatementFetchSize); drivers such as PostgreSQL then
        // stream the rows instead of loading the whole result before the first page
        fetchSizeBox.setToolTipText(SettingsMessages.results_use_fetch_size_tip);
        // The model setting of the SWT "Disable date/time formatting" (Data Formats page). The default data formatter
        // profile prints milliseconds only (yyyy-MM-dd HH:mm:ss.SSS), so the grid and the cell editor drop the micro- and
        // nanoseconds of timestamp values unless the driver's text is used.
        nativeDateTimeBox.setToolTipText(CoreMessages.options_native_datetime_tip);
        nativeNumericBox.setToolTipText(SettingsMessages.results_native_numeric_tip);
        confirmBeforeSaveBox.setToolTipText(CoreMessages.options_confirm_before_save_tip);
        maxRowsSqlBox.setToolTipText(SettingsMessages.results_max_rows_sql_tip);
        orderModeCombo.setToolTipText(SettingsMessages.results_order_mode_tip);
        forceSubselectBox.setToolTipText(SettingsMessages.results_force_subselect_tip);
        ignoreLabelBox.setToolTipText(SettingsMessages.results_ignore_label_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, CoreMessages.options_query_results);
        row = OptionsDialog.addRow(form, row, CoreMessages.options_max_rows, maxRowsSpinner, CoreMessages.options_max_rows_hint);
        row = OptionsDialog.addRow(form, row, null, fetchSizeBox, null);
        // Binary values in the grid: the formatter and the length of DBValueFormatting.formatBinaryString (display format UI)
        row = OptionsDialog.addRow(form, row, SettingsMessages.results_binary_format, binaryFormatCombo, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.results_binary_max_length, binaryMaxLengthSpinner,
            SettingsMessages.results_binary_max_length_hint);
        row = OptionsDialog.addRow(form, row, null, nativeDateTimeBox, null);
        // Data Formats page of SWT: numbers as the driver returns them, or in scientific notation (DBDFormatSettings)
        row = OptionsDialog.addRow(form, row, null, nativeNumericBox, null);
        row = OptionsDialog.addRow(form, row, null, scientificNumericBox, null);
        row = OptionsDialog.addRow(form, row, null, confirmBeforeSaveBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.results_section_reading);
        row = OptionsDialog.addRow(form, row, null, maxRowsSqlBox, null);
        row = OptionsDialog.addRow(form, row, null, autoFetchBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.results_order_mode, orderModeCombo, null);
        row = OptionsDialog.addRow(form, row, null, forceSubselectBox, null);
        row = OptionsDialog.addRow(form, row, null, ignoreLabelBox, null);
        OptionsDialog.addRow(form, row, null, transformComplexBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return CoreMessages.options_query_results;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.RESULT_SET_MAX_ROWS, ModelPreferences.RESULT_SET_USE_FETCH_SIZE,
            ModelPreferences.RESULT_SET_BINARY_PRESENTATION, ModelPreferences.RESULT_SET_BINARY_STRING_MAX_LEN,
            ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT, ModelPreferences.RESULT_NATIVE_NUMERIC_FORMAT,
            ModelPreferences.RESULT_SCIENTIFIC_NUMERIC_FORMAT, ResultSetGrid.PREF_CONFIRM_BEFORE_SAVE,
            ModelPreferences.RESULT_SET_MAX_ROWS_USE_SQL, ResultSetViewerPanel.PREF_AUTO_FETCH_NEXT_SEGMENT,
            ModelPreferences.RESULT_SET_ORDERING_STRATEGY, ModelPreferences.SQL_FILTER_FORCE_SUBSELECT,
            ModelPreferences.RESULT_SET_IGNORE_COLUMN_LABEL, ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        maxRowsSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.RESULT_SET_MAX_ROWS)));
        fetchSizeBox.setSelected(store.getBoolean(ModelPreferences.RESULT_SET_USE_FETCH_SIZE));
        binaryFormatCombo.setSelectedIndex(Math.max(0, indexOfBinaryFormat(store.getString(ModelPreferences.RESULT_SET_BINARY_PRESENTATION))));
        binaryMaxLengthSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.RESULT_SET_BINARY_STRING_MAX_LEN)));
        nativeDateTimeBox.setSelected(store.getBoolean(ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT));
        nativeNumericBox.setSelected(store.getBoolean(ModelPreferences.RESULT_NATIVE_NUMERIC_FORMAT));
        scientificNumericBox.setSelected(store.getBoolean(ModelPreferences.RESULT_SCIENTIFIC_NUMERIC_FORMAT));
        confirmBeforeSaveBox.setSelected(store.getBoolean(ResultSetGrid.PREF_CONFIRM_BEFORE_SAVE));
        maxRowsSqlBox.setSelected(store.getBoolean(ModelPreferences.RESULT_SET_MAX_ROWS_USE_SQL));
        autoFetchBox.setSelected(ResultSetViewerPanel.isAutoFetchNextSegment(store));
        orderModeCombo.setSelectedIndex(OrderingStrategy.get(store).ordinal());
        forceSubselectBox.setSelected(store.getBoolean(ModelPreferences.SQL_FILTER_FORCE_SUBSELECT));
        ignoreLabelBox.setSelected(store.getBoolean(ModelPreferences.RESULT_SET_IGNORE_COLUMN_LABEL));
        transformComplexBox.setSelected(store.getBoolean(ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES));
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setInt(ModelPreferences.RESULT_SET_MAX_ROWS, ((Number) maxRowsSpinner.getValue()).intValue());
        writer.setBoolean(ModelPreferences.RESULT_SET_USE_FETCH_SIZE, fetchSizeBox.isSelected());
        if (writer.isOverride()
            || indexOfBinaryFormat(writer.getStore().getString(ModelPreferences.RESULT_SET_BINARY_PRESENTATION)) != binaryFormatCombo.getSelectedIndex()) {
            writer.setString(ModelPreferences.RESULT_SET_BINARY_PRESENTATION, DBConstants.BINARY_FORMATS[binaryFormatCombo.getSelectedIndex()].getId());
        }
        writer.setInt(ModelPreferences.RESULT_SET_BINARY_STRING_MAX_LEN, ((Number) binaryMaxLengthSpinner.getValue()).intValue());
        writer.setBoolean(ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT, nativeDateTimeBox.isSelected());
        writer.setBoolean(ModelPreferences.RESULT_NATIVE_NUMERIC_FORMAT, nativeNumericBox.isSelected());
        writer.setBoolean(ModelPreferences.RESULT_SCIENTIFIC_NUMERIC_FORMAT, scientificNumericBox.isSelected());
        writer.setBoolean(ResultSetGrid.PREF_CONFIRM_BEFORE_SAVE, confirmBeforeSaveBox.isSelected());
        writer.setBoolean(ModelPreferences.RESULT_SET_MAX_ROWS_USE_SQL, maxRowsSqlBox.isSelected());
        writer.setBoolean(ResultSetViewerPanel.PREF_AUTO_FETCH_NEXT_SEGMENT, autoFetchBox.isSelected());
        // Stored as the enum name, as OrderingStrategy.get reads it
        writer.setString(ModelPreferences.RESULT_SET_ORDERING_STRATEGY, OrderingStrategy.values()[orderModeCombo.getSelectedIndex()].name());
        writer.setBoolean(ModelPreferences.SQL_FILTER_FORCE_SUBSELECT, forceSubselectBox.isSelected());
        writer.setBoolean(ModelPreferences.RESULT_SET_IGNORE_COLUMN_LABEL, ignoreLabelBox.isSelected());
        writer.setBoolean(ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES, transformComplexBox.isSelected());
    }

    /**
     * Position of a binary formatter id in {@link DBConstants#BINARY_FORMATS} (the combo's order), or -1.
     */
    private static int indexOfBinaryFormat(@Nullable String id) {
        for (int i = 0; i < DBConstants.BINARY_FORMATS.length; i++) {
            if (DBConstants.BINARY_FORMATS[i].getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
