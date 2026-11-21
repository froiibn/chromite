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
package com.seizia.chromite.ext.altibase.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.altibase.model.AltibaseQueue;
import com.seizia.chromite.ext.generic.edit.GenericTableColumnManager;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.List;
import java.util.Map;

/**
 * Column manager of Altibase-family tables.
 *
 * 설계: 큐의 열은 서버가 메시지 크기로 만드는 큐의 내부 구조라 더하거나 바꾸거나 이름을 바꾸거나 지울 수 없다(전에는 일반 테이블처럼
 * New Column…, Rename, Delete가 보였다). 다른 테이블의 열은 전과 같다.
 *
 * 변경 이력:
 * - 2026-11-21 Chromite: 큐의 열에는 만들기·바꾸기·이름 바꾸기·삭제 없음(앱 변경 #154, docs/chromite/altibase.md "큐")
 */
public class AltibaseTableColumnManager extends GenericTableColumnManager 
    implements DBEObjectRenamer<GenericTableColumn> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return !(container instanceof AltibaseQueue) && super.canCreateObject(container);
    }

    @Override
    public boolean canEditObject(@NotNull GenericTableColumn object) {
        return !(object.getParentObject() instanceof AltibaseQueue) && super.canEditObject(object);
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableColumn object) {
        return !(object.getParentObject() instanceof AltibaseQueue) && super.canDeleteObject(object);
    }

    @Override
    public boolean canRenameObject(@NotNull GenericTableColumn object) {
        return !(object.getParentObject() instanceof AltibaseQueue);
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull GenericTableColumn object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        final GenericTableBase table = command.getObject().getParentObject();

        String sql = "ALTER TABLE " + DBUtils.getObjectFullName(table, DBPEvaluationContext.DDL) +
            " ADD COLUMN (" + getNestedDeclaration(monitor, table, command, options) + ")";
        actions.add(
            new SQLDatabasePersistAction(
                ModelMessages.model_jdbc_create_new_table_column,
                sql));
    }
    
    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        final GenericTableColumn column = command.getObject();

        actions.add(
            new SQLDatabasePersistAction(
                "Rename column",
                "ALTER TABLE " + column.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) + " RENAME COLUMN " +
                    DBUtils.getQuotedIdentifier(column.getDataSource(), command.getOldName()) +
                    " TO " + DBUtils.getQuotedIdentifier(column.getDataSource(), command.getNewName())));
    }
}
