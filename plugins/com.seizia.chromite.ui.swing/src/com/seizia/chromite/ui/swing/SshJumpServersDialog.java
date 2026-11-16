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

import com.seizia.chromite.model.net.ssh.SSHConstants;
import com.seizia.chromite.model.net.ssh.config.SSHAuthConfiguration;
import com.seizia.chromite.model.net.ssh.config.SSHHostConfiguration;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 점프 서버 목록 편집 대화상자(SSH 탭의 Jump servers → Edit…).
 *
 * 설계: SWT {@code SSHTunnelDefaultConfiguratorUI}의 점프 서버 표와 같은 값이다. 점프 서버는 대상 SSH 호스트 앞에 차례로 거치는 호스트이고,
 * 모델은 [점프 서버…, 대상 호스트] 순서의 {@link SSHHostConfiguration} 배열로 저장한다({@code SSHUtils.saveHostConfigurations}).
 * <ul>
 *     <li>목록: {@code 사용자@호스트:포트}, 위·아래로 순서 바꾸기, 추가·편집·삭제. 순서는 거치는 순서다(첫 줄이 먼저 접속).</li>
 *     <li>한 서버 편집: 호스트, 포트, 사용자, 인증(Password / Public key / Agent), 키 파일, 비밀번호 또는 키 암호, Remember password.
 *     SSH 탭의 대상 호스트 칸과 같은 규칙이다. 비밀번호는 핸들러 설정과 함께 레지스트리가 보안 저장소에 둔다.</li>
 *     <li>서버 제품이 저장한 키 내용({@link SSHAuthConfiguration.KeyData})은 편집하지 않으면 그대로 둔다(데스크톱은 키 파일 경로만 고른다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-conn-advanced.md "SSH 점프 서버")
 */
final class SshJumpServersDialog extends JDialog {

    private static final String[] AUTH_TYPE_NAMES = {
        ConnectionMessages.settings_password, ConnectionMessages.ssh_auth_public_key, ConnectionMessages.ssh_auth_agent};

    private final DefaultListModel<SSHHostConfiguration> hostsModel = new DefaultListModel<>();
    private final JList<SSHHostConfiguration> hostsList = new JList<>(hostsModel);
    private boolean accepted;

