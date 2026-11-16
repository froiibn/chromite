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
package com.seizia.chromite.model.admin.oracle;

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataType;
import com.seizia.chromite.ext.oracle.model.OracleMaterializedView;
import com.seizia.chromite.ext.oracle.model.OraclePackage;
import com.seizia.chromite.ext.oracle.model.OracleProcedureStandalone;
import com.seizia.chromite.ext.oracle.model.OracleTrigger;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.ext.oracle.model.OracleView;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle compilation errors ({@code ALL_ERRORS}) of stored PL/SQL units and views, and where their lines are in a script.
 *
 * 설계: 컴파일 오류는 예외가 아니라 경고(ORA-17110 "execution completed with warning")로 오고, 줄·위치·메시지는 {@code ALL_ERRORS}에만 있다.
 * SWT는 저장·컴파일 뒤 이 뷰를 읽어(SWT {@code OracleTaskHandler.logObjectErrors}, 모델 {@code OracleObjectValidateAction}의 주석 처리된 코드)
 * 편집기의 오류 줄로 옮겼다. Swing에는 객체 편집기가 없으므로 쿼리 창(Modify, 직접 쓴 CREATE)과 탐색기의 Compile 명령이 이 클래스로 오류를 읽고,
 * 스크립트 안의 그 단위가 시작하는 곳을 찾아 오류 줄을 편집기 줄로 바꾼다.
 * <ul>
 *     <li>줄 번호의 기준(23ai에서 확인): 저장 프로시저·함수·패키지·형식은 형식 키워드({@code PROCEDURE p …})가 있는 줄이 1이다
 *     ({@code CREATE OR REPLACE}가 앞 줄에 있어도). 트리거는 트리거 본문의 PL/SQL 블록({@code DECLARE} 또는 {@code BEGIN})이 있는 줄이 1이다.
 *     1번 줄의 위치는 저장된 원본 기준이므로 같은 줄 앞부분({@code CREATE OR REPLACE })의 길이를 더한다.</li>
 *     <li>CREATE 머리글은 정규식으로만 읽는다(SQL 구문 분석기를 쓰지 않음): 앞의 주석·공백, {@code OR REPLACE}, {@code [NO] FORCE},
 *     {@code EDITIONABLE}·{@code NONEDITIONABLE}·{@code EDITIONING}, 스키마가 붙은 이름과 따옴표 이름을 처리한다.
 *     따옴표 없는 이름은 Oracle처럼 대문자로 바꾸고, 스키마가 없으면 세션의 현재 스키마({@code CURRENT_SCHEMA})다.</li>
 *     <li>읽기만 하며 사용자의 세션에서 실행해도 트랜잭션을 시작하지 않는다(SELECT).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Modify 뒤 오류 줄 표시(O12), 탐색기 Compile의 오류 목록(O11) (docs/chromite/swing-routines.md)
 */
public final class OracleCompileErrors {

    /**
     * One row of {@code ALL_ERRORS}.
     *
     * @param line     line in the stored source (1-based, 0 when the error is not in a line)
     * @param position column in that line (1-based)
     * @param error    ERROR (true) or WARNING (false)
     */
    public record CompileError(
        @NotNull String owner,
        @NotNull String name,
        @NotNull String type,
        int line,
        int position,
        boolean error,
        @NotNull String text
    ) {
    }

    /**
     * A unit created by a CREATE statement: its dictionary type and name, and the text offset where its line 1 starts.
     *
     * @param owner       owner named in the statement, or null for the session's current schema
     * @param sourceStart offset (in the parsed text) of the first character of the unit's line 1
     */
    public record Unit(@Nullable String owner, @NotNull String name, @NotNull String type, int sourceStart) {
    }

    private static final String NAME = "(?:\"[^\"]+\"|[A-Za-z][A-Za-z0-9_$#]*)"; //$NON-NLS-1$

