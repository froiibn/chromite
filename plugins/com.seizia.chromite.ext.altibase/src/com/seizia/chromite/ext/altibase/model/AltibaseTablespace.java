/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.altibase.AltibaseUtils;
import com.seizia.chromite.model.DBPObjectStatistics;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.IPropertyCacheValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectLazy;
import com.seizia.chromite.model.struct.rdb.DBSTableStorageKind;
import org.jkiss.utils.ByteNumberFormat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Altibase tablespace (V$TABLESPACES).
 *
 * 설계(2026-11-15 테이블스페이스 관리): 무엇을 할 수 있는지는 이름이 아니라 {@code V$TABLESPACES.TYPE}(시스템·사용자 종류)으로 정한다
 * ({@link #isUserTablespace}, {@link #supportsOnlineChange}, {@link #supportsAutoextendChange}, {@link #hasDiskFiles}). 시험 서버에서
 * 휘발성·임시 테이블스페이스는 ONLINE/OFFLINE을 거절했고(201191), 메모리·휘발성은 데이터 파일을 받지 않는다(201297).
 * 카탈로그 행에서 오는 값은 {@link #loadRow}가 넣는다: 새로 만든 테이블스페이스는 CREATE 직후 자기 행을 읽고({@link #readCreated}),
 * 새로 고침은 캐시만 비우지 않고 자기 행을 다시 읽어 상태(온라인·오프라인)와 파일 수가 맞게 한다({@link #refreshObject}).
 *
 * 변경 이력:
 * - 2026-11-07 {@link TbsType#getStorageKind()} 추가: 테이블 목록이 읽은 V$TABLESPACES.TYPE을 테이블 저장 방식으로
 *   바꾼다(docs/chromite/altibase.md)
 * - 2026-11-15 새 테이블스페이스 객체, 종류별 지원 판단, 행 다시 읽기, 자동 확장 설정 읽기(앱 변경 #126,
 *   docs/chromite/altibase.md "테이블스페이스 관리")
 * - 2026-11-17 디스크 데이터 테이블스페이스 크기 질의의 열 별칭에서 작은따옴표를 뺌({@code USED_SIZE}). ShardSphere는 따옴표 별칭을
 *   구문 오류(200705)로 거절해 사용·남은 크기를 읽지 못했다(앱 변경 #139, docs/chromite/altibase.md "ShardSphere에서 다른 문장")
 * - 2026-11-17 종류별 크기 문장을 객체 필드에서 정적 {@link #getSizeQuery}로, 세션을 받는 {@link #readSizes}를 둠: 활동 모니터의 공간 탭이
 *   트리와 같은 문장으로 읽어 두 화면의 값이 어긋날 수 없게. 메모리·휘발성의 한도가 자동 확장이 꺼져 있으면 현재 크기, 메모리는 자기 MAXSIZE와
 *   MEM_MAX_DB_SIZE 중 작은 쪽(전에는 모든 메모리 테이블스페이스가 MEM_MAX_DB_SIZE) (앱 변경 #139 작업 7, docs/chromite/altibase.md "공간 사용량")
 * - 2026-11-22 Tables·Indexes 폴더가 같은 테이블 종류({@link #TABLE_KINDS})를 읽음: 인덱스 폴더는 종류를 거르지 않아 테이블 폴더에 없는 내부 표의
 *   인덱스까지 보였고, 테이블 폴더는 구체화된 뷰를 빠뜨렸다(앱 변경 #158, docs/chromite/altibase.md "테이블스페이스의 테이블과 인덱스")
 */
public class AltibaseTablespace extends AltibaseGlobalObject implements DBPRefreshableObject, DBPObjectStatistics {

    // SYS_TABLES_.TABLE_TYPE of the tables the Tables and Indexes folders show: table, queue, materialized view (the kinds of
    // the schema's table list that keep rows in a tablespace; other kinds are the server's internal or extension objects)
    private static final String TABLE_KINDS = "'T', 'Q', 'M'";

    public enum TbsType {
        MEMORY_SYSTEM_DICTIONARY(0),
        MEMORY_SYSTEM_DATA(1),
        MEMORY_USER_DATA(2),
        DISK_SYSTEM_DATA(3),
        DISK_USER_DATA(4),
        DISK_SYSTEM_TEMP(5),
        DISK_USER_TEMP(6),
        DISK_SYSTEM_UNDO(7),
        VOLATILE_USER_DATA(8),
        UNKNOWN(-1);
        
        private final int stateIdx;
        
        TbsType(int stateIdx) {
            this.stateIdx = stateIdx;
        }
        
        /**
         * Get TBS type get its index value. 
         */
        public static TbsType getTbsTypeByIdx(int stateIdx) {
            for (TbsType type : TbsType.values()) {
                if (stateIdx == type.stateIdx) {
                    return type;
                }
            }
            
            return UNKNOWN;
        }

        /**
         * Storage kind of the tables in a tablespace of this type. The temporary and undo types count as disk:
         * they are disk tablespaces, although no user table is created in them.
         */
        @Nullable
        public DBSTableStorageKind getStorageKind() {
            switch (this) {
                case MEMORY_SYSTEM_DICTIONARY:
                case MEMORY_SYSTEM_DATA:
                case MEMORY_USER_DATA:
                    return DBSTableStorageKind.MEMORY;
                case DISK_SYSTEM_DATA:
                case DISK_USER_DATA:
                case DISK_SYSTEM_TEMP:
                case DISK_USER_TEMP:
                case DISK_SYSTEM_UNDO:
                    return DBSTableStorageKind.DISK;
                case VOLATILE_USER_DATA:
                    return DBSTableStorageKind.VOLATILE;
                default:
                    return null;
            }
        }
    }
    
    public enum State {
        OFFLINE(1),
        ONLINE(2),
        BACKUP_OFFLINE(5),
        BACKUP_ONLINE(6),
        DROPPPED(128),
        DISCARDED(1024),
        BACKUP_DISCARDED(1028),
        UNKNOWN(-1);
        
        private final int stateIdx;
        
        State(int stateIdx) {
            this.stateIdx = stateIdx;
        }
        
        /**
         * Get TBS status by its state index value. 
         */
        public static State getStateByIdx(int stateIdx) {
            for (State status : State.values()) {
                if (stateIdx == status.stateIdx) {
                    return status;
                }
            }
            
            return UNKNOWN;
        }
    }
    
    private int id;
    private String name;
    private String extentManagement;
    private String segmentManagement;
    private int dataFileCount;
    private boolean isLogCompression;
    private int pageSizeInBytes;
    
    private TbsType tbsType;
    private State state;
    
    private volatile Long availableSize;
    private volatile Long usedSize;

    // The space of a new tablespace (CREATE only)
    @Nullable
    private AltibaseTablespaceDDL.Space initialSpace;
    
    final FileCache fileCache = new FileCache();
    final TablePartnCache tablePartnCache = new TablePartnCache();
    final IndexPartnCache indexPartnCache = new IndexPartnCache();
    
    protected AltibaseTablespace(AltibaseDataSource dataSource, ResultSet dbResult) {
        super(dataSource, true);
        loadRow(dbResult);
    }
        
    /**
     * A new (not yet created) user tablespace of the type.
     */
    public AltibaseTablespace(@NotNull AltibaseDataSource dataSource, @NotNull TbsType tbsType) {
        super(dataSource, false);
        this.name = "";
        this.tbsType = tbsType;
        this.state = State.UNKNOWN;
    }

    /**
     * Takes the values of a V$TABLESPACES row.
     */
    private void loadRow(@NotNull ResultSet dbResult) {
        this.id                 = JDBCUtils.safeGetInt(dbResult, "ID");
        this.name               = JDBCUtils.safeGetString(dbResult, "NAME");
        this.tbsType            = TbsType.getTbsTypeByIdx(JDBCUtils.safeGetInt(dbResult, "TYPE"));
        this.state              = State.getStateByIdx(JDBCUtils.safeGetInt(dbResult, "STATE"));
        this.extentManagement   = JDBCUtils.safeGetString(dbResult, "EXTENT_MANAGEMENT");
        this.segmentManagement  = JDBCUtils.safeGetString(dbResult, "SEGMENT_MANAGEMENT");    
        this.dataFileCount      = JDBCUtils.safeGetInt(dbResult, "DATAFILE_COUNT");
        this.pageSizeInBytes    = JDBCUtils.safeGetInt(dbResult, "PAGE_SIZE");
        this.isLogCompression   = (JDBCUtils.safeGetInt(dbResult, "ATTR_LOG_COMPRESS") == 1);
    }

    /**
     * The statement that reads the size limit (TOTAL_SIZE) and the used size (USED_SIZE) in bytes of one tablespace of the
     * type; its one parameter is the tablespace ID. The tree's Available/Used Size and the Activity Monitor's Space tab
     * both read through {@link #readSizes}, so they cannot disagree.
     *
     * @return null for an unknown type
     */
    @Nullable
    private static String getSizeQuery(@NotNull TbsType tbsType) {
        switch (tbsType) {
            case MEMORY_SYSTEM_DICTIONARY:
            case MEMORY_SYSTEM_DATA:
            case MEMORY_USER_DATA:
                // The limit is the tablespace's own: its size when autoextend is off, else its MAXSIZE, but never more
                // than MEM_MAX_DB_SIZE, the limit of all memory tablespaces together
                return "SELECT "
                            + " CASE WHEN m.autoextend_mode = 1 THEN LEAST(m.maxsize, TO_NUMBER(d.mem_max_db_size))"
                                + " ELSE m.current_size END TOTAL_SIZE "
                            + " , NVL(mt.used , 0) USED_SIZE"
                        + " FROM v$database d, v$mem_tablespaces m left outer join"
                            + " (SELECT tablespace_id, round(sum(fixed_used_mem + var_used_mem),2) used"
                            + " FROM v$memtbl_info"
                            + " GROUP by tablespace_id) mt on m.space_id = mt.tablespace_id"
                        + " WHERE m.space_id = ?";
            case VOLATILE_USER_DATA:
                return "SELECT"
                        + " m.max_size TOTAL_SIZE"
                        + " , NVL(mt.used, 0) used_size"
                    + " FROM"
                        + " (SELECT"
                            + " space_id"
                            + " , CASE WHEN autoextend_mode = 1 THEN DECODE(max_sizE, 0, (SELECT VALUE1"
                                                  + " FROM V$PROPERTY"
                                                  + " WHERE NAME = 'VOLATILE_MAX_DB_SIZE'), MAX_SIZE)"
                                + " ELSE current_size END AS MAX_SIZE"
                      + " FROM V$VOL_TABLESPACES) M LEFT OUTER JOIN "
                            + " (SELECT tablespace_id, SUM((fixed_used_mem + var_used_mem)) USED"
                            + " FROM V$MEMTBL_INFO"
                            + " GROUP BY tablespace_id ) "
                            + " MT ON m.space_id = mt.tablespace_id"
                    + " WHERE m.space_id = ?";
            case DISK_SYSTEM_DATA:
            case DISK_USER_DATA:
                return "SELECT "
                            + " (d.max * t.page_size) TOTAL_SIZE,"
                            + " nvl(ds.used, 0) USED_SIZE"
                       + " FROM v$tablespaces t LEFT OUTER JOIN "
                           + " (SELECT space_id, sum(total_used_size) USED"
                           + " FROM x$segment"
                           + " GROUP by space_id) ds on ds.space_id = t.id"
                           + ", (SELECT SPACEID"
                           + " , SUM(DECODE(MAXSIZE, 0, CURRSIZE, MAXSIZE)) AS MAX"
                            + " FROM V$DATAFILES"
                           + " GROUP BY SPACEID) D"
                       + " WHERE t.id = D.spaceid AND t.id = ?";
            case DISK_SYSTEM_TEMP:
            case DISK_USER_TEMP:
                return "SELECT "
                            + "(d.max * t.page_size) TOTAL_SIZE,"
                            + " nvl(xts.used_SIZE, 0) USED_SIZE"
                       + " FROM v$tablespaces t LEFT OUTER JOIN "
                               + " (SELECT tbs_id, sum(normal_area_size) used_SIZE "
                           + " FROM x$temptable_stats "
                           + " GROUP BY tbs_id) xts ON t.id = xts.tbs_id"
                           + ", (SELECT SPACEID"
                           + " , SUM(DECODE(MAXSIZE, 0, CURRSIZE, MAXSIZE)) AS MAX"
                            + " FROM V$DATAFILES"
                           + " GROUP BY SPACEID) D"
                       + " WHERE t.id = D.spaceid AND t.id = ?";
            case DISK_SYSTEM_UNDO:
                return "SELECT "
                            + " (d.max * t.page_size) TOTAL_SIZE,"
                            + " ((u.tx_ext_cnt+u.used_ext_cnt+u.unstealable_ext_cnt) * prop.extent_size) USED_SIZE"
                       + " FROM v$tablespaces t, v$disk_undo_usage u,"
                           + " (select value1 extent_size from v$property where name = 'SYS_UNDO_TBS_EXTENT_SIZE') prop"
                           + ", (SELECT SPACEID"
                               + " , SUM(DECODE(MAXSIZE, 0, CURRSIZE, MAXSIZE)) AS MAX"
                            + " FROM V$DATAFILES"
                           + " GROUP BY SPACEID) D"
                       + " WHERE t.id = D.spaceid AND t.id = ?";
            default:
                return null;
        }
    }
    
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }
    
    @Property(viewable = true, order = 2)
    public int getId() {
        return id;
    }
    
    @Property(viewable = true, order = 3)
    public String getTbsType() {
        return tbsType.name();
    }
    
    @Property(viewable = true, order = 4)
    public String getState() {
        return state.name();
    }
    
    @Property(viewable = true, order = 5)
    public String getExtentManagement() {
        return extentManagement;
    }
    
    @Property(viewable = true, order = 6)
    public String getSegmentManagement() {
        return segmentManagement;
    }
    
    @Property(viewable = true, order = 7)
    public int getDataFileCount() {
        return dataFileCount;
    }
    
    @Property(viewable = true, order = 8, formatter = ByteNumberFormat.class)
    public int getPageSizeInKBytes() {
        return pageSizeInBytes;
    }
    
    @Property(viewable = true, order = 9)
    public boolean getIsLogCompression() {
        return isLogCompression;
    }
    
    /**
     * Return avaiable size (byte) in tablespace
     */
    @Property(viewable = true, order = 10, formatter = ByteNumberFormat.class)
    public Long getAvailableSize(DBRProgressMonitor monitor) throws DBException {
        if (availableSize == null) {
            loadSizes(monitor);
        }
        return availableSize;
    }

    /**
     * Return used size (byte) in tablespace
     */
    @Property(viewable = true, order = 11, formatter = ByteNumberFormat.class)
    public Long getUsedSize(DBRProgressMonitor monitor) throws DBException {
        if (usedSize == null) {
            loadSizes(monitor);
        }
        return usedSize;
    }

    /**
     * Return Tablespace type as enumeration
     */
    public TbsType getTbsTypeEnum() {
        return tbsType;
    }
    
    /**
     * Return page size in bytes as int
     */
    public int getPageSizeInBytes() {
        return pageSizeInBytes;
    }
    
    /**
     * Return page size in bytes as String
     */
    public String getPageSizeInBytesStr() {
        return String.valueOf(pageSizeInBytes);
    }
    
    /**
     * Whether this is memory tablespace or not. 
     */
    public boolean isMemTbs() {
        switch (this.tbsType) {
            case MEMORY_SYSTEM_DICTIONARY:
            case MEMORY_SYSTEM_DATA:
            case MEMORY_USER_DATA:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Sets the name of a new tablespace.
     */
    public void setName(@NotNull String name) {
        this.name = name;
    }

    /**
     * Sets the type of a new tablespace (a user type: memory, disk data, temporary, volatile).
     */
    public void setTbsType(@NotNull TbsType tbsType) {
        this.tbsType = tbsType;
    }

    @Nullable
    public AltibaseTablespaceDDL.Space getInitialSpace() {
        return initialSpace;
    }

    /**
     * Sets the data file, size and autoextend of a new tablespace.
     */
    public void setInitialSpace(@Nullable AltibaseTablespaceDDL.Space initialSpace) {
        this.initialSpace = initialSpace;
    }

    /**
     * A tablespace users create and drop (types 2, 4, 6, 8). The system ones (dictionary, system data, undo, system temp)
     * are refused by the server.
     */
    public boolean isUserTablespace() {
        return switch (tbsType) {
            case MEMORY_USER_DATA, DISK_USER_DATA, DISK_USER_TEMP, VOLATILE_USER_DATA -> true;
            default -> false;
        };
    }

    /**
     * A temporary tablespace: its files are TEMPFILEs.
     */
    public boolean isTemporary() {
        return tbsType == TbsType.DISK_USER_TEMP || tbsType == TbsType.DISK_SYSTEM_TEMP;
    }

    /**
     * A user tablespace whose data or temp files are added, changed and dropped by users (disk data and temporary).
     */
    public boolean hasDiskFiles() {
        return tbsType == TbsType.DISK_USER_DATA || tbsType == TbsType.DISK_USER_TEMP;
    }

    /**
     * A user tablespace the server takes ONLINE and OFFLINE (memory and disk data; volatile and temporary refused).
     */
    public boolean supportsOnlineChange() {
        return tbsType == TbsType.MEMORY_USER_DATA || tbsType == TbsType.DISK_USER_DATA;
    }

    /**
     * A user tablespace with its own autoextend settings (memory and volatile; disk ones have them per file).
     */
    public boolean supportsAutoextendChange() {
        return tbsType == TbsType.MEMORY_USER_DATA || tbsType == TbsType.VOLATILE_USER_DATA;
    }

    /**
     * True if online, false if offline, null for another state (backup, dropped, discarded, not read).
     */
    @Nullable
    public Boolean isOnline() {
        return state == State.ONLINE ? Boolean.TRUE : state == State.OFFLINE ? Boolean.FALSE : null;
    }

    /**
     * Reads the autoextend settings of a memory or volatile tablespace (V$MEM_TABLESPACES, V$VOL_TABLESPACES).
     */
    @NotNull
    public AltibaseTablespaceDDL.Autoextend readAutoextend(@NotNull DBRProgressMonitor monitor) throws DBException {
        String qry = tbsType == TbsType.VOLATILE_USER_DATA
            ? "SELECT AUTOEXTEND_MODE, NEXT_SIZE, MAX_SIZE FROM V$VOL_TABLESPACES WHERE SPACE_ID = ?"
            : "SELECT AUTOEXTEND_MODE, AUTOEXTEND_NEXTSIZE, MAXSIZE FROM V$MEM_TABLESPACES WHERE SPACE_ID = ?";
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read autoextend of tablespace '" + getName() + "'");
             JDBCPreparedStatement dbStat = session.prepareStatement(qry)) {
            dbStat.setInt(1, getId());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    throw new DBException("Tablespace " + getName() + " not found");
                }
                // Bytes; null (0) next and max mean the server's default
                long next = dbResult.getLong(2) / 1024;
                long max = dbResult.getLong(3) / 1024;
                return new AltibaseTablespaceDDL.Autoextend(dbResult.getInt(1) == 1, next == 0 ? null : next, max == 0 ? null : max);
            }
        } catch (SQLException e) {
            throw new DBDatabaseException("Can't read tablespace autoextend", e, getDataSource());
        }
    }

    /**
     * After CREATE (same session): takes the new tablespace's catalog row (ID, type, state, page size).
     */
    public void readCreated(@NotNull JDBCSession session) throws SQLException {
        readRow(session, "SELECT * FROM V$TABLESPACES WHERE NAME = ?", name);
    }

    /**
     * Reads the tablespace's catalog row again (state, data file count) and forgets the sizes.
     *
     * @return false if the tablespace no longer exists
     */
    public boolean reloadRow(@NotNull JDBCSession session) throws SQLException {
        usedSize = null;
        availableSize = null;
        return readRow(session, "SELECT * FROM V$TABLESPACES WHERE ID = ?", id);
    }

    private boolean readRow(@NotNull JDBCSession session, @NotNull String qry, @NotNull Object key) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(qry)) {
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

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        usedSize = null;
        availableSize = null;
        
        fileCache.clearCache();
        tablePartnCache.clearCache();
        indexPartnCache.clearCache();
        
        getDataSource().resetStatistics();
        if (isPersisted()) {
            // The state (online/offline) and data file count come from the catalog row, not from the caches above
            try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read tablespace '" + getName() + "'")) {
                if (!reloadRow(session)) {
                    return null;
                }
            } catch (SQLException e) {
                throw new DBDatabaseException("Can't read tablespace", e, getDataSource());
            }
        }
        return this;
    }

    ///////////////////////////////////////////////
    // Statistics

    @Override
    public boolean hasStatistics() {
        return usedSize != null;
    }

    @Override
    public long getStatObjectSize() {
        return usedSize == null ? 0 : usedSize;
    }

    /**
     * Load tablespace size
     */
    public void loadSizes(DBRProgressMonitor monitor) throws DBException {
        if (getSizeQuery(tbsType) == null) {
            availableSize = 0L;
            usedSize = 0L;
            return;
        }
        
        try (final JDBCSession session = DBUtils.openMetaSession(
                monitor, this, "Load tablespace '" + getName() + "' statistics")) {
            Sizes sizes = readSizes(session, tbsType, getId());
            if (sizes != null) {
                usedSize = sizes.used();
                availableSize = sizes.total() - sizes.used();
            }
        } catch (SQLException e) {
            throw new DBDatabaseException("Can't read tablespace statistics", e, getDataSource());
        }
    }

    /**
     * The size limit and the used size of a tablespace, in bytes.
     *
     * @param total what the tablespace may grow to (TOTAL_SIZE; the tree shows {@code total - used} as Available Size)
     * @param used  USED_SIZE
     */
    public record Sizes(long total, long used) {
    }

    /**
     * Reads the sizes of one tablespace in the given session (the tree's meta session, or the Activity Monitor's).
     *
     * @return null for an unknown type or when the server returns no row
     */
    @Nullable
    public static Sizes readSizes(@NotNull JDBCSession session, @NotNull TbsType tbsType, int tablespaceId) throws SQLException {
        String qry = getSizeQuery(tbsType);
        if (qry == null) {
            return null;
        }
        try (JDBCPreparedStatement dbStat = session.prepareStatement(qry)) {
            dbStat.setInt(1, tablespaceId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    return null;
                }
                return new Sizes(dbResult.getLong("TOTAL_SIZE"), dbResult.getLong("USED_SIZE"));
            }
        }
    }
    
    /**
     * Return file collection belongs to this tablespace.
     */
    @Association
    public Collection<AltibaseDataFile> getFiles(DBRProgressMonitor monitor) throws DBException {
        return fileCache.getAllObjects(monitor, this);
    }
    
    /**
     * Returns AltibaseDataFile matches to fileId
     */
    public AltibaseDataFile getFile(DBRProgressMonitor monitor, int fileId) throws DBException {
        for (AltibaseDataFile file : fileCache.getAllObjects(monitor, this)) {
            if (file.getId() == fileId) {
                return file;
            }
        }
        return null;
    }
    
    public FileCache getFileCache() {
        return fileCache;
    }
    
    /**
     *  Returns data files  
     */
    static class FileCache extends JDBCObjectCache<AltibaseTablespace, AltibaseDataFile> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner) throws SQLException {
            
            String qry = null;
            
            if (owner.isMemTbs()) {
                /*
                 * The memory data file consists of two sets, xxx-0-0 and xxx-1-0.
                 */
                qry =  " SELECT"
                            + " mt.ID ID"
                            + " , mt.space_id SPACEID"
                            + " , p.checkpoint_path || '/' || dbfile_name NAME"
                            + " , mt.current_size "
                            + " , mt.dbfile_size DBFILE_SIZE"
                        + " FROM"
                            + " (SELECT "
                                + " 0 ID"
                                + " , space_name || '-0-0' dbfile_name"
                                + " , space_id"
                                + " , current_size "
                                + " , dbfile_size"
                            + " FROM v$mem_tablespaces"
                            + " UNION ALL"
                            + " SELECT "
                                + " 1 ID"
                                + " , space_name || '-1-0' dbfile_name"
                                + " , space_id"
                                + " , current_size "
                                + " , dbfile_size"
                            + " FROM v$mem_tablespaces"
                            + " ) mt,"
                             + " v$mem_tablespace_checkpoint_paths p"
                        + " WHERE"
                            + " p.space_id = mt.space_id AND mt.space_id = ?"
                        + " ORDER BY ID ASC";
            } else {
                qry = "SELECT * FROM V$DATAFILES WHERE SPACEID = ? ORDER BY NAME";
            }
            
            final JDBCPreparedStatement dbStat = session.prepareStatement(qry);
            dbStat.setInt(1, owner.id);
            return dbStat;
        }

        @Override
        protected AltibaseDataFile fetchObject(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            if (owner.isMemTbs()) {
                return new AltibaseDataFile4Mem(owner, resultSet);
            } else {
                return new AltibaseDataFile4Disk(owner, resultSet);
            }
        }
    }

    /**
     * Return tables belongs to this tablespace.
     */
    @Association
    public Collection<AltibaseTablespaceObj4Table> getAltibaseTablespaceObj4Tables(DBRProgressMonitor monitor) throws DBException {
        return tablePartnCache.getAllObjects(monitor, this);
    }

    /**
     *  Returns Table and partition belongs to this partition  
     */
    static class TablePartnCache extends JDBCObjectCache<AltibaseTablespace, AltibaseTablespaceObj4Table> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner) throws SQLException {
            
            String qry = 
                    "SELECT * FROM"
                    + " ("
                    + " SELECT"
                        + " u.user_name, t.table_name AS obj_name, null as partition_name"
                    + " FROM"
                        + " system_.sys_users_ u, system_.sys_tables_ t"
                    + " WHERE"
                        + " u.user_id = t.user_id AND t.table_type IN (" + TABLE_KINDS + ") AND "
                        + " t.is_partitioned = 'F' AND t.tbs_id = ?"
                    + " UNION ALL"
                    + " SELECT"
                        + " u.user_name, t.table_name AS obj_name, tp.partition_name"
                    + " FROM"
                        + " system_.sys_users_ u, system_.sys_tables_ t, system_.sys_table_partitions_ tp"
                    + " WHERE"
                        + " u.user_id = t.user_id AND t.table_type IN (" + TABLE_KINDS + ") AND "
                        + " t.is_partitioned = 'T' AND t.table_id = tp.table_id AND tp.tbs_id = ?"
                + " )"
                + " ORDER BY 1,2,3";

            
            final JDBCPreparedStatement dbStat = session.prepareStatement(qry);
            dbStat.setInt(1, owner.id);
            dbStat.setInt(2, owner.id);
            return dbStat;
        }

        @Override
        protected AltibaseTablespaceObj4Table fetchObject(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new AltibaseTablespaceObj4Table(owner, resultSet);
        }
    }
    
    /**
     *  Returns Table and partition belongs to this partition  
     */

    @Association
    public Collection<AltibaseTablespaceObj4Index> getAltibaseTablespaceObj4Indexes(DBRProgressMonitor monitor) throws DBException {
        return indexPartnCache.getAllObjects(monitor, this);
    }

    static class IndexPartnCache extends JDBCObjectCache<AltibaseTablespace, AltibaseTablespaceObj4Index> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner) throws SQLException {
            
            String qry = 
                    "SELECT * FROM"
                    + " ("
                    + " SELECT"
                        + " u.user_name"
                        + " ,i.index_name AS obj_name"
                        + " ,NULL AS partition_name"
                        + " , ut.user_name AS table_schema"
                        + " , t.table_name"
                    + " FROM"
                        + " system_.sys_users_ u, system_.sys_indices_ i, system_.sys_users_ ut, system_.sys_tables_ t"
                    + " WHERE"
                        + " u.user_id = i.user_id AND i.is_partitioned = 'F'"
                        + " AND i.table_id = t.table_id AND ut.user_id = t.user_id"
                        + " AND t.table_type IN (" + TABLE_KINDS + ")"
                        + " AND i.tbs_id = ?"
                    + " UNION ALL "
                    + " SELECT"
                        + " u.user_name"
                        + " ,i.index_name AS obj_name"
                        + " ,ip.index_partition_name"
                        + " , ut.user_name AS table_schema"
                        + " , t.table_name"
                    + " FROM"
                        + " system_.sys_users_ u, system_.sys_indices_ i, system_.sys_index_partitions_ ip, "
                        + " system_.sys_users_ ut, system_.sys_tables_ t"
                    + " WHERE"
                        + " u.user_id = ip.user_id"
                        + " AND i.index_id = ip.index_id"
                        + " AND i.is_partitioned = 'T'"
                        + " AND i.table_id = t.table_id AND ut.user_id = t.user_id"
                        + " AND t.table_type IN (" + TABLE_KINDS + ")"
                        + " AND ip.tbs_id = ?"
                    + " )"
                    + " ORDER BY 1, 2, 3";

            final JDBCPreparedStatement dbStat = session.prepareStatement(qry);
            dbStat.setInt(1, owner.id);
            dbStat.setInt(2, owner.id);
            return dbStat;
        }

        @Override
        protected AltibaseTablespaceObj4Index fetchObject(
                @NotNull JDBCSession session, 
                @NotNull AltibaseTablespace owner, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new AltibaseTablespaceObj4Index(owner, resultSet);
        }
    }

    static Object resolveTablespaceReference(DBRProgressMonitor monitor, DBSObjectLazy<AltibaseDataSource> referrer, 
            @Nullable Object propertyId) throws DBException {
        final AltibaseDataSource dataSource = referrer.getDataSource();
        return AltibaseUtils.resolveLazyReference(monitor, dataSource, dataSource.tablespaceCache, referrer, propertyId);
    }

    public static class TablespaceReferenceValidator implements IPropertyCacheValidator<DBSObjectLazy<AltibaseDataSource>> {
        @Override
        public boolean isPropertyCached(@NotNull DBSObjectLazy<AltibaseDataSource> object, @NotNull Object propertyId) {
            return object.getLazyReference(propertyId) instanceof AltibaseTablespace ||
                    object.getLazyReference(propertyId) == null ||
                    object.getDataSource().tablespaceCache.isFullyCached();
        }
    }
}
