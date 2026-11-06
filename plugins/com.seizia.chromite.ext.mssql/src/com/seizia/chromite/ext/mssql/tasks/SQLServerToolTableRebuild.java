/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.mssql.tasks;

import com.seizia.chromite.ext.mssql.model.SQLServerTableBase;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;

import java.util.List;

public class SQLServerToolTableRebuild extends SQLServerToolWithStatus<SQLServerTableBase, SQLServerToolTableRebuildSettings> {
    @Override
    public SQLServerToolTableRebuildSettings createToolSettings() {
        return new SQLServerToolTableRebuildSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, SQLServerToolTableRebuildSettings settings, List<DBEPersistAction> queries, SQLServerTableBase object) throws DBCException {
        queries.add(new SQLDatabasePersistAction("ALTER INDEX ALL ON " + object.getFullyQualifiedName(DBPEvaluationContext.DDL) + " REBUILD "));
    }
}
