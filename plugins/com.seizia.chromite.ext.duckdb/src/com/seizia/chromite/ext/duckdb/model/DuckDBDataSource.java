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
package com.seizia.chromite.ext.duckdb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.regex.Pattern;

/**
 * DuckDB Data source
 */
public class DuckDBDataSource extends GenericDataSource {
    private static final Pattern ARRAY_TYPE_NAME_PATTERN = Pattern.compile("\\[\\s*\\d*\\s*\\]\\s*$");

    public DuckDBDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, GenericMetaModel metaModel) throws DBException {
        super(monitor, container, metaModel, new DuckDBSQLDialect());
    }

    @NotNull
    @Override
    public DBPDataKind resolveDataKind(@NotNull String typeName, int valueType) {
        if (DuckDBConstants.isGeometryType(typeName)) {
            return DBPDataKind.OBJECT;
        } else if (ARRAY_TYPE_NAME_PATTERN.matcher(typeName).find()) {
            return DBPDataKind.ARRAY;
        } else {
            return super.resolveDataKind(typeName, valueType);
        }
    }

    @NotNull
    @Override
    protected DBPDataSourceInfo createDataSourceInfo(@NotNull DBRProgressMonitor monitor, @NotNull JDBCDatabaseMetaData metaData) {
        return new DuckDBDataSourceInfo(getContainer().getDriver(), metaData);
    }

    @NotNull
    @Override
    protected JDBCExecutionContext createExecutionContext(@NotNull JDBCRemoteInstance instance, @NotNull String type) {
        return new DuckDBExecutionContext(instance, type);
    }
}
