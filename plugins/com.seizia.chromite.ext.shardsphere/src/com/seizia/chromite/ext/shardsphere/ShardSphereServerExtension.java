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
package com.seizia.chromite.ext.shardsphere;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.extension.AltibaseServerExtension;
import com.seizia.chromite.ext.altibase.extension.AltibaseServerExtensionState;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.model.ShardSphereServer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognises a ShardSphere server by its catalog.
 *
 * 설계: 드라이버(`altibase:shardsphere`)나 제품 이름이 아니라 서버 카탈로그의 세 가지를 따로 읽는다: GEMINI(ShardSphere의 복제) 카탈로그
 * 표 {@code SYSTEM_.SYS_GEMINIS_}, 샤딩 패키지 {@code SYS.DBMS_SHARD}, 샤딩 메타 노드(사용자 {@code SYS_SHARD}가 있고
 * {@code SHARD_META_ENABLE = 1}). 일반 ShardSphere 서버는 GEMINI와 패키지가 있고 메타 노드가 아니며, Altibase 7.3에는 셋 다 없다
 * (2026-11-17 세 서버에서 읽음, docs/chromite/shardsphere.md "감지"). 셋을 한 SELECT로 읽어 연결마다 왕복 한 번이다. 없는 표를
 * 준비해 보는 대신 카탈로그 행을 세므로 Altibase에서도 오류가 나지 않는다(로그 없음). 읽지 못하면 "없음"이다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 감지만(앱 변경 #139 작업 2·3, docs/chromite/shardsphere.md)
 * - 2026-11-17 메타 노드면 노드 이름을 한 번 더 읽음({@code SYS_SHARD.NODES_}의 이름만, 호스트·포트는 읽지 않음): {@code NODE[data(} 뒤
 *   자동 완성(앱 변경 #139 작업 6b, docs/chromite/shardsphere.md "접두어 문장 다시 읽기")
 */
public class ShardSphereServerExtension implements AltibaseServerExtension {

    private static final Log log = Log.getLog(ShardSphereServerExtension.class);

    /**
     * GEMINI catalog table rows, DBMS_SHARD package rows (spec and body), SYS_SHARD user rows, SHARD_META_ENABLE = 1 rows.
     */
    private static final String QUERY_DETECT = "SELECT"
        + " (SELECT COUNT(*) FROM SYSTEM_.SYS_TABLES_ T, SYSTEM_.SYS_USERS_ U"
        + " WHERE T.USER_ID = U.USER_ID AND U.USER_NAME = 'SYSTEM_' AND T.TABLE_NAME = 'SYS_GEMINIS_'),"
        + " (SELECT COUNT(*) FROM SYSTEM_.SYS_PACKAGES_ P, SYSTEM_.SYS_USERS_ U"
        + " WHERE P.USER_ID = U.USER_ID AND U.USER_NAME = 'SYS' AND P.PACKAGE_NAME = 'DBMS_SHARD'),"
        + " (SELECT COUNT(*) FROM SYSTEM_.SYS_USERS_ WHERE USER_NAME = 'SYS_SHARD'),"
        + " (SELECT COUNT(*) FROM V$PROPERTY WHERE NAME = 'SHARD_META_ENABLE' AND VALUE1 = '1')"
        + " FROM DUAL";

    /**
     * The node names of a sharding meta node (names only: the hosts and ports of the nodes are not read).
     */
    private static final String QUERY_NODE_NAMES = "SELECT NODE_NAME FROM SYS_SHARD.NODES_ ORDER BY NODE_ID";

    @Nullable
    @Override
    public AltibaseServerExtensionState detect(@NotNull JDBCSession session, @NotNull AltibaseDataSource dataSource) {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_DETECT);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            if (!dbResult.next()) {
                return null;
            }
            boolean geminiCatalog = dbResult.getLong(1) > 0;
            boolean shardingPackage = dbResult.getLong(2) > 0;
            boolean shardingMetaNode = dbResult.getLong(3) > 0 && dbResult.getLong(4) > 0;
            if (!geminiCatalog && !shardingPackage && !shardingMetaNode) {
                return null;
            }
            return new ShardSphereServer(dataSource, geminiCatalog, shardingPackage, shardingMetaNode,
                shardingMetaNode ? readNodeNames(session) : List.of());
        } catch (SQLException e) {
            log.debug("Can't read the ShardSphere catalog: " + e.getErrorCode() + " " + e.getSQLState());
            return null;
        }
    }

    /**
     * The node names of the meta node's sharding dictionary, for the completion of {@code NODE[data(}; empty if they
     * can't be read (the completion then proposes no names).
     */
    @NotNull
    private static List<String> readNodeNames(@NotNull JDBCSession session) {
        List<String> names = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_NODE_NAMES);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String name = dbResult.getString(1);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        } catch (SQLException e) {
            log.debug("Can't read the sharding node names: " + e.getErrorCode() + " " + e.getSQLState());
            return List.of();
        }
        return names;
    }

}
