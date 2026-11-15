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
package com.seizia.chromite.ext.mssql.model;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.mssql.SQLServerMessages;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A file of a SQL Server database ({@code sys.database_files}): a data file of a filegroup (its parent) or a log file (its
 * parent is the database).
 *
 * 설계: 값은 그 데이터베이스의 {@code [db].sys.database_files} 행에서 온다(데이터베이스의 사용자이면 읽을 수 있다; 시험 서버에서 낮은
 * 권한 로그인으로 확인). 크기·증가·최대 크기는 서버의 8 KB 페이지 수 그대로 두고 MB로 바꿔 보인다. 무엇을 바꿀 수 있는지는 카탈로그 값으로
 * 정한다({@link #supportsChanges}, {@link #canDelete}): 읽기 전용 파일(읽기 전용 파일 그룹의 파일)은 서버가 추가·변경·이름 바꾸기·삭제를
 * 모두 거절하고(5048, 5055), 주 데이터 파일(file_id 1)과 주 로그 파일(file_id 2)은 지울 수 없다(5020).
 * 새로 고침은 자기 행만 다시 읽는다({@link #refreshObject}): 파일 설정을 바꾼 뒤 데이터베이스 전체를 다시 읽지 않기 위해서다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public class SQLServerDatabaseFile implements DBSObject, DBPNamedObject2, DBPSaveableObject, DBPRefreshableObject {

    /**
     * {@code sys.database_files.type}: data (rows) and log files.
     */
    public static final int TYPE_ROWS = 0;
    public static final int TYPE_LOG = 1;

    // The primary data file and the first log file: the server never removes them
    private static final int PRIMARY_DATA_FILE_ID = 1;
    private static final int PRIMARY_LOG_FILE_ID = 2;
    // sys.database_files.max_size of a log file created without MAXSIZE or with UNLIMITED: 2 TB in pages
    private static final long LOG_UNLIMITED_PAGES = 268435456L;
    private static final BigDecimal PAGES_PER_MB = BigDecimal.valueOf(128);

    private final SQLServerDatabase database;
    private final DBSObject parent;
    private boolean persisted;
    private int fileId;
    private int type;
    private String typeDesc;
    private String name;
    private String physicalName;
    private String stateDesc;
    private long sizePages;
    private long maxSizePages;
    private long growth;
    private boolean percentGrowth;
    private boolean readOnly;

    // The settings of a new file (ADD FILE only)
    @Nullable
    private SQLServerStorageDDL.FileSpec initialSpec;

    SQLServerDatabaseFile(@NotNull SQLServerDatabase database, @NotNull DBSObject parent, @NotNull ResultSet dbResult) {
        this.database = database;
        this.parent = parent;
        loadRow(dbResult);
        this.persisted = true;
    }

    /**
     * A new (not yet added) data file of the filegroup.
     */
    public SQLServerDatabaseFile(@NotNull SQLServerFilegroup filegroup) {
        this(filegroup.getDatabase(), filegroup, TYPE_ROWS);
    }

    /**
     * A new (not yet added) log file of the database.
     */
    public SQLServerDatabaseFile(@NotNull SQLServerDatabase database) {
        this(database, database, TYPE_LOG);
    }

    private SQLServerDatabaseFile(@NotNull SQLServerDatabase database, @NotNull DBSObject parent, int type) {
        this.database = database;
        this.parent = parent;
        this.type = type;
        this.name = ""; //$NON-NLS-1$
    }

    private void loadRow(@NotNull ResultSet dbResult) {
        this.fileId = JDBCUtils.safeGetInt(dbResult, "file_id");
        this.type = JDBCUtils.safeGetInt(dbResult, "type");
        this.typeDesc = JDBCUtils.safeGetString(dbResult, "type_desc");
        this.name = JDBCUtils.safeGetString(dbResult, "name");
        this.physicalName = JDBCUtils.safeGetString(dbResult, "physical_name");
        this.stateDesc = JDBCUtils.safeGetString(dbResult, "state_desc");
        this.sizePages = JDBCUtils.safeGetLong(dbResult, "size");
        this.maxSizePages = JDBCUtils.safeGetLong(dbResult, "max_size");
        this.growth = JDBCUtils.safeGetLong(dbResult, "growth");
        this.percentGrowth = JDBCUtils.safeGetBoolean(dbResult, "is_percent_growth");
        this.readOnly = JDBCUtils.safeGetBoolean(dbResult, "is_read_only");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Override
    public void setName(@NotNull String name) {
        this.name = name;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getFileName() {
        return physicalName;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getType() {
        return typeDesc;
    }

    /**
     * The size in MB (pages of 8 KB), with a fraction when it is not a whole number of MB.
     */
    @NotNull
    @Property(viewable = true, order = 4)
    public BigDecimal getSize() {
        return toMb(sizePages);
    }

    /**
     * "64 MB", "10%", or "None" when the file does not grow.
     */
    @NotNull
    @Property(viewable = true, order = 5)
    public String getAutogrowth() {
        if (!isAutogrowth()) {
            return SQLServerMessages.storage_growth_none;
        }
        return percentGrowth ? growth + "%" : toMb(growth).toPlainString() + " MB"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * "100 MB" or "Unlimited".
     */
    @NotNull
    @Property(viewable = true, order = 6)
    public String getMaxSize() {
        Long maxSizeMb = getMaxSizeMb();
        return maxSizeMb == null ? SQLServerMessages.storage_size_unlimited : maxSizeMb + " MB"; //$NON-NLS-1$
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getState() {
        return stateDesc;
    }

    @Property(viewable = true, order = 8)
    public boolean isReadOnly() {
        return readOnly;
    }

    public int getFileId() {
        return fileId;
    }

    public boolean isLog() {
        return type == TYPE_LOG;
    }

    /**
     * The file grows: a growth is set and the maximum is not the current size ({@code max_size} 0: no growth allowed).
     */
    public boolean isAutogrowth() {
        return growth != 0 && maxSizePages != 0;
    }

    public boolean isPercentGrowth() {
        return percentGrowth;
    }

    /**
     * The growth in MB or percent, as set ({@link #isPercentGrowth}).
     */
    @NotNull
    public BigDecimal getGrowthValue() {
        return percentGrowth ? BigDecimal.valueOf(growth) : toMb(growth);
    }

    /**
     * The maximum size in whole MB, or null for no limit (-1, or a log file's 2 TB default).
     */
    @Nullable
    public Long getMaxSizeMb() {
        if (maxSizePages == -1 || isLog() && maxSizePages == LOG_UNLIMITED_PAGES) {
            return null;
        }
        long pages = maxSizePages == 0 ? sizePages : maxSizePages;
        return toMb(pages).longValue();
    }

    @NotNull
    public SQLServerDatabase getDatabase() {
        return database;
    }

    /**
     * The filegroup of a data file; null for a log file.
     */
    @Nullable
    public SQLServerFilegroup getFilegroup() {
        return parent instanceof SQLServerFilegroup filegroup ? filegroup : null;
    }

    @Nullable
    public SQLServerStorageDDL.FileSpec getInitialSpec() {
        return initialSpec;
    }

    /**
     * Sets the physical file, size and growth of a new file.
     */
    public void setInitialSpec(@Nullable SQLServerStorageDDL.FileSpec initialSpec) {
        this.initialSpec = initialSpec;
    }

    /**
     * A data or log file of a database whose storage users change, that the server lets change (not read-only).
     */
    public boolean supportsChanges() {
        return persisted && (type == TYPE_ROWS || type == TYPE_LOG) && !readOnly && database.supportsStorageChanges();
    }

    /**
     * {@link #supportsChanges} and not the primary data or log file.
     */
    public boolean canDelete() {
        return supportsChanges() && fileId != PRIMARY_DATA_FILE_ID && fileId != PRIMARY_LOG_FILE_ID;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return database.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted) {
        this.persisted = persisted;
    }

    /**
     * After ADD FILE (same session): takes the new file's row (ID, physical name, sizes) by its logical name.
     */
    public void readCreated(@NotNull JDBCSession session) throws SQLException {
        readRow(session, "name = ?", name);
    }

    /**
     * Reads the file's row again.
     *
     * @return false if the file no longer exists
     */
    public boolean reloadRow(@NotNull JDBCSession session) throws SQLException {
        return readRow(session, "file_id = ?", fileId);
    }

    private boolean readRow(@NotNull JDBCSession session, @NotNull String condition, @NotNull Object key) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT * FROM " + SQLServerUtils.getSystemTableName(database, "database_files") + " WHERE " + condition)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            dbStat.setObject(1, key);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    return false;
                }
                loadRow(dbResult);
                return true;
            }
        }
    }

    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!persisted) {
            return this;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read file '" + getName() + "'")) { //$NON-NLS-1$ //$NON-NLS-2$
            return reloadRow(session) ? this : null;
        } catch (SQLException e) {
            throw new DBDatabaseException("Can't read database file", e, getDataSource());
        }
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Pages as MB without trailing zeros, never in exponent form (10 MB is "10", not "1E+1").
     */
    @NotNull
    private static BigDecimal toMb(long pages) {
        BigDecimal mb = BigDecimal.valueOf(pages).divide(PAGES_PER_MB).stripTrailingZeros();
        return mb.scale() < 0 ? mb.setScale(0) : mb;
    }
}
