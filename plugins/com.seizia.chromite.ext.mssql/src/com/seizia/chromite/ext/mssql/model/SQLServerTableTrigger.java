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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.sql.ResultSet;

/**
 * SQLServerTableTrigger
 */
public class SQLServerTableTrigger extends SQLServerTriggerBase<SQLServerTableBase> {

    private SQLServerTableBase table;

    public SQLServerTableTrigger(@NotNull SQLServerTableBase table, ResultSet dbResult) {
        super(table, dbResult);
        this.table = table;
    }

    public SQLServerTableTrigger(@NotNull SQLServerTableBase table, String name) {
        super(table, name);
        this.table = table;
    }

    @Override
    @Property(viewable = true, order = 4)
    public SQLServerTableBase getTable() {
        return table;
    }

    public SQLServerSchema getSchema() {
        return table.getSchema();
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(),
            getSchema(),
            this);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getTable().getSchema().getTriggerCache().refreshObject(monitor, getSchema(), this);
    }

    public boolean canDisable() {
        return !isDisabled() && !(getParentObject() instanceof SQLServerView);
    }

    public boolean canEnable() {
        return isDisabled() && !(getParentObject() instanceof SQLServerView);
    }

    @NotNull
    @Override
    public SQLServerDatabase getDatabase() {
        return table.getDatabase();
    }
}
