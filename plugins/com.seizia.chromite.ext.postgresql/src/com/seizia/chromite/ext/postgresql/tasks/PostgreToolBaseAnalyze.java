/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.postgresql.tasks;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.postgresql.model.PostgreTableBase;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.List;

public class PostgreToolBaseAnalyze extends PostgreToolWithStatus<DBSObject, PostgreToolBaseAnalyzeSettings> {
    @NotNull
    @Override
    public PostgreToolBaseAnalyzeSettings createToolSettings() {
        return new PostgreToolBaseAnalyzeSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, PostgreToolBaseAnalyzeSettings settings, List<DBEPersistAction> queries, DBSObject object) throws DBCException {
        String sql = "ANALYZE VERBOSE";
        if(object instanceof PostgreTableBase){
            PostgreTableBase postObject = (PostgreTableBase) object;
            sql += " " + postObject.getFullyQualifiedName(DBPEvaluationContext.DDL);
        }
        queries.add(new SQLDatabasePersistAction(sql));
    }
}
