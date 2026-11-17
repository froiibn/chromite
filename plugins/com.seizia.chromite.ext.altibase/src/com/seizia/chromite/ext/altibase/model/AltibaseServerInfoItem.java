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

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.internal.AltibaseMessages;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * One fact of the System Info &gt; Server Information folder (name and value).
 *
 * 설계: 활동 모니터의 개요 차트가 보이지 않는 서버의 사실(시작 단계·시각, 아카이브 로그 모드·상태, 버전, 백업 기록)을 System Info 폴더에 둔다.
 * 그 폴더에 이미 Properties(V$PROPERTY)와 Module Memory Usage(V$MEMSTAT)가 같은 이름·값 목록으로 있어 새 창이나 화면 코드가 필요 없다.
 * <ul>
 *     <li>문장은 Altibase 7.3과 ShardSphere(일반 서버, 샤딩 메타 노드)에 모두 있는 뷰·열만 쓴다. 한쪽에만 있는 열(ShardSphere의
 *     {@code V$INSTANCE.PROCESS_ID}, {@code V$VERSION.PRODUCT_NAME}, 복제 프로토콜 열 {@code REPL_PROTOCOL_VERSION} /
 *     {@code GEMI_PROTOCOL_VERSION})은 읽지 않는다. 그래서 서버마다 고르는 분기가 없다.</li>
 *     <li>뷰마다 문장 하나: 한 뷰를 읽을 수 없어도(권한 등) 다른 항목은 보인다. 모두 실패하면 첫 오류를 던진다.</li>
 *     <li>{@code STARTUP_TIME_SEC}은 1970-01-01 UTC부터의 초(시험 서버에서 컨테이너 시작 시각, {@code STARTUP_TIME_SEC + WORKING_TIME_SEC}가
 *     지금과 같음을 확인)라 클라이언트 시간대로 보인다.</li>
 *     <li>아카이브 경로({@code V$ARCHIVE.ARCHIVE_DEST})는 보이지 않는다. 서버 속성 {@code ARCHIVE_DIR}로 Properties에 이미 있다.</li>
 *     <li>다음에 아카이브할 로그 파일은 아카이브 모드({@code ARCHIVE_MODE} 0이 아님)일 때만 보인다. 백업 기록은 행이 있을 때만 마지막 백업을
 *     보인다. 세 서버 모두 아카이브 모드가 아니고 백업 행이 없어 두 값은 실제로 본 적이 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성 (앱 변경 #139 작업 8, docs/chromite/altibase.md "개요 차트와 서버 정보")
 */
public class AltibaseServerInfoItem implements DBSObject {

    private static final Log log = Log.getLog(AltibaseServerInfoItem.class);

    private static final String QUERY_VERSION =
        "SELECT PRODUCT_VERSION, PKG_BUILD_PLATFORM_INFO, PRODUCT_TIME, SM_VERSION, META_VERSION, PROTOCOL_VERSION FROM V$VERSION"; //$NON-NLS-1$
    private static final String QUERY_INSTANCE = "SELECT STARTUP_PHASE, STARTUP_TIME_SEC, WORKING_TIME_SEC FROM V$INSTANCE"; //$NON-NLS-1$
    private static final String QUERY_LOG = "SELECT SERVER_STATUS, ARCHIVELOG_MODE FROM V$LOG"; //$NON-NLS-1$
    private static final String QUERY_ARCHIVE =
        "SELECT ARCHIVE_MODE, ARCHIVE_THR_RUNNING, CURRENT_LOGFILE, NEXTLOGFILE_TO_ARCH FROM V$ARCHIVE ORDER BY LFG_ID"; //$NON-NLS-1$
    private static final String QUERY_BACKUP = "SELECT COUNT(*), MAX(END_BACKUP_TIME) FROM V$BACKUP_INFO"; //$NON-NLS-1$

    private static final long SECONDS_PER_DAY = 86_400;

    private interface RowReader {
        void read(@NotNull JDBCResultSet dbResult, @NotNull List<AltibaseServerInfoItem> items) throws SQLException;
    }

    private final AltibaseDataSource dataSource;
    private final String name;
    private final String value;

    private AltibaseServerInfoItem(@NotNull AltibaseDataSource dataSource, @NotNull String name, @Nullable String value) {
        this.dataSource = dataSource;
        this.name = name;
        this.value = value;
    }

    @NotNull
    static List<AltibaseServerInfoItem> read(@NotNull DBRProgressMonitor monitor, @NotNull AltibaseDataSource dataSource) throws DBException {
        List<AltibaseServerInfoItem> items = new ArrayList<>();
        SQLException firstError = null;
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read server information")) { //$NON-NLS-1$
            RowReader[] readers = {
                (rs, list) -> {
                    add(dataSource, list, AltibaseMessages.server_info_version, rs.getString(1));
                    add(dataSource, list, AltibaseMessages.server_info_build, rs.getString(2));
                    add(dataSource, list, AltibaseMessages.server_info_build_time, rs.getString(3));
                    add(dataSource, list, AltibaseMessages.server_info_sm_version, rs.getString(4));
                    add(dataSource, list, AltibaseMessages.server_info_meta_version, rs.getString(5));
                    add(dataSource, list, AltibaseMessages.server_info_protocol_version, rs.getString(6));
                },
                (rs, list) -> {
                    add(dataSource, list, AltibaseMessages.server_info_startup_phase, rs.getString(1));
                    long started = rs.getLong(2);
                    if (!rs.wasNull()) {
                        add(dataSource, list, AltibaseMessages.server_info_started,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(started * 1000)));
                    }
                    long working = rs.getLong(3);
                    if (!rs.wasNull()) {
                        add(dataSource, list, AltibaseMessages.server_info_uptime, formatUptime(working));
                    }
                },
                (rs, list) -> {
                    add(dataSource, list, AltibaseMessages.server_info_status, rs.getString(1));
                    add(dataSource, list, AltibaseMessages.server_info_archive_mode, rs.getString(2));
                },
                (rs, list) -> {
                    boolean archiveMode = rs.getLong(1) != 0;
                    add(dataSource, list, AltibaseMessages.server_info_archiver,
                        rs.getLong(2) != 0 ? AltibaseMessages.server_info_running : AltibaseMessages.server_info_stopped);
                    add(dataSource, list, AltibaseMessages.server_info_current_log, rs.getString(3));
                    if (archiveMode) {
                        add(dataSource, list, AltibaseMessages.server_info_next_archive_log, rs.getString(4));
                    }
                },
                (rs, list) -> {
                    long count = rs.getLong(1);
                    add(dataSource, list, AltibaseMessages.server_info_backups, String.valueOf(count));
                    if (count > 0) {
                        add(dataSource, list, AltibaseMessages.server_info_last_backup, rs.getString(2));
                    }
                }
            };
            String[] queries = {QUERY_VERSION, QUERY_INSTANCE, QUERY_LOG, QUERY_ARCHIVE, QUERY_BACKUP};
            for (int i = 0; i < queries.length && !monitor.isCanceled(); i++) {
                try (JDBCPreparedStatement dbStat = session.prepareStatement(queries[i]);
                     JDBCResultSet dbResult = dbStat.executeQuery()) {
                    // One row per view (V$ARCHIVE: the first log file group)
                    if (dbResult.next()) {
                        readers[i].read(dbResult, items);
                    }
                } catch (SQLException e) {
                    log.debug("Error reading server information: " + queries[i], e);
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
        }
        if (items.isEmpty() && firstError != null) {
            throw new DBException("Failed to read server information", firstError);
        }
        return items;
    }

    private static void add(
        @NotNull AltibaseDataSource dataSource,
        @NotNull List<AltibaseServerInfoItem> items,
        @NotNull String name,
        @Nullable String value
    ) {
        items.add(new AltibaseServerInfoItem(dataSource, name, value == null ? null : value.trim()));
    }

    @NotNull
    private static String formatUptime(long seconds) {
        long rest = seconds % SECONDS_PER_DAY;
        String time = String.format("%02d:%02d:%02d", rest / 3600, rest / 60 % 60, rest % 60); //$NON-NLS-1$
        return NLS.bind(AltibaseMessages.server_info_uptime_value, seconds / SECONDS_PER_DAY, time);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getValue() {
        return value;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return dataSource;
    }
}
