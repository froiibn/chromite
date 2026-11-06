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
package com.seizia.chromite.ext.hive.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.ext.hive.model.jdbc.HiveJdbcFactory;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformType;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCFactory;
import com.seizia.chromite.model.impl.sql.QueryTransformerLimit;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLState;

import java.sql.SQLException;

public class HiveDataSource extends GenericDataSource {
    private static final Log log = Log.getLog(HiveDataSource.class);

    private static final String CONNECTION_CLOSED_MESSAGE = "Connection is closed";

    public HiveDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, GenericMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel, new HiveSQLDialect());
    }

    @NotNull
    @Override
    protected HiveDataSourceInfo createDataSourceInfo(@NotNull DBRProgressMonitor monitor, @NotNull JDBCDatabaseMetaData metaData) {
        return new HiveDataSourceInfo(monitor, this, metaData);
    }

    @NotNull
    @Override
    protected JDBCFactory createJdbcFactory() {
        return new HiveJdbcFactory();
    }

    @NotNull
    @Override
    public ErrorType discoverErrorType(@NotNull Throwable error) {
        if (error instanceof SQLException && CONNECTION_CLOSED_MESSAGE.equals(error.getMessage())) {
            return ErrorType.CONNECTION_LOST;
        }
        String sqlState = SQLState.getStateFromException(error);
        if (SQLState.SQL_08S01.getCode().equals(sqlState)) {
            // By some reason many Hive errors have this SQL state
            return ErrorType.NORMAL;
        }
        return super.discoverErrorType(error);
    }

    @Nullable
    @Override
    public DBCQueryTransformer createQueryTransformer(@NotNull DBCQueryTransformType type) {
        if (type == DBCQueryTransformType.RESULT_SET_LIMIT) {
            return new QueryTransformerLimit(true, false);
        }
        return null;
    }

    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        return adapter == DBDValueHandlerProvider.class
            ? adapter.cast(new HiveValueHandlerProvider())
            : super.getAdapter(adapter);
    }
}
