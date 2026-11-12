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
package com.seizia.chromite.model.sql.parser.rules;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.sql.parser.tokens.SQLDelimiterToken;
import com.seizia.chromite.model.text.parser.TPCharacterScanner;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPToken;
import com.seizia.chromite.model.text.parser.TPTokenAbstract;

/**
 * A line with only {@code /} (spaces after it allowed) ends the statement before it, as in Oracle SQL*Plus and Altibase
 * iSQL: a statement without a semicolon ({@code SELECT … FROM dual} then {@code /}) ends there, and the {@code /} after a
 * PL/SQL or PSM block becomes an empty statement that is dropped.
 *
 * 설계:
 * <ul>
 *     <li>방언의 문장 구분자({@code getScriptDelimiters})에 {@code /}를 넣으면 줄 가운데의 나눗셈({@code SELECT 4/2 FROM dual})에서도 문장이
 *     끊긴다. 그래서 줄 단위로, 줄 첫 칸의 {@code /} 뒤에 공백만 있을 때만 구분자 토큰을 돌려준다. 주석({@code /* … *}{@code /})이나
 *     나눗셈은 그대로 다른 규칙이 읽는다.</li>
 *     <li>평범한 문장 구분자 토큰이다(배치 구분자가 아님). 파서가 블록 안이라고 보는 동안의 구분자는 무시되므로(upstream 동작), 블록을 잘못 센
 *     경우까지 {@code /}가 강제로 끝내지는 않는다.</li>
 *     <li>Oracle({@code OracleDialectRules})과 Altibase({@code AltibaseDialectRules})가 같은 규칙을 쓴다. 방언 규칙의 CONTROL 위치에 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: Oracle SQL*Plus 규칙({@code OracleSqlPlusRule})의 {@code /} 줄 처리를 옮겨 Altibase와 함께 씀. Altibase가 문장 구분자의
 *   {@code /}로 {@code SELECT 4/2 AS q FROM dual}을 두 문장으로 나누던 것(앱 변경 #80, docs/chromite/oracle-sqlplus-commands.md "Altibase")
 */
public class SQLSlashLineRule implements TPRule {

    private final TPToken delimiterToken = new SQLDelimiterToken();

    @NotNull
    @Override
    public TPToken evaluate(@NotNull TPCharacterScanner scanner) {
        if (!isLineStart(scanner)) {
            return TPTokenAbstract.UNDEFINED;
        }
        int first = scanner.read();
        if (first != '/') {
            scanner.unread();
            return TPTokenAbstract.UNDEFINED;
        }
        int read = 1;
        boolean onlySpaces = true;
        for (int c = scanner.read(); ; c = scanner.read()) {
            read++;
            if (c == TPCharacterScanner.EOF || c == '\n' || c == '\r') {
                // The line break is not part of the token
                scanner.unread();
                read--;
                break;
            }
            onlySpaces &= Character.isWhitespace(c);
        }
        if (onlySpaces) {
            return delimiterToken;
        }
        for (int i = 0; i < read; i++) {
            scanner.unread();
        }
        return TPTokenAbstract.UNDEFINED;
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
