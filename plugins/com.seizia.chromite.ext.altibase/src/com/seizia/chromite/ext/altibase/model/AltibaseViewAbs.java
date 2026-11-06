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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.AltibaseUtils;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericView;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObjectState;

public abstract class AltibaseViewAbs extends GenericView implements DBPStatefulObject {

    private static final Log log = Log.getLog(AltibaseViewAbs.class);
    
    protected String schemaName = null;
    protected boolean isValid = false;

    public AltibaseViewAbs(JDBCSession session, GenericStructContainer container, String tableName, String tableType, JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
        schemaName = container.getName();

        // Create a new view
        if (session == null) {
            isValid = true;
        // Fetch from a database
        } else {
            try {
                isValid = AltibaseUtils.getViewStatus(session, tableType, getSchemaName(), getName());
            } catch (DBCException e) {
                log.warn("Can't update view status.", e);
            }
        }
    }

    @Override
    public String getSchemaName() {
        return schemaName;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, 
                "Refresh state of view '" + this.getName() + "'")) {
            isValid = AltibaseUtils.getViewStatus(session, getTableType(), getSchemaName(), getName());
        }
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return isValid ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }
}
