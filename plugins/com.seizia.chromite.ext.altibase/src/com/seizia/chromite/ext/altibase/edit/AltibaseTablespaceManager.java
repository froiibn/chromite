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
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespace;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespaceDDL;
import com.seizia.chromite.model.DBPDataSource;
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
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Creates and drops Altibase-family tablespaces (Object Explorer "New Tablespace…" and Delete).
 *
 * 설계: 문장은 {@link AltibaseTablespaceDDL}이 만든다(시험 서버에서 확인한 문법). 새 객체의 종류·파일·크기·자동 확장은 화면의 대화상자가
 * {@link AltibaseTablespace#setInitialSpace}로 넣는다(구성기 없이 만드는 Swing 경로, {@code OPTION_SKIP_CONFIGURATION}).
 * <ul>
 *     <li>만들 수 있는 곳은 연결의 Storage &gt; Tablespaces 폴더(컨테이너가 데이터 원본)뿐이다. 사용자의 Tablespaces 목록 같은 다른 폴더에서는
 *     만들지 않는다.</li>
 *     <li>지울 수 있는 것은 사용자 테이블스페이스만이다({@link AltibaseTablespace#isUserTablespace}, 종류 코드로 판단). 삭제 옵션은 서버 문법의
 *     세 가지다: 내용({@link #OPTION_DELETE_CONTENTS}), 데이터 파일({@link #OPTION_DELETE_DATA_FILES}), 제약 조건 연쇄
 *     ({@link #OPTION_DELETE_CASCADE}). 뒤의 둘은 내용과 함께일 때만 쓴다.</li>
 *     <li>CREATE 직후 같은 세션에서 새 테이블스페이스의 카탈로그 행을 읽는다({@link AltibaseTablespace#readCreated}). 읽지 않으면 캐시에 들어가는
 *     새 객체의 ID가 0이라 그 노드의 Files·Tables가 시스템 사전 테이블스페이스의 것을 읽는다.</li>
 *     <li>이름 바꾸기는 없다: 서버에 테이블스페이스 이름을 바꾸는 문장이 없다(시험한 세 모양 모두 구문 오류).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리")
 */
public class AltibaseTablespaceManager extends SQLObjectEditor<AltibaseTablespace, AltibaseDataSource> {

    private static final Log log = Log.getLog(AltibaseTablespaceManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY | FEATURE_DELETE_CONTENTS | FEATURE_DELETE_DATA_FILES | FEATURE_DELETE_CASCADE;
    }

    @Nullable
    @Override
    public DBSObjectCache<AltibaseDataSource, AltibaseTablespace> getObjectsCache(AltibaseTablespace object) {
        return object.getDataSource().getTablespaceCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof AltibaseDataSource && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull AltibaseTablespace object) {
        return object.isUserTablespace() && super.canDeleteObject(object);
    }

    @Override
    public boolean canEditObject(@NotNull AltibaseTablespace object) {
        return false;
    }

    @Override
    protected AltibaseTablespace createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new AltibaseTablespace((AltibaseDataSource) container, AltibaseTablespace.TbsType.DISK_USER_DATA);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        AltibaseTablespace tablespace = command.getObject();
        AltibaseTablespaceDDL.Space space = tablespace.getInitialSpace();
        if (space == null) {
            throw new DBException("The settings of the new tablespace are missing");
        }
        actions.add(new SQLDatabasePersistAction("Create tablespace", AltibaseTablespaceDDL.createTablespace(tablespace, space)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        tablespace.readCreated(jdbcSession);
                    } catch (SQLException e) {
                        // The tablespace exists; its node shows the right values after a refresh
                        log.debug("Cannot read the new tablespace " + tablespace.getName(), e);
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
        actions.add(new SQLDatabasePersistAction("Drop tablespace", AltibaseTablespaceDDL.dropTablespace(command.getObject(),
            CommonUtils.getOption(options, OPTION_DELETE_CONTENTS),
            CommonUtils.getOption(options, OPTION_DELETE_DATA_FILES),
            CommonUtils.getOption(options, OPTION_DELETE_CASCADE))));
    }
}
