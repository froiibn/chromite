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
package com.seizia.chromite.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.connection.DBPAuthModelDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Data source provider
 */
public interface DBPDataSourceProvider<DATASOURCE extends DBPDataSource> extends DBPDataSourceURLProvider, DBPObject {
    long FEATURE_NONE        = 0;
    long FEATURE_CATALOGS    = 1;
    long FEATURE_SCHEMAS     = 2;
    long FEATURE_CATALOGS_ONLY = 4;

    /**
     * Initializes data source provider
     * @param platform application
     */
    void init(@NotNull DBPPlatform platform);

    /**
     * Supported features
     * @return features
     */
    long getFeatures();
    
    /**
     * Supported connection properties.
     *
     * @param monitor progress monitor
     * @param driver driver
     * @param connectionInfo connection information   @return property group which contains all supported properties
     * @throws DBException on any error
     */
    @NotNull
    DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException;

    /**
     * Class of datasource.
     * It is used for static examining of database structure
     */
    @NotNull
    Class<? extends DATASOURCE> getDataSourceClass();

    /**
     * Opens new data source
     * @param monitor progress monitor
     * @param container data source container
     * @return new data source object
     * @throws DBException on any error
     */
    @NotNull
    DATASOURCE openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container)
        throws DBException;

    @NotNull
    default DBPAuthModelDescriptor detectConnectionAuthModel(
        @NotNull DBPDriver driver,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) {
        DBPAuthModelDescriptor am = connectionInfo.getAuthModelDescriptor();
        DBPAuthModelDescriptor ram = am.getReplacedBy(driver);
        if (ram != null) {
            return ram;
        }
        return am;
    }

    default boolean providesDriverClasses(@NotNull DBPDriver driver) {
        return true;
    }

}
