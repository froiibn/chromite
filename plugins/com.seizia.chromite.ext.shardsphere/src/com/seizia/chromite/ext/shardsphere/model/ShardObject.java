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
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

/**
 * A shard object of the sharding meta node's dictionary ({@code SYS_SHARD.OBJECTS_}) and its distribution.
 *
 * 설계: 사전은 카탈로그와 이름(소유자, 객체 이름)으로만 이어지고 번호가 없다. 그래서 실제 테이블로 가는 길은 소유자와 이름 속성이다(Swing
 * 속성 보기에는 객체 참조를 눌러 이동하는 기능이 없다, docs/chromite/shardsphere.md "샤딩 탐색기"). 코드는 서버에서 본 것(종류 {@code T},
 * 방식 {@code H})과 매뉴얼의 식별자로만 아는 것을 이름으로 보이고, 모르는 코드는 코드 그대로 보인다.
 *
 * 분산({@link #getDistribution})은 이 객체를 처음 열 때 한 SELECT로 읽는다(분산 값·복제·독립 행). 해시는 사전에 상한이 글자로 저장되어
 * 글자 순서(1000, 250, …)가 되므로 수로 정렬하고, 다른 방식은 사전에 있는 순서 그대로 둔다(뜻을 모르는 순서를 만들지 않음). 기본 노드는
 * 객체 행에 있으므로 질의 없이 마지막 행으로 더한다.
 *
 * 노드별 행 수({@link #getRowsPerNode})는 모든 데이터 노드에서 COUNT를 하므로 앱의 기존 "비용이 큰 개체 속성 읽기" 설정(기본 꺼짐)이
 * 켜져 있을 때만 속성 보기에 보이고 읽힌다(목록 열에는 없음). 트리를 열 때는 읽지 않는다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardObject implements DBPRefreshableObject {

    private static final String OBJECT_TYPE_TABLE = "T"; //$NON-NLS-1$
    private static final String OBJECT_TYPE_PROCEDURE = "P"; //$NON-NLS-1$
    private static final String SPLIT_HASH = "H"; //$NON-NLS-1$
    private static final String SPLIT_RANGE = "R"; //$NON-NLS-1$
    private static final String SPLIT_LIST = "L"; //$NON-NLS-1$
    private static final String SPLIT_CLONE = "C"; //$NON-NLS-1$
    private static final String SPLIT_SOLO = "S"; //$NON-NLS-1$

    // RANGES_.SUB_VALUE of an object without a sub-shard key
    private static final String NO_SUB_VALUE = "$$N/A"; //$NON-NLS-1$

    // Kind column of QUERY_DISTRIBUTION
    private static final String ROW_VALUE = "V"; //$NON-NLS-1$
    private static final String ROW_CLONE = "C"; //$NON-NLS-1$
    private static final String ROW_SOLO = "S"; //$NON-NLS-1$

    /**
     * The value rows, clones and solos of one shard object with the node names (the shard id bound three times).
     */
    private static final String QUERY_DISTRIBUTION = "SELECT 'V' AS KIND, R.VALUE, R.SUB_VALUE, R.NODE_ID, N.NODE_NAME"
        + " FROM SYS_SHARD.RANGES_ R LEFT OUTER JOIN SYS_SHARD.NODES_ N ON N.NODE_ID = R.NODE_ID WHERE R.SHARD_ID = ?"
        + " UNION ALL SELECT 'C', NULL, NULL, C.NODE_ID, N.NODE_NAME"
        + " FROM SYS_SHARD.CLONES_ C LEFT OUTER JOIN SYS_SHARD.NODES_ N ON N.NODE_ID = C.NODE_ID WHERE C.SHARD_ID = ?"
        + " UNION ALL SELECT 'S', NULL, NULL, S.NODE_ID, N.NODE_NAME"
        + " FROM SYS_SHARD.SOLOS_ S LEFT OUTER JOIN SYS_SHARD.NODES_ N ON N.NODE_ID = S.NODE_ID WHERE S.SHARD_ID = ?";

    /**
     * Rows per data node of a shard table: one result row per node, in node order (a GROUP BY on the node name fails).
     * The only statement of the explorer that reaches the data nodes.
     */
    private static final String QUERY_ROWS_PER_NODE = "NODE[data] SELECT SHARD_NODE_NAME(), COUNT(*) FROM %s.%s";

    @NotNull
    private final ShardSphereServer server;
    private final int shardId;
    @NotNull
    private final String owner;
    @NotNull
    private final String name;
    @Nullable
    private final String objectType;
    @Nullable
    private final String splitMethod;
    @Nullable
    private final String keyColumn;
    @Nullable
    private final String subSplitMethod;
    @Nullable
    private final String subKeyColumn;
    @Nullable
    private final Integer defaultNodeId;
    @Nullable
    private final String defaultNodeName;

    private final DistributionCache distributionCache = new DistributionCache();
    // Read on request only (see getRowsPerNode); reset by a refresh
    @Nullable
    private volatile String rowsPerNode;

    ShardObject(@NotNull ShardSphereServer server, @NotNull ResultSet dbResult) {
        this.server = server;
        this.shardId = JDBCUtils.safeGetInt(dbResult, "SHARD_ID");
        this.owner = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "USER_NAME"));
        this.name = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "OBJECT_NAME"));
        this.objectType = JDBCUtils.safeGetStringTrimmed(dbResult, "OBJECT_TYPE");
        this.splitMethod = JDBCUtils.safeGetStringTrimmed(dbResult, "SPLIT_METHOD");
        this.keyColumn = JDBCUtils.safeGetStringTrimmed(dbResult, "KEY_COLUMN_NAME");
        this.subSplitMethod = JDBCUtils.safeGetStringTrimmed(dbResult, "SUB_SPLIT_METHOD");
        this.subKeyColumn = JDBCUtils.safeGetStringTrimmed(dbResult, "SUB_KEY_COLUMN_NAME");
        this.defaultNodeId = JDBCUtils.safeGetInteger(dbResult, "DEFAULT_NODE_ID");
        this.defaultNodeName = JDBCUtils.safeGetStringTrimmed(dbResult, "DEFAULT_NODE_NAME");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getOwner() {
        return owner;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getKind() {
        return objectTypeLabel(objectType);
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getSplitMethod() {
        return splitMethodLabel(splitMethod);
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getKeyColumn() {
        return keyColumn;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getSubSplitMethod() {
        return splitMethodLabel(subSplitMethod);
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getSubKeyColumn() {
        return subKeyColumn;
    }

    @Nullable
    @Property(viewable = true, order = 8)
    public String getDefaultNode() {
        if (defaultNodeName != null) {
            return defaultNodeName;
        }
        return defaultNodeId == null ? null : String.valueOf(defaultNodeId);
    }

    @Property(viewable = true, order = 9)
    public int getShardId() {
        return shardId;
    }

    /**
     * The rows of each data node, as "node: rows" (a shard table only). Counts on every data node, so it is an expensive
     * property: read only when the user turned on reading expensive properties, and only in the property view.
     */
    @Nullable
    @Property(order = 10, expensive = true)
    public String getRowsPerNode(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!OBJECT_TYPE_TABLE.equals(objectType)) {
            return null;
        }
        String result = rowsPerNode;
        if (result == null) {
            result = readRowsPerNode(monitor);
            rowsPerNode = result;
        }
        return result;
    }

    @NotNull
    private String readRowsPerNode(@NotNull DBRProgressMonitor monitor) throws DBException {
        AltibaseDataSource dataSource = getDataSource();
        String sql = String.format(QUERY_ROWS_PER_NODE,
            DBUtils.getQuotedIdentifier(dataSource, owner, true, true),
            DBUtils.getQuotedIdentifier(dataSource, name, true, true));
        StringBuilder result = new StringBuilder();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read rows per node of shard table '" + name + "'");
             JDBCPreparedStatement dbStat = session.prepareStatement(sql);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                if (!result.isEmpty()) {
                    result.append(", "); //$NON-NLS-1$
                }
                result.append(JDBCUtils.safeGetStringTrimmed(dbResult, 1)).append(": ").append(dbResult.getLong(2)); //$NON-NLS-1$
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        return result.toString();
    }

    /**
     * Value rows (hash upper bounds in numeric order, other methods as the dictionary lists them), clones, solos and the
     * default node.
     */
    @NotNull
    public List<ShardPlacement> getDistribution(@NotNull DBRProgressMonitor monitor) throws DBException {
        return distributionCache.getAllObjects(monitor, this);
    }

    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) {
        distributionCache.clearCache();
        rowsPerNode = null;
        return this;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public ShardSphereServer getParentObject() {
        return server;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return server.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    /**
     * The names of OBJECTS_.OBJECT_TYPE: T seen on the server; P from the manual's identifiers only. Other codes as they are.
     */
    @Nullable
    private static String objectTypeLabel(@Nullable String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case OBJECT_TYPE_TABLE -> ShardSphereMessages.shard_object_kind_table;
            case OBJECT_TYPE_PROCEDURE -> ShardSphereMessages.shard_object_kind_procedure;
            default -> code;
        };
    }

    /**
     * The names of OBJECTS_.SPLIT_METHOD and SUB_SPLIT_METHOD: H seen on the server; R, L, C, S from the manual's
     * identifiers only. Other codes as they are.
     */
    @Nullable
    private static String splitMethodLabel(@Nullable String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case SPLIT_HASH -> ShardSphereMessages.shard_split_hash;
            case SPLIT_RANGE -> ShardSphereMessages.shard_split_range;
            case SPLIT_LIST -> ShardSphereMessages.shard_split_list;
            case SPLIT_CLONE -> ShardSphereMessages.shard_split_clone;
            case SPLIT_SOLO -> ShardSphereMessages.shard_split_solo;
            default -> code;
        };
    }

    /**
     * A hash upper bound as a number; null if it is not one (then the dictionary order is kept).
     */
    @Nullable
    private static BigDecimal toNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class DistributionCache extends JDBCObjectCache<ShardObject, ShardPlacement> {

        DistributionCache() {
            // Value rows, clones, solos, the default node; within value rows of a hash object the bounds as numbers.
            // List.sort is stable: rows that compare equal keep the dictionary order.
            setListOrderComparator(Comparator.comparing(ShardPlacement::getPlacementKind)
                .thenComparing((first, second) -> compareHashBounds(first, second)));
        }

        private static int compareHashBounds(@NotNull ShardPlacement first, @NotNull ShardPlacement second) {
            if (first.getPlacementKind() != ShardPlacement.Kind.VALUE || !SPLIT_HASH.equals(first.getParentObject().splitMethod)) {
                return 0;
            }
            BigDecimal firstBound = toNumber(first.getValue());
            BigDecimal secondBound = toNumber(second.getValue());
            if (firstBound == null || secondBound == null) {
                return 0;
            }
            return firstBound.compareTo(secondBound);
        }

        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull ShardObject owner) throws SQLException {
            JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_DISTRIBUTION);
            dbStat.setInt(1, owner.shardId);
            dbStat.setInt(2, owner.shardId);
            dbStat.setInt(3, owner.shardId);
            return dbStat;
        }

        @Nullable
        @Override
        protected ShardPlacement fetchObject(@NotNull JDBCSession session, @NotNull ShardObject owner, @NotNull JDBCResultSet resultSet) {
            String rowKind = JDBCUtils.safeGetStringTrimmed(resultSet, "KIND");
            ShardPlacement.Kind kind;
            if (ROW_VALUE.equals(rowKind)) {
                kind = ShardPlacement.Kind.VALUE;
            } else if (ROW_CLONE.equals(rowKind)) {
                kind = ShardPlacement.Kind.CLONE;
            } else if (ROW_SOLO.equals(rowKind)) {
                kind = ShardPlacement.Kind.SOLO;
            } else {
                return null;
            }
            String subValue = JDBCUtils.safeGetStringTrimmed(resultSet, "SUB_VALUE");
            return new ShardPlacement(
                owner,
                kind,
                JDBCUtils.safeGetStringTrimmed(resultSet, "VALUE"),
                NO_SUB_VALUE.equals(subValue) ? null : subValue,
                JDBCUtils.safeGetInteger(resultSet, "NODE_ID"),
                JDBCUtils.safeGetStringTrimmed(resultSet, "NODE_NAME"));
        }

        @Override
        protected void addCustomObjects(
            @NotNull DBRProgressMonitor monitor,
            @NotNull ShardObject owner,
            @NotNull List<ShardPlacement> objectList
        ) {
            if (owner.defaultNodeId != null) {
                objectList.add(new ShardPlacement(
                    owner, ShardPlacement.Kind.DEFAULT_NODE, null, null, owner.defaultNodeId, owner.defaultNodeName));
            }
        }
    }

}
