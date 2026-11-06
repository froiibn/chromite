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
package com.seizia.chromite.ext.oracle.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleTableColumn;
import com.seizia.chromite.ext.oracle.model.OracleTableConstraint;
import com.seizia.chromite.ext.oracle.model.OracleTableConstraintColumn;
import com.seizia.chromite.ext.oracle.ui.internal.OracleUIMessages;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditConstraintPage;

import java.util.Map;

/**
 * OracleConstraintConfigurator
 */
public class OracleConstraintConfigurator implements DBEObjectConfigurator<OracleTableConstraint> {


    @NotNull
    @Override
    public OracleTableConstraint configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object parent, @NotNull OracleTableConstraint constraint, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditConstraintPage editPage = new EditConstraintPage(
                OracleUIMessages.edit_oracle_constraint_manager_dialog_title,
                constraint);
            if (!editPage.edit()) {
                return null;
            }
            constraint.setName(editPage.getConstraintName());
            constraint.setConstraintType(editPage.getConstraintType());
            constraint.setSearchCondition(editPage.getConstraintExpression());

            int colIndex = 1;
            for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                constraint.addColumn(
                    new OracleTableConstraintColumn(
                        constraint,
                        (OracleTableColumn) tableColumn,
                        colIndex++));
            }

            return constraint;
        });
    }

}
