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

import com.seizia.chromite.ext.altibase.extension.AltibaseServerExtensionState;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.ShardSphereStatementPrefix;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * What one connection found on its ShardSphere server.
 *
 * 설계: 새 화면 없이 기존 Object Explorer와 속성 보기로 감지 결과를 보이려고, 이 객체가 연결의 확장 상태이면서 System Info 아래 노드다
 * ({@link #getTreeObjects}). 세 값은 따로 둔다: 다음 작업이 각각에 기대는 기능(GEMINI 트리, 샤딩 탐색기)이 다르고, 일반 서버와 메타 노드가
 * 다르게 답한다. 값은 연결할 때 한 번 읽은 것이고, 연결을 새로 고치면 데이터 원본이 새 객체로 바꾼다. 읽기 전용이다(객체 관리자 없음).
 *
 * 방언 기여(샤딩 문법 낱말, 함수 {@code SHARD_NODE_NAME}·{@code SHARD_KEY}, 문장 접두어 {@code NODE[...]}·{@code SHARD})는 샤딩 패키지가
 * 있는 연결에서만 낸다: 접두어와 낱말은 샤딩 문법이고, 연결의 방언 객체에만 들어간다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 2·3, docs/chromite/shardsphere.md)
 * - 2026-11-17 방언 기여: 샤딩 패키지가 있으면 문장 접두어({@link ShardSphereStatementPrefix}), 문법 낱말, 함수(앱 변경 #139 작업 6a,
 *   docs/chromite/shardsphere.md "문장 접두어")
 */
public class ShardSphereServer implements AltibaseServerExtensionState, DBSObject {

    // The product name, not a translatable label
    private static final String NAME = "ShardSphere"; //$NON-NLS-1$

    /**
     * Words of the sharding statements (statement prefix, CREATE/ALTER/DROP SHARD, ALTER TABLE ... SHARD BY / UNSHARD,
     * EXECUTE META) that V$RESERVED_WORDS does not report on the sharding meta node (2026-11-17). SHARD is reserved there.
     */
    private static final List<String> SHARDING_SYNTAX_WORDS = List.of(
        "NODE", "META", "UNSHARD", "CLONE", "SOLO", "AUTOHASH", "SUBSHARD", "HASH"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    private static final List<String> SHARDING_FUNCTIONS = List.of("SHARD_NODE_NAME", "SHARD_KEY"); //$NON-NLS-1$ //$NON-NLS-2$

    @NotNull
    private final AltibaseDataSource dataSource;
    private final boolean geminiCatalog;
    private final boolean shardingPackage;
    private final boolean shardingMetaNode;

    public ShardSphereServer(
        @NotNull AltibaseDataSource dataSource,
        boolean geminiCatalog,
        boolean shardingPackage,
        boolean shardingMetaNode
    ) {
        this.dataSource = dataSource;
        this.geminiCatalog = geminiCatalog;
        this.shardingPackage = shardingPackage;
        this.shardingMetaNode = shardingMetaNode;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return NAME;
    }

    /**
     * The GEMINI (replication) catalog table SYSTEM_.SYS_GEMINIS_ exists.
     */
    @Property(viewable = true, order = 2)
    public boolean isGeminiCatalog() {
        return geminiCatalog;
    }

    /**
     * The sharding package SYS.DBMS_SHARD exists.
     */
    @Property(viewable = true, order = 3)
    public boolean isShardingPackage() {
        return shardingPackage;
    }

    /**
     * The server is a sharding meta node: the SYS_SHARD user exists and SHARD_META_ENABLE is 1.
     */
    @Property(viewable = true, order = 4)
    public boolean isShardingMetaNode() {
        return shardingMetaNode;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public AltibaseDataSource getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public Collection<? extends DBSObject> getTreeObjects() {
        return List.of(this);
    }

    @NotNull
    @Override
    public Collection<String> getSyntaxKeywords() {
        return shardingPackage ? SHARDING_SYNTAX_WORDS : List.of();
    }

    @NotNull
    @Override
    public Collection<String> getFunctions() {
        return shardingPackage ? SHARDING_FUNCTIONS : List.of();
    }

    @Override
    public int getStatementPrefixLength(@NotNull String text) {
        return shardingPackage ? ShardSphereStatementPrefix.getLength(text) : 0;
    }

}
