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
package com.seizia.chromite.ext.oracle.tasks;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ext.oracle.model.OracleTablePartition;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;

import java.util.List;

public class OracleToolTableTruncate extends SQLToolExecuteHandler<OracleTableBase, OracleToolTableTruncateSettings> {

    @NotNull
    @Override
    public OracleToolTableTruncateSettings createToolSettings() {
        return new OracleToolTableTruncateSettings();
    }

    @Override
    public void generateObjectQueries(
        DBCSession session,
        OracleToolTableTruncateSettings settings,
        List<DBEPersistAction> queries,
        OracleTableBase object
    ) {
        StringBuilder sql = new StringBuilder(32);
        if (object instanceof OracleTablePartition) {
            OracleTablePartition partition = (OracleTablePartition) object;
            sql.append("ALTER TABLE ")
                .append(partition.getParent().getFullyQualifiedName(DBPEvaluationContext.DDL))
                .append(" TRUNCATE ");
            if (partition.isSubPartition()) {
                sql.append("SUB");
            }
            sql.append("PARTITION ")
                .append(DBUtils.getQuotedIdentifier(object));
        } else {
            sql.append("TRUNCATE TABLE ").append(object.getFullyQualifiedName(DBPEvaluationContext.DDL));
        }
        if (settings.isReusable()) {
            sql.append(" REUSE STORAGE");
        }
        queries.add(new SQLDatabasePersistAction(sql.toString()));
    }

    public boolean needsRefreshOnFinish() {
        return true;
    }
}
