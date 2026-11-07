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

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Errors and Timeouts, and the same page for one connection (SWT {@code PrefPageErrorHandle}).
 *
 * 설계:
 * <ul>
 *     <li>시간 제한(ms): 연결 열기 {@code JDBCDataSource.openConnection}(0 = 드라이버의 소켓 시간 제한까지 기다림), 연결 닫기
 *     {@code JDBCDataSource.closeConnection}, 연결 확인 {@code JDBCUtils.isConnectionAlive}와 SSH 터널. 화면에서 기다리는 시간이고 실제 소켓 시간
 *     제한은 드라이버 속성이다.</li>
 *     <li>연결 자동 복구와 재시도 횟수: {@code DBExecUtils.tryExecuteRecover}. 끊긴 연결이나 중단된 트랜잭션이면 다시 연결해 한 번 더 한다.
 *     Chromite의 {@code TransactionRecoveryGuard}가 복구가 사용자의 열린 트랜잭션을 버리게 되면 복구하지 않고 오류로 멈춘다(트랜잭션 안전 규칙).</li>
 *     <li>두지 않은 SWT 키: "오류가 나면 롤백"({@code query.rollback-on-error})은 문장이 실패하면 수동 커밋 세션의 트랜잭션을 묻지 않고 롤백한다
 *     ({@code JDBCStatementImpl.handleExecuteError}). 트랜잭션 안전 규칙에 어긋나 두지 않는다. "취소가 안 되면 시간 뒤 연결 닫기"
 *     ({@code execute.cancel.checkTimeout})는 문장 취소가 안 되면 세션 단위 취소(일반 JDBC는 실행 스레드 인터럽트, {@code JDBCDataSource.cancelCurrentExecution})로
 *     넘어간다. 드라이버에 따라 연결이 끊겨 열린 트랜잭션을 묻지 않고 잃을 수 있어 확인 전까지 두지 않는다(docs/chromite/swing-options.md).</li>
 *     <li>모든 키를 그 작업을 할 때마다 연결의 설정 저장소에서 읽으므로 연결별 값이 효과가 있고, 다시 연결하지 않아도 된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "Errors and Timeouts")
 */
final class ConnectionErrorsOptionsPage extends SettingsPage {

    private final JSpinner openTimeoutSpinner = createMillisSpinner();
    private final JSpinner closeTimeoutSpinner = createMillisSpinner();
    private final JSpinner validationTimeoutSpinner = createMillisSpinner();
    private final JCheckBox recoverBox = new JCheckBox(SettingsMessages.errors_recover);
    private final JSpinner recoverRetrySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    ConnectionErrorsOptionsPage(boolean global) {
        openTimeoutSpinner.setToolTipText(SettingsMessages.errors_open_timeout_tip);
        closeTimeoutSpinner.setToolTipText(SettingsMessages.errors_close_timeout_tip);
        validationTimeoutSpinner.setToolTipText(SettingsMessages.errors_validation_timeout_tip);
        recoverBox.setToolTipText(SettingsMessages.errors_recover_tip);
        recoverBox.addActionListener(e -> updateEnablement());
        recoverRetrySpinner.setToolTipText(SettingsMessages.errors_recover_retry_tip);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.errors_section_timeouts);
        row = OptionsDialog.addRow(form, row, SettingsMessages.errors_open_timeout, openTimeoutSpinner, SettingsMessages.errors_open_timeout_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.errors_close_timeout, closeTimeoutSpinner, SettingsMessages.errors_ms_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.errors_validation_timeout, validationTimeoutSpinner, SettingsMessages.errors_ms_hint);
        row = OptionsDialog.addSection(form, row, SettingsMessages.errors_section_recovery);
        row = OptionsDialog.addRow(form, row, null, recoverBox, null);
        OptionsDialog.addRow(form, row, SettingsMessages.errors_recover_retry, recoverRetrySpinner, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    private static JSpinner createMillisSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1000));
        // Milliseconds without digit grouping
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#")); //$NON-NLS-1$
        return spinner;
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.errors_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.CONNECTION_OPEN_TIMEOUT, ModelPreferences.CONNECTION_CLOSE_TIMEOUT,
            ModelPreferences.CONNECTION_VALIDATION_TIMEOUT, ModelPreferences.EXECUTE_RECOVER_ENABLED,
            ModelPreferences.EXECUTE_RECOVER_RETRY_COUNT);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        openTimeoutSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.CONNECTION_OPEN_TIMEOUT)));
        closeTimeoutSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.CONNECTION_CLOSE_TIMEOUT)));
        validationTimeoutSpinner.setValue(Math.max(0, store.getInt(ModelPreferences.CONNECTION_VALIDATION_TIMEOUT)));
        recoverBox.setSelected(store.getBoolean(ModelPreferences.EXECUTE_RECOVER_ENABLED));
        recoverRetrySpinner.setValue(Math.clamp(store.getInt(ModelPreferences.EXECUTE_RECOVER_RETRY_COUNT), 0, 100));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setInt(ModelPreferences.CONNECTION_OPEN_TIMEOUT, ((Number) openTimeoutSpinner.getValue()).intValue());
        writer.setInt(ModelPreferences.CONNECTION_CLOSE_TIMEOUT, ((Number) closeTimeoutSpinner.getValue()).intValue());
        writer.setInt(ModelPreferences.CONNECTION_VALIDATION_TIMEOUT, ((Number) validationTimeoutSpinner.getValue()).intValue());
        writer.setBoolean(ModelPreferences.EXECUTE_RECOVER_ENABLED, recoverBox.isSelected());
        writer.setInt(ModelPreferences.EXECUTE_RECOVER_RETRY_COUNT, ((Number) recoverRetrySpinner.getValue()).intValue());
    }

    /**
     * The retry count only applies with the recovery on.
     */
    @Override
    void updateEnablement() {
        recoverRetrySpinner.setEnabled(recoverBox.isEnabled() && recoverBox.isSelected());
    }
}
