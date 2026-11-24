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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.OracleDDLFormat;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.exec.DBCQueryTransformProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformType;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.DBCQueryTransformerExt;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.generator.SQLGenerator;
import com.seizia.chromite.model.sql.generator.SQLGeneratorDDL;
import com.seizia.chromite.model.sql.generator.SQLGeneratorSelect;
import com.seizia.chromite.model.sql.registry.SQLGeneratorConfigurationRegistry;
import com.seizia.chromite.model.sql.registry.SQLGeneratorDescriptor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Object Explorer의 "Script as", "Select Top 1000 Rows", "View DDL", "Generate SQL"이 쓰는 SQL 만들기.
 *
 * 설계: SWT의 "Generate SQL" 메뉴가 쓰는 모델 계층 생성기({@code com.seizia.chromite.model.sql.generator}, UI 의존 없음)를 그대로 쓴다.
 * <ul>
 *     <li>CREATE(DDL): {@link SQLGeneratorDDL}("Generate SQL > DDL"). 테이블·뷰는 {@code DBStructUtils.generateTableListDDL},
 *     그 밖의 객체는 {@link DBPScriptObject#getObjectDefinitionText}로 만든다. 생성기 기본 옵션(완전한 이름, 주석 포함)을 쓴다.
 *     이 생성기는 {@code OPTION_INCLUDE_OBJECT_DROP}을 켜므로, 이 옵션을 읽는 MySQL 테이블은 DROP 문이 앞에 붙는다(SWT와 같음).</li>
 *     <li>SELECT: {@link SQLGeneratorSelect}("Generate SQL > SELECT", 숨은 열을 뺀 열 목록). 데이터 검색의 "행 열기"는 같은 SELECT에
 *     모델의 조건 생성기로 만든 WHERE를 붙인다({@link #generateFilteredSelect}).</li>
 *     <li>행 수 제한(Select Top N Rows): 방언마다 다른 문법을 직접 쓰지 않고 연결의 {@link DBCQueryTransformProvider}에 묻는다.
 *     먼저 생성한 스크립트만의 자리({@link DBCQueryTransformType#SCRIPT_ROW_LIMIT}: Oracle 12c 이상 {@code FETCH FIRST},
 *     Altibase·ShardSphere {@code LIMIT})를, 없으면 실행 엔진이 최대 행 수를 SQL로 넣을 때 쓰는
 *     {@link DBCQueryTransformType#RESULT_SET_LIMIT}(SQL Server {@code TOP}, SQLite·PostgreSQL·MySQL {@code LIMIT} 등)를 쓴다.
 *     둘 다 없거나(행 수 문법을 모르는 DB, 일반 JDBC) 적용할 수 없으면 SELECT를 그대로 두고, 편집기의 가져오기 행 수 제한
 *     (JDBC 최대 행 수)으로만 자른다. 행 수 문법이 없는 데이터 소스의 목록: docs/chromite/swing-explorer-object-actions.md "행 수 제한".</li>
 *     <li>Generate SQL: SWT 메뉴({@code SQLGeneratorContributor})처럼 생성기 확장점의 등록({@link SQLGeneratorConfigurationRegistry})에서
 *     선택한 객체에 맞는 생성기를 고른다(SELECT, INSERT, UPDATE, DELETE, MERGE, JOIN, CALL, DDL, PostgreSQL의 INSERT ON CONFLICT 등).
 *     드라이버가 확장점으로 더한 생성기도 코드 수정 없이 나온다.</li>
 * </ul>
 * 생성기 실행은 DB 메타데이터를 읽으므로 작업 스레드에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 Generate SQL 메뉴의 생성기 고르기와 실행 (docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-08 Oracle DDL 형식(전체, 저장소 제외, 간단히)으로 만들기 (SWT 감사 O14, docs/chromite/swing-routines.md)
 * - 2026-11-08 조건을 붙인 SELECT {@link #generateFilteredSelect}(데이터 검색, docs/chromite/swing-data-search.md)
 * - 2026-11-22 취소한 생성은 결과를 돌려주지 않음(생성기는 취소된 모니터에서 멈추고 만든 데까지 돌려줘, 취소 뒤에 열이 없는
 *   CREATE TABLE이 탭에 열렸다), 권한 오류의 첫 줄 {@link #errorMessage} (앱 변경 #159, docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-24 행 수 제한을 스크립트만의 자리(SCRIPT_ROW_LIMIT)에서 먼저 찾음: 엔진 변환기가 없는 Oracle·Altibase·ShardSphere에서
 *   "상위 1000개 행 선택"이 제한 없는 SELECT를 열었다(앱 변경 #183, 같은 문서 "행 수 제한")
 */
final class ObjectScripts {

    private static final Log log = Log.getLog(ObjectScripts.class);

    private ObjectScripts() {
    }

    static boolean canScriptCreate(@Nullable DBSObject object) {
        return object instanceof DBPScriptObject && !(object instanceof DBPDataSource);
    }

    static boolean canSelect(@Nullable DBSObject object) {
        return object instanceof DBSEntity && object instanceof DBSDataContainer;
    }

    @NotNull
    static String generateCreate(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        SQLGeneratorDDL generator = new SQLGeneratorDDL();
        generator.initGenerator(List.of((DBPScriptObject) object));
        return runGenerator(monitor, generator);
    }

    /**
     * Whether the object's DDL has formats to choose from: Oracle tables, views and materialized views (full, without
     * storage, compact), as the SWT DDL tab ({@code OracleEditorUtils.addDDLControl}).
     */
    static boolean hasDdlFormats(@Nullable DBSObject object) {
        return object instanceof OracleTableBase;
    }

    /**
     * The DDL of an Oracle table, view or materialized view in a format. As the SWT combo, the format becomes the
     * connection's current one ({@link OracleConstants#PREF_KEY_DDL_FORMAT}, which tables read) and is passed as an
     * option too (which views and materialized views read).
     */
    @NotNull
    static String generateCreate(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object, @NotNull OracleDDLFormat format) throws DBException {
        object.getDataSource().getContainer().getPreferenceStore().setValue(OracleConstants.PREF_KEY_DDL_FORMAT, format.name());
        SQLGeneratorDDL generator = new SQLGeneratorDDL();
        generator.initGenerator(List.of((DBPScriptObject) object));
        generator.setGeneratorOption(OracleConstants.PREF_KEY_DDL_FORMAT, format);
        return runGenerator(monitor, generator);
    }

    /**
     * @param maxRows row limit added with the data source's limit syntax, 0 for none
     */
    @NotNull
    static String generateSelect(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity, long maxRows) throws DBException {
        SQLGeneratorSelect generator = new SQLGeneratorSelect();
        generator.initGenerator(List.of(entity));
        String sql = runGenerator(monitor, generator);
        return maxRows > 0 ? applyRowLimit(entity.getDataSource(), sql, maxRows) : sql;
    }

    /**
     * The SELECT of {@link #generateSelect} with a WHERE clause from a filter, values written into the SQL by the model's
     * condition generator (the query window runs plain SQL). Data search opens its matching rows with it.
     *
     * @param maxRows row limit added with the data source's limit syntax, 0 for none
     */
    @NotNull
    static String generateFilteredSelect(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity entity,
        @NotNull DBDDataFilter filter,
        long maxRows
    ) throws DBException {
        SQLGeneratorSelect generator = new SQLGeneratorSelect();
        generator.initGenerator(List.of(entity));
        DBPDataSource dataSource = entity.getDataSource();
        SQLDialect dialect = dataSource.getSQLDialect();
        StringBuilder sql = new StringBuilder(SQLUtils.removeQueryDelimiter(dialect, runGenerator(monitor, generator).trim()));
        sql.append("\nWHERE "); //$NON-NLS-1$
        SQLUtils.appendConditionString(filter, dataSource, null, sql, true);
        String select = sql.toString();
        // The limit goes to the statement without its delimiter: a LIMIT transformer appends after the text, so a
        // delimited statement would end up as "...;\nLIMIT n". applyRowLimit ends a changed statement itself.
        String limited = maxRows > 0 ? applyRowLimit(dataSource, select, maxRows) : select;
        return limited.equals(select) ? select + SQLUtils.getDefaultScriptDelimiter(dialect) + "\n" : limited; //$NON-NLS-1$
    }

    /**
     * The generators of the Generate SQL menu for selected objects ({@code com.seizia.chromite.sqlGenerator} extensions,
     * in the registry's order): those that apply to at least one of the objects; multi-object generators (JOIN) only
     * for several objects. The same choice as the SWT menu ({@code SQLGeneratorContributor}).
     */
    @NotNull
    static List<SQLGeneratorDescriptor> getGenerators(@NotNull List<DBSObject> objects) {
        return objects.isEmpty() ? List.of() : SQLGeneratorConfigurationRegistry.getInstance().getApplicableGenerators(objects, null);
    }

    /**
     * Runs a Generate SQL generator on the objects it applies to. The others are left out: a generator casts each object
     * to its type (a SELECT generator to a table), so a mixed selection (tables and procedures) would fail.
     */
    @NotNull
    static String generate(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLGeneratorDescriptor descriptor,
        @NotNull List<DBSObject> objects
    ) throws DBException {
        List<Object> applicable = new ArrayList<>();
        for (DBSObject object : objects) {
            if (descriptor.appliesTo(object, null)) {
                applicable.add(object);
            }
        }
        return runGenerator(monitor, descriptor.createGenerator(applicable));
    }

    /**
     * The first line of the error window of a command that reads object definitions: "no privilege" when the server
     * refused for privileges (the data source's error type, e.g. Oracle ORA-31603 from DBMS_METADATA), otherwise the
     * command's own line. The server's error (code and text) follows in the window either way.
     */
    @NotNull
    static String errorMessage(@NotNull DBException error, @NotNull String defaultMessage) {
        return DBExecUtils.discoverErrorType(null, error) == DBPErrorAssistant.ErrorType.PERMISSION_DENIED
            ? CoreMessages.scripts_no_privilege
            : defaultMessage;
    }

    @NotNull
    private static String runGenerator(@NotNull DBRProgressMonitor monitor, @NotNull SQLGenerator<?> generator) throws DBException {
        try {
            generator.run(monitor);
        } catch (InvocationTargetException e) {
            if (monitor.isCanceled()) {
                throw new DBInterruptedException("SQL generation canceled");
            }
            Throwable cause = e.getTargetException();
            throw cause instanceof DBException dbException ? dbException : new DBException(CoreMessages.scripts_cant_generate, cause);
        } catch (InterruptedException e) {
            throw new DBInterruptedException("SQL generation canceled");
        }
        // Generators stop at a canceled monitor and return what they have (an Oracle table without columns, a partial
        // script): a canceled request shows nothing rather than that
        if (monitor.isCanceled()) {
            throw new DBInterruptedException("SQL generation canceled");
        }
        String result = generator.getResult();
        if (result == null) {
            throw new DBException(CoreMessages.scripts_none_generated);
        }
        return result;
    }

    @NotNull
    private static String applyRowLimit(@Nullable DBPDataSource dataSource, @NotNull String sql, long maxRows) {
        DBCQueryTransformProvider provider = DBUtils.getAdapter(DBCQueryTransformProvider.class, dataSource);
        DBCQueryTransformer transformer = null;
        if (provider != null) {
            // The script's own limit first (data sources whose engine limit is off), then the engine's
            transformer = provider.createQueryTransformer(DBCQueryTransformType.SCRIPT_ROW_LIMIT);
            if (transformer == null) {
                transformer = provider.createQueryTransformer(DBCQueryTransformType.RESULT_SET_LIMIT);
            }
        }
        if (dataSource == null || transformer == null) {
            return sql;
        }
        SQLQuery query = new SQLQuery(dataSource, sql);
        if (transformer instanceof DBCQueryTransformerExt transformerExt && !transformerExt.isApplicableTo(query)) {
            return sql;
        }
        try {
            transformer.setParameters(0L, maxRows);
            String limited = transformer.transformQueryString(query);
            SQLDialect dialect = dataSource.getSQLDialect();
            // Transformers drop the delimiter (LIMIT) or rebuild the statement (TOP); end it like the generated SQL
            return SQLUtils.removeQueryDelimiter(dialect, limited.trim()) + SQLUtils.getDefaultScriptDelimiter(dialect) + "\n";
        } catch (Exception e) {
            log.debug("Can't add the row limit to the generated SELECT", e);
            return sql;
        }
    }
}
