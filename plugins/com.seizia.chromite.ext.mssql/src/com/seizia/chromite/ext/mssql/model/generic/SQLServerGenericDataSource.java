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
package com.seizia.chromite.ext.mssql.model.generic;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDialectMssql;
import com.seizia.chromite.ext.mssql.model.SQLServerDialectSybase;
import com.seizia.chromite.ext.mssql.model.ServerType;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

public class SQLServerGenericDataSource extends GenericDataSource {

    private static final Log log = Log.getLog(SQLServerGenericDataSource.class);

    private static final String PROP_ENCRYPT_PASS = "ENCRYPT_PASSWORD";
    private boolean hasMetaDataProcedureView = false;

    public SQLServerGenericDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container)
        throws DBException
    {
        this(
            monitor,
            container,
            new SQLServerMetaModel(SQLServerUtils.isDriverSqlServer(container.getDriver()))
        );
    }

    public SQLServerGenericDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, SQLServerMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel,
            metaModel.getServerType() == ServerType.SQL_SERVER ? new SQLServerDialectMssql() : new SQLServerDialectSybase());
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
        Map<String, String> connectionsProps = new HashMap<>();
        if (!getContainer().getPreferenceStore().getBoolean(ModelPreferences.META_CLIENT_NAME_DISABLE)) {
            // App name
            connectionsProps.put(
                SQLServerUtils.isDriverJtds(driver) ? SQLServerConstants.APPNAME_CLIENT_PROPERTY : SQLServerConstants.APPLICATION_NAME_CLIENT_PROPERTY,
                CommonUtils.truncateString(DBUtils.getClientApplicationName(getContainer(), context, purpose), 64));
        }
        if (CommonUtils.toBoolean(connectionInfo.getProviderProperty(SQLServerConstants.PROP_ENCRYPT_PASSWORD))) {
            try {
                DBPPropertyDescriptor[] properties = driver.getDataSourceProvider()
                    .getConnectionProperties(monitor, driver, container, connectionInfo);
                for (DBPPropertyDescriptor descriptor : properties) {
                    if (descriptor.getId().equals(PROP_ENCRYPT_PASS) && descriptor instanceof PropertyDescriptor) {
                        connectionInfo.setProperty(PROP_ENCRYPT_PASS, "true"); // To apply changes
                        context.getDataSource().getContainer().getConnectionConfiguration().setProperty(PROP_ENCRYPT_PASS, "true"); // To see changes in the Driver Properties tab
                        break;
                    }
                }
            } catch (DBException e) {
                log.error("Can't read driver properties", e);
            }
        }
        return connectionsProps;
    }

    @Override
    public Object getDataSourceFeature(String featureId) {
        switch (featureId) {
            case DBPDataSource.FEATURE_LIMIT_AFFECTS_DML:
                return true;
            case DBPDataSource.FEATURE_MAX_STRING_LENGTH:
                return 8000;
        }
        return super.getDataSourceFeature(featureId);
    }

    @NotNull
    @Override
    public DBPDataKind resolveDataKind(@NotNull String typeName, int valueType) {
        if (valueType == Types.VARCHAR) {
            // Workaround for jTDS driver (#3555)
            switch (typeName) {
                case SQLServerConstants.TYPE_DATETIME:
                case SQLServerConstants.TYPE_DATETIME2:
                case SQLServerConstants.TYPE_SMALLDATETIME:
                    return DBPDataKind.DATETIME;
                case SQLServerConstants.TYPE_DATETIMEOFFSET:
                    return DBPDataKind.DATETIME;
            }
        }
        return super.resolveDataKind(typeName, valueType);
    }

    //////////////////////////////////////////////////////////
    // Databases

    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        super.initialize(monitor);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read server information")) {
            JDBCUtils.executeStatement(session, "SELECT TOP 1 1 FROM SYS.SYSPROCEDURE WHERE 1 <> 1");
            hasMetaDataProcedureView = true;
        } catch (SQLException e) {
            hasMetaDataProcedureView = false;
        }
    }

    /**
     * Is meta info SYS.SYSPROCEDURE available for the data source
     */
    public boolean hasMetaDataProcedureView() {
        return hasMetaDataProcedureView;
    }

}
