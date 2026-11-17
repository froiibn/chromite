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

import com.seizia.chromite.ext.altibase.model.AltibaseReplicationCatalog;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * GEMINI, ShardSphere's replication: the Altibase replication catalog under other names ({@code SYSTEM_.SYS_GEMINIS_},
 * {@code SYS_GEMI_HOSTS_}, {@code SYS_GEMI_ITEMS_}, {@code V$GEMSENDER}, {@code V$GEMGAP}, {@code V$GEMRECEIVER}, property
 * {@code GEMINI_PORT_NO}).
 *
 * 설계: 열은 Altibase 복제와 같고 {@code REPLICATION}/{@code REPL}/{@code REP}가 {@code GEMINI}/{@code GEMI}/{@code GEM}으로 바뀌었다
 * (2026-11-16 조회 전용 조사, 열 목록은 서버의 DESC). 그래서 Altibase 모델 클래스가 읽는 이름으로 별칭한 SELECT 목록만 두고 모델은 그대로 쓴다.
 * SELECT 목록은 모델이 읽는 열만 고른다({@code SELECT *}·{@code s.*}를 쓰지 않음: 이름이 다른 열이 별칭 없이 오지 않게).
 * <ul>
 *     <li>없는 것: {@code SYS_GEMINIS_.REMOTE_LAST_DDL_XSN}(모델이 읽지 않음, 목록에서 뺌), {@code SYS_GEMI_HOSTS_.CONN_TYPE}
 *     ({@link #hasHostConnectionType()} = false, 연결 유형 속성 숨김), 지연 단위 속성({@link #getGapUnitProperty()} = null: 지연은
 *     {@code GEM_GAP}을 {@code REP_GAP}으로 별칭해 단위 없이 보인다. 단위 미확인).</li>
 *     <li>값 코드는 GEMINI 매뉴얼에 뜻이 있는 것만 이름으로 보인다: CONFLICT_RESOLUTION 0 없음 / 1 master / 2 slave. OPTIONS는 수
 *     그대로다(매뉴얼: 1 = recovery, 다른 비트는 모름). 모드(GEMI_MODE)·역할(ROLE)·INVALID_RECOVERY·IS_STARTED·송신 START_FLAG·STATUS·
 *     NET_ERROR_FLAG는 코드가 문서에 없고 실제 GEMINI 행을 본 적이 없어 서버의 값 그대로 보인다({@link #hasFamilyStateCodes()} = false).
 *     매뉴얼의 모드 낱말(ASYNC/SYNC)과 Altibase의 모드 이름(Lazy/Eager)은 코드와의 대응이 확인되지 않아 쓰지 않는다.</li>
 *     <li>제어 문장({@code ALTER GEMINI g START | QUICKSTART | STOP | RESET | SYNC …}, 2026-11-17 받은 GEMINI 매뉴얼에 있음)과
 *     {@code DBMS_METADATA.GET_DDL}의 객체 유형은 GEMINI가 설정된 서버에서 시험하지 않았으므로 보내지 않는다
 *     ({@link #supportsControlStatements()} = false, {@link #getDdlObjectType()} = null).</li>
 *     <li>송신 목록은 Altibase 문장처럼 지연 뷰와 내부 조인한다(트리의 송신 노드는 두 뷰에 다 있는 송신만, 전과 같은 모양).</li>
 * </ul>
 * 상태가 없으므로 모든 연결이 한 객체를 함께 쓴다. 연결에 이 카탈로그를 주는 것은 GEMINI 카탈로그를 감지한 {@link ShardSphereServer}다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: GEMINI를 Replications 폴더와 활동 모니터 복제 탭에서 읽음(앱 변경 #139 작업 4·5, docs/chromite/shardsphere.md "GEMINI")
 * - 2026-11-17 문서에 뜻이 있는 코드만 이름으로(충돌 해결), 나머지는 서버의 값 그대로; Altibase의 모드·역할 이름을 쓰지 않음
 *   (앱 변경 #139 수정 3)
 */
public final class GeminiReplicationCatalog implements AltibaseReplicationCatalog {

    public static final GeminiReplicationCatalog INSTANCE = new GeminiReplicationCatalog();

    // The server's name of its replication, not a translatable label
    private static final String NAME = "GEMINI"; //$NON-NLS-1$

    private GeminiReplicationCatalog() {
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return NAME;
    }

    @NotNull
    @Override
    public String getListQuery(boolean byName) {
        return "SELECT" //$NON-NLS-1$
            + " r.gemini_name AS replication_name," //$NON-NLS-1$
            + " r.is_started," //$NON-NLS-1$
            // Documented codes only (GEMINI manual): conflict resolution 0 none, 1 master, 2 slave; mode, role and
            // recovery validity as stored (their codes are not documented)
            + " DECODE(r.conflict_resolution, 0, 'None', 1, 'Master', 2, 'Slave', TO_CHAR(r.conflict_resolution)) AS conflict_resolution," //$NON-NLS-1$
            + " TO_CHAR(r.gemi_mode) AS repl_mode," //$NON-NLS-1$
            + " TO_CHAR(r.role) AS role," //$NON-NLS-1$
            + " r.options," //$NON-NLS-1$
            + " TO_CHAR(r.invalid_recovery) AS recoverable," //$NON-NLS-1$
            + " r.parallel_applier_count," //$NON-NLS-1$
            + " rh.host_ip || ':' || rh.port_no AS remote_addr," //$NON-NLS-1$
            + " r.xsn," //$NON-NLS-1$
            + " r.remote_fault_detect_time," //$NON-NLS-1$
            + " r.give_up_time," //$NON-NLS-1$
            + " r.give_up_xsn," //$NON-NLS-1$
            + " r.remote_xsn," //$NON-NLS-1$
            + " r.applier_init_buffer_size," //$NON-NLS-1$
            + " r.peer_gemini_name AS peer_replication_name" //$NON-NLS-1$
            + " FROM system_.sys_geminis_ r, system_.sys_gemi_hosts_ rh" //$NON-NLS-1$
            + " WHERE r.gemini_name = rh.gemini_name" //$NON-NLS-1$
            + (byName ? " AND r.gemini_name = ?" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + " ORDER BY r.gemini_name"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getItemsQuery() {
        return "SELECT gemini_name AS replication_name, table_oid," //$NON-NLS-1$
            + " local_user_name, local_table_name, local_partition_name," //$NON-NLS-1$
            + " remote_user_name, remote_table_name, remote_partition_name," //$NON-NLS-1$
            + " invalid_max_sn, gemini_unit AS replication_unit" //$NON-NLS-1$
            + " FROM system_.sys_gemi_items_" //$NON-NLS-1$
            + " WHERE gemini_name = ?" //$NON-NLS-1$
            + " ORDER BY local_user_name, local_table_name, local_partition_name," //$NON-NLS-1$
            + " remote_user_name, remote_table_name, remote_partition_name"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getSenderQuery() {
        return "SELECT s.gem_name AS rep_name, s.start_flag, s.net_error_flag, s.xsn, s.commit_xsn, s.status," //$NON-NLS-1$
            + " s.read_log_count, s.send_log_count, g.gem_gap AS rep_gap" //$NON-NLS-1$
            + " FROM v$gemsender s, v$gemgap g" //$NON-NLS-1$
            + " WHERE s.gem_name = ? AND s.gem_name = g.gem_name"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getReceiverQuery() {
        return "SELECT gem_name AS rep_name, apply_xsn," //$NON-NLS-1$
            + " insert_success_count, insert_failure_count, update_success_count, update_failure_count," //$NON-NLS-1$
            + " delete_success_count, delete_failure_count" //$NON-NLS-1$
            + " FROM v$gemreceiver WHERE gem_name = ?"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getPortProperty() {
        return "GEMINI_PORT_NO"; //$NON-NLS-1$
    }

    @Nullable
    @Override
    public String getGapUnitProperty() {
        return null;
    }

    @Override
    public boolean hasHostConnectionType() {
        return false;
    }

    @Override
    public boolean supportsControlStatements() {
        return false;
    }

    /**
     * IS_STARTED, START_FLAG, STATUS, NET_ERROR_FLAG and the mode and role codes are not documented for GEMINI and no
     * GEMINI row has been seen: shown as stored.
     */
    @Override
    public boolean hasFamilyStateCodes() {
        return false;
    }

    @Nullable
    @Override
    public String getDdlObjectType() {
        return null;
    }

    @NotNull
    @Override
    public String getStatusHostsQuery() {
        return "SELECT GEMINI_NAME, HOST_IP, PORT_NO FROM SYSTEM_.SYS_GEMI_HOSTS_ ORDER BY GEMINI_NAME, HOST_NO"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusReplicationsQuery() {
        return "SELECT GEMINI_NAME, IS_STARTED, GEMI_MODE, ROLE, ITEM_COUNT, XSN, " //$NON-NLS-1$
            + "REMOTE_FAULT_DETECT_TIME, GIVE_UP_TIME FROM SYSTEM_.SYS_GEMINIS_ ORDER BY GEMINI_NAME"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusSendersQuery() {
        return "SELECT s.GEM_NAME, s.STATUS, s.START_FLAG, s.NET_ERROR_FLAG, s.PEER_IP, s.PEER_PORT, g.GEM_GAP, " //$NON-NLS-1$
            + "s.XSN, s.COMMIT_XSN FROM V$GEMSENDER s LEFT OUTER JOIN V$GEMGAP g ON g.GEM_NAME = s.GEM_NAME ORDER BY s.GEM_NAME"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusReceiversQuery() {
        return "SELECT GEM_NAME, PEER_IP, PEER_PORT, APPLY_XSN, " //$NON-NLS-1$
            + "INSERT_SUCCESS_COUNT + UPDATE_SUCCESS_COUNT + DELETE_SUCCESS_COUNT, " //$NON-NLS-1$
            + "INSERT_FAILURE_COUNT + UPDATE_FAILURE_COUNT + DELETE_FAILURE_COUNT FROM V$GEMRECEIVER ORDER BY GEM_NAME"; //$NON-NLS-1$
    }

}
