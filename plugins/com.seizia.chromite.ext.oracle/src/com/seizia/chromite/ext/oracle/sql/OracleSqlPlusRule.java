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
import com.seizia.chromite.model.sql.parser.rules.SQLSlashLineRule;
import com.seizia.chromite.model.sql.parser.tokens.SQLControlToken;
import com.seizia.chromite.model.text.parser.TPCharacterScanner;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPToken;
import com.seizia.chromite.model.text.parser.TPTokenAbstract;

/**
 * SQL*Plus command lines of an Oracle script, which belong to the client, not to the server: such a line becomes a
 * control token (run on the client by {@link OracleSqlPlusCommandHandler}). A line with only {@code /} is
 * {@link SQLSlashLineRule}'s, next to this rule in {@link OracleDialectRules}.
 *
 * 설계:
 * <ul>
 *     <li>upstream의 {@code SQLFullLineRule}(MySQL {@code SOURCE})처럼 줄 첫 칸에서만 알아본다. SQL*Plus는 앞에 공백이 있어도 받지만,
 *     PL/SQL 본문은 보통 들여 쓰므로 들여 쓴 {@code EXECUTE IMMEDIATE} 같은 줄을 명령으로 오인하지 않는다.</li>
 *     <li>규칙은 토큰 위치마다 불리므로, 줄 첫 칸이 아니거나 첫 글자가 명령의 첫 글자가 아니면 줄을 읽지 않고 바로 돌려준다.
 *     줄을 읽은 뒤 명령이 아니면({@link OracleSqlPlus#parse}) 읽은 글자를 모두 되돌린다.</li>
 *     <li>앞 줄이 {@code ,}나 {@code (}로 끝나면 문장이 이어지는 줄이라 명령으로 보지 않는다({@code SELECT a,} 다음 줄의 열 이름
 *     {@code prompt}). 명령 모양인 줄에서만 앞을 거꾸로 읽으므로 비용이 작다.</li>
 *     <li>{@code /}만 있는 줄(SQL*Plus에서 버퍼를 실행하는 줄)은 Altibase iSQL에도 있어서 두 방언이 함께 쓰는 {@link SQLSlashLineRule}이
 *     맡는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: Oracle 쿼리 창의 SQL*Plus 명령 (앱 변경 #46, docs/chromite/oracle-sqlplus-commands.md)
 * - 2026-11-12 {@code /}만 있는 줄의 처리를 {@link SQLSlashLineRule}로 옮김: Altibase도 같은 규칙을 쓴다(앱 변경 #80)
 */
class OracleSqlPlusRule implements TPRule {

    /**
     * Command id of the control tokens, registered with the handler in plugin.xml ({@code com.seizia.chromite.sqlCommand}).
     */
    static final String COMMAND_ID = "oracle.sqlplus"; //$NON-NLS-1$

    private final TPToken commandToken = new SQLControlToken(COMMAND_ID);

    @NotNull
    @Override
    public TPToken evaluate(@NotNull TPCharacterScanner scanner) {
        if (!isLineStart(scanner)) {
            return TPTokenAbstract.UNDEFINED;
        }
        int first = scanner.read();
        if (first == TPCharacterScanner.EOF) {
            scanner.unread();
            return TPTokenAbstract.UNDEFINED;
        }
        // The first letters of SET, PROMPT, EXEC and SPOOL
        if ("SsPpEe".indexOf(first) < 0) {
            scanner.unread();
            return TPTokenAbstract.UNDEFINED;
        }
        StringBuilder line = new StringBuilder().append((char) first);
        int read = 1;
        for (int c = scanner.read(); ; c = scanner.read()) {
            read++;
            if (c == TPCharacterScanner.EOF || c == '\n' || c == '\r') {
                // The line break is not part of the token
                scanner.unread();
                read--;
                break;
            }
            line.append((char) c);
        }
        if (OracleSqlPlus.parse(line.toString()) != null && !continuesStatement(scanner, read)) {
            return commandToken;
        }
        for (int i = 0; i < read; i++) {
            scanner.unread();
        }
        return TPTokenAbstract.UNDEFINED;
    }

    /**
     * True if the text before the line (spaces and line breaks skipped) ends with {@code ,} or {@code (}: the line goes
     * on with a statement (a column named prompt on its own line of a select list), so it is not a command. The scanner
     * is left where it was.
     *
     * @param lineLength characters of the line the scanner has just read
     */
    private static boolean continuesStatement(@NotNull TPCharacterScanner scanner, int lineLength) {
        for (int i = 0; i < lineLength; i++) {
            scanner.unread();
        }
        int back = 0;
        int previous = TPCharacterScanner.EOF;
        while (scanner.getOffset() > 0) {
            scanner.unread();
            back++;
            int c = scanner.read();
            scanner.unread();
            if (!Character.isWhitespace(c)) {
                previous = c;
                break;
            }
        }
        for (int i = 0; i < back + lineLength; i++) {
            scanner.read();
        }
        return previous == ',' || previous == '(';
    }

    private static boolean isLineStart(@NotNull TPCharacterScanner scanner) {
        if (scanner.getOffset() == 0) {
            return true;
        }
        scanner.unread();
        int previous = scanner.read();
        return previous == TPCharacterScanner.EOF || previous == '\n' || previous == '\r';
    }
}
