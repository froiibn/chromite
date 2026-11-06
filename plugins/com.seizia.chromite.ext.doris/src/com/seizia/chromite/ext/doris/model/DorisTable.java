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
package com.seizia.chromite.ext.doris.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.ext.doris.DorisUtils;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Doris Table - represents a table within a Doris database.
 */
public class DorisTable extends GenericTable {

    private static final String COL_CREATE_TABLE = "Create Table"; //$NON-NLS-1$

    private String ddl;

    public DorisTable(
        @NotNull GenericStructContainer container,
        @Nullable String tableName,
        @Nullable String tableType,
        @Nullable JDBCResultSet dbResult
    ) {
        super(container, tableName, tableType, dbResult);
    }

    @NotNull
    @Override
    public DorisDataSource getDataSource() {
        return (DorisDataSource) super.getDataSource();
    }

    @Nullable
    @Override
    public GenericCatalog getCatalog() {
        return DorisObjectNameUtils.getCatalog(getContainer());
    }

    @Nullable
    @Override
    public GenericSchema getSchema() {
        return DorisObjectNameUtils.getSchema(getContainer());
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DorisObjectNameUtils.getFullyQualifiedName(getDataSource(), getCatalog(), getSchema(), this);
    }

    @Override
    public boolean isView() {
        return false;
    }

    @Nullable
    @Override
    public String getDDL() {
        return ddl;
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (CommonUtils.getOption(options, DBPScriptObject.OPTION_REFRESH)) {
            ddl = null;
        }
        if (ddl == null && isPersisted()) {
            loadDDL(monitor);
        }
        return ddl != null ? ddl : "";
    }

    private void loadDDL(@NotNull DBRProgressMonitor monitor) throws DBCException {
        ddl = DorisUtils.loadShowCreateDDL(
            monitor, this, "Load table DDL", "SHOW CREATE TABLE", COL_CREATE_TABLE); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
