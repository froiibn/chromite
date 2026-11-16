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
package com.seizia.chromite.ext.cubrid.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.cubrid.model.CubridDataSource;
import com.seizia.chromite.ext.cubrid.model.CubridPrivilage;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistActionAtomic;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * CUBRID user manager: CREATE/DROP USER.
 *
 * 설계: 암호가 든 CREATE는 쿼리 관리자 기록에서 뺀다({@code setDisableSessionLogging}, PostgreSQL 역할·SQL Server 로그인 관리자와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 암호가 든 CREATE의 기록 끄기 (docs/chromite/password-handling.md)
 */
public class CubridUserManager
    extends SQLObjectEditor<CubridPrivilage, GenericStructContainer> /*implements DBEObjectRenamer<OracleSchema>*/ {

    private static final int MAX_NAME_GEN_ATTEMPTS = 100;

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, CubridPrivilage> getObjectsCache(CubridPrivilage object) {
        DBSObject parentObject = object.getParentObject();
        if (parentObject instanceof CubridDataSource container) {
            return container.getDataSource().getCubridPrivilageCache();
        }
        return null;
    }

    @Override
    protected CubridPrivilage createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        String newName = this.getNewName((CubridDataSource) container, "NEW_USER");
        return new CubridPrivilage((CubridDataSource) container, newName, null);
    }


    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        CubridPrivilage user = command.getObject();
        StringBuilder builder = new StringBuilder();
        builder.append("CREATE USER ");
        builder.append(DBUtils.getQuotedIdentifier(user.getDataSource(), this.getUserName(user, command.getProperties())));
        if (buildBody(user, builder, command.getProperties())) {
            command.setDisableSessionLogging(true); // Hide password from Query Manager
        }
        actions.add(new SQLDatabasePersistAction("Create User", builder.toString()));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        String builder = "DROP USER " + DBUtils.getQuotedIdentifier(command.getObject());
        actions.add(new DeletePersistAction(command.getObject(), builder));
    }

    /**
     * Appends the PASSWORD, GROUPS and COMMENT clauses.
     *
     * @return whether a password was written (the statement must then stay out of the Query Manager log)
     */
    private boolean buildBody(CubridPrivilage user, StringBuilder builder, Map<Object, Object> properties) {
        Object password = properties.get("PASSWORD");
        Object description = properties.get("DESCRIPTION");
        Object group = properties.get("GROUPS");
        boolean hasPassword = password != null && CommonUtils.isNotEmpty(password.toString());
        if (hasPassword) {
            builder.append(" PASSWORD ");
            builder.append(SQLUtils.quoteString(user, password.toString()));
        }
        if (group != null && !CommonUtils.isEmpty((List<String>) properties.get("GROUPS"))) {
            builder.append(" GROUPS ");
            List<String> groups = (List<String>) properties.get("GROUPS");
            List<String> quotedGroups = groups.stream()
                .map(quoteGroup -> DBUtils.getQuotedIdentifier(user.getDataSource(), quoteGroup))
                .toList();
            builder.append(String.join(", ", quotedGroups));
        }

        if (description != null && CommonUtils.isNotEmpty(description.toString())) {
            builder.append(" COMMENT ");
            builder.append(SQLUtils.quoteString(user, description.toString()));
        }
        return hasPassword;
    }

    @NotNull
    private String getUserName(CubridPrivilage user, Map<Object, Object> properties) {
        Object name = properties.get("NAME");
        if (name != null) {
            user.setName(name.toString().toUpperCase());
        }
        return user.getName();

    }

    private static class DeletePersistAction extends SQLDatabasePersistActionAtomic {
        CubridPrivilage database;
        public DeletePersistAction(CubridPrivilage privilage, String script) {
            super("drop user", script);
            this.database = privilage;
        }

        @Override
        public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) throws DBCException {
            super.afterExecute(session, error);
            if (error == null) {
                GenericSchema user = this.database.getParentObject().getSchema(database.getName());
                DBUtils.fireObjectRemove(user);
            }
        }
    }

    @NotNull
    private String getNewName(@NotNull CubridDataSource container, @NotNull String baseName) {
        for (int i = 0; i < MAX_NAME_GEN_ATTEMPTS; i++) {
            String transform = DBObjectNameCaseTransformer.transformName(
                container.getDataSource(),
                i == 0 ? baseName : (baseName + "_" + i)
            );
            DBSObject child = container.getCubridPrivilageCache().getCachedObject(transform);
            if (child == null) {
                return transform;
            }
        }
        log.error("Error generating child object name: max attempts reached");
        return baseName;

    }

    public boolean canCreateObject(@NotNull Object container) {
        return !((CubridDataSource) container).isShard();
    }

    @Override
    public boolean canEditObject(@NotNull CubridPrivilage object) {
        return !object.getDataSource().isShard();
    }

    @Override
    public boolean canDeleteObject(@NotNull CubridPrivilage object) {
        return !object.getDataSource().isShard();
    }
}
