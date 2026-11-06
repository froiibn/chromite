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
package com.seizia.chromite.ext.altibase.tasks;

import com.seizia.chromite.ext.altibase.model.AltibaseMaterializedView;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;

import java.util.List;

public class AltibaseToolMViewRefresh extends SQLToolExecuteHandler<AltibaseMaterializedView, AltibaseToolMViewRefreshSettings> {
    @Override
    public AltibaseToolMViewRefreshSettings createToolSettings() {
        return new AltibaseToolMViewRefreshSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, AltibaseToolMViewRefreshSettings settings, 
            List<DBEPersistAction> queries, AltibaseMaterializedView object) throws DBCException {
        String sql = String.format("EXEC SYSTEM_.REFRESH_MATERIALIZED_VIEW('%s', '%s')", 
                DBUtils.getQuotedIdentifier(object.getDataSource(), object.getSchemaName()),
                DBUtils.getQuotedIdentifier(object.getDataSource(), object.getName()));
        queries.add(new SQLDatabasePersistAction(sql));
    }

    @Override
    public boolean needsRefreshOnFinish() {
        return true;
    }
}
