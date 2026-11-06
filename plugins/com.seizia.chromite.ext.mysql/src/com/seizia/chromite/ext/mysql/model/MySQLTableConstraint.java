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
package com.seizia.chromite.ext.mysql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.rdb.DBSTableColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * GenericPrimaryKey
 */
public class MySQLTableConstraint extends MySQLTableConstraintBase {
    private final List<MySQLTableConstraintColumn> columns = new ArrayList<>();
    private String checkClause;

    public MySQLTableConstraint(MySQLTable table, String name, String remarks, DBSEntityConstraintType constraintType, boolean persisted) {
        super(table, name, remarks, constraintType, persisted);
    }

    public MySQLTableConstraint(MySQLTable table, String name, String description, DBSEntityConstraintType constraintType, boolean persisted, JDBCResultSet resultSet) {
        super(table, name, description, constraintType, persisted, resultSet);
        this.checkClause = JDBCUtils.safeGetString(resultSet, MySQLConstants.COL_CHECK_CLAUSE);
    }

    // Copy constructor
    protected MySQLTableConstraint(DBRProgressMonitor monitor, MySQLTable table, DBSEntityConstraint source) throws DBException {
        super(table, source, false);
        if (source instanceof DBSEntityReferrer) {
            List<? extends DBSEntityAttributeRef> columns = ((DBSEntityReferrer) source).getAttributeReferences(monitor);
            if (columns != null) {
                this.columns.clear();
                for (DBSEntityAttributeRef col : columns) {
                    if (col.getAttribute() != null) {
                        MySQLTableColumn ownCol = table.getAttribute(monitor, col.getAttribute().getName());
                        this.columns.add(new MySQLTableConstraintColumn(this, ownCol, col.getAttribute().getOrdinalPosition()));
                    }
                }
            }
        }
    }

    @Nullable
    @Override
    public List<MySQLTableConstraintColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor) {
        return columns;
    }

    @Override
    public void addAttributeReference(DBSTableColumn column) throws DBException {
        this.columns.add(new MySQLTableConstraintColumn(this, (MySQLTableColumn) column, columns.size()));
    }

    public void setAttributeReferences(List<MySQLTableConstraintColumn> columns) {
        this.columns.clear();
        this.columns.addAll(columns);
    }

    public void setCheckClause(String clause) {
        this.checkClause = clause;
    }

    @Property(viewable = true, editable = true, order = 4, visibleIf = MySQLCheckConstraintsValueValidator.class)
    public String getCheckClause() {
        return checkClause;
    }

    public void addColumn(MySQLTableConstraintColumn column) {
        this.columns.add(column);
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(),
            getTable().getContainer(),
            getTable(),
            this);
    }

    @NotNull
    @Override
    public MySQLDataSource getDataSource() {
        return getTable().getDataSource();
    }

    public static class MySQLCheckConstraintsValueValidator implements IPropertyValueValidator<MySQLTableConstraint, Object> {

        @Override
        public boolean isValidValue(@NotNull MySQLTableConstraint object, @Nullable Object value) throws IllegalArgumentException {
            return object.getDataSource().supportsCheckConstraints();
        }
    }

}
