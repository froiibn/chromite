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
import org.jkiss.dbeaver.ext.mssql.SQLServerConstants;
import org.jkiss.dbeaver.ext.mysql.MySQLConstants;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.model.impl.net.SocksProxyImpl;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.ssh.SSHTunnelImpl;
import org.jkiss.dbeaver.registry.network.NetworkHandlerDescriptor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;

/**
 * 네트워크 핸들러(SSH 터널, 프록시, SSL) 하나의 설정 칸. Connect 대화상자의 핸들러 탭({@link ConnectionNetworkPage}) 안에 들어간다.
 *
 * 설계: SWT는 핸들러 구현 클래스마다 SWT 설정 화면을 {@code org.jkiss.dbeaver.ui.propertyConfigurator} 확장으로 등록하고
 * ({@code UIPropertyConfiguratorRegistry}), 연결 화면({@code ConnectionPageNetworkHandler})이 그 화면의 {@code loadSettings}/
 * {@code saveSettings}/{@code getErrorMessage}를 부른다. Swing 제품에는 그 SWT 번들이 없으므로 같은 세 동작을 가진 Swing 칸을 이 번들에 두고
 * {@link #create}가 핸들러에 맞는 것을 고른다. 값을 읽고 쓰는 규칙(속성 키, 비밀번호와 비밀 속성의 저장 위치)은 각 SWT 화면을 그대로 따르므로,
 * SWT 제품과 같은 연결 설정 파일을 주고받을 수 있다. 비밀번호는 {@link DBWHandlerConfiguration#setPassword}·{@code setSecureProperty}에만 넣는다.
 * 연결 레지스트리가 저장할 때 이 값들을 비밀 저장소(암호화한 {@code credentials-config.json} 또는 보안 저장소)로 보낸다
 * ({@code DataSourceParser.saveNetworkHandlerConfiguration}).
 * <ul>
 *     <li>SSH 터널(구현 {@link SSHTunnelImpl}): {@link SshTunnelPanel}. SWT {@code SSHTunnelDefaultConfiguratorUI}.</li>
 *     <li>SOCKS 프록시(구현 {@link SocksProxyImpl}): {@link SocksProxyPanel}. SWT {@code SocksProxyConfiguratorUI}.</li>
 *     <li>SSL(핸들러 ID {@code postgre_ssl}, {@code mysql_ssl}, {@code mssql_ssl}): {@link SslHandlerPanel}.
 *     SWT {@code PostgreSSLConfigurator}, {@code MySQLSSLConfigurator}, {@code SQLServerSSLConfigurator}.</li>
 * </ul>
 * 그 밖의 핸들러(ClickHouse·Databend·TiDB Lake의 SSL)는 Swing 칸이 없어 탭을 만들지 않는다(SWT도 설정 화면이 없는 핸들러는 탭을 만들지 않는다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 */
abstract class NetworkHandlerPanel {

    /**
     * Called when a value changes (validation of the dialog).
     */
    protected final Runnable changeListener;

    protected NetworkHandlerPanel(@NotNull Runnable changeListener) {
        this.changeListener = changeListener;
    }

    /**
     * The panel for a handler, or {@code null} when the Swing frontend has no settings panel for it.
     */
    @Nullable
    static NetworkHandlerPanel create(@NotNull NetworkHandlerDescriptor descriptor, @NotNull Runnable changeListener) {
        String implName = descriptor.getHandlerType().getImplName();
        if (SSHTunnelImpl.class.getName().equals(implName)) {
            return new SshTunnelPanel(changeListener);
        }
        if (SocksProxyImpl.class.getName().equals(implName)) {
            return new SocksProxyPanel(changeListener);
        }
        return switch (descriptor.getId()) {
            case PostgreConstants.HANDLER_SSL -> new SslHandlerPanel(SslHandlerPanel.Kind.POSTGRESQL, changeListener);
            case MySQLConstants.HANDLER_SSL -> new SslHandlerPanel(SslHandlerPanel.Kind.MYSQL, changeListener);
            case SQLServerConstants.HANDLER_SSL -> new SslHandlerPanel(SslHandlerPanel.Kind.SQLSERVER, changeListener);
            default -> null;
        };
    }

    @NotNull
    abstract JComponent getComponent();

    /**
     * Fills the fields from the handler configuration of the connection.
     */
    abstract void loadSettings(@NotNull DBWHandlerConfiguration configuration);

    /**
     * Writes the fields into a handler configuration.
     */
    abstract void saveSettings(@NotNull DBWHandlerConfiguration configuration);

    /**
     * Why the settings are incomplete, or {@code null}. Only asked when the handler is enabled.
     */
    @Nullable
    String getErrorMessage() {
        return null;
    }

    /**
     * Turns the fields on or off together with the "Use …" check box of the tab.
     */
    void setFieldsEnabled(boolean enabled) {
        setEnabledDeep(getComponent(), enabled);
    }

    private static void setEnabledDeep(@NotNull Component component, boolean enabled) {
        if (component instanceof JLabel) {
            // Labels stay readable
            return;
        }
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledDeep(child, enabled);
            }
        }
    }

    /**
     * A document listener that reports every change to {@link #changeListener}.
     */
    @NotNull
    protected DocumentListener createDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changeListener.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changeListener.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changeListener.run();
            }
        };
    }

    /**
     * A path field with a "Browse…" button (a file chooser starting at the current path).
     */
    @NotNull
    static JComponent createPathField(@NotNull JTextField field, @NotNull String chooserTitle, boolean folder) {
        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(chooserTitle);
            chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            String current = field.getText().trim();
            if (!current.isEmpty()) {
                File file = new File(current);
                if (folder || file.isDirectory()) {
                    chooser.setCurrentDirectory(file);
                } else {
                    chooser.setSelectedFile(file);
                }
            }
            if (chooser.showOpenDialog(field) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browseButton, BorderLayout.EAST);
        return panel;
    }

    /**
     * A page body of form rows with the dialog's page insets (10, 12), scrolled vertically when it does not fit.
     */
    @NotNull
    static JComponent createPage(@NotNull ConnectionSettingsPanel.FormBuilder form) {
        form.addFiller();
        JPanel page = form.getPanel();
        page.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return page;
    }
}
