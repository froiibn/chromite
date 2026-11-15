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
package com.seizia.chromite.ext.altibase.model;

import com.seizia.chromite.ext.altibase.model.AltibaseTablespace.State;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.ByteNumberFormat;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A data or temp file of a disk tablespace (V$DATAFILES; sizes in pages, shown in bytes).
 *
 * 변경 이력:
 * - 2026-11-15 새 파일 객체(Add Data File의 파일 이름·크기·자동 확장, {@link #getInitialSpace})와 ADD 직후 자기 행 읽기
 *   ({@link #readCreated}) (앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리")
 */
public class AltibaseDataFile4Disk extends AltibaseDataFile {

    private BigInteger currSize;
    private BigInteger nextSize;
    private BigInteger initSize;
    private BigInteger maxSize;
    private boolean isAutoExtend;
    private State state;

    // The size and autoextend of a new file (ADD only)
    @Nullable
    private AltibaseTablespaceDDL.Space initialSpace;

    protected AltibaseDataFile4Disk(AltibaseTablespace tablespace, ResultSet dbResult) {
        super(tablespace, dbResult);
        loadSizes(dbResult);
    }

    /**
     * A new file of a disk data or temporary tablespace, not yet added.
     */
    public AltibaseDataFile4Disk(@NotNull AltibaseTablespace tablespace) {
        super(tablespace);
        this.currSize = BigInteger.ZERO;
        this.nextSize = BigInteger.ZERO;
        this.initSize = BigInteger.ZERO;
        this.maxSize = BigInteger.ZERO;
        this.state = State.UNKNOWN;
    }

    @Nullable
    public AltibaseTablespaceDDL.Space getInitialSpace() {
        return initialSpace;
    }

    /**
     * Sets the file name, size and autoextend of a new file.
     */
    public void setInitialSpace(@NotNull AltibaseTablespaceDDL.Space initialSpace) {
        this.initialSpace = initialSpace;
        setName(initialSpace.file());
    }

    /**
     * After ADD DATAFILE / TEMPFILE (same session): takes the newest file row of the tablespace, so the file has the
     * server's full path, ID and sizes.
     */
    public void readCreated(@NotNull JDBCSession session) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT * FROM V$DATAFILES WHERE SPACEID = ? AND ID = (SELECT MAX(ID) FROM V$DATAFILES WHERE SPACEID = ?)")) {
            dbStat.setInt(1, getParentObject().getId());
            dbStat.setInt(2, getParentObject().getId());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (dbResult.next()) {
                    loadRow(dbResult);
                    loadSizes(dbResult);
                }
            }
        }
    }

    private void loadSizes(@NotNull ResultSet dbResult) {
        this.currSize = new BigInteger(JDBCUtils.safeGetString(dbResult, "CURRSIZE"));
        this.nextSize = new BigInteger(JDBCUtils.safeGetString(dbResult, "NEXTSIZE"));
        this.initSize = new BigInteger(JDBCUtils.safeGetString(dbResult, "INITSIZE"));
        this.maxSize  = new BigInteger(JDBCUtils.safeGetString(dbResult, "MAXSIZE"));
        this.isAutoExtend = (JDBCUtils.safeGetInt(dbResult, "AUTOEXTEND") == 1);
        this.state = State.getStateByIdx(JDBCUtils.safeGetInt(dbResult, "STATE"));
    }

    
    @Property(viewable = true, order = 4, formatter = ByteNumberFormat.class)
    public BigInteger getCurrSize() {
        return currSize.multiply(new BigInteger(this.getParentObject().getPageSizeInBytesStr()));
    }
    
    @Property(viewable = true, order = 5, formatter = ByteNumberFormat.class)
    public BigInteger getNextSize() {
        return nextSize.multiply(new BigInteger(this.getParentObject().getPageSizeInBytesStr()));
    }
    
    @Property(viewable = true, order = 6, formatter = ByteNumberFormat.class)
    public BigInteger getInitSize() {
        return initSize.multiply(new BigInteger(this.getParentObject().getPageSizeInBytesStr()));
    }
    
    @Property(viewable = true, order = 7, formatter = ByteNumberFormat.class)
    public BigInteger getMaxSize() {
        return maxSize.multiply(new BigInteger(this.getParentObject().getPageSizeInBytesStr()));
    }
    
    @Property(viewable = true, order = 8)
    public boolean getAutoExtend() {
        return this.isAutoExtend;
    }
    
    @Property(viewable = true, order = 10)
    public String getState() {
        return this.state.name();
    }
}