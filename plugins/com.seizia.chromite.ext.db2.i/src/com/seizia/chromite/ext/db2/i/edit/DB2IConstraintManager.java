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
package com.seizia.chromite.ext.db2.i.edit;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.db2.i.model.DB2IConstraint;
import com.seizia.chromite.ext.generic.edit.GenericPrimaryKeyManager;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericUniqueKey;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;

import java.util.List;
import java.util.Map;

public class DB2IConstraintManager extends GenericPrimaryKeyManager {

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) {
        GenericUniqueKey key = command.getObject();
        GenericStructContainer container = key.getParentObject().getParentObject();
        if (key.getConstraintType() == DBSEntityConstraintType.CHECK && key instanceof DB2IConstraint && container != null) {
            DB2IConstraint constraint = (DB2IConstraint) key;
            actions.add(
                new SQLDatabasePersistAction("Create check constraint",
                    "ALTER TABLE " + constraint.getParentObject().getFullyQualifiedName(DBPEvaluationContext.DDL) +
                        " ADD CONSTRAINT " + DBUtils.getFullyQualifiedName(constraint.getDataSource(), container.getName(), constraint.getName()) +
                        " CHECK (" + constraint.getCheckConstraintDefinition() + ")"
                ));
        } else {
            super.addObjectCreateActions(monitor, executionContext, actions, command, options);
        }
    }
}
