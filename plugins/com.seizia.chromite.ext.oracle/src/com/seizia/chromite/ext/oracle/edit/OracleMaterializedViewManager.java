/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OracleDDLFormat;
import com.seizia.chromite.ext.oracle.model.OracleMaterializedView;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleTableColumn;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.edit.prop.DBECommandComposite;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLTableManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * OracleMaterializedViewManager
 */
public class OracleMaterializedViewManager extends SQLObjectEditor<OracleMaterializedView, OracleSchema> {

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
            throw new DBException("View name cannot be empty"); //$NON-NLS-1$
        }
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleMaterializedView> getObjectsCache(OracleMaterializedView object)
    {
        return (DBSObjectCache) object.getSchema().getTableCache();
    }

    @Override
    protected OracleMaterializedView createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        OracleSchema schema = (OracleSchema) container;
        OracleMaterializedView newView = createMaterializedView(schema);
        setNewObjectName(monitor, schema, newView);
        newView.setObjectDefinitionText("SELECT 1 FROM DUAL");
        newView.setCurrentDDLFormat(OracleDDLFormat.COMPACT);
        return newView;
    }

    @NotNull
    protected OracleMaterializedView createMaterializedView(@NotNull OracleSchema schema) {
        return new OracleMaterializedView(schema, "NEW_MVIEW"); //$NON-NLS-1$
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return SQLTableManager.BASE_MATERIALIZED_VIEW_NAME;
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        createOrReplaceViewQuery(monitor, actions, command, options);
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        createOrReplaceViewQuery(monitor, actionList, command, options);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        actions.add(
            new SQLDatabasePersistAction("Drop view", "DROP MATERIALIZED VIEW " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }

    private void createOrReplaceViewQuery(DBRProgressMonitor monitor, List<DBEPersistAction> actions, DBECommandComposite<OracleMaterializedView, PropertyHandler> command, Map<String, Object> options) throws DBException {
        OracleMaterializedView view = command.getObject();

        StringBuilder decl = new StringBuilder(200);
        final String lineSeparator = GeneralUtils.getDefaultLineSeparator();
        boolean hasComment = command.hasProperty("comment");
        if (!hasComment || command.getProperties().size() > 1) {
            String mViewDefinition = view.getMViewText().trim();
            if (mViewDefinition.contains("CREATE MATERIALIZED VIEW")) {
                if (mViewDefinition.endsWith(";")) mViewDefinition = mViewDefinition.substring(0, mViewDefinition.length() - 1);
                decl.append(mViewDefinition);
            } else {
                decl.append("CREATE MATERIALIZED VIEW ").append(view.getFullyQualifiedName(DBPEvaluationContext.DDL)).append(lineSeparator) //$NON-NLS-1$
                    .append("AS ").append(mViewDefinition); //$NON-NLS-1$
            }
            if (view.isPersisted()) {
                actions.add(
                    new SQLDatabasePersistAction("Drop view", "DROP MATERIALIZED VIEW " + view.getFullyQualifiedName(DBPEvaluationContext.DDL))); //$NON-NLS-2$
            }
            List<SQLScriptElement> sqlScriptElements = SQLScriptParser.parseScript(view.getDataSource(), mViewDefinition);
            if (sqlScriptElements.size() > 1) {
                // In this case we already have and view definition, and view/columns comments
                for (SQLScriptElement scriptElement : sqlScriptElements) {
                    actions.add(new SQLDatabasePersistAction("Create view part", scriptElement.getText()));
                }
                return;
            }
            actions.add(
                new SQLDatabasePersistAction("Create view", decl.toString()));
        }

        if (hasComment || (CommonUtils.getOption(options, DBPScriptObject.OPTION_OBJECT_SAVE) && CommonUtils.isNotEmpty(view.getComment()))) {
            actions.add(new SQLDatabasePersistAction(
                "Comment view",
                "COMMENT ON MATERIALIZED VIEW " + view.getFullyQualifiedName(DBPEvaluationContext.DDL) +
                    " IS " + SQLUtils.quoteString(view.getDataSource(), CommonUtils.notEmpty(view.getComment()))));
        }

        if (!(hasComment && command.getProperties().size() == 1) && CommonUtils.getOption(options, DBPScriptObject.OPTION_OBJECT_SAVE)) { // Add column comments only in save case
            // Column comments for the newly created table
            for (OracleTableColumn column : CommonUtils.safeCollection(view.getAttributes(monitor))) {
                if (!CommonUtils.isEmpty(column.getComment(monitor))) {
                    OracleTableColumnManager.addColumnCommentAction(actions, column, view);
                }
            }
        }
    }

}

