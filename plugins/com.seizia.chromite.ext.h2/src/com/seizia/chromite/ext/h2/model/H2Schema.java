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
package com.seizia.chromite.ext.h2.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPObjectStatisticsCollector;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.util.List;

public class H2Schema extends GenericSchema implements DBPObjectStatisticsCollector {
    private boolean hasStatistics = false;

    public H2Schema(@NotNull GenericDataSource dataSource, @Nullable GenericCatalog catalog, @NotNull String schemaName) {
        super(dataSource, catalog, schemaName);
    }

    @Override
    public boolean isStatisticsCollected() {
        return hasStatistics;
    }

    @Override
    public void collectObjectStatistics(
        @NotNull DBRProgressMonitor monitor,
        boolean totalSizeOnly,
        boolean forceRefresh
    ) throws DBException {
        if (hasStatistics && !forceRefresh) {
            return;
        }
        List<? extends GenericTableBase> tables = getTables(monitor);
        try (DBCSession session = DBUtils.openMetaSession(monitor, this, "Read table statistics")) {
            try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement("SELECT DISK_SPACE_USED(?)")) {
                for (GenericTableBase table : tables) {
                    if (table instanceof H2Table h2Table) {
                        dbStat.setString(1, table.getFullyQualifiedName(DBPEvaluationContext.DDL));
                        try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                            if (dbResult.next()) {
                                h2Table.setTableDiskSize(dbResult.getLong(1));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                throw new DBCException("Error reading catalog statistics", e);
            }
        } finally {
            hasStatistics = true;
        }
    }
}
