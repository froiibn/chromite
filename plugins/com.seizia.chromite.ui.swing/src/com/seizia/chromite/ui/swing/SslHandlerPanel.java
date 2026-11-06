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
import org.jkiss.dbeaver.ext.mssql.SQLServerConstants;
import org.jkiss.dbeaver.ext.mysql.MySQLConstants;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.model.impl.net.SSLConfigurationMethod;
import org.jkiss.dbeaver.model.impl.net.SSLHandlerTrustStoreImpl;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;

/**
 * SSL 설정 칸(SSL 탭). PostgreSQL·MySQL/MariaDB는 인증서 파일, SQL Server는 키 저장소다.
 *
 * 설계: SWT의 공통 부분 {@code SSLConfiguratorTrustStoreUI}와 DB별 화면의 load/save를 데스크톱 경로 그대로 따른다
 * (분산 제품용 {@code .value} 비밀 속성 경로는 쓰지 않는다. SWT도 데스크톱에서는 파일 경로를 일반 속성에 둔다).
 * <ul>
 *     <li>공통: 방법({@link SSLHandlerTrustStoreImpl#PROP_SSL_METHOD}) = 인증서({@link SSLConfigurationMethod#CERTIFICATES}) 또는
 *     키 저장소({@link SSLConfigurationMethod#KEYSTORE}). 이 세 핸들러는 한 가지만 지원하므로 고르는 칸이 없다.
 *     인증서 경로는 CA·클라이언트 인증서·클라이언트 키 속성, 키 저장소는 경로 속성과 비밀번호다.
 *     키 저장소 비밀번호는 SWT처럼 핸들러 비밀번호({@code setPassword} + 저장 켬)라 비밀 저장소에 저장된다.</li>
 *     <li>PostgreSQL({@code PostgreSSLConfigurator}): CA 인증서 포함, SSL mode(빈 값·disable…verify-full), SSL factory(클래스 이름).
 *     SWT는 드라이버 jar에서 {@code SSLSocketFactory} 구현을 찾아 콤보에 넣지만 여기서는 직접 입력한다.
 *     방법 속성이 없는 예전 설정은 예전 키({@code rootCert} 등)에서 읽는다.</li>
 *     <li>MySQL/MariaDB({@code MySQLSSLConfigurator}): CA 인증서 포함, 암호 스위트, Require SSL, Verify server certificate(기본 켬),
 *     Allow public key retrieval. 예전 설정은 예전 키에서 읽는다.</li>
 *     <li>SQL Server({@code SQLServerSSLConfigurator}): 키 저장소 경로·비밀번호, 인증서 호스트 이름. SQL Server 모델은 SSL 핸들러가 켜져 있으면
 *     드라이버 속성 {@code encrypt=true}로 연결한다({@code SQLServerDataSource.getAllConnectionProperties}). 키 저장소는 비워 둘 수 있다
 *     (그때는 JVM 기본 신뢰 저장소를 쓰고, 주 양식의 Trust server certificate로 검증을 끌 수 있다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 */
final class SslHandlerPanel extends NetworkHandlerPanel {