    /**
     * CREATE of a stored unit, anchored at the start of the text (after comments are skipped).
     */
    private static final Pattern CREATE_HEADER = Pattern.compile(
        "CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NO\\s+)?FORCE\\s+)?(?:(?:EDITIONABLE|NONEDITIONABLE|EDITIONING)\\s+)*" //$NON-NLS-1$
            + "(?<type>PACKAGE\\s+BODY|TYPE\\s+BODY|PACKAGE|TYPE|PROCEDURE|FUNCTION|TRIGGER|MATERIALIZED\\s+VIEW|VIEW)\\s+" //$NON-NLS-1$
            + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?<first>" + NAME + ")(?:\\s*\\.\\s*(?<second>" + NAME + "))?", //$NON-NLS-1$ //$NON-NLS-2$
        Pattern.CASE_INSENSITIVE);

    /**
     * The start of a trigger's PL/SQL block, which is line 1 of the trigger's errors.
     */
    private static final Pattern TRIGGER_BLOCK = Pattern.compile("\\b(?:DECLARE|BEGIN|COMPOUND\\s+TRIGGER)\\b", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private OracleCompileErrors() {
    }

    /**
     * The dictionary types ({@code ALL_ERRORS.TYPE}) of an object's units, or an empty list if it is not compiled code.
     */
    @NotNull
    public static List<String> getErrorTypes(@NotNull DBSObject object) {
        if (object instanceof OraclePackage) {
            return List.of("PACKAGE", "PACKAGE BODY"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (object instanceof OracleDataType) {
            return List.of("TYPE", "TYPE BODY"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (object instanceof OracleProcedureStandalone procedure) {
            return List.of(procedure.getProcedureType() == DBSProcedureType.FUNCTION ? "FUNCTION" : "PROCEDURE"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (object instanceof OracleTrigger<?>) {
            return List.of("TRIGGER"); //$NON-NLS-1$
        } else if (object instanceof OracleMaterializedView) {
            return List.of("MATERIALIZED VIEW"); //$NON-NLS-1$
        } else if (object instanceof OracleView) {
            return List.of("VIEW"); //$NON-NLS-1$
        }
        return List.of();
    }

    /**
     * Reads the errors of a unit, in their order.
     *
     * @param types the unit's types (a package: spec and body)
     */
    @NotNull
    public static List<CompileError> read(
        @NotNull JDBCSession session,
        @NotNull String owner,
        @NotNull String name,
        @NotNull List<String> types
    ) throws SQLException {
        List<CompileError> errors = new ArrayList<>();
        if (types.isEmpty()) {
            return errors;
        }
        String prefix = session.getDataSource() instanceof OracleDataSource dataSource ? OracleUtils.getSysSchemaPrefix(dataSource) : ""; //$NON-NLS-1$
        String sql = "SELECT TYPE, LINE, POSITION, ATTRIBUTE, TEXT FROM " + prefix //$NON-NLS-1$
            + "ALL_ERRORS WHERE OWNER = ? AND NAME = ? AND TYPE IN (" //$NON-NLS-1$
            + String.join(", ", Collections.nCopies(types.size(), "?")) + ") ORDER BY TYPE, SEQUENCE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, name);
            for (int i = 0; i < types.size(); i++) {
                statement.setString(3 + i, types.get(i));
            }
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String text = resultSet.getString(5);
                    errors.add(new CompileError(owner, name, resultSet.getString(1), resultSet.getInt(2), resultSet.getInt(3),
                        !"WARNING".equals(resultSet.getString(4)), text == null ? "" : text.strip())); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return errors;
    }

    /**
     * The session's current schema (the owner of units created without one).
     */
    @NotNull
    public static String readCurrentSchema(@NotNull JDBCSession session) throws SQLException {
        try (JDBCPreparedStatement statement = session.prepareStatement("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) { //$NON-NLS-1$
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : ""; //$NON-NLS-1$
            }
        }
    }

    /**
     * The unit created by a CREATE statement, or null if the statement creates no stored code or view.
     */
    @Nullable
    public static Unit parseCreate(@NotNull String statement) {
        int start = skipComments(statement, 0);
        Matcher matcher = CREATE_HEADER.matcher(statement);
        matcher.region(start, statement.length());
        if (!matcher.lookingAt()) {
            return null;
        }
        String type = matcher.group("type").toUpperCase(Locale.ENGLISH).replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String first = matcher.group("first"); //$NON-NLS-1$
        String second = matcher.group("second"); //$NON-NLS-1$
        String owner = second == null ? null : dictionaryName(first);
        String name = dictionaryName(second == null ? first : second);
        int sourceStart = matcher.start("type"); //$NON-NLS-1$
        if ("TRIGGER".equals(type)) { //$NON-NLS-1$
            // Trigger errors count from the trigger's PL/SQL block
            Matcher block = TRIGGER_BLOCK.matcher(statement);
            if (block.find(matcher.end())) {
                sourceStart = block.start();
            }
        }
        return new Unit(owner, name, type, sourceStart);
    }

    /**
     * Where a unit's line 1 starts in a script of several statements (the Modify script of a package has the spec and
     * the body), or -1 if no CREATE statement of the unit is found.
     */
    public static int findSourceStart(@NotNull String script, @NotNull String name, @NotNull String type) {
        Matcher matcher = Pattern.compile("(?m)^[ \\t]*CREATE\\b", Pattern.CASE_INSENSITIVE).matcher(script); //$NON-NLS-1$
        while (matcher.find()) {
            Unit unit = parseCreate(script.substring(matcher.start()));
            if (unit != null && unit.name().equals(name) && unit.type().equals(type)) {
                return matcher.start() + unit.sourceStart();
            }
        }
        return -1;
    }

    /**
     * The 0-based text offset of an error in a text where the unit's line 1 starts at {@code sourceStart}.
     */
    public static int getErrorOffset(@NotNull String text, int sourceStart, @NotNull CompileError error) {
        int lineStart = sourceStart;
        if (error.line() > 1) {
            int line = 1;
            int index = sourceStart;
            while (line < error.line()) {
                int next = text.indexOf('\n', index);
                if (next < 0) {
                    return text.length();
                }
                index = next + 1;
                line++;
            }
            lineStart = index;
        }
        int offset = lineStart + Math.max(error.position() - 1, 0);
        // The column must stay in its line
        int lineEnd = text.indexOf('\n', lineStart);
        return Math.min(offset, lineEnd < 0 ? text.length() : lineEnd);
    }

    @NotNull
    private static String dictionaryName(@NotNull String name) {
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
            return name.substring(1, name.length() - 1);
        }
        return name.toUpperCase(Locale.ENGLISH);
    }

    /**
     * The offset after the white space and comments that start the text at {@code offset}.
     */
    private static int skipComments(@NotNull String text, int offset) {
        int index = offset;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
            } else if (text.startsWith("--", index)) { //$NON-NLS-1$
                int end = text.indexOf('\n', index);
                index = end < 0 ? text.length() : end + 1;
            } else if (text.startsWith("/*", index)) { //$NON-NLS-1$
                int end = text.indexOf("*/", index + 2); //$NON-NLS-1$
                index = end < 0 ? text.length() : end + 2;
            } else {
                break;
            }
        }
        return index;
    }
}
