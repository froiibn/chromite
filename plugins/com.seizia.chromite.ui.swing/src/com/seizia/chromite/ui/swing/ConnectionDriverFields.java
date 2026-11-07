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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.bigquery.BQDataSourceProvider;
import com.seizia.chromite.ext.mimer.MimerConstants;
import com.seizia.chromite.ext.mimer.MimerDataSourceProvider;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.oracle.OracleDataSourceProvider;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionType;
import com.seizia.chromite.ext.oracle.model.dict.OracleLanguage;
import com.seizia.chromite.ext.oracle.model.dict.OracleTerritory;
import com.seizia.chromite.ext.oracle.oci.OCIUtils;
import com.seizia.chromite.ext.snowflake.SnowflakeConstants;
import com.seizia.chromite.ext.snowflake.SnowflakeDataSourceProvider;
import com.seizia.chromite.ext.snowflake.SnowflakeUtils;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.registry.DataSourceDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 드라이버별 주 양식 칸. 범용 칸({@link ConnectionSettingsPanel})으로는 고칠 수 없는 SWT DB별 연결 화면의 주 칸을 옮겼다.
 *
 * 설계: SWT는 DB마다 연결 화면 전체를 따로 만든다({@code SQLServerConnectionPage}, {@code OracleConnectionPage}). Swing은 범용 양식을 유지하고
 * 정해진 자리에 칸을 더하는 고리만 둔다(Server name 앞, Database 칸 옆, 주소 묶음 끝, 인증 묶음 끝). 값의 저장 위치는 SWT 화면과 같다.
 * <ul>
 *     <li>SQL Server(Microsoft 드라이버, {@link SQLServerUtils#isDriverSqlServer}): SSMS "Connect to Server"의 Encrypt와
 *     Trust server certificate. Encrypt는 드라이버 속성 {@code encrypt}(Optional = 지움(모델이 false로 연결), Mandatory = true,
 *     Strict = strict)다. SWT 화면에는 없고 Driver Properties 표에서만 고치던 값이며, SSL 탭이 켜져 있으면 모델이 true로 바꾼다
 *     ({@code SQLServerDataSource.getAllConnectionProperties}). Trust server certificate는 공급자 속성
 *     {@code sslTrustServerCertificate}(SWT와 같음, Provider Properties 표에서는 뺌). Server name의 {@code 호스트\인스턴스}(명명된 인스턴스)는 따로 처리하지 않는다.
 *     공급자가 기본 포트(1433)를 URL에 넣지 않으므로({@code SQLServerDataSourceProvider.getConnectionURL}) 포트를 적지 않으면
 *     {@code serverName=호스트\인스턴스}가 되어 드라이버가 인스턴스 이름으로 찾아간다.</li>
 *     <li>Oracle({@link OracleDataSourceProvider}): Connection type(Basic / TNS, 공급자 속성 {@code connection-type}). Basic은 범용 Server name·Database에
 *     SID / Service name 콤보(공급자 속성 {@code sid-service}, 새 연결은 Service name, Provider Properties 표에서는 뺌)를 더한다.
 *     TNS는 Server name·Database 줄을 숨기고 Network alias(DB 이름에 저장)와 TNS names folder(공급자 속성 {@code tns-path}, tnsnames.ora가 있는 폴더,
 *     비우면 {@code TNS_ADMIN} 환경 변수와 기본 Oracle 홈)를 보인다. 별칭 목록은 모델의 {@link OCIUtils#readTnsNames}로 읽는다.
 *     SWT의 Custom(JDBC URL 템플릿)은 Additional Connection Parameters의 JDBC URL 연결 방식이 맡는다(저장할 때 connection-type = CUSTOM).
 *     Oracle 클라이언트(OCI) 홈 선택은 옮기지 않았다(상용 기능에서만 보이는 SWT 칸).</li>
 *     <li>Oracle 세션 설정(Options의 Session Settings 탭, {@link #getOptionsPage}): SWT {@code OracleConnectionExtraPage}의 "Session settings"
 *     묶음(언어·지역 콤보, 날짜·타임스탬프 형식, 길이 의미, 통화)과 같은 공급자 속성({@code OracleConstants.PROP_SESSION_*})이다. 모델이 연결할 때
 *     {@code ALTER SESSION SET NLS_…}으로 적용한다({@code OracleDataSource.initializeContextState}). 기본값(빈 값)이면 속성을 지운다(SWT와 같음).
 *     언어·지역 목록은 SWT처럼 모델의 {@link OracleLanguage}·{@link OracleTerritory}이고 직접 입력도 된다. 길이 의미는 SWT의 글 칸 대신
 *     BYTE/CHAR 콤보(입력 가능)다.</li>
 *     <li>BigQuery({@code BQConnectionPage}): Project(DB 이름). 예시 URL에 {@code {database}}가 없어 범용 양식에 칸이 없었고, 모델은 이 값을
 *     드라이버 속성 {@code ProjectId}로 넘긴다. 비어 있으면 Test·Connect가 꺼진다(SWT {@code isComplete}).</li>
 *     <li>Snowflake({@code SnowflakeConnectionPage}): Warehouse(서버 이름, SWT와 같음)와 Schema(내부 공급자 속성 {@code @…-schema@}).
 *     모델은 서버 이름이 비면 공급자 속성 {@code warehouse}, 내부 속성이 비면 {@code schema}를 읽는다. 표에 선언된 두 키({@code warehouse}, {@code schema})는
 *     모델이 먼저 읽는 자리를 보이지 않아, 표에서 빼고 모델이 실제로 쓰는 값을 이 칸에 보인다. 저장은 값이 바뀌었을 때만 SWT 자리에 쓰고 대체 키를 지운다.</li>
 *     <li>SAP HANA({@code HANAConnectionPage}): Edition, Instance number, Tenant database. 모델은 공급자 속성 {@code edition}·{@code instanceNumber}를
 *     읽지 않는다. 이 값으로 대화상자가 포트(플랫폼 3NN15/3NN13, Express 39015, Cloud 443)와 드라이버 속성 {@code databaseName}(테넌트 DB)을 정한다.
 *     포트를 정하는 에디션이면 Server name에 적은 포트 대신 그 포트를 쓰고, 칸 아래에 그 포트를 보인다.</li>
 *     <li>Firebird Embedded({@code FireBirdEmbeddedConnectionPage}): 네이티브 라이브러리 폴더 → 공급자 속성 {@code nativeLibraryPath}.
 *     공급자가 연결 전에 {@code jna.library.path}에 더한다.</li>
 *     <li>Mimer SQL({@code MimerConnectionSettingsPage}): Protocol(TCP/IP, 이 컴퓨터의 공유 메모리) → 공급자 속성 {@code protocol},
 *     PROGRAM 보안 계층의 Program(드라이버 속성)과 비밀번호(인증 속성, 레지스트리가 보안 저장소에 둠).</li>
 * </ul>
 * HANA·Firebird는 공급자 클래스나 상수를 내보내지 않아 {@code plugin.xml}의 공급자·드라이버 ID로 고른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 Oracle 세션 설정(NLS) 탭과 드라이버별 Options 탭 고리({@link #getOptionsPage})
 *   (docs/chromite/swing-conn-advanced.md "Oracle 세션 설정")
 * - 2026-11-08 BigQuery Project, Snowflake Warehouse·Schema, HANA 에디션, Firebird Embedded 라이브러리 폴더, Mimer 프로토콜·PROGRAM
 *   (docs/chromite/swing-conn-advanced.md "3. DB별 주 양식 칸")
 */
abstract class ConnectionDriverFields {

    /**
     * Provider and driver IDs of plugin.xml (the HANA and Firebird bundles export no provider class or constant for them).
     */
    private static final String PROVIDER_HANA = "hana"; //$NON-NLS-1$
    private static final String PROVIDER_FIREBIRD = "jaybird"; //$NON-NLS-1$
    private static final String DRIVER_FIREBIRD_EMBEDDED = "jaybird_embedded"; //$NON-NLS-1$

    protected final Runnable changeListener;

    protected ConnectionDriverFields(@NotNull Runnable changeListener) {
        this.changeListener = changeListener;
    }

    /**
     * The driver's extra fields, or {@code null} when the generic form is enough.
     */
    @Nullable
    static ConnectionDriverFields create(@NotNull DBPDriver driver, @NotNull Runnable changeListener) {
        if (driver.getDataSourceProvider() instanceof OracleDataSourceProvider) {
            return new OracleFields(changeListener);
        }
        if (SQLServerUtils.isDriverSqlServer(driver)) {
            return new SqlServerFields(changeListener);
        }
        DBPDataSourceProvider provider = driver.getDataSourceProvider();
        if (provider instanceof BQDataSourceProvider) {
            return new BigQueryFields(changeListener);
        }
        if (provider instanceof SnowflakeDataSourceProvider) {
            return new SnowflakeFields(changeListener);
        }
        if (provider instanceof MimerDataSourceProvider) {
            return new MimerFields(changeListener);
        }
        if (PROVIDER_HANA.equals(driver.getProviderId())) {
            return new HanaFields(changeListener, driver.getDefaultPort());
        }
        if (PROVIDER_FIREBIRD.equals(driver.getProviderId()) && DRIVER_FIREBIRD_EMBEDDED.equals(driver.getId())) {
            // FireBirdConnectionPage shows the native library page for the embedded driver only
            return new FirebirdEmbeddedFields(changeListener);
        }
        return null;
    }

    /**
     * Rows before the server name row.
     */
    void addLeadingRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
    }

    /**
     * The component of the Database row (the field itself, or the field with something next to it).
     */
    @NotNull
    JComponent decorateDatabaseField(@NotNull JTextField databaseField) {
        return databaseField;
    }

    /**
     * Rows after the address rows.
     */
    void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
    }

    /**
     * Rows after the authentication rows.
     */
    void addSecurityRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
    }

    /**
     * The generic server name and database rows are used (they are shown and validated).
     */
    boolean isGenericAddressUsed() {
        return true;
    }

    abstract void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew);

    /**
     * Writes the fields after the generic address was written.
     *
     * @param urlMode the connection is made with a typed JDBC URL
     */
    abstract void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode);

    /**
     * Why the fields are incomplete, or {@code null}.
     */
    @Nullable
    String validate() {
        return null;
    }

    /**
     * A driver specific tab of the Options tabs.
     */
    record OptionsPage(@NotNull String title, @NotNull JComponent page) {
    }

    /**
     * The driver's own Options tab, or {@code null}.
     */
    @Nullable
    OptionsPage getOptionsPage() {
        return null;
    }

    /**
     * Provider properties edited here: left out of the Provider Properties table.
     */
    @NotNull
    Set<String> getHandledProviderProperties() {
        return Collections.emptySet();
    }

    /**
     * Sets a provider property; the model's default value is not added to a connection that does not have the property,
     * so that opening and saving an unchanged connection does not count as a change (and ask to reconnect).
     */
    protected static void setProviderProperty(
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull String name,
        @NotNull String value,
        @NotNull String defaultValue
    ) {
        if (value.equals(defaultValue) && configuration.getProviderProperty(name) == null) {
            return;
        }
        configuration.setProviderProperty(name, value);
    }

    /**
     * SQL Server (Microsoft driver): Encrypt and Trust server certificate.
     */
    private static final class SqlServerFields extends ConnectionDriverFields {
        /**
         * The driver property the SQL Server model reads (SQLServerDataSource.PROP_ENCRYPT_SSL is private).
         */
        private static final String DRIVER_PROP_ENCRYPT = "encrypt"; //$NON-NLS-1$
        private static final String ENCRYPT_STRICT = "strict"; //$NON-NLS-1$
        private static final String[] ENCRYPT_ITEMS = {
            ConnectionMessages.driver_encrypt_optional, ConnectionMessages.driver_encrypt_mandatory, ConnectionMessages.driver_encrypt_strict};

        private final JComboBox<String> encryptCombo = new JComboBox<>(ENCRYPT_ITEMS);
        private final JCheckBox trustCertificateCheck = new JCheckBox(ConnectionMessages.driver_trust_certificate);

        SqlServerFields(@NotNull Runnable changeListener) {
            super(changeListener);
            encryptCombo.setToolTipText(ConnectionMessages.driver_encrypt_tip);
            trustCertificateCheck.setToolTipText(ConnectionMessages.driver_trust_certificate_tip);
        }

        @Override
        void addSecurityRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addGroupGap();
            form.addRow(ConnectionMessages.driver_encrypt, ConnectionSettingsPanel.leftAligned(encryptCombo));
            form.addRow((JLabel) null, trustCertificateCheck);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            String encrypt = configuration.getProperty(DRIVER_PROP_ENCRYPT);
            encryptCombo.setSelectedIndex(ENCRYPT_STRICT.equalsIgnoreCase(encrypt) ? 2 : CommonUtils.toBoolean(encrypt) ? 1 : 0);
            trustCertificateCheck.setSelected(CommonUtils.getBoolean(
                configuration.getProviderProperty(SQLServerConstants.PROP_SSL_TRUST_SERVER_CERTIFICATE), false));
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            switch (encryptCombo.getSelectedIndex()) {
                case 1 -> configuration.setProperty(DRIVER_PROP_ENCRYPT, Boolean.TRUE.toString());
                case 2 -> configuration.setProperty(DRIVER_PROP_ENCRYPT, ENCRYPT_STRICT);
                default -> configuration.removeProperty(DRIVER_PROP_ENCRYPT);
            }
            setProviderProperty(configuration, SQLServerConstants.PROP_SSL_TRUST_SERVER_CERTIFICATE,
                String.valueOf(trustCertificateCheck.isSelected()), Boolean.FALSE.toString());
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(SQLServerConstants.PROP_SSL_TRUST_SERVER_CERTIFICATE);
        }
    }

    /**
     * Oracle: Basic (server name + SID/service name) or TNS (network alias + tnsnames folder).
     */
    private static final class OracleFields extends ConnectionDriverFields {
        private static final String NLS_LENGTH_BYTE = "BYTE"; //$NON-NLS-1$
        private static final String NLS_LENGTH_CHAR = "CHAR"; //$NON-NLS-1$
        private static final String[] CONNECTION_TYPE_ITEMS = {ConnectionMessages.driver_oracle_basic, "TNS"}; //$NON-NLS-1$

        private final JComboBox<String> connectionTypeCombo = new JComboBox<>(CONNECTION_TYPE_ITEMS);
        private final JComboBox<String> sidServiceCombo = new JComboBox<>();
        private final JLabel aliasLabel = new JLabel(ConnectionMessages.driver_oracle_alias);
        private final JComboBox<String> aliasCombo = new JComboBox<>();
        private final JTextField aliasField;
        private final JLabel tnsPathLabel = new JLabel(ConnectionMessages.driver_oracle_tns_folder);
        private final JTextField tnsPathField = new JTextField();
        private final JComponent tnsPathRow;
        /**
         * Session settings (NLS) by provider property: combo boxes (language, territory, length semantics) and text fields.
         */
        private final Map<String, JComboBox<String>> sessionCombos = new LinkedHashMap<>();
        private final Map<String, JTextField> sessionFields = new LinkedHashMap<>();
        private boolean loading;

        OracleFields(@NotNull Runnable changeListener) {
            super(changeListener);
            for (OracleConnectionType type : OracleConnectionType.values()) {
                sidServiceCombo.addItem(type.getTitle());
            }
            sidServiceCombo.setToolTipText(ConnectionMessages.driver_oracle_sid_service_tip);
            aliasCombo.setEditable(true);
            aliasField = (JTextField) aliasCombo.getEditor().getEditorComponent();
            tnsPathField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.driver_oracle_tns_folder_placeholder);
            tnsPathField.setToolTipText(ConnectionMessages.driver_oracle_tns_folder_tip);
            tnsPathRow = NetworkHandlerPanel.createPathField(tnsPathField, ConnectionMessages.driver_oracle_tns_folder_tip, true);
            connectionTypeCombo.addActionListener(e -> {
                updateVisibility();
                if (!loading) {
                    changeListener.run();
                    if (SwingUtilities.getWindowAncestor(connectionTypeCombo) instanceof ConnectionDialog dialog) {
                        // Rows were shown or hidden
                        dialog.fitToContent();
                    }
                }
            });
            DocumentListener listener = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    changed(e);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    changed(e);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    changed(e);
                }

                private void changed(@NotNull DocumentEvent e) {
                    if (loading) {
                        return;
                    }
                    if (e.getDocument() == tnsPathField.getDocument()) {
                        // Invoked later: the alias combo can't be refilled while a document event is being delivered
                        SwingUtilities.invokeLater(OracleFields.this::refreshAliases);
                    }
                    changeListener.run();
                }
            };
            aliasField.getDocument().addDocumentListener(listener);
            tnsPathField.getDocument().addDocumentListener(listener);
            List<String> languages = new ArrayList<>();
            for (OracleLanguage language : OracleLanguage.values()) {
                languages.add(language.getLanguage());
            }
            List<String> territories = new ArrayList<>();
            for (OracleTerritory territory : OracleTerritory.values()) {
                territories.add(territory.getTerritory());
            }
            addSessionCombo(OracleConstants.PROP_SESSION_LANGUAGE, languages, ConnectionMessages.driver_oracle_nls_language_tip, listener);
            addSessionCombo(OracleConstants.PROP_SESSION_TERRITORY, territories, ConnectionMessages.driver_oracle_nls_territory_tip, listener);
            addSessionField(OracleConstants.PROP_SESSION_NLS_DATE_FORMAT, ConnectionMessages.driver_oracle_nls_date_format_tip, listener);
            addSessionField(OracleConstants.PROP_SESSION_NLS_TIMESTAMP_FORMAT, ConnectionMessages.driver_oracle_nls_timestamp_format_tip, listener);
            addSessionCombo(OracleConstants.PROP_SESSION_NLS_LENGTH_FORMAT, List.of(NLS_LENGTH_BYTE, NLS_LENGTH_CHAR),
                ConnectionMessages.driver_oracle_nls_length_tip, listener);
            addSessionField(OracleConstants.PROP_SESSION_NLS_CURRENCY_FORMAT, ConnectionMessages.driver_oracle_nls_currency_tip, listener);
        }

        private void addSessionCombo(@NotNull String property, @NotNull List<String> values, @NotNull String tip, @NotNull DocumentListener listener) {
            JComboBox<String> combo = new JComboBox<>();
            // The first item is the database default (no ALTER SESSION), as SWT's "Default"
            combo.addItem(ConnectionMessages.driver_oracle_nls_default);
            values.forEach(combo::addItem);
            combo.setEditable(true);
            combo.setMaximumRowCount(20);
            combo.setToolTipText(tip);
            ((JTextField) combo.getEditor().getEditorComponent()).getDocument().addDocumentListener(listener);
            sessionCombos.put(property, combo);
        }

        private void addSessionField(@NotNull String property, @NotNull String tip, @NotNull DocumentListener listener) {
            JTextField field = new JTextField();
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.driver_oracle_nls_default);
            field.setToolTipText(tip);
            field.getDocument().addDocumentListener(listener);
            sessionFields.put(property, field);
        }

        @NotNull
        @Override
        OptionsPage getOptionsPage() {
            ConnectionSettingsPanel.FormBuilder form = new ConnectionSettingsPanel.FormBuilder();
            form.addRow(ConnectionMessages.driver_oracle_nls_language, sessionCombos.get(OracleConstants.PROP_SESSION_LANGUAGE));
            form.addRow(ConnectionMessages.driver_oracle_nls_territory, sessionCombos.get(OracleConstants.PROP_SESSION_TERRITORY));
            form.addRow(ConnectionMessages.driver_oracle_nls_date_format, sessionFields.get(OracleConstants.PROP_SESSION_NLS_DATE_FORMAT));
            form.addRow(ConnectionMessages.driver_oracle_nls_timestamp_format, sessionFields.get(OracleConstants.PROP_SESSION_NLS_TIMESTAMP_FORMAT));
            form.addRow(ConnectionMessages.driver_oracle_nls_length, sessionCombos.get(OracleConstants.PROP_SESSION_NLS_LENGTH_FORMAT));
            form.addRow(ConnectionMessages.driver_oracle_nls_currency, sessionFields.get(OracleConstants.PROP_SESSION_NLS_CURRENCY_FORMAT));
            JLabel hint = new JLabel(ConnectionMessages.driver_oracle_nls_hint);
            hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            hint.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
            form.addGroupGap();
            form.addRow((JLabel) null, hint);
            form.addFiller();
            JPanel page = form.getPanel();
            page.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            return new OptionsPage(ConnectionMessages.driver_oracle_session_tab,
                new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        }

        @Override
        void addLeadingRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            connectionTypeCombo.setToolTipText(ConnectionMessages.driver_oracle_type_tip);
            form.addRow(ConnectionMessages.settings_connection_type, ConnectionSettingsPanel.leftAligned(connectionTypeCombo));
        }

        @NotNull
        @Override
        JComponent decorateDatabaseField(@NotNull JTextField databaseField) {
            JPanel panel = new JPanel(new BorderLayout(6, 0));
            panel.add(databaseField, BorderLayout.CENTER);
            panel.add(sidServiceCombo, BorderLayout.EAST);
            return panel;
        }

        @Override
        void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(aliasLabel, aliasCombo);
            form.addRow(tnsPathLabel, tnsPathRow);
        }

        @Override
        boolean isGenericAddressUsed() {
            return !isTns();
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            loading = true;
            try {
                DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
                String sidService = configuration.getProviderProperty(OracleConstants.PROP_SID_SERVICE);
                // The provider builds a service name URL unless SID is set (OracleDataSourceProvider.getConnectionURL)
                sidServiceCombo.setSelectedItem(OracleConnectionType.SID.name().equals(sidService)
                    ? OracleConnectionType.SID.getTitle() : OracleConnectionType.SERVICE.getTitle());
                boolean tns = OracleConstants.ConnectionType.isType(configuration, OracleConstants.ConnectionType.TNS);
                connectionTypeCombo.setSelectedIndex(tns ? 1 : 0);
                tnsPathField.setText(CommonUtils.notEmpty(configuration.getProviderProperty(OracleConstants.PROP_TNS_PATH)));
                refreshAliases();
                aliasField.setText(tns ? CommonUtils.notEmpty(configuration.getDatabaseName()) : "");
                for (Map.Entry<String, JComboBox<String>> entry : sessionCombos.entrySet()) {
                    String value = configuration.getProviderProperty(entry.getKey());
                    entry.getValue().setSelectedItem(CommonUtils.isEmpty(value) ? ConnectionMessages.driver_oracle_nls_default : value);
                }
                for (Map.Entry<String, JTextField> entry : sessionFields.entrySet()) {
                    entry.getValue().setText(CommonUtils.notEmpty(configuration.getProviderProperty(entry.getKey())));
                }
            } finally {
                loading = false;
            }
            updateVisibility();
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            // OracleConnectionPage.saveSettings
            String type = urlMode ? OracleConstants.ConnectionType.CUSTOM
                : isTns() ? OracleConstants.ConnectionType.TNS : OracleConstants.ConnectionType.BASIC;
            setProviderProperty(configuration, OracleConstants.PROP_CONNECTION_TYPE, type, OracleConstants.ConnectionType.BASIC);
            if (OracleConstants.ConnectionType.TNS.equals(type)) {
                configuration.setDatabaseName(aliasField.getText().trim());
                configuration.setProviderProperty(OracleConstants.PROP_TNS_PATH, tnsPathField.getText().trim());
            }
            setProviderProperty(configuration, OracleConstants.PROP_SID_SERVICE,
                OracleConnectionType.getTypeForTitle(CommonUtils.notEmpty((String) sidServiceCombo.getSelectedItem())).name(),
                OracleConnectionType.SERVICE.name());
            // OracleConnectionExtraPage.saveSettings: the default (or an empty value) removes the property
            for (Map.Entry<String, JComboBox<String>> entry : sessionCombos.entrySet()) {
                String value = ((JTextField) entry.getValue().getEditor().getEditorComponent()).getText().trim();
                setSessionProperty(configuration, entry.getKey(), value.equals(ConnectionMessages.driver_oracle_nls_default) ? "" : value); //$NON-NLS-1$
            }
            for (Map.Entry<String, JTextField> entry : sessionFields.entrySet()) {
                setSessionProperty(configuration, entry.getKey(), entry.getValue().getText().trim());
            }
        }

        private static void setSessionProperty(@NotNull DBPConnectionConfiguration configuration, @NotNull String name, @NotNull String value) {
            if (value.isEmpty()) {
                configuration.removeProviderProperty(name);
            } else {
                configuration.setProviderProperty(name, value);
            }
        }

        @Nullable
        @Override
        String validate() {
            return isTns() && aliasField.getText().isBlank() ? ConnectionMessages.driver_oracle_enter_alias : null;
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            Set<String> properties = new HashSet<>(sessionCombos.keySet());
            properties.addAll(sessionFields.keySet());
            properties.add(OracleConstants.PROP_SID_SERVICE);
            return properties;
        }

        private boolean isTns() {
            return connectionTypeCombo.getSelectedIndex() == 1;
        }

        private void updateVisibility() {
            boolean tns = isTns();
            sidServiceCombo.setVisible(!tns);
            for (JComponent component : new JComponent[]{aliasLabel, aliasCombo, tnsPathLabel, tnsPathRow}) {
                component.setVisible(tns);
            }
        }

        /**
         * Fills the alias list from tnsnames.ora (OracleConnectionPage.getAvailableServiceNames without the client home selector).
         */
        private void refreshAliases() {
            String typed = aliasField.getText();
            String tnsPath = tnsPathField.getText().trim();
            Collection<String> aliases;
            if (!tnsPath.isEmpty()) {
                File folder = new File(tnsPath);
                aliases = folder.exists() ? OCIUtils.readTnsNames(folder, false).keySet() : Collections.emptyList();
            } else {
                File defaultHome = OCIUtils.getDefaultOraHomePath();
                aliases = defaultHome != null
                    ? OCIUtils.readTnsNames(defaultHome, false).keySet() : OCIUtils.readTnsNames(null, true).keySet();
            }
            boolean wasLoading = loading;
            loading = true;
            try {
                aliasCombo.removeAllItems();
                for (String alias : aliases) {
                    aliasCombo.addItem(alias);
                }
                aliasField.setText(typed);
            } finally {
                loading = wasLoading;
            }
        }
    }

    /**
     * Sets or (empty value) removes a provider property.
     */
    protected static void setOrRemoveProviderProperty(@NotNull DBPConnectionConfiguration configuration, @NotNull String name, @NotNull String value) {
        if (value.isEmpty()) {
            configuration.removeProviderProperty(name);
        } else {
            configuration.setProviderProperty(name, value);
        }
    }

    /**
     * Runs {@link #changeListener} when the text of a field changes (the dialog validates again).
     */
    protected void listenTo(@NotNull JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
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
        });
    }

    /**
     * BigQuery: the project (the database name; BQDataSource passes it as the driver property ProjectId). The URL template
     * has no {database}, so the generic form has no field for it.
     */
    private static final class BigQueryFields extends ConnectionDriverFields {
        private final JTextField projectField = new JTextField();

        BigQueryFields(@NotNull Runnable changeListener) {
            super(changeListener);
            projectField.setToolTipText(ConnectionMessages.driver_bigquery_project_tip);
            listenTo(projectField);
        }

        @Override
        void addLeadingRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(ConnectionMessages.driver_bigquery_project, projectField);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            projectField.setText(CommonUtils.notEmpty(dataSource.getConnectionConfiguration().getDatabaseName()));
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            configuration.setDatabaseName(projectField.getText().trim());
        }

        @Nullable
        @Override
        String validate() {
            // BQConnectionPage.isComplete
            return projectField.getText().isBlank() ? ConnectionMessages.driver_bigquery_enter_project : null;
        }
    }

    /**
     * Snowflake: warehouse and default schema (SnowflakeConnectionPage). They are read where the model reads them
     * (warehouse: server name, then the provider property; schema: the internal provider property, then "schema") and
     * written where SWT writes them (server name, internal property).
     */
    private static final class SnowflakeFields extends ConnectionDriverFields {
        private final JTextField warehouseField = new JTextField();
        private final JTextField schemaField = new JTextField();

        SnowflakeFields(@NotNull Runnable changeListener) {
            super(changeListener);
            warehouseField.setToolTipText(ConnectionMessages.driver_snowflake_warehouse_tip);
            schemaField.setToolTipText(ConnectionMessages.driver_snowflake_schema_tip);
        }

        @Override
        void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(ConnectionMessages.driver_snowflake_warehouse, warehouseField);
            form.addRow(ConnectionMessages.driver_snowflake_schema, schemaField);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            warehouseField.setText(CommonUtils.notEmpty(SnowflakeUtils.getWarehouse(configuration)));
            schemaField.setText(getSchema(configuration));
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            // Written only when the value differs from the one the model would use: an unchanged connection stays equal
            // even when its value is in the fallback place, and an edited value can't be hidden by the fallback
            String warehouse = warehouseField.getText().trim();
            if (!warehouse.equals(CommonUtils.notEmpty(SnowflakeUtils.getWarehouse(configuration)))) {
                configuration.setServerName(warehouse);
                configuration.removeProviderProperty(SnowflakeConstants.PROP_WAREHOUSE);
            }
            String schema = schemaField.getText().trim();
            if (!schema.equals(getSchema(configuration))) {
                setOrRemoveProviderProperty(configuration, SnowflakeConstants.PROP_SCHEMA, schema);
                configuration.removeProviderProperty(SnowflakeConstants.PROP_SCHEMA2);
            }
        }

        /**
         * The schema the model uses (SnowflakeDataSourceProvider.getConnectionURL).
         */
        @NotNull
        private static String getSchema(@NotNull DBPConnectionConfiguration configuration) {
            String schema = configuration.getProviderProperty(SnowflakeConstants.PROP_SCHEMA);
            if (CommonUtils.isEmpty(schema)) {
                schema = configuration.getProviderProperty(SnowflakeConstants.PROP_SCHEMA2);
            }
            return CommonUtils.notEmpty(schema);
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(SnowflakeConstants.PROP_WAREHOUSE, SnowflakeConstants.PROP_SCHEMA, SnowflakeConstants.PROP_SCHEMA2);
        }
    }

    /**
     * SAP HANA: edition, instance number and tenant database (HANAConnectionPage). The model reads none of the two
     * provider properties: they only let the dialog work out the port (platform editions: 3NN15 for a single database,
     * 3NN13 for the system or a tenant database; Express 39015; Cloud 443) and the tenant database, which is the driver
     * property databaseName.
     */
    private static final class HanaFields extends ConnectionDriverFields {
        private static final String PROP_EDITION = "edition"; //$NON-NLS-1$
        private static final String PROP_INSTANCE_NUMBER = "instanceNumber"; //$NON-NLS-1$
        private static final String DRIVER_PROP_DATABASE_NAME = "databaseName"; //$NON-NLS-1$
        private static final String EXPRESS_INSTANCE = "90"; //$NON-NLS-1$
        private static final String EXPRESS_PORT = "39015"; //$NON-NLS-1$
        private static final String CLOUD_PORT = "443"; //$NON-NLS-1$

        /**
         * HANAEdition of the SWT page, in the same order.
         */
        private enum Edition {
            GENERIC, PLATFORM_SINGLE_DB, PLATFORM_SYSTEM_DB, PLATFORM_TENANT_DB, EXPRESS, CLOUD;

            boolean isPlatform() {
                return this == PLATFORM_SINGLE_DB || this == PLATFORM_SYSTEM_DB || this == PLATFORM_TENANT_DB;
            }
        }

        private final String defaultPort;
        private final JComboBox<String> editionCombo = new JComboBox<>(new String[]{
            ConnectionMessages.driver_hana_edition_generic, ConnectionMessages.driver_hana_edition_single,
            ConnectionMessages.driver_hana_edition_system, ConnectionMessages.driver_hana_edition_tenant,
            ConnectionMessages.driver_hana_edition_express, ConnectionMessages.driver_hana_edition_cloud});
        private final JLabel instanceLabel = new JLabel(ConnectionMessages.driver_hana_instance);
        private final JTextField instanceField = new JTextField(4);
        private final JLabel databaseLabel = new JLabel(ConnectionMessages.driver_hana_tenant_database);
        private final JTextField databaseField = new JTextField();
        private final JLabel portHint = new JLabel();
        private String typedInstance = ""; //$NON-NLS-1$
        private boolean loading;

        HanaFields(@NotNull Runnable changeListener, @Nullable String defaultPort) {
            super(changeListener);
            this.defaultPort = CommonUtils.notEmpty(defaultPort);
            instanceField.setToolTipText(ConnectionMessages.driver_hana_instance_tip);
            portHint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            portHint.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
            editionCombo.addActionListener(e -> {
                updateVisibility();
                if (!loading) {
                    changeListener.run();
                    if (SwingUtilities.getWindowAncestor(editionCombo) instanceof ConnectionDialog dialog) {
                        // Rows were shown or hidden
                        dialog.fitToContent();
                    }
                }
            });
            listenTo(instanceField);
            listenTo(databaseField);
            instanceField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    updatePortHint();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    updatePortHint();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    updatePortHint();
                }
            });
        }

        @Override
        void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(ConnectionMessages.driver_hana_edition, ConnectionSettingsPanel.leftAligned(editionCombo));
            form.addRow(instanceLabel, ConnectionSettingsPanel.leftAligned(instanceField));
            form.addRow(databaseLabel, databaseField);
            form.addRow((JLabel) null, portHint);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            loading = true;
            try {
                DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
                Edition edition = Edition.GENERIC;
                String saved = configuration.getProviderProperty(PROP_EDITION);
                for (Edition value : Edition.values()) {
                    if (value.name().equals(saved)) {
                        edition = value;
                    }
                }
                typedInstance = CommonUtils.notEmpty(configuration.getProviderProperty(PROP_INSTANCE_NUMBER));
                instanceField.setEditable(true);
                instanceField.setText(typedInstance);
                databaseField.setText(CommonUtils.notEmpty(getDatabaseName(configuration)));
                editionCombo.setSelectedIndex(edition.ordinal());
            } finally {
                loading = false;
            }
            updateVisibility();
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            // HANAConnectionPage.saveSettings
            Edition edition = getEdition();
            setProviderProperty(configuration, PROP_EDITION, edition.name(), Edition.GENERIC.name());
            if (edition == Edition.GENERIC) {
                return;
            }
            setOrRemoveProviderProperty(configuration, PROP_INSTANCE_NUMBER, getInstance());
            String database = edition == Edition.PLATFORM_TENANT_DB ? databaseField.getText().trim() : ""; //$NON-NLS-1$
            for (String name : new ArrayList<>(configuration.getProperties().keySet())) {
                // The driver reads the property name in any case
                if (name.equalsIgnoreCase(DRIVER_PROP_DATABASE_NAME) && (database.isEmpty() || !name.equals(DRIVER_PROP_DATABASE_NAME))) {
                    configuration.removeProperty(name);
                }
            }
            if (!database.isEmpty()) {
                configuration.setProperty(DRIVER_PROP_DATABASE_NAME, database);
            }
            String port = getPort(configuration.getHostPort());
            if (!urlMode && port != null) {
                configuration.setHostPort(port);
            }
        }

        @Nullable
        @Override
        String validate() {
            // HANAConnectionPage.isComplete
            Edition edition = getEdition();
            if (edition.isPlatform() && !instanceField.getText().isBlank()) {
                int instance = CommonUtils.toInt(instanceField.getText().trim(), -1);
                if (instance < 0 || instance > 99) {
                    return ConnectionMessages.driver_hana_enter_instance;
                }
            }
            if (edition == Edition.PLATFORM_TENANT_DB && databaseField.getText().isBlank()) {
                return ConnectionMessages.driver_hana_enter_database;
            }
            return null;
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(PROP_EDITION, PROP_INSTANCE_NUMBER);
        }

        @NotNull
        private Edition getEdition() {
            return Edition.values()[Math.max(0, editionCombo.getSelectedIndex())];
        }

        /**
         * The instance number the edition uses: typed (platform), fixed (Express) or none.
         */
        @NotNull
        private String getInstance() {
            Edition edition = getEdition();
            return edition.isPlatform() ? instanceField.getText().trim() : edition == Edition.EXPRESS ? EXPRESS_INSTANCE : ""; //$NON-NLS-1$
        }

        /**
         * The port the edition sets, or {@code null} when the typed one is kept (HANAConnectionPage.editionUpdated).
         *
         * @param typedPort the port written from the server name (the driver's default when none was typed)
         */
        @Nullable
        private String getPort(@Nullable String typedPort) {
            Edition edition = getEdition();
            if (edition.isPlatform()) {
                String instance = instanceField.getText().trim();
                int number = CommonUtils.toInt(instance, -1);
                if (instance.isEmpty() || number < 0 || number > 99) {
                    return null;
                }
                return String.format(edition == Edition.PLATFORM_SINGLE_DB ? "3%02d15" : "3%02d13", number); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (edition == Edition.EXPRESS) {
                // Express listens on 39015 unless another port was typed
                return CommonUtils.isEmpty(typedPort) || typedPort.equals(defaultPort) ? EXPRESS_PORT : null;
            }
            return edition == Edition.CLOUD ? CLOUD_PORT : null;
        }

        private void updateVisibility() {
            Edition edition = getEdition();
            boolean instance = edition.isPlatform() || edition == Edition.EXPRESS;
            instanceLabel.setVisible(instance);
            instanceField.setVisible(instance);
            // Express shows its fixed number; the typed one comes back on a platform edition (HANAConnectionPage keeps instanceValue)
            if (edition == Edition.EXPRESS && instanceField.isEditable()) {
                typedInstance = instanceField.getText();
                instanceField.setText(EXPRESS_INSTANCE);
            } else if (edition.isPlatform() && !instanceField.isEditable()) {
                instanceField.setText(typedInstance);
            }
            instanceField.setEditable(edition.isPlatform());
            boolean database = edition == Edition.PLATFORM_TENANT_DB;
            databaseLabel.setVisible(database);
            databaseField.setVisible(database);
            updatePortHint();
        }

        private void updatePortHint() {
            Edition edition = getEdition();
            String port = edition == Edition.EXPRESS ? EXPRESS_PORT : getPort(null);
            portHint.setText(port == null ? "" : NLS.bind(ConnectionMessages.driver_hana_port_hint, port)); //$NON-NLS-1$
            portHint.setVisible(port != null);
        }

        /**
         * The driver property databaseName in any case (HANAConnectionPage.getProperty).
         */
        @Nullable
        private static String getDatabaseName(@NotNull DBPConnectionConfiguration configuration) {
            for (Map.Entry<String, String> entry : configuration.getProperties().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(DRIVER_PROP_DATABASE_NAME)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /**
     * Firebird Embedded: the folder of the native client library (FireBirdEmbeddedConnectionPage). The provider adds it
     * to jna.library.path before connecting (FireBirdDataSourceProvider.getConnectionURL).
     */
    private static final class FirebirdEmbeddedFields extends ConnectionDriverFields {
        private static final String PROP_NATIVE_LIBRARY_PATH = "nativeLibraryPath"; //$NON-NLS-1$

        private final JTextField pathField = new JTextField();

        FirebirdEmbeddedFields(@NotNull Runnable changeListener) {
            super(changeListener);
            pathField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.driver_firebird_library_placeholder);
            pathField.setToolTipText(ConnectionMessages.driver_firebird_library_tip);
        }

        @Override
        void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(ConnectionMessages.driver_firebird_library,
                NetworkHandlerPanel.createPathField(pathField, ConnectionMessages.driver_firebird_library_tip, true));
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            pathField.setText(CommonUtils.notEmpty(dataSource.getConnectionConfiguration().getProviderProperty(PROP_NATIVE_LIBRARY_PATH)));
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            setOrRemoveProviderProperty(configuration, PROP_NATIVE_LIBRARY_PATH, pathField.getText().trim());
        }
    }

    /**
     * Mimer SQL: protocol (provider property, "local" = shared memory on this computer) and the PROGRAM security layer
     * (driver property program, auth property programPwd, which the registry keeps in the secure storage)
     * (MimerConnectionSettingsPage).
     */
    private static final class MimerFields extends ConnectionDriverFields {
        private final JComboBox<String> protocolCombo = new JComboBox<>(new String[]{
            ConnectionMessages.driver_mimer_protocol_tcp, ConnectionMessages.driver_mimer_protocol_local});
        private final JTextField programField = new JTextField();
        private final JPasswordField programPasswordField = new JPasswordField();

        MimerFields(@NotNull Runnable changeListener) {
            super(changeListener);
            protocolCombo.setToolTipText(ConnectionMessages.driver_mimer_protocol_tip);
            programField.setToolTipText(ConnectionMessages.driver_mimer_program_tip);
            programPasswordField.setToolTipText(ConnectionMessages.driver_mimer_program_tip);
        }

        @Override
        void addAddressRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(ConnectionMessages.driver_mimer_protocol, ConnectionSettingsPanel.leftAligned(protocolCombo));
        }

        @Override
        void addSecurityRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addGroupGap();
            form.addRow(ConnectionMessages.driver_mimer_program, programField);
            form.addRow(ConnectionMessages.driver_mimer_program_password, programPasswordField);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource, boolean isNew) {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            protocolCombo.setSelectedIndex(MimerConstants.PROTOCOL_LOCAL.equals(configuration.getProviderProperty(MimerConstants.PROP_PROTOCOL)) ? 1 : 0);
            programField.setText(CommonUtils.notEmpty(configuration.getProperty(MimerConstants.PROP_PROGRAM)));
            programPasswordField.setText(CommonUtils.notEmpty(configuration.getAuthProperty(MimerConstants.PROP_PROGRAM_PASSWORD)));
        }

        @Override
        void saveSettings(@NotNull DBPConnectionConfiguration configuration, boolean urlMode) {
            setProviderProperty(configuration, MimerConstants.PROP_PROTOCOL,
                protocolCombo.getSelectedIndex() == 1 ? MimerConstants.PROTOCOL_LOCAL : MimerConstants.PROTOCOL_TCP, MimerConstants.PROTOCOL_TCP);
            String program = programField.getText().trim();
            if (program.isEmpty()) {
                configuration.removeProperty(MimerConstants.PROP_PROGRAM);
            } else {
                configuration.setProperty(MimerConstants.PROP_PROGRAM, program);
            }
            String password = new String(programPasswordField.getPassword());
            if (!password.isEmpty()) {
                configuration.setAuthProperty(MimerConstants.PROP_PROGRAM_PASSWORD, password);
            } else if (configuration.getAuthProperty(MimerConstants.PROP_PROGRAM_PASSWORD) != null) {
                configuration.setAuthProperty(MimerConstants.PROP_PROGRAM_PASSWORD, null);
            }
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(MimerConstants.PROP_PROTOCOL);
        }
    }
}
