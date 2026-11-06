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

package com.seizia.chromite.ext.postgresql.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.PostgreMessages;
import com.seizia.chromite.ext.postgresql.model.PostgreAttribute;
import com.seizia.chromite.ext.postgresql.model.PostgreTableConstraint;
import com.seizia.chromite.ext.postgresql.model.PostgreTableConstraintColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditConstraintPage;

import java.util.Map;

/**
 * Postgre constraint configurator
 */
public class PostgreConstraintConfigurator implements DBEObjectConfigurator<PostgreTableConstraint> {


    @NotNull
    @Override
    public PostgreTableConstraint configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object parent, @NotNull PostgreTableConstraint constraint, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditConstraintPage editPage = new EditConstraintPage(
                PostgreMessages.edit_constraint_page_add_constraint,
                constraint);
            if (!editPage.edit()) {
                return null;
            }

            constraint.setName(editPage.getConstraintName());
            constraint.setConstraintType(editPage.getConstraintType());
            if (constraint.getConstraintType().isCustom()) {
                constraint.setSource(editPage.getConstraintExpression());
            } else {
                int colIndex = 1;
                for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                    constraint.addColumn(
                        new PostgreTableConstraintColumn(
                            constraint,
                            (PostgreAttribute) tableColumn,
                            colIndex++));
                }
            }
            return constraint;
        });
    }
}
