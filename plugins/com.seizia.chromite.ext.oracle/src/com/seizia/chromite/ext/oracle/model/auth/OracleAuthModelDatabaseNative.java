/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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

package com.seizia.chromite.ext.oracle.model.auth;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionRole;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNative;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Properties;

/**
 * Oracle database native auth model.
 */
public class OracleAuthModelDatabaseNative extends AuthModelDatabaseNative<OracleDatabaseNativeCredentials> {

    public static final String ID = "oracle_native";

    @Override
    public Object initAuthentication(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull OracleDatabaseNativeCredentials credentials,
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull Properties connProperties
    ) throws DBException {
        String userName = configuration.getUserName();
        if (!CommonUtils.isEmpty(userName) && !userName.contains(" AS ")) {
            String role = configuration.getAuthProperty(OracleConstants.PROP_AUTH_LOGON_AS);
            if (CommonUtils.isEmpty(role)) {
                // Role can be also passed as provided property
                String logonAs = configuration.getProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS);
                if (!OracleConnectionRole.NORMAL.getTitle().equalsIgnoreCase(logonAs)) {
                    role = configuration.getProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS);
                }
            }
            if (!CommonUtils.isEmpty(role)) {
                userName += " AS " + role;
            }
        }

        boolean setOsUser = CommonUtils.getBoolean(
            configuration.getProviderProperty(OracleConstants.PROP_SET_OS_USER),
            false
        );
        if (setOsUser) {
            String formattedUsername = userName.toUpperCase().split(" AS ")[0];
            connProperties.setProperty(OracleConstants.CONN_PROP_SESSION_OS_USER, formattedUsername);
        }

        credentials.setUserName(userName);
        return super.initAuthentication(monitor, dataSource, credentials, configuration, connProperties);
    }

    @Override
    public void endAuthentication(@NotNull DBPDataSourceContainer dataSource, @NotNull DBPConnectionConfiguration configuration, @NotNull Properties connProperties) {
        super.endAuthentication(dataSource, configuration, connProperties);
    }

    @NotNull
    @Override
    public OracleDatabaseNativeCredentials createCredentials() {
        return new OracleDatabaseNativeCredentials();
    }
}
