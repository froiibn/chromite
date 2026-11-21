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

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.AltibaseSchema;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.List;

/**
 * The stream objects one schema (user) owns on a ShardSphere server: the "Stream" node under the schema in Object Explorer,
 * with the folders Stream Tables, Continuous Tables and Continuous Queries that this bundle's {@code plugin.xml} injects.
 *
 * 설계: 스트림 테이블·연속 테이블·연속 쿼리는 사용자가 소유하므로 그 스키마 아래에 둔다(Altibase 트리의 스키마 자리
 * {@code schemaExtension}, {@link ShardSphereServer#getSchemaTreeObjects}). 스키마 목록을 읽을 때 이 객체는 질의 없이 만들어지고,
 * 폴더를 처음 열 때 그 폴더의 목록을 읽는다. 카탈로그의 {@code SYS_TABLES_.TABLE_TYPE}으로 가른다(2026-11-21 시험용 개체로 확인):
 * {@code X} 스트림 테이블, {@code B}(집계 저장 테이블 {@code <t>$AGGR}이 있음)·{@code C} 연속 테이블, {@code Y} 연속 스트림 테이블,
 * {@code I} 연속 쿼리. 연속 테이블·스트림 테이블·쿼리는 {@code SYS_CONTINUOUS_TABLES_} 한 SELECT로 함께 읽고 폴더마다 나눈다.
 * 새로 고침은 읽은 것을 비운다. 읽기 전용이다(객체 관리자 없음).
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #153, docs/chromite/shardsphere.md "스트림 탐색기")
 */
public class StreamSchema implements DBPRefreshableObject {

    /**
     * The stream tables of one user (TABLE_TYPE 'X').
     */
    static final String QUERY_STREAM_TABLES = "SELECT T.TABLE_NAME, T.TABLE_ID, T.COLUMN_COUNT, T.TBS_NAME, T.CREATED"
        + " FROM SYSTEM_.SYS_TABLES_ T JOIN SYSTEM_.SYS_USERS_ U ON U.USER_ID = T.USER_ID"
        + " WHERE U.USER_NAME = ? AND T.TABLE_TYPE = 'X' ORDER BY T.TABLE_NAME";

    /**
     * The continuous tables, continuous stream tables and continuous queries of one user, with the storage table's name
     * and the TTL: a continuous table with an aggregation has its TTL on the storage table, one without on itself.
     */
    private static final String QUERY_CONTINUOUS = "SELECT T.TABLE_NAME, T.TABLE_TYPE, T.TABLE_ID, C.VIEW_ID,"
        + " A.TABLE_NAME AS AGGR_TABLE_NAME, C.WINDOW_UNIT, C.WINDOW_TIME, C.WINDOW_STEP_UNIT, C.WINDOW_STEP_TIME,"
        + " C.EXEC_GROUP, C.EXEC_ORDER, C.IS_ENABLE, C.EXEC_QUERY1, C.EXEC_QUERY2, C.EXEC_QUERY3, C.EXEC_QUERY4, %s"
        + " FROM SYSTEM_.SYS_CONTINUOUS_TABLES_ C JOIN SYSTEM_.SYS_TABLES_ T ON T.TABLE_ID = C.TABLE_ID"
        + " JOIN SYSTEM_.SYS_USERS_ U ON U.USER_ID = T.USER_ID"
        + " LEFT OUTER JOIN SYSTEM_.SYS_TABLES_ A ON A.TABLE_ID = C.AGGR_TABLE_ID%s"
        + " WHERE U.USER_NAME = ? ORDER BY T.TABLE_NAME";
    private static final String TTL_COLUMNS = "X.UNIT AS TTL_UNIT, X.TIME AS TTL_TIME";
    private static final String TTL_JOIN = " LEFT OUTER JOIN SYSTEM_.SYS_TTL_ X ON X.TABLE_ID = NVL(C.AGGR_TABLE_ID, C.TABLE_ID)";
    private static final String NO_TTL_COLUMNS = "NULL AS TTL_UNIT, NULL AS TTL_TIME";

    @NotNull
    private final ShardSphereServer server;
    @NotNull
    private final AltibaseSchema schema;
    private final StreamTableCache streamTableCache = new StreamTableCache();
    private final ContinuousCache continuousCache = new ContinuousCache();

    StreamSchema(@NotNull ShardSphereServer server, @NotNull AltibaseSchema schema) {
        this.server = server;
        this.schema = schema;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return ShardSphereMessages.stream_node_name;
    }

    /**
     * Lets the injected folders tell this object from another extension's object at the same tree place ({@code visibleIf}).
     */
    public boolean isStreamSchema() {
        return true;
    }

    @NotNull
    public List<StreamTable> getStreamTables(@NotNull DBRProgressMonitor monitor) throws DBException {
        return streamTableCache.getAllObjects(monitor, this);
    }

    @NotNull
    public List<ContinuousObject> getContinuousTables(@NotNull DBRProgressMonitor monitor) throws DBException {
        return continuousCache.getAllObjects(monitor, this).stream().filter(object -> !object.isQuery()).toList();
    }

    @NotNull
    public List<ContinuousObject> getContinuousQueries(@NotNull DBRProgressMonitor monitor) throws DBException {
        return continuousCache.getAllObjects(monitor, this).stream().filter(ContinuousObject::isQuery).toList();
    }

    @NotNull
    AltibaseSchema getSchema() {
        return schema;
    }

    /**
     * Forgets what the folders read, so that they read it again.
     */
    @NotNull
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) {
        streamTableCache.clearCache();
        continuousCache.clearCache();
        return this;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public AltibaseSchema getParentObject() {
        return schema;
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

    private static class StreamTableCache extends JDBCObjectCache<StreamSchema, StreamTable> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull StreamSchema owner) throws SQLException {
            JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_STREAM_TABLES);
            dbStat.setString(1, owner.schema.getName());
            return dbStat;
        }

        @NotNull
        @Override
        protected StreamTable fetchObject(@NotNull JDBCSession session, @NotNull StreamSchema owner, @NotNull JDBCResultSet resultSet) {
            return new StreamTable(owner, resultSet);
        }
    }

    private static class ContinuousCache extends JDBCObjectCache<StreamSchema, ContinuousObject> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull StreamSchema owner) throws SQLException {
            boolean ttl = owner.server.isTtlCatalog();
            JDBCPreparedStatement dbStat = session.prepareStatement(String.format(QUERY_CONTINUOUS,
                ttl ? TTL_COLUMNS : NO_TTL_COLUMNS, ttl ? TTL_JOIN : "")); //$NON-NLS-1$
            dbStat.setString(1, owner.schema.getName());
            return dbStat;
        }

        @NotNull
        @Override
        protected ContinuousObject fetchObject(@NotNull JDBCSession session, @NotNull StreamSchema owner, @NotNull JDBCResultSet resultSet) {
            return new ContinuousObject(owner, resultSet);
        }
    }

}
