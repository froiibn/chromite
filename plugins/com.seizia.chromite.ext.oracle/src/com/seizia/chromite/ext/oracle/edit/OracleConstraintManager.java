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
import com.seizia.chromite.ext.oracle.model.OracleObjectStatus;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ext.oracle.model.OracleTableConstraint;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.DBECommandAbstract;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLConstraintManager;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

import java.util.List;
import java.util.Map;

/**
 * Oracle constraint manager
 */
public class OracleConstraintManager extends SQLConstraintManager<OracleTableConstraint, OracleTableBase> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleTableConstraint> getObjectsCache(OracleTableConstraint object)
    {
        return object.getParentObject().getSchema().getConstraintCache();
    }

    @Override
    protected OracleTableConstraint createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull final Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        OracleTableBase table = (OracleTableBase) container;

        return new OracleTableConstraint(
            table,
            "",
            DBSEntityConstraintType.UNIQUE_KEY,
            null,
            OracleObjectStatus.ENABLED);
    }

    @Override
    protected String getDropConstraintPattern(OracleTableConstraint constraint)
    {
        String clause = "CONSTRAINT"; //$NON-NLS-1$;

        String tableType = constraint.getTable().isView() ? "VIEW" : "TABLE";

        return "ALTER " + tableType + " " + PATTERN_ITEM_TABLE + " DROP " + clause + " " + PATTERN_ITEM_CONSTRAINT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options)
    {
    	OracleTableConstraint constraint = command.getObject();
        boolean isView = constraint.getTable().isView();
        String tableType = isView ? "VIEW" : "TABLE";
    	OracleTableBase table = constraint.getTable();
        actions.add(
                new SQLDatabasePersistAction(
                    ModelMessages.model_jdbc_create_new_constraint,
                    "ALTER " + tableType + " " + table.getFullyQualifiedName(DBPEvaluationContext.DDL) +
                        "\nADD " + getNestedDeclaration(monitor, table, command, options) +
                    " "  + (!isView && constraint.getStatus() == OracleObjectStatus.ENABLED ? "ENABLE" : "DISABLE" ) +
                    (isView ? " NOVALIDATE" : "")
                	));
    }

    @Override
    protected void appendConstraintDefinition(@NotNull StringBuilder decl, @NotNull DBECommandAbstract<OracleTableConstraint> command) {
        if (command.getObject().getConstraintType() == DBSEntityConstraintType.CHECK) {
            decl.append(" (").append((command.getObject()).getSearchCondition()).append(")");
        } else {
            super.appendConstraintDefinition(decl, command);
        }
    }
}
