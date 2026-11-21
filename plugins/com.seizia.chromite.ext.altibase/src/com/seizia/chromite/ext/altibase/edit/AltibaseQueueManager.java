/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.model.AltibaseJobDDL;
import com.seizia.chromite.ext.altibase.model.AltibaseMetaModel;
import com.seizia.chromite.ext.altibase.model.AltibaseQueue;
import com.seizia.chromite.ext.altibase.model.AltibaseSchema;
import com.seizia.chromite.ext.generic.model.GenericSequence;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates and drops the queues of Altibase-family servers (Object Explorer schema &gt; Queues: "New Queue…" and Delete).
 *
 * 설계: 큐는 테이블 모양(열·인덱스)이지만 구조를 바꿀 수 없다. 서버가 메시지 크기 하나로 열 넷과 인덱스, 내부 시퀀스
 * {@code <queue>_NEXT_MSG_ID}를 만들고, {@code DROP QUEUE}가 그 셋을 함께 지운다(Altibase 7.3, ShardSphere 5.1.9에서 확인). 그래서
 * 테이블 관리자(구조 편집기)가 아니라 만들기·지우기만 하는 객체 관리자다.
 * <ul>
 *     <li>전에는 일반 테이블 관리자를 이어받아 구조 편집기였다: 큐에 Design(테이블 디자이너)이 보였고, 삭제 확인에 "종속 개체도 삭제"가 있어
 *     고르면 {@code DROP QUEUE … CASCADE}(두 서버 모두 구문 오류 200705)가 나갔다. 만들기는 막혀 있었다.</li>
 *     <li>만들기는 스키마의 Queues 폴더에서, 이름과 메시지 크기로 한다({@link AltibaseQueue#setInitialMessageSize}, 화면의 대화상자가 넣음).
 *     문장은 {@link AltibaseJobDDL#createQueue}다.</li>
 *     <li>큐를 만들거나 지운 뒤 같은 스키마의 시퀀스 목록을 맞춘다: 시퀀스 목록을 이미 읽었으면 새 내부 시퀀스를 카탈로그에서 읽어 넣고,
 *     지운 큐의 것은 뺀다(탐색기 노드도 같은 이벤트로). 맞추지 않으면 지운 큐의 시퀀스 노드가 남아 고를 때 오류가 났다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-21 Chromite: 구조 편집기가 아닌 만들기·지우기 관리자로, CASCADE 없음, 새 큐, 내부 시퀀스 목록 맞추기
 *   (앱 변경 #154, docs/chromite/altibase.md "큐")
 */
public class AltibaseQueueManager extends SQLObjectEditor<GenericTableBase, GenericStructContainer> {

    private static final Log log = Log.getLog(AltibaseQueueManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, GenericTableBase> getObjectsCache(GenericTableBase object) {
        return object.getContainer().getTableCache();
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof AltibaseSchema && super.canCreateObject(container);
    }

    @Override
    public boolean canEditObject(@NotNull GenericTableBase object) {
        return false;
    }

    @Override
    protected GenericTableBase createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new AltibaseQueue((GenericStructContainer) container, null, AltibaseQueue.TABLE_TYPE_QUEUE, null);
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        GenericTableBase queue = command.getObject();
        Integer size = queue instanceof AltibaseQueue altibaseQueue ? altibaseQueue.getInitialMessageSize() : null;
        if (size == null) {
            throw new DBException("The message size of the new queue is missing");
        }
        actions.add(new SQLDatabasePersistAction("Create queue",
            AltibaseJobDDL.createQueue(DBUtils.getObjectFullName(queue, DBPEvaluationContext.DDL), size)) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null && session instanceof JDBCSession jdbcSession) {
                    addQueueSequence(jdbcSession, queue);
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
        GenericTableBase queue = command.getObject();
        actions.add(new SQLDatabasePersistAction("Drop queue",
            AltibaseJobDDL.dropQueue(DBUtils.getObjectFullName(queue, DBPEvaluationContext.DDL))) {
            @Override
            public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
                if (error == null) {
                    removeQueueSequence(queue);
                }
            }
        });
    }

    /**
     * Puts the new queue's internal sequence into the schema's sequence list if that list is read already.
     */
    private static void addQueueSequence(@NotNull JDBCSession session, @NotNull GenericTableBase queue) {
        GenericStructContainer container = queue.getContainer();
        var cache = container.getSequenceCache();
        String name = queue.getName() + AltibaseMetaModel.QUEUE_SEQUENCE_SUFFIX;
        if (!cache.isFullyCached() || cache.getCachedObject(name) != null) {
            // Not read yet: the list is read with the sequence when it is opened
            return;
        }
        try (JDBCStatement dbStat = container.getDataSource().getMetaModel().prepareSequencesLoadStatement(session, container)) {
            dbStat.executeStatement();
            try (JDBCResultSet dbResult = dbStat.openResultSet()) {
                while (dbResult != null && dbResult.next()) {
                    if (name.equals(JDBCUtils.safeGetStringTrimmed(dbResult, "TABLE_NAME"))) {
                        GenericSequence sequence = container.getDataSource().getMetaModel().createSequenceImpl(session, container, dbResult);
                        if (sequence != null) {
                            cache.cacheObject(sequence);
                            DBUtils.fireObjectAdd(sequence, new HashMap<>());
                        }
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Cannot read the internal sequence of the new queue " + queue.getName(), e);
        }
    }

    /**
     * Removes the dropped queue's internal sequence from the schema's sequence list (DROP QUEUE dropped it).
     */
    private static void removeQueueSequence(@NotNull GenericTableBase queue) {
        var cache = queue.getContainer().getSequenceCache();
        GenericSequence sequence = cache.getCachedObject(queue.getName() + AltibaseMetaModel.QUEUE_SEQUENCE_SUFFIX);
        if (sequence != null) {
            cache.removeObject(sequence, false);
            DBUtils.fireObjectRemove(sequence);
        }
    }
}
