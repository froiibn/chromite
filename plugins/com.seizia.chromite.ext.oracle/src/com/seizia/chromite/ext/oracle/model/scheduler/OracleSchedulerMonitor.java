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
package com.seizia.chromite.ext.oracle.model.scheduler;

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * A leaf under the schema's Scheduler folder that opens a document tab: the job run details (history) or the running jobs.
 *
 * 설계: SQL Server 에이전트의 "작업 활동 모니터" 잎({@code SQLServerAgentActivityMonitor})과 같이 데이터가 없는 탐색기 항목이다.
 * 기록과 실행 중인 작업은 행이 많고 자주 바뀌어 트리 노드 대신 탭의 표로 보인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerMonitor implements DBSObject {

    public enum Kind {
        RUN_DETAILS,
        RUNNING_JOBS
    }

    private final OracleSchema schema;
    private final Kind kind;

    OracleSchedulerMonitor(@NotNull OracleSchema schema, @NotNull Kind kind) {
        this.schema = schema;
        this.kind = kind;
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    @NotNull
    public OracleSchema getSchema() {
        return schema;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return kind == Kind.RUN_DETAILS ? OracleSchedulerMessages.node_run_details : OracleSchedulerMessages.node_running_jobs;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public OracleSchema getParentObject() {
        return schema;
    }

    @NotNull
    @Override
    public OracleDataSource getDataSource() {
        return schema.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String toString() {
        return getName();
    }
}
