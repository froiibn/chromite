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
package com.seizia.chromite.ext.h2.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.edit.GenericProcedureManager;
import com.seizia.chromite.ext.generic.model.GenericProcedure;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class H2RoutineAliasManager extends GenericProcedureManager {

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        GenericProcedure object = command.getObject();
        if (command.getProperties().containsKey(DBConstants.PROP_ID_DESCRIPTION)) {
            actionList.add(
                new SQLDatabasePersistAction("Alter routine alias description",
                    "COMMENT ON ALIAS " + object.getFullyQualifiedName(DBPEvaluationContext.DDL) +
                        " IS " + SQLUtils.quoteString(object, CommonUtils.notEmpty(object.getDescription()))
                )
            );
        }
        super.addObjectModifyActions(monitor, executionContext, actionList, command, options);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        actions.add(
            new SQLDatabasePersistAction("Drop alias", "DROP ALIAS " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }
}
