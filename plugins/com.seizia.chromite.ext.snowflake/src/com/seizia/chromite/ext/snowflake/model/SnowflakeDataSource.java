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
package com.seizia.chromite.ext.snowflake.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.snowflake.SnowflakeConstants;
import com.seizia.chromite.ext.snowflake.SnowflakeUtils;
import com.seizia.chromite.ext.snowflake.model.auth.SnowflakeAuthModelSnowflake;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class SnowflakeDataSource extends GenericDataSource {

    private static final String APPLICATION_PROPERTY = "application";

    public SnowflakeDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, SnowflakeMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel, new SnowflakeSQLDialect());
    }
    
    public SnowflakeDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull SnowflakeMetaModel metaModel,
        @NotNull SnowflakeSQLDialect dialect
    ) throws DBException {
        super(monitor, container, metaModel, dialect);
    }

    @NotNull
    @Override
    protected Map<String, String> getInternalConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) {
        return getInternalConnectionProperties(connectionInfo);
    }

    @NotNull
    static Map<String, String> getInternalConnectionProperties(@NotNull DBPConnectionConfiguration connectionInfo) {
        Map<String, String> props = new HashMap<>();

        String warehouse = SnowflakeUtils.getWarehouse(connectionInfo);
        if (!CommonUtils.isEmpty(warehouse)) {
            props.put(SnowflakeConstants.PROP_WAREHOUSE, warehouse);
        }

        // Backward compatibility - use legacy provider property
        // Newer versions use auth model
        String authProp = connectionInfo.getProviderProperty(SnowflakeConstants.PROP_AUTHENTICATOR_LEGACY);
        if (!CommonUtils.isEmpty(authProp)) {
            props.put("authenticator", authProp);
        }

        return props;
    }

    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

    @Override
    protected boolean isUserPasswordAuthentication(@NotNull DBPConnectionConfiguration connectionInfo) {
        boolean passwordAuthModel = super.isUserPasswordAuthentication(connectionInfo)
            || SnowflakeAuthModelSnowflake.ID.equals(connectionInfo.getAuthModelId());
        return passwordAuthModel && hasPasswordAuthenticator(connectionInfo);
    }

    protected boolean hasPasswordAuthenticator(@NotNull DBPConnectionConfiguration connectionInfo) {
        return isPasswordAuthenticator(connectionInfo.getAuthProperty(SnowflakeConstants.PROP_AUTHENTICATOR))
            && isPasswordAuthenticator(connectionInfo.getProviderProperty(SnowflakeConstants.PROP_AUTHENTICATOR_LEGACY));
    }

    @Override
    protected void prepareUserPasswordValidationConfiguration(@NotNull DBPConnectionConfiguration connectionInfo) {
        // Keep Snowflake username/password model settings, including MFA configuration.
    }

    private static boolean isPasswordAuthenticator(@Nullable String authenticator) {
        return CommonUtils.isEmpty(authenticator)
            || SnowflakeConstants.AUTHENTICATOR_SNOWFLAKE.equalsIgnoreCase(authenticator)
            || SnowflakeConstants.AUTHENTICATOR_USERNAME_PASSWORD_MFA.equalsIgnoreCase(authenticator);
    }

    @NotNull
    @Override
    protected Properties getAllConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBCException {
        Properties props = super.getAllConnectionProperties(monitor, context, purpose, connectionInfo);
        final String clientAppName = DBUtils.getClientApplicationName(container, context, null, false);
        final String appName = "DBeaver_" + clientAppName.replace(" ", "");
        props.put(APPLICATION_PROPERTY, appName);
        return props;
    }

    @NotNull
    @Override
    protected JDBCExecutionContext createExecutionContext(@NotNull JDBCRemoteInstance instance, @NotNull String type) {
        return new SnowflakeExecutionContext(instance, type);
    }

    @Override
    protected void initializeContextState(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext context,
        @Nullable JDBCExecutionContext initFrom
    ) throws DBException {
        SnowflakeExecutionContext executionContext = (SnowflakeExecutionContext) context;
        String warehouse = SnowflakeUtils.getWarehouse(container.getActualConnectionConfiguration());
        if (!CommonUtils.isEmpty(warehouse)) {
            executionContext.setActiveWarehouse(monitor, warehouse);
        }
        if (initFrom == null) {
            executionContext.refreshDefaults(monitor, true);
            return;
        }
        SnowflakeExecutionContext executionMetaContext = (SnowflakeExecutionContext) initFrom;
        GenericCatalog defaultCatalog = executionMetaContext.getDefaultCatalog();
        GenericSchema defaultSchema = executionMetaContext.getDefaultSchema();
        if (defaultCatalog != null) {
            executionContext.setDefaultCatalog(monitor, defaultCatalog, defaultSchema, true);
        } else if (defaultSchema != null) {
            executionContext.setDefaultSchema(monitor, defaultSchema, true);
        }
    }
}
