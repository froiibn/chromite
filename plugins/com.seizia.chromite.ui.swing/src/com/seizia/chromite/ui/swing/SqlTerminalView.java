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
import com.seizia.chromite.ui.swing.internal.TerminalMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;

/**
 * 쿼리 창 하나의 SQL 터미널(Query &gt; SQL Terminal, 결과 영역의 Terminal 탭): 실행한 문장과 결과를 명령줄 클라이언트처럼 글자로 쌓는다.
 *
 * 설계:
 * <ul>
 *     <li>SWT의 SQL 터미널({@code ui.editors.sql.terminal}의 {@code SQLTerminalView})을 옮겼다. SWT처럼 결과 표는 그대로 만들고
 *     터미널은 같은 실행을 글자로도 쓴다. 그래서 결과 편집·내보내기는 Results 탭에서 그대로 쓸 수 있다.</li>
 *     <li>내용은 실행마다 지우지 않고 쌓는다(명령줄 클라이언트처럼 앞 실행과 비교할 수 있게). 지우기는 오른쪽 클릭 메뉴의 Clear다.
 *     메모리가 끝없이 늘지 않도록 {@link #MAX_LENGTH}자를 넘으면 앞쪽의 오래된 줄부터 지운다.</li>
 *     <li>문장 하나의 글은 {@code SQL> 문장}, 결과 표(글자 표), Messages 탭과 같은 메시지(경고·서버 출력·행 수·시간), 오류 순서다.
 *     메시지 글은 쿼리 창이 Messages 탭에 쓰는 것을 그대로 받는다(같은 문구를 두 번 만들지 않는다).</li>
 *     <li>글자 표는 실행 Job 스레드에서 만든다({@link #formatTable}, EDT를 막지 않는다). 칸 너비는 SWT 글자 결과의 기본 최대 칸 크기
 *     ({@code RESULT_TEXT_MAX_COLUMN_SIZE} 기본값 255, {@code DataEditorsPreferencesInitializer})를 넘지 않고, 넘는 값은 잘라 {@code …}를 붙인다.
 *     NULL은 결과 표처럼 {@code NULL}로 쓴다. 한글·한자 같은 전각 문자는 두 칸으로 세어 고정폭 글꼴에서 줄이 맞게 한다.</li>
 *     <li>모양은 Messages 탭과 같다: 고정폭 글꼴(스타일 클래스 {@code monospaced}), 오류는 {@code Chromite.status.error} 색.</li>
 * </ul>
 * 화면 메서드는 EDT에서 부른다. {@code format*}은 어느 스레드에서나 부를 수 있다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: SWT SQL 터미널을 쿼리 창마다 켜고 끄는 Terminal 탭으로 옮김 (docs/chromite/swing-sql-terminal.md)
 */
final class SqlTerminalView {

    /**
     * Widest column of a text table, as the SWT text presentation's default maximum column size.
     */
    static final int MAX_COLUMN_WIDTH = 255;
    /**
     * Characters kept; older output is removed from the start beyond this.
     */
    private static final int MAX_LENGTH = 2_000_000;
    private static final String PROMPT = "SQL> "; //$NON-NLS-1$
    private static final String ELLIPSIS = "…"; //$NON-NLS-1$

    private final JTextPane textPane = new JTextPane();
    private final JScrollPane component;

    SqlTerminalView() {
        textPane.setEditable(false);
        // font.code, like the Messages tab
        textPane.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = menu.add(TerminalMessages.terminal_copy);
        copyItem.addActionListener(e -> textPane.copy());
        menu.add(TerminalMessages.terminal_select_all).addActionListener(e -> textPane.selectAll());
        menu.addSeparator();
        menu.add(TerminalMessages.terminal_clear).addActionListener(e -> clear());
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                copyItem.setEnabled(textPane.getSelectionEnd() > textPane.getSelectionStart());
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        textPane.setComponentPopupMenu(menu);
        component = new JScrollPane(textPane);
        component.setBorder(BorderFactory.createEmptyBorder());
    }

    @NotNull
    JComponent getComponent() {
        return component;
    }

    void clear() {
        textPane.setText("");
    }

    void append(@NotNull String text) {
        append(text, null);
    }

