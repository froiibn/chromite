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
package com.seizia.chromite.model.sql.transformers;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryTransformer;
import com.seizia.chromite.model.sql.SQLSyntaxManager;

/**
 * SQLQueryTransformerAllRows.
*/
public class SQLQueryTransformerAllRows implements SQLQueryTransformer {
    @NotNull
    @Override
    public SQLQuery transformQuery(@NotNull DBPDataSource dataSource, @NotNull SQLSyntaxManager syntaxManager, @NotNull SQLQuery query) throws DBException {
        SQLQuery allRowsQuery = new SQLQuery(dataSource, query.getText(), query);
        allRowsQuery.setResultSetLimit(0, 0);
        return allRowsQuery;
    }
}
