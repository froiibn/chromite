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
package com.seizia.chromite.ext.mssql.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableColumn;
import com.seizia.chromite.ext.mssql.model.SQLServerTableUniqueKey;
import com.seizia.chromite.ext.mssql.model.SQLServerTableUniqueKeyColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditConstraintPage;

import java.util.Map;

/**
 * SQL server unique constraint configurator
 */
public class SQLServerUniqueKeyConfigurator implements DBEObjectConfigurator<SQLServerTableUniqueKey> {

    @NotNull
    @Override
    public SQLServerTableUniqueKey configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull SQLServerTableUniqueKey primaryKey, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditConstraintPage editPage = new EditConstraintPage(
                "Create constraint",
                primaryKey);
            if (!editPage.edit()) {
                return null;
            }
            primaryKey.setConstraintType(editPage.getConstraintType());
            primaryKey.setName(editPage.getConstraintName());
            int colIndex = 1;
            for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                primaryKey.addColumn(
                    new SQLServerTableUniqueKeyColumn(
                        primaryKey,
                        (SQLServerTableColumn) tableColumn,
                        colIndex++));
            }
            return primaryKey;
        });
    }

}
