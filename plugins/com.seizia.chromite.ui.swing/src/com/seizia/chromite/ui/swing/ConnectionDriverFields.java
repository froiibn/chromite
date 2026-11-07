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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.oracle.OracleDataSourceProvider;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionType;
import com.seizia.chromite.ext.oracle.model.dict.OracleLanguage;
import com.seizia.chromite.ext.oracle.model.dict.OracleTerritory;
import com.seizia.chromite.ext.oracle.oci.OCIUtils;
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
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 Oracle 세션 설정(NLS) 탭과 드라이버별 Options 탭 고리({@link #getOptionsPage})
 *   (docs/chromite/swing-conn-advanced.md "Oracle 세션 설정")
 */
abstract class ConnectionDriverFields {

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
}
