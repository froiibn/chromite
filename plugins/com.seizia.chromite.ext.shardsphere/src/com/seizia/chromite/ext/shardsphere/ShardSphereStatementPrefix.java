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
package com.seizia.chromite.ext.shardsphere;

import com.seizia.chromite.model.sql.SQLDialect;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statement prefixes of a ShardSphere sharding server: {@code NODE[meta]}, {@code NODE[data]},
 * {@code NODE[data('n1', 'n2')]}, {@code NODE[all]} and {@code SHARD}.
 *
 * 설계: 접두어는 문장의 일부가 아니라 "어디서 실행할지"다. Chromite는 접두어를 뺀 본문으로 문장의 종류를 정하고(위험한 문 확인, 읽기 전용,
 * 트랜잭션), 서버에는 글을 그대로 보낸다({@code SQLDialect#getStatementPrefixLength}). 규칙은 서버가 받는 것과 같다(2026-11-17 샤딩 메타
 * 노드에서 확인한 형태, docs/chromite/shardsphere.md "문장 접두어"): 앞의 공백·주석, {@code NODE}, 공백, {@code [}, 공백·줄바꿈,
 * {@code meta} | {@code all} | {@code data} | {@code data(이름, …)}, 공백, {@code ]}; 이름은 작은따옴표·큰따옴표·맨 이름, 대소문자 무시.
 * {@code SHARD}는 뒤에 공백이나 주석이 오는 따로 선 낱말이다. 문장 맨 앞에서만 본다: 문장 가운데의 {@code node[1]}, {@code CREATE SHARD},
 * {@code ALTER TABLE … SHARD BY}, {@code FROM NODE[data](…)}는 접두어가 아니다. 서버가 거절하는 접두어 둘({@code NODE[data] SHARD …})도
 * 이어서 뗀다: 떼어 낸 쪽이 본문의 종류를 보여 주므로 안전한 쪽이다(서버는 어차피 거절한다). 접두어가 아닌 글은 0이다(오늘과 같은 판단).
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 접두어 인식(앱 변경 #139 작업 6a, docs/chromite/shardsphere.md)
 * - 2026-11-17 하위 질의 형태({@link #getSubqueryForm})와 입력 중인 접두어의 제안({@link #getProposals}). 메타 노드가 받는 형태는
 *   {@code FROM NODE[x](SELECT …)}·{@code FROM SHARD (SELECT …)}이고 {@code FROM (NODE[x] SELECT …)}는 거절한다(조사와 이 작업의 JDBC 확인).
 *   서버가 받지 않는 접두어({@code NODE[all]}, 접두어 둘)와 SELECT가 아닌 본문은 형태가 없다(앱 변경 #139 작업 6b,
 *   docs/chromite/shardsphere.md "접두어 문장 다시 읽기")
 */
public final class ShardSphereStatementPrefix {

    private static final String NODE = "NODE"; //$NON-NLS-1$
    private static final String SHARD = "SHARD"; //$NON-NLS-1$
    private static final List<String> NODE_TARGETS_WITHOUT_NAMES = List.of("META", "ALL"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final String NODE_TARGET_DATA = "DATA"; //$NON-NLS-1$
    private static final String NODE_TARGET_ALL = "ALL"; //$NON-NLS-1$
    // What may follow "NODE[": every target of the prefix (NODE[all] is valid before DML, the server rejects it before SELECT)
    private static final List<String> NODE_TARGET_PROPOSALS = List.of("meta", "data", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String SELECT = "SELECT"; //$NON-NLS-1$
    // A line break before the closing parenthesis ends a line comment at the end of the statement
    private static final String SUBQUERY_OPEN = "(\n"; //$NON-NLS-1$
    private static final String SUBQUERY_CLOSE = "\n)"; //$NON-NLS-1$

    private ShardSphereStatementPrefix() {
    }

    /**
     * The derived table that reads a prefixed SELECT on the nodes of its prefix, for the rewrites that put a query in a
     * subquery (row count, filters, order): {@code NODE[x](body)} or {@code SHARD (body)}, the forms the sharding meta node
     * accepts; the statement in parentheses with its prefix is rejected. The body keeps its own ORDER BY and LIMIT (they
     * apply per node, as the user wrote them); a trailing {@code ;} and the comments before the prefix are left out.
     *
     * @return null when the text has no prefix, has two (rejected by the server), has {@code NODE[all]} (rejected before
     * a SELECT) or its body is not a SELECT
     */
    @Nullable
    public static String getSubqueryForm(@NotNull String text) {
        int end = prefixEnd(text, 0);
        if (end < 0 || getLength(text) != end) {
            return null;
        }
        int start = skipSpaceAndComments(text, 0);
        boolean shard = text.substring(start, wordEnd(text, start)).equalsIgnoreCase(SHARD);
        if (!shard && nodeTarget(text, start).equals(NODE_TARGET_ALL)) {
            return null;
        }
        String body = stripTrailingDelimiters(text.substring(end));
        if (!isSelect(body)) {
            return null;
        }
        return text.substring(start, end) + (shard ? " " : "") + SUBQUERY_OPEN + body + SUBQUERY_CLOSE;
    }

    /**
     * Proposals for a {@code NODE[...]} prefix being typed: after {@code NODE[} its targets, inside {@code data(} the
     * given node names (quoted). The targets follow the case of the typed {@code NODE}.
     *
     * @param text      a statement from its start to the caret
     * @param nodeNames the node names of the connection's meta node, empty for another server
     * @return null when the caret is not in the brackets of a {@code NODE} prefix (or in {@code data(} without names)
     */
    @Nullable
    public static SQLDialect.StatementPrefixProposals getProposals(@NotNull String text, @NotNull List<String> nodeNames) {
        int i = skipSpaceAndComments(text, 0);
        int nodeEnd = wordEnd(text, i);
        String node = text.substring(i, nodeEnd);
        if (!node.equalsIgnoreCase(NODE)) {
            return null;
        }
        int j = skipSpace(text, nodeEnd);
        if (j >= text.length() || text.charAt(j) != '[') {
            return null;
        }
        j = skipSpace(text, j + 1);
        int targetEnd = wordEnd(text, j);
        if (targetEnd == text.length()) {
            boolean upper = node.equals(node.toUpperCase(Locale.ENGLISH));
            List<String> words = new ArrayList<>();
            for (String target : NODE_TARGET_PROPOSALS) {
                words.add(upper ? target.toUpperCase(Locale.ENGLISH) : target);
            }
            return new SQLDialect.StatementPrefixProposals(j, words);
        }
        if (!text.substring(j, targetEnd).equalsIgnoreCase(NODE_TARGET_DATA)) {
            return null;
        }
        int k = skipSpace(text, targetEnd);
        if (k >= text.length() || text.charAt(k) != '(' || nodeNames.isEmpty()) {
            return null;
        }
        // The name being typed starts after the opening parenthesis or the last comma; a closed list is not completed
        int nameStart = k + 1;
        for (int m = k + 1; m < text.length(); m++) {
            char c = text.charAt(m);
            if (c == ')' || c == ']') {
                return null;
            }
            if (c == ',') {
                nameStart = m + 1;
            }
        }
        nameStart = skipSpace(text, nameStart);
        int typed = nameStart < text.length() && (text.charAt(nameStart) == '\'' || text.charAt(nameStart) == '"') ? nameStart + 1 : nameStart;
        if (wordEnd(text, typed) != text.length()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String name : nodeNames) {
            names.add('\'' + name.replace("'", "''") + '\''); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new SQLDialect.StatementPrefixProposals(nameStart, names);
    }

    /**
     * The target word of a {@code NODE[...]} prefix that starts at {@code nodeStart}, in upper case.
     */
    @NotNull
    private static String nodeTarget(@NotNull String text, int nodeStart) {
        int j = skipSpace(text, wordEnd(text, nodeStart));
        j = skipSpace(text, j + 1);
        return text.substring(j, wordEnd(text, j)).toUpperCase(Locale.ENGLISH);
    }

    /**
     * Whether the statement starts with SELECT, after whitespace, comments and opening parentheses.
     */
    private static boolean isSelect(@NotNull String body) {
        int i = skipSpaceAndComments(body, 0);
        while (i < body.length() && body.charAt(i) == '(') {
            i = skipSpaceAndComments(body, i + 1);
        }
        return body.substring(i, wordEnd(body, i)).equalsIgnoreCase(SELECT);
    }

    @NotNull
    private static String stripTrailingDelimiters(@NotNull String body) {
        int end = body.length();
        while (end > 0 && (Character.isWhitespace(body.charAt(end - 1)) || body.charAt(end - 1) == ';')) {
            end--;
        }
        return body.substring(0, end);
    }

    /**
     * The length of the prefixes at the start of the text, with the whitespace and comments before them; 0 if the text
     * does not start with a prefix. The rest of the text is the statement proper.
     */
    public static int getLength(@NotNull String text) {
        int length = 0;
        for (;;) {
            int end = prefixEnd(text, length);
            if (end <= length) {
                return length;
            }
            length = end;
        }
    }

    /**
     * The end of one prefix that starts after the whitespace and comments at {@code from}, or -1.
     */
    private static int prefixEnd(@NotNull String text, int from) {
        int i = skipSpaceAndComments(text, from);
        int wordEnd = wordEnd(text, i);
        String word = text.substring(i, wordEnd).toUpperCase(Locale.ENGLISH);
        if (word.equals(SHARD)) {
            // A separate word followed by whitespace or a comment, and a statement after it
            return wordEnd < text.length() && isSpaceOrCommentStart(text, wordEnd) && skipSpaceAndComments(text, wordEnd) < text.length()
                ? wordEnd : -1;
        }
        if (!word.equals(NODE)) {
            return -1;
        }
        int j = skipSpace(text, wordEnd);
        if (j >= text.length() || text.charAt(j) != '[') {
            return -1;
        }
        j = skipSpace(text, j + 1);
        int targetEnd = wordEnd(text, j);
        String target = text.substring(j, targetEnd).toUpperCase(Locale.ENGLISH);
        j = targetEnd;
        if (target.equals(NODE_TARGET_DATA)) {
            int k = skipSpace(text, j);
            if (k < text.length() && text.charAt(k) == '(') {
                j = namesEnd(text, k + 1);
                if (j < 0) {
                    return -1;
                }
            }
        } else if (!NODE_TARGETS_WITHOUT_NAMES.contains(target)) {
            return -1;
        }
        j = skipSpace(text, j);
        return j < text.length() && text.charAt(j) == ']' ? j + 1 : -1;
    }

    /**
     * After {@code data(}: names separated by commas, or none, then {@code )}. Returns the index after {@code )}, or -1.
     */
    private static int namesEnd(@NotNull String text, int from) {
        int i = skipSpace(text, from);
        if (i < text.length() && text.charAt(i) == ')') {
            return i + 1;
        }
        for (;;) {
            i = nameEnd(text, i);
            if (i < 0) {
                return -1;
            }
            i = skipSpace(text, i);
            if (i >= text.length()) {
                return -1;
            }
            char c = text.charAt(i);
            if (c == ')') {
                return i + 1;
            }
            if (c != ',') {
                return -1;
            }
            i = skipSpace(text, i + 1);
        }
    }

    private static int nameEnd(@NotNull String text, int i) {
        if (i >= text.length()) {
            return -1;
        }
        char c = text.charAt(i);
        if (c == '\'' || c == '"') {
            for (int k = i + 1; k < text.length(); k++) {
                if (text.charAt(k) == c) {
                    if (c == '\'' && k + 1 < text.length() && text.charAt(k + 1) == '\'') {
                        k++;
                        continue;
                    }
                    return k > i + 1 ? k + 1 : -1;
                }
            }
            return -1;
        }
        int end = wordEnd(text, i);
        return end > i ? end : -1;
    }

    private static int wordEnd(@NotNull String text, int i) {
        int k = i;
        while (k < text.length() && isWordChar(text.charAt(k))) {
            k++;
        }
        return k;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static int skipSpace(@NotNull String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isSpaceOrCommentStart(@NotNull String text, int i) {
        return Character.isWhitespace(text.charAt(i)) || text.startsWith("--", i) || text.startsWith("/*", i); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int skipSpaceAndComments(@NotNull String text, int i) {
        for (;;) {
            i = skipSpace(text, i);
            if (text.startsWith("--", i)) { //$NON-NLS-1$
                int eol = text.indexOf('\n', i);
                i = eol < 0 ? text.length() : eol + 1;
            } else if (text.startsWith("/*", i)) { //$NON-NLS-1$
                int end = text.indexOf("*/", i + 2); //$NON-NLS-1$
                i = end < 0 ? text.length() : end + 2;
            } else {
                return i;
            }
        }
    }
}
