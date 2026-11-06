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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.impl.net.SocksConstants;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;

/**
 * SOCKS 프록시 설정 칸(Proxy 탭): Host, Port, User name, Password, Remember password.
 *
 * 설계: SWT {@code SocksProxyConfiguratorUI}의 load/save를 그대로 따른다. 호스트·포트는 핸들러 속성
 * ({@link SocksConstants#PROP_HOST}, {@link SocksConstants#PROP_PORT}), 사용자·비밀번호는 핸들러의 사용자·비밀번호다
 * (비밀번호는 연결 레지스트리가 비밀 저장소에 저장한다). 모델의 프록시 구현({@code SocksProxyImpl})은 SOCKS만 지원하므로
 * HTTP 프록시는 고를 수 없다. SWT 화면의 "전역 네트워크 설정 열기" 링크는 Eclipse 설정 화면이라 두지 않았다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 */
final class SocksProxyPanel extends NetworkHandlerPanel {

    private final JTextField hostField = new JTextField();
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(SocksConstants.DEFAULT_SOCKS_PORT, 0, 65535, 1));
    private final JTextField userField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePasswordCheck = new JCheckBox("Remember password");
    private final JComponent component;

    SocksProxyPanel(@NotNull Runnable changeListener) {
        super(changeListener);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(portSpinner, "#"); //$NON-NLS-1$
        editor.getTextField().setColumns(6);
        portSpinner.setEditor(editor);
        hostField.getDocument().addDocumentListener(createDocumentListener());
        // Same as SocksProxyConfiguratorUI: the password is typed only when it is saved
        savePasswordCheck.addActionListener(e -> passwordField.setEnabled(savePasswordCheck.isSelected() && savePasswordCheck.isEnabled()));

        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        form.addRow("Proxy host", hostField);
        form.addRow("Port", ConnectionSettingsPanel.leftAligned(portSpinner));
        form.addGroupGap();
        form.addRow("User name", userField);
        form.addRow("Password", passwordField);
        form.addRow((JLabel) null, savePasswordCheck);
        component = createPage(form);
    }

    @NotNull
    @Override
    JComponent getComponent() {
        return component;
    }

    @Override
    void loadSettings(@NotNull DBWHandlerConfiguration configuration) {
        hostField.setText(CommonUtils.notEmpty(configuration.getStringProperty(SocksConstants.PROP_HOST)));
        String port = configuration.getStringProperty(SocksConstants.PROP_PORT);
        portSpinner.setValue(CommonUtils.isEmpty(port) ? SocksConstants.DEFAULT_SOCKS_PORT : CommonUtils.toInt(port));
        userField.setText(CommonUtils.notEmpty(configuration.getUserName()));
        passwordField.setText(CommonUtils.notEmpty(configuration.getPassword()));
        savePasswordCheck.setSelected(configuration.isSavePassword());
        passwordField.setEnabled(savePasswordCheck.isSelected());
    }

    @Override
    void saveSettings(@NotNull DBWHandlerConfiguration configuration) {
        configuration.setProperty(SocksConstants.PROP_HOST, hostField.getText().trim());
        configuration.setProperty(SocksConstants.PROP_PORT, portSpinner.getValue());
        configuration.setUserName(userField.getText().trim());
        configuration.setPassword(new String(passwordField.getPassword()));
        configuration.setSavePassword(savePasswordCheck.isSelected());
    }

    @Nullable
    @Override
    String getErrorMessage() {
        // SocksProxyConfiguratorUI.isComplete accepts anything; without a host the proxy can't be used
        return hostField.getText().isBlank() ? "Enter the proxy host." : null;
    }

    @Override
    void setFieldsEnabled(boolean enabled) {
        super.setFieldsEnabled(enabled);
        passwordField.setEnabled(enabled && savePasswordCheck.isSelected());
    }
}
