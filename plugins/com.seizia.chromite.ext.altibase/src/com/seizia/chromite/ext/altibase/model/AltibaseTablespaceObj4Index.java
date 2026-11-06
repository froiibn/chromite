/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class AltibaseTablespaceObj4Index extends AltibaseTablespaceObjAbs {

    private String tableSchema;
    private String tableName;

    private AltibaseTableIndex targetIndex;

    AltibaseTablespaceObj4Index(AltibaseTablespace parent, JDBCResultSet resultSet) {
        super(parent, resultSet);
        tableSchema = JDBCUtils.safeGetString(resultSet, "table_schema");
        tableName = JDBCUtils.safeGetString(resultSet, "table_name");
    }

    @Property(viewable = true, linkPossible = true, order = 3)
    public AltibaseTableIndex getObject(DBRProgressMonitor monitor) throws DBException {
        return getTargetIndex(monitor);
    }

    @Property(viewable = true, linkPossible = true, order = 20)
    public AltibaseSchema getTableSchema() {
        return (AltibaseSchema) getDataSource().getSchema(tableSchema);
    } 

    @Property(viewable = true, linkPossible = true, order = 21)
    public GenericTable getTable(DBRProgressMonitor monitor) throws DBException {
        return getTargetTable(monitor, tableSchema, tableName);
    }

    private AltibaseTableIndex getTargetIndex(DBRProgressMonitor monitor) throws DBException {
        if (targetIndex == null) {
            AltibaseSchema schema = (AltibaseSchema) getDataSource().getSchema(schemaName);
            if (schema != null) {
                targetIndex = (AltibaseTableIndex) schema.getIndex(monitor, objName);
            }
        }
        return targetIndex;
    }
}