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
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.bigquery.auth.BQAuthModel;
import com.seizia.chromite.ext.bigquery.model.BQConstants;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.auth.SQLServerAuthModelNTLM;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.auth.OracleAuthModelDatabaseNative;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionRole;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.model.AuthModelPgPass;
import com.seizia.chromite.ext.snowflake.SnowflakeConstants;
import com.seizia.chromite.ext.snowflake.model.auth.SnowflakeAuthModelSnowflake;
import com.seizia.chromite.ext.spanner.auth.SpannerAuthModel;
import com.seizia.chromite.ext.spanner.model.SpannerConstants;
import com.seizia.chromite.model.access.DBAAuthModel;
import com.seizia.chromite.model.connection.DBPAuthModelDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.registry.DataSourceDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 인증 방식마다 Login·Password 밖에 더 있는 칸(Connect 대화상자의 Authentication 묶음 아래).
 *
 * 설계: SWT는 인증 방식 구현 클래스마다 설정 화면을 {@code propertyConfigurator}로 등록한다. 범용 칸(Login, Password)은
 * {@link ConnectionAuthModels}가 맡고, 여기에는 그 화면들이 범용 칸에 더하는 칸만 옮겼다. 값의 저장 위치는 각 SWT 화면과 같다.
 * <ul>
 *     <li>SQL Server NTLM({@link SQLServerAuthModelNTLM}, SWT {@code SQLServerAuthConfigurator}): "Use integrated security" →
 *     드라이버 속성 {@code integratedSecurity}(기본 켬). 다른 방식으로 바꾸면 그 속성을 지운다(SWT {@code resetSettings}).</li>
 *     <li>Oracle Username/password({@link OracleAuthModelDatabaseNative}, SWT {@code OracleAuthDatabaseNativeConfigurator}): Role
 *     (Normal / SYSDBA / SYSOPER) → 인증 속성 {@code oracle.logon-as}. 저장할 때 예전 키({@code internal-logon})와, 같은 값을 담던
 *     공급자 속성 {@code oracle.logon-as}를 지운다. 그래서 Provider Properties 표에서는 Role 줄을 뺀다({@link #getHandledProviderProperties}).</li>
 *     <li>PostgreSQL PgPass({@link AuthModelPgPass}, SWT {@code PostgreAuthPgPassConfigurator}): "Override host name for pgpass" + 이름 →
 *     공급자 속성 {@code overriddenUsername}(모델의 상수 이름 그대로). 비밀번호는 pgpass 파일에 있으므로 SWT처럼 비밀번호 저장을 켠다.</li>
 *     <li>BigQuery({@link BQAuthModel}, SWT {@code BigQueryAuthConfigurator}): OAuth type(Service-based / User-based) → 드라이버 속성
 *     {@code OAuthType}(0 / 1), 서비스 계정 키 파일 → 드라이버 속성 {@code OAuthPvtKeyPath}(Service-based일 때만 보임). 두 값은 JDBC 드라이버가
 *     그대로 읽는다.</li>
 *     <li>Snowflake({@link SnowflakeAuthModelSnowflake}, SWT {@code SnowflakeAuthSnowflakeConfigurator}): Role(입력 가능) → 인증 속성 {@code role},
 *     Authenticator(빈 값 / Snowflake / External browser, 입력 가능) → 인증 속성 {@code authenticator}. 읽을 때는 SWT처럼 예전 공급자 속성
 *     ({@code @…-role@}, {@code @…-authenticator@})도 보고, 저장할 때 예전 키를 지운다.</li>
 *     <li>Spanner({@link SpannerAuthModel}, SWT {@code SpannerAuthConfigurator}): 서비스 계정 키 파일 → 공급자 속성 {@code PvtKeyPath}.
 *     비우면 속성을 지운다(SWT는 빈 문자열을 남겨 공식 드라이버에 {@code credentials=""}가 넘어갔다, {@code SpannerDataSource}).</li>
 * </ul>
 * 인증 방식 클래스는 모델 번들의 것을 {@code instanceof}로 판단한다(SWT 등록도 클래스 계층으로 고른다).
 * 바뀌지 않은 연결을 Save해도 "바뀜"이 되지 않도록, 모델 기본값(OAuth type 0, 빈 Role·Authenticator)은 없는 키에 새로 넣지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 BigQuery OAuth 종류·키 파일, Snowflake Role·Authenticator, Spanner 키 파일 (docs/chromite/swing-conn-advanced.md "1. 인증 방식")
 */
final class ConnectionAuthExtras {

    private static final Log log = Log.getLog(ConnectionAuthExtras.class);

    /**
     * Extra fields of one auth model.
     */
    abstract static class Extra {
        final DBPAuthModelDescriptor model;
        final List<JComponent> components = new ArrayList<>();

        Extra(@NotNull DBPAuthModelDescriptor model) {
            this.model = model;
        }

        abstract void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form);

        abstract void loadSettings(@NotNull DataSourceDescriptor dataSource);

        /**
         * The model is selected: writes the fields.
         */
        abstract void saveSettings(@NotNull DataSourceDescriptor target);

        /**
         * Another model is selected: removes what this model's fields wrote.
         */
        void resetSettings(@NotNull DataSourceDescriptor target) {
        }

        /**
         * Provider properties edited here (left out of the Provider Properties table).
         */
        @NotNull
        Set<String> getHandledProviderProperties() {
            return Collections.emptySet();
        }

        void setVisible(boolean visible) {
            for (JComponent component : components) {
                component.setVisible(visible);
            }
        }

        @NotNull
        protected <T extends JComponent> T register(@NotNull T component) {
            components.add(component);
            return component;
        }
    }

    private ConnectionAuthExtras() {
    }

    /**
     * The extra fields of the auth models in the Authentication combo (models without extra fields have none).
     */
    @NotNull
    static List<Extra> create(@NotNull List<DBPAuthModelDescriptor> models) {
        List<Extra> extras = new ArrayList<>();
        for (DBPAuthModelDescriptor model : models) {
            DBAAuthModel<?> instance;
            try {
                instance = model.getInstance();
            } catch (IllegalStateException e) {
                log.debug("Auth model " + model.getId() + " is not available", e);
                continue;
            }
            if (instance instanceof SQLServerAuthModelNTLM) {
                extras.add(new IntegratedSecurity(model));
            } else if (instance instanceof OracleAuthModelDatabaseNative) {
                extras.add(new OracleRole(model));
            } else if (instance instanceof AuthModelPgPass) {
                extras.add(new PgPassHostName(model));
            } else if (instance instanceof BQAuthModel) {
                extras.add(new BigQueryOAuth(model));
            } else if (instance instanceof SnowflakeAuthModelSnowflake) {
                extras.add(new SnowflakeRole(model));
            } else if (instance instanceof SpannerAuthModel) {
                extras.add(new SpannerKeyFile(model));
            }
        }
        return extras;
    }

    private static final class IntegratedSecurity extends Extra {
        private final JCheckBox check = register(new JCheckBox(ConnectionMessages.auth_integrated_security));

        IntegratedSecurity(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            check.setToolTipText(ConnectionMessages.auth_integrated_security_tip);
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow((JLabel) null, check);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            check.setSelected(CommonUtils.getBoolean(
                dataSource.getConnectionConfiguration().getProperty(SQLServerConstants.PROP_CONNECTION_INTEGRATED_SECURITY), true));
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            target.getConnectionConfiguration().setProperty(
                SQLServerConstants.PROP_CONNECTION_INTEGRATED_SECURITY, String.valueOf(check.isSelected()));
        }

        @Override
        void resetSettings(@NotNull DataSourceDescriptor target) {
            target.getConnectionConfiguration().removeProperty(SQLServerConstants.PROP_CONNECTION_INTEGRATED_SECURITY);
        }
    }

    private static final class OracleRole extends Extra {
        private final JLabel label = register(new JLabel(ConnectionMessages.auth_role));
        private final JComboBox<String> combo = register(new JComboBox<>());

        OracleRole(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            for (OracleConnectionRole role : OracleConnectionRole.values()) {
                combo.addItem(role.getTitle());
            }
            combo.setToolTipText(ConnectionMessages.auth_role_tip);
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(label, ConnectionSettingsPanel.leftAligned(combo));
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            // Same order as OracleAuthModelDatabaseNative: the auth property, the provider property, the legacy key
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            String role = configuration.getAuthProperty(OracleConstants.PROP_AUTH_LOGON_AS);
            if (CommonUtils.isEmpty(role)) {
                role = configuration.getProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS);
            }
            if (CommonUtils.isEmpty(role)) {
                role = configuration.getProviderProperty(OracleConstants.PROP_INTERNAL_LOGON);
            }
            combo.setSelectedIndex(0);
            if (!CommonUtils.isEmpty(role)) {
                for (OracleConnectionRole value : OracleConnectionRole.values()) {
                    if (value.getTitle().equalsIgnoreCase(role)) {
                        combo.setSelectedItem(value.getTitle());
                    }
                }
            }
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
            if (combo.getSelectedIndex() > 0) {
                configuration.setAuthProperties(Collections.singletonMap(
                    OracleConstants.PROP_AUTH_LOGON_AS, ((String) combo.getSelectedItem()).toLowerCase(Locale.ENGLISH)));
            } else if (configuration.getAuthProperty(OracleConstants.PROP_AUTH_LOGON_AS) != null) {
                // SWT replaces the auth properties with an empty map; removing only the role keeps an unchanged connection equal
                configuration.setAuthProperty(OracleConstants.PROP_AUTH_LOGON_AS, null);
            }
            // Legacy and duplicate places of the role
            configuration.removeProviderProperty(OracleConstants.PROP_INTERNAL_LOGON);
            configuration.removeProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS);
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(OracleConstants.PROP_AUTH_LOGON_AS);
        }
    }

    private static final class PgPassHostName extends Extra {
        private final JCheckBox check = register(new JCheckBox(ConnectionMessages.auth_pgpass_override));
        private final JTextField field = register(new JTextField());

        PgPassHostName(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            check.setToolTipText(ConnectionMessages.auth_pgpass_override_tip);
            check.addActionListener(e -> field.setEnabled(check.isSelected()));
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow((JLabel) null, check);
            form.addRow((JLabel) null, field);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            String hostName = dataSource.getConnectionConfiguration().getProviderProperty(PostgreConstants.PG_PASS_HOSTNAME);
            check.setSelected(!CommonUtils.isEmpty(hostName));
            field.setText(CommonUtils.notEmpty(hostName));
            field.setEnabled(check.isSelected());
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            if (check.isSelected()) {
                target.getConnectionConfiguration().setProviderProperty(PostgreConstants.PG_PASS_HOSTNAME, field.getText().trim());
            } else {
                // SWT puts a null value; removing it keeps an unchanged connection equal
                target.getConnectionConfiguration().removeProviderProperty(PostgreConstants.PG_PASS_HOSTNAME);
            }
            // The password is in the pgpass file (PostgreAuthPgPassConfigurator.saveSettings)
            target.setSavePassword(true);
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(PostgreConstants.PG_PASS_HOSTNAME);
        }
    }

    /**
     * BigQuery: OAuth type (driver property OAuthType, 0 = service account, 1 = user account) and the service account key file.
     */
    private static final class BigQueryOAuth extends Extra {
        private static final String SERVICE_BASED = "0"; //$NON-NLS-1$
        private static final String USER_BASED = "1"; //$NON-NLS-1$

        private final JLabel typeLabel = register(new JLabel(ConnectionMessages.auth_bigquery_oauth_type));
        private final JComboBox<String> typeCombo = register(new JComboBox<>(new String[]{
            ConnectionMessages.auth_bigquery_service_based, ConnectionMessages.auth_bigquery_user_based}));
        private final JLabel keyLabel = register(new JLabel(ConnectionMessages.auth_key_file));
        private final JTextField keyField = new JTextField();
        private final JComponent keyRow = register(NetworkHandlerPanel.createPathField(keyField, ConnectionMessages.auth_key_file, false));
        private boolean shown;

        BigQueryOAuth(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            keyField.setToolTipText(ConnectionMessages.auth_bigquery_key_file_tip);
            typeCombo.addActionListener(e -> updateKeyRow());
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(typeLabel, ConnectionSettingsPanel.leftAligned(typeCombo));
            form.addRow(keyLabel, keyRow);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            typeCombo.setSelectedIndex(USER_BASED.equals(configuration.getProperty(BQConstants.DRIVER_PROP_OAUTH_TYPE)) ? 1 : 0);
            keyField.setText(CommonUtils.notEmpty(configuration.getProperty(BQConstants.DRIVER_PROP_OAUTH_PVT_KEYPATH)));
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
            String type = typeCombo.getSelectedIndex() == 1 ? USER_BASED : SERVICE_BASED;
            // The driver's default (service account) is not added to a connection without the property
            if (!SERVICE_BASED.equals(type) || configuration.getProperty(BQConstants.DRIVER_PROP_OAUTH_TYPE) != null) {
                configuration.setProperty(BQConstants.DRIVER_PROP_OAUTH_TYPE, type);
            }
            String keyPath = keyField.getText().trim();
            if (SERVICE_BASED.equals(type) && !keyPath.isEmpty()) {
                configuration.setProperty(BQConstants.DRIVER_PROP_OAUTH_PVT_KEYPATH, keyPath);
            } else {
                configuration.removeProperty(BQConstants.DRIVER_PROP_OAUTH_PVT_KEYPATH);
            }
        }

        @Override
        void setVisible(boolean visible) {
            shown = visible;
            super.setVisible(visible);
            updateKeyRow();
        }

        /**
         * The key file is only used by a service account (BigQueryAuthConfigurator.updateKeyPathVisibility).
         */
        private void updateKeyRow() {
            boolean serviceBased = typeCombo.getSelectedIndex() == 0;
            keyLabel.setVisible(shown && serviceBased);
            keyRow.setVisible(shown && serviceBased);
            keyRow.revalidate();
        }
    }

    /**
     * Snowflake: role and authenticator (auth properties; earlier versions kept them in provider properties).
     */
    private static final class SnowflakeRole extends Extra {
        /**
         * Authenticator values of SnowflakeAuthSnowflakeConfigurator: none (the model's default), Snowflake, external browser (SSO).
         */
        private static final String AUTHENTICATOR_EXTERNAL_BROWSER = "externalbrowser"; //$NON-NLS-1$
        private static final String[] AUTHENTICATOR_VALUES = {"", SnowflakeConstants.AUTHENTICATOR_SNOWFLAKE, AUTHENTICATOR_EXTERNAL_BROWSER};
        private static final String[] AUTHENTICATOR_LABELS = {
            "", ConnectionMessages.auth_snowflake_authenticator_snowflake, ConnectionMessages.auth_snowflake_authenticator_browser}; //$NON-NLS-1$

        private final JLabel roleLabel = register(new JLabel(ConnectionMessages.auth_role));
        private final JTextField roleField = register(new JTextField());
        private final JLabel authenticatorLabel = register(new JLabel(ConnectionMessages.auth_snowflake_authenticator));
        private final JComboBox<String> authenticatorCombo = register(new JComboBox<>(AUTHENTICATOR_LABELS));

        SnowflakeRole(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            roleField.setToolTipText(ConnectionMessages.auth_snowflake_role_tip);
            // Other authenticators (e.g. oauth, username_password_mfa) can be typed
            authenticatorCombo.setEditable(true);
            authenticatorCombo.setToolTipText(ConnectionMessages.auth_snowflake_authenticator_tip);
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(roleLabel, roleField);
            form.addRow(authenticatorLabel, authenticatorCombo);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            // Same order as SnowflakeAuthSnowflakeConfigurator.loadSettings: the auth property, then the legacy provider properties
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            String role = configuration.getAuthProperty(SnowflakeConstants.PROP_AUTH_ROLE);
            if (CommonUtils.isEmpty(role)) {
                role = configuration.getProviderProperty(SnowflakeConstants.PROP_AUTH_ROLE);
            }
            if (CommonUtils.isEmpty(role)) {
                role = configuration.getProviderProperty(SnowflakeConstants.PROP_ROLE_LEGACY);
            }
            roleField.setText(CommonUtils.notEmpty(role));
            String authenticator = configuration.getAuthProperty(SnowflakeConstants.PROP_AUTHENTICATOR);
            if (CommonUtils.isEmpty(authenticator)) {
                authenticator = configuration.getProviderProperty(SnowflakeConstants.PROP_AUTHENTICATOR_LEGACY);
            }
            if (CommonUtils.isEmpty(authenticator)) {
                authenticator = configuration.getProviderProperty(SnowflakeConstants.PROP_AUTHENTICATOR);
            }
            authenticatorCombo.setSelectedItem(toLabel(CommonUtils.notEmpty(authenticator)));
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
            setAuthProperty(configuration, SnowflakeConstants.PROP_AUTH_ROLE, roleField.getText().trim());
            Object selected = authenticatorCombo.getEditor().getItem();
            setAuthProperty(configuration, SnowflakeConstants.PROP_AUTHENTICATOR, toValue(selected == null ? "" : selected.toString().trim())); //$NON-NLS-1$
            // The values now live in the auth properties (SnowflakeAuthSnowflakeConfigurator.saveSettings)
            configuration.removeProviderProperty(SnowflakeConstants.PROP_AUTHENTICATOR_LEGACY);
            configuration.removeProviderProperty(SnowflakeConstants.PROP_ROLE_LEGACY);
        }

        /**
         * An empty value removes the property (SWT keeps an empty authenticator; leaving no key keeps an unchanged
         * connection equal).
         */
        private static void setAuthProperty(@NotNull DBPConnectionConfiguration configuration, @NotNull String name, @NotNull String value) {
            if (!value.isEmpty()) {
                configuration.setAuthProperty(name, value);
            } else if (configuration.getAuthProperty(name) != null) {
                configuration.setAuthProperty(name, null);
            }
        }

        @NotNull
        private static String toLabel(@NotNull String value) {
            for (int i = 0; i < AUTHENTICATOR_VALUES.length; i++) {
                if (AUTHENTICATOR_VALUES[i].equalsIgnoreCase(value)) {
                    return AUTHENTICATOR_LABELS[i];
                }
            }
            return value;
        }

        @NotNull
        private static String toValue(@NotNull String text) {
            for (int i = 0; i < AUTHENTICATOR_LABELS.length; i++) {
                if (AUTHENTICATOR_LABELS[i].equals(text)) {
                    return AUTHENTICATOR_VALUES[i];
                }
            }
            return text;
        }
    }

    /**
     * Spanner: the service account key file (provider property PvtKeyPath).
     */
    private static final class SpannerKeyFile extends Extra {
        private final JLabel label = register(new JLabel(ConnectionMessages.auth_key_file));
        private final JTextField field = new JTextField();
        private final JComponent row = register(NetworkHandlerPanel.createPathField(field, ConnectionMessages.auth_key_file, false));

        SpannerKeyFile(@NotNull DBPAuthModelDescriptor model) {
            super(model);
            field.setToolTipText(ConnectionMessages.auth_spanner_key_file_tip);
        }

        @Override
        void addRows(@NotNull ConnectionSettingsPanel.FormBuilder form) {
            form.addRow(label, row);
        }

        @Override
        void loadSettings(@NotNull DataSourceDescriptor dataSource) {
            field.setText(CommonUtils.notEmpty(dataSource.getConnectionConfiguration().getProviderProperty(SpannerConstants.DRIVER_PROP_PVTKEYPATH)));
        }

        @Override
        void saveSettings(@NotNull DataSourceDescriptor target) {
            String path = field.getText().trim();
            if (path.isEmpty()) {
                target.getConnectionConfiguration().removeProviderProperty(SpannerConstants.DRIVER_PROP_PVTKEYPATH);
            } else {
                target.getConnectionConfiguration().setProviderProperty(SpannerConstants.DRIVER_PROP_PVTKEYPATH, path);
            }
        }

        @NotNull
        @Override
        Set<String> getHandledProviderProperties() {
            return Set.of(SpannerConstants.DRIVER_PROP_PVTKEYPATH);
        }
    }
}
