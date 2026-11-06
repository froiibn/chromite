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
package com.seizia.chromite.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.struct.AbstractTableConstraint;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTableCheckConstraint;
import com.seizia.chromite.model.struct.rdb.DBSTableConstraintColumn;

import java.util.List;
import java.util.Map;

/**
 * SQLServerTableCheckConstraint
 */
public class SQLServerTableCheckConstraint extends AbstractTableConstraint<SQLServerTable, DBSTableConstraintColumn>
    implements SQLServerObject, DBPScriptObject, DBSTableCheckConstraint
{
    private boolean disabled;
    private String definition;
    private long objectId;

    public SQLServerTableCheckConstraint(SQLServerTable table, JDBCResultSet dbResult) {
        super(table, JDBCUtils.safeGetString(dbResult, "name"), null, DBSEntityConstraintType.CHECK);
        this.objectId = JDBCUtils.safeGetLong(dbResult, "object_id");
        this.disabled = JDBCUtils.safeGetInt(dbResult, "is_disabled") != 0;
        this.definition = JDBCUtils.safeGetString(dbResult, "definition");
    }

    public SQLServerTableCheckConstraint(SQLServerTable table) {
        super(table, null, null, DBSEntityConstraintType.CHECK);
        this.objectId = -1;
        this.disabled = false;
        this.definition = null;
    }

    @Property(viewable = false, editable = true, order = 10)
    public boolean isDisabled() {
        return disabled;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return getTable().getDataSource();
    }

    @Property(viewable = false, editable = true, order = 80)
    @Override
    public long getObjectId() {
        return objectId;
    }

    @Override
    @Property(viewable = true, editable = true, length = PropertyLength.MULTILINE, order = 20)
    public String getCheckConstraintDefinition() {
        return definition;
    }

    @Override
    public void setCheckConstraintDefinition(String expression) {
        this.definition = expression;
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        return getCheckConstraintDefinition();
    }

    @Nullable
    @Override
    public List<DBSTableConstraintColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor) throws DBException {
        return null;
    }

    @Override
    public void setAttributeReferences(List<DBSTableConstraintColumn> dbsTableConstraintColumns) throws DBException {

    }

    @Nullable
    @Override
    public SQLServerDatabase getDatabase() {
        return getTable().getDatabase();
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), getTable(), this);
    }
}
