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
package com.seizia.chromite.ext.shardsphere.model;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Map;

/**
 * A stream table ({@code CREATE STREAM TABLE}, {@code SYS_TABLES_.TABLE_TYPE = 'X'}) in the Stream Tables folder of a schema.
 *
 * 설계: 일반 Tables 폴더(유형 T)에는 전부터 나오지 않았고, 거기에 넣으면 테이블 명령(삭제, 잘라내기, TTL, 디자인 …)이 따라오는데 스트림
 * 테이블에서 확인하지 않았다. 그래서 이 번들의 폴더에 읽기 전용으로 둔다. 열 수는 서버가 자동으로 더하는 {@code $INSERT_TIME}을 넣은
 * {@code SYS_TABLES_.COLUMN_COUNT} 그대로다. 정의는 {@code DBMS_METADATA.GET_DDL('TABLE', …)}이 {@code CREATE STREAM TABLE}로 돌려준다
 * (2026-11-21 확인, 자동 열은 들어 있지 않음). {@code EXPORT_CONTINUOUS_OBJECT}는 스트림 테이블에 오류(201859)다. 읽기 전용 연결에서는
 * 서버가 이 EXEC를 거절하므로(266307) DDL 보기는 정의가 없다는 줄 하나를 보인다(오류 창 대신).
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #153, docs/chromite/shardsphere.md "스트림 탐색기")
 */
public class StreamTable implements DBSObject, DBPScriptObject, DBPQualifiedObject {

    private static final Log log = Log.getLog(StreamTable.class);

    // The driver's CallableStatement through Chromite's wrapper fails on this driver (AltibaseMetaModel.getDDLFromDbmsMetadata)
    private static final String CALL_GET_DDL = "exec ? := dbms_metadata.get_ddl(?, ?, ?)";
    private static final String DDL_OBJECT_TYPE = "TABLE"; //$NON-NLS-1$

    @NotNull
    private final StreamSchema parent;
    @NotNull
    private final String name;
    private final int columnCount;
    @Nullable
    private final String tablespace;
    @Nullable
    private final Timestamp created;

    StreamTable(@NotNull StreamSchema parent, @NotNull ResultSet dbResult) {
        this.parent = parent;
        this.name = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "TABLE_NAME"));
        this.columnCount = JDBCUtils.safeGetInt(dbResult, "COLUMN_COUNT");
        this.tablespace = JDBCUtils.safeGetStringTrimmed(dbResult, "TBS_NAME");
        this.created = JDBCUtils.safeGetTimestamp(dbResult, "CREATED");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getOwner() {
        return parent.getSchema().getName();
    }

    /**
     * The columns with the server's own {@code $INSERT_TIME}.
     */
    @Property(viewable = true, order = 3)
    public int getColumnCount() {
        return columnCount;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getTablespace() {
        return tablespace;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public Timestamp getCreated() {
        return created;
    }

    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read DDL of stream table '" + name + "'");
             CallableStatement dbCall = session.getOriginal().prepareCall(CALL_GET_DDL)) {
            dbCall.registerOutParameter(1, Types.VARCHAR);
            dbCall.setString(2, DDL_OBJECT_TYPE);
            dbCall.setString(3, name);
            dbCall.setString(4, getOwner());
            dbCall.execute();
            String ddl = dbCall.getString(1);
            return ddl == null || ddl.isBlank() ? ShardSphereMessages.stream_definition_unavailable : ddl;
        } catch (SQLException e) {
            // A read-only connection refuses the EXEC (266307, 2026-11-21): the DDL view says so instead of an error
            log.debug("Can't read stream table DDL: " + e.getErrorCode() + " " + e.getSQLState());
            return ShardSphereMessages.stream_definition_unavailable;
        }
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), parent.getSchema(), this);
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public StreamSchema getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return parent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

}
