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
package com.seizia.chromite.ext.oracle.tasks;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.oracle.model.OracleTableTrigger;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;

import java.util.List;

public class OracleToolTableTriggerDisable extends SQLToolExecuteHandler<OracleTableTrigger, OracleToolTableTriggerSettings> {

    @NotNull
    @Override
    public OracleToolTableTriggerSettings createToolSettings() {
        return new OracleToolTableTriggerSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, OracleToolTableTriggerSettings settings, List<DBEPersistAction> queries, OracleTableTrigger object) throws DBCException {
        String sql = "ALTER TRIGGER " + object.getFullyQualifiedName(DBPEvaluationContext.DDL) + " DISABLE";
        queries.add(new SQLDatabasePersistAction(sql));
    }

    public boolean needsRefreshOnFinish() {
        return true;
    }
}
