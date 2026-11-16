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
package com.seizia.chromite.ext.oracle.model.plan;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.utils.IntKeyMap;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A row of an executed cursor's plan ({@code V$SQL_PLAN_STATISTICS_ALL}) with the row source statistics of its last
 * execution, the values {@code DBMS_XPLAN.DISPLAY_CURSOR(..., 'ALLSTATS LAST')} shows.
 *
 * 설계: 커서 계획 행은 PLAN_TABLE과 열 이름이 같아 {@link OraclePlanNode}가 그대로 읽고, 실행 통계 열만 이 클래스가 더한다.
 * 하위 클래스로 둔 이유: 계획 탭의 트리 열은 노드 클래스의 보이는 속성이므로, 예상 계획(PLAN_TABLE)에는 빈 실제 값 열이 생기지 않는다.
 * 문장 행(ID 0)의 CARDINALITY는 커서 계획에서 NULL이라 예상 행이 없음으로 둔다(0으로 두면 실제와 크게 어긋난 것으로 보임).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 */
public class OracleActualPlanNode extends OraclePlanNode {

    @Nullable
    private final Long estimatedRows;
    @Nullable
    private final Long starts;
    @Nullable
    private final Long actualRows;
    @Nullable
    private final Long elapsedMicros;
    @Nullable
    private final Long buffers;
    @Nullable
    private final Long diskReads;

    OracleActualPlanNode(OracleDataSource dataSource, IntKeyMap<OraclePlanNode> prevNodes, ResultSet dbResult) throws SQLException {
        super(dataSource, prevNodes, dbResult);
        this.estimatedRows = JDBCUtils.safeGetLongNullable(dbResult, "cardinality");
        this.starts = JDBCUtils.safeGetLongNullable(dbResult, "last_starts");
        this.actualRows = JDBCUtils.safeGetLongNullable(dbResult, "last_output_rows");
        this.elapsedMicros = JDBCUtils.safeGetLongNullable(dbResult, "last_elapsed_time");
        Long consistentGets = JDBCUtils.safeGetLongNullable(dbResult, "last_cr_buffer_gets");
        Long currentGets = JDBCUtils.safeGetLongNullable(dbResult, "last_cu_buffer_gets");
        // DISPLAY_CURSOR's Buffers column: consistent and current mode gets
        this.buffers = consistentGets == null && currentGets == null ? null
            : (consistentGets == null ? 0 : consistentGets) + (currentGets == null ? 0 : currentGets);
        this.diskReads = JDBCUtils.safeGetLongNullable(dbResult, "last_disk_reads");
    }

    @Nullable
    @Property(order = 11, viewable = true)
    public Long getStarts() {
        return starts;
    }

    @Nullable
    @Property(order = 12, viewable = true)
    public Long getActualRows() {
        return actualRows;
    }

    /**
     * Elapsed time of the operation and its children in the last execution, in milliseconds.
     */
    @Nullable
    @Property(order = 13, viewable = true)
    public Double getActualTime() {
        return elapsedMicros == null ? null : elapsedMicros / 1000.0;
    }

    @Nullable
    @Property(order = 14, viewable = true)
    public Long getBuffers() {
        return buffers;
    }

    @Nullable
    @Property(order = 15, viewable = true)
    public Long getDiskReads() {
        return diskReads;
    }

    @Nullable
    @Override
    public Number getNodeRowCount() {
        return estimatedRows;
    }
}
