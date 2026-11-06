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
package com.seizia.chromite.ext.mssql.edit;

import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerExecutionContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.List;

/**
 * SQLServerObjectManager
 */
public abstract class SQLServerObjectManager<OBJECT_TYPE extends DBSObject, CONTAINER_TYPE extends DBSObject>
    extends SQLObjectEditor<OBJECT_TYPE, CONTAINER_TYPE> {

    protected void addDatabaseSwitchAction1(DBCExecutionContext executionContext, List<DBEPersistAction> actions, SQLServerDatabase objDatabase) {
        SQLServerDatabase defaultDatabase = ((SQLServerExecutionContext) executionContext).getDefaultCatalog();
        if (defaultDatabase != null && defaultDatabase != objDatabase) {
            actions.add(new SQLDatabasePersistAction("Set current database", "USE " + DBUtils.getQuotedIdentifier(objDatabase), false)); //$NON-NLS-2$
        }
    }

    protected void addDatabaseSwitchAction2(DBCExecutionContext executionContext, List<DBEPersistAction> actions, SQLServerDatabase objDatabase) {
        SQLServerDatabase defaultDatabase = ((SQLServerExecutionContext) executionContext).getDefaultCatalog();
        if (defaultDatabase != null && defaultDatabase != objDatabase) {
            actions.add(new SQLDatabasePersistAction("Set current database ", "USE " + DBUtils.getQuotedIdentifier(defaultDatabase), false)); //$NON-NLS-2$
        }
    }

}

