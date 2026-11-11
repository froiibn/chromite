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
package com.seizia.chromite.ext.generic;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModelDescriptor;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DatabaseURL;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSourceProvider;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.exec.SQLActualPlanSupport;
import org.jkiss.utils.CommonUtils;

/**
 * Generic JDBC data source provider: the driver's meta model makes the data source.
 *
 * 변경 이력:
 * - 2026-11-12 연결 전에 실제 계획 여부를 답함({@link SQLActualPlanSupport}): 데이터 소스를 만드는 메타 모델이 답한다(Altibase).
 *   일반 JDBC 연결에 Altibase 드라이버를 써도 Altibase 메타 모델이 데이터 소스를 만들기 때문이다(앱 변경 #70)
 */
public abstract class GenericDataSourceProvider<DATASOURCE extends GenericDataSource> extends JDBCDataSourceProvider<DATASOURCE>
    implements SQLActualPlanSupport {

    protected GenericDataSourceProvider(@NotNull Class<? extends DATASOURCE> dsClass) {
        super(dsClass);
    }

    @Override
    public long getFeatures() {
        return FEATURE_CATALOGS | FEATURE_SCHEMAS;
    }

    @NotNull
    @Override
    public String getConnectionURL(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration connectionInfo) throws DBException {
        return DatabaseURL.generateUrlByTemplate(driver, connectionInfo);
    }

    @NotNull
    @Override
    public DATASOURCE openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container)
        throws DBException {
        GenericMetaModel metaModelInstance = GenericMetaModelRegistry.getInstance().getMetaModel(container);
        return getDataSourceClass().cast(metaModelInstance.createDataSourceImpl(monitor, container));
    }

    /**
     * The meta model that {@link #openDataSource} would use answers; finding it reads only the driver.
     */
    @Override
    public boolean isActualPlanSupported(@NotNull DBPDataSourceContainer container) {
        try {
            return GenericMetaModelRegistry.getInstance().getMetaModel(container) instanceof SQLActualPlanSupport support
                && support.isActualPlanSupported(container);
        } catch (DBException e) {
            log.debug("Can't find the meta model of " + container.getName(), e);
            return false;
        }
    }

    protected GenericMetaModelDescriptor getStandardMetaModel() {
        return GenericMetaModelRegistry.getInstance().getStandardMetaModel();
    }

    @NotNull
    @Override
    public DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException {
        DBPPropertyDescriptor[] connectionProperties = super.getConnectionProperties(monitor, driver, dataSourceContainer, connectionInfo);
        if (connectionProperties == null || connectionProperties.length == 0) {
            // Try to get list of supported properties from custom driver config
            String driverParametersString = CommonUtils.toString(driver.getDriverParameter(GenericConstants.PARAM_DRIVER_PROPERTIES));
            if (!driverParametersString.isEmpty()) {
                String[] propList = driverParametersString.split(",");
                connectionProperties = new DBPPropertyDescriptor[propList.length];
                for (int i = 0; i < propList.length; i++) {
                    String propName = propList[i].trim();
                    connectionProperties[i] = new PropertyDescriptor(
                        ModelMessages.model_jdbc_driver_properties,
                        propName,
                        propName,
                        null,
                        String.class,
                        false,
                        null,
                        null,
                        true);
                }
            }
        }
        return connectionProperties;
    }
}
