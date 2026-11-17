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
 * One link of the reading session to a data node ({@code V$SHARD_CONNECTION_INFO}).
 *
 * 설계: 뷰는 질의한 세션의 연결만 보이고, 그 세션이 데이터 노드에 무엇을 보낸 뒤에야 행이 생긴다. 탐색기는 행을 채우려고 노드에 문장을
 * 보내지 않고 있는 것만 보인다(앱의 메타데이터 세션: 노드별 행 수를 읽은 뒤 그 노드들의 행이 생긴다). 주소 열(LOCAL_ADDRESS,
 * REMOTE_ADDRESS)은 읽지 않는다: 노드는 이름으로 알아보고 그 주소는 데이터 노드에 있다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardLink implements DBSObject {

    @NotNull
    private final ShardSphereServer server;
    @NotNull
    private final String nodeName;
    @Nullable
    private final Integer nodeId;
    @Nullable
    private final Integer sessionId;
    @Nullable
    private final Integer nodeSessionId;
    @Nullable
    private final Long connectTime;
    @Nullable
    private final Long touchCount;
    @Nullable
    private final Long prepareCount;
    @Nullable
    private final Long executeCount;
    @Nullable
    private final Long shardScn;
    @Nullable
    private final Long linkFailure;

    ShardLink(@NotNull ShardSphereServer server, @NotNull ResultSet dbResult) {
        this.server = server;
        this.nodeName = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "NODE_NAME"));
        this.nodeId = JDBCUtils.safeGetInteger(dbResult, "NODE_ID");
        this.sessionId = JDBCUtils.safeGetInteger(dbResult, "SID");
        this.nodeSessionId = JDBCUtils.safeGetInteger(dbResult, "NODE_SID");
        this.connectTime = getLong(dbResult, "CONNECT_TIME");
        this.touchCount = getLong(dbResult, "TOUCH_COUNT");
        this.prepareCount = getLong(dbResult, "PREPARE_COUNT");
        this.executeCount = getLong(dbResult, "EXECUTE_COUNT");
        this.shardScn = getLong(dbResult, "SHARD_SCN");
        this.linkFailure = getLong(dbResult, "LINK_FAILURE");
    }

    @Nullable
    private static Long getLong(@NotNull ResultSet dbResult, @NotNull String column) {
        Object value = JDBCUtils.safeGetObject(dbResult, column);
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * The data node's name.
     */
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return nodeName;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public Integer getNodeId() {
        return nodeId;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public Integer getSessionId() {
        return sessionId;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public Integer getNodeSessionId() {
        return nodeSessionId;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public Long getConnectTime() {
        return connectTime;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public Long getTouchCount() {
        return touchCount;
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public Long getPrepareCount() {
        return prepareCount;
    }

    @Nullable
    @Property(viewable = true, order = 8)
    public Long getExecuteCount() {
        return executeCount;
    }

    @Nullable
    @Property(viewable = true, order = 9)
    public Long getShardScn() {
        return shardScn;
    }

    @Nullable
    @Property(viewable = true, order = 10)
    public Long getLinkFailure() {
        return linkFailure;
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
