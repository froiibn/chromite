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
package com.seizia.chromite.ext.cubrid.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.cubrid.model.CubridUniqueKey;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.ext.generic.model.GenericTableConstraintColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTableCheckConstraint;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.ConstraintNameGenerator;
import com.seizia.chromite.ui.editors.object.struct.EditConstraintPage;

import java.lang.reflect.Field;
import java.util.Map;

public class CubridTableUniqueKeyConfigurator implements DBEObjectConfigurator<CubridUniqueKey> {
    private static final Log log = Log.getLog(CubridTableUniqueKeyConfigurator.class);

    @NotNull
    @Override
    public CubridUniqueKey configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object table, @NotNull CubridUniqueKey primaryKey, @NotNull Map<String, Object> options) {
        return new UITask<CubridUniqueKey>() {
            @Override
            protected CubridUniqueKey runTask() {
            	EditConstraintPage editPage = new EditConstraintPage(
                    "Create unique constraint",
                    primaryKey);

            	try {
                    Field nameGenField = EditConstraintPage.class.getDeclaredField("nameGenerator");
                    nameGenField.setAccessible(true);
                    nameGenField.set(editPage, new CubridConstraintNameGenerator(
                        primaryKey.getParentObject(),
                        primaryKey.isPersisted() ? primaryKey.getName() : null,
                        primaryKey.getConstraintType()
                    ));
                } catch (Exception e) {
                    log.error(e);
                }

                if (!editPage.edit()) {
                    return null;
                }

                primaryKey.setConstraintType(editPage.getConstraintType());
                primaryKey.setName(editPage.getConstraintName());
                if (primaryKey instanceof DBSTableCheckConstraint checkConstraint) {
                    checkConstraint.setCheckConstraintDefinition(editPage.getConstraintExpression());
                }
                int colIndex = 1;
                for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                    primaryKey.addColumn(
                        new GenericTableConstraintColumn(
                            primaryKey,
                            (GenericTableColumn) tableColumn,
                            colIndex++));
                }
                return primaryKey;
            }
        }.execute();
    }

   class CubridConstraintNameGenerator extends ConstraintNameGenerator {

       public CubridConstraintNameGenerator(
                DBSEntity entity,
                String constraintName,
                DBSEntityConstraintType constraintType) {
            super(entity, constraintName, constraintType);
        }

        @Override
        public String getConstraintName() {
            return super.getConstraintName() != null ? super.getConstraintName().toLowerCase() : null;
        }
    }

}
