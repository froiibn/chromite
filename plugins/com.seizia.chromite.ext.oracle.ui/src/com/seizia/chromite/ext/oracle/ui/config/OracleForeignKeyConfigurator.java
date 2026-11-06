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
package com.seizia.chromite.ext.oracle.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleTableConstraint;
import com.seizia.chromite.ext.oracle.model.OracleTableForeignKey;
import com.seizia.chromite.ext.oracle.model.OracleTableForeignKeyColumn;
import com.seizia.chromite.ext.oracle.ui.internal.OracleUIMessages;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLForeignKeyManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditForeignKeyPage;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Oracle foreign key manager
 */
public class OracleForeignKeyConfigurator implements DBEObjectConfigurator<OracleTableForeignKey> {

    @Nullable
    @Override
    public OracleTableForeignKey configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object table, @NotNull OracleTableForeignKey foreignKey, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditForeignKeyPage editPage = new EditForeignKeyPage(
                OracleUIMessages.edit_oracle_foreign_key_manager_dialog_title,
                foreignKey,
                new DBSForeignKeyModifyRule[]{
                    DBSForeignKeyModifyRule.NO_ACTION,
                    DBSForeignKeyModifyRule.CASCADE, DBSForeignKeyModifyRule.RESTRICT,
                    DBSForeignKeyModifyRule.SET_NULL,
                    DBSForeignKeyModifyRule.SET_DEFAULT}, options);
            editPage.setSupportsCustomName(true);
            if (!editPage.edit()) {
                return null;
            }

            foreignKey.setReferencedConstraint((OracleTableConstraint) editPage.getUniqueConstraint());
            String customName = editPage.getName();
            if (CommonUtils.isNotEmpty(customName)) {
                foreignKey.setName(customName);
            } else {
                SQLForeignKeyManager.updateForeignKeyName(monitor, foreignKey);
            }
            foreignKey.setDeleteRule(editPage.getOnDeleteRule());
            int colIndex = 1;
            for (EditForeignKeyPage.FKColumnInfo tableColumn : editPage.getColumns()) {
                foreignKey.addColumn(
                    new OracleTableForeignKeyColumn(
                        foreignKey,
                        tableColumn.getOrCreateOwnColumn(monitor, commandContext, foreignKey.getTable()),
                        colIndex++));
            }
            SQLForeignKeyManager.updateForeignKeyName(monitor, foreignKey);
            return foreignKey;
        });
    }

}
