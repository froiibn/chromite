/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Oracle tablespace file
 *
 * 변경 이력:
 * - 2026-11-15 새 파일의 설정과 ADD 직후 행 읽기({@link #readCreated}): 서버가 붙인 전체 경로를 이름으로 받는다(상대 이름은
 *   {@code $ORACLE_HOME/dbs} 아래가 됨). 이름은 파일 경로라 따옴표로 감싸지 않는다({@link #getFullyQualifiedName})
 *   (앱 변경 #127, docs/chromite/oracle-storage.md)
 */
public class OracleDataFile extends OracleObject<OracleTablespace> implements DBPQualifiedObject {

    public enum OnlineStatus {
        SYSOFF,
        SYSTEM,
        OFFLINE,
        ONLINE,
        RECOVER,
    }

    private final OracleTablespace tablespace;
    private long id;
    private long relativeNo;
    private BigDecimal bytes;
    private BigDecimal blocks;
    private BigDecimal maxBytes;
    private BigDecimal maxBlocks;
    private long incrementBy;
    private BigDecimal userBytes;
    private BigDecimal userBlocks;

    private boolean available;
    private boolean autoExtensible;
    private OnlineStatus onlineStatus;

    private boolean temporary;

    // The file name, size and autoextend of a new file (ADD only)
    @Nullable
    private OracleTablespaceDDL.Space newSpace;

    protected OracleDataFile(OracleTablespace tablespace, ResultSet dbResult, boolean temporary)
    {
        super(
            tablespace,
            JDBCUtils.safeGetString(dbResult, "FILE_NAME"),
            true);
        this.tablespace = tablespace;
        this.temporary = temporary;
        loadRow(dbResult);
    }

    /**
     * Takes the values of a DBA_DATA_FILES or DBA_TEMP_FILES row (the name is set by the caller).
     */
    private void loadRow(@NotNull ResultSet dbResult)
    {
        this.id = JDBCUtils.safeGetLong(dbResult, "FILE_ID");
        this.relativeNo = JDBCUtils.safeGetLong(dbResult, "RELATIVE_FNO");
        this.bytes = JDBCUtils.safeGetBigDecimal(dbResult, "BYTES");
        this.blocks = JDBCUtils.safeGetBigDecimal(dbResult, "BLOCKS");
        this.maxBytes = JDBCUtils.safeGetBigDecimal(dbResult, "MAXBYTES");
        this.maxBlocks = JDBCUtils.safeGetBigDecimal(dbResult, "MAXBLOCKS");
        this.incrementBy = JDBCUtils.safeGetLong(dbResult, "INCREMENT_BY");
        this.userBytes = JDBCUtils.safeGetBigDecimal(dbResult, "USER_BYTES");
        this.userBlocks = JDBCUtils.safeGetBigDecimal(dbResult, "USER_BLOCKS");
        this.autoExtensible = JDBCUtils.safeGetBoolean(dbResult, "AUTOEXTENSIBLE", OracleConstants.RESULT_YES_VALUE);
        this.available = "AVAILABLE".equals(JDBCUtils.safeGetStringTrimmed(dbResult, OracleConstants.COLUMN_STATUS));
        if (!this.temporary) {
            this.onlineStatus = CommonUtils.valueOf(OnlineStatus.class, JDBCUtils.safeGetStringTrimmed(dbResult, "ONLINE_STATUS"));
        }
    }

    // New created tablespace
    public OracleDataFile(@NotNull OracleTablespace tablespace, @NotNull String name) {
        super(tablespace, name, false);
        this.tablespace = tablespace;
        this.temporary = tablespace.getContents() == OracleTablespace.Contents.TEMPORARY;
        this.bytes = BigDecimal.valueOf(1000000); // Minimum value
    }

    public OracleTablespace getTablespace()
    {
        return tablespace;
    }

    /**
     * The file path as the server reports it: a path, not an SQL identifier, so it is never quoted (the delete
     * confirmation and error messages would show it as "/path").
     */
    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context)
    {
        return getName();
    }

    @Nullable
    public OracleTablespaceDDL.Space getNewSpace()
    {
        return newSpace;
    }

    /**
     * Sets the file name, size and autoextend of a new file.
     */
    public void setNewSpace(@NotNull OracleTablespaceDDL.Space newSpace)
    {
        this.newSpace = newSpace;
        setName(newSpace.file());
    }

    /**
     * After ADD (same session): takes the new file's catalog row, found by the entered name (the server's full path is
     * the entered name, or ends with a path separator and the entered name when a relative name was entered).
     */
    public void readCreated(@NotNull JDBCSession session) throws SQLException
    {
        String entered = newSpace == null ? getName() : newSpace.file();
        if (entered == null) {
            return;
        }
        try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM "
            + OracleUtils.getSysSchemaPrefix(getDataSource()) + (temporary ? "DBA_TEMP_FILES" : "DBA_DATA_FILES")
            + " WHERE TABLESPACE_NAME=? ORDER BY FILE_ID")) {
            dbStat.setString(1, tablespace.getName());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    String fileName = JDBCUtils.safeGetString(dbResult, "FILE_NAME");
                    if (fileName != null && (fileName.equals(entered)
                        || fileName.endsWith("/" + entered) || fileName.endsWith("\\" + entered))) {
                        // A file of the same name in another directory may be older: the last match wins, an exact one ends
                        setName(fileName);
                        loadRow(dbResult);
                        if (fileName.equals(entered)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, order = 1)
    public String getName()
    {
        return name;
    }

    @Property(order = 2)
    public long getId()
    {
        return id;
    }

    @Property(order = 3)
    public long getRelativeNo()
    {
        return relativeNo;
    }

    @Property(viewable = true, editable = true, order = 4)
    public BigDecimal getBytes()
    {
        return bytes;
    }

    public void setBytes(BigDecimal bytes) {
        this.bytes = bytes;
    }

    @Property(viewable = true, order = 5)
    public BigDecimal getBlocks()
    {
        return blocks;
    }

    @Property(viewable = true, editable = true, order = 6)
    public BigDecimal getMaxBytes()
    {
        return maxBytes;
    }

    public void setMaxBytes(BigDecimal maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Property(viewable = true, order = 7)
    public BigDecimal getMaxBlocks()
    {
        return maxBlocks;
    }

    @Property(viewable = true, order = 8)
    public long getIncrementBy()
    {
        return incrementBy;
    }

    @Property(viewable = true, order = 9)
    public BigDecimal getUserBytes()
    {
        return userBytes;
    }

    @Property(viewable = true, order = 10)
    public BigDecimal getUserBlocks()
    {
        return userBlocks;
    }

    @Property(viewable = true, order = 11)
    public boolean isAvailable()
    {
        return available;
    }

    @Property(viewable = true, editable = true, order = 12)
    public boolean isAutoExtensible()
    {
        return autoExtensible;
    }

    public void setAutoExtensible(boolean autoExtensible) {
        this.autoExtensible = autoExtensible;
    }

    @Property(viewable = true, order = 13)
    public OnlineStatus getOnlineStatus()
    {
        return onlineStatus;
    }

    @Property(viewable = true, order = 14)
    public boolean isTemporary()
    {
        return temporary;
    }

}
