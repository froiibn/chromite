/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.model.impl.sql.edit.struct;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.impl.struct.AbstractTable;
import com.seizia.chromite.model.impl.struct.AbstractTableIndex;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSTableIndexColumn;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * JDBC constraint manager
 */
public abstract class SQLIndexManager<OBJECT_TYPE extends AbstractTableIndex, TABLE_TYPE extends AbstractTable>
    extends SQLObjectEditor<OBJECT_TYPE, TABLE_TYPE>
{

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options)
    {
        final TABLE_TYPE table = (TABLE_TYPE)command.getObject().getTable();
        final OBJECT_TYPE index = command.getObject();

        // Create index
        final String indexName = DBUtils.getQuotedIdentifier(index.getDataSource(), index.getName());
        if (index instanceof DBPNamedObject2) {
            ((DBPNamedObject2) index).setName(indexName);
        } else {
            log.error("Cannot set index name");
        }

        final String tableName = DBUtils.getEntityScriptName(table, options);

        StringBuilder decl = new StringBuilder(40);
        decl.append("CREATE");
        appendIndexModifiers(index, decl);
        decl.append(" INDEX ").append(indexName); //$NON-NLS-1$
        appendIndexType(index, decl);
        decl.append(" ON ").append(tableName); //$NON-NLS-1$
        appendIndexTypeAfterOn(index, decl);
        decl.append(" ("); //$NON-NLS-1$
        try {
            // Get columns using void monitor
            boolean firstColumn = true;
            for (DBSTableIndexColumn indexColumn : CommonUtils.safeCollection(command.getObject().getAttributeReferences(new VoidProgressMonitor()))) {
                if (!firstColumn) decl.append(","); //$NON-NLS-1$
                firstColumn = false;
                decl.append(DBUtils.getQuotedIdentifier(indexColumn));
                appendIndexColumnModifiers(monitor, decl, indexColumn);
            }
        } catch (DBException e) {
            log.error(e);
        }
        decl.append(")"); //$NON-NLS-1$

        actions.add(
            new SQLDatabasePersistAction(ModelMessages.model_jdbc_create_new_index, decl.toString())
        );
    }

    protected void appendIndexType(OBJECT_TYPE index, StringBuilder decl) {

    }

    protected void appendIndexTypeAfterOn(OBJECT_TYPE index, StringBuilder decl) {

    }

    protected void appendIndexModifiers(OBJECT_TYPE index, StringBuilder decl) {
        if (index.isUnique()) {
            decl.append(" UNIQUE");
        }
    }

    protected void appendIndexColumnModifiers(DBRProgressMonitor monitor, StringBuilder decl, DBSTableIndexColumn indexColumn) {
        if (!indexColumn.isAscending()) {
            decl.append(" DESC"); //$NON-NLS-1$
        }
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        actions.add(
            new SQLDatabasePersistAction(
                ModelMessages.model_jdbc_drop_index,
                getDropIndexPattern(command.getObject())
                    .replace(PATTERN_ITEM_TABLE, DBUtils.getObjectFullName(command.getObject().getTable(), DBPEvaluationContext.DDL))
                    .replace(PATTERN_ITEM_INDEX, command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL))
                    .replace(PATTERN_ITEM_INDEX_SHORT, DBUtils.getQuotedIdentifier(command.getObject())))
        );
    }

    protected String getDropIndexPattern(OBJECT_TYPE index)
    {
        return "DROP INDEX " + PATTERN_ITEM_INDEX; //$NON-NLS-1$
    }


}

