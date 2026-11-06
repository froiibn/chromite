/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.mysql.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;
import com.seizia.chromite.model.sql.task.SQLToolRunStatisticsGenerator;
import com.seizia.chromite.model.sql.task.SQLToolStatistics;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstract tool with status
 */
public abstract class MySQLToolWithStatus<OBJECT_TYPE extends DBSObject, SETTINGS extends SQLToolExecuteSettings<OBJECT_TYPE>>
    extends SQLToolExecuteHandler<OBJECT_TYPE, SETTINGS> implements SQLToolRunStatisticsGenerator<OBJECT_TYPE, SETTINGS, DBEPersistAction>
{
    @NotNull
    @Override
    public List<ToolStatus> getExecuteStatistics(@NotNull OBJECT_TYPE object, @NotNull SETTINGS settings, @NotNull DBEPersistAction action, @NotNull
    DBCSession session, @NotNull DBCStatement dbStat) throws DBException {
        DBCResultSet dbResult = dbStat.openResultSet();
        if (!(dbResult instanceof JDBCResultSet)) {
            return Collections.emptyList();
        }
        try (dbResult) {
            List<ToolStatus> statusList = new ArrayList<>();
            while (dbResult.nextRow()) {
                statusList.add(
                    new ToolStatus(
                        object,
                        JDBCUtils.safeGetString((JDBCResultSet) dbResult, "Msg_type"),
                        JDBCUtils.safeGetString((JDBCResultSet) dbResult, "Msg_text")));
            }
            return statusList;
        }
    }

    public class ToolStatus extends SQLToolStatistics<OBJECT_TYPE> {
        private final String messageType;
        private final String messageText;

        ToolStatus(OBJECT_TYPE object, String messageType, String messageText) {
            super(object);
            this.messageType = messageType;
            this.messageText = messageText;
        }

        @Property(viewable = true, order = 1)
        @Override
        public OBJECT_TYPE getObject() {
            return super.getObject();
        }

        @Property(viewable = true, order = 2)
        public String getMessageType() {
            return messageType;
        }

        @Property(viewable = true, order = 3)
        public String getMessageText() {
            return messageText;
        }

    }

}
