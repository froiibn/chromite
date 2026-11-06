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
package com.seizia.chromite.ext.clickhouse.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.clickhouse.model.ClickhouseTable;
import com.seizia.chromite.ext.generic.edit.GenericTableManager;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * Clickhouse table manager
 */
public class ClickhouseTableManager extends GenericTableManager implements DBEObjectRenamer<GenericTableBase> {

    private static final Log log = Log.getLog(ClickhouseTableManager.class);

    @Override
    protected String getDropTableType(GenericTableBase table) {
        // Both tables and views must be deleted with DROP TABLE
        return "TABLE";
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull GenericTableBase object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        GenericTableBase table = command.getObject();
        GenericDataSource dataSource = table.getDataSource();
        GenericStructContainer container = table.getParentObject();
        if (container != null) {
            actions.add(new SQLDatabasePersistAction(
                "Rename table",
                "RENAME TABLE " + DBUtils.getFullyQualifiedName(dataSource, container.getName(), command.getOldName()) +
                    " TO " + DBUtils.getFullyQualifiedName(dataSource, container.getName(), command.getNewName())));
        }
    }

    @Override
    protected void appendTableModifiers(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase table,
        @NotNull NestedObjectCommand tableProps,
        @NotNull StringBuilder ddl,
        boolean alter,
        @NotNull Map<String, Object> options) {
        if (table instanceof ClickhouseTable) {
            String delimiter = getDelimiter(options);
            ClickhouseTable clickhouseTable = (ClickhouseTable) table;
            if (clickhouseTable.getEngine() != null) {
                ddl.append(" ENGINE = ").append(clickhouseTable.getEngine().getName());
                if (CommonUtils.isNotEmpty(clickhouseTable.getEngineMessage())) {
                    ddl.append(delimiter).append(clickhouseTable.getEngineMessage());
                }
            } else {
                try {
                    List<? extends GenericTableColumn> attributes = table.getAttributes(monitor);
                    if (!CommonUtils.isEmpty(attributes)) {
                        ddl.append(" ENGINE = MergeTree()")
                            .append(delimiter)
                            .append("ORDER BY ").append(DBUtils.getQuotedIdentifier(attributes.get(0)));
                    } else {
                        ddl.append(" ENGINE = Log");
                    }
                } catch (DBException e) {
                    log.debug("Can't read " + table.getName() + " columns");
                }
            }
            if (!table.isPersisted() && tableProps.getProperty(DBConstants.PROP_ID_DESCRIPTION) != null
                && CommonUtils.isNotEmpty(table.getDescription())) {
                ddl.append(delimiter).append("COMMENT ").append(SQLUtils.quoteString(table, table.getDescription())); //$NON-NLS-1$
            }
        }
    }

    @Override
    protected void addObjectExtraActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull NestedObjectCommand<GenericTableBase, PropertyHandler> command,
        @NotNull Map<String, Object> options)
    {
        GenericTableBase tableBase = command.getObject();
        if (tableBase.isPersisted() && command.hasProperty(DBConstants.PROP_ID_DESCRIPTION)) {
            actions.add(new SQLDatabasePersistAction(
                "Comment table",
                "ALTER TABLE " + tableBase.getFullyQualifiedName(DBPEvaluationContext.DDL)
                    + " MODIFY COMMENT "
                    + SQLUtils.quoteString(tableBase, CommonUtils.notEmpty(tableBase.getDescription()))));
        }
    }
}
