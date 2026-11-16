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
package com.seizia.chromite.ext.postgresql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.internal.PostgreSQLMessages;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistActionAtomic;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * PostgreDatabaseManager
 *
 * 변경 이력:
 * - 2026-11-08 CREATE DATABASE에 LC_COLLATE·LC_CTYPE: 새 데이터베이스 대화상자가 고른 값({@link PostgreDatabase#setCollate},
 *   {@link PostgreDatabase#setCtype}). 비어 있으면 템플릿의 값을 따른다 (docs/chromite/swing-create-objects.md)
 */
public class PostgreDatabaseManager extends SQLObjectEditor<PostgreDatabase, PostgreDataSource> implements DBEObjectRenamer<PostgreDatabase> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<PostgreDataSource, PostgreDatabase> getObjectsCache(PostgreDatabase object) {
        return object.getDataSource().getDatabaseCache();
    }

    @Override
    public void deleteObject(@NotNull DBECommandContext commandContext, @NotNull PostgreDatabase object, @NotNull Map<String, Object> options) throws DBException {
        if (object == object.getDataSource().getDefaultInstance()) {
            throw new DBException("Cannot drop the currently open database." +
                "\nSwitch to another database and try again\n(Note: enable 'Show all databases' option to see them).");
        }
        super.deleteObject(commandContext, object, options);
    }

    @Override
    protected PostgreDatabase createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        return ((PostgreDataSource) container).createDatabaseImpl(monitor, "NewDatabase", null, null, null, null);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        final PostgreDatabase database = command.getObject();
        verifyCanCreateDB(database);
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE ").append(DBUtils.getQuotedIdentifier(database));

        if (database.getInitialOwner() != null) {
            sql.append("\nOWNER = ").append(DBUtils.getQuotedIdentifier(database.getInitialOwner()));
        }
        if (!CommonUtils.isEmpty(database.getTemplateName())) {
            sql.append("\nTEMPLATE = ").append(DBUtils.getQuotedIdentifier(database.getDataSource(), database.getTemplateName()));
        }
        if (database.getInitialEncoding() != null) {
            sql.append("\nENCODING = '").append(database.getInitialEncoding().getName()).append("'");
        }
        if (!CommonUtils.isEmpty(database.getCollate())) {
            sql.append("\nLC_COLLATE = ").append(SQLUtils.quoteString(database, database.getCollate()));
        }
        if (!CommonUtils.isEmpty(database.getCtype())) {
            sql.append("\nLC_CTYPE = ").append(SQLUtils.quoteString(database, database.getCtype()));
        }
        if (database.getInitialTablespace() != null) {
            sql.append("\nTABLESPACE = ").append(DBUtils.getQuotedIdentifier(database.getDataSource(), database.getInitialTablespace().getName()));
        }
        actions.add(new CreateDatabaseAction(database, sql));
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        actions.add(new DeleteDatabaseAction(command));
    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext, @NotNull PostgreDatabase database, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
        processObjectRename(commandContext, database, options, newName);
    }

    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options) {
        actions.add(
            new SQLDatabasePersistAction(
                "Rename database",
                "ALTER DATABASE " + DBUtils.getQuotedIdentifier(command.getObject().getDataSource(), command.getOldName()) + //$NON-NLS-1$
                    " RENAME TO " + DBUtils.getQuotedIdentifier(command.getObject().getDataSource(), command.getNewName())) //$NON-NLS-1$
        );
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) {
        if (command.getProperties().size() > 1 || command.getProperty(DBConstants.PROP_ID_DESCRIPTION) == null) {
            try {
                generateAlterActions(monitor, actionList, command);
            } catch (DBException e) {
                log.error(e);
            }
        }
    }

    private void generateAlterActions(DBRProgressMonitor monitor, List<DBEPersistAction> actionList, ObjectChangeCommand command) throws DBException {
        final PostgreDatabase database = command.getObject();
        final String alterPrefix = "ALTER DATABASE " + DBUtils.getQuotedIdentifier(command.getObject()) + " ";
        if (command.hasProperty("defaultTablespace")) {
            actionList.add(new SQLDatabasePersistAction(alterPrefix + "SET TABLESPACE " + DBUtils.getQuotedIdentifier(database.getDefaultTablespace(monitor))));
        }
        if (command.hasProperty("defaultEncoding")) {
            actionList.add(new SQLDatabasePersistAction(alterPrefix + "SET ENCODING " + DBUtils.getQuotedIdentifier(database.getDefaultEncoding(monitor))));
        }
        if (command.hasProperty("dBA")) {
            actionList.add(new SQLDatabasePersistAction(alterPrefix + "OWNER TO " + DBUtils.getQuotedIdentifier(database.getDBA(monitor))));
        }
    }

    @Override
    protected void validateObjectProperties(@NotNull DBRProgressMonitor monitor, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        super.validateObjectProperties(monitor, command, options);
        options.put(DBECommandContext.OPTION_AVOID_TRANSACTIONS, true);
    }

    private static class DeleteDatabaseAction extends SQLDatabasePersistActionAtomic {

        private final PostgreDatabase database;

        public DeleteDatabaseAction(ObjectDeleteCommand command) {
            super("Drop database", "DROP DATABASE " + DBUtils.getQuotedIdentifier(command.getObject()));
            database = command.getObject();
        }

        @Override
        public void beforeExecute(@NotNull DBCSession session) throws DBCException {
            super.beforeExecute(session);
            database.shutdown(session.getProgressMonitor());
        }
    }

    private static class CreateDatabaseAction extends SQLDatabasePersistActionAtomic {
        private final PostgreDatabase database;

        public CreateDatabaseAction(PostgreDatabase database, StringBuilder sql) {
            super("Create database", sql.toString());
            this.database = database;
        }

        @Override
        public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) throws DBCException {
            super.afterExecute(session, error);
            if (error == null) {
                try {
                    database.checkInstanceConnection(session.getProgressMonitor());
                } catch (DBException e) {
                    log.error("Can't connect to the new database");
                }
            }
        }
    }

    @Override
    protected void addObjectExtraActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull NestedObjectCommand<PostgreDatabase, PropertyHandler> command, @NotNull Map<String, Object> options) throws DBException {
        if (command.hasProperty(DBConstants.PROP_ID_DESCRIPTION)) {
            PostgreDatabase database = command.getObject();
            actions.add(new SQLDatabasePersistAction("COMMENT ON DATABASE " + DBUtils.getQuotedIdentifier(database) +
                " IS " + SQLUtils.quoteString(database, CommonUtils.notEmpty(database.getDescription()))));
        }
    }

    /*
     * Throws if we cannot create DB because of connection settings
     */
    private void verifyCanCreateDB(@NotNull PostgreDatabase database) throws DBException {
        DBPConnectionConfiguration configuration = database.getDataSource().getContainer().getActualConnectionConfiguration();
        if (!isReadDatabaseList(configuration)) {
            throw new DBException(PostgreSQLMessages.error_multi_database_mode_disabled_description);
        }
    }

    private boolean isReadDatabaseList(@NotNull DBPConnectionConfiguration configuration) {
        return CommonUtils.getBoolean(configuration.getProviderProperty(PostgreConstants.PROP_SHOW_NON_DEFAULT_DB), false);
    }

}

