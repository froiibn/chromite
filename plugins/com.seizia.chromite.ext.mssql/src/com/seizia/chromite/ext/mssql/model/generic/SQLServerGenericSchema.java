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
package com.seizia.chromite.ext.mssql.model.generic;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.model.DBPObjectStatisticsCollector;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;

/**
* SQL Server schemas
*/
public class SQLServerGenericSchema extends GenericSchema implements DBPObjectStatisticsCollector {

    private static final Log log = Log.getLog(SQLServerGenericSchema.class);

    private long schemaId;
    private boolean hasStatistics;

    public SQLServerGenericSchema(GenericDataSource dataSource, GenericCatalog catalog, String schemaName, long schemaId) {
        super(dataSource, catalog, schemaName);
        this.schemaId = schemaId;
    }

    @Property(viewable = true, order = 3)
    public long getSchemaId() {
        return schemaId;
    }

    @Override
    public boolean isStatisticsCollected() {
        return hasStatistics;
    }

    @Override
    public void collectObjectStatistics(@NotNull DBRProgressMonitor monitor, boolean totalSizeOnly, boolean forceRefresh) throws DBException {
        if (hasStatistics && !forceRefresh) {
            return;
        }
        boolean isSQLServer = ((SQLServerMetaModel) getDataSource().getMetaModel()).isSqlServer();
        if (!isSQLServer && !getDataSource().isServerVersionAtLeast(15, 0)) {
            hasStatistics = true;
            return;
        }
        GenericCatalog catalog = getCatalog();
        if (catalog == null) {
            log.debug("Can't read tables statistics due to lack of schemas catalog");
            return;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load table statistics")) {
            try (JDBCPreparedStatement dbStat = SQLServerUtils.prepareTableStatisticLoadStatement(
                session,
                getDataSource(),
                catalog,
                getSchemaId(),
                null,
                isSQLServer)) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String tableName = dbResult.getString("name");
                        GenericTableBase table = getTable(monitor, tableName);
                        if (table instanceof SQLServerGenericTable) {
                            ((SQLServerGenericTable) table).fetchTableStats(dbResult);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error reading table statistics", e);
        } finally {
            hasStatistics = true;
        }
    }
}
