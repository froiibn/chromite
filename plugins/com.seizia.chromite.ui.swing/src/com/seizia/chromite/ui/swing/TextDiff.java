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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.model.sql.Diff;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 두 글의 줄 비교 결과: 좌우를 맞춘 행, 차이 묶음, 바뀐 줄 안의 다른 글자 범위. 화면 없는 계산이라 EDT 밖에서 만든다({@link TextDiffView}).
 *
 * 설계:
 * <ul>
 *     <li>줄 비교는 모델의 Myers O(ND) {@link Diff} 하나를 쓴다. 스키마 비교 화면에 있던 줄 단위 최장 공통 부분열(LCS)은 줄 수의 곱만큼 표를 만들어
 *     (2만 줄이면 4억 칸) 4백만 칸을 넘으면 같은 위치끼리 비교했으므로 지웠다. Myers는 차이 수(D)에 비례하고 메모리는 줄 수에 비례한다.</li>
 *     <li>줄은 정규화한 글(옵션: 공백, 대소문자)을 정수 번호로 바꿔 비교한다. 한쪽에만 있는 줄은 짝이 될 수 없으므로 비교 전에 뺀다
 *     (전혀 다른 두 글은 Myers에 들어가는 줄이 없어 바로 끝난다). 짝지은 줄 사이(틈)의 줄은 차례로 짝지어 "바뀐 줄", 남는 줄은 한쪽에만 있는 줄이다.</li>
 *     <li>줄 끝 문자(CRLF/LF/CR)와 마지막 줄 바꿈은 차이가 아니다(줄로 나눌 때 버림).</li>
 *     <li>바뀐 줄의 다른 부분은 낱말 단위(글자·숫자 묶음, 공백 묶음, 기호 하나)로 같은 {@link Diff}에 넣는다. SQL은 식별자·키워드 단위로 바뀌므로
 *     글자 단위보다 읽기 쉽다.</li>
 *     <li>크기 제한: 한쪽이 {@link #MAX_LINES}줄이나 {@link #MAX_CHARS}자를 넘으면 비교하지 않는다({@link #isTooLarge}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 텍스트 비교 탭과 스키마 비교가 함께 쓰는 줄 비교 (docs/chromite/text-compare.md)
 */
final class TextDiff {

    static final int MAX_LINES = 200_000;
    static final int MAX_CHARS = 20_000_000;
    /**
     * Above this many tokens in a changed line the line is marked as a whole, not word by word.
     */
    private static final int MAX_LINE_TOKENS = 5_000;

    /**
     * How spaces count: as they are, without the leading and trailing ones (the schema compare), or without those and with
     * runs of spaces as one (Ignore Whitespace).
     */
    enum Whitespace {
        EXACT, TRIM, IGNORE
    }

    record Options(@NotNull Whitespace whitespace, boolean ignoreCase, boolean ignoreBlankLines) {
        /**
         * The schema compare's rule: lines compared without their surrounding spaces.
         */
        static final Options SCHEMA = new Options(Whitespace.TRIM, false, false);

        boolean isAnySet() {
            return whitespace != Whitespace.EXACT || ignoreCase || ignoreBlankLines;
        }
    }

    enum Kind {
        /**
         * The same line on both sides.
         */
        EQUAL,
        /**
         * A blank line ignored by the options (on one side or both): no difference.
         */
        IGNORED,
        CHANGED,
        LEFT_ONLY,
        RIGHT_ONLY;

        boolean isDifference() {
            return this == CHANGED || this == LEFT_ONLY || this == RIGHT_ONLY;
        }
    }

    /**
     * A row of the side-by-side view: a line of each side, or -1 where that side has an empty filler row.
     */
    record Row(int left, int right, @NotNull Kind kind) {
    }

    /**
     * A run of rows with differences (the unit of Previous / Next Difference and of the count).
     */
    record Hunk(int firstRow, int lastRow) {
    }

    private final String[] leftLines;
    private final String[] rightLines;
    private final List<Row> rows = new ArrayList<>();
    private final List<Hunk> hunks = new ArrayList<>();
    /**
     * Per row of a changed pair: the differing column ranges of the left and right line, as start/end pairs.
     */
    private final Map<Integer, int[][]> marks = new HashMap<>();
    private final boolean tooLarge;
    private final boolean exactlyEqual;

    private TextDiff(@NotNull String[] leftLines, @NotNull String[] rightLines, boolean tooLarge) {
        this.leftLines = leftLines;
        this.rightLines = rightLines;
        this.tooLarge = tooLarge;
        this.exactlyEqual = !tooLarge && Arrays.equals(leftLines, rightLines);
    }

    /**
     * Compares two texts (call off the EDT for long texts).
     */
    @NotNull
    static TextDiff compute(@NotNull String left, @NotNull String right, @NotNull Options options) {
        String[] leftLines = splitLines(left);
        String[] rightLines = splitLines(right);
        boolean tooLarge = leftLines.length > MAX_LINES || rightLines.length > MAX_LINES
            || left.length() > MAX_CHARS || right.length() > MAX_CHARS;
        TextDiff diff = new TextDiff(leftLines, rightLines, tooLarge);
        if (!tooLarge) {
            diff.compare(options);
        }
        return diff;
    }

    /**
     * The lines of a text: any line terminator ends a line, and a terminator at the end does not start another line.
     */
    @NotNull
    static String[] splitLines(@NotNull String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        String[] lines = text.split("\\R", -1); //$NON-NLS-1$
        return lines.length > 1 && lines[lines.length - 1].isEmpty() ? Arrays.copyOf(lines, lines.length - 1) : lines;
    }

    @NotNull
    String[] getLeftLines() {
        return leftLines;
    }

    @NotNull
    String[] getRightLines() {
        return rightLines;
    }

    @NotNull
    List<Row> getRows() {
        return rows;
    }

    @NotNull
    List<Hunk> getHunks() {
        return hunks;
    }

    /**
     * The differing column ranges of a changed row ({left start/end pairs, right start/end pairs}), or null.
     */
    @Nullable
    int[][] getMarks(int row) {
        return marks.get(row);
    }

    boolean isTooLarge() {
        return tooLarge;
    }

    /**
     * The texts have the same lines even without the options (line terminators and a final one aside).
     */
    boolean isExactlyEqual() {
        return exactlyEqual;
    }

    //////////////////////////////////////////////////////////
    // Lines

    private void compare(@NotNull Options options) {
        // Each distinct normalized line gets a number; blank lines ignored by the options get none (-1) and never match
        Map<String, Integer> ids = new HashMap<>();
        int[] leftIds = toIds(leftLines, options, ids);
        int[] rightIds = toIds(rightLines, options, ids);
        boolean[] onRight = new boolean[ids.size()];
        boolean[] onLeft = new boolean[ids.size()];
        for (int id : rightIds) {
            if (id >= 0) {
                onRight[id] = true;
            }
        }
        for (int id : leftIds) {
            if (id >= 0) {
                onLeft[id] = true;
            }
        }
        // Only lines that occur on both sides can be matched: the rest goes to the gaps without costing the diff time
        int[] leftIndex = keep(leftIds, onRight);
        int[] rightIndex = keep(rightIds, onLeft);
        Integer[] a = new Integer[leftIndex.length];
        Integer[] b = new Integer[rightIndex.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = leftIds[leftIndex[i]];
        }
        for (int i = 0; i < b.length; i++) {
            b[i] = rightIds[rightIndex[i]];
        }
        int previousLeft = 0;
        int previousRight = 0;
        int aPosition = 0;
        int bPosition = 0;
        for (Diff.Range range : Diff.prepareDiff(a, b, Integer::equals)) {
            switch (range.operation) {
                case DELETE_A -> aPosition += range.length;
                case INSERT_B -> bPosition += range.length;
                case MATCH_AB -> {
                    for (int k = 0; k < range.length; k++) {
                        int left = leftIndex[aPosition + k];
                        int right = rightIndex[bPosition + k];
                        addGap(previousLeft, left, previousRight, right, leftIds, rightIds, options);
                        rows.add(new Row(left, right, Kind.EQUAL));
                        previousLeft = left + 1;
                        previousRight = right + 1;
                    }
                    aPosition += range.length;
                    bPosition += range.length;
                }
            }
        }
        addGap(previousLeft, leftLines.length, previousRight, rightLines.length, leftIds, rightIds, options);
        collectHunks();
        for (int row = 0; row < rows.size(); row++) {
            Row current = rows.get(row);
            if (current.kind() == Kind.CHANGED) {
                marks.put(row, markLine(leftLines[current.left()], rightLines[current.right()], options));
            }
        }
    }

    @NotNull
    private static int[] toIds(@NotNull String[] lines, @NotNull Options options, @NotNull Map<String, Integer> ids) {
        int[] result = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            result[i] = options.ignoreBlankLines() && lines[i].isBlank() ? -1
                : ids.computeIfAbsent(normalize(lines[i], options), key -> ids.size());
        }
        return result;
    }

    @NotNull
    private static int[] keep(@NotNull int[] ids, @NotNull boolean[] onOtherSide) {
        int count = 0;
        int[] index = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] >= 0 && onOtherSide[ids[i]]) {
                index[count++] = i;
            }
        }
        return Arrays.copyOf(index, count);
    }

    @NotNull
    static String normalize(@NotNull String line, @NotNull Options options) {
        String result = switch (options.whitespace()) {
            case EXACT -> line;
            case TRIM -> line.strip();
            case IGNORE -> collapseSpaces(line.strip());
        };
        return options.ignoreCase() ? result.toLowerCase(Locale.ROOT) : result;
    }

    @NotNull
    private static String collapseSpaces(@NotNull String text) {
        StringBuilder result = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                continue;
            }
            if (space) {
                result.append(' ');
                space = false;
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * The unmatched lines between two matched ones: paired in order as changed lines, the rest on one side only. Ignored
     * blank lines stay in their place without a difference, two of them on the same row.
     */
    private void addGap(int leftStart, int leftEnd, int rightStart, int rightEnd, @NotNull int[] leftIds, @NotNull int[] rightIds,
                        @NotNull Options options) {
        int i = leftStart;
        int j = rightStart;
        while (i < leftEnd || j < rightEnd) {
            boolean leftBlank = i < leftEnd && leftIds[i] < 0;
            boolean rightBlank = j < rightEnd && rightIds[j] < 0;
            if (leftBlank && rightBlank) {
                rows.add(new Row(i++, j++, Kind.IGNORED));
            } else if (leftBlank) {
                rows.add(new Row(i++, -1, Kind.IGNORED));
            } else if (rightBlank) {
                rows.add(new Row(-1, j++, Kind.IGNORED));
            } else if (i < leftEnd && j < rightEnd) {
                // A line equal to the other one can be left unmatched by the diff (moved repeated lines): no difference
                rows.add(new Row(i, j, leftIds[i] == rightIds[j] ? Kind.EQUAL : Kind.CHANGED));
                i++;
                j++;
            } else if (i < leftEnd) {
                rows.add(new Row(i++, -1, Kind.LEFT_ONLY));
            } else {
                rows.add(new Row(-1, j++, Kind.RIGHT_ONLY));
            }
        }
    }

    /**
     * Runs of rows with differences; an equal row ends a run, an ignored blank row inside a run does not.
     */
    private void collectHunks() {
        int first = -1;
        int last = -1;
        for (int row = 0; row < rows.size(); row++) {
            Kind kind = rows.get(row).kind();
            if (kind.isDifference()) {
                if (first < 0) {
                    first = row;
                }
                last = row;
            } else if (kind == Kind.EQUAL && first >= 0) {
                hunks.add(new Hunk(first, last));
                first = -1;
            }
        }
        if (first >= 0) {
            hunks.add(new Hunk(first, last));
        }
    }

    //////////////////////////////////////////////////////////
    // Words of a changed line

    @NotNull
    private static int[][] markLine(@NotNull String left, @NotNull String right, @NotNull Options options) {
        List<int[]> leftTokens = tokenize(left);
        List<int[]> rightTokens = tokenize(right);
        if (leftTokens.size() > MAX_LINE_TOKENS || rightTokens.size() > MAX_LINE_TOKENS) {
            return new int[][]{{0, left.length()}, {0, right.length()}};
        }
        String[] a = tokenKeys(left, leftTokens, options);
        String[] b = tokenKeys(right, rightTokens, options);
        List<Integer> leftMarks = new ArrayList<>();
        List<Integer> rightMarks = new ArrayList<>();
        int aPosition = 0;
        int bPosition = 0;
        for (Diff.Range range : Diff.prepareDiff(a, b, String::equals)) {
            switch (range.operation) {
                case MATCH_AB -> {
                    aPosition += range.length;
                    bPosition += range.length;
                }
                case DELETE_A -> {
                    addMarks(leftMarks, leftTokens, a, aPosition, range.length, options);
                    aPosition += range.length;
                }
                case INSERT_B -> {
                    addMarks(rightMarks, rightTokens, b, bPosition, range.length, options);
                    bPosition += range.length;
                }
            }
        }
        return new int[][]{toArray(leftMarks), toArray(rightMarks)};
    }

    /**
     * Tokens of a line as start/end columns: a run of letters and digits (with _ $ # @), a run of spaces, or one other character.
     */
    @NotNull
    private static List<int[]> tokenize(@NotNull String line) {
        List<int[]> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            int start = i;
            char c = line.charAt(i);
            if (isWordChar(c)) {
                while (i < line.length() && isWordChar(line.charAt(i))) {
                    i++;
                }
            } else if (Character.isWhitespace(c)) {
                while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
            } else {
                // A surrogate pair is one character on the screen
                i += Character.charCount(line.codePointAt(i));
            }
            tokens.add(new int[]{start, i});
        }
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    @NotNull
    private static String[] tokenKeys(@NotNull String line, @NotNull List<int[]> tokens, @NotNull Options options) {
        String[] keys = new String[tokens.size()];
        for (int i = 0; i < keys.length; i++) {
            String token = line.substring(tokens.get(i)[0], tokens.get(i)[1]);
            if (options.whitespace() != Whitespace.EXACT && token.isBlank()) {
                token = " "; //$NON-NLS-1$
            }
            keys[i] = options.ignoreCase() ? token.toLowerCase(Locale.ROOT) : token;
        }
        return keys;
    }

    private static void addMarks(@NotNull List<Integer> marks, @NotNull List<int[]> tokens, @NotNull String[] keys, int from, int count,
                                 @NotNull Options options) {
        for (int k = from; k < from + count; k++) {
            // Spaces that the options ignore are not marked
            if (options.whitespace() != Whitespace.EXACT && keys[k].equals(" ")) { //$NON-NLS-1$
                continue;
            }
            int start = tokens.get(k)[0];
            int end = tokens.get(k)[1];
            int size = marks.size();
            if (size > 0 && marks.get(size - 1) == start) {
                marks.set(size - 1, end);
            } else {
                marks.add(start);
                marks.add(end);
            }
        }
    }

    @NotNull
    private static int[] toArray(@NotNull List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
