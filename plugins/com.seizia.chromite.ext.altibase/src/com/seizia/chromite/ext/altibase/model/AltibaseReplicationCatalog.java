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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * The replication catalog of a server of the Altibase family: the dictionary tables, views and properties that hold its
 * replications, and the statements that read them. Every replication statement of this plugin (the Replications folder,
 * a replication's items, sender and receiver, its state and DDL) and of the Activity Monitor's replication status goes
 * through the connection's catalog ({@link AltibaseDataSource#getReplicationCatalog()}).
 *
 * 설계: 같은 계열의 서버가 복제 카탈로그를 다른 이름으로 가질 수 있다(표·뷰·열·속성 이름만 다르고 구조가 같음). 모델 클래스
 * ({@link AltibaseReplication}, {@link AltibaseReplicationItem}, {@link AltibaseReplicationSender}, {@link AltibaseReplicationReceiver})는
 * 열 이름으로 읽으므로, 카탈로그마다 SELECT 목록이 열을 Altibase의 이름으로 별칭한다. 그래서 모델은 카탈로그를 모르고, 카탈로그에 없는 열은
 * 행에 없을 뿐이다("값 없음"). 그 열에만 기대는 속성은 {@link #hasHostConnectionType()}·{@link #getGapUnitProperty()}로 숨긴다.
 * 활동 모니터의 복제 탭은 열을 순서로 읽으므로 그 문장들은 열 순서만 지킨다.
 * 기본은 Altibase 카탈로그({@link AltibaseDefaultReplicationCatalog})이고 그 문장은 이 자리를 만들기 전과 글자 하나까지 같다. 다른 카탈로그는
 * 서버 확장이 그 서버를 감지했을 때 연결마다 준다({@link com.seizia.chromite.ext.altibase.extension.AltibaseServerExtensionState#getReplicationCatalog()}).
 * 이 플러그인은 그 카탈로그가 어느 제품의 것인지 모른다. 확인하지 않은 제어 문장(시작·중지·동기화)과 DDL 호출은 카탈로그가 허락할 때만 보낸다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 복제 카탈로그 전략(앱 변경 #139 작업 4·5, docs/chromite/altibase.md "복제 카탈로그")
 */
public interface AltibaseReplicationCatalog {

    /**
     * The name the server gives its replication, shown in the property view and the replication status summary; null for
     * the family's own replication (the texts say "replication", as before).
     */
    @Nullable
    String getDisplayName();

    /**
     * The replications with their remote host, one row per replication and host, in the columns that
     * {@link AltibaseReplication} reads; ordered by name.
     *
     * @param byName true: one parameter, the replication name
     */
    @NotNull
    String getListQuery(boolean byName);

    /**
     * The items of a replication in the columns that {@link AltibaseReplicationItem} reads. One parameter: the replication
     * name.
     */
    @NotNull
    String getItemsQuery();

    /**
     * The sender thread of a replication in the columns that {@link AltibaseReplicationSender} reads. One parameter: the
     * replication name.
     */
    @NotNull
    String getSenderQuery();

    /**
     * The receiver threads of a replication in the columns that {@link AltibaseReplicationReceiver} reads. One parameter:
     * the replication name.
     */
    @NotNull
    String getReceiverQuery();

    /**
     * The {@code V$PROPERTY} name of the replication port (0: replication is disabled on the server).
     */
    @NotNull
    String getPortProperty();

    /**
     * The {@code V$PROPERTY} name of the unit of the sender gap; null if the catalog has none. Without a unit the gap is
     * shown as the server's number, not as bytes.
     */
    @Nullable
    String getGapUnitProperty();

    /**
     * Whether the host table has the connection type column (the replication's "Connection Type" property).
     */
    boolean hasHostConnectionType();

    /**
     * Whether the start, stop and synchronize statements ({@code ALTER REPLICATION ... START|STOP|SYNC}) are known to work
     * on this catalog's replications. False: the commands are not offered and the tools refuse to write them.
     */
    boolean supportsControlStatements();

    /**
     * The {@code DBMS_METADATA.GET_DDL} object type of a replication; null if it is not known for this catalog (no call,
     * no DDL).
     */
    @Nullable
    String getDdlObjectType();

    /**
     * Replication status: name, host IP and port of every replication host, ordered by name and host number.
     */
    @NotNull
    String getStatusHostsQuery();

    /**
     * Replication status: name, started flag, mode, role, item count, XSN, remote fault detect time and give-up time of
     * every replication, ordered by name.
     */
    @NotNull
    String getStatusReplicationsQuery();

    /**
     * Replication status: name, status, start flag, network error flag, peer IP, peer port, gap (in the server's unit),
     * XSN and commit XSN of every sender, ordered by name.
     */
    @NotNull
    String getStatusSendersQuery();

    /**
     * Replication status: name, peer IP, peer port, applied XSN, applied changes and failed changes of every receiver,
     * ordered by name.
     */
    @NotNull
    String getStatusReceiversQuery();

}
