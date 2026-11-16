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
package com.seizia.chromite.tools.transfer.stream.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * DataSourceProviderLocal
 */
public class StreamDataSourceProvider implements DBPDataSourceProvider<DBPDataSource> {
    public static final String PROVIDER_ID = "stream";

    @Override
    public void init(@NotNull DBPPlatform platform) {

    }

    @Override
    public long getFeatures() {
        return FEATURE_NONE;
    }

    @NotNull
    @Override
    public DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) {
        return new DBPPropertyDescriptor[0];
    }

    @NotNull
    @Override
    public Class<? extends DBPDataSource> getDataSourceClass() {
        return DBPDataSource.class;
    }

    @NotNull
    @Override
    public DBPDataSource openDataSource(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        throw new DBCFeatureNotSupportedException();
    }

    @NotNull
    @Override
    public String getConnectionURL(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration connectionInfo) {
        return "";
    }
}
