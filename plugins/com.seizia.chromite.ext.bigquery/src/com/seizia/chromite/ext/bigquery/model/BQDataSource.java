/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.bigquery.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public class BQDataSource extends GenericDataSource {

    public BQDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull GenericMetaModel metaModel
    ) throws DBException {
        super(monitor, container, metaModel, new BQSQLDialect());
    }

    @NotNull
    @Override
    protected JDBCExecutionContext createExecutionContext(@NotNull JDBCRemoteInstance instance, @NotNull String type) throws DBCException {
        return new BQExecutionContext(instance, type);
    }

    @Override
    protected String getConnectionURL(@NotNull DBPConnectionConfiguration connectionInfo) throws DBException {
        String connectionURL = super.getConnectionURL(connectionInfo);
        if (CommonUtils.isNotEmpty(connectionURL) &&
            (connectionURL.contains("OAuthPvtKeyPath={server};") || connectionURL.contains("OAuthServiceAcctEmail=;"))
        ) {
            // Backward compatibility. We do not want to use this incorrect pattern as a URL. Better to create a new URL.
            DBPDriver driver = getContainer().getDriver();
            return driver.getDataSourceProvider().getConnectionURL(driver, connectionInfo);
        }
        return connectionURL;
    }

    @NotNull
    @Override
    protected Map<String, String> getInternalConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBCException {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(BQConstants.DRIVER_PROP_PROJECT_ID, connectionInfo.getDatabaseName());
        if (connectionInfo.getUserName() != null) {
            props.put(BQConstants.DRIVER_PROP_ACCOUNT, connectionInfo.getUserName());
        } else {
            props.put(BQConstants.DRIVER_PROP_ACCOUNT, "");
        }
        String additionalProjects = connectionInfo.getProviderProperty(BQConstants.DRIVER_PROP_ADDITIONAL_PROJECTS);
        if (CommonUtils.isNotEmpty(additionalProjects)) {
            props.put(BQConstants.DRIVER_PROP_ADDITIONAL_PROJECTS, additionalProjects);
        }

        return props;
    }

    @NotNull
    public DBPDataKind resolveDataKind(@NotNull String typeName, int valueType) {
        if (typeName.equals(BQConstants.DATA_TYPE_STRUCT)) {
            return DBPDataKind.STRUCT;
        }
        return super.resolveDataKind(typeName, valueType);
    }

    @NotNull
    @Override
    public String getDefaultDataTypeName(@NotNull DBPDataKind dataKind) {
        switch (dataKind) {
            case STRING:
                return "STRING";
            default:
                return super.getDefaultDataTypeName(dataKind);
        }
    }

    protected boolean isSessionModeEnabled() {
        return false;
    }
}
