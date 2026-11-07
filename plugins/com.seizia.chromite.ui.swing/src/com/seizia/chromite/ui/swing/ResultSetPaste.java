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

import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.util.ArrayList;
import java.util.List;

/**
 * 결과 표 붙여넣기: 클립보드 글자를 칸 값으로 나누고, 특수 붙여넣기 설정을 묻는다(SWT {@code ResultSetHandlerPasteSpecial},
 * {@code ResultSetPasteSettings}). 값을 칸에 넣는 일은 표가 한다({@link ResultSetGrid}).
 *
 * 설계: 스프레드시트와 결과 표가 복사하는 형식(줄 = 행, 탭 = 칸)을 읽는다. 엑셀처럼 탭·줄 바꿈·따옴표가 든 값은 큰따옴표로 감싸고 안의 따옴표는 두 번
 * 쓴다고 보고 풀며, "따옴표 무시"를 고르면 따옴표도 글자로 둔다. 끝의 빈 줄(복사한 글자의 마지막 줄 바꿈)은 행으로 치지 않는다.
 * 일반 붙여넣기(Ctrl+V)는 SWT 기본값처럼 있는 행에만 넣고 NULL 표지가 없다. 특수 붙여넣기 설정은 이 실행 동안 기억한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 여러 칸 붙여넣기와 특수 붙여넣기 (docs/chromite/swing-grid-editing.md "붙여넣기")
 */
final class ResultSetPaste {

    private static final Log log = Log.getLog(ResultSetPaste.class);

    /**
     * Paste settings.
     *
     * @param insertNewRows add rows when the text has more lines than the grid has rows below the selected cell
     * @param ignoreQuotes  quotes are text, not value delimiters
     * @param nullMarker    a value equal to this text becomes NULL; empty: none
     */
    record Options(boolean insertNewRows, boolean ignoreQuotes, @NotNull String nullMarker) {
        static final Options PLAIN = new Options(false, false, ""); //$NON-NLS-1$

        boolean isNull(@NotNull String text) {
            return !nullMarker.isEmpty() && nullMarker.equals(text);
        }
    }

    private static Options lastOptions = new Options(true, false, ""); //$NON-NLS-1$

    private ResultSetPaste() {
    }

    /**
     * The clipboard's text, or null if it holds none.
     */
    @Nullable
    static String readClipboard() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data instanceof String text ? text : null;
        } catch (Exception e) {
            log.debug("No text in the clipboard", e);
            return null;
        }
    }

    /**
     * Splits text into lines of values (tabs between values, quoted values may hold tabs and line breaks).
     */
    @NotNull
    static List<String[]> parse(@NotNull String text, boolean ignoreQuotes) {
        List<String[]> lines = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    value.append(c);
                }
            } else if (c == '"' && !ignoreQuotes && value.isEmpty() && !quoted) {
                inQuotes = true;
                quoted = true;
            } else if (c == '\t') {
                values.add(value.toString());
                value.setLength(0);
                quoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                values.add(value.toString());
                lines.add(values.toArray(new String[0]));
                values.clear();
                value.setLength(0);
                quoted = false;
            } else {
                value.append(c);
            }
        }
        if (!value.isEmpty() || !values.isEmpty() || quoted) {
            values.add(value.toString());
            lines.add(values.toArray(new String[0]));
        }
        return lines;
    }

    /**
     * Asks for the Paste Special settings.
     *
     * @return the settings, or null if canceled
     */
    @Nullable
    static Options askOptions(@NotNull Component parent) {
        JCheckBox newRowsCheck = new JCheckBox(GridEditMessages.gridedit_paste_new_rows, lastOptions.insertNewRows());
        JCheckBox quotesCheck = new JCheckBox(GridEditMessages.gridedit_paste_ignore_quotes, lastOptions.ignoreQuotes());
        JTextField nullField = new JTextField(lastOptions.nullMarker(), 10);
        JPanel nullRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nullRow.add(new JLabel(GridEditMessages.gridedit_paste_null_marker));
        nullRow.add(Box.createHorizontalStrut(6));
        nullRow.add(nullField);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel(GridEditMessages.gridedit_paste_special_hint));
        panel.add(newRowsCheck);
        panel.add(quotesCheck);
        panel.add(nullRow);
        // Pasting changes only the grid (the save is separate): OK may be the default
        int answer = JOptionPane.showConfirmDialog(parent, panel, GridEditMessages.gridedit_paste_special_title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        lastOptions = new Options(newRowsCheck.isSelected(), quotesCheck.isSelected(), nullField.getText());
        return lastOptions;
    }
}
