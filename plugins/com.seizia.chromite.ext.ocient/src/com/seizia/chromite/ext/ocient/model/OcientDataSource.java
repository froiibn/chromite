/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.ocient.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.ocient.model.plan.OcientQueryPlaner;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class OcientDataSource extends GenericDataSource {

    public OcientDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container) throws DBException {
        super(monitor, container, new OcientMetaModel(), new OcientSQLDialect());
    }

    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        if (adapter == DBCQueryPlanner.class) {
            return adapter.cast(new OcientQueryPlaner(this));
        }
        return super.getAdapter(adapter);
    }

    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

}
