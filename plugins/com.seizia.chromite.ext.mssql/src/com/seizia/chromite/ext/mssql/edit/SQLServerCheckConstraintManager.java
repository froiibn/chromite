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
import com.seizia.chromite.ext.mssql.model.SQLServerTable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableCheckConstraint;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * SQL server unique constraint manager
 */
public class SQLServerCheckConstraintManager extends SQLObjectEditor<SQLServerTableCheckConstraint, SQLServerTable> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, SQLServerTableCheckConstraint> getObjectsCache(SQLServerTableCheckConstraint object) {
        return object.getParentObject().getCheckConstraintCache();
    }

    @Override
    protected SQLServerTableCheckConstraint createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        return new SQLServerTableCheckConstraint((SQLServerTable) container);
    }

    @Override
    protected void validateObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        SQLServerTableCheckConstraint object = command.getObject();
        if (object.getConstraintType() == DBSEntityConstraintType.CHECK && CommonUtils.isEmpty(object.getCheckConstraintDefinition())) {
            throw new DBException("CHECK constraint definition is empty");
        }
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        final SQLServerTableCheckConstraint constraint = command.getObject();

        actions.add(
            new SQLDatabasePersistAction(
                ModelMessages.model_jdbc_create_new_constraint,
                "ALTER TABLE " + constraint.getParentObject().getFullyQualifiedName(DBPEvaluationContext.DDL) +
                    " WITH NOCHECK" +
                    " ADD CONSTRAINT " + DBUtils.getQuotedIdentifier(constraint) +
                    " CHECK (" + constraint.getCheckConstraintDefinition() + ")"
            ));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        final SQLServerTableCheckConstraint constraint = command.getObject();
        actions.add(
            new SQLDatabasePersistAction(
                ModelMessages.model_jdbc_drop_constraint,
                "ALTER TABLE " + constraint.getParentObject().getFullyQualifiedName(DBPEvaluationContext.DDL) +
                    " DROP CONSTRAINT " + DBUtils.getQuotedIdentifier(constraint)
            ));
    }

}
