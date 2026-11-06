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
package com.seizia.chromite.ext.cloudflare.d1;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.cloudflare.d1.model.CloudflareD1DataSource;
import com.seizia.chromite.ext.sqlite.model.SQLiteDataSourceProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Arrays;
import java.util.Set;

public class CloudflareD1DataSourceProvider extends SQLiteDataSourceProvider {
    private static final Set<String> AUTH_PROPERTIES = Set.of("accountId", "apiToken");

    public CloudflareD1DataSourceProvider() {
        super(CloudflareD1DataSource.class);
    }

    @NotNull
    @Override
    public DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor, @NotNull DBPDriver driver, @NotNull DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException {
        return Arrays.stream(super.getConnectionProperties(monitor, driver, dataSourceContainer, connectionInfo))
            .filter(property -> !AUTH_PROPERTIES.contains(property.getId()))
            .toArray(DBPPropertyDescriptor[]::new);
    }
}
