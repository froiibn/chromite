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
package com.seizia.chromite.ext.mssql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerTable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableBase;
import com.seizia.chromite.ext.mssql.model.SQLServerTableForeignKey;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLForeignKeyManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;

import java.util.Map;

/**
 * SQL Server foreign key manager
 */
public class SQLServerForeignKeyManager extends SQLForeignKeyManager<SQLServerTableForeignKey, SQLServerTableBase> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, SQLServerTableForeignKey> getObjectsCache(SQLServerTableForeignKey object)
    {
        return object.getParentObject().getContainer().getForeignKeyCache();
    }

    @Override
    protected SQLServerTableForeignKey createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object table,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        SQLServerTableForeignKey foreignKey = new SQLServerTableForeignKey(
            (SQLServerTable) table,
            null,
            null,
            null,
            DBSForeignKeyModifyRule.NO_ACTION,
            DBSForeignKeyModifyRule.NO_ACTION,
            false);
        foreignKey.setName(getNewConstraintName(monitor, foreignKey));
        return foreignKey;

    }

}
