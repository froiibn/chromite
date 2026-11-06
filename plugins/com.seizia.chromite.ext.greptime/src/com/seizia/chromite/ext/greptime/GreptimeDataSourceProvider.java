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
package com.seizia.chromite.ext.greptime;

import org.jkiss.code.DynamicCall;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.GenericDataSourceProvider;
import com.seizia.chromite.ext.greptime.model.GreptimeDataSource;
import com.seizia.chromite.ext.greptime.model.GreptimeMetaModel;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class GreptimeDataSourceProvider extends GenericDataSourceProvider<GreptimeDataSource> {

    private static final GreptimeMetaModel META_MODEL = new GreptimeMetaModel();

    @DynamicCall
    public GreptimeDataSourceProvider() {
        super(GreptimeDataSource.class);
    }

    @NotNull
    @Override
    public GreptimeDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new GreptimeDataSource(monitor, container, META_MODEL);
    }
}
