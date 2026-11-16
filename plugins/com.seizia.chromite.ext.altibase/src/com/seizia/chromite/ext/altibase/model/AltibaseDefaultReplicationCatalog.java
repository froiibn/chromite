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
 * The Altibase replication catalog: {@code SYSTEM_.SYS_REPLICATIONS_}, {@code SYS_REPL_HOSTS_}, {@code SYS_REPL_ITEMS_},
 * {@code V$REPSENDER}, {@code V$REPGAP}, {@code V$REPRECEIVER}, properties {@code REPLICATION_PORT_NO} and
 * {@code REPLICATION_GAP_UNIT}.
 *
 * 설계: 문장은 이 클래스를 만들기 전에 각 클래스에 있던 문자열을 그대로 옮긴 것이다(트리 목록·조회 {@code AltibaseDataSource.ReplicationCache},
 * 송신·수신 {@code AltibaseReplication}의 캐시, 활동 모니터의 {@code AltibaseReplicationDialect}). Altibase 서버가 받는 문장이 글자 하나까지
 * 바뀌지 않게 하려고 모양(소문자, 공백, {@code SELECT *}, {@code s.*})을 고치지 않았다(docs/chromite/altibase.md "복제 카탈로그").
 * 상태가 없으므로 모든 연결이 한 객체를 함께 쓴다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 각 클래스의 복제 문장을 옮겨 모음(앱 변경 #139 작업 4·5)
 */
public final class AltibaseDefaultReplicationCatalog implements AltibaseReplicationCatalog {

    public static final AltibaseDefaultReplicationCatalog INSTANCE = new AltibaseDefaultReplicationCatalog();

    private AltibaseDefaultReplicationCatalog() {
    }

    @Nullable
    @Override
    public String getDisplayName() {
        return null;
    }

    @NotNull
    @Override
    public String getListQuery(boolean byName) {
        return "SELECT"
                + " r.replication_name,"
                + " r.is_started,"
                + " DECODE( r.conflict_resolution,"
                    + " 0, 'Default', "
                    + " 1, 'Master', "
                    + " 2, 'Slave', "
                    + " 'Unknown') AS conflict_resolution,"
                + " DECODE( r.repl_mode, "
                    + " 0, 'Lazy', "
                    + " 2,'Eager', "
                    + " 'Unknown') AS repl_mode, "
                + " DECODE( r.role,"
                    + " 0, 'General',"
                    + " 1, 'Log Analyzer',"
                    + " 2, 'Propagable Logging',"
                    + " 3, 'Propagation',"
                    + " 4, 'Propagation for Log Analyzer ',"
                    + " 'Unknown') AS role,"
                + " r.options,"
                + " DECODE( r.invalid_recovery,"
                    + " 0, 'Valid',"
                    + " 1, 'Invalid',"
                    + " 'Unknown') AS recoverable,"
                + " parallel_applier_count,"
                + " rh.host_ip || ':' || rh.port_no AS remote_addr,"
                + " rh.conn_type AS remote_conn_type,"
                + " r.xsn,"
                + " r.remote_last_ddl_xsn,"
                + " r.remote_fault_detect_time,"
                + " r.give_up_time,"
                + " r.give_up_xsn,"
                + " r.remote_xsn,"
                + " r.applier_init_buffer_size,"
                + " r.peer_replication_name"
            + " FROM system_.sys_replications_ r, system_.sys_repl_hosts_ rh"
            + " WHERE"
                + " r.replication_name = rh.replication_name"
                + (byName ? " AND r.replication_name = ?" : "")
            + " ORDER BY r.replication_name";
    }

    @NotNull
    @Override
    public String getItemsQuery() {
        return "SELECT * FROM system_.sys_repl_items_"
            + " WHERE replication_name = ?"
            + " ORDER BY local_user_name, local_table_name, local_partition_name, "
            + " remote_user_name, remote_table_name, remote_partition_name";
    }

    @NotNull
    @Override
    public String getSenderQuery() {
        return "SELECT s.*, (g.rep_gap * p.value1) as gap_size_in_byte FROM v$repsender s, v$repgap g,  v$property p "
            + "WHERE s.rep_name = ? AND s.rep_name = g.rep_name AND p.name = 'REPLICATION_GAP_UNIT'";
    }

    @NotNull
    @Override
    public String getReceiverQuery() {
        return "SELECT * from v$repreceiver WHERE rep_name = ?";
    }

    @NotNull
    @Override
    public String getPortProperty() {
        return "REPLICATION_PORT_NO"; //$NON-NLS-1$
    }

    @Nullable
    @Override
    public String getGapUnitProperty() {
        return "REPLICATION_GAP_UNIT"; //$NON-NLS-1$
    }

    @Override
    public boolean hasHostConnectionType() {
        return true;
    }

    @Override
    public boolean supportsControlStatements() {
        return true;
    }

    @Nullable
    @Override
    public String getDdlObjectType() {
        return "REPLICATION";
    }

    @NotNull
    @Override
    public String getStatusHostsQuery() {
        return "SELECT REPLICATION_NAME, HOST_IP, PORT_NO FROM SYSTEM_.SYS_REPL_HOSTS_ ORDER BY REPLICATION_NAME, HOST_NO"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusReplicationsQuery() {
        return "SELECT REPLICATION_NAME, IS_STARTED, REPL_MODE, ROLE, ITEM_COUNT, XSN, " //$NON-NLS-1$
            + "REMOTE_FAULT_DETECT_TIME, GIVE_UP_TIME FROM SYSTEM_.SYS_REPLICATIONS_ ORDER BY REPLICATION_NAME"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusSendersQuery() {
        return "SELECT s.REP_NAME, s.STATUS, s.START_FLAG, s.NET_ERROR_FLAG, s.PEER_IP, s.PEER_PORT, g.REP_GAP, " //$NON-NLS-1$
            + "s.XSN, s.COMMIT_XSN FROM V$REPSENDER s LEFT OUTER JOIN V$REPGAP g ON g.REP_NAME = s.REP_NAME ORDER BY s.REP_NAME"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public String getStatusReceiversQuery() {
        return "SELECT REP_NAME, PEER_IP, PEER_PORT, APPLY_XSN, " //$NON-NLS-1$
            + "INSERT_SUCCESS_COUNT + UPDATE_SUCCESS_COUNT + DELETE_SUCCESS_COUNT, " //$NON-NLS-1$
            + "INSERT_FAILURE_COUNT + UPDATE_FAILURE_COUNT + DELETE_FAILURE_COUNT FROM V$REPRECEIVER ORDER BY REP_NAME"; //$NON-NLS-1$
    }

}
