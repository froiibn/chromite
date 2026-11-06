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
package com.seizia.chromite.ext.db2.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.db2.model.DB2Index;
import com.seizia.chromite.ext.db2.model.DB2TableBase;
import com.seizia.chromite.ext.db2.model.dict.DB2UniqueRule;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLIndexManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;

import java.util.Map;

/**
 * DB2 Index manager
 *
 * @author Denis Forveille
 */
public class DB2IndexManager extends SQLIndexManager<DB2Index, DB2TableBase> {

    @Override
    public boolean canEditObject(@NotNull DB2Index object) {
        return false;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, DB2Index> getObjectsCache(DB2Index object) {
        return object.getParentObject().getSchema().getIndexCache();
    }

    @Override
    protected DB2Index createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        DB2TableBase table = (DB2TableBase) container;
        return new DB2Index(
            table,
            "INDEX",
            DBSIndexType.UNKNOWN,
            DB2UniqueRule.U);
    }

}
