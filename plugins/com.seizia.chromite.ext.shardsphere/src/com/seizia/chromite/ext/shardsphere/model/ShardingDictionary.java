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
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.List;

/**
 * The sharding dictionary of one connection to a sharding meta node: what the explorer reads, each part on first use.
 *
 * 설계: 메타 노드인 연결의 {@link ShardSphereServer}만 이 객체를 갖는다. 그래서 다른 서버(일반 ShardSphere, Altibase)에는 질의가 하나도 더
 * 가지 않는다(감지 문장뿐). 모든 문장은 SELECT이고 메타 노드 자신에서 실행된다(노드별 행 수만 데이터 노드로, {@link ShardObject}). 각 부분은
 * 트리 폴더를 처음 열 때 읽고, 새로 고침({@link #clear})이 비운다. 샤딩 속성은 System Info의 Properties와 같은 객체
 * ({@link ShardingProperty}, Altibase의 속성 객체)로 보인다: 같은 {@code V$PROPERTY} 행이고 열 이름과 번역이 이미 있다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardingDictionary {

    /**
     * The data nodes with the number of shard objects placed on each (a distribution value, a clone, a solo or the
     * default node).
     */
    static final String QUERY_NODES = "SELECT N.NODE_ID, N.NODE_NAME, N.HOST_IP, N.PORT_NO, N.ALTERNATE_SERVERS, N.EXTRA_OPTIONS,"
        + " (SELECT COUNT(*) FROM SYS_SHARD.OBJECTS_ O WHERE O.DEFAULT_NODE_ID = N.NODE_ID"
        + " OR O.SHARD_ID IN (SELECT R.SHARD_ID FROM SYS_SHARD.RANGES_ R WHERE R.NODE_ID = N.NODE_ID)"
        + " OR O.SHARD_ID IN (SELECT C.SHARD_ID FROM SYS_SHARD.CLONES_ C WHERE C.NODE_ID = N.NODE_ID)"
        + " OR O.SHARD_ID IN (SELECT S.SHARD_ID FROM SYS_SHARD.SOLOS_ S WHERE S.NODE_ID = N.NODE_ID)) AS OBJECT_COUNT"
        + " FROM SYS_SHARD.NODES_ N ORDER BY N.NODE_ID";

    private static final String QUERY_OBJECTS = "SELECT O.SHARD_ID, O.USER_NAME, O.OBJECT_NAME, O.OBJECT_TYPE, O.SPLIT_METHOD,"
        + " O.KEY_COLUMN_NAME, O.SUB_SPLIT_METHOD, O.SUB_KEY_COLUMN_NAME, O.DEFAULT_NODE_ID, N.NODE_NAME AS DEFAULT_NODE_NAME"
        + " FROM SYS_SHARD.OBJECTS_ O LEFT OUTER JOIN SYS_SHARD.NODES_ N ON N.NODE_ID = O.DEFAULT_NODE_ID"
        + " ORDER BY O.USER_NAME, O.OBJECT_NAME";

    private static final String QUERY_PROPERTIES = "SELECT * FROM V$PROPERTY WHERE NAME LIKE 'SHARD\\_%' ESCAPE '\\' ORDER BY NAME";

    /**
     * The links of the reading session only (the view shows no other session's); without the address columns.
     */
    private static final String QUERY_LINKS = "SELECT NODE_ID, NODE_NAME, SID, NODE_SID, CONNECT_TIME, TOUCH_COUNT, PREPARE_COUNT,"
        + " EXECUTE_COUNT, SHARD_SCN, LINK_FAILURE FROM V$SHARD_CONNECTION_INFO ORDER BY NODE_ID";

    private static final String QUERY_VERSION = "SELECT MAJOR_VER, MINOR_VER, PATCH_VER FROM SYS_SHARD.VERSION_";

    private final NodeCache nodeCache = new NodeCache();
    private final ObjectCache objectCache = new ObjectCache();
    private final PropertyCache propertyCache = new PropertyCache();
    private final LinkCache linkCache = new LinkCache();
    // "major.minor.patch"; null until read
    @Nullable
    private volatile String version;

    @NotNull
    List<ShardNode> getNodes(@NotNull DBRProgressMonitor monitor, @NotNull ShardSphereServer server) throws DBException {
        return nodeCache.getAllObjects(monitor, server);
    }

    @NotNull
    List<ShardObject> getObjects(@NotNull DBRProgressMonitor monitor, @NotNull ShardSphereServer server) throws DBException {
        return objectCache.getAllObjects(monitor, server);
    }

    @NotNull
    List<ShardingProperty> getProperties(@NotNull DBRProgressMonitor monitor, @NotNull ShardSphereServer server) throws DBException {
        return propertyCache.getAllObjects(monitor, server);
    }

    @NotNull
    List<ShardLink> getLinks(@NotNull DBRProgressMonitor monitor, @NotNull ShardSphereServer server) throws DBException {
        return linkCache.getAllObjects(monitor, server);
    }

    @Nullable
    String getVersion(@NotNull DBRProgressMonitor monitor, @NotNull ShardSphereServer server) throws DBException {
        String result = version;
        if (result == null) {
            try (JDBCSession session = DBUtils.openMetaSession(monitor, server, "Read the sharding dictionary version");
                 JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_VERSION);
                 JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    return null;
                }
                result = dbResult.getInt(1) + "." + dbResult.getInt(2) + "." + dbResult.getInt(3); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (SQLException e) {
                throw new DBDatabaseException(e, server.getDataSource());
            }
            version = result;
        }
        return result;
    }

    /**
     * Forgets everything read, so that the tree reads it again (a refresh of the ShardSphere node or one of its folders).
     */
    void clear() {
        nodeCache.clearCache();
        objectCache.clearCache();
        propertyCache.clearCache();
        linkCache.clearCache();
        version = null;
    }

    private static class NodeCache extends JDBCObjectCache<ShardSphereServer, ShardNode> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull ShardSphereServer owner) throws SQLException {
            return session.prepareStatement(QUERY_NODES);
        }

        @NotNull
        @Override
        protected ShardNode fetchObject(@NotNull JDBCSession session, @NotNull ShardSphereServer owner, @NotNull JDBCResultSet resultSet) {
            return new ShardNode(owner, resultSet);
        }
    }

    private static class ObjectCache extends JDBCObjectCache<ShardSphereServer, ShardObject> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull ShardSphereServer owner) throws SQLException {
            return session.prepareStatement(QUERY_OBJECTS);
        }

        @NotNull
        @Override
        protected ShardObject fetchObject(@NotNull JDBCSession session, @NotNull ShardSphereServer owner, @NotNull JDBCResultSet resultSet) {
            return new ShardObject(owner, resultSet);
        }
    }

    private static class PropertyCache extends JDBCObjectCache<ShardSphereServer, ShardingProperty> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull ShardSphereServer owner) throws SQLException {
            return session.prepareStatement(QUERY_PROPERTIES);
        }

        @NotNull
        @Override
        protected ShardingProperty fetchObject(@NotNull JDBCSession session, @NotNull ShardSphereServer owner, @NotNull JDBCResultSet resultSet) {
            return new ShardingProperty(owner, resultSet);
        }
    }

    private static class LinkCache extends JDBCObjectCache<ShardSphereServer, ShardLink> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull ShardSphereServer owner) throws SQLException {
            return session.prepareStatement(QUERY_LINKS);
        }

        @NotNull
        @Override
        protected ShardLink fetchObject(@NotNull JDBCSession session, @NotNull ShardSphereServer owner, @NotNull JDBCResultSet resultSet) {
            return new ShardLink(owner, resultSet);
        }
    }

}
