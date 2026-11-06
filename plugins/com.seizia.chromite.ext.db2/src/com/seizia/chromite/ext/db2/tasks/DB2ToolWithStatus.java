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
package com.seizia.chromite.ext.db2.tasks;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;
import com.seizia.chromite.model.sql.task.SQLToolRunStatisticsGenerator;
import com.seizia.chromite.model.sql.task.SQLToolStatistics;
import com.seizia.chromite.model.struct.DBSObject;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

public abstract class DB2ToolWithStatus <OBJECT_TYPE extends DBSObject, SETTINGS extends SQLToolExecuteSettings<OBJECT_TYPE>>
        extends SQLToolExecuteHandler<OBJECT_TYPE, SETTINGS> implements SQLToolRunStatisticsGenerator<OBJECT_TYPE, SETTINGS, DBEPersistAction> {
    @NotNull
    @Override
    public List<ToolStatus> getExecuteStatistics(@NotNull OBJECT_TYPE object, @NotNull SETTINGS settings, @NotNull DBEPersistAction action, @NotNull
    DBCSession session, @NotNull DBCStatement dbStat) throws DBCException {
        List<ToolStatus> statusList = new ArrayList<>();
        try {
            int warnNum = 0;
            SQLWarning warning = ((JDBCStatement) dbStat).getWarnings();
            while (warning != null) {
                statusList.add(new ToolStatus(object, warning.getMessage()));
                warnNum++;
                warning = warning.getNextWarning();
            }
            if (warnNum == 0) {
                statusList.add(new ToolStatus(object, "Done")); //$NON-NLS-1$
            }
        } catch (SQLException e) {
            // ignore
        }
        return statusList;
    }

    public class ToolStatus extends SQLToolStatistics<OBJECT_TYPE> {
        private final String message;

        ToolStatus(OBJECT_TYPE object, String message) {
            super(object);
            this.message = message;
        }

        @Property(viewable = true, order = 1)
        @Override
        public OBJECT_TYPE getObject() {
            return super.getObject();
        }

        @Property(viewable = true, order = 2)
        public String getMessage() {
            return message;
        }
    }
}
