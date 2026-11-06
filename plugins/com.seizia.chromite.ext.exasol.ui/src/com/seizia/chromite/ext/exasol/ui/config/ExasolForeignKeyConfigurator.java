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
package com.seizia.chromite.ext.exasol.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.exasol.model.ExasolTable;
import com.seizia.chromite.ext.exasol.model.ExasolTableForeignKey;
import com.seizia.chromite.ext.exasol.model.ExasolTableForeignKeyColumn;
import com.seizia.chromite.ext.exasol.model.ExasolTableUniqueKey;
import com.seizia.chromite.ext.exasol.ui.internal.ExasolMessages;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLForeignKeyManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExasolForeignKeyConfigurator implements DBEObjectConfigurator<ExasolTableForeignKey> {
    protected static final Log log = Log.getLog(ExasolForeignKeyConfigurator.class);

    @NotNull
    @Override
    public ExasolTableForeignKey configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull ExasolTableForeignKey foreignKey, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            ExasolCreateForeignKeyDialog editPage = new ExasolCreateForeignKeyDialog(ExasolMessages.dialog_create_foreign_key_title, foreignKey, options);

            if (!editPage.edit()) {
                return null;
            }
            foreignKey.setName(editPage.getName());
            foreignKey.setReferencedConstraint((ExasolTableUniqueKey)editPage.getUniqueConstraint());
            foreignKey.setEnabled(editPage.isEnabled());

            List<ExasolTableForeignKeyColumn> columns = new ArrayList<>();
            int cnt = 0;
            for (ExasolCreateForeignKeyDialog.FKColumnInfo column : editPage.getColumns()) {
                try {
                    ExasolTable refTable = foreignKey.getReferencedConstraint().getTable();
                    columns.add(new ExasolTableForeignKeyColumn(
                            foreignKey,
                            column.getOrCreateOwnColumn(monitor, commandContext, foreignKey.getTable()),
                            refTable.getAttribute(monitor, column.getRefColumn().getName()),
                            ++cnt));
                } catch (DBException e) {
                    log.error("Could not get Attribute Information from Table");
                    return null;
                }
            }

            foreignKey.setAttributeReferences(columns);
            SQLForeignKeyManager.updateForeignKeyName(monitor, foreignKey);

            return foreignKey;
        });
    }
}
