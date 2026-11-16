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
package com.seizia.chromite.ext.mysql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLTableBase;
import com.seizia.chromite.ext.mysql.model.MySQLView;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLTableManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.parser.SQLSemanticProcessor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * MySQLViewManager
 */
public class MySQLViewManager extends MySQLTableManager {

    @Nullable
    @Override
    public DBSObjectCache<MySQLCatalog, MySQLTableBase> getObjectsCache(MySQLTableBase object)
    {
        return object.getContainer().getTableCache();
    }

    @Override
    protected void validateObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        MySQLTableBase object = command.getObject();
        if (CommonUtils.isEmpty(object.getName())) {
            throw new DBException("View name cannot be empty");
        }
        if (CommonUtils.isEmpty(((MySQLView) object).getAdditionalInfo().getDefinition())) {
            throw new DBException("View definition cannot be empty");
        }
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return SQLTableManager.BASE_VIEW_NAME;
    }

    @Override
    protected MySQLView createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        MySQLCatalog catalog = (MySQLCatalog) container;
        MySQLView newView = new MySQLView(catalog);
        setNewObjectName(monitor, catalog, newView);
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
        createOrReplaceViewQuery(actions, (MySQLView) command.getObject());
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
    {
        createOrReplaceViewQuery(actionList, (MySQLView) command.getObject());
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        actions.add(
            new SQLDatabasePersistAction("Drop view", "DROP VIEW " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }

    private void createOrReplaceViewQuery(List<DBEPersistAction> actions, MySQLView view)
    {
        StringBuilder decl = new StringBuilder(200);
        final String lineSeparator = GeneralUtils.getDefaultLineSeparator();
        String viewDDL = view.getAdditionalInfo().getDefinition();
        if (viewDDL == null) {
            viewDDL = "";
        }
        
        if (!view.isPersisted() && SQLSemanticProcessor.isSelectQuery(view.getDataSource().getSQLDialect(), viewDDL)) {
            if (view.getDataSource().supportsAlterView()) {
                decl.append("CREATE");
            } else {
                decl.append("CREATE OR REPLACE");
            }
            decl.append(" VIEW ").append(view.getFullyQualifiedName(DBPEvaluationContext.DDL)).append(lineSeparator) //$NON-NLS-1$
                .append("AS "); //$NON-NLS-1$
        }

        final MySQLView.CheckOption checkOption = view.getAdditionalInfo().getCheckOption();
        if (checkOption != null && checkOption != MySQLView.CheckOption.NONE) {
            if (viewDDL.endsWith(";")) {
                viewDDL = viewDDL.substring(0, viewDDL.length() - 1); //$NON-NLS-1$
            }
            decl.append(viewDDL).append(lineSeparator).append("WITH ").append(checkOption.getDefinitionName()).append(" CHECK OPTION"); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            decl.append(viewDDL);
        }
        
        actions.add(new SQLDatabasePersistAction("Create view", decl.toString()) {
            @Override
            public void beforeExecute(@NotNull DBCSession session) throws DBCException {
                MySQLView schemaView;
                try {
                    schemaView = DBUtils.findObject(view.getParentObject().getViews(session.getProgressMonitor()), view.getName());
                } catch (DBException e) {
                    throw new DBCException(e, session.getExecutionContext());
                }
                if (schemaView != view) {
                    throw new DBCException("View with name '" + view.getName() + "' already exists. Choose another name");
                }
                super.beforeExecute(session);
            }
        });
    }

}

