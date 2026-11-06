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
package com.seizia.chromite.ext.tidblake.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Properties;

/**
 * TiDBLake datasource
 */
public class TiDBLakeDataSource extends GenericDataSource {

    public TiDBLakeDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, TiDBLakeMetaModel metaModel)
            throws DBException {
        super(monitor, container, metaModel, new TiDBLakeSQLDialect());
    }


    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

    @NotNull
    @Override
    protected Properties getAllConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBCException {
        Properties properties = super.getAllConnectionProperties(monitor, context, purpose, connectionInfo);

        final DBWHandlerConfiguration sslConfig = getContainer().getActualConnectionConfiguration().getHandler("tidblake-ssl");

        if (sslConfig != null && sslConfig.isEnabled()) {
            try {
                initSSL(monitor, properties);
            } catch (Exception e) {
                throw new DBCException("Error configuring SSL certificates", e);
            }
        }
        return properties;
    }

    private void initSSL(DBRProgressMonitor monitor, Properties properties) throws DBException {
        monitor.subTask("Initialising SSL configuration");
        properties.put(TiDBLakeConstants.SSL_PARAM, "enable");
    }
}
