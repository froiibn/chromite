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
package com.seizia.chromite.ext.oracle.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleObjectStatus;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ext.oracle.model.OracleTableForeignKey;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLForeignKeyManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;

import java.util.Map;

/**
 * Oracle foreign key manager
 */
public class OracleForeignKeyManager extends SQLForeignKeyManager<OracleTableForeignKey, OracleTableBase> {


    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleTableForeignKey> getObjectsCache(OracleTableForeignKey object)
    {
        return object.getParentObject().getSchema().getForeignKeyCache();
    }

    @Override
    protected OracleTableForeignKey createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        OracleTableBase table = (OracleTableBase) container;

        return new OracleTableForeignKey(
            table,
            "",
            OracleObjectStatus.ENABLED,
            null,
            DBSForeignKeyModifyRule.NO_ACTION);
    }

}
