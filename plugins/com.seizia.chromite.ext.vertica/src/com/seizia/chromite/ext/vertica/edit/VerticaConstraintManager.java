/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.vertica.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.edit.GenericPrimaryKeyManager;
import com.seizia.chromite.ext.vertica.model.VerticaConstraint;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.List;
import java.util.Map;

public class VerticaConstraintManager extends GenericPrimaryKeyManager {

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        VerticaConstraint constraint = (VerticaConstraint) command.getObject();

        if (command.getProperties().containsKey(DBConstants.PROP_ID_ENABLED)) {
            actionList.add(
                new SQLDatabasePersistAction("Alter constraint",
                    "ALTER TABLE " + constraint.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) +
                        " ALTER CONSTRAINT " + DBUtils.getQuotedIdentifier(constraint.getDataSource(), constraint.getName()) + " " +
                        (constraint.isEnabled() ? "ENABLED" : "DISABLED")
                    )
            );
        }

        /*if (command.getProperties().containsKey(DBConstants.PROP_ID_DESCRIPTION)) {
            actionList.add(
                new SQLDatabasePersistAction("Alter constraint description",
                    "COMMENT ON CONSTRAINT " + DBUtils.getQuotedIdentifier(constraint.getDataSource(), constraint.getName()) +
                        " ON " + constraint.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) + " IS " +
                        SQLUtils.quoteString(constraint, CommonUtils.notEmpty(constraint.getDescription()))
                    )
            );
        }*/
        super.addObjectModifyActions(monitor, executionContext, actionList, command, options);
    }
}
