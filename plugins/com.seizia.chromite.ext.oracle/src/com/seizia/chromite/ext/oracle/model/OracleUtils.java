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
import com.seizia.chromite.ext.oracle.edit.OracleTableColumnManager;
import com.seizia.chromite.ext.oracle.model.source.OracleSourceObject;
import com.seizia.chromite.ext.oracle.model.source.OracleStatefulObject;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectLazy;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBInterruptedException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle utils
 *
 * 설계: 암호는 {@link #quotePassword}로만 문장에 넣는다. {@code DBUtils.getQuotedIdentifier}는 식별자용이라 이미 큰따옴표로
 * 싸인 글을 그대로 두고(사용자가 친 {@code "abc"}가 암호 {@code abc}가 됨), 이름 규칙에 맞는 글은 따옴표 없이 둔다.
 *
 * {@link #getDDL}은 사용자의 취소(취소된 모니터, 프로세스 안의 중단)를 "DBMS_METADATA를 쓸 수 없음"으로 보지 않는다. 그렇게 보면 취소
 * 뒤에 다른 방법으로 DDL을 만들러 가서(빈 문자열을 받은 {@code DBStructUtils.getTableDDL}도 같음) 취소가 듣지 않고 빈 DDL이 나온다.
 * {@code ORA-01013}만으로는 사용자의 취소로 보지 않는다: 문장 시간 초과와 다른 세션의 {@code ALTER SYSTEM CANCEL SQL}도 같은 오류다.
 * 권한이 없어 DBMS_METADATA가 거절한 테이블(ORA-31603)은 업스트림처럼 사전 뷰로 만든 DDL로 대신하되, 서버의 거절과
 * 빠질 수 있다는 것을 머리 주석으로 밝힌다.
 *
 * 변경 이력:
 * - 2026-11-08 {@link #quotePassword} 추가: 암호 바꾸기·새 스키마의 암호 따옴표 처리 (docs/chromite/password-handling.md)
 * - 2026-11-22 {@link #getDDL}: 취소면 {@link DBInterruptedException}(대신 만들지 않음), 대신 만든 테이블 DDL에 서버 오류와
 *   "빠질 수 있음" 주석 (앱 변경 #159, docs/chromite/swing-routines.md "DDL 권한과 취소")
 * - 2026-11-22 {@link #getDDL}: 취소는 모니터와 프로세스 안의 중단으로만 판단. ORA-01013을 취소로 분류한 뒤에도 시간 초과·다른 세션의
 *   취소가 오류(테이블은 대신 만든 DDL)로 남게 (앱 변경 #169, docs/chromite/swing-frontend.md "DB별 취소 오류의 분류")
 * - 2026-11-29 {@link #addSchemaChangeActions(DBCExecutionContext, List, OracleSourceObject, Map)}: 명령 옵션
 *   {@link DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}이면 끝의 {@code ALTER SESSION SET CURRENT_SCHEMA=<기본>}을 넣지 않음. Modify 스크립트를
 *   받는 자기 세션 창은 개체의 스키마에서 열리는데, 그 문장이 실행 뒤 창의 세션을 기본 스키마로 옮겼다
 *   (docs/chromite/swing-sql-editor-session-ui.md "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창")
 */
public class OracleUtils {

    private static final Log log = Log.getLog(OracleUtils.class);

    /**
     * A password as {@code IDENTIFIED BY} and {@code REPLACE} take it: always in double quotes, so every character is
     * kept as typed (a single quote included). Oracle has no escape for a double quote in a password and refuses one
     * (ORA-25716), so it is refused here with a clear message before anything is sent. YashanDB (Oracle syntax) uses it
     * too.
     *
     * @throws DBException if the password contains a double quote
     */
    @NotNull
    public static String quotePassword(@NotNull String password) throws DBException {
        if (password.indexOf('"') >= 0) {
            throw new DBException("The password cannot contain a double quote");
        }
        return "\"" + password + "\"";
    }

    public static String getDDL(
        DBRProgressMonitor monitor,
        String objectType,
        OracleTableBase object,
        OracleDDLFormat ddlFormat,
        Map<String, Object> options) throws DBException
    {
        if (monitor.isCanceled()) {
            throw new DBInterruptedException("DDL reading canceled");
        }
        String objectFullName = DBUtils.getObjectFullName(object, DBPEvaluationContext.DDL);

        OracleSchema schema = object.getContainer();

        final OracleDataSource dataSource = object.getDataSource();
        final boolean separateConstraintIndexes = isSeparateConstraintIndexesDDL(object, options);

        monitor.subTask("Load sources for " + objectType + " '" + objectFullName + "'...");
        try (final JDBCSession session = DBUtils.openMetaSession(monitor, object, "Load source code for " + objectType + " '" + objectFullName + "'")) {
            
            if (CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_ONLY_FOREIGN_KEYS)) {
                if (!CommonUtils.isEmpty(object.getConstraints(monitor))) {
                    return invokeDBMSMetadataGetDependentDDL(session, schema, object, DBMSMetaDependentObjectType.REF_CONSTRAINT);
                } else {
                    return "";
                }
            }

            if (dataSource.isAtLeastV9()) {
                try {
                    // Do not add semicolon in the end
                    JDBCUtils.executeProcedure(
                        session,
                        "begin\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',true);\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE'," + ddlFormat.isShowStorage() + ");\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'TABLESPACE'," + ddlFormat.isShowTablespace() + ");\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES'," + ddlFormat.isShowSegments() + ");\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'EMIT_SCHEMA'," + CommonUtils.getOption(options, DBPScriptObject.OPTION_FULLY_QUALIFIED_NAMES, true) + ");\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'CONSTRAINTS'," + !separateConstraintIndexes + ");\n" +
                                "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'REF_CONSTRAINTS'," + !CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SEPARATE_FOREIGN_KEYS_STATEMENTS, true) + ");\n" +
                            "end;");
                } catch (SQLException e) {
                    log.error("Can't apply DDL transform parameters", e);
                }
            }

            if (monitor.isCanceled()) {
                throw new DBInterruptedException("DDL reading canceled");
            }

            String ddl = fetchDDL(session, objectType, object.getName(), schema);
            if (ddl == null) return "-- EMPTY DDL";

            if (monitor.isCanceled()) return ddl;

            if (separateConstraintIndexes) {
                // Constraints are excluded from the table DDL. Indexes are created before the constraints
                // which use them - this preserves indexes created independently of their constraints
                if (!CommonUtils.isEmpty(object.getIndexes(monitor))) {
                    ddl += invokeDBMSMetadataGetDependentIndexDDL(session, schema, object, false);
                }

                if (monitor.isCanceled()) return ddl;

                if (!CommonUtils.isEmpty(object.getConstraints(monitor))) {
                    ddl += invokeDBMSMetadataGetDependentDDL(session, schema, object, DBMSMetaDependentObjectType.CONSTRAINT);
                }
            }

            if (monitor.isCanceled()) return ddl;

            if (!CommonUtils.isEmpty(object.getConstraints(monitor)) &&
                !CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SKIP_FOREIGN_KEYS) &&
                CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SEPARATE_FOREIGN_KEYS_STATEMENTS)) {
                ddl += invokeDBMSMetadataGetDependentDDL(session, schema, object, DBMSMetaDependentObjectType.REF_CONSTRAINT);
            }

            if (monitor.isCanceled()) return ddl;

            if (!CommonUtils.isEmpty(object.getTriggers(monitor))) {
                ddl += invokeDBMSMetadataGetDependentDDL(session, schema, object, DBMSMetaDependentObjectType.TRIGGER);
            }

            if (monitor.isCanceled()) return ddl;

            if (!separateConstraintIndexes && !CommonUtils.isEmpty(object.getIndexes(monitor))) {
                // Add index info to main DDL. For some reasons, GET_DDL returns columns, constraints, but not indexes
                ddl += invokeDBMSMetadataGetDependentIndexDDL(session, schema, object, true);
            }

            if (monitor.isCanceled()) return ddl;

            if (ddlFormat == OracleDDLFormat.FULL) {
                // Add grants info to main DDL
                ddl += invokeDBMSMetadataGetDependentDDL(session, schema, object, DBMSMetaDependentObjectType.OBJECT_GRANT);
            }

            if (monitor.isCanceled()) return ddl;

            if (ddlFormat != OracleDDLFormat.COMPACT) {
                // Add object and objects columns info to main DDL
                ddl = addCommentsToDDL(monitor, object, ddl);
            }
            return ddl;
        } catch (SQLException e) {
            if (monitor.isCanceled() || DBExecUtils.isInterruption(e)) {
                // A GET_DDL the user canceled is not a reason to generate the DDL another way. ORA-01013 alone is not
                // the user's cancel: a statement timeout and another session's cancel raise it too
                throw new DBInterruptedException("DDL reading canceled", e);
            }
            if (object instanceof OracleTablePhysical) {
                log.error("Error generating Oracle DDL. Generate default.", e);
                // Marked with the server's refusal: the generated DDL has only what this account can read in the
                // dictionary views (no storage, grants or trigger bodies of another schema's table)
                return SQLUtils.generateCommentLine(dataSource, "DBMS_METADATA: " + CommonUtils.notEmpty(e.getMessage()).lines().findFirst().orElse("").trim()) +
                    SQLUtils.generateCommentLine(dataSource, "DDL generated from the dictionary views this account can read. It may be incomplete.") +
                    DBStructUtils.generateTableDDL(monitor, object, options, true);
            } else {
                throw new DBDatabaseException(e, dataSource);
            }
        }
    }

    @Nullable
    public static String fetchDDL(
        JDBCSession session,
        String objectType,
        String objectName
    ) throws SQLException {
        return fetchDDL(session, objectType, objectName, null);
    }

    @Nullable
    public static String fetchDDL(
        JDBCSession session,
        String objectType,
        String objectName,
        @Nullable OracleSchema schema
    ) throws SQLException {
        String ddl;
        // Read main object DDL
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT DBMS_METADATA.GET_DDL(?,?" + (schema == null ? "" : ",?") + ") TXT FROM DUAL")) {
            dbStat.setString(1, objectType);
            dbStat.setString(2, objectName);
            if (schema != null) {
                dbStat.setString(3, schema.getName());
            }
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (dbResult.next()) {
                    Object ddlValue = dbResult.getObject(1);
                    if (ddlValue instanceof Clob) {
                        StringWriter buf = new StringWriter();
                        try (Reader clobReader = ((Clob) ddlValue).getCharacterStream()) {
                            IOUtils.copyText(clobReader, buf);
                        } catch (IOException e) {
                            log.warn("Can't write ddl query response to string", e);
                        }
                        ddl = buf.toString();

                    } else {
                        ddl = CommonUtils.toString(ddlValue);
                    }
                } else {
                    log.warn("No DDL for " + objectType + " '" + objectName + "'");
                    return null;
                }
            }
        }
        ddl = ddl.trim();
        return ddl;
    }

    private enum DBMSMetaDependentObjectType {
        INDEX,
        CONSTRAINT,
        REF_CONSTRAINT,
        TRIGGER,
        OBJECT_GRANT
    }

    private static String invokeDBMSMetadataGetDependentDDL(JDBCSession session, OracleSchema schema, OracleTableBase object, DBMSMetaDependentObjectType dependentObjectType) {
        String ddl = "";
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT DBMS_METADATA.GET_DEPENDENT_DDL('" + dependentObjectType.name() + "',?" + (schema == null ? "" : ",?") + ") TXT FROM DUAL")) {
            dbStat.setString(1, object.getName());
            if (schema != null) {
                dbStat.setString(2, schema.getName());
            }
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (dbResult.next()) {
                    ddl = "\n\n" + dbResult.getString(1).trim();
                }
            }
        } catch (Exception e) {
            // No dependent index DDL or something went wrong
            log.debug("Error reading dependent DDL '" + dependentObjectType +
                "' for '" + object.getFullyQualifiedName(DBPEvaluationContext.DDL) + "': " + e.getMessage());
        }
        return ddl;
    }

    private static boolean isSeparateConstraintIndexesDDL(@NotNull OracleTableBase object, Map<String, Object> options) {
        return object instanceof OracleTablePhysical &&
            CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SEPARATE_CONSTRAINT_INDEXES);
    }

    private static String invokeDBMSMetadataGetDependentIndexDDL(
        JDBCSession session,
        OracleSchema schema,
        OracleTableBase object,
        boolean skipConstraintIndexes
    ) {
        String ddl = "";
        String query = """
            SELECT DBMS_METADATA.GET_DDL('INDEX', i.index_name, i.owner) AS ddl
            FROM ALL_INDEXES i
            WHERE i.table_owner = NVL(?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA'))
              AND i.table_name = ?
              AND i.generated = 'N'
            """;
        if (skipConstraintIndexes) {
            query += """
                  AND i.index_name NOT IN (
                      SELECT c.index_name
                      FROM ALL_CONSTRAINTS c
                      WHERE c.owner = NVL(?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA'))
                        AND c.table_name = ?
                        AND c.constraint_type IN ('P', 'U')
                        AND c.index_name IS NOT NULL
                  )
                """;
        }
        try (JDBCPreparedStatement dbStat = session.prepareStatement(query)) {
            String schemaName = schema == null ? null : schema.getName();
            dbStat.setString(1, schemaName);
            dbStat.setString(2, object.getName());
            if (skipConstraintIndexes) {
                dbStat.setString(3, schemaName);
                dbStat.setString(4, object.getName());
            }
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                StringBuilder ddlBuilder = new StringBuilder();
                while (dbResult.next()) {
                    String indexDDL = dbResult.getString(1);
                    if (indexDDL != null) {
                        ddlBuilder.append("\n\n").append(indexDDL.trim());
                    }
                }
                ddl = ddlBuilder.toString();
            }
        } catch (Exception e) {
            log.debug("Error reading dependent DDL 'INDEX' for '"
                + object.getFullyQualifiedName(DBPEvaluationContext.DDL) + "': " + e.getMessage());
        }
        return ddl;
    }

    /**
     * Enumeration of granted object types supported by Oracle's DBMS_METADATA.GET_GRANTED_DDL function.
     * These represent different categories of privileges that can be extracted as DDL statements.
     *
     * <ul>
     *     <li><b>SYSTEM_GRANT</b> - System-level privileges granted to a user or role (e.g., CREATE SESSION).</li>
     *     <li><b>ROLE_GRANT</b> - Roles granted to a user or another role.</li>
     *     <li><b>OBJECT_GRANT</b> - Object-level privileges (e.g., SELECT, INSERT on specific tables or views).</li>
     *     <li><b>TABLESPACE_QUOTA</b> - Tablespace quotas granted to a user or role (e.g., QUOTA 100M / UNLIMITED ON a tablespace).</li>
     * </ul>
     */
    public enum DBMSMetaGrantedObjectType {
        SYSTEM_GRANT,
        ROLE_GRANT,
        OBJECT_GRANT,
        TABLESPACE_QUOTA
    }

    /**
     * Retrieves the granted DDL for a specific grantee and object type
     * using Oracle's DBMS_METADATA.GET_GRANTED_DDL function.
     *
     * @param session              the active JDBC session connected to the Oracle database
     * @param grantee              the grantee (user or role) whose granted privileges are to be fetched
     * @param dependentObjectType  the type of granted object (e.g., SYSTEM_GRANT, ROLE_GRANT, OBJECT_GRANT)
     * @return the DDL string representing the granted privileges, or an empty string if none found or an error occurs
     */
    @NotNull
    public static String invokeDBMSMetadataGetGrantedDDL(
        @NotNull JDBCSession session,
        @NotNull OracleGrantee grantee,
        @NotNull DBMSMetaGrantedObjectType dependentObjectType
    ) {
        String ddl = "";
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT DBMS_METADATA.GET_GRANTED_DDL(?,?) TXT FROM DUAL")) {
            dbStat.setString(1, dependentObjectType.name());
            dbStat.setString(2, grantee.getName());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (dbResult.next()) {
                    ddl = dbResult.getString(1).trim();
                }
            }
        } catch (Exception e) {
            // No dependent index DDL or something went wrong
            log.debug("Error reading dependent DDL '" + dependentObjectType +
                "' for '" + grantee.getName() + "': " + e.getMessage());
        }
        return ddl;
    }

    /**
     * Appends the provided DDL statement to the given SQL buffer with proper formatting.
     * Adds a semicolon at the end if it's not already present.
     *
     * @param sql the StringBuilder to append the DDL to
     * @param ddl the DDL string to append; if null or empty, nothing is added
     */
    public static void addDDLLine(@NotNull StringBuilder sql, @Nullable String ddl) {
        if (CommonUtils.isNotEmpty(ddl)) {
            sql.append("\n").append(ddl);
            if (!ddl.endsWith(";")) {
                sql.append(";");
            }
            sql.append("\n");
        }
    }

    private static String addCommentsToDDL(DBRProgressMonitor monitor, OracleTableBase object, String ddl) {
        StringBuilder ddlBuilder = new StringBuilder(ddl);
        String objectFullName = object.getFullyQualifiedName(DBPEvaluationContext.DDL);

        String objectComment = object.getComment(monitor);
        if (!CommonUtils.isEmpty(objectComment)) {
            String objectTypeName = "TABLE";
            if (object instanceof OracleMaterializedView) {
                objectTypeName = "MATERIALIZED VIEW";
            }
            ddlBuilder.append("\n\n").append("COMMENT ON ").append(objectTypeName).append(" ").append(objectFullName).append(" IS ").
                    append(SQLUtils.quoteString(object.getDataSource(), objectComment)).append(SQLConstants.DEFAULT_STATEMENT_DELIMITER);
        }

        try {
            List<OracleTableColumn> attributes = object.getAttributes(monitor);
            if (!CommonUtils.isEmpty(attributes)) {
                List<DBEPersistAction> actions = new ArrayList<>();
                if (CommonUtils.isEmpty(objectComment)) {
                    ddlBuilder.append("\n");
                }
                for (OracleTableColumn column : CommonUtils.safeCollection(attributes)) {
                    String columnComment = column.getComment(monitor);
                    if (!CommonUtils.isEmpty(columnComment)) {
                        OracleTableColumnManager.addColumnCommentAction(actions, column, column.getTable());
                    }
                }
                if (!CommonUtils.isEmpty(actions)) {
                    for (DBEPersistAction action : actions) {
                        ddlBuilder.append("\n").append(action.getScript()).append(SQLConstants.DEFAULT_STATEMENT_DELIMITER);
                    }
                }
            }
        } catch (DBException e) {
            log.debug("Error reading object columns", e);
        }

        return ddlBuilder.toString();
    }

    public static void setCurrentSchema(JDBCSession session, String schema) throws SQLException {
        JDBCUtils.executeSQL(session,
            "ALTER SESSION SET CURRENT_SCHEMA=" + DBUtils.getQuotedIdentifier(session.getDataSource(), schema));
    }

    public static String getCurrentSchema(JDBCSession session) throws SQLException {
        return JDBCUtils.queryString(
            session,
            "SELECT SYS_CONTEXT( 'USERENV', 'CURRENT_SCHEMA' ) FROM DUAL");
    }

    public static String normalizeSourceName(@NotNull DBRProgressMonitor monitor, @NotNull OracleSourceObject object, boolean body) {
        try {
            String source = body ?
                ((DBPScriptObjectExt)object).getExtendedDefinitionText(monitor) :
                object.getObjectDefinitionText(monitor, DBPScriptObject.EMPTY_OPTIONS);
            if (source == null) {
                return null;
            }
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b" + object.getSourceType() + "\\b"+ (body ? "\\s+BODY" : "") +
                "\\s(\\s*)([\\w$\\.\\\"]+)[\\s\\(]+", java.util.regex.Pattern.CASE_INSENSITIVE);
            final Matcher matcher = pattern.matcher(source);
            if (matcher.find()) {
                String objectName = matcher.group(2);
                if (objectName.indexOf('.') == -1) {
                    if (!objectName.equalsIgnoreCase(object.getName())) {
                        object.setName(DBObjectNameCaseTransformer.transformObjectName(object, objectName));
                        object.getDataSource().getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, object));
                    }
                    return source;//.substring(0, matcher.start(1)) + object.getSchema().getName() + "." + objectName + source.substring(matcher.end(2));
                }
            }
            return source.trim();
        } catch (DBException e) {
            log.error(e);
            return null;
        }
    }

    public static void addSchemaChangeActions(DBCExecutionContext executionContext, List<DBEPersistAction> actions, OracleSourceObject object)
    {
        addSchemaChangeActions(executionContext, actions, object, DBPScriptObject.EMPTY_OPTIONS);
    }

    /**
     * Puts {@code ALTER SESSION SET CURRENT_SCHEMA=<object's schema>} first and, when the context's current schema is another one,
     * the switch back to it last, unless the command's options keep the session in the object's schema
     * ({@link DBPScriptObject#OPTION_SKIP_DEFAULTS_RESTORE}).
     */
    public static void addSchemaChangeActions(
        DBCExecutionContext executionContext,
        List<DBEPersistAction> actions,
        OracleSourceObject object,
        Map<String, Object> options
    ) {
        OracleSchema schema = object.getSchema();
        if (schema == null) {
            return;
        }
        actions.add(0, new SQLDatabasePersistAction(
            "Set target schema",
            "ALTER SESSION SET CURRENT_SCHEMA=" + schema.getName(),
            DBEPersistAction.ActionType.INITIALIZER));
        OracleSchema defaultSchema = ((OracleExecutionContext)executionContext).getDefaultSchema();
        if (schema != defaultSchema && defaultSchema != null && !CommonUtils.getOption(options, DBPScriptObject.OPTION_SKIP_DEFAULTS_RESTORE)) {
            actions.add(new SQLDatabasePersistAction(
                "Set current schema",
                "ALTER SESSION SET CURRENT_SCHEMA=" + defaultSchema.getName(),
                DBEPersistAction.ActionType.FINALIZER));
        }
    }

    public static String getSysSchemaPrefix(OracleDataSource dataSource) {
        boolean useSysView = CommonUtils.toBoolean(dataSource.getContainer().getConnectionConfiguration().getProviderProperty(OracleConstants.PROP_METADATA_USE_SYS_SCHEMA));
        if (useSysView) {
            return OracleConstants.SCHEMA_SYS + ".";
        } else {
            return "";
        }
    }

    public static String getSource(DBRProgressMonitor monitor, OracleSourceObject sourceObject, boolean body, boolean insertCreateReplace) throws DBCException
    {
        if (sourceObject.getSourceType().isCustom()) {
            log.warn("Can't read source for custom source objects");
            return "-- ???? CUSTOM SOURCE";
        }
        final String sourceType = sourceObject.getSourceType().name().replace("_", " ");
        final OracleSchema sourceOwner = sourceObject.getSchema();
        if (sourceOwner == null) {
            log.warn("No source owner for object '" + sourceObject.getName() + "'");
            return null;
        }
        monitor.beginTask("Load sources for '" + sourceObject.getName() + "'...", 1);
        String sysViewName = OracleConstants.VIEW_DBA_SOURCE;
        if (!sourceObject.getDataSource().isViewAvailable(monitor, OracleConstants.SCHEMA_SYS, sysViewName)) {
            sysViewName = OracleConstants.VIEW_ALL_SOURCE;
        }
        try (final JDBCSession session = DBUtils.openMetaSession(monitor, sourceOwner, "Load source code for " + sourceType + " '" + sourceObject.getName() + "'")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT TEXT FROM " + getSysSchemaPrefix(sourceObject.getDataSource()) + sysViewName + " " +
                    "WHERE TYPE=? AND OWNER=? AND NAME=? " +
                    "ORDER BY LINE")) {
                String sourceName;
                if (sourceObject instanceof OracleJavaClass) {
                    sourceName = ((OracleJavaClass) sourceObject).getSourceName();
                } else {
                    sourceName = sourceObject.getName();
                }
                dbStat.setString(1, body ? sourceType + " BODY" : sourceType);
                dbStat.setString(2, sourceOwner.getName());
                dbStat.setString(3, sourceName);
                dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder source = null;
                    int lineCount = 0;
                    while (dbResult.next()) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        String line = dbResult.getString(1);
                        if (source == null) {
                            source = new StringBuilder(200);
                        }
                        if (line == null) {
                            line = "";
                        }
                        source.append(line);
                        if (sourceObject instanceof OracleJavaClass && !line.endsWith("\n")) {
                            // Java source
                            source.append("\n");
                        }
                        lineCount++;
                        monitor.subTask("Line " + lineCount);
                    }
                    if (source == null) {
                        return null;
                    }
                    if (insertCreateReplace) {
                        return insertCreateReplace(sourceObject, body, source.toString());
                    } else {
                        return source.toString();
                    }
                }
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
        } finally {
            monitor.done();
        }
    }

    public static String getSysUserViewName(DBRProgressMonitor monitor, OracleDataSource dataSource, String viewName)
    {
        String dbaView = "DBA_" + viewName;
        if (dataSource.isViewAvailable(monitor, OracleConstants.SCHEMA_SYS, dbaView)) {
            return OracleUtils.getSysSchemaPrefix(dataSource) + dbaView;
        } else {
            return OracleUtils.getSysSchemaPrefix(dataSource) + "USER_" + viewName;
        }
    }

    public static String getAdminAllViewPrefix(DBRProgressMonitor monitor, OracleDataSource dataSource, String viewName)
    {
        boolean useDBAView = CommonUtils.toBoolean(dataSource.getContainer().getConnectionConfiguration().getProviderProperty(OracleConstants.PROP_ALWAYS_USE_DBA_VIEWS));
        if (useDBAView) {
            String dbaView = "DBA_" + viewName;
            if (dataSource.isViewAvailable(monitor, OracleConstants.SCHEMA_SYS, dbaView)) {
                return OracleUtils.getSysSchemaPrefix(dataSource) + dbaView;
            }
        }
        return OracleUtils.getSysSchemaPrefix(dataSource) + "ALL_" + viewName;
    }

    public static String getSysCatalogHint(OracleDataSource dataSource)
    {
        return dataSource.isUseRuleHint() ? "/*+RULE*/" : "";
    }

    static <PARENT extends DBSObject> Object resolveLazyReference(
        DBRProgressMonitor monitor,
        PARENT parent,
        DBSObjectCache<PARENT,?> cache,
        DBSObjectLazy<?> referrer,
        Object propertyId)
        throws DBException
    {
        final Object reference = referrer.getLazyReference(propertyId);
        if (reference instanceof String) {
            Object object;
            if (monitor != null) {
                object = cache.getObject(
                    monitor,
                    parent,
                    (String) reference);
            } else {
                object = cache.getCachedObject((String) reference);
            }
            if (object != null) {
                return object;
            } else {
                log.warn("Object '" + reference + "' not found");
                return reference;
            }
        } else {
            return reference;
        }
    }

    public static boolean getObjectStatus(
        DBRProgressMonitor monitor,
        OracleStatefulObject object,
        OracleObjectType objectType)
        throws DBCException
    {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, object, "Refresh state of " + objectType.getTypeName() + " '" + object.getName() + "'")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT STATUS FROM " + OracleUtils.getAdminAllViewPrefix(monitor, object.getDataSource(), "OBJECTS") + " WHERE OBJECT_TYPE=? AND OWNER=? AND OBJECT_NAME=?")) {
                dbStat.setString(1, objectType.getTypeName());
                dbStat.setString(2, object.getSchema().getName());
                dbStat.setString(3, DBObjectNameCaseTransformer.transformObjectName(object, object.getName()));
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        return OracleConstants.RESULT_STATUS_VALID.equals(dbResult.getString(OracleConstants.COLUMN_STATUS));
                    } else {
                        log.warn(objectType.getTypeName() + " '" + object.getName() + "' not found in system dictionary");
                        return false;
                    }
                }
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
        }
    }

    public static String insertCreateReplace(OracleSourceObject object, boolean body, String source) {
        String sourceType = object.getSourceType().name();
        if (body) {
            sourceType += " BODY";
        }
        Pattern srcPattern = Pattern.compile("^(" + sourceType + ")\\s+(\"?\\w+\"?)(?:\\.(\"?\\w+\"?))?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = srcPattern.matcher(source);
        if (matcher.find()) {
            if (matcher.group(3) == null) {
                return "CREATE OR REPLACE " + matcher.group(1) + " " +
                    DBUtils.getQuotedIdentifier(object.getSchema()) + "." + matcher.group(2) +
                    source.substring(matcher.end());
            } else {
                return "CREATE OR REPLACE " + source;
            }
        }
        return source;
    }

    public static String formatWord(String word)
	{
		if (word == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(word.length());
		sb.append(Character.toUpperCase(word.charAt(0)));
		for (int i = 1; i < word.length(); i++) {
			char c = word.charAt(i);
			if ((c == 'i' || c == 'I') && sb.charAt(i - 1) == 'I') {
				sb.append('I');
			} else {
				sb.append(Character.toLowerCase(c));
			}
		}
		return sb.toString();
	}

    public static String formatSentence(String sent)
	{
		if (sent == null) {
			return "";
		}
		StringBuilder result = new StringBuilder();
		StringTokenizer st = new StringTokenizer(sent, " \t\n\r-,.\\/", true);
		while (st.hasMoreTokens()) {
			String word = st.nextToken();
			if (word.length() > 0) {
				result.append(formatWord(word));
			}
		}

		return result.toString();
	}
}
