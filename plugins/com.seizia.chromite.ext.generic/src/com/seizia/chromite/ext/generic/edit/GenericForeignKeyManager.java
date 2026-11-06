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
package com.seizia.chromite.ext.generic.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableForeignKey;
import com.seizia.chromite.ext.generic.model.GenericUtils;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.impl.edit.DBECommandAbstract;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLForeignKeyManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyDeferability;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Generic foreign manager
 */
public class GenericForeignKeyManager extends SQLForeignKeyManager<GenericTableForeignKey, GenericTableBase> {

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, GenericTableForeignKey> getObjectsCache(GenericTableForeignKey object)
    {
        return object.getParentObject().getContainer().getForeignKeysCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return (container instanceof GenericTable)
            && ((GenericTable) container).getDataSource().getInfo().supportsReferentialIntegrity()
            && GenericUtils.canAlterTable((GenericTable) container);
    }

    @Override
    public boolean canEditObject(@NotNull GenericTableForeignKey object) {
        return GenericUtils.canAlterTable(object);
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableForeignKey object) {
        return GenericUtils.canAlterTable(object);
    }

    @Override
    protected GenericTableForeignKey createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options
    ) {
        GenericTableBase tableBase = (GenericTableBase)container;
        GenericTableForeignKey foreignKey = tableBase.getDataSource().getMetaModel().createTableForeignKeyImpl(
            tableBase,
            null,
            null,
            null,
            DBSForeignKeyModifyRule.NO_ACTION,
            DBSForeignKeyModifyRule.NO_ACTION,
            DBSForeignKeyDeferability.NOT_DEFERRABLE,
            false);
        foreignKey.setName(getNewConstraintName(monitor, foreignKey));
        return foreignKey;
    }

    @Override
    protected StringBuilder getNestedDeclaration(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase owner, @NotNull
    DBECommandAbstract<GenericTableForeignKey> command, @NotNull Map<String, Object> options) {
        if (options.get(DBPScriptObject.OPTION_COMPOSITE_OBJECT) instanceof DBSEntity &&
            !owner.getDataSource().getMetaModel().supportNestedForeignKeys()
        ) {
            return null;
        }
        return super.getNestedDeclaration(monitor, owner, command, options);
    }

    @Override
    protected boolean isLegacyForeignKeySyntax(GenericTableBase owner) {
        return GenericUtils.isLegacySQLDialect(owner);
    }

    @Override
    protected void appendUpdateDeleteRule(GenericTableForeignKey foreignKey, StringBuilder decl) {
        String onDeleteRule = foreignKey.getDataSource().getMetaModel().generateOnDeleteFK(foreignKey.getDeleteRule());
        if (!CommonUtils.isEmpty(onDeleteRule)) {
            decl.append(" ").append(onDeleteRule);
        }

        String onUpdateFK = foreignKey.getDataSource().getMetaModel().generateOnUpdateFK(foreignKey.getUpdateRule());
        if (!CommonUtils.isEmpty(onUpdateFK)) {
            decl.append(" ").append(onUpdateFK);
        }
    }

    @Override
    protected boolean isFKConstraintDuplicated(GenericTableBase owner) {
        return owner.getDataSource().getMetaModel().isFKConstraintWordDuplicated();
    }
}
