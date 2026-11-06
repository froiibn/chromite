/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNative;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.StandardConstants;

import java.util.Properties;

/**
 * Oracle OS auth model.
 */
public class OracleAuthOS extends AuthModelDatabaseNative<OracleAuthOSCredentials> {

    public static final String ID = "oracle_os";

    @Override
    public boolean isCredentialsPromptRequired() {
        return false;
    }

    @NotNull
    @Override
    public OracleAuthOSCredentials createCredentials() {
        return new OracleAuthOSCredentials();
    }

    @NotNull
    @Override
    public OracleAuthOSCredentials loadCredentials(@NotNull DBPDataSourceContainer dataSource, @NotNull DBPConnectionConfiguration configuration) {
        OracleAuthOSCredentials credentials = super.loadCredentials(dataSource, configuration);
        credentials.setUserName(null);
        credentials.setUserPassword(null);
        return credentials;
    }

    @Override
    public Object initAuthentication(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull OracleAuthOSCredentials credentials,
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull Properties connProperties
    ) throws DBException {
        connProperties.put(OracleConstants.CONN_PROP_SESSION_OS_USER, System.getProperty(StandardConstants.ENV_USER_NAME));
        return super.initAuthentication(monitor, dataSource, credentials, configuration, connProperties);
    }

}
