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
package com.seizia.chromite.ext.oracle.edit;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleDataFile;
import com.seizia.chromite.ext.oracle.model.OracleTablespace;
import com.seizia.chromite.ext.oracle.model.OracleTablespaceDDL;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adds and drops the data files of Oracle tablespaces and the temp files of temporary tablespaces (Object Explorer
 * "New Data File…" on a tablespace's Files folder, and Delete on a file).
 *
 * 설계: 문장은 {@link OracleTablespaceDDL}이 만든다. 데이터베이스가 기대는 테이블스페이스({@link OracleTablespace#isFixedStorage})의 파일은
 * 여기서 바꾸지 않는다.
 * <ul>
 *     <li>추가는 빅파일 테이블스페이스에서 하지 않는다(ORA-32771: 파일이 하나뿐). 삭제는 빅파일이나 파일이 하나뿐인 테이블스페이스에서
 *     하지 않는다(ORA-03261). 스몰파일 영구 테이블스페이스의 첫 파일은 서버가 ORA-03263으로 거절하는데, 어느 파일이 첫 파일인지 카탈로그가
 *     따로 알려 주지 않아(파일 번호는 다시 쓰임) 막지 않고 서버의 오류를 보인다.</li>
 *     <li>ADD 직후 같은 세션에서 새 파일의 행(서버가 붙인 전체 경로, ID, 크기)을 읽는다. 추가·삭제 뒤 테이블스페이스의 크기를 잊고 알린다.</li>
 *     <li>DROP DATAFILE/TEMPFILE은 서버 디스크의 파일도 지운다(시험 서버에서 확인: 같은 이름을 REUSE 없이 다시 추가할 수 있었다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #127, docs/chromite/oracle-storage.md)
 */
public class OracleDataFileManager extends SQLObjectEditor<OracleDataFile, OracleTablespace> {

    private static final Log log = Log.getLog(OracleDataFileManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<OracleTablespace, OracleDataFile> getObjectsCache(OracleDataFile object) {
        return object.getTablespace().getFileCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof OracleTablespace tablespace && tablespace.supportsFileAdd() && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull OracleDataFile object) {
        OracleTablespace tablespace = object.getTablespace();
        if (!tablespace.supportsFileChanges() || tablespace.isBigFile()) {
            return false;
        }
        // The files as the tree read them; one file cannot be dropped
        Collection<OracleDataFile> files = tablespace.getFileCache().getCachedObjects();
        return files.size() > 1 && super.canDeleteObject(object);
    }

    @Override
    public boolean canEditObject(@NotNull OracleDataFile object) {
        return false;
    }

    @Override
    protected OracleDataFile createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new OracleDataFile((OracleTablespace) container, "");
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        OracleDataFile file = command.getObject();
        if (file.getNewSpace() == null) {
            throw new DBException("The settings of the new data file are missing");
        }
        OracleTablespace tablespace = file.getTablespace();
        actions.add(new SQLDatabasePersistAction("Add data file", OracleTablespaceDDL.addFile(tablespace, file.getNewSpace())) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null) {
                    if (session instanceof JDBCSession jdbcSession) {
                        try {
                            file.readCreated(jdbcSession);
                        } catch (SQLException e) {
                            log.debug("Cannot read the new data file of " + tablespace.getName(), e);
                        }
                    }
                    sizesChanged(tablespace);
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
        OracleDataFile file = command.getObject();
        actions.add(new SQLDatabasePersistAction("Drop data file", OracleTablespaceDDL.dropFile(file)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null) {
                    sizesChanged(file.getTablespace());
                }
            }
        });
    }

    /**
     * The tablespace's size changed: forgotten (read again when shown) and told to the navigator. The file list itself
     * is changed by the command's reflector.
     */
    private static void sizesChanged(@NotNull OracleTablespace tablespace) {
        tablespace.resetSizes();
        DBUtils.fireObjectUpdate(tablespace);
    }
}
