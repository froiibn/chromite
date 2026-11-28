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
package com.seizia.chromite.ext.mssql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerExecutionContext;
import com.seizia.chromite.ext.mssql.model.SQLServerSchema;
import com.seizia.chromite.ext.mssql.model.SQLServerView;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLTableManager;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * SQLServerViewManager
 *
 * 변경 이력:
 * - 2026-11-29 Chromite: 명령 옵션 {@link DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}이면 끝의 {@code USE <기본 DB>}를 넣지 않음
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
 */
public class SQLServerViewManager extends SQLServerBaseTableManager<SQLServerView> {

    enum ViewAction {
        CREATE,
        ALTER,
        DROP
    }

    @NotNull
    @Override
    public Class<? extends DBSObject>[] getChildTypes() {
        return new Class[0];
    }

    @Override
    protected void validateObjectProperty(
        @NotNull SQLServerView object,
        @NotNull DBPPropertyDescriptor property,
        @Nullable Object value
    ) throws DBException {
        if (CommonUtils.isEmpty(object.getName())) {
            throw new DBException("View name cannot be empty");
        }
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return SQLTableManager.BASE_VIEW_NAME;
    }

    @Override
    protected SQLServerView createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        SQLServerView newView = new SQLServerView((SQLServerSchema) container);
        setNewObjectName(monitor, (SQLServerSchema) container, newView);
        return newView;
    }

    @Override
    protected void addStructObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull StructCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        createOrReplaceViewQuery(monitor, executionContext, actions, command.getObject(), ViewAction.CREATE, options);
    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (command.getProperties().size() > 1 || !command.hasProperty(DBConstants.PROP_ID_DESCRIPTION)) {
            createOrReplaceViewQuery(monitor, executionContext, actionList, command.getObject(), ViewAction.ALTER, options);
        }
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        createOrReplaceViewQuery(monitor, executionContext, actions, command.getObject(), ViewAction.DROP, options);
    }

    private void createOrReplaceViewQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull SQLServerView view,
        @NotNull ViewAction action,
        @NotNull Map<String, Object> options
    ) {
        SQLServerDatabase procDatabase = view.getContainer().getDatabase();
        SQLServerDatabase defaultDatabase = ((SQLServerExecutionContext)executionContext).getDefaultCatalog();
        boolean addUse = defaultDatabase != null && procDatabase != null && defaultDatabase != procDatabase;
        if (addUse) {
            actions.add(new SQLDatabasePersistAction("Set current database", "USE " + DBUtils.getQuotedIdentifier(procDatabase), false)); //$NON-NLS-2$
        }

        switch (action) {
            case CREATE:
                actions.add(new SQLDatabasePersistAction("Create view", view.getDDL()));
                break;
            case ALTER:
                actions.add(new SQLDatabasePersistAction("Alter view", SQLServerUtils.changeCreateToAlterDDL(view.getDataSource(), view.getDDL())));
                break;
            case DROP:
                actions.add(new SQLDatabasePersistAction("Drop view", "DROP VIEW " + view.getFullyQualifiedName(DBPEvaluationContext.DDL))); //$NON-NLS-2$
                break;
        }

        if (addUse && !CommonUtils.getOption(options, DBPScriptObject.OPTION_SKIP_DEFAULTS_RESTORE)) {
            actions.add(new SQLDatabasePersistAction("Set current database ", "USE " + DBUtils.getQuotedIdentifier(defaultDatabase), false)); //$NON-NLS-2$
        }
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull SQLServerView object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }


}
