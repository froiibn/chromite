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

import org.jkiss.code.NotNull;

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
 */
public final class ShardSphereStatementPrefix {

    private static final String NODE = "NODE"; //$NON-NLS-1$
    private static final String SHARD = "SHARD"; //$NON-NLS-1$
    private static final List<String> NODE_TARGETS_WITHOUT_NAMES = List.of("META", "ALL"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final String NODE_TARGET_DATA = "DATA"; //$NON-NLS-1$

    private ShardSphereStatementPrefix() {
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
