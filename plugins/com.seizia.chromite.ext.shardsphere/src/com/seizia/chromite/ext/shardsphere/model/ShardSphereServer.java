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
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.AltibaseReplicationCatalog;
import com.seizia.chromite.ext.altibase.model.AltibaseTable;
import com.seizia.chromite.ext.shardsphere.ShardSphereStatementPrefix;
import com.seizia.chromite.model.DBPObjectWithContributedProperties;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
 * - 2026-11-17 접두어 SELECT의 하위 질의 형태(메타 노드에서만, 그 서버에서 확인한 형태라서)와 입력 중인 접두어의 제안(메타 노드에서는
 *   노드 이름도, 연결할 때 한 번 읽은 이름만)(앱 변경 #139 작업 6b, docs/chromite/shardsphere.md "접두어 문장 다시 읽기")
 * - 2026-11-17 샤딩 탐색기: 메타 노드면 이 노드 아래 폴더(데이터 노드, 샤드 객체, 샤딩 속성, 노드 연결)와 사전 버전 속성. 폴더는 이 번들의
 *   {@code plugin.xml}이 Altibase 트리의 {@code serverExtension} 자리 아래에 넣고 {@code visibleIf}로 메타 노드에서만 보인다. 사전은
 *   {@link ShardingDictionary}가 메타 노드인 연결에서만 갖는다(다른 서버에는 질의 없음). 새로 고침은 읽은 것을 비운다
 *   (앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 * - 2026-11-17 GEMINI 카탈로그가 있으면 연결의 복제 카탈로그로 {@link GeminiReplicationCatalog}를 줌: Replications 폴더와 활동 모니터의
 *   복제 탭이 GEMINI를 읽음(앱 변경 #139 작업 4·5, docs/chromite/shardsphere.md "GEMINI")
 * - 2026-11-17 테이블 속성 기여({@link ShardTableProperties}: {@code SYS_TTL_}가 있으면 TTL, 메타 노드면 샤드 객체), 문서의 함수
 *   ({@code PVTIME_TO_DATE}, {@code EXPORT_CONTINUOUS_OBJECT}, {@code FROM_UNIXTIME}: 두 서버에서 준비됨)와 TTL 문법 낱말
 *   (앱 변경 #139 수정 6·8, docs/chromite/shardsphere.md "테이블 속성: TTL과 샤드", "낱말과 함수")
 * - 2026-11-17 {@code SYS_TTL_}가 있으면 연결의 어댑터로 기존 테이블의 TTL 설정·변경·해제({@link ShardTableTtl}, 중립 자리
 *   {@link AltibaseTableTtl}). 메타 노드의 샤드 테이블에는 내지 않으려고 감지 때 읽은 샤드 테이블 목록을 받음(읽지 못했으면 null → TTL
 *   명령 없음)(앱 변경 #152,
 *   docs/chromite/shardsphere.md "TTL 관리")
 */
public class ShardSphereServer implements AltibaseServerExtensionState, DBPRefreshableObject {

    // The product name, not a translatable label
    private static final String NAME = "ShardSphere"; //$NON-NLS-1$

    /**
     * Words of the sharding statements (statement prefix, CREATE/ALTER/DROP SHARD, ALTER TABLE ... SHARD BY / UNSHARD,
     * EXECUTE META) that V$RESERVED_WORDS does not report on the sharding meta node (2026-11-17). SHARD is reserved there.
     */
    private static final List<String> SHARDING_SYNTAX_WORDS = List.of(
        "NODE", "META", "UNSHARD", "CLONE", "SOLO", "AUTOHASH", "SUBSHARD", "HASH"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    private static final List<String> SHARDING_FUNCTIONS = List.of("SHARD_NODE_NAME", "SHARD_KEY"); //$NON-NLS-1$ //$NON-NLS-2$
    /**
     * Documented functions that both ShardSphere servers prepare and Altibase 7.3 does not (2026-11-17). SHARD_ID (named in
     * the SQL manual) and PVDATE_TO_TIME are not here: both servers answer "function not found".
     */
    private static final List<String> SERVER_FUNCTIONS = List.of(
        "PVTIME_TO_DATE", "EXPORT_CONTINUOUS_OBJECT", "FROM_UNIXTIME"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    /**
     * The TTL clause of CREATE / ALTER TABLE ({@code USING TTL n unit ON COLUMN c}), where the server has SYS_TTL_.
     */
    private static final List<String> TTL_SYNTAX_WORDS = List.of("TTL"); //$NON-NLS-1$

    @NotNull
    private final AltibaseDataSource dataSource;
    private final boolean geminiCatalog;
    private final boolean shardingPackage;
    private final boolean shardingMetaNode;
    // The TTL dictionary table SYSTEM_.SYS_TTL_ exists
    private final boolean ttlCatalog;
    // The node names of the meta node's sharding dictionary (names only), empty on another server
    @NotNull
    private final List<String> nodeNames;
    // The sharding explorer's reads; only on a sharding meta node
    @Nullable
    private final ShardingDictionary sharding;
    // The TTL of existing tables; only where the TTL dictionary table exists
    @Nullable
    private final ShardTableTtl tableTtl;

    /**
     * @param shardTables owner and name of each shard table of a sharding meta node (empty on another server): no TTL
     *                    command for them; null if they could not be read: no TTL command at all
     */
    public ShardSphereServer(
        @NotNull AltibaseDataSource dataSource,
        boolean geminiCatalog,
        boolean shardingPackage,
        boolean shardingMetaNode,
        boolean ttlCatalog,
        @NotNull List<String> nodeNames,
        @Nullable Set<List<String>> shardTables
    ) {
        this.dataSource = dataSource;
        this.geminiCatalog = geminiCatalog;
        this.shardingPackage = shardingPackage;
        this.shardingMetaNode = shardingMetaNode;
        this.ttlCatalog = ttlCatalog;
        this.nodeNames = List.copyOf(nodeNames);
        this.sharding = shardingMetaNode ? new ShardingDictionary() : null;
        this.tableTtl = ttlCatalog && shardTables != null ? new ShardTableTtl(shardTables) : null;
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

    /**
     * The version of the sharding dictionary (SYS_SHARD.VERSION_), only on a sharding meta node.
     */
    @Nullable
    @Property(viewable = true, order = 5, visibleIf = MetaNodeOnly.class)
    public String getShardingVersion(@NotNull DBRProgressMonitor monitor) throws DBException {
        return sharding == null ? null : sharding.getVersion(monitor, this);
    }

    @NotNull
    public List<ShardNode> getShardNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        return sharding == null ? List.of() : sharding.getNodes(monitor, this);
    }

    @NotNull
    public List<ShardObject> getShardObjects(@NotNull DBRProgressMonitor monitor) throws DBException {
        return sharding == null ? List.of() : sharding.getObjects(monitor, this);
    }

    @NotNull
    public List<ShardingProperty> getShardingProperties(@NotNull DBRProgressMonitor monitor) throws DBException {
        return sharding == null ? List.of() : sharding.getProperties(monitor, this);
    }

    @NotNull
    public List<ShardLink> getShardLinks(@NotNull DBRProgressMonitor monitor) throws DBException {
        return sharding == null ? List.of() : sharding.getLinks(monitor, this);
    }

    /**
     * Forgets what the sharding explorer read; the detection itself is renewed by a refresh of the connection.
     */
    @NotNull
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) {
        if (sharding != null) {
            sharding.clear();
        }
        return this;
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
        List<String> words = new ArrayList<>();
        if (shardingPackage) {
            words.addAll(SHARDING_SYNTAX_WORDS);
        }
        if (ttlCatalog) {
            words.addAll(TTL_SYNTAX_WORDS);
        }
        return words;
    }

    @NotNull
    @Override
    public Collection<String> getFunctions() {
        List<String> functions = new ArrayList<>(SERVER_FUNCTIONS);
        if (shardingPackage) {
            functions.addAll(SHARDING_FUNCTIONS);
        }
        return functions;
    }

    /**
     * A table's TTL where the server has SYS_TTL_, and its shard object on a sharding meta node; nothing (no query) for
     * any other object or server.
     */
    @NotNull
    @Override
    public List<DBPObjectWithContributedProperties.ContributedProperty> getObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObject object
    ) throws DBException {
        if (!(object instanceof AltibaseTable table) || !(ttlCatalog || shardingMetaNode)) {
            return List.of();
        }
        return ShardTableProperties.read(monitor, table, ttlCatalog, shardingMetaNode);
    }

    @Override
    public int getStatementPrefixLength(@NotNull String text) {
        return shardingPackage ? ShardSphereStatementPrefix.getLength(text) : 0;
    }

    /**
     * Only on the sharding meta node: the subquery forms were proven there (2026-11-17); on another server a prefixed
     * statement stays without one (not read again).
     */
    @Nullable
    @Override
    public String getPrefixedStatementSubquery(@NotNull String text) {
        return shardingMetaNode ? ShardSphereStatementPrefix.getSubqueryForm(text) : null;
    }

    @Nullable
    @Override
    public SQLDialect.StatementPrefixProposals getStatementPrefixProposals(@NotNull String text) {
        return shardingPackage ? ShardSphereStatementPrefix.getProposals(text, nodeNames) : null;
    }

    /**
     * The TTL of existing tables ({@link AltibaseTableTtl}) where the TTL dictionary table was detected.
     */
    @Nullable
    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        return adapter == AltibaseTableTtl.class && tableTtl != null ? adapter.cast(tableTtl) : null;
    }

    /**
     * GEMINI where its catalog was detected (on any ShardSphere server, meta node or not); otherwise the Altibase catalog.
     */
    @Nullable
    @Override
    public AltibaseReplicationCatalog getReplicationCatalog() {
        return geminiCatalog ? GeminiReplicationCatalog.INSTANCE : null;
    }

    /**
     * Shows a property only on a sharding meta node: the property view of another server's node keeps its three rows.
     */
    public static class MetaNodeOnly implements IPropertyValueValidator<ShardSphereServer, Object> {
        @Override
        public boolean isValidValue(@NotNull ShardSphereServer object, @Nullable Object value) {
            return object.isShardingMetaNode();
        }
    }

}
