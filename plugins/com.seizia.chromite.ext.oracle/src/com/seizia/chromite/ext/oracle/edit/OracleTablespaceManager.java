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
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleTablespace;
import com.seizia.chromite.ext.oracle.model.OracleTablespaceDDL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
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
 * Creates, renames and drops Oracle tablespaces (Object Explorer "New Tablespace…", F2 and Delete).
 *
 * 설계: 문장은 {@link OracleTablespaceDDL}이 만든다(시험 서버에서 확인한 문법). 새 객체의 종류·빅파일·파일·크기·자동 확장은 화면의
 * 대화상자가 {@link OracleTablespace#setNewSettings}로 넣는다(구성기 없이 만드는 Swing 경로, {@code OPTION_SKIP_CONFIGURATION}).
 * <ul>
 *     <li>만들 수 있는 곳은 연결의 Storage &gt; Tablespaces 폴더(컨테이너가 데이터 원본)뿐이다.</li>
 *     <li>지우기·이름 바꾸기는 데이터베이스가 기대는 테이블스페이스가 아닐 때만이다({@link OracleTablespace#isFixedStorage}: SYSTEM, SYSAUX,
 *     기본 영구·임시, 활성 언두, 읽을 수 없으면 그 종류 모두). 삭제 옵션은 서버 문법의 세 가지다: 내용({@link #OPTION_DELETE_CONTENTS}),
 *     데이터 파일({@link #OPTION_DELETE_DATA_FILES}), 제약 조건 연쇄({@link #OPTION_DELETE_CASCADE}). 뒤의 둘은 내용과 함께일 때만 쓴다.</li>
 *     <li>CREATE 직후 같은 세션에서 새 테이블스페이스의 카탈로그 행을 읽는다({@link OracleTablespace#readCreated}). 서버가 정한 값(상태,
 *     빅파일, 블록 크기)이 새로 고치지 않아도 노드와 속성에 맞게 보인다.</li>
 *     <li>속성 편집은 없다({@link #canEditObject}): 모델의 속성은 {@code editable}이지만 ALTER 문을 만드는 곳이 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #127, docs/chromite/oracle-storage.md)
 */
public class OracleTablespaceManager extends SQLObjectEditor<OracleTablespace, OracleDataSource> implements DBEObjectRenamer<OracleTablespace> {

    private static final Log log = Log.getLog(OracleTablespaceManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY | FEATURE_DELETE_CONTENTS | FEATURE_DELETE_DATA_FILES | FEATURE_DELETE_CASCADE;
    }

    @Nullable
    @Override
    public DBSObjectCache<OracleDataSource, OracleTablespace> getObjectsCache(OracleTablespace object) {
        return object.getDataSource().getTablespaceCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof OracleDataSource && super.canCreateObject(container);
    }

    @Override
    public boolean canDeleteObject(@NotNull OracleTablespace object) {
        return !object.isFixedStorage() && super.canDeleteObject(object);
    }

    @Override
    public boolean canRenameObject(@NotNull OracleTablespace object) {
        return !object.isFixedStorage();
    }

    @Override
    public boolean canEditObject(@NotNull OracleTablespace object) {
        return false;
    }

    @Override
    protected OracleTablespace createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new OracleTablespace((OracleDataSource) container);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        OracleTablespace tablespace = command.getObject();
        OracleTablespaceDDL.NewTablespace settings = tablespace.getNewSettings();
        if (settings == null) {
            throw new DBException("The settings of the new tablespace are missing");
        }
        actions.add(new SQLDatabasePersistAction("Create tablespace", OracleTablespaceDDL.createTablespace(tablespace, settings)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    try {
                        tablespace.readCreated(jdbcSession);
                    } catch (SQLException e) {
                        // The tablespace exists; its node shows the server's values after a refresh
                        log.debug("Cannot read the new tablespace " + tablespace.getName(), e);
                    }
                }
            }
        });
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull OracleTablespace object,
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
        actions.add(new SQLDatabasePersistAction("Rename tablespace",
            OracleTablespaceDDL.renameTablespace(command.getObject(), command.getOldName(), command.getNewName())));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Drop tablespace", OracleTablespaceDDL.dropTablespace(command.getObject(),
            CommonUtils.getOption(options, OPTION_DELETE_CONTENTS),
            CommonUtils.getOption(options, OPTION_DELETE_DATA_FILES),
            CommonUtils.getOption(options, OPTION_DELETE_CASCADE))));
    }
}
