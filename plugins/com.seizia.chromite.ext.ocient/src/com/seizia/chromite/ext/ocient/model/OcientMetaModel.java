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
package com.seizia.chromite.ext.ocient.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericView;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.util.Map;

public class OcientMetaModel extends GenericMetaModel {

    public OcientMetaModel() {
        super();
    }
    
    @NotNull
    @Override
    public GenericTableBase createTableOrViewImpl(
        @NotNull GenericStructContainer container,
        @Nullable String tableOrViewName,
        @Nullable String tableOrViewType,
        @Nullable JDBCResultSet dbResult)
    {
        if (tableOrViewType != null && isView(tableOrViewType))
        {
            return new OcientView(
                container,
                tableOrViewName,
                tableOrViewType,
                dbResult);
        } else {
            return new OcientTable(
                container,
                tableOrViewName,
                tableOrViewType,
                dbResult);
        }
    }

    protected String getObjectDDL(
        DBRProgressMonitor monitor,
        GenericTableBase sourceObject,
        Map<String, Object> options,
        String sql,
        String description
    ) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();

        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, description)) {
            try (JDBCPreparedStatement stmt = session.prepareStatement(sql)) {
                try (JDBCResultSet resultSet = stmt.executeQuery()) {
                    StringBuilder createText = new StringBuilder();
                    while (resultSet.nextRow()) {
                        String creationDDL = resultSet.getString(1);

                        if (creationDDL != null)
                            createText.append(creationDDL);
                    }

                    if (createText.isEmpty()) {
                        return "-- Create text not found";
                    }
                    else {
                        return createText.toString();
                    }
                }
            }
        }
        catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    public String getViewDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericView sourceObject,
        @NotNull Map<String, Object> options
    ) throws DBException {
        String fullQualifiedName =
                DBUtils.getFullQualifiedName(sourceObject.getDataSource(), sourceObject.getContainer(), sourceObject);
        String viewDDLSQL = String.format("export view %s", fullQualifiedName);
        try {
            return getObjectDDL(monitor, sourceObject, options, viewDDLSQL,
                    "Read Ocient view create text");
        } catch (DBException e) {
            return super.getViewDDL(monitor, sourceObject, options);
        }
    }

    public String getTableDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase sourceObject,
        @NotNull Map<String, Object> options
    ) throws DBException {
        String fullQualifiedName =
                DBUtils.getFullQualifiedName(sourceObject.getDataSource(), sourceObject.getContainer(), sourceObject);
        String tableDDLSQL = String.format("export table %s", fullQualifiedName);
        try {
            return getObjectDDL(monitor, sourceObject, options, tableDDLSQL,
                    "Read Ocient base table create text");
        } catch (DBException e) {
            return super.getTableDDL(monitor, sourceObject, options);
        }
    }

}
