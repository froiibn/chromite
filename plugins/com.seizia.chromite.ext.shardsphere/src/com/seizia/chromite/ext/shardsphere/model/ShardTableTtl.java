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
package com.seizia.chromite.ext.shardsphere.model;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ext.altibase.model.AltibaseTable;
import com.seizia.chromite.ext.generic.model.GenericSchema;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The TTL of an existing table on a ShardSphere server: reads it from {@code SYSTEM_.SYS_TTL_} and writes the statements
 * {@code ALTER TABLE t USING TTL n unit ON COLUMN c} (set or change) and {@code ALTER TABLE t USING NOTTL} (remove).
 *
 * 설계: Altibase 플러그인의 중립 자리 {@link AltibaseTableTtl}을 이 번들이 채운다(연결의 어댑터, {@link ShardSphereServer#getAdapter}).
 * 문장은 시스피어 답변(2026-11-17 15:28)과 같은 날 일반 서버에서 시험용 표로 확인한 것이다(docs/chromite/shardsphere.md "TTL 관리").
 * <ul>
 *     <li>단위: 시스피어가 이름을 든 다섯(분, 시간, 일, 월, 년)만 새 TTL에 고르게 한다. 분은 {@code MINUTE}다({@code MIN}은 서버의 구문 오류).
 *     서버가 받는 {@code WEEK}·{@code QUARTER}와 삭제 주기보다 짧으면 거절되는 {@code SECOND}는 고르게 하지 않고, 다른 길로 걸린 TTL을
 *     보일 때만 이름을 안다({@link #getUnit}).</li>
 *     <li>기준 컬럼: 날짜·시간 종류 컬럼만 고르게 한다. 서버는 INTEGER·VARCHAR 컬럼도 받지만 뜻을 모르고, 시스피어의 예는 DATE다.</li>
 *     <li>해당하는 테이블: 일반 테이블(큐·뷰·구체화된 뷰는 다른 모델 클래스라 여기 오지 않음) 가운데 시스템 스키마({@code SYSTEM_})가 아닌 것.
 *     샤딩 메타 노드에서는 샤드 테이블({@code SYS_SHARD.OBJECTS_}의 테이블)을 뺀다: 샤드 테이블의 TTL은 클러스터 전체에 걸리므로
 *     확인하기 전까지 명령을 내지 않는다(안전 쪽). 목록은 감지 때 한 번 읽은 것이다(메뉴는 메모리만 봄).</li>
 *     <li>이름은 방언의 규칙대로 따옴표를 붙인다(소유자.테이블, 컬럼).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 기존 테이블의 TTL 설정·변경·해제(앱 변경 #152, docs/chromite/shardsphere.md "TTL 관리")
 */
public final class ShardTableTtl implements AltibaseTableTtl {

    private static final String KEYWORD_ALTER_TABLE = "ALTER TABLE "; //$NON-NLS-1$
    private static final String KEYWORD_USING_TTL = " USING TTL "; //$NON-NLS-1$
    private static final String KEYWORD_ON_COLUMN = " ON COLUMN "; //$NON-NLS-1$
    private static final String KEYWORD_USING_NOTTL = " USING NOTTL"; //$NON-NLS-1$

    // The statement word and the SYS_TTL_.UNIT code of each unit
    private static final Unit MINUTE = new Unit("MINUTE", "M", ShardSphereMessages.ttl_unit_name_minute); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit HOUR = new Unit("HOUR", "H", ShardSphereMessages.ttl_unit_name_hour); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit DAY = new Unit("DAY", "D", ShardSphereMessages.ttl_unit_name_day); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit MONTH = new Unit("MONTH", "O", ShardSphereMessages.ttl_unit_name_month); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit YEAR = new Unit("YEAR", "Y", ShardSphereMessages.ttl_unit_name_year); //$NON-NLS-1$ //$NON-NLS-2$
    // Accepted by the server but not offered (the vendor named the five above)
    private static final Unit WEEK = new Unit("WEEK", "W", ShardSphereMessages.ttl_unit_name_week); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit QUARTER = new Unit("QUARTER", "Q", ShardSphereMessages.ttl_unit_name_quarter); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Unit SECOND = new Unit("SECOND", "S", ShardSphereMessages.ttl_unit_name_second); //$NON-NLS-1$ //$NON-NLS-2$

    private static final List<Unit> OFFERED_UNITS = List.of(MINUTE, HOUR, DAY, MONTH, YEAR);
    private static final List<Unit> KNOWN_UNITS = List.of(MINUTE, HOUR, DAY, MONTH, YEAR, WEEK, QUARTER, SECOND);

    // Owner and name of each shard table of a sharding meta node, as read at detection; empty on another server
    @NotNull
    private final Set<List<String>> shardTables;

    ShardTableTtl(@NotNull Set<List<String>> shardTables) {
        this.shardTables = Set.copyOf(shardTables);
    }

    @Override
    public boolean appliesTo(@NotNull DBSTable table) {
        if (!(table instanceof AltibaseTable altibaseTable) || !table.isPersisted() || altibaseTable.isSystem()) {
            return false;
        }
        GenericSchema schema = altibaseTable.getSchema();
        return schema != null && !schema.isSystem() && !shardTables.contains(List.of(schema.getName(), table.getName()));
    }

    @NotNull
    @Override
    public List<Unit> getUnits() {
        return OFFERED_UNITS;
    }

    /**
     * Days, as the vendor's example: a unit picked by mistake (minutes instead of days) would let rows expire far too early.
     */
    @NotNull
    @Override
    public Unit getDefaultUnit() {
        return DAY;
    }

    @Nullable
    @Override
    public Unit getUnit(@NotNull String code) {
        for (Unit unit : KNOWN_UNITS) {
            if (unit.code().equals(code)) {
                return unit;
            }
        }
        return null;
    }

    @Override
    public boolean isTtlColumn(@NotNull DBSEntityAttribute column) {
        return column.getDataKind() == DBPDataKind.DATETIME;
    }

    @Nullable
    @Override
    public Setting readSetting(@NotNull DBRProgressMonitor monitor, @NotNull DBSTable table) throws DBException {
        DBSObject owner = table.getParentObject();
        if (owner == null) {
            return null;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, table, "Read TTL of table '" + table.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
             JDBCPreparedStatement dbStat = session.prepareStatement(ShardTableProperties.QUERY_TTL)) {
            dbStat.setString(1, owner.getName());
            dbStat.setString(2, table.getName());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    return null;
                }
                String column = JDBCUtils.safeGetStringTrimmed(dbResult, "COLUMN_NAME"); //$NON-NLS-1$
                String unit = JDBCUtils.safeGetStringTrimmed(dbResult, "UNIT"); //$NON-NLS-1$
                return new Setting(column == null ? "" : column, JDBCUtils.safeGetLong(dbResult, "TIME"), //$NON-NLS-1$ //$NON-NLS-2$
                    unit == null ? "" : unit); //$NON-NLS-1$
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, table.getDataSource());
        }
    }

    @NotNull
    @Override
    public String getStatement(@NotNull DBSTable table, @NotNull Change change) {
        String name = DBUtils.getObjectFullName(table, DBPEvaluationContext.DDL);
        DBSEntityAttribute column = change.column();
        if (column == null) {
            return KEYWORD_ALTER_TABLE + name + KEYWORD_USING_NOTTL;
        }
        Unit unit = Objects.requireNonNull(change.unit(), "TTL unit"); //$NON-NLS-1$
        return KEYWORD_ALTER_TABLE + name + KEYWORD_USING_TTL + change.amount() + " " + unit.keyword() //$NON-NLS-1$
            + KEYWORD_ON_COLUMN + DBUtils.getQuotedIdentifier(column);
    }

}
