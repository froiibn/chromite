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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.GenericConstants;
import com.seizia.chromite.ext.generic.model.*;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectManager;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLTableManager;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.navigator.meta.DBXTreeNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Generic table manager
 */
public class GenericTableManager extends SQLTableManager<GenericTableBase, GenericStructContainer> {

    private static final Class<? extends DBSObject>[] CHILD_TYPES = CommonUtils.array(
        GenericTableColumn.class,
        GenericUniqueKey.class,
        GenericTableForeignKey.class,
        GenericTableIndex.class
    );

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, GenericTableBase> getObjectsCache(GenericTableBase object)
    {
        return object.getContainer().getTableCache();
    }

    @NotNull
    @Override
    public Class<? extends DBSObject>[] getChildTypes()
    {
        return CHILD_TYPES;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return super.canCreateObject(container);
    }

    @Override
    protected GenericTableBase createDatabaseObject(@NotNull DBRProgressMonitor monitor, @NotNull DBECommandContext context, @NotNull
    Object container, @Nullable
    Object copyFrom, @NotNull Map<String, Object> options)
    {
        GenericStructContainer structContainer = (GenericStructContainer) container;

        boolean isView = false;
        Object navContainer = options.get(DBEObjectManager.OPTION_CONTAINER);
        if (navContainer instanceof DBNDatabaseFolder) {
            List<DBXTreeNode> folderChildren = ((DBNDatabaseFolder) navContainer).getMeta().getChildren((DBNNode) navContainer);
            if (folderChildren.size() == 1 && folderChildren.get(0) instanceof DBXTreeItem && ((DBXTreeItem) folderChildren.get(0)).getPropertyName().equals("views")) {
                isView = true;
            }
        }
        String tableName = getNewChildName(monitor, structContainer, isView ? BASE_VIEW_NAME : BASE_TABLE_NAME);
        return structContainer.getDataSource().getMetaModel().createTableOrViewImpl(structContainer, tableName,
            isView ? GenericConstants.TABLE_TYPE_VIEW : GenericConstants.TABLE_TYPE_TABLE,
            null);
    }

    @Override
    protected boolean excludeFromDDL(@NotNull NestedObjectCommand command, @NotNull Collection<NestedObjectCommand> orderedCommands) {
        // Filter out indexes for unique constraints (if they have the same name)
        DBPObject object = command.getObject();
        if (object instanceof DBSTableIndex) {
            for (NestedObjectCommand ccom : orderedCommands) {
                if (ccom.getObject() instanceof DBSEntityConstraint &&
                    ccom.getObject() != object &&
                    ((DBSEntityConstraint) ccom.getObject()).getConstraintType().isUnique() &&
                    CommonUtils.equalObjects(
                        ((DBSTableIndex) object).getName(), ((DBSEntityConstraint) ccom.getObject()).getName()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void addObjectExtraActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull NestedObjectCommand<GenericTableBase, PropertyHandler> command, @NotNull Map<String, Object> options) throws DBException {
        GenericTableBase tableBase = command.getObject();
        if (command.hasProperty(DBConstants.PROP_ID_DESCRIPTION)) {
            actions.add(new SQLDatabasePersistAction(
                    "Comment table",
                    "COMMENT ON TABLE " + tableBase.getFullyQualifiedName(DBPEvaluationContext.DDL) +
                            " IS " + SQLUtils.quoteString(tableBase, CommonUtils.notEmpty(tableBase.getDescription()))));
        }

        if (!tableBase.isPersisted()) {
            // Column comments for the newly created table
            for (GenericTableColumn column : CommonUtils.safeCollection(tableBase.getAttributes(monitor))) {
                if (!CommonUtils.isEmpty(column.getDescription())) {
                    GenericTableColumnManager.addColumnCommentAction(actions, column, column.getTable());
                }
            }
        }
    }

}
