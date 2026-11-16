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
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

/**
 * A filegroup of a SQL Server database ({@code sys.filegroups}) with its files.
 *
 * 설계: 값은 그 데이터베이스의 {@code [db].sys.filegroups} 행과 파일 수다. 무엇을 할 수 있는지는 카탈로그 값으로 정한다(시험 서버의 오류로
 * 확인):
 * <ul>
 *     <li>명령은 행 데이터 파일 그룹({@code type = 'FG'})에만 있다. FILESTREAM·메모리 최적화 파일 그룹은 보이기만 한다(파일이 디렉터리이고
 *     크기가 없어 같은 대화상자로 다룰 수 없다).</li>
 *     <li>주 파일 그룹({@code data_space_id = 1}, 서버가 정한 번호)은 이름 바꾸기(5012), 읽기 전용(5047), 삭제를 서버가 거절한다.</li>
 *     <li>파일이 없는 파일 그룹은 기본값·읽기 전용으로 바꿀 수 없다(5050). 그래서 파일 수를 함께 읽는다({@link #getFileCount}).</li>
 *     <li>읽기 전용 파일 그룹에는 파일을 추가·변경·삭제할 수 없다(5048). 이름 바꾸기는 된다.</li>
 * </ul>
 * 새로 고침({@link #refreshObject})은 데이터베이스의 파일 그룹 행을 모두 다시 읽는다: 기본 파일 그룹을 바꾸면 이전 기본 파일 그룹의 값도
 * 바뀌기 때문이다. 파일 목록은 비우고 다시 읽는다(읽기 전용을 바꾸면 파일의 읽기 전용 값도 바뀐다).
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public class SQLServerFilegroup implements DBSObject, DBPNamedObject2, DBPSaveableObject, DBPRefreshableObject {

    /**
     * {@code sys.filegroups.type} of a rows filegroup.
     */
    private static final String TYPE_ROWS = "FG"; //$NON-NLS-1$
    // data_space_id of the primary filegroup
    private static final int PRIMARY_DATA_SPACE_ID = 1;

    private final SQLServerDatabase database;
    private final FileCache fileCache = new FileCache();
    private boolean persisted;
    private String name;
    private int dataSpaceId;
    private String type;
    private String typeDesc;
    private boolean isDefault;
    private boolean readOnly;
    private int fileCount;

    SQLServerFilegroup(@NotNull SQLServerDatabase database, @NotNull ResultSet dbResult) {
        this.database = database;
        loadRow(dbResult);
        this.persisted = true;
    }

    /**
     * A new (not yet added) rows filegroup.
     */
    public SQLServerFilegroup(@NotNull SQLServerDatabase database) {
        this.database = database;
        this.name = ""; //$NON-NLS-1$
        this.type = TYPE_ROWS;
    }

    void loadRow(@NotNull ResultSet dbResult) {
        this.name = JDBCUtils.safeGetString(dbResult, "name");
        this.dataSpaceId = JDBCUtils.safeGetInt(dbResult, "data_space_id");
        this.type = JDBCUtils.safeGetString(dbResult, "type");
        this.typeDesc = JDBCUtils.safeGetString(dbResult, "type_desc");
        this.isDefault = JDBCUtils.safeGetBoolean(dbResult, "is_default");
        this.readOnly = JDBCUtils.safeGetBoolean(dbResult, "is_read_only");
        this.fileCount = JDBCUtils.safeGetInt(dbResult, "file_count");
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
    public String getType() {
        return typeDesc;
    }

    @Property(viewable = true, order = 3)
    public boolean isDefault() {
        return isDefault;
    }

    @Property(viewable = true, order = 4)
    public boolean isReadOnly() {
        return readOnly;
    }

    @Property(viewable = true, order = 5)
    public int getFileCount() {
        return fileCount;
    }

    public int getDataSpaceId() {
        return dataSpaceId;
    }

    public boolean isPrimary() {
        return dataSpaceId == PRIMARY_DATA_SPACE_ID;
    }

    /**
     * A rows filegroup of a database whose storage users change: it may get files, become the default, be renamed.
     */
    public boolean supportsChanges() {
        return persisted && TYPE_ROWS.equals(type) && database.supportsStorageChanges();
    }

    /**
     * {@link #supportsChanges} and not the primary filegroup: renamed, removed, made read-only.
     */
    public boolean supportsSecondaryChanges() {
        return supportsChanges() && !isPrimary();
    }

    /**
     * New files can be added: {@link #supportsChanges} and not read-only.
     */
    public boolean canAddFiles() {
        return supportsChanges() && !readOnly;
    }

    @NotNull
    public SQLServerDatabase getDatabase() {
        return database;
    }

    @Association
    @NotNull
    public Collection<SQLServerDatabaseFile> getFiles(@NotNull DBRProgressMonitor monitor) throws DBException {
        return fileCache.getAllObjects(monitor, this);
    }

    @NotNull
    public FileCache getFileCache() {
        return fileCache;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public SQLServerDatabase getParentObject() {
        return database;
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
     * Reads the filegroup's row by its name (same session): after ADD FILEGROUP (ID, type), ADD FILE and REMOVE FILE (file count).
     */
    public void readRow(@NotNull JDBCSession session) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(SQLServerDatabase.getFilegroupQuery(database) + " WHERE fg.name = ?")) { //$NON-NLS-1$
            dbStat.setString(1, name);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (dbResult.next()) {
                    loadRow(dbResult);
                }
            }
        }
    }

    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        fileCache.clearCache();
        if (!persisted) {
            return this;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read filegroups of '" + database.getName() + "'")) { //$NON-NLS-1$ //$NON-NLS-2$
            return database.reloadFilegroups(session, this) ? this : null;
        } catch (SQLException e) {
            throw new DBDatabaseException("Can't read filegroups", e, getDataSource());
        }
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * The data files of the filegroup.
     */
    public static class FileCache extends JDBCObjectCache<SQLServerFilegroup, SQLServerDatabaseFile> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull SQLServerFilegroup filegroup) throws SQLException {
            JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM "
                + SQLServerUtils.getSystemTableName(filegroup.getDatabase(), "database_files") //$NON-NLS-1$
                + " WHERE data_space_id = ? ORDER BY file_id"); //$NON-NLS-1$
            dbStat.setInt(1, filegroup.getDataSpaceId());
            return dbStat;
        }

        @Override
        protected SQLServerDatabaseFile fetchObject(@NotNull JDBCSession session, @NotNull SQLServerFilegroup filegroup, @NotNull JDBCResultSet resultSet) {
            return new SQLServerDatabaseFile(filegroup.getDatabase(), filegroup, resultSet);
        }
    }
}