    void appendError(@NotNull String text) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        Color errorColor = UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
        StyleConstants.setForeground(attributes, errorColor != null ? errorColor : Color.RED);
        append(text, attributes);
    }

    private void append(@NotNull String text, @Nullable SimpleAttributeSet attributes) {
        StyledDocument document = textPane.getStyledDocument();
        try {
            document.insertString(document.getLength(), text.endsWith("\n") ? text : text + "\n", attributes);
            int excess = document.getLength() - MAX_LENGTH;
            if (excess > 0) {
                // Whole lines only, so that the first line kept is not cut in the middle of a table row
                String head = document.getText(0, Math.min(document.getLength(), excess + MAX_LENGTH / 10));
                int lineEnd = head.indexOf('\n', excess - 1);
                document.remove(0, lineEnd >= 0 ? lineEnd + 1 : head.length());
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        // Follow the output, like a console
        textPane.setCaretPosition(document.getLength());
    }

    //////////////////////////////////////////////////////////
    // Text (any thread)

    /**
     * The statement as typed after the prompt; continuation lines are indented under the first one.
     */
    @NotNull
    static String formatStatement(@NotNull String statement) {
        String indent = " ".repeat(PROMPT.length());
        return PROMPT + statement.strip().replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n" + indent); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * A result set as a text table with a header row; numbers are right-aligned.
     */
    @NotNull
    static String formatTable(@NotNull ResultSetGrid.Data data) {
        String[] columns = data.columns();
        int[] widths = new int[columns.length];
        String[] header = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            header[i] = toCell(columns[i]);
            widths[i] = displayWidth(header[i]);
        }
        String[][] cells = new String[data.rows().size()][];
        for (int r = 0; r < cells.length; r++) {
            Object[] row = data.rows().get(r);
            cells[r] = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                Object value = i < row.length ? row[i] : null;
                cells[r][i] = toCell(value == null ? "" : value.toString());
                widths[i] = Math.max(widths[i], displayWidth(cells[r][i]));
            }
        }
        StringBuilder border = new StringBuilder("+"); //$NON-NLS-1$
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+'); //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder();
        text.append(border).append('\n');
        appendRow(text, header, widths, null);
        text.append(border).append('\n');
        for (String[] row : cells) {
            appendRow(text, row, widths, data.numeric());
        }
        text.append(border);
        return text.toString();
    }

    private static void appendRow(@NotNull StringBuilder text, @NotNull String[] row, @NotNull int[] widths, @Nullable boolean[] rightAligned) {
        text.append('|');
        for (int i = 0; i < row.length; i++) {
            String padding = " ".repeat(widths[i] - displayWidth(row[i])); //$NON-NLS-1$
            boolean right = rightAligned != null && i < rightAligned.length && rightAligned[i];
            text.append(' ').append(right ? padding + row[i] : row[i] + padding).append(" |"); //$NON-NLS-1$
        }
        text.append('\n');
    }

    /**
     * One line, at most {@link #MAX_COLUMN_WIDTH} columns wide.
     */
    @NotNull
    private static String toCell(@NotNull String value) {
        String line = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' '); //$NON-NLS-1$ //$NON-NLS-2$
        if (displayWidth(line) <= MAX_COLUMN_WIDTH) {
            return line;
        }
        StringBuilder cut = new StringBuilder();
        int width = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int charWidth = isWide(codePoint) ? 2 : 1;
            if (width + charWidth > MAX_COLUMN_WIDTH - 1) {
                break;
            }
            cut.appendCodePoint(codePoint);
            width += charWidth;
            i += Character.charCount(codePoint);
        }
        return cut + ELLIPSIS;
    }

    /**
     * Columns the text takes in a monospaced font: East Asian wide characters (Hangul, CJK, full-width forms) take two.
     */
    static int displayWidth(@NotNull String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += isWide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)     // Hangul Jamo
            || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)     // CJK radicals .. Yi
            || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)     // Hangul syllables
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)     // CJK compatibility ideographs
            || (codePoint >= 0xFE30 && codePoint <= 0xFE4F)     // CJK compatibility forms
            || (codePoint >= 0xFF00 && codePoint <= 0xFF60)     // full-width forms
            || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
            || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);  // CJK extensions
    }
}
