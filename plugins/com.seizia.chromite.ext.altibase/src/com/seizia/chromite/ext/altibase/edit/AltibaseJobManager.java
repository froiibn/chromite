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
import com.seizia.chromite.ext.altibase.model.AltibaseJob;
import com.seizia.chromite.ext.altibase.model.AltibaseJobDDL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
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
 * Creates and drops the jobs of Altibase-family servers (Object Explorer Global metadata &gt; Jobs: "New Job…" and Delete).
 *
 * 설계: 테이블스페이스 관리자({@link AltibaseTablespaceManager})와 같은 모양이다. 문장은 {@link AltibaseJobDDL}이 만들고(두 서버에서 확인한
 * 문법), 새 작업의 이름과 정의는 화면의 대화상자가 넣는다(구성기 없이 만드는 Swing 경로). 바꾸기·켜기·끄기는 객체 편집이 아니라 Object
 * Explorer 유지 관리 명령이다({@code ObjectMaintenanceActions}): 절마다 따로 실행해야 하고 지금 값으로 채운 대화상자가 필요해서다.
 * <ul>
 *     <li>만들 수 있는 곳은 연결의 Jobs 폴더(컨테이너가 데이터 원본)뿐이다.</li>
 *     <li>권한(CREATE / ALTER / DROP ANY JOB, SYS는 늘 가짐)은 여기서 보지 않는다. 권한이 없으면 서버 오류 201137이 다른 변경과 같은
 *     오류 창에 보인다(테이블스페이스와 같은 처리). 권한은 역할로도 받을 수 있어 메뉴를 열 때마다 판단하지 않는다.</li>
 *     <li>CREATE 직후 같은 세션에서 새 작업의 카탈로그 행을 읽는다({@link AltibaseJob#readCreated}).</li>
 *     <li>이름 바꾸기는 없다(서버에 문장이 없음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #154, docs/chromite/altibase.md "작업 만들기·바꾸기·삭제")
 */
public class AltibaseJobManager extends SQLObjectEditor<AltibaseJob, AltibaseDataSource> {

    private static final Log log = Log.getLog(AltibaseJobManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, AltibaseJob> getObjectsCache(AltibaseJob object) {
        return object.getDataSource().getJobCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof AltibaseDataSource && super.canCreateObject(container);
    }

    @Override
    public boolean canEditObject(@NotNull AltibaseJob object) {
        return false;
    }

    @Override
    protected AltibaseJob createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new AltibaseJob((AltibaseDataSource) container);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        AltibaseJob job = command.getObject();
        AltibaseJobDDL.Definition definition = job.getInitialDefinition();
        if (definition == null) {
            throw new DBException("The settings of the new job are missing");
        }
        actions.add(new SQLDatabasePersistAction("Create job", AltibaseJobDDL.createJob(job, definition)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        job.readCreated(jdbcSession);
                    } catch (SQLException e) {
                        // The job exists; its node shows the right values after a refresh
                        log.debug("Cannot read the new job " + job.getName(), e);
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
        actions.add(new SQLDatabasePersistAction("Drop job", AltibaseJobDDL.dropJob(command.getObject())));
    }
}
