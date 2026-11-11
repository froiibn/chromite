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
package com.seizia.chromite.ext.oracle.sql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL*Plus client commands that Oracle scripts carry but the server does not accept ({@code SET SERVEROUTPUT ON},
 * {@code SET DEFINE OFF}, {@code PROMPT}, {@code EXEC}, {@code SPOOL}): which lines are such commands and what they ask.
 *
 * 설계:
 * <ul>
 *     <li>문장 나누기({@link OracleSqlPlusRule})와 실행({@link OracleSqlPlusCommandHandler})이 같은 판단을 쓰도록 한곳에 둔다.
 *     규칙이 명령으로 본 줄은 처리기가 반드시 알아본다.</li>
 *     <li>SQL*Plus처럼 명령은 한 줄이고 세미콜론이 없어도 된다. 그래서 토큰 규칙으로 줄 단위로 알아본다(upstream MySQL {@code SOURCE},
 *     Exasol {@code DEFINE}과 같은 방식). 명령 줄은 SQL 문장 중간에 오면 문장을 끊으므로, SQL과 겹치지 않는 모양만 받는다:
 *     {@code SET}은 SQL*Plus 시스템 변수 이름(줄임 포함) 뒤에 {@code =}가 오지 않을 때만(UPDATE의 {@code SET col = …},
 *     {@code ALTER SESSION SET …}과 구별), {@code EXEC}는 뒤가 {@code IMMEDIATE}(PL/SQL)나 {@code ON}(GRANT)이 아닐 때만,
 *     {@code PROMPT}는 뒤가 {@code , . )}로 시작하지 않을 때만(열 이름 prompt). 앞 줄이 {@code ,}나 {@code (}로 끝나면 문장이
 *     이어지는 줄이므로 규칙({@link OracleSqlPlusRule})이 명령으로 보지 않는다.</li>
 *     <li>{@code SET AUTOCOMMIT}은 받지 않는다. 무시하면 사용자가 자동 커밋이라 믿는 동안 트랜잭션이 열려 있게 되고, 바꾸면 묻지 않고
 *     커밋 방식을 바꾸게 된다(트랜잭션 안전 규칙). 서버로 가서 지금처럼 오류가 난다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: Oracle 쿼리 창의 SQL*Plus 명령 (앱 변경 #46, docs/chromite/oracle-sqlplus-commands.md)
 */
public final class OracleSqlPlus {

    /**
     * What a SQL*Plus command line asks for.
     */
    public enum Kind {
        /** {@code SET SERVEROUTPUT ON|OFF [SIZE n|UNLIMITED] [FORMAT …]} */
        SERVER_OUTPUT,
        /** {@code SET DEFINE ON|OFF|c}, {@code SET SCAN ON|OFF} */
        DEFINE,
        /** Other {@code SET} options: display settings of the SQL*Plus client */
        DISPLAY_SETTING,
        /** {@code PROMPT [text]} */
        PROMPT,
        /** {@code EXEC[UTE] call} */
        EXECUTE,
        /** {@code SPOOL [file|OFF|OUT]} */
        SPOOL
    }

    /**
     * A recognized command line.
     *
     * @param kind     what it asks for
     * @param name     the option of a {@code SET} line in full ({@code SERVEROUTPUT}), else the command in full ({@code PROMPT})
     * @param argument the rest of the line (without a trailing {@code ;}, except for {@code PROMPT}), may be empty
     */
    public record Command(@NotNull Kind kind, @NotNull String name, @NotNull String argument) {
    }

    /**
     * SQL*Plus system variables (full name, shortest abbreviation SQL*Plus accepts), all but {@code AUTOCOMMIT} (see class
     * doc).
     */
    private static final Object[][] SET_OPTIONS = {
        {"SERVEROUTPUT", 9}, {"DEFINE", 3}, {"SCAN", 4},
        {"ECHO", 4}, {"FEEDBACK", 4}, {"TIMING", 4}, {"LINESIZE", 3}, {"PAGESIZE", 5}, {"VERIFY", 3}, {"HEADING", 3},
        {"TERMOUT", 4}, {"TRIMSPOOL", 5}, {"TRIMOUT", 4}, {"LONG", 4}, {"LONGCHUNKSIZE", 5}, {"ARRAYSIZE", 5},
        {"SQLBLANKLINES", 5}, {"WRAP", 3}, {"NEWPAGE", 4}, {"COLSEP", 6}, {"NUMWIDTH", 4}, {"NUMFORMAT", 4}, {"TAB", 3},
        {"ESCAPE", 3}, {"CONCAT", 3}, {"TIME", 2}, {"UNDERLINE", 3}, {"NULL", 4}, {"EMBEDDED", 3}, {"MARKUP", 4},
        {"SQLPROMPT", 4}, {"HEADSEP", 5}, {"PAUSE", 3}, {"AUTOTRACE", 5}, {"AUTOPRINT", 5}, {"APPINFO", 4},
        {"SHOWMODE", 4}, {"RECSEP", 6}, {"RECSEPCHAR", 10}, {"DESCRIBE", 5}, {"FLUSH", 3}, {"COPYCOMMIT", 5},
        {"LOBOFFSET", 5}, {"SQLCASE", 4}, {"SQLTERMINATOR", 4}, {"SUFFIX", 3}, {"EXITCOMMIT", 5}
    };

    // First word of the line and the rest: the command word is letters only, as in SQL*Plus
    private static final Pattern COMMAND_WORD = Pattern.compile("([A-Za-z]+)(?:\\s+(.*))?", Pattern.DOTALL);
    // A SET option is a word that may contain '_' (so that TIME_ZONE is not taken for TIME)
    private static final Pattern SET_OPTION = Pattern.compile("([A-Za-z_]+)(?:\\s+(.*))?", Pattern.DOTALL);

    private OracleSqlPlus() {
    }

    /**
     * The command a line asks for, or null if the line is not a SQL*Plus command this connection handles on the client.
     *
     * @param line one line of the script, without its line break
     */
    @Nullable
    public static Command parse(@NotNull String line) {
        Matcher matcher = COMMAND_WORD.matcher(line.strip());
        if (!matcher.matches()) {
            return null;
        }
        String word = matcher.group(1).toUpperCase(Locale.ENGLISH);
        String rest = matcher.group(2) == null ? "" : matcher.group(2).strip();
        if (word.equals("SET")) {
            return parseSet(stripSemicolon(rest));
        }
        if (isAbbreviation(word, "PROMPT", 3)) {
            // A column named prompt at the start of a select list line ("prompt, b", "prompt.x", "prompt)")
            if (!rest.isEmpty() && ",.)".indexOf(rest.charAt(0)) >= 0) {
                return null;
            }
            return new Command(Kind.PROMPT, "PROMPT", rest);
        }
        if (isAbbreviation(word, "EXECUTE", 4)) {
            String call = stripSemicolon(rest);
            String next = call.split("\\s+", 2)[0].toUpperCase(Locale.ENGLISH);
            // PL/SQL EXECUTE IMMEDIATE, GRANT EXECUTE ON
            if (call.isEmpty() || next.equals("IMMEDIATE") || next.equals("ON")) {
                return null;
            }
            return new Command(Kind.EXECUTE, "EXECUTE", call);
        }
        if (isAbbreviation(word, "SPOOL", 3)) {
            return new Command(Kind.SPOOL, "SPOOL", stripSemicolon(rest));
        }
        return null;
    }

    @Nullable
    private static Command parseSet(@NotNull String rest) {
        Matcher matcher = SET_OPTION.matcher(rest);
        if (!matcher.matches()) {
            return null;
        }
        String value = matcher.group(2) == null ? "" : matcher.group(2).strip();
        if (value.startsWith("=")) {
            // UPDATE ... SET col = value on its own line
            return null;
        }
        String option = matcher.group(1).toUpperCase(Locale.ENGLISH);
        for (Object[] known : SET_OPTIONS) {
            String name = (String) known[0];
            if (isAbbreviation(option, name, (Integer) known[1])) {
                Kind kind = switch (name) {
                    case "SERVEROUTPUT" -> Kind.SERVER_OUTPUT;
                    case "DEFINE", "SCAN" -> Kind.DEFINE;
                    default -> Kind.DISPLAY_SETTING;
                };
                return new Command(kind, name, value);
            }
        }
        return null;
    }

    /**
     * True if the word is the keyword or one of its abbreviations SQL*Plus accepts ({@code SERVEROUT} for
     * {@code SERVEROUTPUT}).
     */
    public static boolean isAbbreviation(@NotNull String word, @NotNull String keyword, int shortest) {
        String upper = word.toUpperCase(Locale.ENGLISH);
        return upper.length() >= shortest && keyword.startsWith(upper);
    }

    @NotNull
    private static String stripSemicolon(@NotNull String text) {
        String result = text.strip();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).strip();
        }
        return result;
    }
}
