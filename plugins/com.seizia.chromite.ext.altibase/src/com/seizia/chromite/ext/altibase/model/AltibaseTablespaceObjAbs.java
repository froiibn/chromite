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

package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public abstract class AltibaseTablespaceObjAbs extends AltibaseObject<AltibaseTablespace> {

    protected String schemaName;
    protected String objName;
    protected String partnName;

    protected GenericTable targetTable;

    AltibaseTablespaceObjAbs(AltibaseTablespace parent, JDBCResultSet resultSet) {
        super(parent, DBUtils.getFullyQualifiedName(
                parent.getDataSource(),
                JDBCUtils.safeGetString(resultSet, "USER_NAME"), 
                JDBCUtils.safeGetString(resultSet, "OBJ_NAME"), 
                JDBCUtils.safeGetString(resultSet, "PARTITION_NAME")),
                true);

        schemaName = JDBCUtils.safeGetString(resultSet, "USER_NAME");
        objName = JDBCUtils.safeGetString(resultSet, "OBJ_NAME");
        partnName = JDBCUtils.safeGetString(resultSet, "PARTITION_NAME");
    }

    @NotNull
    @Override
    @Property(viewable = false, order = 1, hidden = true)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public AltibaseSchema getSchema() {
        return (AltibaseSchema) getDataSource().getSchema(schemaName);
    }

    @Property(viewable = true, order = 10)
    public String getPartnName() {
        return partnName;
    }

    protected GenericTable getTargetTable(DBRProgressMonitor monitor, String tableSchema, String tableName) throws DBException {
        if (targetTable == null) {
            AltibaseSchema schema = (AltibaseSchema) getDataSource().getSchema(tableSchema);
            if (schema != null) {
                targetTable = (GenericTable) schema.getTable(monitor, tableName);
            }
        }
        return targetTable;
    }
}