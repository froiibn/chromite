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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTableColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreTableConstraint
 */
public class PostgreTableConstraint extends PostgreTableConstraintBase<PostgreTableConstraintColumn> {
    private static final Log log = Log.getLog(PostgreTableConstraint.class);

    private String source;
    private final List<PostgreTableConstraintColumn> columns = new ArrayList<>();

    public PostgreTableConstraint(PostgreTableBase table, String name, DBSEntityConstraintType constraintType, JDBCResultSet resultSet) throws DBException {
        super(table, name, constraintType, resultSet);
        String sourceCopy = JDBCUtils.safeGetString(resultSet, "consrc_copy");
        if (sourceCopy == null && getDataSource().getServerType().supportsPGConstraintExpressionColumn()) {
            if (!getDataSource().isServerVersionAtLeast(12, 0)) {
                this.source = JDBCUtils.safeGetString(resultSet, "consrc");
            } else {
                this.source = null;
            }
        } else {
            this.source = sourceCopy;
        }
    }

    public PostgreTableConstraint(PostgreTableBase table, String constraintName, DBSEntityConstraintType constraintType) {
        super(table, constraintName, constraintType);
    }

    public PostgreTableConstraint(DBRProgressMonitor monitor, PostgreTableReal owner, PostgreTableConstraint srcConstr) throws DBException {
        super(monitor, owner, srcConstr);
        this.source = srcConstr.source;
        for (PostgreTableConstraintColumn srcCol : srcConstr.columns) {
            PostgreTableColumn ownAttr = owner.getAttribute(monitor, srcCol.getAttribute().getName());
            if (ownAttr != null) {
                this.columns.add(new PostgreTableConstraintColumn(this, ownAttr, this.columns.size()));
            }
        }
    }

    @Override
    void cacheAttributes(DBRProgressMonitor monitor, List<? extends PostgreTableConstraintColumn> children, boolean secondPass) {
        if (secondPass) {
            return;
        }
        columns.clear();
        columns.addAll(children);
    }

    @Nullable
    @Override
    public List<PostgreTableConstraintColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor)
    {
        return columns;
    }

    @Override
    public void addAttributeReference(DBSTableColumn column) throws DBException {
        columns.add(new PostgreTableConstraintColumn(this, (PostgreAttribute<?>) column, columns.size()));
    }

    public void addColumn(PostgreTableConstraintColumn column) {
        this.columns.add(column);
    }

    public List<PostgreTableConstraintColumn> getColumns() {
        return columns;
    }

    @Override
    public void setAttributeReferences(List<PostgreTableConstraintColumn> columns) throws DBException {
        this.columns.clear();
        this.columns.addAll(columns);
    }

    @Property(viewable = true, editable = true, order = 10)
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

}
