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

import com.seizia.chromite.ext.oracle.model.OracleTable;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.List;
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
public class OracleToolsTableGatherStatistics extends SQLToolExecuteHandler<DBSObject, OracleToolTableGatherStatisticsSettings> {
    @Override
    public OracleToolTableGatherStatisticsSettings createToolSettings() {
        return new OracleToolTableGatherStatisticsSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, OracleToolTableGatherStatisticsSettings settings, List<DBEPersistAction> queries, DBSObject object) throws DBCException {
        if (object instanceof OracleTable) {
            OracleTable table = (OracleTable)object;
            int percent = settings.getSamplePercent();
            String sql = "BEGIN \n" +
                    " DBMS_STATS.GATHER_TABLE_STATS (\n" +
                    " OWNNAME => '" + DBUtils.getQuotedIdentifier(table.getSchema()) + "',\n" +
                    " TABNAME => '" + DBUtils.getQuotedIdentifier(table) + "'";
            if (percent > 0) {
                sql += ",\n estimate_percent => " + percent;
            }
            sql += " \n );\n END;";
            queries.add(new SQLDatabasePersistAction(sql));
        }
    }
}
