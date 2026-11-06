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
package com.seizia.chromite.ext.databend;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.databend.model.DatabendDataSource;
import com.seizia.chromite.ext.databend.model.DatabendMetaModel;
import com.seizia.chromite.ext.generic.GenericDataSourceProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPInformationProvider;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;


public class DatabendDataSourceProvider extends GenericDataSourceProvider<DatabendDataSource> implements DBPInformationProvider {

    public DatabendDataSourceProvider() {
        super(DatabendDataSource.class);
    }

    @NotNull
    @Override
    public DatabendDataSource openDataSource(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBPDataSourceContainer container)
            throws DBException {
        return new DatabendDataSource(monitor, container, new DatabendMetaModel());
    }

    @Nullable
    @Override
    public String getObjectInformation(@NotNull DBPObject object, @NotNull String infoType) {
        if (object instanceof DBPDataSourceContainer && infoType.equals(INFO_TARGET_ADDRESS)) {
            return ((DBPDataSourceContainer) object).getConnectionConfiguration().getServerName();
        }
        return null;
    }

}
