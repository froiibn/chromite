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
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPObjectWithContributedProperties.ContributedProperty;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a ShardSphere server adds to a table's property view: its TTL ({@code SYSTEM_.SYS_TTL_}) and, on a sharding meta
 * node, its shard object ({@code SYS_SHARD.OBJECTS_}: split method and shard key).
 *
 * 설계: 속성 보기가 테이블 하나를 보일 때만 읽는다(Altibase 모델의 기여 자리, {@code AltibaseServerExtensionState#getObjectProperties}).
 * 서버에 그 사전이 있을 때만 그 문장을 보낸다: TTL은 감지 때 {@code SYS_TTL_}가 있던 연결, 샤드 객체는 메타 노드. 둘 다 소유자와 이름으로
 * 찾는다(샤딩 사전에는 테이블 번호가 없고, Altibase 테이블 객체도 번호를 갖지 않는다). 행이 없으면 아무것도 보이지 않는다.
 * <ul>
 *     <li>TTL 단위 코드: Y 년, Q 분기, O 월, W 주, D 일, H 시간, M 분, S 초(스트림 매뉴얼의 {@code SYS_TTL_} 설명). 모르는 코드는 수와
 *     코드 그대로. 기준 컬럼은 {@code SYS_COLUMNS_}에서 {@code TABLE_ID}·{@code COLUMN_ID}로 찾고, 못 찾으면 번호를 보인다.
 *     실제 TTL 행을 본 적이 없다(두 서버 모두 0건, 2026-11-17): 문장은 두 서버에서 실행해 0건, 표시는 손으로 만든 행으로만 확인.</li>
 *     <li>분산 방식 이름은 샤딩 탐색기와 같다({@link ShardObject#splitMethodLabel}, 시스피어가 답한 코드).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 테이블의 TTL과 샤드 객체(앱 변경 #139 수정 6, docs/chromite/shardsphere.md "테이블 속성: TTL과 샤드")
 */
public final class ShardTableProperties {

    /**
     * The TTL rows of one table (owner, name); the column by its id.
     */
    static final String QUERY_TTL = "SELECT T.COLUMN_ID, C.COLUMN_NAME, T.TIME, T.UNIT FROM SYSTEM_.SYS_TTL_ T"
        + " JOIN SYSTEM_.SYS_TABLES_ B ON B.TABLE_ID = T.TABLE_ID"
        + " JOIN SYSTEM_.SYS_USERS_ U ON U.USER_ID = B.USER_ID"
        + " LEFT OUTER JOIN SYSTEM_.SYS_COLUMNS_ C ON C.TABLE_ID = T.TABLE_ID AND C.COLUMN_ID = T.COLUMN_ID"
        + " WHERE U.USER_NAME = ? AND B.TABLE_NAME = ?";

    /**
     * The shard object of one table (owner, name) in the meta node's sharding dictionary.
     */
    static final String QUERY_SHARD_OBJECT = "SELECT O.SPLIT_METHOD, O.KEY_COLUMN_NAME, O.SUB_SPLIT_METHOD, O.SUB_KEY_COLUMN_NAME"
        + " FROM SYS_SHARD.OBJECTS_ O WHERE O.OBJECT_TYPE = 'T' AND O.USER_NAME = ? AND O.OBJECT_NAME = ?";

    private ShardTableProperties() {
    }

    /**
     * Reads the TTL (if {@code ttl}) and the shard object (if {@code shard}) of the table.
     */
    @NotNull
    static List<ContributedProperty> read(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSTable table,
        boolean ttl,
        boolean shard
    ) throws DBException {
        List<ContributedProperty> properties = new ArrayList<>();
        if (!ttl && !shard) {
            return properties;
        }
        String owner = table.getParentObject() == null ? null : table.getParentObject().getName();
        if (owner == null) {
            return properties;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, table, "Read ShardSphere properties of table '" + table.getName() + "'")) {
            if (ttl) {
                try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_TTL)) {
                    dbStat.setString(1, owner);
                    dbStat.setString(2, table.getName());
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        while (dbResult.next()) {
                            addTtl(properties, dbResult);
                        }
                    }
                }
            }
            if (shard) {
                try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_SHARD_OBJECT)) {
                    dbStat.setString(1, owner);
                    dbStat.setString(2, table.getName());
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        if (dbResult.next()) {
                            addShardObject(properties, dbResult);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, table.getDataSource());
        }
        return properties;
    }

    private static void addTtl(@NotNull List<ContributedProperty> properties, @NotNull JDBCResultSet dbResult) {
        String columnName = JDBCUtils.safeGetStringTrimmed(dbResult, "COLUMN_NAME");
        String column = columnName != null ? columnName : JDBCUtils.safeGetString(dbResult, "COLUMN_ID");
        String category = ShardSphereMessages.table_category_ttl;
        properties.add(new ContributedProperty(category, ShardSphereMessages.table_ttl_column, column, null));
        properties.add(new ContributedProperty(category, ShardSphereMessages.table_ttl_period,
            ttlPeriod(JDBCUtils.safeGetInteger(dbResult, "TIME"), JDBCUtils.safeGetStringTrimmed(dbResult, "UNIT")), null));
    }

    private static void addShardObject(@NotNull List<ContributedProperty> properties, @NotNull JDBCResultSet dbResult) {
        String category = ShardSphereMessages.table_category_sharding;
        properties.add(new ContributedProperty(category, ShardSphereMessages.table_split_method,
            ShardObject.splitMethodLabel(JDBCUtils.safeGetStringTrimmed(dbResult, "SPLIT_METHOD")), null));
        properties.add(new ContributedProperty(category, ShardSphereMessages.table_shard_key,
            JDBCUtils.safeGetStringTrimmed(dbResult, "KEY_COLUMN_NAME"), null));
        String subSplitMethod = JDBCUtils.safeGetStringTrimmed(dbResult, "SUB_SPLIT_METHOD");
        String subKey = JDBCUtils.safeGetStringTrimmed(dbResult, "SUB_KEY_COLUMN_NAME");
        // Only a sub-sharded table has the second level
        if (subSplitMethod != null || subKey != null) {
            properties.add(new ContributedProperty(category, ShardSphereMessages.table_sub_split_method,
                ShardObject.splitMethodLabel(subSplitMethod), null));
            properties.add(new ContributedProperty(category, ShardSphereMessages.table_sub_shard_key, subKey, null));
        }
    }

    /**
     * The TTL amount with its unit in words; an unknown unit code as the number and the code.
     */
    @Nullable
    static String ttlPeriod(@Nullable Integer time, @Nullable String unit) {
        if (time == null) {
            return unit;
        }
        if (unit == null) {
            return String.valueOf(time);
        }
        String pattern = switch (unit) {
            case "Y" -> ShardSphereMessages.ttl_unit_year; //$NON-NLS-1$
            case "Q" -> ShardSphereMessages.ttl_unit_quarter; //$NON-NLS-1$
            case "O" -> ShardSphereMessages.ttl_unit_month; //$NON-NLS-1$
            case "W" -> ShardSphereMessages.ttl_unit_week; //$NON-NLS-1$
            case "D" -> ShardSphereMessages.ttl_unit_day; //$NON-NLS-1$
            case "H" -> ShardSphereMessages.ttl_unit_hour; //$NON-NLS-1$
            case "M" -> ShardSphereMessages.ttl_unit_minute; //$NON-NLS-1$
            case "S" -> ShardSphereMessages.ttl_unit_second; //$NON-NLS-1$
            default -> null;
        };
        return pattern == null ? time + " " + unit : NLS.bind(pattern, time); //$NON-NLS-1$
    }

}
