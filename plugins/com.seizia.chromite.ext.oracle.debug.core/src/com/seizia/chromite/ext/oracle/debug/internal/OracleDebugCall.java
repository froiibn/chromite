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
package com.seizia.chromite.ext.oracle.debug.internal;

import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleProcedureBase;
import com.seizia.chromite.ext.oracle.model.OracleProcedurePackaged;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The anonymous block the debugged session runs: the routine call with the user's argument expressions, and the outputs
 * (function result, OUT arguments) read back through bind variables.
 *
 * 설계:
 * <ul>
 *     <li>인수는 PostgreSQL처럼 사용자가 쓴 식(PL/SQL 식) 그대로 넣고, 이름 표기({@code 이름 => 값})로 부른다. 패키지의 겹친(overload)
 *     루틴도 이름과 형식으로 골라진다.</li>
 *     <li>OUT·IN OUT 인수와 함수 결과는 블록 지역 변수로 받고 끝에 바인드 변수로 돌려준다. 스칼라 형식은 {@code VARCHAR2(32767)}
 *     변수로 받는다: 암묵 변환으로 NUMBER·DATE 등의 IN OUT·OUT 인수에 넘기고 받을 수 있다(26ai에서 확인). BOOLEAN은 글자로 바꾼다.
 *     레코드·컬렉션·객체·REF CURSOR는 선언 형식(ALL_ARGUMENTS의 TYPE_OWNER·TYPE_NAME·TYPE_SUBNAME)으로 받고 값은 보이지 않는다.</li>
 *     <li>블록은 {@code DBMS_DEBUG.DEBUG_OFF}를 부르지 않는다: 중지(abort)는 블록의 예외 처리기까지 건너뛰므로, 디버그 모드 끄기는
 *     호출이 어떻게 끝나든 세션이 따로 한다({@link OracleDebugSession}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param block   the anonymous block; its {@code ?} binds are the outputs in order
 * @param outputs result columns: a bound output, or a fixed text for a value that cannot be shown
 */
record OracleDebugCall(@NotNull String block, @NotNull List<Output> outputs) {

    /**
     * A result column.
     *
     * @param column      column label
     * @param placeholder text shown instead of the value (not bound), or null for a bound value
     */
    record Output(@NotNull String column, @Nullable String placeholder) {
    }

    private enum Kind {
        SCALAR,
        BOOLEAN,
        OPAQUE
    }

    private static final Set<String> SCALAR_TYPES = Set.of(
        "VARCHAR2", "NVARCHAR2", "VARCHAR", "CHAR", "NCHAR", "LONG", "NUMBER", "FLOAT", "INTEGER", "SMALLINT", "DECIMAL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
        "BINARY_INTEGER", "PLS_INTEGER", "BINARY_FLOAT", "BINARY_DOUBLE", "DATE", "RAW", "LONG RAW", "CLOB", "NCLOB", "ROWID", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        "UROWID"); //$NON-NLS-1$
    private static final String SCALAR_DECLARATION = "VARCHAR2(32767)"; //$NON-NLS-1$
    private static final String REF_CURSOR = "REF CURSOR"; //$NON-NLS-1$

    /**
     * @param session metadata session of the routine's connection
     * @param values  argument expressions of the routine's input (IN, IN OUT) parameters, in their order
     */
    @NotNull
    static OracleDebugCall compose(
        @NotNull JDBCSession session,
        @NotNull OracleProcedureBase<?> routine,
        @NotNull List<String> values
    ) throws SQLException, DBGException {
        OracleDataSource dataSource = routine.getDataSource();
        String packageName = routine instanceof OracleProcedurePackaged packaged ? packaged.getParentObject().getName() : null;
        Integer overload = routine.getOverloadNumber();
        String sql = "SELECT ARGUMENT_NAME, POSITION, IN_OUT, DATA_TYPE, TYPE_OWNER, TYPE_NAME, TYPE_SUBNAME FROM " //$NON-NLS-1$
            + OracleUtils.getSysSchemaPrefix(dataSource) + "ALL_ARGUMENTS " //$NON-NLS-1$
            + "WHERE OWNER = ? AND OBJECT_NAME = ? AND NVL(PACKAGE_NAME, CHR(0)) = NVL(?, CHR(0)) " //$NON-NLS-1$
            + "AND NVL(OVERLOAD, CHR(0)) = NVL(?, CHR(0)) AND DATA_LEVEL = 0 ORDER BY SEQUENCE"; //$NON-NLS-1$

        StringBuilder declarations = new StringBuilder();
        List<String> arguments = new ArrayList<>();
        List<String> outputTexts = new ArrayList<>();
        List<Output> outputs = new ArrayList<>();
        String resultAssignment = ""; //$NON-NLS-1$
        int inputIndex = 0;
        int variableCount = 0;
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, routine.getSchema().getName());
            statement.setString(2, routine.getName());
            statement.setString(3, packageName);
            statement.setString(4, overload == null ? null : overload.toString());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    int position = resultSet.getInt(2);
                    String inOut = resultSet.getString(3);
                    String dataType = resultSet.getString(4);
                    if (name == null && dataType == null) {
                        // The row ALL_ARGUMENTS has for a routine without arguments
                        continue;
                    }
                    Kind kind = kindOf(dataType);
                    String declaration = declarationOf(dataSource, kind, dataType, resultSet.getString(5), resultSet.getString(6),
                        resultSet.getString(7), name == null ? routine.getName() : name);
                    if (position == 0) {
                        declarations.append("  v_result ").append(declaration).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        resultAssignment = "v_result := "; //$NON-NLS-1$
                        addOutput(outputs, outputTexts, routine.getName(), "v_result", kind, dataType); //$NON-NLS-1$
                        continue;
                    }
                    boolean input = "IN".equals(inOut) || "IN/OUT".equals(inOut); //$NON-NLS-1$ //$NON-NLS-2$
                    boolean output = "OUT".equals(inOut) || "IN/OUT".equals(inOut); //$NON-NLS-1$ //$NON-NLS-2$
                    String value = null;
                    if (input) {
                        if (inputIndex >= values.size()) {
                            throw new DBGException("Argument value count (" + values.size() + ") does not match the routine's input parameters");
                        }
                        value = values.get(inputIndex++);
                        if (CommonUtils.isEmptyTrimmed(value)) {
                            throw new DBGException("Argument '" + name + "' was not set");
                        }
                    }
                    String argument = DBUtils.getQuotedIdentifier(dataSource, name) + " => "; //$NON-NLS-1$
                    if (output) {
                        String variable = "v_" + (++variableCount); //$NON-NLS-1$
                        declarations.append("  ").append(variable).append(' ').append(declaration); //$NON-NLS-1$
                        if (value != null) {
                            declarations.append(" := ").append(value); //$NON-NLS-1$
                        }
                        declarations.append(";\n"); //$NON-NLS-1$
                        arguments.add(argument + variable);
                        addOutput(outputs, outputTexts, name, variable, kind, dataType);
                    } else {
                        arguments.add(argument + value);
                    }
                }
            }
        }
        if (inputIndex != values.size()) {
            throw new DBGException("Argument value count (" + values.size() + ") does not match the routine's input parameters (" + inputIndex + ")");
        }

        StringBuilder block = new StringBuilder();
        if (!declarations.isEmpty()) {
            block.append("DECLARE\n").append(declarations); //$NON-NLS-1$
        }
        block.append("BEGIN\n  ").append(resultAssignment).append(qualifiedName(routine, packageName)); //$NON-NLS-1$
        if (!arguments.isEmpty()) {
            block.append('(').append(String.join(", ", arguments)).append(')'); //$NON-NLS-1$
        }
        block.append(";\n"); //$NON-NLS-1$
        for (String text : outputTexts) {
            block.append("  ? := ").append(text).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        block.append("END;"); //$NON-NLS-1$
        return new OracleDebugCall(block.toString(), outputs);
    }

    private static void addOutput(
        @NotNull List<Output> outputs,
        @NotNull List<String> outputTexts,
        @NotNull String column,
        @NotNull String variable,
        @NotNull Kind kind,
        @Nullable String dataType
    ) {
        switch (kind) {
            case SCALAR -> {
                outputs.add(new Output(column, null));
                outputTexts.add(variable);
            }
            case BOOLEAN -> {
                outputs.add(new Output(column, null));
                outputTexts.add("CASE WHEN " + variable + " THEN 'TRUE' WHEN NOT " + variable + " THEN 'FALSE' END"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            case OPAQUE -> outputs.add(new Output(column, "(" + dataType + ")")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @NotNull
    private static Kind kindOf(@Nullable String dataType) {
        if (dataType == null) {
            return Kind.OPAQUE;
        }
        if (dataType.equals("BOOLEAN") || dataType.equals("PL/SQL BOOLEAN")) { //$NON-NLS-1$ //$NON-NLS-2$
            return Kind.BOOLEAN;
        }
        if (SCALAR_TYPES.contains(dataType) || dataType.startsWith("TIMESTAMP") || dataType.startsWith("INTERVAL")) { //$NON-NLS-1$ //$NON-NLS-2$
            return Kind.SCALAR;
        }
        return Kind.OPAQUE;
    }

    @NotNull
    private static String declarationOf(
        @NotNull OracleDataSource dataSource,
        @NotNull Kind kind,
        @Nullable String dataType,
        @Nullable String typeOwner,
        @Nullable String typeName,
        @Nullable String typeSubname,
        @NotNull String argumentName
    ) throws DBGException {
        switch (kind) {
            case SCALAR:
                return SCALAR_DECLARATION;
            case BOOLEAN:
                return "BOOLEAN"; //$NON-NLS-1$
            default:
                if (typeName != null) {
                    StringBuilder declaration = new StringBuilder();
                    if (typeOwner != null) {
                        declaration.append(DBUtils.getQuotedIdentifier(dataSource, typeOwner)).append('.');
                    }
                    declaration.append(DBUtils.getQuotedIdentifier(dataSource, typeName));
                    if (typeSubname != null) {
                        declaration.append('.').append(DBUtils.getQuotedIdentifier(dataSource, typeSubname));
                    }
                    return declaration.toString();
                }
                if (REF_CURSOR.equals(dataType)) {
                    return "SYS_REFCURSOR"; //$NON-NLS-1$
                }
                throw new DBGException("The debugger cannot pass argument " + argumentName + " of type " + dataType);
        }
    }

    @NotNull
    private static String qualifiedName(@NotNull OracleProcedureBase<?> routine, @Nullable String packageName) {
        OracleDataSource dataSource = routine.getDataSource();
        StringBuilder name = new StringBuilder(DBUtils.getQuotedIdentifier(dataSource, routine.getSchema().getName())).append('.');
        if (packageName != null) {
            name.append(DBUtils.getQuotedIdentifier(dataSource, packageName)).append('.');
        }
        return name.append(DBUtils.getQuotedIdentifier(dataSource, routine.getName())).toString();
    }
}
