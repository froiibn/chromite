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
import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.clickhouse.ClickhouseConstants;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.model.impl.net.SSLConfigurationMethod;
import com.seizia.chromite.model.impl.net.SSLHandlerTrustStoreImpl;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;

/**
 * SSL 설정 칸(SSL 탭). PostgreSQL·MySQL/MariaDB·ClickHouse는 인증서 파일, SQL Server는 키 저장소, Databend·TiDB Lake는 켜기만 한다.
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
 *     방법 속성이 없는 예전 설정은 예전 키({@code rootCert} 등)에서 읽는다.
 *     "Use proxy server"({@code sslProxyServer})는 SWT가 숨긴 칸이다({@code PostgreSSLConfigurator.ENABLE_PROXY = false}). 모델은 이 값이 켜져 있으면
 *     SSL 설정을 드라이버에 하나도 넘기지 않는다({@code PostgreDataSource.initProxySSL}이 비어 있음). 그래서 새로 켜는 칸은 두지 않고,
 *     저장된 값이 켜져 있을 때만 확인란을 보여 끌 수 있게 한다(보이지 않는 값이 아래 SSL 칸을 무시하게 두지 않으려고).</li>
 *     <li>MySQL/MariaDB({@code MySQLSSLConfigurator}): CA 인증서 포함, 암호 스위트, Require SSL, Verify server certificate(기본 켬),
 *     Allow public key retrieval. 예전 설정은 예전 키에서 읽는다.</li>
 *     <li>SQL Server({@code SQLServerSSLConfigurator}): 키 저장소 경로·비밀번호, 인증서 호스트 이름. SQL Server 모델은 SSL 핸들러가 켜져 있으면
 *     드라이버 속성 {@code encrypt=true}로 연결한다({@code SQLServerDataSource.getAllConnectionProperties}). 키 저장소는 비워 둘 수 있다
 *     (그때는 JVM 기본 신뢰 저장소를 쓰고, 주 양식의 Trust server certificate로 검증을 끌 수 있다).</li>
 *     <li>ClickHouse({@code ClickhouseSSLConfigurator}): CA·클라이언트 인증서·클라이언트 키 경로와 SSL mode(STRICT/NONE, {@code ssl.mode}).
 *     모델은 공식 드라이버({@code com_clickhouse})일 때 이 값들을 {@code Properties}에 그대로 넣으므로 빈 값이라도 늘 쓴다(없으면 NPE).
 *     SWT처럼 SSL mode는 늘 저장하고 경로는 빈 문자열도 저장한다.</li>
 *     <li>Databend·TiDB Lake({@code DatabendSSLConfigurator}, {@code TiDBLakeSSLConfigurator}): 모델은 핸들러가 켜져 있는지만 보고
 *     {@code sslmode=enable}을 넣는다({@code DatabendDataSource}, {@code TiDBLakeDataSource}의 {@code initSSL}). 그래서 칸 없이 안내만 둔다.
 *     SWT TiDB Lake 화면의 클라이언트 인증서 칸은 모델이 읽지 않아 옮기지 않았다. 두 핸들러는 {@code desktop="false"}이지만
 *     SWT는 이 값으로 탭을 거르지 않았다({@code ConnectionPageSettings.getDialogPages}, {@code isDesktopHandler} 호출 없음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 ClickHouse SSL 칸, Databend·TiDB Lake SSL 안내, 저장된 PostgreSQL "Use proxy server"를 끄는 칸
 *   (docs/chromite/swing-conn-advanced.md "SSL")
 */
final class SslHandlerPanel extends NetworkHandlerPanel {

