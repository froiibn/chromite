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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;

/**
 * 이진 값을 16진수로 고치는 대화상자(SWT {@code ui.editors.hex}의 {@code HexEditControl}).
 *
 * 설계:
 * <ul>
 *     <li>SWT 16진수 편집기는 바이트 칸마다 커서가 움직이는 전용 컨트롤(삽입·덮어쓰기 모드, 찾기·바꾸기)이었다. Swing에는 그런 컨트롤이 없어
 *     16진수 글자를 고치는 글 상자로 했다: 한 줄 16바이트, 왼쪽에 오프셋, 오른쪽에 글자(ASCII) 미리 보기. 글자 편집이라 붙여넣기·지우기·
 *     끼워 넣기가 그대로 되고, 공백과 줄 바꿈은 무시한다.</li>
 *     <li>고치는 동안 0.2초 뒤 글자를 바이트로 읽어 오프셋·미리 보기·바이트 수를 다시 그린다. 16진수가 아닌 글자나 홀수 개 숫자는 아래 줄에
 *     이유를 쓰고 OK를 끈다. 줄 맞춤은 고치는 중에 바꾸지 않는다(커서가 튐). "정리"가 16바이트 줄로 다시 맞춘다.</li>
 *     <li>여는 크기는 값 패널이 읽는 크기({@link ResultSetCellContent#MAX_LOAD_SIZE})까지다. 더 큰 값은 파일 불러오기나 외부 편집기를 쓴다.</li>
 *     <li>OK는 바이트를 돌려주고, 결과 뷰어가 열의 값 처리기로 셀 값을 만든다(파일 불러오기와 같은 길, {@link ResultSetGrid#setCellObject}).
 *     셀만 바뀌므로 OK가 기본 단추다. 읽기 전용 셀은 보기만 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 이진 값 16진수 편집 (docs/chromite/swing-value-editors.md)
 */
final class ResultSetHexEditor extends JDialog {

    static final int BYTES_PER_LINE = 16;
    private static final int PARSE_DELAY_MS = 200;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray(); //$NON-NLS-1$

    private final JTextArea hexArea = new JTextArea();
    private final JTextArea offsetArea = new JTextArea();
    private final JTextArea textArea = new JTextArea();
    private final JLabel sizeLabel = new JLabel(" "); //$NON-NLS-1$
    private final JLabel errorLabel = new JLabel(" "); //$NON-NLS-1$
    private final JButton okButton = new JButton(CoreMessages.common_ok);
    private final Timer parseTimer;
    private final boolean editable;
    @Nullable
    private byte[] parsed;
    @Nullable
    private byte[] result;

