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
package com.seizia.chromite.ext.postgresql.model;

import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.sql.SQLQuery;

import java.sql.SQLException;
import java.sql.Statement;

/**
* Query transformer for fetch-all selects
*/
class QueryTransformerFetchAll implements DBCQueryTransformer {

    public static final int BULK_EXPORT_FETCH_SIZE = 100000;

    @Override
    public void setParameters(Object... parameters)
    {
    }

    @Override
    public String transformQueryString(SQLQuery query) throws DBCException {
        return query.getText();
    }

    @Override
    public void transformStatement(DBCStatement statement, int parameterIndex) throws DBCException {
        // Set fetch size to 100k (maybe this should be configurable?)
        try {
            ((Statement)statement).setFetchSize(BULK_EXPORT_FETCH_SIZE);
        } catch (SQLException e) {
            throw new DBCException(e, statement.getSession().getExecutionContext());
        }
    }
}