    /**
     * Same list as PostgreSSLConfigurator.SSL_MODES (the empty value leaves the driver's default).
     */
    private static final String[] POSTGRE_SSL_MODES = {"", "disable", "allow", "prefer", "require", "verify-ca", "verify-full"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    enum Kind {
        POSTGRESQL, MYSQL, SQLSERVER
    }

    private final Kind kind;
    private final JComponent component;

    private final JTextField caCertField = new JTextField();
    private final JTextField clientCertField = new JTextField();
    private final JTextField clientKeyField = new JTextField();
    private final JTextField keyStoreField = new JTextField();
    private final JPasswordField keyStorePasswordField = new JPasswordField();
    private final JTextField keyStoreHostnameField = new JTextField();
    private final JComboBox<String> sslModeCombo = new JComboBox<>(POSTGRE_SSL_MODES);
    private final JTextField sslFactoryField = new JTextField();
    private final JTextField cipherSuitesField = new JTextField();
    private final JCheckBox requireSslCheck = new JCheckBox("Require SSL");
    private final JCheckBox verifyServerCertCheck = new JCheckBox("Verify server certificate");
    private final JCheckBox publicKeyRetrievalCheck = new JCheckBox("Allow public key retrieval");

    SslHandlerPanel(@NotNull Kind kind, @NotNull Runnable changeListener) {
        super(changeListener);
        this.kind = kind;
        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        if (kind == Kind.SQLSERVER) {
            form.addRow("Keystore", createPathField(keyStoreField, "Keystore file", false));
            keyStoreField.setToolTipText("Java keystore (*.jks, *.pfx) with the certificates to trust");
            form.addRow("Keystore password", keyStorePasswordField);
            form.addRow("Certificate host name", keyStoreHostnameField);
            keyStoreHostnameField.setToolTipText("Host name in the server certificate, when it differs from the server name");
        } else {
            form.addRow("CA certificate", createPathField(caCertField, "CA certificate file", false));
            form.addRow("Client certificate", createPathField(clientCertField, "Client certificate file", false));
            form.addRow("Client private key", createPathField(clientKeyField, "Client private key file", false));
            if (kind == Kind.MYSQL) {
                cipherSuitesField.setToolTipText("Comma-separated list of cipher suites (optional)");
                form.addRow("Cipher suites", cipherSuitesField);
                form.addGroupGap();
                requireSslCheck.setToolTipText("Fail when the server does not support SSL");
                verifyServerCertCheck.setToolTipText("Check the server certificate against the CA certificate");
                publicKeyRetrievalCheck.setToolTipText("Allow the client to request the server's RSA public key");
                form.addRow((JLabel) null, requireSslCheck);
                form.addRow((JLabel) null, verifyServerCertCheck);
                form.addRow((JLabel) null, publicKeyRetrievalCheck);
            } else {
                form.addGroupGap();
                sslModeCombo.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (CommonUtils.isEmpty((String) value)) {
                            setText("Driver default");
                        }
                        return this;
                    }
                });
                form.addRow("SSL mode", ConnectionSettingsPanel.leftAligned(sslModeCombo));
                sslFactoryField.setToolTipText("Class name of an SSLSocketFactory in the driver (optional)");
                form.addRow("SSL factory", sslFactoryField);
            }
        }
        component = createPage(form);
    }

    @NotNull
    @Override
    JComponent getComponent() {
        return component;
    }

    @Override
    void loadSettings(@NotNull DBWHandlerConfiguration configuration) {
        boolean legacy = CommonUtils.isEmpty(configuration.getStringProperty(SSLHandlerTrustStoreImpl.PROP_SSL_METHOD));
        switch (kind) {
            case SQLSERVER -> {
                keyStoreField.setText(getProperty(configuration, legacy ? SQLServerConstants.PROP_SSL_KEYSTORE : SSLHandlerTrustStoreImpl.PROP_SSL_KEYSTORE));
                keyStorePasswordField.setText(CommonUtils.notEmpty(legacy
                    ? configuration.getStringProperty(SQLServerConstants.PROP_SSL_KEYSTORE_PASSWORD) : configuration.getPassword()));
                keyStoreHostnameField.setText(getProperty(configuration, SQLServerConstants.PROP_SSL_KEYSTORE_HOSTNAME));
            }
            case POSTGRESQL -> {
                caCertField.setText(getProperty(configuration, legacy ? PostgreConstants.PROP_SSL_ROOT_CERT : SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT));
                clientCertField.setText(getProperty(configuration, legacy ? PostgreConstants.PROP_SSL_CLIENT_CERT : SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT));
                clientKeyField.setText(getProperty(configuration, legacy ? PostgreConstants.PROP_SSL_CLIENT_KEY : SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY));
                sslModeCombo.setSelectedItem(getProperty(configuration, PostgreConstants.PROP_SSL_MODE));
                sslFactoryField.setText(getProperty(configuration, PostgreConstants.PROP_SSL_FACTORY));
            }
            case MYSQL -> {
                caCertField.setText(getProperty(configuration, legacy ? MySQLConstants.PROP_SSL_CA_CERT : SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT));
                clientCertField.setText(getProperty(configuration, legacy ? MySQLConstants.PROP_SSL_CLIENT_CERT : SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT));
                clientKeyField.setText(getProperty(configuration, legacy ? MySQLConstants.PROP_SSL_CLIENT_KEY : SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY));
                cipherSuitesField.setText(getProperty(configuration, MySQLConstants.PROP_SSL_CIPHER_SUITES));
                requireSslCheck.setSelected(configuration.getBooleanProperty(MySQLConstants.PROP_REQUIRE_SSL, false));
                verifyServerCertCheck.setSelected(configuration.getBooleanProperty(MySQLConstants.PROP_VERIFY_SERVER_SERT, true));
                publicKeyRetrievalCheck.setSelected(configuration.getBooleanProperty(MySQLConstants.PROP_SSL_PUBLIC_KEY_RETRIEVE, false));
            }
        }
    }

    @Override
    void saveSettings(@NotNull DBWHandlerConfiguration configuration) {
        if (kind == Kind.SQLSERVER) {
            configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_KEYSTORE, keyStoreField.getText().trim());
            String password = new String(keyStorePasswordField.getPassword()).trim();
            // SSLConfiguratorTrustStoreUI.saveSettings: an empty password keeps the saved one
            if (!password.isEmpty()) {
                configuration.setPassword(password);
                configuration.setSavePassword(true);
            }
            configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_METHOD, SSLConfigurationMethod.KEYSTORE.name());
            configuration.setProperty(SQLServerConstants.PROP_SSL_KEYSTORE_HOSTNAME, keyStoreHostnameField.getText().trim());
            return;
        }
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT, caCertField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT, clientCertField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY, clientKeyField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_METHOD, SSLConfigurationMethod.CERTIFICATES.name());
        if (kind == Kind.POSTGRESQL) {
            configuration.setProperty(PostgreConstants.PROP_SSL_MODE, CommonUtils.notEmpty((String) sslModeCombo.getSelectedItem()));
            configuration.setProperty(PostgreConstants.PROP_SSL_FACTORY, sslFactoryField.getText().trim());
        } else {
            configuration.setProperty(MySQLConstants.PROP_REQUIRE_SSL, String.valueOf(requireSslCheck.isSelected()));
            configuration.setProperty(MySQLConstants.PROP_VERIFY_SERVER_SERT, String.valueOf(verifyServerCertCheck.isSelected()));
            configuration.setProperty(MySQLConstants.PROP_SSL_PUBLIC_KEY_RETRIEVE, String.valueOf(publicKeyRetrievalCheck.isSelected()));
            configuration.setProperty(MySQLConstants.PROP_SSL_CIPHER_SUITES, cipherSuitesField.getText().trim());
        }
    }

    @NotNull
    private static String getProperty(@NotNull DBWHandlerConfiguration configuration, @NotNull String name) {
        return CommonUtils.notEmpty(configuration.getStringProperty(name));
    }
}
