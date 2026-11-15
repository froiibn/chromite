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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPObjectStatistics;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPScriptObject;
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
import org.jkiss.utils.ByteNumberFormat;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Oracle tablespace
 *
 * 설계(2026-11-15 테이블스페이스 관리): 어떤 저장소 명령을 보일지는 이름 목록이 아니라 모델이 읽은 카탈로그 값으로 정한다
 * ({@link #isFixedStorage}, {@link #supportsOnlineChange}, {@link #supportsReadOnlyChange}, {@link #supportsFileAdd}). 이름으로 정하는 것은
 * 데이터베이스가 정한 SYSTEM·SYSAUX뿐이다. 기본 테이블스페이스와 활성 언두는 {@link OracleStorageRoles}(목록을 읽을 때 함께 읽음)로 정한다.
 * 카탈로그 행의 값은 {@link #loadRow}가 넣는다: 새로 만든 테이블스페이스는 CREATE 직후 자기 행을 읽고({@link #readCreated}), 새로 고침은
 * 캐시만 비우지 않고 자기 행을 다시 읽어 상태(온라인·오프라인·읽기 전용)가 맞게 한다({@link #refreshObject}).
 *
 * 변경 이력:
 * - 2026-11-15 새 테이블스페이스 객체, 이름 바꾸기({@link DBPNamedObject2}), 지원 판단, 행 다시 읽기, 파일 캐시 공개(앱 변경 #127,
 *   docs/chromite/oracle-storage.md)
 */
public class OracleTablespace extends OracleGlobalObject implements DBPRefreshableObject, DBPObjectStatistics, DBPScriptObject, DBPNamedObject2
{
    /**
     * Tablespaces whose names the database itself fixes; they get no storage commands.
     */
    private static final Set<String> DATABASE_TABLESPACES = Set.of("SYSTEM", "SYSAUX");

    private static final Log log = Log.getLog(OracleTablespace.class);

    public enum Status {
        ONLINE,
        OFFLINE,
        READ_ONLY
    }

    public enum Contents {
        PERMANENT,
        TEMPORARY,
        UNDO
    }

    public enum Logging {
        LOGGING,
        NOLOGGING,
    }

    public enum ExtentManagement {
        DICTIONARY,
        LOCAL
    }

    public enum AllocationType {
        SYSTEM,
        UNIFORM,
        USER,
    }

    public enum SegmentSpaceManagement {
        MANUAL,
        AUTO
    }

    public enum Retention {
        GUARANTEE,
        NOGUARANTEE,
        NOT_APPLY
    }

    private String name;
    private long blockSize;
    private long initialExtent;
    private long nextExtent;
    private long minExtents;
    private long maxExtents;
    private long pctIncrease;
    private long minExtLen;
    private Status status;
    private Contents contents;
    private Logging logging;
    private boolean forceLogging;
    private ExtentManagement extentManagement;
    private AllocationType allocationType;
    private boolean pluggedIn;
    private SegmentSpaceManagement segmentSpaceManagement;
    private boolean defTableCompression;
    private Retention retention;
    private boolean bigFile;
    private volatile Long availableSize;
    private volatile Long usedSize;

    private final FileCache fileCache = new FileCache();
    private final SegmentCache segmentCache = new SegmentCache();
    private String ddlStringHolder = null;

    // The settings of a new tablespace (CREATE only)
    @Nullable
    private OracleTablespaceDDL.NewTablespace newSettings;

    protected OracleTablespace(OracleDataSource dataSource, ResultSet dbResult)
    {
        super(dataSource, true);
        loadRow(dbResult);
    }

    /**
     * A new (not yet created) tablespace.
     */
    public OracleTablespace(@NotNull OracleDataSource dataSource)
    {
        super(dataSource, false);
        this.name = "";
        this.status = Status.ONLINE;
        this.contents = Contents.PERMANENT;
    }

    /**
     * Takes the values of a DBA_TABLESPACES (or USER_TABLESPACES) row.
     */
    private void loadRow(@NotNull ResultSet dbResult)
    {
        this.name = JDBCUtils.safeGetString(dbResult, "TABLESPACE_NAME");
        this.blockSize = JDBCUtils.safeGetLong(dbResult, "BLOCK_SIZE");
        this.initialExtent = JDBCUtils.safeGetLong(dbResult, "INITIAL_EXTENT");
        this.nextExtent = JDBCUtils.safeGetLong(dbResult, "NEXT_EXTENT");
        this.minExtents = JDBCUtils.safeGetLong(dbResult, "MIN_EXTENTS");
        this.maxExtents = JDBCUtils.safeGetLong(dbResult, "MAX_EXTENTS");
        this.pctIncrease = JDBCUtils.safeGetLong(dbResult, "PCT_INCREASE");
        this.minExtLen = JDBCUtils.safeGetLong(dbResult, "MIN_EXTLEN");
        this.status = CommonUtils.valueOf(
            Status.class,
            JDBCUtils.safeGetString(dbResult, OracleConstants.COLUMN_STATUS),
            Status.OFFLINE,
            true);
        this.contents = CommonUtils.valueOf(Contents.class, JDBCUtils.safeGetString(dbResult, "CONTENTS"), null, true);
        this.logging = CommonUtils.valueOf(Logging.class, JDBCUtils.safeGetString(dbResult, "LOGGING"), null, true);
        this.forceLogging = JDBCUtils.safeGetBoolean(dbResult, "FORCE_LOGGING", OracleConstants.RESULT_YES_VALUE);
        this.extentManagement = CommonUtils.valueOf(ExtentManagement.class, JDBCUtils.safeGetString(dbResult, "EXTENT_MANAGEMENT"), null, true);
        this.allocationType = CommonUtils.valueOf(AllocationType.class, JDBCUtils.safeGetString(dbResult, "ALLOCATION_TYPE"), null, true);
        this.pluggedIn = JDBCUtils.safeGetBoolean(dbResult, "PLUGGED_IN", OracleConstants.RESULT_YES_VALUE);
        this.segmentSpaceManagement = CommonUtils.valueOf(SegmentSpaceManagement.class, JDBCUtils.safeGetString(dbResult, "SEGMENT_SPACE_MANAGEMENT"), null, true);
        this.defTableCompression = "ENABLED".equals(JDBCUtils.safeGetString(dbResult, "DEF_TAB_COMPRESSION"));
        this.retention = CommonUtils.valueOf(Retention.class, JDBCUtils.safeGetString(dbResult, "RETENTION"), null, true);
        this.bigFile = JDBCUtils.safeGetBoolean(dbResult, "BIGFILE", OracleConstants.RESULT_YES_VALUE);
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, order = 1)
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of a new tablespace, or the new name after a rename.
     */
    @Override
    public void setName(String name)
    {
        this.name = name;
    }

    @Property(viewable = true, order = 4, formatter = ByteNumberFormat.class)
    public Long getAvailableSize(DBRProgressMonitor monitor) throws DBException {
        if (availableSize == null) {
            loadSizes(monitor);
        }
        return availableSize;
    }

    @Property(viewable = true, order = 5, formatter = ByteNumberFormat.class)
    public Long getUsedSize(DBRProgressMonitor monitor) throws DBException {
        if (usedSize == null) {
            loadSizes(monitor);
        }
        return usedSize;
    }

    @Property(viewable = true, editable = true, order = 22, formatter = ByteNumberFormat.class)
    public long getBlockSize()
    {
        return blockSize;
    }

    @Property(editable = true, order = 23)
    public long getInitialExtent()
    {
        return initialExtent;
    }

    @Property(editable = true, order = 24)
    public long getNextExtent()
    {
        return nextExtent;
    }

    @Property(editable = true, order = 25)
    public long getMinExtents()
    {
        return minExtents;
    }

    @Property(editable = true, order = 26)
    public long getMaxExtents()
    {
        return maxExtents;
    }

    @Property(editable = true, order = 27)
    public long getPctIncrease()
    {
        return pctIncrease;
    }

    @Property(editable = true, order = 28)
    public long getMinExtLen()
    {
        return minExtLen;
    }

    @Property(viewable = true, editable = true, order = 29)
    public Status getStatus()
    {
        return status;
    }

    @Property(editable = true, order = 30)
    public Contents getContents()
    {
        return contents;
    }

    @Property(editable = true, order = 31)
    public Logging isLogging()
    {
        return logging;
    }

    @Property(editable = true, order = 32)
    public boolean isForceLogging()
    {
        return forceLogging;
    }

    @Property(editable = true, order = 33)
    public ExtentManagement getExtentManagement()
    {
        return extentManagement;
    }

    @Property(editable = true, order = 34)
    public AllocationType getAllocationType()
    {
        return allocationType;
    }

    @Property(editable = true, order = 35)
    public boolean isPluggedIn()
    {
        return pluggedIn;
    }

    @Property(editable = true, order = 36)
    public SegmentSpaceManagement getSegmentSpaceManagement()
    {
        return segmentSpaceManagement;
    }

    @Property(editable = true, order = 37)
    public boolean isDefTableCompression()
    {
        return defTableCompression;
    }

    @Property(editable = true, order = 38)
    public Retention getRetention()
    {
        return retention;
    }

    @Property(editable = true, order = 39)
    public boolean isBigFile()
    {
        return bigFile;
    }

    @Association
    public Collection<OracleDataFile> getFiles(DBRProgressMonitor monitor) throws DBException
    {
        return fileCache.getAllObjects(monitor, this);
    }

    public OracleDataFile getFile(DBRProgressMonitor monitor, long relativeFileNo) throws DBException
    {
        for (OracleDataFile file : fileCache.getAllObjects(monitor, this)) {
            if (file.getRelativeNo() == relativeFileNo) {
                return file;
            }
        }
        return null;
    }

    public FileCache getFileCache() {
        return fileCache;
    }

    @Association
    public Collection<OracleSegment<OracleTablespace>> getSegments(DBRProgressMonitor monitor) throws DBException
    {
        return segmentCache.getAllObjects(monitor, this);
    }

    @Nullable
    public OracleTablespaceDDL.NewTablespace getNewSettings()
    {
        return newSettings;
    }

    /**
     * Sets the kind, file, size and autoextend of a new tablespace.
     */
    public void setNewSettings(@NotNull OracleTablespaceDDL.NewTablespace newSettings)
    {
        this.newSettings = newSettings;
        this.contents = newSettings.contents();
    }

    /**
     * A tablespace the database depends on, so no storage command is offered: SYSTEM and SYSAUX (names the database
     * fixes), the database default permanent and temporary tablespaces and the active undo tablespace
     * ({@link OracleStorageRoles}). When the login cannot read which one that is, every tablespace of that kind counts.
     */
    public boolean isFixedStorage()
    {
        if (!isPersisted()) {
            return false;
        }
        if (DATABASE_TABLESPACES.contains(name) || contents == null) {
            return true;
        }
        OracleStorageRoles roles = getDataSource().getTablespaceCache().getStorageRoles();
        String role = switch (contents) {
            case PERMANENT -> roles.defaultPermanent();
            case TEMPORARY -> roles.defaultTemporary();
            case UNDO -> roles.activeUndo();
        };
        return role == null || role.equalsIgnoreCase(name);
    }

    /**
     * ONLINE and OFFLINE: permanent and undo tablespaces (temporary ones refused with ORA-03217).
     */
    public boolean supportsOnlineChange()
    {
        return !isFixedStorage() && contents != Contents.TEMPORARY;
    }

    /**
     * READ ONLY and READ WRITE: permanent tablespaces only (undo ORA-30021, temporary ORA-03217).
     */
    public boolean supportsReadOnlyChange()
    {
        return !isFixedStorage() && contents == Contents.PERMANENT;
    }

    /**
     * Data and temp files may be added, changed and dropped.
     */
    public boolean supportsFileChanges()
    {
        return !isFixedStorage();
    }

    /**
     * A file may be added: not to a bigfile tablespace (ORA-32771).
     */
    public boolean supportsFileAdd()
    {
        return supportsFileChanges() && !bigFile;
    }

    /**
     * True if online, false if offline, null if read only (the status column does not tell whether a read-only
     * tablespace is offline too).
     */
    @Nullable
    public Boolean isOnline()
    {
        return status == Status.ONLINE ? Boolean.TRUE : status == Status.OFFLINE ? Boolean.FALSE : null;
    }

    /**
     * After CREATE (same session): takes the new tablespace's catalog row.
     */
    public void readCreated(@NotNull JDBCSession session) throws SQLException
    {
        readRow(session);
    }

    /**
     * Reads the tablespace's catalog row again (status, contents) and forgets the sizes.
     *
     * @return false if the tablespace no longer exists
     */
    public boolean reloadRow(@NotNull JDBCSession session) throws SQLException
    {
        availableSize = null;
        usedSize = null;
        return readRow(session);
    }

    private boolean readRow(@NotNull JDBCSession session) throws SQLException
    {
        try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM "
            + OracleUtils.getSysUserViewName(session.getProgressMonitor(), getDataSource(), "TABLESPACES") + " WHERE TABLESPACE_NAME=?")) {
            dbStat.setString(1, name);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    return false;
                }
                loadRow(dbResult);
                return true;
            }
        }
    }

    /**
     * Forgets the sizes after a data file change (they are read again when shown).
     */
    public void resetSizes()
    {
        availableSize = null;
        usedSize = null;
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException
    {
        availableSize = null;
        usedSize = null;
        ddlStringHolder = null;
        fileCache.clearCache();
        segmentCache.clearCache();
        getDataSource().resetStatistics();
        if (isPersisted()) {
            // The status (online, offline, read only) comes from the catalog row, not from the caches above
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

    private void loadSizes(DBRProgressMonitor monitor) throws DBException {
        try (final JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load tablespace '" + getName() + "' statistics")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM\n" +
                "(SELECT SUM(F.BYTES) AVAILABLE_SPACE FROM " + OracleUtils.getSysSchemaPrefix(getDataSource()) + "DBA_DATA_FILES F WHERE F.TABLESPACE_NAME=?) XDF,\n" +
                "(SELECT SUM(S.BYTES) USED_SPACE FROM " + OracleUtils.getSysSchemaPrefix(getDataSource()) + "DBA_SEGMENTS S WHERE S.TABLESPACE_NAME=?) XS")) {
                dbStat.setString(1, getName());
                dbStat.setString(2, getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        fetchSizes(dbResult);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException("Can't read tablespace statistics", e, getDataSource());
        }
    }

    void fetchSizes(JDBCResultSet dbResult) throws SQLException {
        availableSize = dbResult.getLong("AVAILABLE_SPACE");
        usedSize = dbResult.getLong("USED_SPACE");
    }


    // Public: the data file manager (edit package) caches new files in it
    public static class FileCache extends JDBCObjectCache<OracleTablespace, OracleDataFile> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull OracleTablespace owner) throws SQLException
        {
            final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT * FROM " + OracleUtils.getSysSchemaPrefix(owner.getDataSource()) + "DBA_" +
                    (owner.getContents() == Contents.TEMPORARY ? "TEMP" : "DATA") +
                    "_FILES WHERE TABLESPACE_NAME=? ORDER BY FILE_NAME");
            dbStat.setString(1, owner.getName());
            return dbStat;
        }

        @Override
        protected OracleDataFile fetchObject(@NotNull JDBCSession session, @NotNull OracleTablespace owner, @NotNull JDBCResultSet resultSet) throws SQLException, DBException
        {
            return new OracleDataFile(owner, resultSet, owner.getContents() == Contents.TEMPORARY);
        }
    }

    static class SegmentCache extends JDBCObjectCache<OracleTablespace, OracleSegment<OracleTablespace>> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull OracleTablespace owner) throws SQLException
        {
            final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT * FROM " + OracleUtils.getSysUserViewName(session.getProgressMonitor(), owner.getDataSource(), "SEGMENTS") +
                " WHERE TABLESPACE_NAME=? ORDER BY SEGMENT_NAME");
            dbStat.setString(1, owner.getName());
            return dbStat;
        }

        @Override
        protected OracleSegment<OracleTablespace> fetchObject(@NotNull JDBCSession session, @NotNull OracleTablespace owner, @NotNull JDBCResultSet resultSet) throws SQLException, DBException
        {
            return new OracleSegment<>(session.getProgressMonitor(), owner, resultSet);
        }
    }

    public static Object resolveTablespaceReference(DBRProgressMonitor monitor, DBSObjectLazy<OracleDataSource> referrer, @Nullable Object propertyId) throws DBException
    {
        final OracleDataSource dataSource = referrer.getDataSource();
        if (!dataSource.isAdmin()) {
            return referrer.getLazyReference(propertyId);
        } else {
            return OracleUtils.resolveLazyReference(monitor, dataSource, dataSource.getTablespaceCache(), referrer, propertyId);
        }
    }

    public static class TablespaceReferenceValidator implements IPropertyCacheValidator<DBSObjectLazy<OracleDataSource>> {
        @Override
        public boolean isPropertyCached(@NotNull DBSObjectLazy<OracleDataSource> object, @NotNull Object propertyId)
        {
            return
                object.getLazyReference(propertyId) instanceof OracleTablespace ||
                object.getLazyReference(propertyId) == null ||
                object.getDataSource().getTablespaceCache().isFullyCached() ||
                !object.getDataSource().isAdmin();
        }
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {

        if (ddlStringHolder != null) {
            return ddlStringHolder;
        }
        String objectType = "TABLESPACE";
        String objectName = getName();
        String ddl = null;
        try (final JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load source code for " + objectType + " '" + objectName + "'")) {
            if (this.getDataSource().isAtLeastV9()) {
                try {
                    JDBCUtils.executeProcedure(
                        session,
                        "begin\n" +
                            "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',true);\n" +
                            "end;");
                } catch (SQLException e) {
                    log.error("Can't apply DDL transform parameters", e);
                }
            }
            ddl = OracleUtils.fetchDDL(session, objectType, objectName);
        } catch (SQLException e) {
            log.error("Can't fetch DDL for " + objectType + ":" + objectName, e);
        }
        if (ddl == null) {
            ddl = "-- EMPTY DDL";
        }
        ddlStringHolder = ddl.trim();
        return ddlStringHolder;
    }

}
