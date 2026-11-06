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
package com.seizia.chromite.ext.exasol.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.exasol.model.ExasolTableKeyColumn;
import com.seizia.chromite.ext.exasol.model.ExasolTableUniqueKey;
import com.seizia.chromite.ext.exasol.ui.internal.ExasolMessages;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditConstraintPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExasolPrimaryKeyConfigurator implements DBEObjectConfigurator<ExasolTableUniqueKey> {
    protected static final Log log = Log.getLog(ExasolPrimaryKeyConfigurator.class);

    @NotNull
    @Override
    public ExasolTableUniqueKey configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull ExasolTableUniqueKey constraint, @NotNull Map<String, Object> options) {
        return new UITask<ExasolTableUniqueKey>() {
            @Override
            protected ExasolTableUniqueKey runTask()
            {
                EditConstraintPage editPage = new EditConstraintPage(
                        ExasolMessages.edit_exasol_constraint_manager_dialog_title,
                        constraint);
                if (!editPage.edit()) {
                    return null;
                }

                constraint.setConstraintType(editPage.getConstraintType());
                constraint.setEnabled(editPage.isEnableConstraint());
                constraint.setName(editPage.getConstraintName());

                List<ExasolTableKeyColumn> constColumns = new ArrayList<ExasolTableKeyColumn>();
                int ordinalPosition = 0;
                for(DBSEntityAttribute tableColumn  : editPage.getSelectedAttributes())
                {
                    ExasolTableKeyColumn col;
                    try {
                        col = new ExasolTableKeyColumn(constraint, constraint.getTable().getAttribute(monitor, tableColumn.getName()), ++ordinalPosition);
                    } catch (DBException e) {
                        log.error("Could not find column " + tableColumn.getName() + " in table " + constraint.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL));
                        return null;
                    }
                    constColumns.add(col);
                }
                constraint.setAttributeReferences(constColumns);
                return constraint;
            }
        }.execute();
    }
}
