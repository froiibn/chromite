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
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryType;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.parser.tokens.SQLTokenType;
import com.seizia.chromite.model.text.parser.TPRuleBasedScanner;
import com.seizia.chromite.model.text.parser.TPToken;
import com.seizia.chromite.model.text.parser.TPTokenDefault;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *     <li>파서의 판정만으로는 증명이 아니다. 쿼리 창의 토크나이저(방언 규칙, 문자열·따옴표·대괄호 식별자·주석은 건너뜀)로 읽은 맨 단어 중
 *     첫 단어 뒤에 문장을 시작하는 단어({@link #STATEMENT_WORDS})가 있으면 읽기가 아니다. T-SQL은 문장 사이에 {@code ;}가 없어도 되고
 *     JSQLParser는 {@code DELETE}·{@code COMMIT}을 별칭으로 읽기 때문이다. 그런 이름의 열은 따옴표로 감싸야 통과한다(잘못된 거절은 받아들임).</li>
 *     <li>위험 분류: {@code DROP}, {@code TRUNCATE}, WHERE 없는 {@code DELETE}/{@code UPDATE}({@link SQLQuery#isDropDangerous()},
 *     {@link SQLQuery#isDeleteUpdateDangerous()}), 그리고 되돌릴 수 없는 서버 명령({@code SHUTDOWN}, {@code KILL}, {@code RESTORE} 등).
 *     글의 모든 문장(파서가 나눈 것)을 판정해 가장 위험한 것이 정한다. 한 부분 안에서도 위험 단어가 어디에 있든, 또는 첫 단어 뒤의
 *     DELETE/UPDATE(FOR·ON·THEN 뒤 제외, WHERE를 알 수 없음)는 DANGEROUS다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-12 문장이 둘 이상인 글은 읽기가 아님(앱 변경 #91). 공유 세션에서는 이 분류만 읽기 도구를 지키는데 첫 문장만 보았다
 *   (docs/chromite/ai-agent.md "공유 세션에서 롤백에 기대는 호출")
 * - 2026-11-12 {@link #trimTrailingDelimiter}: 서버로 보내는 글의 끝 구분자를 쿼리 창의 파서 판단대로 남김(앱 변경 #87). 끝의 {@code ;}를 모두
 *   지워 Oracle PL/SQL 블록이 PLS-00103으로 실패하고 프로시저가 INVALID로 만들어졌으며, SQL Server MERGE가 10713으로 실패했다
 *   (docs/chromite/ai-agent.md "문장 끝의 구분자")
 * - 2026-11-12 읽기 판정에 독립된 토큰 검사를 더하고, 위험 판정은 모든 문장 중 가장 위험한 것으로 함(앱 변경 #93). SQL Server는 문장 사이에
 *   {@code ;}가 없어도 되어 {@code SELECT 1 DELETE FROM t COMMIT}을 JSQLParser가 별칭 붙은 SELECT로 읽었고 runReadOnlySql이 지우고 커밋했다.
 *   {@code INSERT …; DROP …}은 첫 문장만 보아 WRITE였다 (docs/chromite/ai-agent.md "읽기 판정의 토큰 검사")
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

    /**
     * Words that start a statement that writes, changes the schema, ends or starts a transaction, calls code or changes the
     * session. T-SQL needs no delimiter between statements, and JSQLParser reads such a word as an alias
     * ({@code SELECT 1 DELETE FROM t COMMIT} is "SELECT 1 AS DELETE FROM t AS COMMIT"), so a bare one anywhere after the
     * first word means the text may hold another statement. Not REPLACE or COMMENT (common function and column names).
     */
    private static final Set<String> STATEMENT_WORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "TRUNCATE", "DROP", "CREATE", "ALTER", "RENAME", "GRANT", "REVOKE",
        "DENY", "EXEC", "EXECUTE", "CALL", "COMMIT", "ROLLBACK", "SAVE", "BEGIN", "SET", "USE", "DBCC", "KILL", "BACKUP",
        "RESTORE", "SHUTDOWN", "INTO", "DECLARE", "WAITFOR", "RECONFIGURE", "CHECKPOINT", "DISABLE", "ENABLE", "OPENROWSET",
        "LOCK", "COPY", "VACUUM", "ANALYZE", "REINDEX", "CLUSTER", "LOAD", "HANDLER", "PREPARE", "DEALLOCATE", "NOTIFY",
        "REFRESH", "RESET", "REVERT", "SETUSER", "PRAGMA", "ATTACH", "DETACH");

    /**
     * Words that make a part of runSql/runScript DANGEROUS wherever they stand (the regex only looked at the start).
     */
    private static final Set<String> DANGEROUS_WORDS = Set.of("DROP", "TRUNCATE", "SHUTDOWN", "KILL", "RESTORE");

    private AgentSqlClassifier() {
    }

    /**
     * The bare words of a text as the query window's tokenizer reads them (the dialect's rules from the script parser's
     * context): strings, quoted or bracketed identifiers and comments are skipped. Upper case, in order.
     */
    @NotNull
    static List<String> words(@Nullable DBPDataSource dataSource, @NotNull String text) {
        SQLParserContext context = dataSource != null
            ? SQLScriptBatches.createParserContext(dataSource, text)
            : SQLScriptParser.prepareSqlParserContext(null, BasicSQLDialect.INSTANCE,
                DBWorkbench.getPlatform().getPreferenceStore(), text);
        TPRuleBasedScanner scanner = context.getScanner();
        scanner.setRange(context.getDocument(), 0, text.length());
        List<String> words = new ArrayList<>();
        for (TPToken token = scanner.nextToken(); !token.isEOF(); token = scanner.nextToken()) {
            if (token.isWhitespace()) {
                continue;
            }
            SQLTokenType type = token instanceof TPTokenDefault typed && typed.getData() instanceof SQLTokenType t ? t : SQLTokenType.T_OTHER;
            if (type == SQLTokenType.T_STRING || type == SQLTokenType.T_QUOTED || type == SQLTokenType.T_COMMENT) {
                continue;
            }
            int start = scanner.getTokenOffset();
            int end = Math.min(text.length(), start + scanner.getTokenLength());
            if (start < 0 || start >= end) {
                continue;
            }
            String piece = text.substring(start, end);
            // A token may hold more than one word (rules that read up to a delimiter): split on what is not a word character
            for (String word : piece.split("[^\\p{L}\\p{N}_@#$]+")) {
                if (!word.isEmpty()) {
                    words.add(word.toUpperCase(Locale.ROOT));
                }
            }
        }
        return words;
    }

    /**
     * For a statement that may write but must stay one statement (the plan tools measure it in a transaction they roll
     * back, validateSql compiles it under NOEXEC/EXPLAIN): the first word after the first that can't belong to that one
     * statement, or null. A COMMIT, a SET NOEXEC/SHOWPLAN/IMPLICIT_TRANSACTIONS OFF or a second DML statement glued on
     * without ';' would otherwise run, or keep what the rollback should undo. DML words are allowed only where one
     * statement has them (MERGE ... THEN UPDATE/DELETE/INSERT, INSERT/MERGE INTO, UPDATE ... SET, FOR UPDATE).
     */
    @Nullable
    static String foreignStatementWord(@Nullable DBPDataSource dataSource, @NotNull String text) {
        List<String> words = words(dataSource, text);
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            String previous = words.get(i - 1);
            boolean allowed = switch (word) {
                case "INSERT", "DELETE" -> previous.equals("THEN");
                case "UPDATE" -> previous.equals("THEN") || previous.equals("FOR");
                case "INTO" -> previous.equals("INSERT") || previous.equals("MERGE");
                case "SET" -> {
                    // UPDATE t SET / UPDATE s.t AS x SET: the nearest UPDATE just before, with no other SET between
                    boolean found = false;
                    for (int j = i - 1; j >= Math.max(0, i - 6) && !words.get(j).equals("SET"); j--) {
                        if (words.get(j).equals("UPDATE")) {
                            found = true;
                            break;
                        }
                    }
                    yield found;
                }
                case "MERGE" -> false;
                default -> !STATEMENT_WORDS.contains(word) && !word.equals("GO") && !word.equals("NOEXEC") &&
                    !word.equals("PARSEONLY") && !word.startsWith("SHOWPLAN") && !word.equals("IMPLICIT_TRANSACTIONS");
            };
            if (!allowed) {
                return word;
            }
        }
        return null;
    }

    /**
     * The first statement word ({@link #STATEMENT_WORDS}) after the first word of the text, or null.
     */
    @Nullable
    private static String statementWordAfterFirst(@NotNull List<String> words) {
        for (int i = 1; i < words.size(); i++) {
            if (STATEMENT_WORDS.contains(words.get(i))) {
                return words.get(i);
            }
        }
        return null;
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
        // The parser's verdict alone is not proof: an independent token scan must agree (T-SQL statements need no ';')
        String word = statementWordAfterFirst(words(dataSource, text));
        if (word != null) {
            return new Verdict(false, SQLQueryType.UNKNOWN, "the text has the bare word " + word + " after its first word, which " +
                "may start another statement (SQL Server needs no ';' between statements). If it is a column or alias name, quote " +
                "the identifier; otherwise run it with runSql");
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
     * READ for provably read-only statements, WRITE otherwise. Every statement of the text is rated and the most dangerous
     * one decides: the parts the script parser finds, and inside a part (T-SQL needs no ';') a dangerous word anywhere, or
     * a DELETE/UPDATE after the first word whose WHERE can't be told.
     */
    @NotNull
    public static AIToolAccess access(@Nullable DBPDataSource dataSource, @NotNull String sql) {
        String text = stripTrailingDelimiter(sql);
        List<String> parts = dataSource == null ? List.of(text) : QueryTools.splitScript(dataSource, text);
        if (parts.size() > 1) {
            AIToolAccess access = AIToolAccess.WRITE;
            for (String part : parts) {
                access = access.max(access(dataSource, part));
            }
            return access;
        }
        if (DANGEROUS_COMMAND.matcher(text).matches()) {
            return AIToolAccess.DANGEROUS;
        }
        List<String> words = words(dataSource, text);
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String previous = i == 0 ? "" : words.get(i - 1);
            if (DANGEROUS_WORDS.contains(word)) {
                return AIToolAccess.DANGEROUS;
            }
            // Not FOR UPDATE, ON UPDATE/DELETE (foreign keys), MERGE ... THEN UPDATE/DELETE
            if (i > 0 && (word.equals("DELETE") || word.equals("UPDATE")) && !previous.equals("FOR") && !previous.equals("ON")
                && !previous.equals("THEN")) {
                return AIToolAccess.DANGEROUS;
            }
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
