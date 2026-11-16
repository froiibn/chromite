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
package com.seizia.chromite.ext.hsqldb.model;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericSynonym;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

/**
 * HSQLSynonym
 */
public class HSQLSynonym extends GenericSynonym {

    private static final Log log = Log.getLog(HSQLSynonym.class);

    private String targetSchemaName;
    private String targetObjectName;
    private String targetObjectType;

    protected HSQLSynonym(GenericStructContainer container, JDBCResultSet dbResult) {
        super(container, JDBCUtils.safeGetString(dbResult, "SYNONYM_NAME"), null);
        targetSchemaName = JDBCUtils.safeGetString(dbResult, "OBJECT_SCHEMA");
        targetObjectName = JDBCUtils.safeGetString(dbResult, "OBJECT_NAME");
        targetObjectType = JDBCUtils.safeGetString(dbResult, "OBJECT_TYPE");
    }

    @Property(viewable = true, order = 3)
    public String getTargetObjectType() {
        return targetObjectType;
    }

    @Property(viewable = true, order = 4)
    @Override
    public DBSObject getTargetObject(DBRProgressMonitor monitor) throws DBException {
        GenericSchema schema = getDataSource().getSchema(targetSchemaName);
        if (schema == null) {
            log.error("Schema '" + targetSchemaName + "' not found");
        } else {
            return schema.getTable(monitor, targetObjectName);
        }
        return null;
    }
}
