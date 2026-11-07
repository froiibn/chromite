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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.function.IntPredicate;

/**
 * 결과의 텍스트 보기(SWT {@code PlainTextPresentation}, SSMS "Results to Text"): 표 자리에 열을 고정폭 글자로 맞춘 글을 보인다.
 *
 * 설계:
 * <ul>
 *     <li>글은 표가 보이는 행과 열(숨긴 열은 빼고 표의 열 순서)로 만든다. 값은 표의 표시 문자열이고 NULL은 표처럼 {@code NULL}, 숫자 열은
 *     오른쪽에 붙인다. 머리글 아래에 {@code -} 줄을 둔다(SWT 기본 "구분선 보이기").</li>
 *     <li>열 너비는 그 열의 가장 긴 값까지, 최대 {@link #MAX_COLUMN_WIDTH}자(SWT 설정 {@code resultset.show.plaintext.maxColumnSize} 기본 255)다.
 *     한글·한자처럼 넓은 글자는 두 칸으로 센다(고정폭 글꼴에서 줄이 맞게). 줄 바꿈과 탭은 공백으로 바꾼다.</li>
 *     <li>읽기 전용이다. 고르고 Ctrl+C로 복사한다. 아주 많은 행은 {@link #MAX_ROWS}행까지만 쓰고 아래에 알린다(EDT에서 만드므로).</li>
 *     <li>글꼴은 값 패널·Messages와 같은 코드 글꼴({@code monospaced} 스타일)이다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "텍스트 보기")
 */
final class ResultSetTextView extends JPanel {

    static final int MAX_COLUMN_WIDTH = 255;
    private static final int MAX_ROWS = 20_000;

    /**
     * The grid the text is made of.
     */
    interface Source {
        @Nullable
        JTable getTable();

        boolean isNumeric(int column);
    }

    private final Source source;
    private final JTextArea textArea = new JTextArea();

    ResultSetTextView(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;
        textArea.setEditable(false);
        textArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        add(new JScrollPane(textArea), BorderLayout.CENTER);
    }

    /**
     * Makes the text of the grid's rows again.
     */
    void refresh() {
        JTable table = source.getTable();
        textArea.setText(table == null ? "" : format(table, source::isNumeric));
        textArea.setCaretPosition(0);
    }

    /**
     * The fixed-width text of a grid's visible columns and rows.
     *
     * @param numeric whether a model column holds numbers (right-aligned)
     */
    @NotNull
    static String format(@NotNull JTable table, @NotNull IntPredicate numeric) {
        int columns = table.getColumnCount();
        int rows = Math.min(table.getRowCount(), MAX_ROWS);
        String[][] cells = new String[rows + 1][columns];
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            cells[0][c] = clean(String.valueOf(table.getColumnModel().getColumn(c).getHeaderValue()));
            widths[c] = width(cells[0][c]);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                Object value = table.getValueAt(r, c);
                String text = clean(value == null ? "" : value.toString());
                cells[r + 1][c] = text;
                widths[c] = Math.max(widths[c], width(text));
            }
        }
        StringBuilder text = new StringBuilder();
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    text.append(' ');
                }
                boolean right = r > 0 && numeric.test(table.convertColumnIndexToModel(c));
                appendPadded(text, cells[r][c], widths[c], right);
            }
            trimEnd(text);
            text.append('\n');
            if (r == 0) {
                for (int c = 0; c < columns; c++) {
                    if (c > 0) {
                        text.append(' ');
                    }
                    text.append("-".repeat(widths[c])); //$NON-NLS-1$
                }
                text.append('\n');
            }
        }
        if (table.getRowCount() > rows) {
            text.append('\n').append(NLS.bind(GridNavMessages.gridnav_text_capped, String.format("%,d", MAX_ROWS), //$NON-NLS-1$
                String.format("%,d", table.getRowCount()))); //$NON-NLS-1$
        }
        return text.toString();
    }

    /**
     * One line of a value, cut to the column width limit.
     */
    @NotNull
    private static String clean(@NotNull String text) {
        String line = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        StringBuilder result = new StringBuilder();
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            width += charWidth(ch);
            if (width > MAX_COLUMN_WIDTH) {
                break;
            }
            result.append(ch);
        }
        return result.toString();
    }

    private static void appendPadded(@NotNull StringBuilder text, @NotNull String value, int width, boolean right) {
        int padding = width - width(value);
        if (right) {
            text.append(" ".repeat(Math.max(0, padding))).append(value); //$NON-NLS-1$
        } else {
            text.append(value).append(" ".repeat(Math.max(0, padding))); //$NON-NLS-1$
        }
    }

    private static void trimEnd(@NotNull StringBuilder text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        text.setLength(end);
    }

    /**
     * Columns a text takes in a fixed-width font: wide (East Asian) characters take two.
     */
    static int width(@NotNull String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += charWidth(text.charAt(i));
        }
        return width;
    }

    private static int charWidth(char ch) {
        // Hangul Jamo, CJK punctuation to Hangul syllables, compatibility ideographs, full-width forms
        return ch >= 0x1100 && ch <= 0x115F || ch >= 0x2E80 && ch <= 0xD7A3 || ch >= 0xF900 && ch <= 0xFAFF
            || ch >= 0xFE30 && ch <= 0xFE4F || ch >= 0xFF00 && ch <= 0xFF60 || ch >= 0xFFE0 && ch <= 0xFFE6 ? 2 : 1;
    }
}
