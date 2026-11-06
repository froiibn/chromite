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
package com.seizia.chromite.ext.trino.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericView;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCBasicDataTypeCache;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCDataType;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBStructUtils;

import java.sql.SQLException;
import java.util.Map;

public class TrinoMetaModel extends GenericMetaModel {

    public TrinoMetaModel() {
    }

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new TrinoDataSource(monitor, container, this);
    }

    @Override
    public JDBCBasicDataTypeCache<GenericStructContainer, ? extends JDBCDataType> createDataTypeCache(
        @NotNull GenericStructContainer container
    ) {
        return new TrinoDataTypeCache(container);
    }
    
    @Override
    public String getViewDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericView sourceObject, @NotNull Map<String, Object> options) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Trino view source")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SHOW CREATE VIEW " + sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))) 
            {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                    	return dbResult.getString(1);
                    } else {
                        return "-- View definition not found in system catalog";
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }	

    @Override
    public String getTableDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase sourceObject, @NotNull Map<String, Object> options) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Trino table DDL")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                    "SHOW CREATE TABLE " + sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))) 
                {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        return dbResult.getString(1);
                    } else {
                        // Trino uses multiple sources, just in case there is one that does not provide a result
                        return "-- Generated generic DDL \n" +
                                DBStructUtils.generateTableDDL(monitor, sourceObject, options, false);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }     
}   
