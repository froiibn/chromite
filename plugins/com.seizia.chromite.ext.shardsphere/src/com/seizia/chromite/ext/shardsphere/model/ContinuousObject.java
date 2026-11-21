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

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.extension.AltibaseExecutionSettings;
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A continuous table, continuous stream table or continuous query ({@code SYSTEM_.SYS_CONTINUOUS_TABLES_}) of a schema.
 *
 * 설계: 세 종류가 같은 사전 행을 갖는다(실행 그룹·순서, 켜짐, 실행 문장, 정의 문장의 번호). 그래서 한 클래스로 두고 종류는
 * {@code SYS_TABLES_.TABLE_TYPE}으로 가른다: {@code B}·{@code C} 연속 테이블({@code B}는 집계 저장 테이블 {@code <t>$AGGR}이 있음),
 * {@code Y} 연속 스트림 테이블, {@code I} 연속 쿼리. 윈도·저장 테이블·TTL은 쿼리에 없으므로 쿼리에서는 속성을 숨긴다.
 * <ul>
 *     <li>정의(DDL 보기): {@code EXPORT_CONTINUOUS_OBJECT(VIEW_ID)}. 사전의 {@code VIEW_ID}는 테이블이면 정의를 담은 내부 객체
 *     {@code <t>$TEXT}, 쿼리면 쿼리 자신이다. 테이블 번호({@code TABLE_ID})를 넘기면 테이블에는 오류(201859)다(2026-11-21 확인).
 *     함수가 실패하면 테이블은 {@code SYS_VIEW_PARSE_}(같은 {@code VIEW_ID})의 글을 쓰고, 그것도 없으면 정의가 없다는 줄 하나를 보인다
 *     (실행 문장은 속성에 있다).</li>
 *     <li>윈도 단위 {@code WINDOW_UNIT}: S 초, M 분, H 시간, D 일, W 주, O 월(시험용 개체로 모두 확인, YEAR는 서버가 거절). 스텝 단위
 *     {@code WINDOW_STEP_UNIT}은 서버가 정한다: 본 값 S, M, H와 R(매뉴얼: 마이크로초, 30초 윈도에서 200000). 모르는 코드는 수와 코드
 *     그대로.</li>
 *     <li>TTL은 집계 저장 테이블이 있으면 그 테이블, 없으면 자기 행에 있다(2026-11-21 확인). 단위 이름은 테이블 TTL과 같다
 *     ({@link ShardTableProperties#ttlPeriod}).</li>
 *     <li>{@code IS_ENABLE}: T 켜짐, F 꺼짐(ENABLE·DISABLE로 만든 개체로 확인).</li>
 * </ul>
 * 사용 / 사용 안 함과 실행 설정(그룹, 순서, 연속 테이블의 TTL)은 Altibase 플러그인의 중립 능력 {@link AltibaseExecutionSettings}로 유지 관리
 * 명령에 준다(문장은 {@link StreamDDL}). TTL 단위는 그 연결의 테이블 TTL 능력({@link ShardTableTtl}, TTL 사전이 있을 때)의 것을 쓴다. 연속
 * 스트림 테이블과 연속 쿼리는 TTL을 받지 않는다(서버가 201849, 200705로 거절). 삭제는 이 번들의 객체 관리자, 만들기는 틀이다.
 * 노드를 다시 읽으면({@link #refreshObject}) 자기 사전 행을 다시 읽어 값을 바꾼다(목록의 캐시가 같은 객체를 가지므로 새 객체를 만들지
 * 않음). 행이 없으면(다른 곳에서 지움) 노드가 빠진다.
 * 스키마의 개체 목록(자동 완성, 이름 찾기)에는 테이블 종류만 열이 있는 {@link StreamRelation}으로 낸다({@link #getRelation}). 연속 쿼리는
 * 서버가 SELECT를 거절하므로(200753) 내지 않는다.
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #153, docs/chromite/shardsphere.md "스트림 탐색기")
 * - 2026-11-22 사용 / 사용 안 함, 실행 설정, 자기 행 다시 읽기(앱 변경 #156, docs/chromite/shardsphere.md "스트림 명령")
 * - 2026-11-22 스키마의 개체 목록에 낼 객체({@link #getRelation}, 쿼리는 없음)(앱 변경 #163, docs/chromite/shardsphere.md "자동 완성의 스트림 객체")
 */
public class ContinuousObject implements DBSObject, DBPScriptObject, DBPQualifiedObject, DBPRefreshableObject, AltibaseExecutionSettings {

    private static final Log log = Log.getLog(ContinuousObject.class);

    private static final String TYPE_AGGREGATE_TABLE = "B"; //$NON-NLS-1$
    private static final String TYPE_TABLE = "C"; //$NON-NLS-1$
    private static final String TYPE_STREAM_TABLE = "Y"; //$NON-NLS-1$
    private static final String TYPE_QUERY = "I"; //$NON-NLS-1$
    private static final String ENABLED = "T"; //$NON-NLS-1$
    // WINDOW_STEP_UNIT for microseconds (the stream manual)
    private static final String UNIT_MICROSECOND = "R"; //$NON-NLS-1$

    /**
     * The definition the server exports for this object (the dictionary's VIEW_ID; a host variable as the argument of a
     * function in the select list is refused by this family of servers, so the id is read in the same query).
     */
    private static final String QUERY_EXPORT = "SELECT EXPORT_CONTINUOUS_OBJECT(C.VIEW_ID) FROM SYSTEM_.SYS_CONTINUOUS_TABLES_ C"
        + " WHERE C.TABLE_ID = ?";
    private static final String QUERY_PARSE = "SELECT PARSE FROM SYSTEM_.SYS_VIEW_PARSE_ WHERE VIEW_ID = ? ORDER BY SEQ_NO";

    @NotNull
    private final StreamSchema parent;
    @NotNull
    private final String name;
    private final int tableId;
    // The values below are read again by refreshObject
    @Nullable
    private String tableType;
    private int viewId;
    @Nullable
    private String storageTable;
    @Nullable
    private String windowUnit;
    private int windowTime;
    @Nullable
    private String windowStepUnit;
    private int windowStepTime;
    private int execGroup;
    private int execOrder;
    private boolean enabled;
    @NotNull
    private String statements = "";
    @Nullable
    private String ttlUnit;
    @Nullable
    private Integer ttlTime;
    // This table in the schema's object list: made on first use, none for a query
    @Nullable
    private volatile StreamRelation relation;

    ContinuousObject(@NotNull StreamSchema parent, @NotNull ResultSet dbResult) {
        this.parent = parent;
        this.name = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "TABLE_NAME"));
        this.tableId = JDBCUtils.safeGetInt(dbResult, "TABLE_ID");
        read(dbResult);
    }

    private void read(@NotNull ResultSet dbResult) {
        this.tableType = JDBCUtils.safeGetStringTrimmed(dbResult, "TABLE_TYPE");
        this.viewId = JDBCUtils.safeGetInt(dbResult, "VIEW_ID");
        this.storageTable = JDBCUtils.safeGetStringTrimmed(dbResult, "AGGR_TABLE_NAME");
        this.windowUnit = JDBCUtils.safeGetStringTrimmed(dbResult, "WINDOW_UNIT");
        this.windowTime = JDBCUtils.safeGetInt(dbResult, "WINDOW_TIME");
        this.windowStepUnit = JDBCUtils.safeGetStringTrimmed(dbResult, "WINDOW_STEP_UNIT");
        this.windowStepTime = JDBCUtils.safeGetInt(dbResult, "WINDOW_STEP_TIME");
        this.execGroup = JDBCUtils.safeGetInt(dbResult, "EXEC_GROUP");
        this.execOrder = JDBCUtils.safeGetInt(dbResult, "EXEC_ORDER");
        this.enabled = ENABLED.equals(JDBCUtils.safeGetStringTrimmed(dbResult, "IS_ENABLE"));
        StringJoiner joined = new StringJoiner("\n"); //$NON-NLS-1$
        for (String column : new String[] {"EXEC_QUERY1", "EXEC_QUERY2", "EXEC_QUERY3", "EXEC_QUERY4"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String statement = JDBCUtils.safeGetStringTrimmed(dbResult, column);
            if (statement != null && !statement.isEmpty()) {
                joined.add(statement);
            }
        }
        this.statements = joined.toString();
        this.ttlUnit = JDBCUtils.safeGetStringTrimmed(dbResult, "TTL_UNIT");
        this.ttlTime = JDBCUtils.safeGetInteger(dbResult, "TTL_TIME");
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

    @Nullable
    @Property(viewable = true, order = 3)
    public String getKind() {
        if (tableType == null) {
            return null;
        }
        return switch (tableType) {
            case TYPE_AGGREGATE_TABLE, TYPE_TABLE -> ShardSphereMessages.stream_kind_continuous_table;
            case TYPE_STREAM_TABLE -> ShardSphereMessages.stream_kind_continuous_stream_table;
            case TYPE_QUERY -> ShardSphereMessages.stream_kind_continuous_query;
            default -> tableType;
        };
    }

    /**
     * The window length in words; empty without a window.
     */
    @Nullable
    @Property(viewable = true, order = 4, visibleIf = TableOnly.class)
    public String getWindow() {
        return windowUnit == null ? null : period(windowTime, windowUnit);
    }

    /**
     * The step the server chose for the window, in words.
     */
    @Nullable
    @Property(viewable = true, order = 5, visibleIf = TableOnly.class)
    public String getWindowStep() {
        return windowStepUnit == null ? null : period(windowStepTime, windowStepUnit);
    }

    @Nullable
    @Property(viewable = true, order = 6, visibleIf = TableOnly.class)
    public String getTtl() {
        return ttlUnit == null && ttlTime == null ? null : ShardTableProperties.ttlPeriod(ttlTime, ttlUnit);
    }

    /**
     * The table that holds an aggregating continuous table's rows ({@code <name>$AGGR}); its indexes go there.
     */
    @Nullable
    @Property(viewable = true, order = 7, visibleIf = TableOnly.class)
    public String getStorageTable() {
        return storageTable;
    }

    @Property(viewable = true, order = 8)
    public int getExecGroup() {
        return execGroup;
    }

    @Property(viewable = true, order = 9)
    public int getExecOrder() {
        return execOrder;
    }

    @Property(viewable = true, order = 10)
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The statements the server runs for this object ({@code EXEC_QUERY1}-{@code 4}), one per line.
     */
    @NotNull
    @Property(order = 11, length = PropertyLength.MULTILINE)
    public String getStatements() {
        return statements;
    }

    boolean isQuery() {
        return TYPE_QUERY.equals(tableType);
    }

    /**
     * This table as the schema's object list shows it: a view-like table with columns; only a continuous stream table
     * accepts inserted rows (the server refuses every write into a continuous table). Null for a continuous query, which
     * the server does not let a query read (SELECT 200753).
     */
    @Nullable
    StreamRelation getRelation() {
        if (isQuery()) {
            return null;
        }
        StreamRelation result = relation;
        if (result == null) {
            synchronized (this) {
                result = relation;
                if (result == null) {
                    result = new StreamRelation(parent.getSchema(), this, name, true, TYPE_STREAM_TABLE.equals(tableType));
                    relation = result;
                }
            }
        }
        return result;
    }

    /**
     * A continuous table with or without aggregation (not a continuous stream table or query): the kinds that take a TTL.
     */
    private boolean isTable() {
        return TYPE_AGGREGATE_TABLE.equals(tableType) || TYPE_TABLE.equals(tableType);
    }

    /**
     * The TTL capability of the connection where the server has the TTL dictionary (for its units), on continuous tables only.
     */
    @Nullable
    private AltibaseTableTtl getTtlCapability() {
        return isTable() ? parent.getServer().getAdapter(AltibaseTableTtl.class) : null;
    }

    @NotNull
    @Override
    public Settings getExecutionSettings() {
        AltibaseTableTtl ttl = getTtlCapability();
        AltibaseTableTtl.Unit unit = ttl == null || ttlUnit == null ? null : ttl.getUnit(ttlUnit);
        return new Settings(execGroup, execOrder, unit == null || ttlTime == null ? null : new Ttl(ttlTime, unit));
    }

    /**
     * The units of the table TTL; none where a TTL is set in a unit this connection does not know (it is not changed here).
     */
    @NotNull
    @Override
    public List<AltibaseTableTtl.Unit> getTtlUnits() {
        AltibaseTableTtl ttl = getTtlCapability();
        if (ttl == null || ttlUnit != null && ttl.getUnit(ttlUnit) == null) {
            return List.of();
        }
        return ttl.getUnits();
    }

    @Nullable
    @Override
    public AltibaseTableTtl.Unit getDefaultTtlUnit() {
        AltibaseTableTtl ttl = getTtlCapability();
        return ttl == null ? null : ttl.getDefaultUnit();
    }

    @NotNull
    @Override
    public String getEnableStatement(boolean enable) {
        return StreamDDL.enable(this, enable);
    }

    @NotNull
    @Override
    public List<String> getSettingsStatements(@NotNull Settings settings) {
        return StreamDDL.changeSettings(this, getExecutionSettings(), settings);
    }

    /**
     * Reads this object's dictionary row again; null if it is gone (the node is then removed).
     */
    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read continuous object '" + name + "' again");
             JDBCPreparedStatement dbStat = StreamSchema.prepareContinuous(session, parent, tableId);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            if (!dbResult.next()) {
                return null;
            }
            read(dbResult);
            return this;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, getDataSource());
        }
    }

    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read definition of continuous object '" + name + "'")) {
            String definition = readExport(session);
            if (definition == null && !isQuery()) {
                definition = readParse(session);
            }
            return definition != null ? definition : ShardSphereMessages.stream_definition_unavailable;
        }
    }

    @Nullable
    private String readExport(@NotNull JDBCSession session) {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_EXPORT)) {
            dbStat.setInt(1, tableId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                String text = dbResult.next() ? dbResult.getString(1) : null;
                return text == null || text.isBlank() ? null : text;
            }
        } catch (SQLException e) {
            log.debug("Can't export continuous object: " + e.getErrorCode() + " " + e.getSQLState());
            return null;
        }
    }

    /**
     * The creating statement the dictionary keeps for a continuous table, in pieces of up to 100 characters.
     */
    @Nullable
    private String readParse(@NotNull JDBCSession session) {
        StringBuilder text = new StringBuilder();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(QUERY_PARSE)) {
            dbStat.setInt(1, viewId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    String piece = dbResult.getString(1);
                    if (piece != null) {
                        text.append(piece);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Can't read continuous table text: " + e.getErrorCode() + " " + e.getSQLState());
            return null;
        }
        return text.isEmpty() ? null : text.toString();
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

    /**
     * A window or step amount with its unit in words: the units of {@code SYS_TTL_} plus R (microseconds).
     */
    @Nullable
    private static String period(int time, @NotNull String unit) {
        if (UNIT_MICROSECOND.equals(unit)) {
            return NLS.bind(ShardSphereMessages.stream_unit_microsecond, time);
        }
        return ShardTableProperties.ttlPeriod(time, unit);
    }

    /**
     * Shows a property on continuous tables only: a continuous query has no window, storage table or TTL.
     */
    public static class TableOnly implements IPropertyValueValidator<ContinuousObject, Object> {
        @Override
        public boolean isValidValue(@NotNull ContinuousObject object, @Nullable Object value) {
            return !object.isQuery();
        }
    }

}
