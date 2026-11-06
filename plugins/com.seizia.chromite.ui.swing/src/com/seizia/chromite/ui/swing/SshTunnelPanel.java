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

import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DataSourceVariableResolver;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.ssh.SSHConstants;
import com.seizia.chromite.model.net.ssh.SSHTunnelImpl;
import com.seizia.chromite.model.net.ssh.SSHUtils;
import com.seizia.chromite.model.net.ssh.config.SSHAuthConfiguration;
import com.seizia.chromite.model.net.ssh.config.SSHHostConfiguration;
import com.seizia.chromite.model.net.ssh.registry.SSHSessionControllerDescriptor;
import com.seizia.chromite.model.net.ssh.registry.SSHSessionControllerRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.RuntimeUtils;
import com.seizia.chromite.utils.SystemVariablesResolver;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SSH 터널 설정 칸(SSH 탭).
 *
 * 설계: SWT {@code SSHTunnelDefaultConfiguratorUI}의 load/save를 모델 코드 그대로 따른다.
 * <ul>
 *     <li>대상 호스트(Host, Port, User name, Authentication, Private key, Password/Passphrase, Remember password)는
 *     {@link SSHUtils#loadHostConfigurations}/{@link SSHUtils#saveHostConfigurations}로 읽고 쓴다. 인증 방식은 SWT 콤보와 같은 순서의
 *     Password / Public key / Agent({@link SSHConstants.AuthType})이고, Agent 소켓 경로는 SWT처럼 Windows가 아닐 때만 보인다.
 *     공개 키는 데스크톱 SWT처럼 키 파일 경로({@link SSHAuthConfiguration.KeyFile})로 저장한다.
 *     비밀번호(또는 키 암호)는 핸들러 비밀번호라 연결 레지스트리가 비밀 저장소에 저장하고, Remember password를 끄면 저장하지 않는다.</li>
 *     <li>점프 서버: 읽은 점프 서버는 그대로 저장한다(SWT의 점프 서버 목록 편집 화면은 아직 옮기지 않았고, 몇 개가 있는지 안내만 한다).</li>
 *     <li>고급 설정: 구현(SSHJ 기본, {@link SSHSessionControllerRegistry}), Keep-alive·연결 시간 초과(ms), 로컬·원격 호스트와 포트,
 *     호스트 키 확인 건너뛰기, 터널 공유. 저장 규칙(0 이하는 지움, 기본 시간 초과는 저장 안 함)은 SWT {@code saveSettings}와 같다.</li>
 *     <li>Test tunnel: SWT {@code testTunnelConnection}처럼 폼 값으로 만든 핸들러 설정(변수 풀기, DB 연결 없음)으로 모델의
 *     {@link SSHTunnelImpl}을 열었다가 닫고, SSH 클라이언트·서버 버전을 보인다. 저장하지 않는 비밀번호도 이 시험에는 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class SshTunnelPanel extends NetworkHandlerPanel {

    private static final Log log = Log.getLog(SshTunnelPanel.class);

    private static final String[] AUTH_TYPE_NAMES = {
        ConnectionMessages.settings_password, ConnectionMessages.ssh_auth_public_key, ConnectionMessages.ssh_auth_agent};

    private final JComponent component;

    private final JTextField hostField = new JTextField();
    private final JSpinner portSpinner = createNumberSpinner(SSHConstants.DEFAULT_PORT, 1, 65535);
    private final JTextField userField = new JTextField();
    private final JComboBox<String> authTypeCombo = new JComboBox<>(AUTH_TYPE_NAMES);
    private final JLabel privateKeyLabel = new JLabel(ConnectionMessages.ssh_private_key);
    private final JTextField privateKeyField = new JTextField();
    private final JComponent privateKeyRow;
    private final JLabel passwordLabel = new JLabel(ConnectionMessages.settings_password);
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePasswordCheck = new JCheckBox(ConnectionMessages.settings_remember_password);
    private final JLabel agentSocketLabel = new JLabel(ConnectionMessages.ssh_agent_socket);
    private final JTextField agentSocketField = new JTextField();
    private final JLabel jumpHostsLabel = new JLabel();

    private final JComboBox<SSHSessionControllerDescriptor> implementationCombo = new JComboBox<>();
    private final JSpinner keepAliveSpinner = createNumberSpinner(0, 0, Integer.MAX_VALUE);
    private final JSpinner timeoutSpinner = createNumberSpinner(SSHConstants.DEFAULT_CONNECT_TIMEOUT, 0, Integer.MAX_VALUE);
    private final JTextField localHostField = new JTextField();
    private final JSpinner localPortSpinner = createNumberSpinner(0, 0, 65535);
    private final JTextField remoteHostField = new JTextField();
    private final JSpinner remotePortSpinner = createNumberSpinner(0, 0, 65535);
    private final JCheckBox bypassVerificationCheck = new JCheckBox(ConnectionMessages.ssh_bypass_verification);
    private final JCheckBox shareTunnelsCheck = new JCheckBox(ConnectionMessages.ssh_share_tunnel);
    private final JButton testButton = new JButton(ConnectionMessages.ssh_test);

    /**
     * Jump servers read from the configuration: saved again as they are.
     */
    private List<SSHHostConfiguration> jumpHosts = Collections.emptyList();
    /**
     * The loaded configuration, the base of the "Test Tunnel" configuration (data source, handler descriptor).
     */
    @Nullable
    private DBWHandlerConfiguration loadedConfiguration;

    SshTunnelPanel(@NotNull Runnable changeListener) {
        super(changeListener);
        for (SSHSessionControllerDescriptor descriptor : SSHSessionControllerRegistry.getInstance().getDescriptors()) {
            implementationCombo.addItem(descriptor);
        }
        implementationCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SSHSessionControllerDescriptor descriptor) {
                    setText(descriptor.getLabel());
                }
                return this;
            }
        });
        hostField.getDocument().addDocumentListener(createDocumentListener());
        privateKeyField.getDocument().addDocumentListener(createDocumentListener());
        authTypeCombo.addActionListener(e -> {
            updateAuthTypeFields();
            changeListener.run();
        });
        privateKeyRow = createPathField(privateKeyField, ConnectionMessages.ssh_private_key_file, false);
        agentSocketField.setToolTipText(ConnectionMessages.ssh_agent_socket_tip);
        localHostField.setToolTipText(ConnectionMessages.ssh_local_host_tip);
        localPortSpinner.setToolTipText(ConnectionMessages.ssh_local_port_tip);
        remoteHostField.setToolTipText(ConnectionMessages.ssh_remote_host_tip);
        remotePortSpinner.setToolTipText(ConnectionMessages.ssh_remote_port_tip);
        bypassVerificationCheck.setToolTipText(ConnectionMessages.ssh_bypass_verification_tip);
        shareTunnelsCheck.setToolTipText(ConnectionMessages.ssh_share_tunnel_tip);
        testButton.addActionListener(e -> testTunnel());

        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        JPanel hostPanel = new JPanel(new BorderLayout(12, 0));
        hostPanel.add(hostField, BorderLayout.CENTER);
        JPanel portPanel = new JPanel(new BorderLayout(6, 0));
        portPanel.add(new JLabel(ConnectionMessages.common_port), BorderLayout.WEST);
        portPanel.add(portSpinner, BorderLayout.CENTER);
        hostPanel.add(portPanel, BorderLayout.EAST);
        form.addRow(ConnectionMessages.ssh_host, hostPanel);
        form.addRow(ConnectionMessages.common_user_name, userField);
        form.addRow(ConnectionMessages.settings_authentication, ConnectionSettingsPanel.leftAligned(authTypeCombo));
        form.addRow(privateKeyLabel, privateKeyRow);
        form.addRow(passwordLabel, passwordField);
        form.addRow((JLabel) null, savePasswordCheck);
        form.addRow(agentSocketLabel, agentSocketField);
        form.addRow((JLabel) null, jumpHostsLabel);
        jumpHostsLabel.setForeground(ConnectionDialog.getThemeColor("Chromite.secondaryForeground", "Label.disabledForeground", Color.GRAY)); //$NON-NLS-1$ //$NON-NLS-2$

        form.addGroupGap();
        form.addRow(ConnectionMessages.ssh_implementation, ConnectionSettingsPanel.leftAligned(implementationCombo));
        form.addRow(ConnectionMessages.ssh_keep_alive, ConnectionSettingsPanel.leftAligned(keepAliveSpinner));
        form.addRow(ConnectionMessages.ssh_connect_timeout, ConnectionSettingsPanel.leftAligned(timeoutSpinner));
        form.addRow(ConnectionMessages.ssh_local_host, localHostField);
        form.addRow(ConnectionMessages.ssh_local_port, ConnectionSettingsPanel.leftAligned(localPortSpinner));
        form.addRow(ConnectionMessages.ssh_remote_host, remoteHostField);
        form.addRow(ConnectionMessages.ssh_remote_port, ConnectionSettingsPanel.leftAligned(remotePortSpinner));
        form.addRow((JLabel) null, bypassVerificationCheck);
        form.addRow((JLabel) null, shareTunnelsCheck);
        form.addGroupGap();
        form.addRow((JLabel) null, ConnectionSettingsPanel.leftAligned(testButton));
        component = createPage(form);
        updateAuthTypeFields();
    }

    @NotNull
    @Override
    JComponent getComponent() {
        return component;
    }

    @Override
    void loadSettings(@NotNull DBWHandlerConfiguration configuration) {
        loadedConfiguration = new DBWHandlerConfiguration(configuration);
        SSHHostConfiguration[] hosts;
        try {
            hosts = SSHUtils.loadHostConfigurations(configuration, false);
        } catch (DBException e) {
            log.warn("Can't load the SSH configuration", e);
            hosts = new SSHHostConfiguration[]{new SSHHostConfiguration(CommonUtils.notEmpty(SSHConstants.DEFAULT_USER_NAME), "",
                SSHConstants.DEFAULT_PORT, new SSHAuthConfiguration.Password("", true))};
        }
        jumpHosts = new ArrayList<>(Arrays.asList(hosts).subList(0, hosts.length - 1));
        jumpHostsLabel.setText(jumpHosts.isEmpty() ? "" : NLS.bind(ConnectionMessages.ssh_jump_hosts, jumpHosts.size())); //$NON-NLS-1$
        jumpHostsLabel.setVisible(!jumpHosts.isEmpty());

        SSHHostConfiguration target = hosts[hosts.length - 1];
        hostField.setText(target.hostname());
        portSpinner.setValue(target.port() > 0 ? target.port() : SSHConstants.DEFAULT_PORT);
        // SSHTunnelDefaultConfiguratorUI.ConfigurationWrapper: a new tunnel starts with the OS user name
        userField.setText(CommonUtils.isEmpty(target.username()) && !configuration.isEnabled()
            ? CommonUtils.notEmpty(SSHConstants.DEFAULT_USER_NAME) : target.username());
        SSHAuthConfiguration auth = target.auth();
        if (auth instanceof SSHAuthConfiguration.WithPassword withPassword) {
            passwordField.setText(CommonUtils.notEmpty(withPassword.password()));
            // A new tunnel saves the password by default (ConfigurationWrapper)
            savePasswordCheck.setSelected(withPassword.savePassword() || !configuration.isEnabled());
        }
        if (auth instanceof SSHAuthConfiguration.KeyFile keyFile) {
            authTypeCombo.setSelectedIndex(SSHConstants.AuthType.PUBLIC_KEY.ordinal());
            privateKeyField.setText(keyFile.path());
        } else if (auth instanceof SSHAuthConfiguration.KeyData) {
            // Key text saved by a server product: shown as a public key without a file (choose a file to replace it)
            authTypeCombo.setSelectedIndex(SSHConstants.AuthType.PUBLIC_KEY.ordinal());
            privateKeyField.setText("");
        } else if (auth instanceof SSHAuthConfiguration.Agent agent) {
            authTypeCombo.setSelectedIndex(SSHConstants.AuthType.AGENT.ordinal());
            agentSocketField.setText(CommonUtils.notEmpty(agent.authSockPath()));
        } else {
            authTypeCombo.setSelectedIndex(SSHConstants.AuthType.PASSWORD.ordinal());
        }

        // Same default as SSHTunnelDefaultConfiguratorUI.loadSettings: SSHJ when nothing is chosen
        String implementation = configuration.getStringProperty(SSHConstants.PROP_IMPLEMENTATION);
        SSHSessionControllerDescriptor selected = CommonUtils.isEmpty(implementation) ? null
            : SSHSessionControllerRegistry.getInstance().getDescriptor(implementation);
        if (selected == null) {
            for (int i = 0; i < implementationCombo.getItemCount(); i++) {
                if ("SSHJ".equals(implementationCombo.getItemAt(i).getLabel())) { //$NON-NLS-1$
                    selected = implementationCombo.getItemAt(i);
                }
            }
        }
        if (selected != null) {
            implementationCombo.setSelectedItem(selected);
        }
        bypassVerificationCheck.setSelected(configuration.getBooleanProperty(SSHConstants.PROP_BYPASS_HOST_VERIFICATION));
        shareTunnelsCheck.setSelected(configuration.getBooleanProperty(SSHConstants.PROP_SHARE_TUNNELS, true));
        shareTunnelsCheck.setVisible(!SSHUtils.DISABLE_SESSION_SHARING);
        localHostField.setText(CommonUtils.notEmpty(configuration.getStringProperty(SSHConstants.PROP_LOCAL_HOST)));
        localPortSpinner.setValue(Math.max(0, configuration.getIntProperty(SSHConstants.PROP_LOCAL_PORT)));
        remoteHostField.setText(CommonUtils.notEmpty(configuration.getStringProperty(SSHConstants.PROP_REMOTE_HOST)));
        remotePortSpinner.setValue(Math.max(0, configuration.getIntProperty(SSHConstants.PROP_REMOTE_PORT)));
        keepAliveSpinner.setValue(Math.max(0, configuration.getIntProperty(SSHConstants.PROP_ALIVE_INTERVAL)));
        int timeout = configuration.getIntProperty(SSHConstants.PROP_CONNECT_TIMEOUT);
        timeoutSpinner.setValue(timeout > 0 ? timeout : SSHConstants.DEFAULT_CONNECT_TIMEOUT);
        updateAuthTypeFields();
    }

    @Override
    void saveSettings(@NotNull DBWHandlerConfiguration configuration) {
        List<SSHHostConfiguration> hosts = new ArrayList<>(jumpHosts);
        hosts.add(getTargetHost());
        SSHUtils.saveHostConfigurations(configuration, hosts.toArray(SSHHostConfiguration[]::new));

        if (implementationCombo.getSelectedItem() instanceof SSHSessionControllerDescriptor descriptor) {
            configuration.setProperty(SSHConstants.PROP_IMPLEMENTATION, descriptor.getId());
        }
        configuration.setProperty(SSHConstants.PROP_BYPASS_HOST_VERIFICATION, bypassVerificationCheck.isSelected() ? Boolean.TRUE : null);
        configuration.setProperty(SSHConstants.PROP_SHARE_TUNNELS, shareTunnelsCheck.isSelected());
        configuration.setProperty(SSHConstants.PROP_LOCAL_HOST, localHostField.getText().trim());
        int localPort = (Integer) localPortSpinner.getValue();
        configuration.setProperty(SSHConstants.PROP_LOCAL_PORT, localPort <= 0 ? null : localPort);
        configuration.setProperty(SSHConstants.PROP_REMOTE_HOST, remoteHostField.getText().trim());
        int remotePort = (Integer) remotePortSpinner.getValue();
        configuration.setProperty(SSHConstants.PROP_REMOTE_PORT, remotePort <= 0 ? null : remotePort);
        int keepAlive = (Integer) keepAliveSpinner.getValue();
        configuration.setProperty(SSHConstants.PROP_ALIVE_INTERVAL, keepAlive <= 0 ? null : keepAlive);
        int timeout = (Integer) timeoutSpinner.getValue();
        // SSHTunnelDefaultConfiguratorUI.saveSettings writes the timeout only when it is not the default; a stored one is cleared here
        configuration.setProperty(SSHConstants.PROP_CONNECT_TIMEOUT,
            timeout != 0 && timeout != SSHConstants.DEFAULT_CONNECT_TIMEOUT ? timeout : null);
    }

    @Nullable
    @Override
    String getErrorMessage() {
        // SSHTunnelDefaultConfiguratorUI.validateConfiguration
        if (hostField.getText().isBlank()) {
            return ConnectionMessages.ssh_enter_host;
        }
        if (getAuthType() == SSHConstants.AuthType.PUBLIC_KEY && privateKeyField.getText().isBlank()) {
            return ConnectionMessages.ssh_choose_private_key;
        }
        return null;
    }

    @Override
    void setFieldsEnabled(boolean enabled) {
        super.setFieldsEnabled(enabled);
        if (enabled) {
            updateAuthTypeFields();
        }
    }

    @NotNull
    private SSHConstants.AuthType getAuthType() {
        return SSHConstants.AuthType.values()[Math.max(0, authTypeCombo.getSelectedIndex())];
    }

    /**
     * Shows the fields of the authentication type (SSHTunnelDefaultConfiguratorUI.updateAuthMethodVisibility).
     */
    private void updateAuthTypeFields() {
        SSHConstants.AuthType type = getAuthType();
        boolean key = type == SSHConstants.AuthType.PUBLIC_KEY;
        boolean password = type != SSHConstants.AuthType.AGENT;
        // The agent socket is not supported on Windows
        boolean agentSocket = type == SSHConstants.AuthType.AGENT && !RuntimeUtils.isWindows();
        privateKeyLabel.setVisible(key);
        privateKeyRow.setVisible(key);
        passwordLabel.setText(key ? ConnectionMessages.ssh_passphrase : ConnectionMessages.settings_password);
        passwordLabel.setVisible(password);
        passwordField.setVisible(password);
        savePasswordCheck.setVisible(password);
        agentSocketLabel.setVisible(agentSocket);
        agentSocketField.setVisible(agentSocket);
        component.revalidate();
        component.repaint();
    }

    /**
     * The target host of the form (SSHTunnelDefaultConfiguratorUI.CredentialsPanel.saveSettings, desktop: a key file).
     */
    @NotNull
    private SSHHostConfiguration getTargetHost() {
        String password = CommonUtils.nullIfEmpty(new String(passwordField.getPassword()).trim());
        boolean savePassword = savePasswordCheck.isSelected();
        SSHAuthConfiguration auth = switch (getAuthType()) {
            case PASSWORD -> new SSHAuthConfiguration.Password(password, savePassword);
            case PUBLIC_KEY -> new SSHAuthConfiguration.KeyFile(privateKeyField.getText().trim(), password, savePassword);
            case AGENT -> new SSHAuthConfiguration.Agent(CommonUtils.nullIfEmpty(agentSocketField.getText().trim()));
        };
        return new SSHHostConfiguration(userField.getText().trim(), hostField.getText().trim(), (Integer) portSpinner.getValue(), auth);
    }

    /**
     * Opens and closes the tunnel with the form values, like SSHTunnelDefaultConfiguratorUI.testTunnelConnection.
     */
    private void testTunnel() {
        String problem = getErrorMessage();
        if (problem != null) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.ssh_tunnel_title, problem);
            return;
        }
        if (loadedConfiguration == null) {
            return;
        }
        DBWHandlerConfiguration configuration = new DBWHandlerConfiguration(loadedConfiguration);
        configuration.setProperties(Collections.emptyMap());
        saveSettings(configuration);
        // The typed password is used even when it is not saved (SWT asks for it in that case)
        configuration.setPassword(CommonUtils.nullIfEmpty(new String(passwordField.getPassword()).trim()));
        DBPDataSourceContainer dataSource = configuration.getDataSource();
        if (dataSource != null) {
            configuration.resolveDynamicVariables(new DataSourceVariableResolver(dataSource, dataSource.getConnectionConfiguration()));
        } else {
            configuration.resolveDynamicVariables(SystemVariablesResolver.INSTANCE);
        }
        // The tunnel is tested without the database
        configuration.setDataSource(null);
        long start = System.currentTimeMillis();
        try {
            String[] versions = ProgressDialog.runTask(ConnectionMessages.ssh_test_task, monitor -> {
                monitor.beginTask(ConnectionMessages.ssh_test_open, 2);
                SSHTunnelImpl tunnel = new SSHTunnelImpl();
                DBPConnectionConfiguration connectionConfiguration = new DBPConnectionConfiguration();
                connectionConfiguration.setHostName("localhost"); //$NON-NLS-1$
                connectionConfiguration.setHostPort(configuration.getStringProperty(DBWHandlerConfiguration.PROP_PORT));
                try {
                    tunnel.initializeHandler(monitor, configuration, connectionConfiguration);
                    monitor.worked(1);
                    try {
                        return new String[]{
                            tunnel.getImplementation().getClientVersion(),
                            tunnel.getImplementation().getServerVersion()
                        };
                    } finally {
                        monitor.subTask(ConnectionMessages.ssh_test_close);
                        tunnel.closeTunnel(monitor);
                    }
                } finally {
                    monitor.done();
                }
            });
            JOptionPane.showMessageDialog(component,
                NLS.bind(ConnectionMessages.ssh_test_result, new Object[] {
                    System.currentTimeMillis() - start, CommonUtils.notEmpty(versions[0]), CommonUtils.notEmpty(versions[1])}),
                ConnectionMessages.ssh_tunnel_title,
                JOptionPane.INFORMATION_MESSAGE);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.ssh_tunnel_title, ConnectionMessages.ssh_test_error, e);
        }
    }

    @NotNull
    private static JSpinner createNumberSpinner(int value, int minimum, int maximum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, 1));
        // Plain numbers without digit grouping
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#"); //$NON-NLS-1$
        editor.getTextField().setColumns(7);
        spinner.setEditor(editor);
        return spinner;
    }
}
