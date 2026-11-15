/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.mssql.edit;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerFilegroup;
import com.seizia.chromite.ext.mssql.model.SQLServerStorageDDL;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Adds, renames and removes the filegroups of a SQL Server database (Object Explorer "New Filegroup…" on a database's
 * Storage &gt; Filegroups folder, F2 and Delete on a filegroup).
 *
 * 설계: 문장은 {@link SQLServerStorageDDL}이 만든다. 새 파일 그룹은 이름만 받는다(Object Explorer의 이름 대화상자). 만들 수 있는 것은 행
 * 데이터 파일 그룹뿐이다(FILESTREAM·메모리 최적화는 경로·컨테이너가 달라 빼었다). 바꿀 수 있는 것은 주 파일 그룹이 아닌 행 데이터 파일
 * 그룹이다({@link SQLServerFilegroup#supportsSecondaryChanges}): 주 파일 그룹은 서버가 이름 바꾸기(5012)와 삭제를 거절한다. 파일이 있는
 * 파일 그룹의 삭제는 서버가 거절하고(5042) 그 오류가 보인다. ADD 직후 같은 세션에서 새 파일 그룹의 행(ID, 종류)을 읽어, 저장 뒤 새로 고치지
 * 않아도 그 노드의 Files 폴더가 맞는 파일을 읽는다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public class SQLServerFilegroupManager extends SQLServerStorageObjectEditor<SQLServerFilegroup, SQLServerDatabase> {

    private static final Log log = Log.getLog(SQLServerFilegroupManager.class);

    @Nullable
    @Override
    public DBSObjectCache<SQLServerDatabase, SQLServerFilegroup> getObjectsCache(SQLServerFilegroup object) {
        return object.getDatabase().getFilegroupCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof SQLServerDatabase database && database.supportsStorageChanges() && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull SQLServerFilegroup object) {
        return object.supportsSecondaryChanges() && super.canDeleteObject(object);
    }

    @Override
    public boolean canRenameObject(@NotNull SQLServerFilegroup object) {
        return object.supportsSecondaryChanges();
    }

    @Override
    protected SQLServerFilegroup createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new SQLServerFilegroup((SQLServerDatabase) container);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        SQLServerFilegroup filegroup = command.getObject();
        actions.add(new SQLDatabasePersistAction("Add filegroup", SQLServerStorageDDL.addFilegroup(filegroup)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        filegroup.readRow(jdbcSession);
                    } catch (SQLException e) {
                        // The filegroup exists; its node shows the right values after a refresh
                        log.debug("Cannot read the new filegroup " + filegroup.getName(), e);
                    }
                }
            }
        });
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Remove filegroup", SQLServerStorageDDL.removeFilegroup(command.getObject())));
    }

    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Rename filegroup",
            SQLServerStorageDDL.renameFilegroup(command.getObject(), command.getOldName(), command.getNewName())));
    }
}
