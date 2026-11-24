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
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.altibase.AltibaseUtils;
import com.seizia.chromite.ext.generic.GenericConstants;
import com.seizia.chromite.ext.generic.model.*;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaObject;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCQueryTransformProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformType;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCConstants;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.sql.QueryTransformerLimit;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.exec.SQLActualPlanSupport;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectFilter;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.utils.CommonUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Altibase DataSource
 *
 * 변경 이력:
 * - 2026-11-07 테이블 목록을 카탈로그 질의로 읽음({@link #prepareTableLoadStatement}): 테이블 저장 방식을 같은
 *   질의에서 받기 위해서다(docs/chromite/altibase.md)
 * - 2026-11-12 연결 전에 실제 계획 여부를 답함({@link SQLActualPlanSupport}): {@link AltibaseDataSource}는 늘 실제 계획 어댑터를 준다(앱 변경 #70)
 * - 2026-11-17 테이블 DDL의 주석: 서버의 DBMS_METADATA에 GET_DEPENDENT_DDL·SET_TRANSFORM_PARAM이 있을 때만 부른다
 *   ({@link AltibaseDataSource#hasDbmsMetadataSubprogram}, 연결마다 판단). ShardSphere 5.1에는 둘 다 없어 주석이 빠지고 경고가 남았다.
 *   GET_DEPENDENT_DDL이 없으면 같은 모양의 COMMENT 문을 카탈로그(SYSTEM_.SYS_COMMENTS_)에서 만든다({@link #getCommentDDLFromCatalog},
 *   앱 변경 #139, docs/chromite/altibase.md "ShardSphere에서 다른 문장")
 * - 2026-11-17 GET_DEPENDENT_DDL을 {@code SELECT ...(?, ?, ?) FROM DUAL}이 아니라 get_ddl처럼 {@code exec ? := ...}로 부름. Altibase 7.3은
 *   SELECT 목록의 함수 인자로 호스트 변수를 받지 않아(201275 "Invalid use of host variables") Altibase에서도 주석이 늘 빠졌다(같은 문서)
 * - 2026-11-17 {@link #getReplicationDDL}: DBMS_METADATA 객체 유형을 복제의 카탈로그에서 받고, 카탈로그가 유형을 모르면 부르지 않음
 *   (앱 변경 #139 작업 4, docs/chromite/altibase.md "복제 카탈로그")
 * - 2026-11-17 시퀀스 목록에 큐의 내부 시퀀스({@code <queue>_NEXT_MSG_ID})를 카탈로그로 가려 읽기 전용 {@link AltibaseQueueSequence}로
 *   (앱 변경 #139 수정 4, docs/chromite/altibase.md "큐의 내부 시퀀스")
 * - 2026-11-21 {@link #QUEUE_SEQUENCE_SUFFIX}를 열어 큐 관리자가 큐를 만들고 지운 뒤 시퀀스 목록을 맞춤(앱 변경 #154, docs/chromite/altibase.md "큐")
 * - 2026-11-22 작업 DDL(getJobDDL)을 지움: 작업이 정의를 카탈로그 행으로 만든다(앱 변경 #158, docs/chromite/altibase.md "작업 정의 보기")
 * - 2026-11-24 카탈로그에서 읽은 원본 앞의, 이 모델이 붙이는 주석 줄(경고, 패키지 머리·본문 이름)을 한 번만 보임({@link #withoutGeneratedHeader}).
 *   서버는 받은 글을 주석까지 원본으로 두어, DDL 보기 글을 실행할 때마다 그 줄이 한 벌씩 늘었다(앱 변경 182,
 *   docs/chromite/altibase.md "저장 루틴의 문장 나누기")
 * - 2026-11-24 생성한 스크립트의 행 수 제한({@link #createQueryTransformer}, {@code LIMIT n}): "상위 1000개 행 선택"이 제한 없는
 *   SELECT를 열었다(앱 변경 #183, docs/chromite/swing-explorer-object-actions.md "행 수 제한")
 * - 2026-11-25 카탈로그 원본(뷰, 구체화된 뷰, 루틴, 타입세트, 패키지, 트리거) 앞에 경고 줄을 붙이지 않음: 서버가 둔 원본 그대로이고,
 *   ShardSphere에는 패키지가 있다(GET_DDL이 그 종류를 거절). {@link #withoutGeneratedHeader}가 스크립트의 객체 이름 줄("-- 소유자.뷰 소스")도
 *   알아봄: DDL 보기 글을 실행하면 그 줄이 뷰 원본에 남아, 다시 열 때마다 한 벌씩 늘었다(docs/chromite/altibase.md "뷰 DDL의 경고 줄")
 * - 2026-11-25 인덱스 DDL: GET_DDL이 인덱스를 거절하는 서버(ShardSphere)에서 카탈로그로 문장을 만듦({@link #getIndexDDLFromCatalog}).
 *   테이블 DDL에 제약조건의 인덱스와 서버의 테이블 글에 이미 있는 인덱스를 다시 붙이지 않음(Altibase 7.3에서도 글을 실행하면 같은 이름의
 *   인덱스가 한 번 더 만들어져 실패했다), 테이블 글이 이미 ';'로 끝나면 더 붙이지 않음, GET_DDL의 거절(990001)이 아닌 SQLException을
 *   디버그 로그에 남김(docs/chromite/altibase.md "인덱스 DDL")
 */
public class AltibaseMetaModel extends GenericMetaModel implements SQLActualPlanSupport, DBCQueryTransformProvider {

    private static final Log log = Log.getLog(AltibaseMetaModel.class);

    // The name a queue's internal sequence has after the queue's name, and the sequence list's column that marks it
    public static final String QUEUE_SEQUENCE_SUFFIX = "_NEXT_MSG_ID";
    private static final String COLUMN_QUEUE_SEQUENCE = "QUEUE_SEQUENCE";

    // The dependent object type of DBMS_METADATA.GET_DEPENDENT_DDL for the comments of a table
    private static final String DEP_OBJECT_COMMENT = "COMMENT";

    // SYS_INDICES_.INDEX_TYPE and SYS_PART_INDICES_.PARTITION_TYPE values seen on Altibase 7.3 and ShardSphere 5.1
    // (CREATE INDEX with INDEXTYPE IS BTREE / RTREE, LOCAL): the only kinds the catalog index statement is built for
    private static final int INDEX_TYPE_BTREE = 1;
    private static final int INDEX_TYPE_RTREE = 6;
    private static final int INDEX_PARTITION_LOCAL = 0;

    // Before an index statement that is commented out because the catalog does not describe the index fully
    private static final String INDEX_NOT_DESCRIBED =
        "-- [WARNING] The catalog does not describe this index fully (%s), the statement below may not be correct.";

    // The warning line builds before 2026-11-25 put before every catalog source. The server keeps it in a source that was
    // run from such a DDL text, so it is still recognized there (withoutGeneratedHeader)
    private static final String LEGACY_NO_DBMS_METADATA =
        "-- [WARNING] Without DBMS_METADATA package, the generated DDL may not be correct.";

    public AltibaseMetaModel() {
    }

    @Override
    public boolean isActualPlanSupported(@NotNull DBPDataSourceContainer container) {
        return true;
    }

    /**
     * Only the row limit of a generated script ({@link DBCQueryTransformType#SCRIPT_ROW_LIMIT}), not the engine's
     * {@link DBCQueryTransformType#RESULT_SET_LIMIT}: the user's own queries keep their JDBC row limit.
     * {@code LIMIT n} after the whole statement (after ORDER BY / WHERE, before FOR UPDATE), checked on Altibase 7.3 and
     * ShardSphere 5.1 for tables, views, materialized views, synonyms, partitioned tables, queues and stream objects.
     * Total rows only: the servers count a LIMIT offset from 1 ({@code LIMIT 0, n} is error 200713) and refuse
     * {@code OFFSET}, {@code FETCH FIRST} and {@code TOP}.
     */
    @Nullable
    @Override
    public DBCQueryTransformer createQueryTransformer(@NotNull DBCQueryTransformType type) {
        if (type == DBCQueryTransformType.SCRIPT_ROW_LIMIT) {
            return new QueryTransformerLimit(false);
        }
        return null;
    }

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new AltibaseDataSource(monitor, container, this);
    }

    @Override
    public boolean isSystemSchema(GenericSchema schema) {
        return AltibaseConstants.USER_SYSTEM_.equals(schema.getName());
    }

    @Override
    public AltibaseSchema createSchemaImpl(@NotNull GenericDataSource dataSource, 
            @Nullable GenericCatalog catalog, @NotNull String schemaName) throws DBException {
        return new AltibaseSchema(dataSource, catalog, schemaName);
    }

    @Override
    public AltibaseDataTypeCache createDataTypeCache(@NotNull GenericStructContainer container) {
        return new AltibaseDataTypeCache(container);
    }

    /**
     * Reads the table list from the catalog instead of {@code DatabaseMetaData.getTables}, so the tablespace of each
     * table and its storage kind (V$TABLESPACES.TYPE: memory, disk or volatile) come in the same query and the
     * navigator needs no query per table ({@link AltibaseTable}).
     * The JDBC columns and table types are the ones of the Altibase JDBC driver's getTables. Synonyms, sequences and
     * the system objects other than tables and views, which the driver adds too, are left out because
     * {@link #createTableImpl} drops them anyway.
     * A partitioned table gets the lowest and highest type of its partitions' tablespaces (hybrid partitioned tables).
     */
    @Override
    public JDBCStatement prepareTableLoadStatement(
        @NotNull JDBCSession session,
        @NotNull GenericStructContainer owner,
        @Nullable GenericTableBase object,
        @Nullable String objectName
    ) throws SQLException {
        String tableName = object != null ? object.getName() : objectName;
        String tableMask = null;
        if (tableName == null) {
            DBSObjectFilter tableFilters = session.getDataSource().getContainer().getObjectFilter(GenericTable.class, owner, false);
            if (tableFilters != null && tableFilters.hasSingleMask() && !CommonUtils.isEmpty(tableFilters.getSingleMask())) {
                tableMask = SQLUtils.makeSQLLike(tableFilters.getSingleMask());
            }
        }
        String partitionTypes = "SELECT %s(pts.TYPE) FROM SYSTEM_.SYS_TABLE_PARTITIONS_ p"
            + " JOIN V$TABLESPACES pts ON pts.ID = p.TBS_ID WHERE p.TABLE_ID = t.TABLE_ID";
        StringBuilder sql = new StringBuilder()
            .append("SELECT (SELECT DB_NAME FROM V$DATABASE) AS ").append(JDBCConstants.TABLE_CAT)
            .append(", u.USER_NAME AS ").append(JDBCConstants.TABLE_SCHEM)
            .append(", t.TABLE_NAME AS ").append(JDBCConstants.TABLE_NAME)
            .append(", DECODE(u.USER_NAME, 'SYSTEM_', 'SYSTEM ', '') || DECODE(t.TABLE_TYPE, 'T', 'TABLE', 'V', 'VIEW',")
            .append(" 'Q', 'QUEUE', 'M', 'MATERIALIZED VIEW') AS ").append(JDBCConstants.TABLE_TYPE)
            .append(", c.COMMENTS AS ").append(JDBCConstants.REMARKS)
            .append(", t.TBS_NAME AS ").append(AltibaseTable.COLUMN_TBS_NAME)
            .append(", t.IS_PARTITIONED AS ").append(AltibaseTable.COLUMN_IS_PARTITIONED)
            .append(", ts.TYPE AS ").append(AltibaseTable.COLUMN_TBS_TYPE)
            .append(", CASE WHEN t.IS_PARTITIONED = 'T' THEN (").append(String.format(partitionTypes, "MIN"))
            .append(") END AS ").append(AltibaseTable.COLUMN_PARTITION_TBS_TYPE_MIN)
            .append(", CASE WHEN t.IS_PARTITIONED = 'T' THEN (").append(String.format(partitionTypes, "MAX"))
            .append(") END AS ").append(AltibaseTable.COLUMN_PARTITION_TBS_TYPE_MAX)
            .append(" FROM SYSTEM_.SYS_TABLES_ t")
            .append(" JOIN SYSTEM_.SYS_USERS_ u ON u.USER_ID = t.USER_ID")
            .append(" LEFT OUTER JOIN SYSTEM_.SYS_COMMENTS_ c ON c.USER_NAME = u.USER_NAME AND c.TABLE_NAME = t.TABLE_NAME")
            .append(" AND c.COLUMN_NAME IS NULL")
            .append(" LEFT OUTER JOIN V$TABLESPACES ts ON ts.ID = t.TBS_ID")
            .append(" WHERE u.USER_NAME = ?")
            .append(" AND (t.TABLE_TYPE IN ('T', 'V') OR (u.USER_NAME <> 'SYSTEM_' AND t.TABLE_TYPE IN ('M', 'Q')))");
        if (tableName != null) {
            sql.append(" AND t.TABLE_NAME = ?");
        } else if (tableMask != null) {
            sql.append(" AND t.TABLE_NAME LIKE ?");
        }
        JDBCPreparedStatement dbStat = session.prepareStatement(sql.toString());
        dbStat.setString(1, owner.getName());
        if (tableName != null) {
            dbStat.setString(2, tableName);
        } else if (tableMask != null) {
            dbStat.setString(2, tableMask);
        }
        return dbStat;
    }

    @Override
    public GenericTableBase createTableImpl(@NotNull JDBCSession session, @NotNull GenericStructContainer owner, 
            @NotNull GenericMetaObject tableObject, @NotNull JDBCResultSet dbResult) {
        String tableName = GenericUtils.safeGetStringTrimmed(tableObject, dbResult, JDBCConstants.TABLE_NAME);
        String tableType = GenericUtils.safeGetStringTrimmed(tableObject, dbResult, JDBCConstants.TABLE_TYPE);

        GenericTableBase table = super.createTableImpl(session, owner, tableObject, dbResult);

        if (table == null) {
            return null;
        }

        switch (tableType) {
            case "TABLE":
            case "SYSTEM TABLE":
                table = new AltibaseTable(owner, tableName, tableType, dbResult);
                break;
            case "VIEW":
            case "SYSTEM VIEW":
                table = new AltibaseView(session, owner, tableName, tableType, dbResult);
                break;
            case "MATERIALIZED VIEW":
                table = new AltibaseMaterializedView(session, owner, tableName, tableType, dbResult);
                break;
            case "QUEUE":
                table = new AltibaseQueue(owner, tableName, tableType, dbResult);
                break;
            case "SYNONYM":
            case "SEQUENCE":
            default:
                table = null;
        }

        return table;
    }

    @NotNull
    @Override
    public GenericTableBase createTableOrViewImpl(
        @NotNull GenericStructContainer container,
        @Nullable String tableName,
        @Nullable String tableType,
        @Nullable JDBCResultSet dbResult) {
        if (tableType != null && isView(tableType)) {

            if (tableType.equalsIgnoreCase(AltibaseConstants.OBJ_TYPE_MATERIALIZED_VIEW)) {
                return new AltibaseMaterializedView(
                        null,
                        container,
                        tableName,
                        tableType,
                        dbResult);
            } else {
                return new AltibaseView(
                        null,
                        container,
                        tableName,
                        tableType,
                        dbResult);
            }
        }

        return new AltibaseTable(
                container,
                tableName,
                tableType,
                dbResult);
    }

    /**
     * Get a specific Index DDL: the server's (DBMS_METADATA.GET_DDL), else the statement built from the catalog
     * ({@link #getIndexDDLFromCatalog}; ShardSphere's GET_DDL refuses indexes) after the warning that the model built it.
     */
    public String getIndexDDL(DBRProgressMonitor monitor, AltibaseTableIndex sourceObject, 
            Map<String, Object> options) throws DBException {
        StringBuilder ddl = new StringBuilder();
        String schemaName = sourceObject.getTable().getSchema().getName();
        String ddlFromMetadata;
        
        ddlFromMetadata = getDDLFromDbmsMetadata(monitor, sourceObject, schemaName, AltibaseConstants.DBOBJ_INDEX);
        if (!CommonUtils.isEmpty(ddlFromMetadata)) {
            ddlFromMetadata = terminated(ddlFromMetadata);
        } else {
            String built = getIndexDDLFromCatalog(monitor, sourceObject, schemaName);
            if (built != null) {
                ddlFromMetadata = AltibaseConstants.NO_DBMS_METADATA + built;
            }
        }
        
        if (CommonUtils.isEmpty(ddlFromMetadata)) {
            ddl.append(String.format(AltibaseConstants.NO_DDL_WITHOUT_DBMS_METADATA, AltibaseConstants.DBOBJ_INDEX));
        } else {
            if (sourceObject.isSystemGenerated()) {
                ddl.append("-- System generated index. Not for user creation.")
                .append(AltibaseConstants.NEW_LINE).append("/*").append(AltibaseConstants.NEW_LINE);
            }
            
            ddl.append(ddlFromMetadata);
            
            if (sourceObject.isSystemGenerated()) {
                ddl.append(AltibaseConstants.NEW_LINE).append("*/");
            }
        }
        
        return ddl.toString();
    }
    
    @Override
    public String getTableDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase sourceObject,
                              @NotNull Map<String, Object> options) throws DBException {
        StringBuilder ddl = new StringBuilder();
        String schemaName = sourceObject.getContainer().getName();
        String ddlFromMetadata;

        ddlFromMetadata = getDDLFromDbmsMetadata(monitor, sourceObject, schemaName, sourceObject.getTableType());
        
        if (CommonUtils.isEmpty(ddlFromMetadata)) {
            ddl.append(AltibaseConstants.NO_DBMS_METADATA).append(super.getTableDDL(monitor, sourceObject, options));
        } else {
            // ShardSphere's text ends with ';' already, Altibase 7.3's does not
            ddl.append(terminated(ddlFromMetadata)).append(AltibaseConstants.NEW_LINE);

            // Comment
            addTableDependentDdl(ddl, DEP_OBJECT_COMMENT, monitor, sourceObject, schemaName);

            // Dependent index, but not the index of a constraint (the table text has the constraint, which makes its
            // index) and not one the server's table text creates already (ShardSphere's text has every index):
            // either would be a second object of the same name when the text is run
            Set<String> constraintIndexes = getConstraintIndexNames(monitor, sourceObject, schemaName);
            for (GenericTableIndex index : sourceObject.getIndexes(monitor)) {
                AltibaseTableIndex altiIndex = (AltibaseTableIndex) index;
                if (!altiIndex.isSystemGenerated() && !constraintIndexes.contains(altiIndex.getName())
                        && !createsIndex(ddlFromMetadata, altiIndex.getName())) {
                    ddl.append(AltibaseConstants.NEW_LINE)
                        .append(altiIndex.getObjectDefinitionText(monitor, options))
                        .append(AltibaseConstants.NEW_LINE);
                }
            }
        }

        return ddl.toString();
    }
    
    /**
     * Get dependent objects of a table to table DDL, such as comment. 
     */
    private void addTableDependentDdl(StringBuilder ddl, String depObjType, 
            DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName) {
        String depDdl = null;
        
        depDdl = getDepDDLFromDbmsMetadata(monitor, sourceObject, schemaName, depObjType);
        depDdl = depDdl.replaceAll("\\n\\n", AltibaseConstants.NEW_LINE); // remove empty line
        if (depDdl != null && depDdl.length() > 0) {
            ddl.append(AltibaseConstants.NEW_LINE).append(depDdl).append(AltibaseConstants.NEW_LINE);
        }
    }

    /**
     * Get a specific Synonym DDL
     */
    public String getSynonymDDL(DBRProgressMonitor monitor, AltibaseSynonym sourceObject, 
            Map<String, Object> options) throws DBException {
        return getDDLFromDbmsMetadata(monitor, sourceObject, (String) options.get("SCHEMA"), "SYNONYM");
    }

    /**
     * Get a specific Replication DDL. Empty where the replication's catalog names no DBMS_METADATA object type (the call
     * is not known to work there and is not sent).
     */
    public String getReplicationDDL(@NotNull DBRProgressMonitor monitor, @NotNull  AltibaseReplication sourceObject,
            @NotNull Map<String, Object> options) throws DBException {
        String objectType = sourceObject.getReplicationCatalog().getDdlObjectType();
        if (objectType == null) {
            return "";
        }
        return getDDLFromDbmsMetadata(monitor, sourceObject, null, objectType);
    }
    
    /**
     * Get a specific DbLink DDL
     */
    public String getDbLinkDDL(DBRProgressMonitor monitor, AltibaseDbLink sourceObject, 
            Map<String, Object> options) throws DBException {
        return getDDLFromDbmsMetadata(monitor, sourceObject, (String) options.get("SCHEMA"), "DB_LINK");
    }
    
    /**
     * Get a specific Library DDL
     */
    public String getLibraryDDL(DBRProgressMonitor monitor, AltibaseLibrary sourceObject, 
            Map<String, Object> options) throws DBException {
        return getDDLFromDbmsMetadata(monitor, sourceObject, null, "LIBRARY");
    }
    
    /**
     * Get a specific Directory DDL
     */
    public String getDirectoryDDL(DBRProgressMonitor monitor, AltibaseDirectory sourceObject, 
            Map<String, Object> options) throws DBException {
        return getDDLFromDbmsMetadata(monitor, sourceObject, null, "DIRECTORY");
    }
    
    @Override
    public String getViewDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericView sourceObject,
                             @NotNull Map<String, Object> options) throws DBException {
        
        String ddl = getDDLFromDbmsMetadata(monitor, sourceObject, sourceObject.getSchema().getName(), 
                AltibaseUtils.getDmbsMetaDataObjTypeName(sourceObject.getTableType()));
        
        if (CommonUtils.isEmpty(ddl)) {
            String sql;
            if (sourceObject instanceof AltibaseMaterializedView) {
                sql = "SELECT"
                        + " parse"
                        + " FROM"
                            + " SYSTEM_.SYS_VIEW_PARSE_ VP"
                            + " ,SYSTEM_.SYS_USERS_ U"
                            + " ,SYSTEM_.SYS_MATERIALIZED_VIEWS_ MV"
                        + " WHERE"
                            + " U.USER_NAME = ?"
                            + " AND MV.MVIEW_NAME = ?"
                            + " AND VP.USER_ID = U.USER_ID"
                            + " AND VP.VIEW_ID = MV.VIEW_ID"
                        + " ORDER BY"
                            + " SEQ_NO ASC";
            } else {
                sql = "SELECT "
                        + " parse "
                    + " FROM "
                        + " SYSTEM_.SYS_VIEW_PARSE_ VP, SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_TABLES_ T"
                    + " WHERE"
                        + " U.USER_NAME = ?"
                        + " AND T.TABLE_NAME = ?"
                        + " AND T.TABLE_TYPE = 'V'"
                        + " AND VP.USER_ID = U.USER_ID"
                        + " AND VP.VIEW_ID = T.TABLE_ID"
                    + " ORDER BY SEQ_NO ASC";
            }

            ddl = getViewProcDDLFromCatalog(monitor, sourceObject, sourceObject.getSchema().getName(), sql);
        }
        
        return (ddl.length() < 1) ? "-- View definition not available" : ddl;
    }

    @Override
    public String getProcedureDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericProcedure sourceObject) throws DBException {
        String ddl = getDDLFromDbmsMetadata(monitor, sourceObject, 
                    sourceObject.getSchema().getName(), ((AltibaseProcedureStandAlone) sourceObject).getProcedureTypeName());
        
        if (CommonUtils.isEmpty(ddl)) {
            String sql = "SELECT "
                    + " parse "
                    + " FROM "
                    + " SYSTEM_.SYS_PROC_PARSE_ PP, SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_PROCEDURES_ P"
                    + " WHERE"
                    + " U.USER_NAME = ?"
                    + " AND P.PROC_NAME = ?"
                    + " AND PP.USER_ID = U.USER_ID"
                    + " AND PP.PROC_OID = P.PROC_OID"
                    + " ORDER BY SEQ_NO ASC";
            ddl = getViewProcDDLFromCatalog(monitor, sourceObject, sourceObject.getSchema().getName(), sql);
        }

        return ddl;
    }

    /**
     * Get a specific Package DDL
     */
    public String getPackageDDL(DBRProgressMonitor monitor, AltibasePackage sourceObject, int packageType) throws DBException {
        boolean hasDbmsMetadata;
        String ddl = getDDLFromDbmsMetadata(monitor, sourceObject, sourceObject.getSchema().getName(), 
                    (packageType == AltibaseConstants.PACKAGE_TYPE_SPEC) ? "PACKAGE_SPEC" : "PACKAGE_BODY");
        
        hasDbmsMetadata = ddl.startsWith("--");
        
        if (hasDbmsMetadata || CommonUtils.isEmpty(ddl)) {
            String sql = "SELECT "
                    + " parse "
                    + " FROM "
                    + " SYSTEM_.SYS_PACKAGE_PARSE_ PP, SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_PACKAGES_ P"
                    + " WHERE"
                    + " U.USER_NAME = ?"
                    + " AND P.PACKAGE_NAME = ?"
                    + " AND PP.USER_ID = U.USER_ID"
                    + " AND PP.PACKAGE_OID = P.PACKAGE_OID"
                    + " AND PP.PACKAGE_TYPE = P.PACKAGE_TYPE"
                    + " AND PP.PACKAGE_TYPE = " + packageType
                    + " ORDER BY PP.PACKAGE_TYPE, SEQ_NO ASC";

            ddl = getPackageDDLFromCatalog(monitor, sourceObject, sourceObject.getSchema().getName(), sql);
        }
        
        return ddl;
    }

    //////////////////////////////////////////////////////
    // Table Columns

    @Override
    public GenericTableColumn createTableColumnImpl(
        @NotNull DBRProgressMonitor monitor,
        @Nullable JDBCResultSet dbResult,
        @NotNull GenericTableBase table,
        String columnName,
        String typeName,
        int valueType,
        int sourceType,
        int ordinalPos,
        long columnSize,
        long charLength,
        Integer scale,
        Integer precision,
        int radix,
        boolean notNull,
        String remarks,
        String defaultValue,
        boolean autoIncrement,
        boolean autoGenerated
    ) {
        return new AltibaseTableColumn(table,
                columnName,
                typeName, valueType, sourceType, ordinalPos,
                columnSize,
                charLength, scale, precision, radix, notNull,
                remarks, defaultValue, autoIncrement, autoGenerated
                );
    }

    //////////////////////////////////////////////////////
    // Sequences

    @Override
    public boolean supportsSequences(@NotNull GenericDataSource dataSource) {
        return true;
    }

    /**
     * The sequences of a schema, with whether each is the internal sequence of a queue of the same schema
     * ({@code <queue>_NEXT_MSG_ID}, decided from the catalog: a queue of that name exists). Altibase 7.3 stores that
     * sequence as {@code TABLE_TYPE = 'W'}; it is listed too, as a read-only {@link AltibaseQueueSequence}.
     */
    @Override
    public JDBCStatement prepareSequencesLoadStatement(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container) throws SQLException {
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + " TABLE_NAME, T.TABLE_TYPE, CURRENT_SEQ, START_SEQ, INCREMENT_SEQ, CACHE_SIZE, MAX_SEQ, MIN_SEQ, IS_CYCLE,"
                        + " CASE WHEN EXISTS (SELECT 1 FROM SYSTEM_.SYS_TABLES_ Q WHERE Q.USER_ID = T.USER_ID"
                        + " AND Q.TABLE_TYPE = 'Q' AND Q.TABLE_NAME || '" + QUEUE_SEQUENCE_SUFFIX + "' = T.TABLE_NAME)"
                        + " THEN 'T' ELSE 'F' END AS " + COLUMN_QUEUE_SEQUENCE
                        + " FROM V$SEQ S, SYSTEM_.SYS_TABLES_ T, SYSTEM_.SYS_USERS_ U"
                        + " WHERE"
                        + " U.USER_NAME = ?"
                        + " AND U.USER_ID = T.USER_ID"
                        + " AND T.TABLE_OID = S.SEQ_OID"
                        + " AND T.TABLE_TYPE IN ('S', 'W')"
                        + " ORDER BY TABLE_NAME ASC");
        dbStat.setString(1, container.getName());
        return dbStat;
    }

    @Nullable
    @Override
    public GenericSequence createSequenceImpl(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container, @NotNull JDBCResultSet dbResult) {
        if (JDBCUtils.safeGetBoolean(dbResult, COLUMN_QUEUE_SEQUENCE, AltibaseConstants.RESULT_T_VALUE)) {
            return new AltibaseQueueSequence(container, dbResult);
        }
        // Another 'W' object than a queue's sequence is not a sequence the user created: not listed (as before)
        if (!"S".equals(JDBCUtils.safeGetStringTrimmed(dbResult, "TABLE_TYPE"))) {
            return null;
        }
        return new AltibaseSequence(container, dbResult);
    }

    //////////////////////////////////////////////////////
    // Synonyms

    @Override
    public boolean supportsSynonyms(@NotNull GenericDataSource dataSource) {
        return true;
    }

    @Override
    public JDBCStatement prepareSynonymsLoadStatement(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container) throws SQLException {
        boolean isPublic = container.getName().equalsIgnoreCase(AltibaseConstants.USER_PUBLIC);
        
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + " NVL2(SYNONYM_OWNER_ID, SYNONYM_OWNER_ID, -1) AS SYNONYM_OWNER_ID,"
                        + " SYNONYM_NAME, OBJECT_OWNER_NAME, OBJECT_NAME"
                        + " FROM"
                        + " SYSTEM_.SYS_SYNONYMS_ S " + ((isPublic) ? "" : ", SYSTEM_.SYS_USERS_ U")
                        + " WHERE"
                        + ((isPublic) ? 
                                " SYNONYM_OWNER_ID IS NULL" 
                                : 
                                " U.USER_NAME = ? AND U.USER_ID = S.SYNONYM_OWNER_ID")
                        + " ORDER BY SYNONYM_NAME");

        if (!isPublic) {
            dbStat.setString(1, container.getName());
        }

        return dbStat;
    }

    @Override
    public GenericSynonym createSynonymImpl(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container, @NotNull JDBCResultSet dbResult) throws DBException {
        return new AltibaseSynonym(container,
                JDBCUtils.safeGetInt(dbResult, "SYNONYM_OWNER_ID"),
                JDBCUtils.safeGetString(dbResult, "SYNONYM_NAME"),
                "",
                JDBCUtils.safeGetString(dbResult, "OBJECT_OWNER_NAME"),
                JDBCUtils.safeGetString(dbResult, "OBJECT_NAME"));
    }

    //////////////////////////////////////////////////////
    // Triggers
    
    @Override
    public boolean supportsTriggers(@NotNull GenericDataSource dataSource) {
        return true;
    }

    @NotNull
    @Override
    public JDBCStatement prepareTableTriggersLoadStatement(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container, @Nullable GenericTableBase table) throws SQLException {
        JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + "   TR.OWNER_SCHEMA"
                        + " , TR.OWNER_TABLE AS OWNER" // DBeaver dependent column alias
                        + " , TR.USER_NAME AS TRIGGER_SCHEMA"
                        + " , TR.TRIGGER_NAME"
                        + " , TR.IS_ENABLE"
                        + " , CASE2(TR.EVENT_TIME = 1, 'BEFORE', TR.EVENT_TIME = 2, 'AFTER'," 
                        + " TR.EVENT_TIME = 3, 'INSTEAD OF', 'Unknown') AS EVENT_TIME"
                        + " , CASE2(TR.EVENT_TYPE = 1, 'INSERT', TR.EVENT_TYPE = 2, 'DELETE'," 
                        + " TR.EVENT_TYPE = 4, 'UPDATE', 'Unknown') AS EVENT_TYPE "
                        + " , CASE2(TR.GRANULARITY = 1, 'FOR EACH ROW', TR.GRANULARITY = 12," 
                        + " 'TFOR EACH STATEMENT', 'Unknown') AS GRANULARITY"
                        + " , TR.UPDATE_COLUMN_CNT"
                        + " , TR.REF_ROW_CNT"
                        + " , CASE2(TDT.STMT_TYPE = 8, 'DELETE', TDT.STMT_TYPE = 19, 'INSERT'," 
                        + " TDT.STMT_TYPE = 33, 'UPDATE', "
                        + " TDT.STMT_TYPE IS NULL, NULL, 'Unknown') AS DML_STMT_TYPE"
                        + " , TDT.USER_NAME AS DMLTABLE_SCHEMA"
                        + " , TDT.TABLE_NAME AS DMLTABLE_NAME"
                        + " FROM "
                        + " (SELECT "
                        + " R.*"
                        + " ,U.USER_NAME AS OWNER_SCHEMA"
                        + " ,T.TABLE_NAME AS OWNER_TABLE"
                        + " FROM "
                        + " SYSTEM_.SYS_USERS_ U"
                        + " , SYSTEM_.SYS_TABLES_ T"
                        + " , SYSTEM_.SYS_TRIGGERS_ R "
                        + " WHERE "
                        + " U.USER_ID = T.USER_ID "
                        + " AND T.TABLE_ID = R.TABLE_ID "
                        + " AND U.USER_NAME = ?"
                        + " AND T.TABLE_NAME " + ((table == null) ? " IS NOT NULL" : "= ?") + ") TR "
                        /* left outer join: DML target table could be null */
                        + " LEFT OUTER JOIN "
                        + " (SELECT"
                        + " TRIGGER_OID"
                        + " , STMT_TYPE"
                        + " , U.USER_NAME"
                        + " , T.TABLE_NAME"
                        + " FROM "
                        + " SYSTEM_.SYS_USERS_ U"
                        + " , SYSTEM_.SYS_TABLES_ T"
                        + " , SYSTEM_.SYS_TRIGGER_DML_TABLES_"
                        + " WHERE"
                        + " DML_TABLE_ID = T.TABLE_ID"
                        + " AND T.USER_ID = U.USER_ID"
                        + " ) TDT"
                        + " ON TR.TRIGGER_OID = TDT.TRIGGER_OID");

        dbStat.setString(1, container.getName()); // user name

        if (table != null) {
            dbStat.setString(2, table.getName()); // table name
        }

        return dbStat;
    }

    @NotNull
    @Override
    public GenericTrigger createTableTriggerImpl(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer container, @NotNull GenericTableBase parent, 
            String triggerName, @NotNull JDBCResultSet dbResult) throws DBException {
        String newTriggerName = triggerName;
        
        if (CommonUtils.isEmpty(newTriggerName)) {
            newTriggerName = JDBCUtils.safeGetStringTrimmed(dbResult, "TRIGGER_NAME");
        }
        if (newTriggerName == null) {
            return null;
        }

        return new AltibaseTableTrigger(
                parent,
                newTriggerName,
                null, //description
                dbResult);
    }

    @Override
    public boolean supportsDatabaseTriggers(@NotNull GenericDataSource dataSource) {
        return true;
    }

    @Override
    public String getTriggerDDL(@NotNull DBRProgressMonitor monitor, 
            @NotNull GenericTrigger trigger) throws DBException {
        String ddl = null;
        String schemaName = null;

        if (trigger.getParentObject() instanceof GenericTable) {
            schemaName = trigger.getParentObject().getParentObject().getName();
        } else {
            schemaName = trigger.getParentObject().getName();
        }

        ddl = getDDLFromDbmsMetadata(monitor, trigger, schemaName, "TRIGGER");
        
        if (CommonUtils.isEmpty(ddl)) {
            String sql = "SELECT "
                    + " substring"
                    + " FROM"
                    + " system_.sys_triggers_ t"
                    + " , system_.sys_trigger_strings_ sts"
                    + " WHERE"
                    + " t.user_name = ?"
                    + " AND t.trigger_name = ?"
                    + " AND sts.trigger_oid = t.trigger_oid"
                    + " ORDER BY seqno";

            ddl = getTriggerDDLFromCatalog(monitor, trigger, schemaName, sql);
        }
        
        return ddl;
    }

    @Override
    public boolean isTableCommentEditable() {
        return true;
    }

    //////////////////////////////////////////////////////
    // Constraints

    @Override
    public JDBCStatement prepareUniqueConstraintsLoadStatement(@NotNull JDBCSession session, 
            @NotNull GenericStructContainer owner, @Nullable GenericTableBase forParent) throws SQLException {

        boolean hasParent = (forParent != null);
        StringBuilder qry = new StringBuilder("SELECT"
                + " (SELECT db_name FROM v$database) AS TABLE_CAT,"
                + " u.user_name AS TABLE_SCHEME,"
                + " t.table_name AS TABLE_NAME,"
                + " col.column_name AS COLUMN_NAME, "
                + " (ccol.constraint_col_order + 1) AS KEY_SEQ,"
                + " c.constraint_name AS PK_NAME, "
                + " c.constraint_type, "
                + " c.check_condition, "
                + " c.validated"
                + " FROM"
                + " system_.sys_users_ u, system_.sys_tables_ t, system_.sys_columns_ col,"
                + " system_.sys_constraints_ c, system_.sys_constraint_columns_ ccol"
                + " WHERE"
                + " u.user_name = ?"

                + " AND u.user_id = c.user_id"
                + " AND u.user_id = t.user_id"
                + " AND t.table_id = c.table_id"
                + " AND c.constraint_type != 0"
                + " AND c.constraint_id = ccol.constraint_id"
                + " AND ccol.column_id = col.column_id");
        
        if (hasParent) {
            qry.append(" AND t.table_name = ?");
        }

        final JDBCPreparedStatement dbStat = session.prepareStatement(qry.toString());

        dbStat.setString(1, owner.getName());
        
        if (hasParent) {
            dbStat.setString(2, forParent.getName());
        }
        
        return dbStat;
    }

    @NotNull
    @Override
    public GenericUniqueKey createConstraintImpl(
        @NotNull GenericTableBase table,
        String constraintName,
        DBSEntityConstraintType constraintType,
        JDBCResultSet dbResult,
        boolean persisted
    ) {
        String condition = "";
        boolean validated = true;
        if (dbResult != null) {
            condition = constraintType.equals(DBSEntityConstraintType.CHECK) ?
                    JDBCUtils.safeGetString(dbResult, "CHECK_CONDITION") : "";
            validated = "T".equals(JDBCUtils.safeGetString(dbResult, "VALIDATED"));
        }
        return new AltibaseConstraint(table, constraintName, null, constraintType, persisted, condition, validated);
    }

    @Override
    public DBSEntityConstraintType getUniqueConstraintType(@NotNull JDBCResultSet dbResult) throws DBException, SQLException {

        int constraintType = JDBCUtils.safeGetInt(dbResult, "CONSTRAINT_TYPE");

        switch (constraintType) {
            case 1:
                return DBSEntityConstraintType.NOT_NULL;
            case 2:
                return DBSEntityConstraintType.UNIQUE_KEY;
            case 3:
                return DBSEntityConstraintType.PRIMARY_KEY;
            case 7:
                return DBSEntityConstraintType.CHECK;
            case 5:
                return AltibaseConstraint.TIMESTAMP;
            case 6:
                return AltibaseConstraint.LOCAL_UNIQUE_KEY;
            /* Foreign key must be handled separately: c.constraint_type != 0 
            case 0:
                return DBSEntityConstraintType.FOREIGN_KEY;
            */

            default:
                String exMsg = String.format("Unknown constraint type [NAME] %s [TYPE] %d", 
                        JDBCUtils.safeGetString(dbResult, "PK_NAME"), constraintType);
                log.error(exMsg);
                throw new DBException(exMsg);
        }
    }

    @Override
    public boolean supportsUniqueKeys() {
        return true;
    }

    @Override
    public boolean supportsCheckConstraints() {
        return true;
    }

    //////////////////////////////////////////////////////
    // Packages

    /**
     * Get Packages name and package object
     */
    private void loadPackages(DBRProgressMonitor monitor, @NotNull GenericObjectContainer container, 
            JDBCSession session, Map<String, AltibasePackage> packageMap) throws SQLException, DBException {

        String schemaName = container.getName();

        // Load packages
        try (JDBCStatement dbStat = preparePackageLoadStatement(session, container)) {
            dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
            dbStat.executeStatement();
            JDBCResultSet dbResult = dbStat.getResultSet();
            if (dbResult != null) {
                try {
                    while (dbResult.next()) {
                        if (monitor.isCanceled()) {
                            break;
                        }

                        String pkgName = JDBCUtils.safeGetString(dbResult, "PACKAGE_NAME");
                        String key = schemaName + "." + pkgName;
                        AltibasePackage pkg = packageMap.get(key);

                        // new package
                        if (pkg == null) {
                            pkg = createPackageImpl(container, pkgName, dbResult);
                            if (pkg != null) {
                                container.addPackage(pkg);
                                packageMap.put(key, pkg);
                            } else {
                                log.warn("Fail to create Package: " + key);
                            }
                        } else {
                            // if body found,
                            if (JDBCUtils.safeGetInt(dbResult, "PACKAGE_TYPE") == AltibaseConstants.PACKAGE_TYPE_BODY) {
                                pkg.setBody(true);
                                pkg.setStatus(JDBCUtils.safeGetBoolean(dbResult, "STATUS", "0"));
                            } else {
                                // can't be here
                                throw new DBException("Duplicated package name found: " + key);
                            }
                        }
                    }
                } finally {
                    dbResult.close();
                }
            }
        }
    }

    /**
     * Get Package dependent procedure/function
     */
    private void loadPackageDepedentProcedures(DBRProgressMonitor monitor, @NotNull GenericObjectContainer container, 
            JDBCSession session, Map<String, AltibasePackage> packageMap) throws SQLException, DBException {

        String pkgSchema = container.getName();

        // Load packages
        try (JDBCStatement dbStat = prepareProcedurePackagedLoadStatement(session, container)) {
            dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
            dbStat.executeStatement();
            JDBCResultSet dbResult = dbStat.getResultSet();
            if (dbResult != null) {
                try {
                    while (dbResult.next()) {
                        if (monitor.isCanceled()) {
                            break;
                        }

                        String pkgName = JDBCUtils.safeGetString(dbResult, "PACKAGE_NAME");
                        String procName = JDBCUtils.safeGetString(dbResult, "SUB_PROC_NAME");
                        int subType = JDBCUtils.safeGetInt(dbResult, "SUB_TYPE");

                        if (CommonUtils.isEmpty(procName)) {
                            continue;
                        }

                        String key = pkgSchema + "." + pkgName;
                        AltibasePackage pkg = packageMap.get(key);

                        if (pkg != null) {
                            final AltibaseProcedurePackaged procedure = new AltibaseProcedurePackaged(
                                    container,
                                    pkgSchema,
                                    pkgName,
                                    procName,
                                    true, // No way to determine package-dependant procedure status from Altibase metadata
                                    (subType == 0) ? DBSProcedureType.PROCEDURE : DBSProcedureType.FUNCTION, 
                                    null);
                            
                            if (procedure != null) {
                                pkg.addProcedure(procedure);
                            }
                        }

                    }
                } finally {
                    dbResult.close();
                }
            }
        }
    }

    /**
     * Get Procedure/Function/Typeset
     * 
     * @throws DBException 
     */
    private void loadPSMs(DBRProgressMonitor monitor, @NotNull GenericObjectContainer container, 
            JDBCSession session) throws SQLException, DBException {

        String procName;
        boolean valid;
        int procType;
        GenericProcedure procedure;

        try (JDBCStatement dbStat = preparePsmLoadStatement(session, container)) {
            dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
            dbStat.executeStatement();

            JDBCResultSet dbResult = dbStat.getResultSet();
            try {
                while (dbResult.next()) {
                    if (monitor.isCanceled()) {
                        break;
                    }

                    procName = JDBCUtils.safeGetString(dbResult, "PROC_NAME");
                    valid = JDBCUtils.safeGetBoolean(dbResult, "STATUS", "0");
                    procType = JDBCUtils.safeGetInt(dbResult, "OBJECT_TYPE");

                    switch (procType) {
                    case AltibaseConstants.PSM_TYPE_PROCEDURE:
                        procedure = new AltibaseProcedureStandAlone(
                                container,
                                procName,
                                valid,
                                DBSProcedureType.PROCEDURE,
                                null);
                        break;
                    case AltibaseConstants.PSM_TYPE_FUNCTION:
                        procedure = new AltibaseProcedureStandAlone(
                                container,
                                procName,
                                valid,
                                DBSProcedureType.FUNCTION,
                                null);
                        break;
                    case AltibaseConstants.PSM_TYPE_TYPESET:
                        procedure = new AltibaseTypeset(container, procName, valid);
                        break;

                    default:
                        procedure = null;
                        // can't be here
                        log.error("Unknown PSM type found: " + procName + ": " + procType);
                        continue;
                    }

                    if (procedure != null) {
                        container.addProcedure(procedure);
                    }

                }
            } finally {
                dbResult.close();
            }
        } 
    }

    //////////////////////////////////////////////////////
    // Procedure load

    /**
     * Altibase JDBC getProcedures method returns procedures and functions together
     */
    public void loadProcedures(@NotNull DBRProgressMonitor monitor, @NotNull GenericObjectContainer container)
            throws DBException {
        // Key: schemaName.objName, Value: AltibasePackage
        Map<String, AltibasePackage> packageMap = new HashMap<String, AltibasePackage>();

        GenericDataSource dataSource = container.getDataSource();

        try (JDBCSession session = DBUtils.openMetaSession(monitor, container, "Load procedures")) {

            loadPackages(monitor, container, session, packageMap);

            loadPackageDepedentProcedures(monitor, container, session, packageMap); 

            loadPSMs(monitor, container, session);

        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    //////////////////////////////////////////////////////
    // Procedure/Function/Typeset
    
    /**
     * Statement to load packages
     */
    public JDBCStatement preparePsmLoadStatement(JDBCSession session, 
            GenericStructContainer container) throws SQLException {
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + " p.*"
                        + " FROM system_.sys_procedures_ p, system_.sys_users_ u"
                        + " WHERE"
                        + " u.user_name = ?"
                        + " and u.user_id = p.user_id"
                        + " ORDER BY proc_name ASC"); 
        dbStat.setString(1, container.getName());
        return dbStat;
    }
    
    //////////////////////////////////////////////////////
    // Packages
    
    /**
     * Statement to load packages
     */
    public JDBCStatement preparePackageLoadStatement(JDBCSession session, 
            GenericStructContainer container) throws SQLException {
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                /* Name, type ordering is required to package specification is prior to body. */
                "SELECT"
                        + " PACKAGE_NAME, PACKAGE_TYPE, AUTHID, STATUS"
                        + " FROM SYSTEM_.SYS_PACKAGES_ P, SYSTEM_.SYS_USERS_ U"
                        + " WHERE"
                        + " U.USER_NAME = ?"
                        + " AND U.USER_ID = P.USER_ID"
                        + " ORDER BY PACKAGE_NAME, PACKAGE_TYPE ASC"); 
        dbStat.setString(1, container.getName());
        return dbStat;
    }

    /**
     * Create Package implementation
     */
    public AltibasePackage createPackageImpl(GenericStructContainer container, 
            String packageName, JDBCResultSet resultSet) {
        return new AltibasePackage(container, packageName, resultSet);
    }

    /**
     * Statement to load package dependent procedure/function
     */
    public JDBCStatement prepareProcedurePackagedLoadStatement(JDBCSession session, 
            GenericStructContainer container) throws SQLException {
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + " PP.PACKAGE_NAME"
                        + ", PP.OBJECT_NAME AS SUB_PROC_NAME"
                        + ", PP.SUB_TYPE" /* SUB_TYPE 0: procedure, 1: function */
                        + " FROM SYSTEM_.SYS_PACKAGES_ P, SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_PACKAGE_PARAS_ PP"
                        + " WHERE"
                        + " U.USER_NAME = ?"
                        + " AND U.USER_ID = P.USER_ID"
                        + " AND P.PACKAGE_OID = PP.PACKAGE_OID"
                        + " AND ( PARA_ORDER = 1 OR PARA_ORDER = 0 )"); /* 0 is if no parameter, 1 is the first parameter if any */
        dbStat.setString(1, container.getName());
        return dbStat;
    }

    /**
     * Statement to load package dependent procedure/function columns
     */
    public JDBCPreparedStatement prepareProcedurePackagedColumnLoadStatement(JDBCSession session, 
            String pkgSchema, String pkgName, String procName) throws SQLException {
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                        + " PP.*, D.TYPE_NAME"
                        + " FROM SYSTEM_.SYS_USERS_ U, SYSTEM_.SYS_PACKAGE_PARAS_ PP, V$DATATYPE D"
                        + " WHERE"
                        + " U.USER_NAME = ?"
                        + " AND PP.PACKAGE_NAME = ?"
                        + " AND PP.OBJECT_NAME = ?"
                        + " AND U.USER_ID = PP.USER_ID"
                        + " AND PP.DATA_TYPE = D.DATA_TYPE"
                        + " ORDER BY PARA_ORDER ASC");
        dbStat.setString(1, pkgSchema);
        dbStat.setString(2, pkgName);
        dbStat.setString(3, procName);
        return dbStat;
    }

    /**
     * Get DDL source for View/Procedure/Function/Typeset
     */
    private String getViewProcDDLFromCatalog(DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName, String sql) {
        return geDDLFromCatalog(monitor, sourceObject, schemaName, sql, "PARSE");
    }
    
    /**
     * Get DDL source for Package
     */
    private String getPackageDDLFromCatalog(DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName, String sql) {
        return geDDLFromCatalog(monitor, sourceObject, schemaName, sql, "PARSE");
    }

    /**
     * Get DDL source for Trigger
     */
    private String getTriggerDDLFromCatalog(DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName, String sql) {
        return geDDLFromCatalog(monitor, sourceObject, schemaName, sql, "SUBSTRING");
    }

    /**
     * Get DDL source from DBMS catalog (meta tables): View/Procedure/Function/Typeset/Trigger.
     * No warning line before it: the text is the statement the server keeps as the object's source, not a DDL this model
     * generates (the warning said "may not be correct" of the server's own text, and ShardSphere has the package).
     */
    private String geDDLFromCatalog(DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName, String sql, 
            String colname) {
        StringBuilder source = new StringBuilder();
        String content = null;
        JDBCPreparedStatement jpstmt = null;
        JDBCResultSet jrs = null;
        GenericMetaObject metaObject = getMetaObject(GenericConstants.OBJECT_TABLE);

        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Get DDL from DB")) {
            jpstmt = session.prepareStatement(sql);
            jpstmt.setString(1, schemaName);
            jpstmt.setString(2, sourceObject.getName());

            jrs = jpstmt.executeQuery();
            while (jrs.next()) {
                if (monitor.isCanceled()) {
                    break;
                }

                content = GenericUtils.safeGetString(metaObject, jrs, colname);
                if (content != null) {
                    source.append(content);
                }
            }
        } catch (Exception e) {
            log.warn("Can't read DDL", e);
        } finally {
            jrs.close();
            jpstmt.close();
        }

        return withoutGeneratedHeader(source.toString(), sourceObject);
    }

    /**
     * The stored source without the comment lines the app puts before a source it shows: the warning earlier builds put
     * there ({@link #LEGACY_NO_DBMS_METADATA}), a package part's name, and the script's object name line
     * ({@link DBStructUtils#getObjectNameComment}, before a view's source). The server keeps the text a client sent as the
     * unit's source, comments before CREATE included, so a source run from the DDL viewer's text holds those lines, and
     * they were shown once more after every such run. Lines are compared stripped: the viewer's text has the platform's
     * line ends, the stored source the query window's.
     */
    @NotNull
    private static String withoutGeneratedHeader(@NotNull String source, @NotNull DBSObject sourceObject) {
        Set<String> generated = new HashSet<>(Set.of(LEGACY_NO_DBMS_METADATA,
            AltibaseConstants.PACKAGE_SPEC_HEADER.strip(), AltibaseConstants.PACKAGE_BODY_HEADER.strip()));
        String objectNameLine = DBStructUtils.getObjectNameComment(sourceObject, ModelMessages.struct_utils_object_ddl_source).strip();
        if (!objectNameLine.isEmpty()) {
            generated.add(objectNameLine);
        }
        int start = 0;
        boolean removed = false;
        while (start < source.length()) {
            int lineEnd = source.indexOf('\n', start);
            int next = lineEnd < 0 ? source.length() : lineEnd + 1;
            String line = source.substring(start, next).strip();
            if (generated.contains(line)) {
                removed = true;
            } else if (!line.isEmpty()) {
                break;
            }
            start = next;
        }
        return removed ? source.substring(start) : source;
    }

    /**
     * Get DDL from DBMS_METADATA package
     */
    private String getDDLFromDbmsMetadata(DBRProgressMonitor monitor, DBSObject sourceObject, String schemaName, String objectType) {
        String ddl = "";
        CallableStatement cstmt = null;
        
        /* Reference:
         * Need to use native CallableStatement
            jcstmt = session.prepareCall("exec ? := dbms_metadata.get_ddl(?, ?, ?)");
            java.lang.NullPointerException
            at Altibase.jdbc.driver.AltibaseParameterMetaData.getParameterMode(AltibaseParameterMetaData.java:31)
            at com.seizia.chromite.model.impl.jdbc.exec.JDBCCallableStatementImpl.getOutputParametersFromJDBC
            (JDBCCallableStatementImpl.java:316)
            at com.seizia.chromite.model.impl.jdbc.exec.JDBCCallableStatementImpl.<init>(JDBCCallableStatementImpl.java:115)
            at com.seizia.chromite.model.impl.jdbc.exec.JDBCFactoryDefault.createCallableStatement(JDBCFactoryDefault.java:48)
         */
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Get DDL from DBMS_METADATA")) {
            if (hasDbmsMetadataPacakge(session)) {
                Connection conn = session.getOriginal();
                cstmt = conn.prepareCall("exec ? := dbms_metadata.get_ddl(?, ?, ?)");
                cstmt.registerOutParameter(1, Types.VARCHAR);

                cstmt.setString(2, objectType);
                cstmt.setString(3, sourceObject.getName());
                cstmt.setString(4, schemaName);

                cstmt.execute();

                ddl = cstmt.getString(1);
            }
        } catch (SQLException se) {
            // Invalid data length.
            if (AltibaseConstants.SQL_STATE_TOO_LONG.equals(se.getSQLState())) {
                ddl = "-- DDL is too long to be fetched.";
            } else if (se.getErrorCode() != AltibaseConstants.EC_DBMS_METADATA_REFUSED) {
                // Not the known refusal of an object type: a real failure must not be silent. Code and state only, a
                // driver's message may name the server
                log.debug("DBMS_METADATA.GET_DDL(" + objectType + ") failed: error " + se.getErrorCode()
                    + ", SQLState " + se.getSQLState());
            }
        } catch (Exception e) {
            log.warn("Can't read DDL from DBMS_METADATA", e);
        } finally {
            try {
                if (cstmt != null) {
                    cstmt.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        return ddl;
    }

    /**
     * Get DDL from DBMS_METADATA package in case of dependent DDL. A server whose package has no GET_DEPENDENT_DDL
     * (ShardSphere) gets the comments from the catalog instead.
     */
    private String getDepDDLFromDbmsMetadata(DBRProgressMonitor monitor, DBSObject sourceObject, 
            String schemaName, String depObjectType) {
        String ddl = "";
        String sqlTerm = "SQLTERMINATOR";
        // A call, as get_ddl: Altibase 7.3 refuses host variables as the arguments of a function in a SELECT list
        // ("Invalid use of host variables", 201275), so the comments never came
        String getDepDdlQry = "exec ? := dbms_metadata.get_dependent_ddl(?, ?, ?)";
        
        Connection conn = null;
        CallableStatement cstmt = null;
        boolean hasTransformParam = false;
        
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Get Dependent DDL from DBMS_METADATA")) {
            AltibaseDataSource dataSource = (AltibaseDataSource) sourceObject.getDataSource();
            if (!dataSource.hasDbmsMetadataSubprogram(session, AltibaseConstants.DBMS_METADATA_GET_DEPENDENT_DDL)) {
                // ShardSphere 5.1: the package has no GET_DEPENDENT_DDL, the comments are in the catalog
                if (DEP_OBJECT_COMMENT.equals(depObjectType)) {
                    ddl = getCommentDDLFromCatalog(session, sourceObject.getName(), schemaName);
                }
            } else if (hasDbmsMetadataPacakge(session)) {
                conn = session.getOriginal();
                hasTransformParam = dataSource.hasDbmsMetadataSubprogram(session, AltibaseConstants.DBMS_METADATA_SET_TRANSFORM_PARAM);
                
                // SQLTERMINATOR: T
                if (hasTransformParam) {
                    setTransformParam(conn, sqlTerm, "T");
                }

                // get dependent ddl 
                cstmt = conn.prepareCall(getDepDdlQry);
                cstmt.registerOutParameter(1, Types.VARCHAR);
                cstmt.setString(2, depObjectType);
                cstmt.setString(3, sourceObject.getName());
                cstmt.setString(4, schemaName);
                cstmt.execute();
                
                ddl = cstmt.getString(1);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() != AltibaseConstants.EC_DBMS_METADATA_NOT_FOUND) {
                log.warn("Failed to get dbms_metadata.get_dependent_ddl [TYPE] " + depObjectType 
                        + " [BASE OBJECT] " + schemaName + "." + sourceObject.getName(), e);
            }
        } catch (Exception e) {
            log.warn("Failed to get dbms_metadata.get_dependent_ddl [TYPE] " + depObjectType 
                    + " [BASE OBJECT] " + schemaName + "." + sourceObject.getName(), e);
        } finally {
            try {
                if (cstmt != null) {
                    cstmt.close();
                }
                
                // SQLTERMINATOR: F
                if (conn != null && hasTransformParam) {
                    setTransformParam(conn, sqlTerm, "F");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            } 
        }

        return ddl;
    }

    /**
     * COMMENT statements of a table read from the catalog (SYSTEM_.SYS_COMMENTS_), in the form and order that
     * DBMS_METADATA.GET_DEPENDENT_DDL('COMMENT', ...) gives with SQLTERMINATOR on (Altibase 7.3): the column comments by
     * column name, then the table comment, separated by an empty line. Empty if the table has no comment.
     */
    private String getCommentDDLFromCatalog(JDBCSession session, String tableName, String schemaName) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        String table = quoteCommentTarget(schemaName) + "." + quoteCommentTarget(tableName);
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT COLUMN_NAME, COMMENTS FROM SYSTEM_.SYS_COMMENTS_ WHERE USER_NAME = ? AND TABLE_NAME = ?"
                + " ORDER BY CASE WHEN COLUMN_NAME IS NULL THEN 1 ELSE 0 END, COLUMN_NAME")) {
            dbStat.setString(1, schemaName);
            dbStat.setString(2, tableName);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    String column = dbResult.getString(1);
                    String comment = CommonUtils.notEmpty(dbResult.getString(2));
                    if (ddl.length() > 0) {
                        ddl.append("\n\n");
                    }
                    ddl.append(column == null
                            ? "COMMENT ON TABLE " + table
                            : "COMMENT ON COLUMN " + table + "." + quoteCommentTarget(column))
                        .append(" IS '").append(comment.replace("'", "''")).append("';");
                }
            }
        }
        return ddl.toString();
    }

    private static String quoteCommentTarget(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
    
    /**
     * The text with one ';' at its end: a server's DDL text may end with one (ShardSphere's table text) or not.
     */
    @NotNull
    private static String terminated(@NotNull String ddl) {
        String text = ddl.stripTrailing();
        return text.endsWith(";") ? text : text + ";";
    }

    /**
     * Whether the server's DDL text has a CREATE INDEX of this index. Both servers quote the index name there.
     */
    private static boolean createsIndex(@NotNull String ddl, @NotNull String indexName) {
        return Pattern.compile("(?i)\\bCREATE\\s+(?:UNIQUE\\s+|LOCALUNIQUE\\s+)?INDEX\\s+(?:\"(?:[^\"]|\"\")*\"\\s*\\.\\s*)?"
            + Pattern.quote(quoteCommentTarget(indexName))).matcher(ddl).find();
    }

    /**
     * The names of the indexes that the table's constraints (primary key, unique) own. Empty if the catalog cannot be
     * read: the table text then lists every index as before.
     */
    @NotNull
    private Set<String> getConstraintIndexNames(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase table,
            @NotNull String schemaName) {
        Set<String> names = new HashSet<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, table, "Read constraint indexes")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                    "SELECT I.INDEX_NAME FROM SYSTEM_.SYS_CONSTRAINTS_ C"
                    + " JOIN SYSTEM_.SYS_INDICES_ I ON I.INDEX_ID = C.INDEX_ID"
                    + " JOIN SYSTEM_.SYS_TABLES_ T ON T.TABLE_ID = C.TABLE_ID"
                    + " JOIN SYSTEM_.SYS_USERS_ U ON U.USER_ID = T.USER_ID"
                    + " WHERE U.USER_NAME = ? AND T.TABLE_NAME = ?")) {
                dbStat.setString(1, schemaName);
                dbStat.setString(2, table.getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        names.add(dbResult.getString(1));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't read the constraint indexes of a table: " + e.getClass().getSimpleName());
        }
        return names;
    }

    /**
     * CREATE INDEX statement of an index built from the catalog, for a server whose DBMS_METADATA.GET_DDL refuses
     * indexes (ShardSphere 5.1, error 990001), terminated by ';'. The form is the one of Altibase 7.3's GET_DDL text:
     * UNIQUE or LOCALUNIQUE, key columns in order with DESC, a function-based key as its expression (the hidden column's
     * DEFAULT_VAL), LOCAL with the index partitions when one has a user's name (only LOCAL when the server named them),
     * the index type, SET PERSISTENT, DIRECTKEY, and the tablespace of an index that is not partitioned. What the catalog
     * does not keep (LOGGING, PARALLEL, STORAGE, DIRECTKEY MAXSIZE) is not in the statement, as in the server's text.
     * An index the catalog cannot describe (an unknown index or partition type, a key without its expression) gets a
     * warning line and the statement commented out, never a wrong statement.
     * Null if the catalog has no such index or cannot be read.
     */
    @Nullable
    private String getIndexDDLFromCatalog(@NotNull DBRProgressMonitor monitor, @NotNull AltibaseTableIndex index,
            @NotNull String schemaName) {
        String tableName = index.getTable().getName();
        List<String> unknown = new ArrayList<>();
        StringBuilder ddl = new StringBuilder();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, index, "Read index definition")) {
            long indexId;
            int indexType;
            boolean localIndex;
            String tablespace;
            String head = "CREATE INDEX ";
            List<String> options = new ArrayList<>();
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                    "SELECT I.INDEX_ID, I.INDEX_TYPE, I.IS_UNIQUE, I.IS_PERS, I.IS_DIRECTKEY, I.IS_PARTITIONED, TS.NAME,"
                    + " PI.PARTITION_TYPE, PI.IS_LOCAL_UNIQUE"
                    + " FROM SYSTEM_.SYS_INDICES_ I"
                    + " JOIN SYSTEM_.SYS_TABLES_ T ON T.TABLE_ID = I.TABLE_ID"
                    + " JOIN SYSTEM_.SYS_USERS_ U ON U.USER_ID = T.USER_ID"
                    + " LEFT OUTER JOIN V$TABLESPACES TS ON TS.ID = I.TBS_ID"
                    + " LEFT OUTER JOIN SYSTEM_.SYS_PART_INDICES_ PI ON PI.INDEX_ID = I.INDEX_ID"
                    + " WHERE U.USER_NAME = ? AND T.TABLE_NAME = ? AND I.INDEX_NAME = ?")) {
                dbStat.setString(1, schemaName);
                dbStat.setString(2, tableName);
                dbStat.setString(3, index.getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (!dbResult.next()) {
                        return null;
                    }
                    indexId = dbResult.getLong(1);
                    indexType = dbResult.getInt(2);
                    if ("T".equals(dbResult.getString(3))) {
                        head = "CREATE UNIQUE INDEX ";
                    } else if ("T".equals(dbResult.getString(9))) {
                        head = "CREATE LOCALUNIQUE INDEX ";
                    }
                    localIndex = "T".equals(dbResult.getString(6));
                    tablespace = dbResult.getString(7);
                    if (localIndex && dbResult.getInt(8) != INDEX_PARTITION_LOCAL) {
                        unknown.add("partition type " + dbResult.getInt(8));
                    }
                    if (indexType == INDEX_TYPE_BTREE) {
                        options.add("INDEXTYPE IS BTREE");
                    } else if (indexType == INDEX_TYPE_RTREE) {
                        options.add("INDEXTYPE IS RTREE");
                    } else {
                        unknown.add("index type " + indexType);
                    }
                    if ("T".equals(dbResult.getString(4))) {
                        options.add("SET PERSISTENT=ON");
                    }
                    if ("T".equals(dbResult.getString(5))) {
                        options.add("DIRECTKEY");
                    }
                }
            }

            List<String> keys = new ArrayList<>();
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                    "SELECT C.COLUMN_NAME, C.IS_HIDDEN, C.DEFAULT_VAL, IC.SORT_ORDER"
                    + " FROM SYSTEM_.SYS_INDEX_COLUMNS_ IC JOIN SYSTEM_.SYS_COLUMNS_ C ON C.COLUMN_ID = IC.COLUMN_ID"
                    + " WHERE IC.INDEX_ID = ? ORDER BY IC.INDEX_COL_ORDER")) {
                dbStat.setLong(1, indexId);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String column = dbResult.getString(1);
                        String key = quoteCommentTarget(column);
                        if ("T".equals(dbResult.getString(2))) {
                            key = dbResult.getString(3);
                            if (CommonUtils.isEmptyTrimmed(key)) {
                                unknown.add("expression of " + column);
                                key = quoteCommentTarget(column);
                            }
                        }
                        keys.add("D".equals(dbResult.getString(4)) ? key + " DESC" : key);
                    }
                }
            }
            if (keys.isEmpty()) {
                unknown.add("key columns");
            }

            ddl.append(head).append(quoteCommentTarget(schemaName)).append('.').append(quoteCommentTarget(index.getName()))
                .append(" ON ").append(quoteCommentTarget(schemaName)).append('.').append(quoteCommentTarget(tableName))
                .append(" (").append(String.join(", ", keys)).append(')');
            if (localIndex) {
                ddl.append(" LOCAL");
                List<String> partitions = new ArrayList<>();
                boolean named = false;
                try (JDBCPreparedStatement dbStat = session.prepareStatement(
                        "SELECT IP.INDEX_PARTITION_NAME, TP.PARTITION_NAME, TS.NAME"
                        + " FROM SYSTEM_.SYS_INDEX_PARTITIONS_ IP"
                        + " JOIN SYSTEM_.SYS_TABLE_PARTITIONS_ TP ON TP.PARTITION_ID = IP.TABLE_PARTITION_ID"
                        + " LEFT OUTER JOIN V$TABLESPACES TS ON TS.ID = IP.TBS_ID"
                        + " WHERE IP.INDEX_ID = ? ORDER BY TP.PARTITION_ID")) {
                    dbStat.setLong(1, indexId);
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        while (dbResult.next()) {
                            String partition = dbResult.getString(1);
                            named |= !partition.startsWith(AltibaseConstants.SYSTEM_GENERATED_PREFIX);
                            String partitionTablespace = dbResult.getString(3);
                            partitions.add(" PARTITION " + quoteCommentTarget(partition) + " ON " + quoteCommentTarget(dbResult.getString(2))
                                + (partitionTablespace == null ? "" : " TABLESPACE " + quoteCommentTarget(partitionTablespace)));
                        }
                    }
                }
                if (named) {
                    ddl.append(" (").append(AltibaseConstants.NEW_LINE)
                        .append(String.join("," + AltibaseConstants.NEW_LINE, partitions)).append(')');
                }
            } else if (tablespace != null) {
                options.add("TABLESPACE " + quoteCommentTarget(tablespace));
            }
            for (String option : options) {
                ddl.append(AltibaseConstants.NEW_LINE).append(option);
            }
            ddl.append(';');
        } catch (Exception e) {
            log.debug("Can't read the index definition from the catalog: " + e.getClass().getSimpleName());
            return null;
        }

        if (unknown.isEmpty()) {
            return ddl.toString();
        }
        // Commented out line by line: a block comment would end at a "*/" in an expression
        return String.format(INDEX_NOT_DESCRIBED, String.join(", ", unknown)) + AltibaseConstants.NEW_LINE
            + "-- " + ddl.toString().replaceAll("\\R", Matcher.quoteReplacement(AltibaseConstants.NEW_LINE + "-- "));
    }

    /**
     * Set dbms_metadata.set_transform_param to change its output format 
     * such as put at the end of returned query with SQL Terminator
     */
    private boolean setTransformParam(Connection conn, String key, String value) throws SQLException {
        boolean result = false;
        CallableStatement cstmt = null;

        try {
            cstmt = conn.prepareCall("exec dbms_metadata.set_transform_param(?, ?)");
            cstmt.setString(1, key);
            cstmt.setString(2, value);
            result = cstmt.execute();
        } catch (SQLException e) {
            log.warn("Failed to execute dbms_metadata.set_transform_param [KEY]" + key + ", [VALUE]" + value, e); 
            
            if (cstmt != null) {
                cstmt.close();
            }
        }
        
        return result;
    }
    
    /**
     * Check whether the connected DBMS has DBMS_METADATA package or not.
     */
    private boolean hasDbmsMetadataPacakge(JDBCSession session) {
        boolean hasDbmsMetadataPacakge = false;

        String sql = "SELECT "
                + " count(*)"
                + " FROM "
                + " SYSTEM_.SYS_PACKAGES_ P" 
                + " WHERE"
                + " PACKAGE_NAME = 'DBMS_METADATA' "
                + " AND STATUS = 0"; // valid

        try (JDBCPreparedStatement jpstmt = session.prepareStatement(sql)) {
            JDBCResultSet jrs =  jpstmt.executeQuery();
            if (jrs.next()) {
                hasDbmsMetadataPacakge = (jrs.getInt(1) == 2);
            }

            jrs.close();
        } catch (Exception e) {
            log.warn("Can't check DBMS_METADATA", e);
        }

        return hasDbmsMetadataPacakge;
    }
    
    @NotNull
    @Override
    public AltibaseTableIndex createIndexImpl(
        @NotNull GenericTableBase table,
        boolean nonUnique,
        String qualifier,
        long cardinality,
        String indexName,
        DBSIndexType indexType,
        boolean persisted) {
        return new AltibaseTableIndex(
                table,
                nonUnique,
                qualifier,
                cardinality,
                indexName,
                indexType,
                persisted);
    }
    
    //////////////////////////////////////////////////////
    // Database Links
    
    /**
     * Statement to load DbLink
     */
    public JDBCStatement prepareDbLinkLoadStatement(@NotNull JDBCSession session, @NotNull GenericStructContainer container,
            @Nullable AltibaseDbLink object, @Nullable String objectName) throws SQLException {
        boolean isNullObject = object == null && objectName == null;
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT u.user_name, l.* FROM system_.sys_database_links_ l, system_.sys_users_ u"
                + " WHERE l.user_id = u.user_id AND u.user_name = ?"
                + (isNullObject ? "" : " AND l.link_name = ?")
                + " ORDER BY link_name ASC");
        
        dbStat.setString(1, container.getName());
        if (!isNullObject) {
            dbStat.setString(2, object != null ? object.getName() : objectName);
        }
        
        return dbStat;
    }
    
    /**
     * Create DbLink implementation
     */
    public AltibaseDbLink createDbLinkImpl(GenericStructContainer container, JDBCResultSet resultSet) {
        return new AltibaseDbLink(container, resultSet);
    }
    
    //////////////////////////////////////////////////////
    // Library
    
    /**
     * Statement to load Library
     */
    public JDBCStatement prepareLibraryLoadStatement(@NotNull JDBCSession session, @NotNull GenericStructContainer container,
            @Nullable AltibaseLibrary object, @Nullable String objectName) throws SQLException {
        boolean isNullObject = object == null && objectName == null;
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT l.* FROM system_.sys_users_ u, system_.sys_libraries_ l "
                + "WHERE u.user_id = l.user_id AND u.user_name =  ? "
                + (isNullObject ? "" : " AND l.library_name = ?")
                + " ORDER BY library_name ASC");
        
        dbStat.setString(1, container.getName());
        if (!isNullObject) {
            dbStat.setString(2, object != null ? object.getName() : objectName);
        }
        
        return dbStat;
    }
    
    /**
     * Create Library implementation
     */
    public AltibaseLibrary createLibraryImpl(GenericStructContainer container, JDBCResultSet resultSet) {
        return new AltibaseLibrary(container, resultSet);
    }
    
    //////////////////////////////////////////////////////
    // Directory
    
    /**
     * Statement to load Directory
     */
    public JDBCStatement prepareDirectoryLoadStatement(@NotNull JDBCSession session, @NotNull GenericStructContainer container,
            @Nullable AltibaseDirectory object, @Nullable String objectName) throws SQLException {
        boolean isNullObject = object == null && objectName == null;
        final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT d.* FROM system_.sys_users_ u, system_.sys_directories_ d "
                + "WHERE u.user_id = d.user_id AND u.user_name =  ? "
                + (isNullObject ? "" : " AND d.directory_name = ?")
                + " ORDER BY directory_name ASC");
        
        dbStat.setString(1, container.getName());
        
        if (!isNullObject) {
            dbStat.setString(2, object != null ? object.getName() : objectName);
        }
        
        return dbStat;
    }
    
    /**
     * Create Directory implementation
     */
    public AltibaseDirectory createDirectoryImpl(GenericStructContainer container, JDBCResultSet resultSet) {
        return new AltibaseDirectory(container, resultSet);
    }
}
