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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mssql.SQLServerConstants;
import org.jkiss.dbeaver.ext.mssql.auth.SQLServerAuthModelNTLM;
import org.jkiss.dbeaver.ext.oracle.model.OracleConstants;
import org.jkiss.dbeaver.ext.oracle.model.auth.OracleAuthModelDatabaseNative;
import org.jkiss.dbeaver.ext.oracle.model.dict.OracleConnectionRole;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.AuthModelPgPass;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPAuthModelDescriptor;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
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
 * </ul>
 * 인증 방식 클래스는 모델 번들의 것을 {@code instanceof}로 판단한다(SWT 등록도 클래스 계층으로 고른다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
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
}
