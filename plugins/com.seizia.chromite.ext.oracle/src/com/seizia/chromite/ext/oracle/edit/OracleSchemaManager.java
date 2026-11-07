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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleUser;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * OracleSchemaManager
 *
 * 설계: 새 스키마(= 사용자)의 암호는 {@link OracleUtils#quotePassword}로 넣고, 암호가 든 CREATE는 쿼리 관리자 기록에서 뺀다
 * ({@code setDisableSessionLogging}, PostgreSQL 역할·SQL Server 로그인 관리자와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 암호 따옴표 처리(큰따옴표가 든 암호는 거절)와 기록 끄기 (docs/chromite/password-handling.md)
 */
public class OracleSchemaManager extends SQLObjectEditor<OracleSchema, OracleDataSource> /*implements DBEObjectRenamer<OracleSchema>*/ {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleSchema> getObjectsCache(OracleSchema object) {
        return object.getDataSource().schemaCache;
    }

    @Override
    protected OracleSchema createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return createSchema((OracleDataSource) container);
    }

    @NotNull
    protected OracleSchema createSchema(@NotNull OracleDataSource dataSource) {
        return new OracleSchema(dataSource, -1, "NEW_SCHEMA");
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        OracleUser user = command.getObject().getUser();
        String sql = "CREATE USER " + DBUtils.getQuotedIdentifier(user);
        if (!CommonUtils.isEmpty(user.getPassword())) {
            sql += " IDENTIFIED BY " + OracleUtils.quotePassword(user.getPassword());
            command.setDisableSessionLogging(true); // Hide password from Query Manager
        }

        actions.add(new SQLDatabasePersistAction("Create schema", sql));
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        actions.add(
            new SQLDatabasePersistAction("Drop schema",
                "DROP USER " + DBUtils.getQuotedIdentifier(command.getObject()) + " CASCADE") //$NON-NLS-2$
        );
    }

/*
    @Override
    public void renameObject(DBECommandContext commandContext, OracleSchema schema, String newName) throws DBException {
        throw new DBException("Direct database rename is not yet implemented in Oracle. You should use export/import functions for that.");
    }
*/

}