    private ResultSetHexEditor(@NotNull Window owner, @NotNull String columnName, @NotNull byte[] bytes, boolean editable) {
        super(owner, NLS.bind(ValueEditMessages.valueedit_hex_title, columnName), ModalityType.APPLICATION_MODAL);
        this.editable = editable;
        // The code font of the value panel and Messages (design-spec font.code)
        Font font = UIManager.getFont("monospaced.font"); //$NON-NLS-1$
        if (font == null) {
            font = new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("TextArea.font").getSize()); //$NON-NLS-1$
        }
        for (JTextArea area : new JTextArea[]{hexArea, offsetArea, textArea}) {
            area.setFont(font);
        }
        offsetArea.setEditable(false);
        offsetArea.setFocusable(false);
        textArea.setEditable(false);
        offsetArea.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        textArea.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        hexArea.setEditable(editable);
        hexArea.setText(format(bytes));
        hexArea.setCaretPosition(0);
        parseTimer = new Timer(PARSE_DELAY_MS, e -> parse());
        parseTimer.setRepeats(false);
        hexArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                parseTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                parseTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                parseTimer.restart();
            }
        });

        // Offsets, hex and the text preview scroll together: one row of three areas in one scroll pane
        JPanel rows = new JPanel(new BorderLayout(12, 0));
        rows.add(offsetArea, BorderLayout.WEST);
        rows.add(hexArea, BorderLayout.CENTER);
        rows.add(textArea, BorderLayout.EAST);
        rows.setBackground(hexArea.getBackground());
        JScrollPane scroll = new JScrollPane(rows);
        FontMetrics metrics = hexArea.getFontMetrics(font);
        scroll.setPreferredSize(new Dimension(metrics.charWidth('0') * (10 + BYTES_PER_LINE * 3 + BYTES_PER_LINE + 8),
            metrics.getHeight() * 18));
        scroll.getVerticalScrollBar().setUnitIncrement(metrics.getHeight());

        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        sizeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JButton formatButton = new JButton(ValueEditMessages.valueedit_hex_format);
        formatButton.setEnabled(editable);
        formatButton.addActionListener(e -> {
            parse();
            if (parsed != null) {
                hexArea.setText(format(parsed));
                hexArea.setCaretPosition(0);
            }
        });
        okButton.setEnabled(editable);
        okButton.addActionListener(e -> {
            parse();
            if (parsed != null) {
                result = parsed;
                dispose();
            }
        });
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(formatButton);
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel info = new JPanel(new GridLayout(0, 1));
        info.add(sizeLabel);
        info.add(errorLabel);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.add(info, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        root.add(scroll, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        // The cell changes, not the database: OK is the default (Ctrl+Enter, as Enter types a line break), Esc cancels
        getRootPane().registerKeyboardAction(e -> okButton.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        parse();
        if (!editable) {
            errorLabel.setText(ValueEditMessages.valueedit_hex_read_only);
        }
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the editor and waits.
     *
     * @param editable false shows the bytes only
     * @return the edited bytes, or null if canceled (or not editable)
     */
    @Nullable
    static byte[] edit(@NotNull Component parent, @NotNull String columnName, @NotNull byte[] bytes, boolean editable) {
        Window window = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        ResultSetHexEditor editor = new ResultSetHexEditor(window, columnName, bytes, editable);
        editor.setVisible(true);
        editor.parseTimer.stop();
        return editor.result;
    }

    /**
     * Reads the hex text and redraws the offsets and the text preview.
     */
    private void parse() {
        parseTimer.stop();
        String text = hexArea.getText();
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() / 3 + 1);
        String error = null;
        int high = -1;
        int line = 1;
        for (int i = 0; i < text.length() && error == null; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            int digit = Character.digit(c, 16);
            if (digit < 0) {
                error = NLS.bind(ValueEditMessages.valueedit_hex_invalid_char, String.valueOf(c), line);
            } else if (high < 0) {
                high = digit;
            } else {
                out.write(high << 4 | digit);
                high = -1;
            }
        }
        if (error == null && high >= 0) {
            error = ValueEditMessages.valueedit_hex_odd;
        }
        parsed = error == null ? out.toByteArray() : null;
        if (parsed != null) {
            sizeLabel.setText(NLS.bind(ValueEditMessages.valueedit_hex_size, String.format("%,d", parsed.length))); //$NON-NLS-1$
            drawSides(parsed);
        }
        if (editable) {
            errorLabel.setText(error == null ? " " : error); //$NON-NLS-1$
            okButton.setEnabled(parsed != null);
        }
    }

    /**
     * Offsets and printable characters, by the lines of the hex text as it is typed (a line of the hex area may
     * hold more or fewer than 16 bytes until it is formatted).
     */
    private void drawSides(@NotNull byte[] bytes) {
        StringBuilder offsets = new StringBuilder();
        StringBuilder chars = new StringBuilder();
        String[] lines = hexArea.getText().split("\n", -1); //$NON-NLS-1$
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            int count = 0;
            for (int j = 0; j < lines[i].length(); j++) {
                if (Character.digit(lines[i].charAt(j), 16) >= 0) {
                    count++;
                }
            }
            int lineBytes = count / 2;
            if (i > 0) {
                offsets.append('\n');
                chars.append('\n');
            }
            offsets.append(String.format("%08X", offset)); //$NON-NLS-1$
            for (int k = offset; k < Math.min(offset + lineBytes, bytes.length); k++) {
                int b = bytes[k] & 0xFF;
                chars.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            offset += lineBytes;
        }
        offsetArea.setText(offsets.toString());
        textArea.setText(chars.toString());
    }

    /**
     * Hex text of bytes: 16 per line, separated by spaces.
     */
    @NotNull
    static String format(@NotNull byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                text.append(i % BYTES_PER_LINE == 0 ? '\n' : ' ');
            }
            text.append(HEX_DIGITS[(bytes[i] >> 4) & 0xF]).append(HEX_DIGITS[bytes[i] & 0xF]);
        }
        return text.toString();
    }
}
