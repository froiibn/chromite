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
package com.seizia.chromite.model.ai.tools;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a statement written by the model is provably read-only.
 *
 * 설계(docs/chromite/ai-agent.md "읽기 전용 보호"):
 * <ul>
 *     <li>"증명하지 못하면 쓰기"가 원칙이다. JSQLParser(DBeaver {@link SQLQuery})가 SELECT로 해석했고, 데이터를 바꾸는 부분
 *     ({@link SQLQuery#isMutatingStatement()}: 쓰기 CTE 포함), {@code INTO}(SQL Server SELECT INTO는 테이블을 만든다),
 *     {@code FOR UPDATE}가 없을 때만 읽기다. {@link SQLQuery#isModifying()}은 해석 실패(UNKNOWN)를 "바꾸지 않음"으로 보므로 쓰지 않는다.</li>
 *     <li>해석기가 모르는 진단 명령 중 읽기 전용으로 알려진 것({@code SHOW}, {@code DESCRIBE}, ANALYZE 없는 {@code EXPLAIN}, 읽기 DBCC,
 *     인수 없는 읽기 시스템 프로시저)은 이름으로 허용한다. 정규식은 문장 전체에 맞아야 해서 뒤에 다른 문장을 붙이면 맞지 않는다.</li>
 *     <li>읽기 도구는 이 분류에 더해 읽기 전용 트랜잭션과 롤백으로 실행한다({@link AgentSql.Mode#READ_ONLY}). 분류기가 틀려도 두 번째 겹이 막는다.
 *     단 "단일 연결" 연결(공유 세션)에서는 두 번째·세 번째 겹이 없어 이 분류가 유일한 보호다.</li>
 *     <li>글에 문장이 둘 이상이면(연결 방언의 스크립트 파서로 나눈 수) 읽기가 아니다. JSQLParser는 첫 문장만 읽고 구분자에서 멈추므로
 *     {@code SELECT 1; INSERT …}를 SELECT로 보았다. SQL Server는 읽기 전용 트랜잭션이 없어 격리 문맥에서도 {@code …; COMMIT}으로
 *     쓴 것이 남았다.</li>
 *     <li>위험 분류: {@code DROP}, {@code TRUNCATE}, WHERE 없는 {@code DELETE}/{@code UPDATE}({@link SQLQuery#isDropDangerous()},
 *     {@link SQLQuery#isDeleteUpdateDangerous()}), 그리고 되돌릴 수 없는 서버 명령({@code SHUTDOWN}, {@code KILL}, {@code RESTORE} 등).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-12 문장이 둘 이상인 글은 읽기가 아님(앱 변경 #91). 공유 세션에서는 이 분류만 읽기 도구를 지키는데 첫 문장만 보았다
 *   (docs/chromite/ai-agent.md "공유 세션에서 롤백에 기대는 호출")
 * - 2026-11-12 {@link #trimTrailingDelimiter}: 서버로 보내는 글의 끝 구분자를 쿼리 창의 파서 판단대로 남김(앱 변경 #87). 끝의 {@code ;}를 모두
 *   지워 Oracle PL/SQL 블록이 PLS-00103으로 실패하고 프로시저가 INVALID로 만들어졌으며, SQL Server MERGE가 10713으로 실패했다
 *   (docs/chromite/ai-agent.md "문장 끝의 구분자")
 */
public final class AgentSqlClassifier {

    private static final Pattern READ_ONLY_COMMAND = Pattern.compile(
        "(?is)\\s*(" +
            "SHOW\\s+[^;]+" +
            "|DESC(RIBE)?\\s+[\\w.`\"\\[\\]]+" +
            "|EXPLAIN\\s+(?!.*\\bANALY[SZ]E\\b)(FORMAT\\s*=\\s*\\w+\\s+)?SELECT\\b[^;]+" +
            "|DBCC\\s+(SHOW_STATISTICS|SQLPERF|INPUTBUFFER|OPENTRAN|USEROPTIONS|TRACESTATUS)\\b[^;]*" +
            "|EXEC(UTE)?\\s+(sys\\.|master\\.dbo\\.|dbo\\.)?(sp_who2?|sp_lock|sp_helpindex|sp_helptext|sp_help|sp_helpdb|sp_helpstats|sp_spaceused)\\b(\\s+N?'[^';]*')?" +
            "|(EXEC(UTE)?\\s+)?(sys\\.)?sp_readerrorlog\\b[^;]*" +
        ")\\s*;?\\s*");

    private static final Pattern DANGEROUS_COMMAND = Pattern.compile(
        "(?is)\\s*(TRUNCATE|SHUTDOWN|KILL|RESTORE|DROP|ALTER\\s+SYSTEM|ALTER\\s+DATABASE\\b.*\\bSET\\s+(OFFLINE|SINGLE_USER|EMERGENCY))\\b.*");

    private AgentSqlClassifier() {
    }

    /**
     * The verdict for one statement.
     *
     * @param readOnly provably read-only
     * @param type     parsed statement type (UNKNOWN when the parser failed)
     * @param reason   why it is not read-only (null when it is)
     */
    public record Verdict(boolean readOnly, @NotNull SQLQueryType type, @Nullable String reason) {
    }

    @NotNull
    public static Verdict classify(@Nullable DBPDataSource dataSource, @NotNull String sql) {
        String text = stripTrailingDelimiter(sql);
        // JSQLParser reads the first statement and stops at its delimiter: "SELECT 1; DELETE ..." parsed as a SELECT
        if (dataSource != null && QueryTools.splitScript(dataSource, text).size() > 1) {
            return new Verdict(false, SQLQueryType.UNKNOWN, "the text holds more than one statement");
        }
        if (READ_ONLY_COMMAND.matcher(text).matches()) {
            return new Verdict(true, SQLQueryType.SELECT, null);
        }
        SQLQuery query = new SQLQuery(dataSource, text);
        SQLQueryType type = query.getType();
        if (type != SQLQueryType.SELECT) {
            return new Verdict(false, type, type == SQLQueryType.UNKNOWN
                ? "the statement could not be parsed as a single SELECT" + parseError(query)
                : "the statement is " + type.name());
        }
        if (query.isMutatingStatement()) {
            return new Verdict(false, type, "the SELECT changes data (SELECT INTO, a data-modifying CTE or a locking clause)");
        }
        Statement statement = query.getStatement();
        if (statement instanceof PlainSelect plain) {
            if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
                return new Verdict(false, type, "SELECT ... INTO creates a table");
            }
            if (plain.getForMode() != null) {
                return new Verdict(false, type, "SELECT ... FOR " + plain.getForMode() + " takes write locks");
            }
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.matches("(?s).*\\bINTO\\s+(OUTFILE|DUMPFILE)\\b.*")) {
            return new Verdict(false, type, "SELECT ... INTO OUTFILE writes a file");
        }
        return new Verdict(true, type, null);
    }

    /**
     * The permission class of a statement for {@code runSql}: DANGEROUS for drops, truncates and unbounded deletes/updates,
     * READ for provably read-only statements, WRITE otherwise.
     */
    @NotNull
    public static AIToolAccess access(@Nullable DBPDataSource dataSource, @NotNull String sql) {
        String text = stripTrailingDelimiter(sql);
        if (DANGEROUS_COMMAND.matcher(text).matches()) {
            return AIToolAccess.DANGEROUS;
        }
        SQLQuery query = new SQLQuery(dataSource, text);
        if (query.isDropDangerous() || query.isDeleteUpdateDangerous()) {
            return AIToolAccess.DANGEROUS;
        }
        return classify(dataSource, text).readOnly() ? AIToolAccess.READ : AIToolAccess.WRITE;
    }

    @NotNull
    private static String parseError(@NotNull SQLQuery query) {
        Throwable error = query.getParseError();
        if (error == null || error.getMessage() == null) {
            return "";
        }
        String message = error.getMessage();
        int newline = message.indexOf('\n');
        return " (" + (newline > 0 ? message.substring(0, newline) : message) + ")";
    }

    /**
     * The statement text as it goes to the server: the trailing delimiter is dropped, except where the query window keeps
     * it. The dialect's script parser (the one that splits the query window's and runScript's statements) leaves the
     * delimiter on a statement that needs it, such as an Oracle PL/SQL block or body ({@code BEGIN ... END;},
     * {@code DECLARE ...}, {@code CREATE PROCEDURE ... END name;}) or a SQL Server {@code MERGE}, and drops it otherwise. The
     * trailing delimiter belongs to the text's last statement, so the parser's result for that one decides.
     */
    @NotNull
    static String trimTrailingDelimiter(@Nullable DBPDataSource dataSource, @NotNull String sql) {
        String text = stripTrailingDelimiter(sql);
        if (dataSource == null || text.length() == sql.strip().length()) {
            return text;
        }
        List<String> statements = QueryTools.splitScript(dataSource, sql);
        return !statements.isEmpty() && statements.getLast().endsWith(SQLConstants.DEFAULT_STATEMENT_DELIMITER)
            ? text + SQLConstants.DEFAULT_STATEMENT_DELIMITER
            : text;
    }

    @NotNull
    static String stripTrailingDelimiter(@NotNull String sql) {
        String text = sql.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }
}
