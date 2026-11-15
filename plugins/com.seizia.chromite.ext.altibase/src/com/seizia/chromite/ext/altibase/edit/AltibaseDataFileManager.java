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
package com.seizia.chromite.ext.altibase.edit;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.model.AltibaseDataFile;
import com.seizia.chromite.ext.altibase.model.AltibaseDataFile4Disk;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespace;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespaceDDL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Adds and drops the data files of Altibase-family disk tablespaces and the temp files of temporary tablespaces (Object
 * Explorer "New Data File…" on a tablespace's Data Files folder, and Delete on a file).
 *
 * 설계: 문장은 {@link AltibaseTablespaceDDL}이 만든다. 파일을 받는 것은 사용자 디스크 데이터·임시 테이블스페이스뿐이다
 * ({@link AltibaseTablespace#hasDiskFiles}): 메모리·휘발성은 서버가 거절하고(201297), 시스템 테이블스페이스의 파일은 여기서 바꾸지 않는다.
 * <ul>
 *     <li>ADD 직후 같은 세션에서 새 파일의 행(서버가 붙인 전체 경로, ID, 크기)과 테이블스페이스의 행(파일 수)을 읽는다. DROP 뒤에도
 *     테이블스페이스의 행을 다시 읽는다. 그래서 탐색기의 노드와 속성이 새로 고치지 않아도 맞다.</li>
 *     <li>DROP DATAFILE은 서버 디스크의 파일을 지우지 않는다(시험 서버에서 확인: 파일이 남고, 같은 이름은 REUSE 없이 다시 추가할 수 없다).
 *     테이블스페이스의 첫 파일처럼 쓰이는 파일은 서버가 "The data file is in use."로 거절한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리")
 */
public class AltibaseDataFileManager extends SQLObjectEditor<AltibaseDataFile, AltibaseTablespace> {

    private static final Log log = Log.getLog(AltibaseDataFileManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<AltibaseTablespace, AltibaseDataFile> getObjectsCache(AltibaseDataFile object) {
        return object.getParentObject().getFileCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof AltibaseTablespace tablespace && tablespace.hasDiskFiles() && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull AltibaseDataFile object) {
        return object instanceof AltibaseDataFile4Disk && object.getParentObject().hasDiskFiles() && super.canDeleteObject(object);
    }

    @Override
    public boolean canEditObject(@NotNull AltibaseDataFile object) {
        return false;
    }

    @Override
    protected AltibaseDataFile createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new AltibaseDataFile4Disk((AltibaseTablespace) container);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (!(command.getObject() instanceof AltibaseDataFile4Disk file) || file.getInitialSpace() == null) {
            throw new DBException("The settings of the new data file are missing");
        }
        AltibaseTablespace tablespace = file.getParentObject();
        actions.add(new SQLDatabasePersistAction("Add data file", AltibaseTablespaceDDL.addFile(tablespace, file.getInitialSpace())) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        file.readCreated(jdbcSession);
                    } catch (SQLException e) {
                        log.debug("Cannot read the new data file of " + tablespace.getName(), e);
                    }
                    reloadTablespace(jdbcSession, tablespace);
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
        AltibaseDataFile file = command.getObject();
        actions.add(new SQLDatabasePersistAction("Drop data file", AltibaseTablespaceDDL.dropFile(file)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    reloadTablespace(jdbcSession, file.getParentObject());
                }
            }
        });
    }

    /**
     * Reads the tablespace's row again (data file count) and tells the navigator, so its node and properties are current.
     * The file list itself is changed by the command's reflector.
     */
    private static void reloadTablespace(@NotNull JDBCSession session, @NotNull AltibaseTablespace tablespace) {
        try {
            tablespace.reloadRow(session);
            DBUtils.fireObjectUpdate(tablespace);
        } catch (SQLException e) {
            log.debug("Cannot read tablespace " + tablespace.getName() + " again", e);
        }
    }
}
