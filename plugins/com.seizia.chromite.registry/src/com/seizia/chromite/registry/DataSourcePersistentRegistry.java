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
package com.seizia.chromite.registry;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceConfigurationStorage;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Data source persistent registry.
 */
public interface DataSourcePersistentRegistry extends DBPDataSourceRegistry {

    /**
     * Loads data sources from storages.
     */
    @Nullable
    DataSourceParseResults loadDataSources(
        @NotNull List<DBPDataSourceConfigurationStorage> storages,
        @NotNull DataSourceConfigurationManager manager,
        @Nullable Collection<String> dataSourceIds,
        boolean refresh,
        boolean purgeUntouched
    );

    /**
     * Saves data sources.
     */
    void saveDataSources();

    /**
     * Returns data source configuration manager.
     */
    @NotNull
    DataSourceConfigurationManager getConfigurationManager();

    /**
     * Saves data source configuration to the configuration manager.
     */
    void saveConfigurationToManager(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DataSourceConfigurationManager configurationManager,
        @Nullable Predicate<DBPDataSourceContainer> filter
    );

}
