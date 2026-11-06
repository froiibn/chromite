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
package com.seizia.chromite.ext.clickhouse;

import org.jkiss.code.DynamicCall;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.clickhouse.model.ClickhouseDataSource;
import com.seizia.chromite.ext.generic.GenericDataSourceProvider;
import com.seizia.chromite.ext.generic.GenericMetaModelRegistry;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class ClickhouseDataSourceProvider extends GenericDataSourceProvider<ClickhouseDataSource> {

    @DynamicCall
    public ClickhouseDataSourceProvider() {
        super(ClickhouseDataSource.class);
    }

    protected ClickhouseDataSourceProvider(@NotNull Class<?extends ClickhouseDataSource> dsClass) {
        super(dsClass);
    }

    @NotNull
    @Override
    public ClickhouseDataSource openDataSource(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBPDataSourceContainer container)
            throws DBException {
        GenericMetaModel metaModel = GenericMetaModelRegistry.getInstance().getMetaModel(container);
        return new ClickhouseDataSource(monitor, container, metaModel);
    }
}
