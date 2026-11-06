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
package com.seizia.chromite.ext.hive.model.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.edit.GenericIndexManager;
import com.seizia.chromite.ext.generic.model.GenericTableIndex;
import com.seizia.chromite.ext.generic.model.GenericTableIndexColumn;
import com.seizia.chromite.ext.hive.model.HiveIndex;
import com.seizia.chromite.ext.hive.model.HiveTable;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;

import java.util.List;
import java.util.Map;

public class HiveIndexManager extends GenericIndexManager {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableIndex object) {
        return true;
    }

    @Override
    protected GenericTableIndex createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        return new HiveIndex((HiveTable) container, "NewIndex",false, "", "Compact", null);
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) {
        HiveIndex tableIndex = (HiveIndex) command.getObject();
        DBSIndexType indexType = tableIndex.getIndexType();
        String indexName = tableIndex.getName();
        String tableName = tableIndex.getTable().getName();
        String hiveIndexType;
        List<GenericTableIndexColumn> indexColumns = tableIndex.getAttributeReferences(monitor);
        if (indexType.getId().equals("COMPACT")) {
            hiveIndexType = "\'COMPACT\'";
        } else {
            hiveIndexType = "\'BITMAP\'";
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE INDEX ").append(indexName).append(" ON TABLE ").append(tableName).append(" (");
        if (indexColumns != null) {
            for (int i = 0; i < indexColumns.size(); i++) {
                if (i == 0) {
                    ddl.append(DBUtils.getQuotedIdentifier(indexColumns.get(i)));
                } else {
                    ddl.append(", ").append(DBUtils.getQuotedIdentifier(indexColumns.get(i)));
                }
            }
        }
        ddl.append(") AS ").append(hiveIndexType).append(" WITH DEFERRED REBUILD");
        actions.add(new SQLDatabasePersistAction("Create table index", ddl.toString()));
        actions.add(new SQLDatabasePersistAction("ALTER INDEX " + indexName + " ON " + tableName + " REBUILD"));
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        GenericTableIndex tableIndex = command.getObject();
        actions.add(new SQLDatabasePersistAction("Drop index table", "DROP INDEX " + tableIndex.getName() +
                            " ON " + tableIndex.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL))); //$NON-NLS-2$
    }
}
