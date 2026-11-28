/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ext.oracle.model.OracleTableTrigger;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLTriggerManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OracleTableTriggerManager
 *
 * 변경 이력:
 * - 2026-11-29 Chromite: 만들기·고치기가 명령 옵션을 {@code OracleUtils.addSchemaChangeActions}에
 *   넘김: {@link com.seizia.chromite.model.DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}이면 끝의 {@code ALTER SESSION SET CURRENT_SCHEMA=<기본>}을 넣지 않음
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
 */
public class OracleTableTriggerManager extends SQLTriggerManager<OracleTableTrigger, OracleTableBase> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleTableTrigger> getObjectsCache(OracleTableTrigger object) {
        return object.getTable().getSchema().getTableTriggerCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof OracleTableBase;
    }

    @Override
    protected OracleTableTrigger createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return createTableTrigger((OracleTableBase) container);
    }

    @NotNull
    protected OracleTableTrigger createTableTrigger(@NotNull OracleTableBase table) {
        return new OracleTableTrigger(table, "NEW_TRIGGER");
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        actions.add(
            new SQLDatabasePersistAction("Drop trigger", "DROP TRIGGER " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }

    protected void createOrReplaceTriggerQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull OracleTableTrigger trigger,
        boolean create
    ) {
        createOrReplaceTriggerQuery(monitor, executionContext, actions, trigger, create, DBPScriptObject.EMPTY_OPTIONS);
    }

    @Override
    protected void createOrReplaceTriggerQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull OracleTableTrigger trigger,
        boolean create,
        @NotNull Map<String, Object> options
    ) {
        String source = OracleUtils.normalizeSourceName(monitor, trigger, false);
        if (source == null) {
            return;
        }
        String script = source;
        if (!script.toUpperCase(Locale.ENGLISH).trim().contains("CREATE ")) {
            script = "CREATE OR REPLACE " + script;
        }
        actions.add(new SQLDatabasePersistAction("Create trigger", script, true)); //$NON-NLS-2$
        OracleUtils.addSchemaChangeActions(executionContext, actions, trigger, options);
    }

}

