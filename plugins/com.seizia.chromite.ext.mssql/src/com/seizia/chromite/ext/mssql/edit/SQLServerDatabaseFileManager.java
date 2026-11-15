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
import com.seizia.chromite.ext.mssql.model.SQLServerDatabaseFile;
import com.seizia.chromite.ext.mssql.model.SQLServerFilegroup;
import com.seizia.chromite.ext.mssql.model.SQLServerStorageDDL;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Adds, renames and removes SQL Server database files: data files of a filegroup (Object Explorer "New File…" on a
 * filegroup's Files folder) and log files ("New Log File…" on a database's Storage &gt; Log Files folder), F2 and Delete on a
 * file.
 *
 * 설계: 문장은 {@link SQLServerStorageDDL}이 만든다. 새 파일의 실제 경로·크기·증가·최대 크기는 화면의 대화상자가
 * {@link SQLServerDatabaseFile#setInitialSpec}로 넣는다(구성기 없이 만드는 Swing 경로). 무엇을 할 수 있는지는 카탈로그 값으로 정한다:
 * 읽기 전용 파일 그룹에는 파일을 더하지 않고(5048), 읽기 전용 파일은 바꾸지 않으며, 주 데이터·로그 파일은 지우지 않는다(5020)
 * ({@link SQLServerDatabaseFile#canDelete}). 데이터가 든 파일의 삭제는 서버가 거절하고(5042) 그 오류가 보인다. 이름 바꾸기는 논리 이름만
 * 바꾼다(실제 파일 이름은 그대로).
 * <ul>
 *     <li>ADD 직후 같은 세션에서 새 파일의 행(ID, 서버가 정한 크기)을 논리 이름으로 읽는다. 데이터 파일이면 파일 그룹의 행(파일 수: 기본값·읽기
 *     전용으로 바꿀 수 있는지)도 다시 읽는다. REMOVE 뒤에도 그렇다. 그래서 새로 고치지 않아도 노드와 속성이 맞다.</li>
 *     <li>REMOVE FILE은 서버 디스크의 파일도 지운다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public class SQLServerDatabaseFileManager extends SQLServerStorageObjectEditor<SQLServerDatabaseFile, DBSObject> {

    private static final Log log = Log.getLog(SQLServerDatabaseFileManager.class);

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, SQLServerDatabaseFile> getObjectsCache(SQLServerDatabaseFile object) {
        SQLServerFilegroup filegroup = object.getFilegroup();
        return filegroup != null ? filegroup.getFileCache() : object.getDatabase().getLogFileCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        boolean supported = container instanceof SQLServerFilegroup filegroup ? filegroup.canAddFiles()
            : container instanceof SQLServerDatabase database && database.supportsStorageChanges();
        return supported && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull SQLServerDatabaseFile object) {
        return object.canDelete() && super.canDeleteObject(object);
    }

    @Override
    public boolean canRenameObject(@NotNull SQLServerDatabaseFile object) {
        return object.supportsChanges();
    }

    @Override
    protected SQLServerDatabaseFile createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return container instanceof SQLServerFilegroup filegroup
            ? new SQLServerDatabaseFile(filegroup) : new SQLServerDatabaseFile((SQLServerDatabase) container);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        SQLServerDatabaseFile file = command.getObject();
        SQLServerStorageDDL.FileSpec spec = file.getInitialSpec();
        if (spec == null) {
            throw new DBException("The settings of the new file are missing");
        }
        actions.add(new SQLDatabasePersistAction("Add file", SQLServerStorageDDL.addFile(file, spec)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        file.readCreated(jdbcSession);
                    } catch (SQLException e) {
                        log.debug("Cannot read the new file " + file.getName(), e);
                    }
                    reloadFilegroup(jdbcSession, file);
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
        SQLServerDatabaseFile file = command.getObject();
        actions.add(new SQLDatabasePersistAction("Remove file", SQLServerStorageDDL.removeFile(file)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    reloadFilegroup(jdbcSession, file);
                }
            }
        });
    }

    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Rename file",
            SQLServerStorageDDL.renameFile(command.getObject(), command.getOldName(), command.getNewName())));
    }

    /**
     * Reads the row of a data file's filegroup again (file count) and tells the navigator. The file list itself is changed
     * by the command's reflector.
     */
    private static void reloadFilegroup(@NotNull JDBCSession session, @NotNull SQLServerDatabaseFile file) {
        SQLServerFilegroup filegroup = file.getFilegroup();
        if (filegroup == null) {
            return;
        }
        try {
            filegroup.readRow(session);
            DBUtils.fireObjectUpdate(filegroup);
        } catch (SQLException e) {
            log.debug("Cannot read filegroup " + filegroup.getName() + " again", e);
        }
    }
}
