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
package com.seizia.chromite.model.admin.altibase;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.AltibaseReplicationCatalog;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.replication.ReplicationDialect;
import com.seizia.chromite.model.admin.replication.ReplicationMessages;
import com.seizia.chromite.model.admin.replication.ReplicationReport;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Altibase 복제 상태: 복제 정의({@code SYSTEM_.SYS_REPLICATIONS_}, 원격 서버 {@code SYS_REPL_HOSTS_}), 송신 스레드({@code V$REPSENDER},
 * 지연 {@code V$REPGAP}), 수신 스레드({@code V$REPRECEIVER}).
 *
 * 설계:
 * <ul>
 *     <li>읽는 표와 값의 뜻은 모델의 복제 노드와 같다: 정의는 {@code AltibaseDataSource}의 복제 캐시(REPL_MODE 0 = Lazy, 2 = Eager,
 *     ROLE 0 = General …), 송신은 {@code AltibaseReplicationSender}(START_FLAG, STATUS 코드, 지연 = REP_GAP × REPLICATION_GAP_UNIT).
 *     코드 이름은 서버 상태 이름이라 옮기지 않는다(다른 DB의 서버 값과 같음).</li>
 *     <li>복제 포트({@code REPLICATION_PORT_NO})가 0이면 서버의 복제가 꺼져 있다(CREATE REPLICATION이 ERR-61023 "Replication is disabled",
 *     7.3에서 확인). 요약 첫 줄에 그렇게 적는다.</li>
 *     <li>이 표들과 {@code V$PROPERTY}는 SYS가 아닌 사용자도 읽는다(7.3, 스키마 사용자로 확인). 그래도 읽지 못한 표는 서버 오류를
 *     "읽을 수 없음"으로 보인다.</li>
 *     <li>시작·중지·동기화는 이 탭(읽기 전용)이 아니라 Object Explorer의 복제 노드 명령이다({@code ObjectMaintenanceActions}).</li>
 *     <li>문장과 속성 이름은 데이터 원본의 복제 카탈로그({@link AltibaseDataSource#getReplicationCatalog()})가 준다. 기본(Altibase)은 위의
 *     표·뷰·속성이고 문장이 전과 같다. 카탈로그가 다른 서버(서버 확장이 감지)는 그 카탈로그의 이름을 요약에 쓰고(포트 행), 지연 단위 속성이
 *     없으면 지연을 바이트가 아닌 서버의 수로 단위 없이 보인다. 이 클래스는 카탈로그가 어느 제품의 것인지 모른다. 별도 방언(관리 쪽 어댑터)을
 *     두지 않은 이유: 표·열의 뜻과 화면이 같아 문장 이름만 다르고, 확장 번들이 관리 번들에 기대지 않게 하려고.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Altibase 복제 상태 (SWT 감사 D-A7, docs/chromite/altibase.md "복제 시작·중지·동기화")
 * - 2026-11-17 문장·속성 이름을 데이터 원본의 복제 카탈로그에서 받음, 카탈로그 이름이 있으면 요약에, 지연 단위가 없으면 단위 없는 지연,
 *   제어 문장이 없으면 시작·중지 안내 없음(앱 변경 #139 작업 5, docs/chromite/altibase.md "복제 카탈로그")
 */
public class AltibaseReplicationDialect extends ReplicationDialect {

    private static final String[] SENDER_START_FLAGS = {
        "Normal", "Quick", "Sync", "Sync Only", "Sync Run", "Sync End", "Recovery", "Offline", "Parallel"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
    private static final String[] SENDER_STATUSES = {
        "Stop", "Run", "Retry", "Failback Normal", "Failback Master", "Failback Slave", "Sync", "Failback Eager", "Failback Flush", "Idle"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$

    @NotNull
    private final AltibaseDataSource dataSource;

    public AltibaseReplicationDialect(@NotNull AltibaseDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        List<String> notices = new ArrayList<>();
        AltibaseReplicationCatalog catalog = dataSource.getReplicationCatalog();
        // The server's name of its replication, or null: the family's own replication (the texts as before)
        String catalogName = catalog.getDisplayName();
        try {
            AdminGrid summary = createSummary();
            String portProperty = catalog.getPortProperty();
            String port = AdminSql.queryString(session, "SELECT VALUE1 FROM V$PROPERTY WHERE NAME = '" + portProperty + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            boolean disabled = port == null || "0".equals(port.trim()); //$NON-NLS-1$
            if (catalogName == null) {
                summary.addRow(ReplicationMessages.repl_alti_row_port, disabled ? ReplicationMessages.repl_alti_disabled : port.trim());
            } else {
                summary.addRow(NLS.bind(ReplicationMessages.repl_alti_row_port_named, catalogName),
                    disabled ? NLS.bind(ReplicationMessages.repl_alti_disabled_named, catalogName, portProperty) : port.trim());
            }
            // Without a gap unit property the unit is unknown (gapUnit stays null): the gap is the server's number, not bytes
            String gapUnitProperty = catalog.getGapUnitProperty();
            Long gapUnit = null;
            if (gapUnitProperty != null) {
                String gapUnitText = AdminSql.queryString(session, "SELECT VALUE1 FROM V$PROPERTY WHERE NAME = '" + gapUnitProperty + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                Long parsedGapUnit = parseLong(gapUnitText);
                gapUnit = parsedGapUnit == null ? 1 : parsedGapUnit;
            }

            AdminGrid replications = new AdminGrid("replications", ReplicationMessages.repl_grid_alti_replications, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_name),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_remote),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_started),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_mode),
                SpaceColumn.text(ReplicationMessages.repl_col_role),
                SpaceColumn.count(ReplicationMessages.repl_col_tables),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_xsn),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_fault_time),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_give_up_time));
            // The commands the description names are not offered where the catalog's control statements are not verified
            if (catalog.supportsControlStatements()) {
                replications.setDescription(ReplicationMessages.repl_alti_replications_desc);
            }
            replications.setEmptyText(ReplicationMessages.repl_alti_no_replications);
            int started = 0;
            try {
                Map<String, List<String>> hosts = new LinkedHashMap<>();
                AdminSql.query(session, catalog.getStatusHostsQuery(),
                    rs -> hosts.computeIfAbsent(rs.getString(1), name -> new ArrayList<>()).add(rs.getString(2) + ":" + rs.getString(3))); //$NON-NLS-1$
                List<Object[]> rows = new ArrayList<>();
                AdminSql.query(session, catalog.getStatusReplicationsQuery(),
                    rs -> {
                        String name = rs.getString(1);
                        boolean isStarted = rs.getInt(2) == 1;
                        rows.add(new Object[]{
                            name,
                            String.join(", ", hosts.getOrDefault(name, List.of())), //$NON-NLS-1$
                            isStarted ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no,
                            decodeMode(rs.getInt(3)),
                            decodeRole(rs.getInt(4)),
                            AdminSql.getLong(rs, 5),
                            AdminSql.getLong(rs, 6),
                            rs.getString(7),
                            rs.getString(8)});
                    });
                for (Object[] row : rows) {
                    replications.addRow(row);
                    if (ReplicationMessages.repl_value_yes.equals(row[2])) {
                        started++;
                    }
                }
            } catch (SQLException e) {
                String reason = NLS.bind(ReplicationMessages.repl_error, e.getMessage());
                replications.setUnavailable(reason);
                notices.add(reason);
            }
            if (replications.getUnavailableReason() == null) {
                summary.addRow(ReplicationMessages.repl_row_replication, replications.getRows().isEmpty()
                    ? ReplicationMessages.repl_not_configured
                    : NLS.bind(ReplicationMessages.repl_alti_configured, replications.getRows().size(), started));
            }

            AdminGrid senders = new AdminGrid("senders", ReplicationMessages.repl_grid_alti_senders, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_name),
                SpaceColumn.text(ReplicationMessages.repl_col_status),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_start_mode),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_network_error),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_peer),
                gapUnit != null
                    ? new SpaceColumn(ReplicationMessages.repl_col_lag_bytes, SpaceColumn.Kind.BYTES, ReplicationMessages.repl_alti_col_gap_tip)
                    : new SpaceColumn(ReplicationMessages.repl_alti_col_gap, SpaceColumn.Kind.COUNT, ReplicationMessages.repl_alti_col_gap_no_unit_tip),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_xsn),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_commit_xsn));
            senders.setEmptyText(ReplicationMessages.repl_alti_no_senders);
            Long senderGapUnit = gapUnit;
            try {
                AdminSql.query(session, catalog.getStatusSendersQuery(),
                    rs -> {
                        Long gap = AdminSql.getLong(rs, 7);
                        senders.addRow(
                            rs.getString(1),
                            decode(SENDER_STATUSES, rs.getInt(2)),
                            decode(SENDER_START_FLAGS, rs.getInt(3)),
                            rs.getInt(4) == 1 ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no,
                            rs.getString(5) == null ? null : rs.getString(5) + ":" + rs.getString(6), //$NON-NLS-1$
                            gap == null || senderGapUnit == null ? gap : gap * senderGapUnit,
                            AdminSql.getLong(rs, 8),
                            AdminSql.getLong(rs, 9));
                    });
            } catch (SQLException e) {
                senders.setUnavailable(NLS.bind(ReplicationMessages.repl_error, e.getMessage()));
            }

            AdminGrid receivers = new AdminGrid("receivers", ReplicationMessages.repl_grid_alti_receivers, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_name),
                SpaceColumn.text(ReplicationMessages.repl_alti_col_peer),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_apply_xsn),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_applied),
                SpaceColumn.count(ReplicationMessages.repl_alti_col_failed));
            receivers.setEmptyText(ReplicationMessages.repl_alti_no_receivers);
            try {
                AdminSql.query(session, catalog.getStatusReceiversQuery(),
                    rs -> receivers.addRow(
                        rs.getString(1),
                        rs.getString(2) == null ? null : rs.getString(2) + ":" + rs.getString(3), //$NON-NLS-1$
                        AdminSql.getLong(rs, 4),
                        AdminSql.getLong(rs, 5),
                        AdminSql.getLong(rs, 6)));
            } catch (SQLException e) {
                receivers.setUnavailable(NLS.bind(ReplicationMessages.repl_error, e.getMessage()));
            }
            return new ReplicationReport(List.of(summary, replications, senders, receivers), notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    /**
     * {@code SYS_REPLICATIONS_.REPL_MODE}, as the model's replication cache decodes it.
     */
    @NotNull
    private static String decodeMode(int mode) {
        return switch (mode) {
            case 0 -> "Lazy"; //$NON-NLS-1$
            case 2 -> "Eager"; //$NON-NLS-1$
            default -> String.valueOf(mode);
        };
    }

    /**
     * {@code SYS_REPLICATIONS_.ROLE}, as the model's replication cache decodes it.
     */
    @NotNull
    private static String decodeRole(int role) {
        return switch (role) {
            case 0 -> "General"; //$NON-NLS-1$
            case 1 -> "Log Analyzer"; //$NON-NLS-1$
            case 2 -> "Propagable Logging"; //$NON-NLS-1$
            case 3 -> "Propagation"; //$NON-NLS-1$
            case 4 -> "Propagation for Log Analyzer"; //$NON-NLS-1$
            default -> String.valueOf(role);
        };
    }

    @NotNull
    private static String decode(@NotNull String[] names, int code) {
        return code >= 0 && code < names.length ? names[code] : String.valueOf(code);
    }

    @Nullable
    private static Long parseLong(@Nullable String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