    private SshJumpServersDialog(@Nullable Window owner, @NotNull List<SSHHostConfiguration> hosts) {
        super(owner, ConnectionMessages.ssh_jump_title, ModalityType.APPLICATION_MODAL);
        hosts.forEach(hostsModel::addElement);
        hostsList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value instanceof SSHHostConfiguration host
                    ? (index + 1) + ". " + host.toDisplayString() : value, index, isSelected, cellHasFocus); //$NON-NLS-1$
                return this;
            }
        });
        hostsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
                }
            }
        });

        JButton addButton = new JButton(ConnectionMessages.common_add);
        JButton editButton = new JButton(ConnectionMessages.common_edit);
        JButton removeButton = new JButton(ConnectionMessages.common_remove);
        JButton upButton = new JButton(ConnectionMessages.ssh_jump_up);
        JButton downButton = new JButton(ConnectionMessages.ssh_jump_down);
        addButton.addActionListener(e -> {
            SSHHostConfiguration host = editHost(null);
            if (host != null) {
                hostsModel.addElement(host);
                hostsList.setSelectedIndex(hostsModel.size() - 1);
            }
        });
        editButton.addActionListener(e -> editSelected());
        removeButton.addActionListener(e -> {
            int index = hostsList.getSelectedIndex();
            if (index >= 0) {
                hostsModel.remove(index);
            }
        });
        upButton.addActionListener(e -> move(-1));
        downButton.addActionListener(e -> move(1));
        Runnable updateButtons = () -> {
            int index = hostsList.getSelectedIndex();
            editButton.setEnabled(index >= 0);
            removeButton.setEnabled(index >= 0);
            upButton.setEnabled(index > 0);
            downButton.setEnabled(index >= 0 && index < hostsModel.size() - 1);
        };
        hostsList.addListSelectionListener(e -> updateButtons.run());
        hostsModel.addListDataListener(new javax.swing.event.ListDataListener() {
            @Override
            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                updateButtons.run();
            }

            @Override
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                updateButtons.run();
            }

            @Override
            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                updateButtons.run();
            }
        });
        updateButtons.run();

        JPanel listButtons = new JPanel(new GridLayout(0, 1, 0, 6));
        listButtons.add(addButton);
        listButtons.add(editButton);
        listButtons.add(removeButton);
        listButtons.add(upButton);
        listButtons.add(downButton);
        JPanel listButtonsHolder = new JPanel(new BorderLayout());
        listButtonsHolder.add(listButtons, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(12, 6));
        center.add(new JLabel(ConnectionMessages.ssh_jump_hint), BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(hostsList);
        scrollPane.setPreferredSize(new Dimension(420, 180));
        center.add(scrollPane, BorderLayout.CENTER);
        center.add(listButtonsHolder, BorderLayout.EAST);

        JButton okButton = new JButton(ConnectionMessages.common_ok);
        JButton cancelButton = new JButton(ConnectionMessages.common_cancel);
        okButton.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Edits the jump servers; {@code null} when the dialog was canceled.
     */
    @Nullable
    static List<SSHHostConfiguration> edit(@NotNull Component parent, @NotNull List<SSHHostConfiguration> hosts) {
        SshJumpServersDialog dialog = new SshJumpServersDialog(SwingUtilities.getWindowAncestor(parent), hosts);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.getHosts() : null;
    }

    @NotNull
    List<SSHHostConfiguration> getHosts() {
        List<SSHHostConfiguration> hosts = new ArrayList<>();
        for (int i = 0; i < hostsModel.size(); i++) {
            hosts.add(hostsModel.get(i));
        }
        return hosts;
    }

    private void editSelected() {
        int index = hostsList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        SSHHostConfiguration host = editHost(hostsModel.get(index));
        if (host != null) {
            hostsModel.set(index, host);
        }
    }

    private void move(int offset) {
        int index = hostsList.getSelectedIndex();
        int target = index + offset;
        if (index < 0 || target < 0 || target >= hostsModel.size()) {
            return;
        }
        SSHHostConfiguration host = hostsModel.remove(index);
        hostsModel.add(target, host);
        hostsList.setSelectedIndex(target);
    }

    /**
     * One jump server's fields (the SSH tab's target host fields); {@code null} when canceled.
     */
    @Nullable
    private SSHHostConfiguration editHost(@Nullable SSHHostConfiguration host) {
        JTextField hostField = new JTextField(host == null ? "" : host.hostname(), 24); //$NON-NLS-1$
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(host == null || host.port() <= 0 ? SSHConstants.DEFAULT_PORT : host.port(), 1, 65535, 1));
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#")); //$NON-NLS-1$
        JTextField userField = new JTextField(host == null ? CommonUtils.notEmpty(SSHConstants.DEFAULT_USER_NAME) : host.username());
        JComboBox<String> authCombo = new JComboBox<>(AUTH_TYPE_NAMES);
        JTextField keyField = new JTextField();
        JComponent keyRow = NetworkHandlerPanel.createPathField(keyField, ConnectionMessages.ssh_private_key_file, false);
        JLabel keyLabel = new JLabel(ConnectionMessages.ssh_private_key);
        JLabel passwordLabel = new JLabel(ConnectionMessages.settings_password);
        JPasswordField passwordField = new JPasswordField();
        JCheckBox savePasswordCheck = new JCheckBox(ConnectionMessages.settings_remember_password, true);
        JLabel agentLabel = new JLabel(ConnectionMessages.ssh_agent_socket);
        JTextField agentField = new JTextField();
        SSHAuthConfiguration auth = host == null ? null : host.auth();
        if (auth instanceof SSHAuthConfiguration.WithPassword withPassword) {
            passwordField.setText(CommonUtils.notEmpty(withPassword.password()));
            savePasswordCheck.setSelected(withPassword.savePassword());
        }
        if (auth instanceof SSHAuthConfiguration.KeyFile keyFile) {
            authCombo.setSelectedIndex(SSHConstants.AuthType.PUBLIC_KEY.ordinal());
            keyField.setText(keyFile.path());
        } else if (auth instanceof SSHAuthConfiguration.KeyData) {
            authCombo.setSelectedIndex(SSHConstants.AuthType.PUBLIC_KEY.ordinal());
        } else if (auth instanceof SSHAuthConfiguration.Agent agent) {
            authCombo.setSelectedIndex(SSHConstants.AuthType.AGENT.ordinal());
            agentField.setText(CommonUtils.notEmpty(agent.authSockPath()));
        }

        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        JPanel hostPanel = new JPanel(new BorderLayout(12, 0));
        hostPanel.add(hostField, BorderLayout.CENTER);
        JPanel portPanel = new JPanel(new BorderLayout(6, 0));
        portPanel.add(new JLabel(ConnectionMessages.common_port), BorderLayout.WEST);
        portPanel.add(portSpinner, BorderLayout.CENTER);
        hostPanel.add(portPanel, BorderLayout.EAST);
        form.addRow(ConnectionMessages.ssh_host, hostPanel);
        form.addRow(ConnectionMessages.common_user_name, userField);
        form.addRow(ConnectionMessages.settings_authentication, ConnectionSettingsPanel.leftAligned(authCombo));
        form.addRow(keyLabel, keyRow);
        form.addRow(passwordLabel, passwordField);
        form.addRow((JLabel) null, savePasswordCheck);
        form.addRow(agentLabel, agentField);
        Runnable updateAuth = () -> {
            SSHConstants.AuthType type = SSHConstants.AuthType.values()[Math.max(0, authCombo.getSelectedIndex())];
            boolean key = type == SSHConstants.AuthType.PUBLIC_KEY;
            boolean password = type != SSHConstants.AuthType.AGENT;
            boolean agent = type == SSHConstants.AuthType.AGENT && !RuntimeUtils.isWindows();
            keyLabel.setVisible(key);
            keyRow.setVisible(key);
            passwordLabel.setText(key ? ConnectionMessages.ssh_passphrase : ConnectionMessages.settings_password);
            passwordLabel.setVisible(password);
            passwordField.setVisible(password);
            savePasswordCheck.setVisible(password);
            agentLabel.setVisible(agent);
            agentField.setVisible(agent);
            form.getPanel().revalidate();
            Window window = SwingUtilities.getWindowAncestor(form.getPanel());
            if (window != null) {
                window.pack();
            }
        };
        authCombo.addActionListener(e -> updateAuth.run());
        updateAuth.run();
        JPanel panel = form.getPanel();
        panel.setPreferredSize(new Dimension(440, panel.getPreferredSize().height + 60));
        while (true) {
            int result = JOptionPane.showConfirmDialog(this, panel, ConnectionMessages.ssh_jump_server_title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            SSHConstants.AuthType type = SSHConstants.AuthType.values()[Math.max(0, authCombo.getSelectedIndex())];
            String problem = hostField.getText().isBlank() ? ConnectionMessages.ssh_enter_host
                : type == SSHConstants.AuthType.PUBLIC_KEY && keyField.getText().isBlank() && !(auth instanceof SSHAuthConfiguration.KeyData)
                ? ConnectionMessages.ssh_choose_private_key : null;
            if (problem == null) {
                break;
            }
            JOptionPane.showMessageDialog(this, problem, ConnectionMessages.ssh_jump_server_title, JOptionPane.WARNING_MESSAGE);
        }
        String password = CommonUtils.nullIfEmpty(new String(passwordField.getPassword()).trim());
        boolean savePassword = savePasswordCheck.isSelected();
        SSHConstants.AuthType type = SSHConstants.AuthType.values()[Math.max(0, authCombo.getSelectedIndex())];
        SSHAuthConfiguration newAuth = switch (type) {
            case PASSWORD -> new SSHAuthConfiguration.Password(password, savePassword);
            case PUBLIC_KEY -> keyField.getText().isBlank() && auth instanceof SSHAuthConfiguration.KeyData keyData
                ? new SSHAuthConfiguration.KeyData(keyData.data(), password, savePassword)
                : new SSHAuthConfiguration.KeyFile(keyField.getText().trim(), password, savePassword);
            case AGENT -> new SSHAuthConfiguration.Agent(CommonUtils.nullIfEmpty(agentField.getText().trim()));
        };
        return new SSHHostConfiguration(userField.getText().trim(), hostField.getText().trim(), (Integer) portSpinner.getValue(), newAuth);
    }
}
