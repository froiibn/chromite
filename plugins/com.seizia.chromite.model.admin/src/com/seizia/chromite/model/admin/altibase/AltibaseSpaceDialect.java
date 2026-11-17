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

import com.seizia.chromite.ext.altibase.model.AltibaseTablespace;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.space.ServerSpaceDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.admin.space.SpaceGrid;
import com.seizia.chromite.model.admin.space.SpaceMessages;
import com.seizia.chromite.model.admin.space.SpaceReport;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Altibase 계열(Altibase, ShardSphere) 공간 사용량: 테이블스페이스(크기, 사용, 사용 가능, 한도, 자동 확장), 데이터 파일과 메모리 테이블스페이스의
 * 체크포인트 경로, 큰 테이블(메모리·디스크 크기).
 *
 * 설계:
 * <ul>
 *     <li>한 구현이 두 서버에 같은 문장을 보낸다(제품 이름 검사 없음). 쓰는 뷰와 열은 Altibase 7.3, ShardSphere 5.1.9와 샤딩 메타 노드에서
 *     모두 준비·실행됨을 확인했다(docs/chromite/altibase.md "공간 사용량").</li>
 *     <li>테이블스페이스의 사용 크기와 한도는 트리(Storage &gt; Tablespaces의 Used/Available Size)와 같은 문장으로 읽는다
 *     ({@link AltibaseTablespace#readSizes}). 문장을 복사하지 않아 두 화면이 어긋날 수 없다. 트리의 Available Size = 이 표의 최대 크기 − 사용.
 *     종류마다 문장이 달라(메모리 {@code V$MEMTBL_INFO}, 디스크 {@code X$SEGMENT}, 임시 {@code X$TEMPTABLE_STATS}, 언두 {@code V$DISK_UNDO_USAGE})
 *     테이블스페이스마다 한 번씩 읽는다(시험 서버 5개, 메타 노드 9개).</li>
 *     <li>크기 = {@code V$TABLESPACES}의 TOTAL_PAGE_COUNT × 그 테이블스페이스의 PAGE_SIZE(메모리 32 KB, 디스크 8 KB로 다르다). 파일 크기도
 *     페이지 수 × 자기 테이블스페이스의 페이지 크기다.</li>
 *     <li>데이터 파일마다의 사용량은 서버가 주지 않아(세그먼트는 테이블스페이스 단위) 파일 표에 사용 열이 없다. 체크포인트 이미지 파일의 크기도
 *     SQL로 읽을 수 없어 경로만 보인다.</li>
 *     <li>큰 테이블은 서버의 {@code SYSTEM_.SYS_TABLE_SIZE_}다(사용자 테이블, 파티션 합계, 메모리 = 할당 페이지 × 32 KB, 디스크 = 세그먼트 사용 크기).
 *     행 수 통계는 이 계열에 없어 열이 없다.</li>
 *     <li>표마다 문장이 실패하면 그 표만 서버 메시지와 함께 "읽을 수 없음"이다(권한이 모자란 로그인 포함). 읽기 전용이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: Altibase 계열 공간 사용량 (앱 변경 #139 작업 7, docs/chromite/swing-admin-tools.md "공간 사용량")
 */
public class AltibaseSpaceDialect extends ServerSpaceDialect {

    // V$DATAFILES.AUTOEXTEND, V$MEM_TABLESPACES / V$VOL_TABLESPACES.AUTOEXTEND_MODE
    private static final int AUTOEXTEND_ON = 1;

    private static final String TABLESPACES_SQL = "SELECT t.ID, t.NAME, t.TYPE, t.STATE, t.TOTAL_PAGE_COUNT * t.PAGE_SIZE, " //$NON-NLS-1$
        + "COALESCE((SELECT MAX(f.AUTOEXTEND) FROM V$DATAFILES f WHERE f.SPACEID = t.ID), " //$NON-NLS-1$
        + "(SELECT m.AUTOEXTEND_MODE FROM V$MEM_TABLESPACES m WHERE m.SPACE_ID = t.ID), " //$NON-NLS-1$
        + "(SELECT v.AUTOEXTEND_MODE FROM V$VOL_TABLESPACES v WHERE v.SPACE_ID = t.ID)) " //$NON-NLS-1$
        + "FROM V$TABLESPACES t ORDER BY t.NAME"; //$NON-NLS-1$

    // The limit as the tree's tablespace size statements count it: MAXSIZE, or the current size without autoextend (MAXSIZE 0)
    private static final String DATA_FILES_SQL = "SELECT f.ID, t.NAME, t.TYPE, f.NAME, f.CURRSIZE * t.PAGE_SIZE, f.AUTOEXTEND, " //$NON-NLS-1$
        + "f.NEXTSIZE * t.PAGE_SIZE, DECODE(f.MAXSIZE, 0, f.CURRSIZE, f.MAXSIZE) * t.PAGE_SIZE, f.STATE " //$NON-NLS-1$
        + "FROM V$DATAFILES f JOIN V$TABLESPACES t ON t.ID = f.SPACEID ORDER BY t.NAME, f.ID"; //$NON-NLS-1$

    private static final String CHECKPOINT_PATHS_SQL = "SELECT t.NAME, p.CHECKPOINT_PATH FROM V$MEM_TABLESPACE_CHECKPOINT_PATHS p " //$NON-NLS-1$
        + "JOIN V$TABLESPACES t ON t.ID = p.SPACE_ID ORDER BY t.NAME, p.CHECKPOINT_PATH"; //$NON-NLS-1$

    private static final String TABLES_SQL = "SELECT USER_NAME, TABLE_NAME, TBS_NAME, MEMORY_SIZE, DISK_SIZE FROM SYSTEM_.SYS_TABLE_SIZE_{0} " //$NON-NLS-1$
        + "ORDER BY MEMORY_SIZE + DISK_SIZE DESC, USER_NAME, TABLE_NAME LIMIT ?"; //$NON-NLS-1$

    @NotNull
    @Override
    public String getScopeLabel() {
        return SpaceMessages.space_scope_owner;
    }

    @NotNull
    @Override
    public SpaceReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int tableLimit) {
        SpaceGrid tablespaces = new SpaceGrid(SpaceGrid.Kind.TABLESPACES, SpaceMessages.space_grid_tablespaces,
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.text(SpaceMessages.space_col_type),
            SpaceColumn.text(SpaceMessages.space_col_state),
            SpaceColumn.bytes(SpaceMessages.space_col_size),
            SpaceColumn.bytes(SpaceMessages.space_col_used),
            SpaceColumn.bytes(SpaceMessages.space_col_free),
            SpaceColumn.percent(SpaceMessages.space_col_used_pct),
            new SpaceColumn(SpaceMessages.space_col_max_size, SpaceColumn.Kind.BYTES_LIMIT, SpaceMessages.space_alti_max_size_tip),
            SpaceColumn.percent(SpaceMessages.space_col_used_of_max_pct),
            SpaceColumn.text(SpaceMessages.space_col_autoextend));
        SpaceGrid dataFiles = new SpaceGrid(SpaceGrid.Kind.DATA_FILES, SpaceMessages.space_grid_data_files,
            SpaceColumn.count(SpaceMessages.space_col_file_id),
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.text(SpaceMessages.space_col_kind),
            SpaceColumn.text(SpaceMessages.space_col_path),
            SpaceColumn.bytes(SpaceMessages.space_col_size),
            SpaceColumn.text(SpaceMessages.space_col_autoextend),
            SpaceColumn.bytes(SpaceMessages.space_col_increment),
            new SpaceColumn(SpaceMessages.space_col_max_size, SpaceColumn.Kind.BYTES_LIMIT, null),
            SpaceColumn.text(SpaceMessages.space_col_state));
        SpaceGrid tables = new SpaceGrid(SpaceGrid.Kind.TABLES, SpaceMessages.space_grid_tables,
            SpaceColumn.text(SpaceMessages.space_col_owner),
            SpaceColumn.text(SpaceMessages.space_col_table),
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            new SpaceColumn(SpaceMessages.space_col_memory, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_memory_tip),
            new SpaceColumn(SpaceMessages.space_col_disk, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_disk_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_total));

        try {
            readTablespaces(session, tablespaces);
        } catch (SQLException e) {
            tablespaces.setUnavailable(NLS.bind(SpaceMessages.space_error, e.getMessage()));
        }
        try {
            readDataFiles(session, dataFiles);
        } catch (SQLException e) {
            dataFiles.setUnavailable(NLS.bind(SpaceMessages.space_error, e.getMessage()));
        }
        List<String> scopes = new ArrayList<>();
        String tableScope = null;
        try {
            scopes.addAll(AdminSql.queryStrings(session, "SELECT DISTINCT USER_NAME FROM SYSTEM_.SYS_TABLE_SIZE_ ORDER BY USER_NAME")); //$NON-NLS-1$
            tableScope = scope != null && scopes.contains(scope) ? scope : null;
            AdminSql.RowReader reader = rs -> {
                Long memory = AdminSql.getLong(rs, 4);
                Long disk = AdminSql.getLong(rs, 5);
                tables.addRow(rs.getString(1), rs.getString(2), rs.getString(3), memory, disk,
                    memory == null && disk == null ? null : (memory == null ? 0 : memory) + (disk == null ? 0 : disk));
            };
            // Plain replacement: NLS.bind would treat quotes in the SQL
            if (tableScope != null) {
                AdminSql.query(session, TABLES_SQL.replace("{0}", " WHERE USER_NAME = ?"), reader, tableScope, tableLimit); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                AdminSql.query(session, TABLES_SQL.replace("{0}", ""), reader, tableLimit); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } catch (SQLException e) {
            tables.setUnavailable(NLS.bind(SpaceMessages.space_error, e.getMessage()));
        }
        return new SpaceReport(List.of(tablespaces, dataFiles, tables), scopes, tableScope, List.of());
    }

    private static void readTablespaces(@NotNull JDBCSession session, @NotNull SpaceGrid grid) throws SQLException {
        record Row(int id, String name, AltibaseTablespace.TbsType type, AltibaseTablespace.State state, Long size, Integer autoextend) {
        }
        List<Row> rows = new ArrayList<>();
        AdminSql.query(session, TABLESPACES_SQL, rs -> {
            Long autoextend = AdminSql.getLong(rs, 6);
            rows.add(new Row(rs.getInt(1), rs.getString(2), AltibaseTablespace.TbsType.getTbsTypeByIdx(rs.getInt(3)),
                AltibaseTablespace.State.getStateByIdx(rs.getInt(4)), AdminSql.getLong(rs, 5),
                autoextend == null ? null : autoextend.intValue()));
        });
        for (Row row : rows) {
            AltibaseTablespace.Sizes sizes = AltibaseTablespace.readSizes(session, row.type(), row.id());
            Long used = sizes == null ? null : sizes.used();
            Long max = sizes == null ? null : sizes.total();
            Long free = used == null || row.size() == null ? null : Math.max(0, row.size() - used);
            grid.addRow(row.name(), row.type().name(), row.state().name(), row.size(), used, free, percent(used, row.size()),
                max, percent(used, max), yesNo(row.autoextend()));
        }
    }

    private static void readDataFiles(@NotNull JDBCSession session, @NotNull SpaceGrid grid) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        AdminSql.query(session, DATA_FILES_SQL, rs -> {
            AltibaseTablespace.TbsType type = AltibaseTablespace.TbsType.getTbsTypeByIdx(rs.getInt(3));
            boolean temporary = type == AltibaseTablespace.TbsType.DISK_SYSTEM_TEMP || type == AltibaseTablespace.TbsType.DISK_USER_TEMP;
            Long autoextend = AdminSql.getLong(rs, 6);
            boolean on = autoextend != null && autoextend == AUTOEXTEND_ON;
            rows.add(new Object[]{AdminSql.getLong(rs, 1), rs.getString(2),
                temporary ? SpaceMessages.space_value_temp_file : SpaceMessages.space_value_data_file,
                rs.getString(4), AdminSql.getLong(rs, 5), yesNo(autoextend == null ? null : autoextend.intValue()),
                on ? AdminSql.getLong(rs, 7) : null, AdminSql.getLong(rs, 8),
                AltibaseTablespace.State.getStateByIdx(rs.getInt(9)).name()});
        });
        // Memory tablespaces keep their checkpoint images in these folders; size and limits are the tablespace's
        AdminSql.query(session, CHECKPOINT_PATHS_SQL, rs -> rows.add(new Object[]{null, rs.getString(1),
            SpaceMessages.space_value_checkpoint_path, rs.getString(2), null, null, null, null, null}));
        for (Object[] row : rows) {
            grid.addRow(row);
        }
    }

    @Nullable
    private static Double percent(@Nullable Long part, @Nullable Long whole) {
        return part == null || whole == null || whole == 0 ? null : part * 100.0 / whole;
    }

    @Nullable
    private static String yesNo(@Nullable Integer autoextend) {
        return autoextend == null ? null : autoextend == AUTOEXTEND_ON ? SpaceMessages.space_value_yes : SpaceMessages.space_value_no;
    }
}
