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
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.model.sql.SQLScriptErrorHandling;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Query Execution: how query windows split scripts into statements, which parameters they
 * recognize, and what a failed statement does to the rest of the script.
 *
 * 설계:
 * <ul>
 *     <li>SWT의 SQL Processing 설정 페이지({@code PrefPageSQLExecute}) 중 Swing 제품이 실제로 읽는 키만 둔다. 구분 기호와
 *     매개 변수 키는 실행 엔진의 구문 관리자({@code SQLSyntaxManager.init})와 스크립트 파서({@code SQLScriptParser},
 *     {@code DefaultSQLQueryParameterParser})가 연결의 설정 저장소에서 읽는다. 쿼리 창은 실행할 때마다 파서 문맥을 새로 만들므로
 *     ({@link com.seizia.chromite.model.sql.exec.SQLScriptBatches#createParserContext}) 다시 연결하지 않아도 다음 실행부터 적용된다.</li>
 *     <li>값은 Maximum rows처럼 플랫폼 설정 저장소에 쓴다. 연결의 설정 저장소는 이것을 부모로 가지므로 연결별 값을 두지 않은 모든 연결에 적용된다.
 *     키는 SWT와 같다(새 키를 만들지 않음).</li>
 *     <li>오류 처리({@link #PREF_SCRIPT_ERROR_HANDLING}, SWT {@code SQLPreferenceConstants.SCRIPT_ERROR_HANDLING}): 이 키를 정의하던 SWT 번들이
 *     지워져 상수를 여기 둔다. 화면은 "멈춤"과 "건너뛰고 계속" 둘만 둔다. SWT의 "멈추고 롤백"·"멈추고 커밋"은 오류 뒤 세션의 트랜잭션을 묻지 않고
 *     끝내므로 트랜잭션 안전 규칙(조용히 커밋·롤백하지 않음)에 어긋난다. 그래서 저장된 값이 무엇이든 멈추면 트랜잭션을 그대로 둔다
 *     ({@link #getEngineErrorHandling}). 열린 트랜잭션은 쿼리 상태 줄에 보이고 사용자가 커밋하거나 롤백한다.</li>
 *     <li>스크립트 커밋 방식({@code script.commit.type}: 끝에서 커밋, N줄마다 커밋, 문장마다 자동 커밋)은 두지 않는다. 모두 묻지 않고 커밋하거나
 *     ({@code AUTOCOMMIT}은 세션을 자동 커밋으로 바꿔 열린 트랜잭션을 커밋한다) 같은 규칙에 어긋난다. 엔진 기본값 {@code NO_COMMIT}을 쓴다.</li>
 *     <li>쿼리 창 세션({@link QueryWindowContext#PREF_SEPARATE_CONNECTION}, SWT SQL Editor 페이지의 "Open separate connection for each editor"):
 *     기본(내장·단일 연결 DB 빼고 창마다) / 항상 / 사용 안 함. 기본은 저장하지 않는다(기본값으로 되돌림). 연결 대화상자에서 연결마다 바꿀 수 있다.
 *     이미 세션을 정한 창은 바뀌지 않고 바꾼 뒤 연결하는 창부터 적용된다.</li>
 *     <li>연결별 값({@link SettingsPage}): 연결 대화상자 Options &gt; Settings에서 같은 페이지로 이 연결만의 값을 둔다(SWT {@code PrefPageSQLExecute}가
 *     연결 속성 페이지였던 것과 같음). 쿼리 창 세션은 연결 대화상자 Connection Properties에 따로 있어 연결별 페이지에는 두지 않는다.
 *     모든 키를 실행할 때마다 읽으므로 다시 연결하지 않아도 된다.</li>
 * </ul>
 * 켜고 끄는 설정은 누를 때 바로, 나머지 값은 페이지의 [저장]에서 저장한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 문 구분 기호, 매개 변수, 오류 처리 (docs/chromite/swing-options.md)
 * - 2026-11-07 쿼리 창 세션(쿼리 창마다 별도 연결) (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-08 {@link SettingsPage}로 바꿈: 연결별 값(연결 대화상자) (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-12 Options 저장 표준(앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class SqlExecutionOptionsPage extends SettingsPage {

    /**
     * SWT {@code SQLPreferenceConstants.SCRIPT_ERROR_HANDLING}: a {@link SQLScriptErrorHandling} name.
     */
    static final String PREF_SCRIPT_ERROR_HANDLING = "script.error.handling"; //$NON-NLS-1$

    // Order of the query window session combo
    private static final ModelPreferences.SeparateConnectionBehavior[] SEPARATE_CONNECTION_MODES = {
        ModelPreferences.SeparateConnectionBehavior.DEFAULT,
        ModelPreferences.SeparateConnectionBehavior.ALWAYS,
        ModelPreferences.SeparateConnectionBehavior.NEVER,
    };

    private static final ModelPreferences.SQLScriptStatementDelimiterMode[] BLANK_LINE_MODES = {
        ModelPreferences.SQLScriptStatementDelimiterMode.SMART,
        ModelPreferences.SQLScriptStatementDelimiterMode.BLANK_LINE_AND_SEPARATOR,
        ModelPreferences.SQLScriptStatementDelimiterMode.ONLY_SEPARATOR,
    };

    private final boolean global;
    private final JTextField delimiterField = new JTextField(6);
    private final JCheckBox ignoreNativeDelimiterBox = new JCheckBox(SettingsMessages.exec_ignore_native_delimiter);
    private final JComboBox<String> blankLineCombo = new JComboBox<>(new String[]{
        SettingsMessages.exec_blank_line_smart, SettingsMessages.exec_blank_line_always, SettingsMessages.exec_blank_line_never});
    private final JCheckBox removeTrailingDelimiterBox = new JCheckBox(SettingsMessages.exec_remove_trailing_delimiter);
    private final JCheckBox parametersBox = new JCheckBox(SettingsMessages.exec_parameters_enabled);
    private final JTextField namedPrefixField = new JTextField(3);
    private final JCheckBox anonymousParametersBox = new JCheckBox(SettingsMessages.exec_anonymous_parameters);
    private final JTextField anonymousMarkField = new JTextField(3);
    private final JCheckBox embeddedParametersBox = new JCheckBox(SettingsMessages.exec_parameters_in_embedded_code);
    private final JCheckBox variablesBox = new JCheckBox(SettingsMessages.exec_variables_enabled);
    private final JTextField commandPrefixField = new JTextField(3);
    private final JComboBox<String> errorHandlingCombo = new JComboBox<>(new String[]{
        SettingsMessages.exec_on_error_stop, SettingsMessages.exec_on_error_ignore});
    private final JComboBox<String> separateConnectionCombo = new JComboBox<>(new String[]{
        SettingsMessages.exec_separate_connection_default, SettingsMessages.exec_separate_connection_always,
        SettingsMessages.exec_separate_connection_never});

    /**
     * @param global the global settings (Options); otherwise one connection's settings, without the query window
     *               session (the connection dialog has it on Connection Properties)
     */
    SqlExecutionOptionsPage(boolean global) {
        this.global = global;
        delimiterField.setToolTipText(SettingsMessages.exec_delimiter_tip);
        ignoreNativeDelimiterBox.setToolTipText(SettingsMessages.exec_ignore_native_delimiter_tip);
        blankLineCombo.setToolTipText(SettingsMessages.exec_blank_line_tip);
        removeTrailingDelimiterBox.setToolTipText(SettingsMessages.exec_remove_trailing_delimiter_tip);
        parametersBox.addActionListener(e -> updateEnablement());
        anonymousParametersBox.addActionListener(e -> updateEnablement());
        embeddedParametersBox.setToolTipText(SettingsMessages.exec_parameters_in_embedded_code_tip);
        commandPrefixField.setToolTipText(SettingsMessages.exec_command_prefix_tip);
        separateConnectionCombo.setToolTipText(SettingsMessages.exec_separate_connection_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.exec_section_delimiters);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_delimiter, delimiterField,
            NLS.bind(SettingsMessages.exec_delimiter_hint, SQLConstants.DEFAULT_STATEMENT_DELIMITER));
        row = OptionsDialog.addRow(form, row, null, ignoreNativeDelimiterBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_blank_line, blankLineCombo, null);
        row = OptionsDialog.addRow(form, row, null, removeTrailingDelimiterBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.exec_section_parameters);
        row = OptionsDialog.addRow(form, row, null, parametersBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_named_prefix, namedPrefixField,
            NLS.bind(SettingsMessages.exec_default_hint, SQLConstants.DEFAULT_PARAMETER_PREFIX));
        row = OptionsDialog.addRow(form, row, null, anonymousParametersBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_anonymous_mark, anonymousMarkField,
            NLS.bind(SettingsMessages.exec_default_hint, SQLConstants.DEFAULT_PARAMETER_MARK));
        row = OptionsDialog.addRow(form, row, null, embeddedParametersBox, null);
        row = OptionsDialog.addRow(form, row, null, variablesBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_command_prefix, commandPrefixField,
            NLS.bind(SettingsMessages.exec_default_hint, SQLConstants.DEFAULT_CONTROL_COMMAND_PREFIX));
        row = OptionsDialog.addSection(form, row, SettingsMessages.exec_section_errors);
        row = OptionsDialog.addRow(form, row, SettingsMessages.exec_on_error, errorHandlingCombo, SettingsMessages.exec_on_error_hint);
        if (global) {
            row = OptionsDialog.addSection(form, row, SettingsMessages.exec_section_sessions);
            OptionsDialog.addRow(form, row, SettingsMessages.exec_separate_connection, separateConnectionCombo,
                SettingsMessages.exec_separate_connection_hint);
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.exec_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        List<String> keys = new ArrayList<>(List.of(
            ModelPreferences.SCRIPT_STATEMENT_DELIMITER, ModelPreferences.SCRIPT_IGNORE_NATIVE_DELIMITER,
            ModelPreferences.SCRIPT_STATEMENT_DELIMITER_BLANK, ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER,
            ModelPreferences.SQL_PARAMETERS_ENABLED, ModelPreferences.SQL_NAMED_PARAMETERS_PREFIX,
            ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED, ModelPreferences.SQL_ANONYMOUS_PARAMETERS_MARK,
            ModelPreferences.SQL_PARAMETERS_IN_EMBEDDED_CODE_ENABLED, ModelPreferences.SQL_VARIABLES_ENABLED,
            ModelPreferences.SQL_CONTROL_COMMAND_PREFIX, PREF_SCRIPT_ERROR_HANDLING));
        if (global) {
            keys.add(QueryWindowContext.PREF_SEPARATE_CONNECTION);
        }
        return keys;
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        delimiterField.setText(store.getString(ModelPreferences.SCRIPT_STATEMENT_DELIMITER));
        ignoreNativeDelimiterBox.setSelected(store.getBoolean(ModelPreferences.SCRIPT_IGNORE_NATIVE_DELIMITER));
        blankLineCombo.setSelectedIndex(indexOf(ModelPreferences.SQLScriptStatementDelimiterMode.fromPreferences(store)));
        removeTrailingDelimiterBox.setSelected(store.getBoolean(ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER));
        parametersBox.setSelected(store.getBoolean(ModelPreferences.SQL_PARAMETERS_ENABLED));
        namedPrefixField.setText(store.getString(ModelPreferences.SQL_NAMED_PARAMETERS_PREFIX));
        anonymousParametersBox.setSelected(store.getBoolean(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED));
        anonymousMarkField.setText(store.getString(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_MARK));
        embeddedParametersBox.setSelected(store.getBoolean(ModelPreferences.SQL_PARAMETERS_IN_EMBEDDED_CODE_ENABLED));
        variablesBox.setSelected(store.getBoolean(ModelPreferences.SQL_VARIABLES_ENABLED));
        commandPrefixField.setText(store.getString(ModelPreferences.SQL_CONTROL_COMMAND_PREFIX));
        errorHandlingCombo.setSelectedIndex(isIgnoreErrors(store) ? 1 : 0);
        if (global) {
            separateConnectionCombo.setSelectedIndex(List.of(SEPARATE_CONNECTION_MODES).indexOf(
                ModelPreferences.SeparateConnectionBehavior.parse(store.getString(QueryWindowContext.PREF_SEPARATE_CONNECTION))));
        }
        updateEnablement();
    }

    /**
     * Whether failed statements are skipped (the script goes on) rather than stopping the script.
     */
    static boolean isIgnoreErrors(@NotNull DBPPreferenceStore store) {
        return SQLScriptErrorHandling.IGNORE.name().equals(store.getString(PREF_SCRIPT_ERROR_HANDLING));
    }

    /**
     * The engine's error handling for a query window run: skip failed statements, or stop without ending the session's
     * transaction. With the commit type {@code NO_COMMIT} (the engine default, never changed here) the engine neither
     * commits nor rolls back at the end of a stopped script for {@link SQLScriptErrorHandling#STOP_COMMIT}, while the
     * engine default {@link SQLScriptErrorHandling#STOP_ROLLBACK} rolls the open transaction back without asking.
     */
    @NotNull
    static SQLScriptErrorHandling getEngineErrorHandling(@NotNull DBPPreferenceStore store) {
        return isIgnoreErrors(store) ? SQLScriptErrorHandling.IGNORE : SQLScriptErrorHandling.STOP_COMMIT;
    }

    @Override
    void save(@NotNull Writer writer) {
        DBPPreferenceStore store = writer.getStore();
        // Empty fields go back to the default (Writer.setStringOrDefault)
        writer.setStringOrDefault(ModelPreferences.SCRIPT_STATEMENT_DELIMITER, delimiterField.getText().trim());
        writer.setBoolean(ModelPreferences.SCRIPT_IGNORE_NATIVE_DELIMITER, ignoreNativeDelimiterBox.isSelected());
        writer.setString(ModelPreferences.SCRIPT_STATEMENT_DELIMITER_BLANK, BLANK_LINE_MODES[blankLineCombo.getSelectedIndex()].name());
        writer.setBoolean(ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER, removeTrailingDelimiterBox.isSelected());
        writer.setBoolean(ModelPreferences.SQL_PARAMETERS_ENABLED, parametersBox.isSelected());
        writer.setStringOrDefault(ModelPreferences.SQL_NAMED_PARAMETERS_PREFIX, namedPrefixField.getText().trim());
        writer.setBoolean(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED, anonymousParametersBox.isSelected());
        writer.setStringOrDefault(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_MARK, anonymousMarkField.getText().trim());
        writer.setBoolean(ModelPreferences.SQL_PARAMETERS_IN_EMBEDDED_CODE_ENABLED, embeddedParametersBox.isSelected());
        writer.setBoolean(ModelPreferences.SQL_VARIABLES_ENABLED, variablesBox.isSelected());
        writer.setStringOrDefault(ModelPreferences.SQL_CONTROL_COMMAND_PREFIX, commandPrefixField.getText().trim());
        boolean ignoreErrors = errorHandlingCombo.getSelectedIndex() == 1;
        if (writer.isOverride() || ignoreErrors != isIgnoreErrors(store)) {
            store.setValue(PREF_SCRIPT_ERROR_HANDLING, ignoreErrors ? SQLScriptErrorHandling.IGNORE.name() : SQLScriptErrorHandling.STOP_ROLLBACK.name());
            writer.markChanged();
        }
        if (!global) {
            return;
        }
        ModelPreferences.SeparateConnectionBehavior separate = SEPARATE_CONNECTION_MODES[separateConnectionCombo.getSelectedIndex()];
        if (separate != ModelPreferences.SeparateConnectionBehavior.parse(store.getString(QueryWindowContext.PREF_SEPARATE_CONNECTION))) {
            // The default is not written: connections without a value of their own follow the code's default
            if (separate == ModelPreferences.SeparateConnectionBehavior.DEFAULT) {
                store.setToDefault(QueryWindowContext.PREF_SEPARATE_CONNECTION);
            } else {
                store.setValue(QueryWindowContext.PREF_SEPARATE_CONNECTION, separate.name());
            }
            writer.markChanged();
        }
    }

    /**
     * The named prefix and the mark only apply with their parameter kind on (and while the page is enabled).
     */
    @Override
    void updateEnablement() {
        namedPrefixField.setEnabled(parametersBox.isEnabled() && parametersBox.isSelected());
        anonymousMarkField.setEnabled(anonymousParametersBox.isEnabled() && anonymousParametersBox.isSelected());
    }

    private static int indexOf(@NotNull ModelPreferences.SQLScriptStatementDelimiterMode mode) {
        for (int i = 0; i < BLANK_LINE_MODES.length; i++) {
            if (BLANK_LINE_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }
}
