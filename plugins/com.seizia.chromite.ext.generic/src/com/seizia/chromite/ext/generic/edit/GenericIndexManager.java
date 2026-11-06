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
package com.seizia.chromite.ext.generic.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableIndex;
import com.seizia.chromite.ext.generic.model.GenericUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLIndexManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;

import java.util.Map;

/**
 * Generic index manager
 */
public class GenericIndexManager extends SQLIndexManager<GenericTableIndex, GenericTableBase> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, GenericTableIndex> getObjectsCache(GenericTableIndex object)
    {
        return object.getTable().getContainer().getIndexCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return (container instanceof GenericTable)
            && ((GenericTable) container).getDataSource().getInfo().supportsIndexes()
            && ((GenericTable) container).getDataSource().getSQLDialect().supportsIndexCreateAndDrop();
    }

    @Override
    public boolean canEditObject(@NotNull GenericTableIndex object) {
        return GenericUtils.canAlterTable(object);
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableIndex object) {
        return object.getDataSource().getSQLDialect().supportsIndexCreateAndDrop();
    }

    @Override
    protected GenericTableIndex createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    )
    {
        GenericTableBase tableBase = (GenericTableBase) container;
        return tableBase.getDataSource().getMetaModel().createIndexImpl(
            tableBase,
            true,
            null,
            0,
            null,
            DBSIndexType.OTHER,
            false);
    }

}
