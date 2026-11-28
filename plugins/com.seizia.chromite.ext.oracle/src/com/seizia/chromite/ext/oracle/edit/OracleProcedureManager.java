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
import com.seizia.chromite.ext.oracle.model.*;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;

import java.util.List;
import java.util.Map;

/**
 * OracleProcedureManager
 *
 * 변경 이력:
 * - 2026-11-29 Chromite: 만들기·고치기가 명령 옵션을 {@code OracleUtils.addSchemaChangeActions}에
 *   넘김: {@link com.seizia.chromite.model.DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}이면 끝의 {@code ALTER SESSION SET CURRENT_SCHEMA=<기본>}을 넣지 않음
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
 */
public class OracleProcedureManager extends SQLObjectEditor<OracleProcedureStandalone, OracleSchema> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleProcedureStandalone> getObjectsCache(OracleProcedureStandalone object)
    {
        return object.getSchema().getProceduresCache();
    }

    @Override
    protected OracleProcedureStandalone createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return createProcedure((OracleSchema) container);
    }

    @NotNull
    protected OracleProcedureStandalone createProcedure(@NotNull OracleSchema schema) {
        return new OracleProcedureStandalone(
            schema,
            "NEW_PROCEDURE",
            DBSProcedureType.PROCEDURE);
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand objectCreateCommand, @NotNull Map<String, Object> options)
    {
        createOrReplaceProcedureQuery(monitor, executionContext, actions, objectCreateCommand.getObject(), options);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand objectDeleteCommand, @NotNull Map<String, Object> options)
    {
        final OracleProcedureStandalone object = objectDeleteCommand.getObject();
        actions.add(
            new SQLDatabasePersistAction("Drop procedure",
                "DROP " + object.getProcedureType().name() + " " + object.getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-1$ //$NON-NLS-2$
        );
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand objectChangeCommand, @NotNull Map<String, Object> options)
    {
        createOrReplaceProcedureQuery(monitor, executionContext, actionList, objectChangeCommand.getObject(), options);
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_EDITOR_ON_CREATE;
    }

    private void createOrReplaceProcedureQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull OracleProcedureStandalone procedure,
        @NotNull Map<String, Object> options
    ) {
        String source = OracleUtils.normalizeSourceName(monitor, procedure, false);
        if (source == null) {
            return;
        }
        actionList.add(new OracleObjectValidateAction(procedure, OracleObjectType.PROCEDURE, "Create procedure", source)); //$NON-NLS-2$
        OracleUtils.addSchemaChangeActions(executionContext, actionList, procedure, options);
    }

}