    /**
     * Same list as PostgreSSLConfigurator.SSL_MODES (the empty value leaves the driver's default).
     */
    private static final String[] POSTGRE_SSL_MODES = {"", "disable", "allow", "prefer", "require", "verify-ca", "verify-full"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    /**
     * ClickhouseSSLConfigurator.SSLModes: STRICT verifies the server certificate, NONE does not.
     */
    private static final String[] CLICKHOUSE_SSL_MODES = {"STRICT", "NONE"}; //$NON-NLS-1$ //$NON-NLS-2$

    enum Kind {
        POSTGRESQL, MYSQL, SQLSERVER, CLICKHOUSE,
        /**
         * Databend, TiDB Lake: the model only checks that the handler is on.
         */
        ENABLE_ONLY
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
    private final JCheckBox proxyCheck = new JCheckBox(ConnectionMessages.ssl_proxy_server);
    private final JComboBox<String> clickhouseModeCombo = new JComboBox<>(CLICKHOUSE_SSL_MODES);
    private final JTextField cipherSuitesField = new JTextField();
    private final JCheckBox requireSslCheck = new JCheckBox(ConnectionMessages.ssl_require);
    private final JCheckBox verifyServerCertCheck = new JCheckBox(ConnectionMessages.ssl_verify_certificate);
    private final JCheckBox publicKeyRetrievalCheck = new JCheckBox(ConnectionMessages.ssl_public_key_retrieval);

    SslHandlerPanel(@NotNull Kind kind, @NotNull Runnable changeListener) {
        super(changeListener);
        this.kind = kind;
        ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
        if (kind == Kind.SQLSERVER) {
            form.addRow(ConnectionMessages.ssl_keystore, createPathField(keyStoreField, ConnectionMessages.ssl_keystore_file, false));
            keyStoreField.setToolTipText(ConnectionMessages.ssl_keystore_tip);
            form.addRow(ConnectionMessages.ssl_keystore_password, keyStorePasswordField);
            form.addRow(ConnectionMessages.ssl_certificate_host, keyStoreHostnameField);
            keyStoreHostnameField.setToolTipText(ConnectionMessages.ssl_certificate_host_tip);
        } else if (kind == Kind.ENABLE_ONLY) {
            JLabel hint = new JLabel(ConnectionMessages.ssl_enable_only);
            hint.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
            form.addRow((JLabel) null, hint);
        } else {
            form.addRow(ConnectionMessages.ssl_ca_certificate, createPathField(caCertField, ConnectionMessages.ssl_ca_certificate_file, false));
            form.addRow(ConnectionMessages.ssl_client_certificate,
                createPathField(clientCertField, ConnectionMessages.ssl_client_certificate_file, false));
            form.addRow(ConnectionMessages.ssl_client_key, createPathField(clientKeyField, ConnectionMessages.ssl_client_key_file, false));
            if (kind == Kind.MYSQL) {
                cipherSuitesField.setToolTipText(ConnectionMessages.ssl_cipher_suites_tip);
                form.addRow(ConnectionMessages.ssl_cipher_suites, cipherSuitesField);
                form.addGroupGap();
                requireSslCheck.setToolTipText(ConnectionMessages.ssl_require_tip);
                verifyServerCertCheck.setToolTipText(ConnectionMessages.ssl_verify_certificate_tip);
                publicKeyRetrievalCheck.setToolTipText(ConnectionMessages.ssl_public_key_retrieval_tip);
                form.addRow((JLabel) null, requireSslCheck);
                form.addRow((JLabel) null, verifyServerCertCheck);
                form.addRow((JLabel) null, publicKeyRetrievalCheck);
            } else if (kind == Kind.CLICKHOUSE) {
                form.addGroupGap();
                clickhouseModeCombo.setToolTipText(ConnectionMessages.ssl_clickhouse_mode_tip);
                form.addRow(ConnectionMessages.ssl_mode, ConnectionSettingsPanel.leftAligned(clickhouseModeCombo));
            } else {
                form.addGroupGap();
                sslModeCombo.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (CommonUtils.isEmpty((String) value)) {
                            setText(ConnectionMessages.ssl_mode_driver_default);
                        }
                        return this;
                    }
                });
                form.addRow(ConnectionMessages.ssl_mode, ConnectionSettingsPanel.leftAligned(sslModeCombo));
                sslFactoryField.setToolTipText(ConnectionMessages.ssl_factory_tip);
                form.addRow(ConnectionMessages.ssl_factory, sslFactoryField);
                // Shown only for a saved "on" value (loadSettings)
                proxyCheck.setToolTipText(ConnectionMessages.ssl_proxy_server_tip);
                proxyCheck.setVisible(false);
                form.addRow((JLabel) null, proxyCheck);
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
                boolean proxy = configuration.getBooleanProperty(PostgreConstants.PROP_SSL_PROXY);
                proxyCheck.setSelected(proxy);
                proxyCheck.setVisible(proxy);
            }
            case CLICKHOUSE -> {
                // SSLConfiguratorTrustStoreUI.loadSettings (desktop: the paths) and ClickhouseSSLConfigurator.loadSettings
                caCertField.setText(getProperty(configuration, SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT));
                clientCertField.setText(getProperty(configuration, SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT));
                clientKeyField.setText(getProperty(configuration, SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY));
                String mode = configuration.getStringProperty(ClickhouseConstants.SSL_MODE_CONF);
                clickhouseModeCombo.setSelectedItem(CLICKHOUSE_SSL_MODES[1].equalsIgnoreCase(mode) ? CLICKHOUSE_SSL_MODES[1] : CLICKHOUSE_SSL_MODES[0]);
            }
            case ENABLE_ONLY -> {
                // Nothing to read: the handler being on is the whole setting
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
        if (kind == Kind.ENABLE_ONLY) {
            return;
        }
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT, caCertField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT, clientCertField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY, clientKeyField.getText().trim());
        configuration.setProperty(SSLHandlerTrustStoreImpl.PROP_SSL_METHOD, SSLConfigurationMethod.CERTIFICATES.name());
        if (kind == Kind.POSTGRESQL) {
            configuration.setProperty(PostgreConstants.PROP_SSL_MODE, CommonUtils.notEmpty((String) sslModeCombo.getSelectedItem()));
            configuration.setProperty(PostgreConstants.PROP_SSL_FACTORY, sslFactoryField.getText().trim());
            // The check box only shows a saved "on" value: left on, the saved value is kept as it is; turned off, it is removed
            if (!proxyCheck.isSelected() && configuration.getBooleanProperty(PostgreConstants.PROP_SSL_PROXY)) {
                configuration.setProperty(PostgreConstants.PROP_SSL_PROXY, null);
            }
        } else if (kind == Kind.CLICKHOUSE) {
            // Always written: the model puts the mode into the driver's Properties, which refuses null
            configuration.setProperty(ClickhouseConstants.SSL_MODE_CONF, (String) clickhouseModeCombo.getSelectedItem());
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
