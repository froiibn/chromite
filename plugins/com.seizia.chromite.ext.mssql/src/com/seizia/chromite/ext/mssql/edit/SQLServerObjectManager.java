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
package com.seizia.chromite.ext.mssql.edit;

import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerExecutionContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * SQLServerObjectManager
 *
 * 변경 이력:
 * - 2026-11-29 Chromite: {@link #addDatabaseSwitchAction2(DBCExecutionContext, List, SQLServerDatabase, Map)}: 명령 옵션
 *   {@link DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}이면 끝의 {@code USE <기본 DB>}를 넣지 않는다. Modify 스크립트를 받는 자기 세션 창은
 *   개체의 DB에서 열리는데, 그 문장이 실행 뒤 창의 세션을 기본 DB로 옮겼다
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
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

    /**
     * {@link #addDatabaseSwitchAction2(DBCExecutionContext, List, SQLServerDatabase)} unless the command's options keep the session in
     * the object's database ({@link DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}).
     */
    protected void addDatabaseSwitchAction2(
        DBCExecutionContext executionContext,
        List<DBEPersistAction> actions,
        SQLServerDatabase objDatabase,
        Map<String, Object> options
    ) {
        if (!CommonUtils.getOption(options, DBPScriptObject.OPTION_SKIP_DEFAULTS_RESTORE)) {
            addDatabaseSwitchAction2(executionContext, actions, objDatabase);
        }
    }

}

