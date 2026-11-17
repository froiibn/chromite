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
package com.seizia.chromite.ext.altibase.tasks;

import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.altibase.model.AltibaseReplication;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;

import java.util.List;

/**
 * Starts a replication.
 *
 * 설계: 복제의 카탈로그가 제어 문장을 허락하지 않으면({@link AltibaseReplication#supportsControlStatements()}) 문장을 만들지 않고
 * 실패한다. 화면은 그런 복제에 이 명령을 내지 않지만, 저장된 작업처럼 다른 길로 와도 확인하지 않은 문장이 서버로 가지 않게 한다.
 *
 * 변경 이력:
 * - 2026-11-17 확인하지 않은 카탈로그의 복제에는 문장을 만들지 않음(앱 변경 #139 작업 4, docs/chromite/altibase.md "복제 카탈로그")
 */
public class AltibaseToolReplicationStart extends SQLToolExecuteHandler<AltibaseReplication, AltibaseToolReplicationStartSettings> {

    @Override
    public AltibaseToolReplicationStartSettings createToolSettings() {
        return new AltibaseToolReplicationStartSettings();
    }

    @Override
    public void generateObjectQueries(DBCSession session, AltibaseToolReplicationStartSettings settings, 
            List<DBEPersistAction> queries, AltibaseReplication object) throws DBCException {
        if (!object.supportsControlStatements()) {
            throw new DBCException("Starting a replication of this catalog is not supported");
        }
        String sql = String.format(AltibaseConstants.ALTER_REPL_PREFIX + " %s START", object.getName());
        queries.add(new SQLDatabasePersistAction(sql));
    }

    @Override
    public boolean needsRefreshOnFinish() {
        return true;
    }
}
