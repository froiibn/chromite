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
package com.seizia.chromite.ext.shardsphere.edit;

import com.seizia.chromite.ext.shardsphere.model.ContinuousObject;
import com.seizia.chromite.ext.shardsphere.model.StreamDDL;
import com.seizia.chromite.ext.shardsphere.model.StreamFunction;
import com.seizia.chromite.ext.shardsphere.model.StreamTable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.List;
import java.util.Map;

/**
 * Drops the stream objects of a ShardSphere server (stream tables, continuous tables and stream tables, continuous
 * queries, stream functions) through Object Explorer's Delete.
 *
 * 설계: 이 번들의 {@code plugin.xml}이 기존 객체 관리자 확장 지점으로 세 모델 클래스에 이 관리자를 준다(공용 코드 변경 없음). 그래서 기존
 * 삭제 흐름(확인 → 미리 보기 → 열린 트랜잭션 질문 → 실행 → 모델 캐시에서 빼고 노드 지우기)을 그대로 쓴다. 문장은 {@link StreamDDL}이다.
 * <ul>
 *     <li>만들기는 여기서 하지 않는다: 정의가 문장(SELECT 본문, 윈도·옵션 절)이라 틀을 쿼리 창에 연다
 *     ({@code AltibaseObjectTemplates}). 그래서 이름만 대화상자가 나오지 않게 {@link #canCreateObject}는 거짓이다.</li>
 *     <li>바꾸기는 객체 편집이 아니라 유지 관리 명령이다(사용 / 사용 안 함, 실행 설정).</li>
 *     <li>다른 객체가 읽는 객체도 서버가 지운다(읽는 쪽은 남음, 2026-11-21 확인). 연쇄 삭제는 두지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #156, docs/chromite/shardsphere.md "스트림 명령")
 */
public class StreamObjectManager extends SQLObjectEditor<DBSObject, DBSObject> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    /**
     * The list cache that holds the object (its node goes when the dropped object leaves it).
     */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public DBSObjectCache<? extends DBSObject, DBSObject> getObjectsCache(DBSObject object) {
        DBSObjectCache<?, ?> cache = null;
        if (object instanceof StreamTable table) {
            cache = table.getParentObject().getStreamTableCache();
        } else if (object instanceof ContinuousObject continuous) {
            cache = continuous.getParentObject().getContinuousCache();
        } else if (object instanceof StreamFunction function) {
            cache = function.getParentObject().getStreamFunctionCache();
        }
        return (DBSObjectCache<? extends DBSObject, DBSObject>) cache;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return false;
    }

    @Override
    public boolean canEditObject(@NotNull DBSObject object) {
        return false;
    }

    @Override
    protected DBSObject createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        throw new DBException("Stream objects are created from a template in a query window");
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        throw new DBException("Stream objects are created from a template in a query window");
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Drop stream object", StreamDDL.drop(command.getObject())));
    }
}
