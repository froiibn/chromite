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
package com.seizia.chromite.model.sql.generator.resultset;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDResultSetDataProvider;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;

import java.util.Collection;
import java.util.List;

public class SQLGeneratorSelectFromData extends SQLGeneratorResultSet {

    @Override
    protected void generateSQL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull StringBuilder sql,
        @NotNull DBDResultSetDataProvider dataProvider
    ) throws DBException {
        for (DBDValueRow firstRow : dataProvider.getSelectedRows()) {
            sql.append("SELECT ");
            boolean hasAttr = false;
            for (DBSAttributeBase attr : getAllAttributes(monitor, dataProvider)) {
                if (hasAttr) sql.append(", ");
                sql.append(DBUtils.getObjectFullName(attr, DBPEvaluationContext.DML));
                hasAttr = true;
            }
            sql.append(getLineSeparator()).append("FROM ").append(getEntityName(dataProvider.getSingleSource()));
            sql.append(getLineSeparator()).append("WHERE ");
            Collection<DBDAttributeBinding> keyAttributes = getKeyAttributes(monitor, dataProvider);
            if (keyAttributes.isEmpty()) {
                // No unique key - use all columns
                keyAttributes = List.of(dataProvider.getAttributes());
            }
            appendKeyConditions(sql, keyAttributes, firstRow);
            sql.append(";\n");
        }
    }

}
