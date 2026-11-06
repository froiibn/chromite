/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package com.seizia.chromite.ext.mssql.auth;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNativeCredentials;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Properties;

/**
 * SQL Server MSI auth model.
 */
public class SQLServerAuthModelMSI extends SQLServerAuthModelAbstract {

    public static final String ID = "sqlserver_msi";

    @NotNull
    @Override
    public SQLServerAuthModelMSICredentials createCredentials() {
        return new SQLServerAuthModelMSICredentials();
    }

    @Override
    public boolean isUserPasswordApplicable() {
        return false;
    }

    @Override
    public Object initAuthentication(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull AuthModelDatabaseNativeCredentials credentials,
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull Properties connProperties
    ) throws DBException {
        connProperties.put(SQLServerConstants.PROP_CONNECTION_AUTHENTICATION, SQLServerConstants.AUTH_ACTIVE_DIRECTORY_MSI);
        if (!CommonUtils.isEmpty(configuration.getUserName())) {
            connProperties.put("msiClientId", configuration.getUserName());
            connProperties.remove(DBConstants.DATA_SOURCE_PROPERTY_USER);
            connProperties.remove(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD);
        }
        return credentials;
    }

    @Override
    public void endAuthentication(
        @NotNull DBPDataSourceContainer dataSource,
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull Properties connProperties
    ) {
        super.endAuthentication(dataSource, configuration, connProperties);
    }

}
