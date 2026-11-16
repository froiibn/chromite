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
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.ResultSet;

/**
 * A data node of the sharding meta node's dictionary ({@code SYS_SHARD.NODES_}).
 *
 * 설계: 사전의 한 행을 읽기 전용으로 보인다(객체 관리자 없음, 노드 관리는 쓰기 서버가 필요한 나중 작업). 노드에 놓인 샤드 객체 수는
 * 같은 질의의 부분 질의로 읽는다(분산 값·복제·독립·기본 노드 가운데 하나라도 이 노드인 객체, {@link ShardingDictionary#QUERY_NODES}).
 * 호스트와 포트는 사전에 있는 그대로 보인다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardNode implements DBSObject {

    @NotNull
    private final ShardSphereServer server;
    private final int id;
    @NotNull
    private final String name;
    @Nullable
    private final String host;
    @Nullable
    private final Integer port;
    @Nullable
    private final String alternateServers;
    @Nullable
    private final String extraOptions;
    private final long objectCount;

    ShardNode(@NotNull ShardSphereServer server, @NotNull ResultSet dbResult) {
        this.server = server;
        this.id = JDBCUtils.safeGetInt(dbResult, "NODE_ID");
        this.name = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "NODE_NAME"));
        this.host = JDBCUtils.safeGetStringTrimmed(dbResult, "HOST_IP");
        this.port = JDBCUtils.safeGetInteger(dbResult, "PORT_NO");
        this.alternateServers = JDBCUtils.safeGetStringTrimmed(dbResult, "ALTERNATE_SERVERS");
        this.extraOptions = JDBCUtils.safeGetStringTrimmed(dbResult, "EXTRA_OPTIONS");
        this.objectCount = JDBCUtils.safeGetLong(dbResult, "OBJECT_COUNT");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public int getId() {
        return id;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getHost() {
        return host;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public Integer getPort() {
        return port;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getAlternateServers() {
        return alternateServers;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getExtraOptions() {
        return extraOptions;
    }

    /**
     * The number of shard objects with a distribution value, a clone, a solo or the default node on this node.
     */
    @Property(viewable = true, order = 7)
    public long getObjectCount() {
        return objectCount;
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

}
