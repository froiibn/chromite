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
package com.seizia.chromite.model.impl.sql.edit.struct;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * SQL trigger manager
 *
 * 변경 이력:
 * - 2026-11-29 Chromite: 만들기·고치기가 명령의 옵션을 받는 {@link #createOrReplaceTriggerQuery(DBRProgressMonitor, DBCExecutionContext, List, DBSTrigger, boolean, Map)}을
 *   부른다. 기본은 옵션 없는 메서드와 같고, 끝에 기본 DB/스키마로 돌아가는 문장을 넣는 관리자가
 *   {@link com.seizia.chromite.model.DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}를 보도록 고쳐 쓴다
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
 */
public abstract class SQLTriggerManager<OBJECT_TYPE extends DBSTrigger, CONTAINER_TYPE extends DBSObject>
    extends SQLObjectEditor<OBJECT_TYPE, CONTAINER_TYPE>
{

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    protected void validateObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (CommonUtils.isEmpty(command.getObject().getName())) {
            throw new DBException("Trigger name cannot be empty");
        }
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options)
    {
        createOrReplaceTriggerQuery(monitor, executionContext, actions, command.getObject(), true, options);
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
    {
        createOrReplaceTriggerQuery(monitor, executionContext, actionList, command.getObject(), false, options);
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options)
    {
        actions.add(
            new SQLDatabasePersistAction("Drop trigger",
                "DROP TRIGGER " + DBUtils.getObjectFullName(command.getObject(), DBPEvaluationContext.DDL))
        );
    }

    protected abstract void createOrReplaceTriggerQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull OBJECT_TYPE trigger,
        boolean create);

    /**
     * Makes the create/replace statements with the command's options (e.g.
     * {@link com.seizia.chromite.model.DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}); by default the options are not used.
     */
    protected void createOrReplaceTriggerQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull OBJECT_TYPE trigger,
        boolean create,
        @NotNull Map<String, Object> options)
    {
        createOrReplaceTriggerQuery(monitor, executionContext, actions, trigger, create);
    }

}

