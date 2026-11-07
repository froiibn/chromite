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
package com.seizia.chromite.model.admin.routines;

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataType;
import com.seizia.chromite.ext.oracle.model.OracleProcedureArgument;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedureParameter;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The script that runs a stored procedure or function with argument values in a query window (Object Explorer
 * "Execute Procedure…", SSMS "Execute Stored Procedure").
 *
 * 설계: SWT의 "Execute procedure"({@code SQLEditorHandlerRunProcedureConsole})는 방언의 호출문 생성기({@code generateStoredProcedureCall})로
 * {@code :이름} 매개 변수가 든 문장을 열고 실행할 때 값을 물었다. OUT 매개 변수 값은 볼 수 없었다. 여기서는 SSMS처럼 대화상자에서 받은 값을 넣은
 * 스크립트를 만들고, OUT·IN OUT 값과 함수 결과를 쿼리 창에서 볼 수 있게 방언마다 받아 둔다. 값은 SQL 식이다({@code 10}, {@code 'text'}, {@code NULL};
 * 디버거 시작 대화상자와 같은 규칙). 비어 있으면 NULL이다.
 * <ul>
 *     <li>SQL Server: SSMS 스크립트와 같은 모양. {@code USE} 배치, OUTPUT 매개 변수마다 같은 이름의 변수, {@code EXEC @return_value = …},
 *     OUTPUT 값과 반환 값을 SELECT로 보인다. 스칼라 함수는 {@code SELECT f(…)}, 테이블 반환 함수는 {@code SELECT * FROM f(…)}이다
 *     (구분은 #122의 {@link SQLDialect#isCallStatementTarget}).</li>
 *     <li>PostgreSQL: 프로시저는 {@code CALL p(…)}(OUT 자리에는 NULL, 서버가 OUT·INOUT 값을 한 행으로 돌려준다), 함수는 {@code SELECT * FROM f(…)}
 *     (OUT 매개 변수는 결과 열이다). 이름 없는 인수가 있을 수 있어 위치 표기로 넘긴다.</li>
 *     <li>Oracle: 익명 블록. 인수는 이름 표기({@code 이름 => 값})로 넘겨 패키지의 겹친 루틴도 골라지고, OUT·IN OUT 값과 함수 결과는 블록 변수로 받아
 *     {@code DBMS_OUTPUT.PUT_LINE}으로 쓴다(쿼리 창이 Messages 탭에 보인다). 변수 선언은 Oracle 디버거({@code OracleDebugCall})와 같은 규칙이다:
 *     스칼라는 {@code VARCHAR2(32767)}(암묵 변환), BOOLEAN은 글자로 바꿔 쓰고, REF CURSOR·레코드·컬렉션·객체는 그 형식으로 받되 값은 쓰지 않는다.</li>
 *     <li>MySQL/MariaDB: OUT·INOUT은 사용자 변수({@code @이름})로 넘기고 뒤에서 SELECT한다. 함수는 {@code SELECT f(…)}다.</li>
 *     <li>Altibase: Oracle과 같은 익명 블록이고 출력은 {@code PRINTLN}이다(7.3에서 확인). 변수는 매개 변수의 형식으로 선언한다.
 *     OUT 자리에 NULL을 넘기는 {@code EXEC}는 ERR-31147로 실패한다.</li>
 *     <li>그 밖의 드라이버: 방언의 실행 키워드({@code CALL} 등, 함수는 {@code SELECT f(…)}와 {@code DUAL})로 부르고 OUT 자리에는 NULL을 넘긴다.
 *     OUT 값은 받지 못한다({@link #capturesOutputs}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Execute Procedure… (SWT 감사 B-S42, docs/chromite/swing-routines.md)
 */
public final class ProcedureCallScript {

    private static final String NULL = "NULL"; //$NON-NLS-1$
    private static final String RETURN_VALUE = "Return Value"; //$NON-NLS-1$

    /**
     * Oracle argument types held in a {@code VARCHAR2} variable (implicit conversion both ways), as the Oracle debugger.
     */
    private static final Set<String> ORACLE_SCALAR_TYPES = Set.of(
        "VARCHAR2", "NVARCHAR2", "VARCHAR", "CHAR", "NCHAR", "LONG", "NUMBER", "FLOAT", "INTEGER", "SMALLINT", "DECIMAL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
        "BINARY_INTEGER", "PLS_INTEGER", "BINARY_FLOAT", "BINARY_DOUBLE", "DATE", "RAW", "LONG RAW", "CLOB", "NCLOB", "ROWID", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        "UROWID"); //$NON-NLS-1$

    private ProcedureCallScript() {
    }

    /**
     * Whether the dialog can run the object: a saved procedure or function.
     */
    public static boolean canExecute(@Nullable Object object) {
        return object instanceof DBSProcedure procedure && procedure.isPersisted() && procedure.getDataSource() != null;
    }

    /**
     * Whether the script shows the OUT values for this routine's database.
     */
    public static boolean capturesOutputs(@NotNull DBSProcedure procedure) {
        DBPDataSource dataSource = procedure.getDataSource();
        return dataSource instanceof SQLServerDataSource || dataSource instanceof PostgreDataSource || dataSource instanceof OracleDataSource
            || dataSource instanceof MySQLDataSource || dataSource instanceof AltibaseDataSource;
    }

    /**
     * The parameters the dialog shows, in their order: the inputs and outputs of the call (not a function's result,
     * nor the result columns of a table function).
     */
    @NotNull
    public static List<DBSProcedureParameter> getParameters(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSProcedure procedure
    ) throws DBException {
        List<DBSProcedureParameter> parameters = new ArrayList<>();
        for (DBSProcedureParameter parameter : CommonUtils.safeCollection(procedure.getParameters(monitor))) {
            if (isInput(parameter) || isOutput(parameter)) {
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    /**
     * Whether the call takes a value for the parameter (IN, IN OUT, PostgreSQL VARIADIC).
     */
    public static boolean isInput(@NotNull DBSProcedureParameter parameter) {
        if (parameter instanceof PostgreProcedureParameter postgreParameter) {
            // pg_proc's "v" mode: the model calls it RESULTSET
            return postgreParameter.getArgumentMode() == PostgreProcedure.ArgumentMode.v || parameter.getParameterKind().isInput();
        }
        if (parameter instanceof OracleProcedureArgument argument && CommonUtils.isEmpty(argument.getTypeName())) {
            // The row ALL_ARGUMENTS has for a routine without arguments
            return false;
        }
        return parameter.getParameterKind().isInput();
    }

    /**
     * Whether the call returns a value in the parameter (OUT, IN OUT).
     */
    public static boolean isOutput(@NotNull DBSProcedureParameter parameter) {
        DBSProcedureParameterKind kind = parameter.getParameterKind();
        return kind == DBSProcedureParameterKind.OUT || kind == DBSProcedureParameterKind.INOUT;
    }

    /**
     * Makes the script.
     *
     * @param values argument expressions of the input parameters ({@link #getParameters}); missing or empty is NULL
     */
    @NotNull
    public static String generate(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSProcedure procedure,
        @NotNull Map<DBSProcedureParameter, String> values
    ) throws DBException {
        DBPDataSource dataSource = procedure.getDataSource();
        if (dataSource == null) {
            throw new DBException("Not connected"); //$NON-NLS-1$
        }
        List<DBSProcedureParameter> all = new ArrayList<>(CommonUtils.safeCollection(procedure.getParameters(monitor)));
        List<DBSProcedureParameter> parameters = getParameters(monitor, procedure);
        if (dataSource instanceof SQLServerDataSource) {
            return sqlServer(procedure, parameters, values);
        } else if (dataSource instanceof PostgreDataSource) {
            return postgres(procedure, parameters, values);
        } else if (dataSource instanceof OracleDataSource) {
            return block(procedure, all, parameters, values, true);
        } else if (dataSource instanceof AltibaseDataSource) {
            return block(procedure, all, parameters, values, false);
        } else if (dataSource instanceof MySQLDataSource) {
            return mysql(procedure, parameters, values);
        }
        return generic(procedure, parameters, values);
    }

    @NotNull
    private static String value(@NotNull Map<DBSProcedureParameter, String> values, @NotNull DBSProcedureParameter parameter) {
        String value = values.get(parameter);
        return CommonUtils.isEmptyTrimmed(value) ? NULL : value.strip();
    }

    @NotNull
    private static String name(@NotNull DBSProcedure procedure) {
        return procedure.getFullyQualifiedName(DBPEvaluationContext.DML);
    }

    @NotNull
    private static String quote(@NotNull DBSProcedure procedure, @NotNull String name) {
        return DBUtils.getQuotedIdentifier(procedure.getDataSource(), name);
    }

    /**
     * The declared type of a parameter, with its length or precision.
     */
    @NotNull
    private static String typeOf(@NotNull DBSProcedureParameter parameter) {
        return parameter instanceof DBSTypedObject typed ? typed.getFullTypeName() : parameter.getParameterType().getFullTypeName();
    }

    //////////////////////////////////////////////////////////
    // SQL Server

    @NotNull
    private static String sqlServer(
        @NotNull DBSProcedure procedure,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull Map<DBSProcedureParameter, String> values
    ) {
        StringBuilder script = new StringBuilder();
        DBSCatalog database = DBUtils.getParentOfType(DBSCatalog.class, procedure);
        if (database != null) {
            // As SSMS and the Modify window (SQLServerObjectManager.addDatabaseSwitchAction1)
            script.append("USE ").append(DBUtils.getQuotedIdentifier(database)).append("\nGO\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (procedure.getProcedureType() == DBSProcedureType.FUNCTION) {
            List<String> arguments = new ArrayList<>();
            for (DBSProcedureParameter parameter : parameters) {
                if (isInput(parameter)) {
                    arguments.add(value(values, parameter));
                }
            }
            String call = name(procedure) + "(" + String.join(", ", arguments) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (procedure.getDataSource().getSQLDialect().isCallStatementTarget(procedure)) {
                script.append("SELECT ").append(call).append(" AS ").append(quote(procedure, RETURN_VALUE)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } else {
                script.append("SELECT * FROM ").append(call).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return script.append("GO\n").toString(); //$NON-NLS-1$
        }
        script.append("DECLARE @return_value int;\n"); //$NON-NLS-1$
        List<String> outputs = new ArrayList<>();
        for (DBSProcedureParameter parameter : parameters) {
            if (isOutput(parameter)) {
                // T-SQL OUTPUT parameters also take a value in: the variable starts with it
                script.append("DECLARE ").append(parameter.getName()).append(' ').append(typeOf(parameter)) //$NON-NLS-1$
                    .append(" = ").append(value(values, parameter)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
                outputs.add(parameter.getName() + " AS " + quote(procedure, parameter.getName())); //$NON-NLS-1$
            }
        }
        script.append("\nEXEC @return_value = ").append(name(procedure)); //$NON-NLS-1$
        for (int i = 0; i < parameters.size(); i++) {
            DBSProcedureParameter parameter = parameters.get(i);
            script.append(i == 0 ? "\n    " : ",\n    ").append(parameter.getName()).append(" = "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (isOutput(parameter)) {
                script.append(parameter.getName()).append(" OUTPUT"); //$NON-NLS-1$
            } else {
                script.append(value(values, parameter));
            }
        }
        script.append(";\n\n"); //$NON-NLS-1$
        if (!outputs.isEmpty()) {
            script.append("SELECT ").append(String.join(", ", outputs)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        script.append("SELECT @return_value AS ").append(quote(procedure, RETURN_VALUE)).append(";\nGO\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return script.toString();
    }

    //////////////////////////////////////////////////////////
    // PostgreSQL

    @NotNull
    private static String postgres(
        @NotNull DBSProcedure procedure,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull Map<DBSProcedureParameter, String> values
    ) {
        boolean isProcedure = procedure.getProcedureType() == DBSProcedureType.PROCEDURE;
        List<String> arguments = new ArrayList<>();
        for (DBSProcedureParameter parameter : parameters) {
            if (isInput(parameter)) {
                arguments.add(value(values, parameter));
            } else if (isProcedure) {
                // A procedure's OUT parameter takes a placeholder in CALL (PostgreSQL 14+); the value comes back as a row
                arguments.add(NULL);
            }
        }
        String call = name(procedure) + "(" + String.join(", ", arguments) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return (isProcedure ? "CALL " + call : "SELECT * FROM " + call) + ";\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    //////////////////////////////////////////////////////////
    // Oracle and Altibase anonymous block

    /**
     * @param oracle Oracle (DBMS_OUTPUT, the debugger's variable rules), else Altibase (PRINTLN, declared types)
     */
    @NotNull
    private static String block(
        @NotNull DBSProcedure procedure,
        @NotNull List<DBSProcedureParameter> all,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull Map<DBSProcedureParameter, String> values,
        boolean oracle
    ) {
        boolean function = procedure.getProcedureType() == DBSProcedureType.FUNCTION;
        if (!oracle && function && parameters.stream().noneMatch(ProcedureCallScript::isOutput)) {
            // An Altibase function without OUT parameters: a plain query shows the result as a grid
            List<String> arguments = new ArrayList<>();
            for (DBSProcedureParameter parameter : parameters) {
                arguments.add(value(values, parameter));
            }
            return "SELECT " + name(procedure) + "(" + String.join(", ", arguments) + ") AS " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + quote(procedure, RETURN_VALUE) + " FROM DUAL;\n"; //$NON-NLS-1$
        }
        StringBuilder declarations = new StringBuilder();
        List<String> arguments = new ArrayList<>();
        List<String> prints = new ArrayList<>();
        String assignment = ""; //$NON-NLS-1$
        if (function) {
            DBSProcedureParameter result = null;
            for (DBSProcedureParameter parameter : all) {
                if (parameter.getParameterKind() == DBSProcedureParameterKind.RETURN) {
                    result = parameter;
                    break;
                }
            }
            String declaration = result == null ? (oracle ? "VARCHAR2(32767)" : "VARCHAR(32000)") : declarationOf(result, oracle); //$NON-NLS-1$ //$NON-NLS-2$
            declarations.append("  v_result ").append(declaration).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
            assignment = "v_result := "; //$NON-NLS-1$
            prints.add(print(RETURN_VALUE, "v_result", result, oracle)); //$NON-NLS-1$
        }
        int variables = 0;
        for (DBSProcedureParameter parameter : parameters) {
            String argument = quote(procedure, parameter.getName()) + " => "; //$NON-NLS-1$
            if (isOutput(parameter)) {
                String variable = "v_" + (++variables); //$NON-NLS-1$
                declarations.append("  ").append(variable).append(' ').append(declarationOf(parameter, oracle)); //$NON-NLS-1$
                if (isInput(parameter)) {
                    declarations.append(" := ").append(value(values, parameter)); //$NON-NLS-1$
                }
                declarations.append(";\n"); //$NON-NLS-1$
                arguments.add(argument + variable);
                prints.add(print(parameter.getName(), variable, parameter, oracle));
            } else {
                arguments.add(argument + value(values, parameter));
            }
        }
        StringBuilder script = new StringBuilder();
        if (!declarations.isEmpty()) {
            script.append("DECLARE\n").append(declarations); //$NON-NLS-1$
        }
        script.append("BEGIN\n  ").append(assignment).append(name(procedure)); //$NON-NLS-1$
        if (!arguments.isEmpty()) {
            script.append("(\n    ").append(String.join(",\n    ", arguments)).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        script.append(";\n"); //$NON-NLS-1$
        for (String print : prints) {
            script.append("  ").append(print).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // No "/" line: the query window ends the block at END; and would send "/" as a statement (ORA-00900)
        return script.append("END;\n").toString(); //$NON-NLS-1$
    }

    @NotNull
    private static String declarationOf(@NotNull DBSProcedureParameter parameter, boolean oracle) {
        if (!oracle) {
            return typeOf(parameter);
        }
        String type = oracleType(parameter);
        if (isBoolean(type)) {
            return "BOOLEAN"; //$NON-NLS-1$
        }
        if (ORACLE_SCALAR_TYPES.contains(type) || type.startsWith("TIMESTAMP") || type.startsWith("INTERVAL")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "VARCHAR2(32767)"; //$NON-NLS-1$
        }
        if ("REF CURSOR".equals(type)) { //$NON-NLS-1$
            return "SYS_REFCURSOR"; //$NON-NLS-1$
        }
        // A record, collection or object type: declared with its own type
        if (parameter instanceof OracleProcedureArgument argument) {
            Object declared = argument.getType();
            if (declared instanceof OracleDataType dataType && !dataType.isPredefined()) {
                return dataType.getFullyQualifiedName(DBPEvaluationContext.DDL);
            }
            if (declared instanceof String packageType) {
                return packageType;
            }
        }
        return type;
    }

    /**
     * The output statement of a value: its text for scalars, TRUE/FALSE for BOOLEAN, the type name for other types.
     */
    @NotNull
    private static String print(@NotNull String label, @NotNull String variable, @Nullable DBSProcedureParameter parameter, boolean oracle) {
        String prefix = "'" + label.replace("'", "''") + " = ' || "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!oracle) {
            return "PRINTLN(" + prefix + variable + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String type = parameter == null ? "VARCHAR2" : oracleType(parameter); //$NON-NLS-1$
        String text;
        if (isBoolean(type)) {
            text = "CASE WHEN " + variable + " THEN 'TRUE' WHEN NOT " + variable + " THEN 'FALSE' END"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } else if (ORACLE_SCALAR_TYPES.contains(type) || type.startsWith("TIMESTAMP") || type.startsWith("INTERVAL")) { //$NON-NLS-1$ //$NON-NLS-2$
            text = variable;
        } else {
            text = "'(" + type.replace("'", "''") + ")'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return "DBMS_OUTPUT.PUT_LINE(" + prefix + text + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The Oracle base type name of an argument ({@code ALL_ARGUMENTS.DATA_TYPE}).
     */
    @NotNull
    private static String oracleType(@NotNull DBSProcedureParameter parameter) {
        return CommonUtils.notEmpty(parameter.getParameterType().getTypeName());
    }

    private static boolean isBoolean(@NotNull String type) {
        return type.equals("BOOLEAN") || type.equals("PL/SQL BOOLEAN"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    //////////////////////////////////////////////////////////
    // MySQL / MariaDB

    @NotNull
    private static String mysql(
        @NotNull DBSProcedure procedure,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull Map<DBSProcedureParameter, String> values
    ) {
        StringBuilder script = new StringBuilder();
        List<String> arguments = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        for (DBSProcedureParameter parameter : parameters) {
            if (isOutput(parameter)) {
                String variable = "@" + quote(procedure, parameter.getName()); //$NON-NLS-1$
                if (isInput(parameter)) {
                    script.append("SET ").append(variable).append(" = ").append(value(values, parameter)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                arguments.add(variable);
                outputs.add(variable + " AS " + quote(procedure, parameter.getName())); //$NON-NLS-1$
            } else {
                arguments.add(value(values, parameter));
            }
        }
        String call = name(procedure) + "(" + String.join(", ", arguments) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (procedure.getProcedureType() == DBSProcedureType.FUNCTION) {
            script.append("SELECT ").append(call).append(" AS ").append(quote(procedure, RETURN_VALUE)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } else {
            script.append("CALL ").append(call).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!outputs.isEmpty()) {
            script.append("SELECT ").append(String.join(", ", outputs)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return script.toString();
    }

    //////////////////////////////////////////////////////////
    // Other drivers

    @NotNull
    private static String generic(
        @NotNull DBSProcedure procedure,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull Map<DBSProcedureParameter, String> values
    ) {
        SQLDialect dialect = procedure.getDataSource().getSQLDialect();
        List<String> arguments = new ArrayList<>();
        for (DBSProcedureParameter parameter : parameters) {
            arguments.add(isInput(parameter) ? value(values, parameter) : NULL);
        }
        String call = name(procedure) + "(" + String.join(", ", arguments) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!dialect.isCallStatementTarget(procedure)) {
            String dual = dialect.getDualTableName();
            return "SELECT " + call + (dual == null ? "" : " FROM " + dual) + ";\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String[] keywords = dialect.getExecuteKeywords();
        return (ArrayUtils.isEmpty(keywords) ? "CALL" : keywords[0]) + " " + call + ";\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
