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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.fife.ui.rsyntaxtextarea.HtmlUtil;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.Pair;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.UnaryOperator;

/**
 * 쿼리 편집기의 글자 도구: 블록 주석 토글, 공백 다듬기, 구분 목록 바꾸기, 소스 코드로 복사, 웹에서 찾기.
 *
 * 설계:
 * <ul>
 *     <li>동작은 SWT 편집기의 처리기를 옮겼다: {@code ToggleMultiLineCommentHandler}(블록 주석), {@code Trim*SpacesHandler}(다듬기),
 *     {@code MorphDelimitedListHandler}(구분 목록), {@code CopySourceCodeHandler}와 {@code convert.impl.*} 변환기(소스 코드로 복사),
 *     {@code OpenLinkInWindowHandler}(웹에서 찾기). 그 처리기들은 SWT 번들과 함께 지워져 모델 계층에서 부를 수 없어 Swing 쪽에 다시 썼다.</li>
 *     <li>블록 주석 기호는 연결 방언의 {@link SQLDialect#getMultiLineComments()}(없으면 {@code /* *}{@code /})다.</li>
 *     <li>소스 코드로 복사는 SWT 변환기 선택 대화상자 대신 형식별 메뉴 항목이다. 옵션은 SWT 확장의 기본값을 쓴다(서식 유지 끔, 줄 구분 공백,
 *     Delphi는 {@code #13#10}, StringBuilder 끔). HTML은 SWT처럼 구문 색을 넣은 {@code <pre>}이며 RSyntaxTextArea의 {@link HtmlUtil}로 만든다.
 *     대상은 선택이 2자 이상이면 선택, 아니면 커서가 있는 문장(SWT와 같음, 문장 경계는 {@link SqlFoldParser}), 그것도 없으면 전체다.</li>
 *     <li>구분 목록 설정 대화상자의 값은 앱을 끝낼 때까지 기억한다(SWT는 대화상자 설정 파일에 저장).</li>
 *     <li>웹에서 찾기는 선택한 글자를 SWT와 같은 검색 주소로 기본 브라우저에 연다. 사용자가 고른 글자만 보내며, 선택이 없으면 아무것도 하지 않는다.</li>
 *     <li>모든 바꾸기는 되돌리기 한 번에 취소되도록 한 편집({@code beginAtomicEdit})으로 묶는다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-ergonomics.md)
 */
final class SqlTextTools {

    private static final Log log = Log.getLog(SqlTextTools.class);

    private static final String SEARCH_WEB_ADDRESS_PREFIX = "https://www.google.com/search?q="; //$NON-NLS-1$

    /**
     * Targets of Copy as Source Code, with the default options of the SWT converters.
     */
    enum SourceFormat {
        JAVA("Java"), //$NON-NLS-1$
        PYTHON("Python"), //$NON-NLS-1$
        CPP("C/C++"), //$NON-NLS-1$
        DELPHI("Delphi"), //$NON-NLS-1$
        UNFORMATTED(null),
        HTML("HTML"); //$NON-NLS-1$

        @Nullable
        private final String label;

        SourceFormat(@Nullable String label) {
            this.label = label;
        }

        @NotNull
        String getLabel() {
            return label != null ? label : EditorMessages.editor_text_unformatted_sql;
        }
    }

    // Morph settings of this application run (EDT only); the SWT dialog defaults
    private static String morphSourceDelimiter = "\t\n,"; //$NON-NLS-1$
    private static String morphTargetDelimiter = ","; //$NON-NLS-1$
    private static String morphQuote = "\""; //$NON-NLS-1$
    private static int morphWrapColumn = 80;
    private static String morphLeadingText = ""; //$NON-NLS-1$
    private static String morphTrailingText = ""; //$NON-NLS-1$

    private SqlTextTools() {
    }

    //////////////////////////////////////////////////////////
    // Block comment

    /**
     * Wraps the selection (or the line at the caret) in the dialect's block comment, or removes the block comment
     * around it; as the SWT {@code ToggleMultiLineCommentHandler}.
     */
    static void toggleBlockComment(@NotNull SqlEditorPanel.SqlTextArea textArea, @Nullable DBPDataSourceContainer container) {
        Pair<String, String> comment = SqlEditCommands.getDialect(container).getMultiLineComments();
        String open = comment != null ? comment.getFirst() : "/*"; //$NON-NLS-1$
        String close = comment != null ? comment.getSecond() : "*/"; //$NON-NLS-1$
        if (textArea.getSelectionStart() == textArea.getSelectionEnd()) {
            // No selection: the text of the caret line
            Element line = getLine(textArea, textArea.getCaretPosition());
            String lineText = getText(textArea, line.getStartOffset(), lineEnd(textArea, line));
            int indent = lineText.length() - lineText.stripLeading().length();
            int trailing = lineText.length() - lineText.stripTrailing().length();
            if (lineText.isBlank()) {
                return;
            }
            textArea.select(line.getStartOffset() + indent, lineEnd(textArea, line) - trailing);
        }
        int start = textArea.getSelectionStart();
        String selected = textArea.getSelectedText();
        String trimmed = selected.strip();
        String result;
        if (trimmed.startsWith(open) && trimmed.endsWith(close) && trimmed.length() >= open.length() + close.length()) {
            // Remove the comment and the line breaks right inside it
            int from = selected.indexOf(open) + open.length();
            int to = selected.lastIndexOf(close);
            while (from < to && (selected.charAt(from) == '\n' || selected.charAt(from) == '\r')) {
                from++;
            }
            while (to > from && (selected.charAt(to - 1) == '\n' || selected.charAt(to - 1) == '\r')) {
                to--;
            }
            result = selected.substring(0, selected.indexOf(open)) + selected.substring(from, to)
                + selected.substring(selected.lastIndexOf(close) + close.length());
        } else {
            // Selected whole lines get the comment marks on lines of their own
            boolean wholeLines = getLine(textArea, start).getStartOffset() == start && selected.endsWith("\n"); //$NON-NLS-1$
            result = wholeLines ? open + "\n" + selected + close + "\n" : open + selected + close; //$NON-NLS-1$ //$NON-NLS-2$
        }
        replaceRange(textArea, start, textArea.getSelectionEnd(), result);
        textArea.select(start, start + result.length());
    }

    //////////////////////////////////////////////////////////
    // Trim

    enum Trim {
        BOTH, LEADING, TRAILING
    }

    /**
     * Trims each selected line (several lines), the selected text (part of one line) or the text before the caret on
     * its line (no selection), as the SWT {@code AbstractTrimSpacesHandler}.
     */
    static void trim(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull Trim trim) {
        UnaryOperator<String> operator = switch (trim) {
            case BOTH -> String::strip;
            case LEADING -> String::stripLeading;
            case TRAILING -> String::stripTrailing;
        };
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        if (start == end) {
            Element line = getLine(textArea, start);
            String before = getText(textArea, line.getStartOffset(), start);
            replaceRange(textArea, line.getStartOffset(), start, operator.apply(before));
            return;
        }
        Element firstLine = getLine(textArea, start);
        Element lastLine = getLine(textArea, end);
        if (firstLine == lastLine) {
            String result = operator.apply(textArea.getSelectedText());
            replaceRange(textArea, start, end, result);
            textArea.select(start, start + result.length());
            return;
        }
        // Whole lines, from the start of the first selected line to the end of the last one
        Element root = textArea.getDocument().getDefaultRootElement();
        int from = firstLine.getStartOffset();
        int to = lineEnd(textArea, lastLine);
        StringBuilder result = new StringBuilder();
        for (int line = root.getElementIndex(start); line <= root.getElementIndex(end); line++) {
            Element element = root.getElement(line);
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(operator.apply(getText(textArea, element.getStartOffset(), lineEnd(textArea, element))));
        }
        replaceRange(textArea, from, to, result.toString());
        textArea.select(from, from + result.length());
    }

    //////////////////////////////////////////////////////////
    // Morph delimited list

    /**
     * Asks for the delimiters and replaces the selected list, as the SWT {@code MorphDelimitedListHandler}.
     */
    static void morphDelimitedList(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        String selected = textArea.getSelectedText();
        if (CommonUtils.isEmpty(selected)) {
            JOptionPane.showMessageDialog(textArea, EditorMessages.editor_text_morph_no_selection, EditorMessages.editor_text_morph_title,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!showMorphDialog(textArea)) {
            return;
        }
        int start = textArea.getSelectionStart();
        String result = morph(selected, morphSourceDelimiter, morphTargetDelimiter, morphQuote, morphWrapColumn, morphLeadingText, morphTrailingText);
        replaceRange(textArea, start, textArea.getSelectionEnd(), result);
        textArea.select(start, start + result.length());
    }

    /**
     * The morph of the SWT handler: the tokens between any of the source delimiter characters, stripped, quoted and
     * joined with the target delimiter; a line break goes before a token that would pass the wrap column.
     */
    @NotNull
    static String morph(
        @NotNull String text,
        @NotNull String sourceDelimiters,
        @NotNull String targetDelimiter,
        @NotNull String quote,
        int wrapColumn,
        @NotNull String leadingText,
        @NotNull String trailingText
    ) {
        String delimiters = sourceDelimiters.contains("\n") && !sourceDelimiters.contains("\r") ? sourceDelimiters + "\r" : sourceDelimiters; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> tokens = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(text, delimiters);
        while (tokenizer.hasMoreTokens()) {
            String token = tokenizer.nextToken().strip();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        StringBuilder result = new StringBuilder(leadingText);
        int lineLength = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String item = quote + tokens.get(i) + quote;
            result.append(item);
            lineLength += item.length();
            if (i < tokens.size() - 1) {
                result.append(targetDelimiter);
                lineLength += targetDelimiter.length();
                if (wrapColumn > 0 && lineLength + tokens.get(i + 1).length() + quote.length() * 2 > wrapColumn) {
                    result.append('\n');
                    lineLength = 0;
                }
            }
        }
        return result.append(trailingText).toString();
    }

    private static boolean showMorphDialog(@NotNull Component parent) {
        String[] delimiters = {"\\n", "\\t", ";", ","}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JComboBox<String> sourceCombo = createCombo(delimiters, CommonUtils.escapeDisplayString(morphSourceDelimiter));
        JComboBox<String> targetCombo = createCombo(delimiters, CommonUtils.escapeDisplayString(morphTargetDelimiter));
        JComboBox<String> quoteCombo = createCombo(new String[]{"\"", "'", ""}, morphQuote); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JSpinner wrapSpinner = new JSpinner(new SpinnerNumberModel(morphWrapColumn, 0, 10_000, 1));
        JTextField leadingField = new JTextField(CommonUtils.escapeDisplayString(morphLeadingText), 20);
        JTextField trailingField = new JTextField(CommonUtils.escapeDisplayString(morphTrailingText), 20);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        addRow(form, c, 0, EditorMessages.editor_text_morph_source_delimiter, sourceCombo);
        addRow(form, c, 1, EditorMessages.editor_text_morph_target_delimiter, targetCombo);
        addRow(form, c, 2, EditorMessages.editor_text_morph_quote, quoteCombo);
        addRow(form, c, 3, EditorMessages.editor_text_morph_wrap, wrapSpinner);
        addRow(form, c, 4, EditorMessages.editor_text_morph_leading, leadingField);
        addRow(form, c, 5, EditorMessages.editor_text_morph_trailing, trailingField);
        JLabel hint = new JLabel(EditorMessages.editor_text_morph_hint);
        hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        form.add(hint, c);

        // Changes the text: Cancel is the default button (the change can also be undone)
        String[] options = {EditorMessages.editor_text_morph_apply, EditorMessages.editor_common_cancel};
        int choice = JOptionPane.showOptionDialog(parent, form, EditorMessages.editor_text_morph_title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
        if (choice != 0) {
            return false;
        }
        String source = CommonUtils.unescapeDisplayString(String.valueOf(sourceCombo.getEditor().getItem()));
        if (source.isEmpty()) {
            return false;
        }
        morphSourceDelimiter = source;
        morphTargetDelimiter = CommonUtils.unescapeDisplayString(String.valueOf(targetCombo.getEditor().getItem()));
        morphQuote = String.valueOf(quoteCombo.getEditor().getItem());
        morphWrapColumn = (Integer) wrapSpinner.getValue();
        morphLeadingText = CommonUtils.unescapeDisplayString(leadingField.getText());
        morphTrailingText = CommonUtils.unescapeDisplayString(trailingField.getText());
        return true;
    }

    @NotNull
    private static JComboBox<String> createCombo(@NotNull String[] values, @NotNull String current) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setEditable(true);
        combo.setSelectedItem(current);
        return combo;
    }

    private static void addRow(@NotNull JPanel form, @NotNull GridBagConstraints c, int row, @NotNull String label, @NotNull JComponent field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(field);
        form.add(labelComponent, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
    }

    //////////////////////////////////////////////////////////
    // Copy as source code

    /**
     * Copies the selection (or the statement at the caret, or all) converted for a programming language.
     */
    static void copyAsSourceCode(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull SourceFormat format, @Nullable DBPDataSourceContainer container) {
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        if (end - start < 2) {
            SqlFoldParser.Statement statement = SqlFoldParser.findStatementAt(SqlFoldParser.findStatements(textArea), textArea.getCaretPosition());
            start = statement != null ? statement.start() : 0;
            end = statement != null ? statement.end() : textArea.getDocument().getLength();
        }
        String text = getText(textArea, start, end);
        if (text.isBlank()) {
            return;
        }
        Transferable contents = switch (format) {
            case HTML -> new HtmlSelection(HtmlUtil.getTextAsHtml(textArea, start, end), text);
            case UNFORMATTED -> new StringSelection(unformat(textArea, start, end, SqlEditCommands.getDialect(container)));
            default -> new StringSelection(convertToSource(text, format));
        };
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
    }

    /**
     * The source code converters of the SWT editor ({@code JavaSQLConverter}, {@code PythonCodeConverter},
     * {@code CPPSQLConverter}, {@code DelphiSQLConverter}) with their default options: each line stripped, a space as
     * the line delimiter inside the string (Delphi: {@code #13#10}).
     */
    @NotNull
    static String convertToSource(@NotNull String text, @NotNull SourceFormat format) {
        String[] lines = text.split("[\\n\\r]+"); //$NON-NLS-1$
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].strip();
        }
        StringBuilder result = new StringBuilder();
        switch (format) {
            case JAVA -> {
                for (int i = 0; i < lines.length; i++) {
                    result.append('"').append(CommonUtils.escapeJavaString(lines[i])).append(" \""); //$NON-NLS-1$
                    result.append(i < lines.length - 1 ? " + \n" : ";"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            case PYTHON -> {
                result.append("\"\"\"\n"); //$NON-NLS-1$
                for (String line : lines) {
                    result.append(CommonUtils.escapeJavaString(line)).append('\n');
                }
                result.append("\"\"\"\n"); //$NON-NLS-1$
            }
            case CPP -> {
                for (int i = 0; i < lines.length; i++) {
                    result.append('"').append(CommonUtils.escapeJavaString(lines[i])).append(" \""); //$NON-NLS-1$
                    result.append(i < lines.length - 1 ? "\n" : ";"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            case DELPHI -> {
                for (int i = 0; i < lines.length; i++) {
                    result.append('\'').append(CommonUtils.escapeJavaString(lines[i].replace("'", "''"))).append("'#13#10"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    if (i < lines.length - 1) {
                        result.append(" + \n"); //$NON-NLS-1$
                    }
                }
            }
            default -> result.append(text);
        }
        return result.toString();
    }

    /**
     * One line of SQL, as the SWT {@code UnformattedSQLConverter}: runs of white space become one space, and line
     * comments become block comments so that they do not swallow the rest of the line.
     */
    @NotNull
    private static String unformat(@NotNull SqlEditorPanel.SqlTextArea textArea, int start, int end, @NotNull SQLDialect dialect) {
        Pair<String, String> blockComment = dialect.getMultiLineComments();
        StringBuilder result = new StringBuilder();
        boolean lastWhitespace = false;
        Element root = textArea.getDocument().getDefaultRootElement();
        for (int line = root.getElementIndex(start); line <= root.getElementIndex(end); line++) {
            for (Token token = textArea.getTokenListForLine(line); token != null && token.isPaintable(); token = token.getNextToken()) {
                int tokenStart = Math.max(start, token.getOffset());
                int tokenEnd = Math.min(end, token.getEndOffset());
                if (tokenStart >= tokenEnd) {
                    continue;
                }
                String lexeme = getText(textArea, tokenStart, tokenEnd);
                if (token.isWhitespace()) {
                    if (!lastWhitespace) {
                        result.append(' ');
                    }
                    lastWhitespace = true;
                } else if (token.isComment()) {
                    String comment = lexeme;
                    if (token.getType() == TokenTypes.COMMENT_EOL && blockComment != null) {
                        comment = blockComment.getFirst() + stripLineComment(comment, dialect) + " " + blockComment.getSecond(); //$NON-NLS-1$
                    }
                    result.append(CommonUtils.compactWhiteSpaces(comment));
                    lastWhitespace = false;
                } else {
                    result.append(lexeme);
                    lastWhitespace = false;
                }
            }
            if (!lastWhitespace && line < root.getElementIndex(end)) {
                // The line break between two lines
                result.append(' ');
                lastWhitespace = true;
            }
        }
        return result.toString().strip();
    }

    @NotNull
    private static String stripLineComment(@NotNull String comment, @NotNull SQLDialect dialect) {
        String[] prefixes = dialect.getSingleLineComments();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                if (!CommonUtils.isEmpty(prefix) && comment.startsWith(prefix)) {
                    return comment.substring(prefix.length());
                }
            }
        }
        return comment;
    }

    /**
     * Clipboard contents with an HTML flavor (pasted with colors into mail or documents) and the plain text.
     */
    private static final class HtmlSelection implements Transferable {
        private static final DataFlavor HTML_FLAVOR = createHtmlFlavor();
        private final String html;
        private final String text;

        HtmlSelection(@NotNull String html, @NotNull String text) {
            this.html = html;
            this.text = text;
        }

        @Nullable
        private static DataFlavor createHtmlFlavor() {
            try {
                return new DataFlavor("text/html;class=java.lang.String"); //$NON-NLS-1$
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return HTML_FLAVOR != null ? new DataFlavor[]{HTML_FLAVOR, DataFlavor.stringFlavor} : new DataFlavor[]{DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(HTML_FLAVOR) || flavor.equals(DataFlavor.stringFlavor);
        }

        @NotNull
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(HTML_FLAVOR)) {
                return html;
            }
            if (flavor.equals(DataFlavor.stringFlavor)) {
                // Plain text targets (editors) get the HTML source, as the SWT converter's text
                return html;
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }

    //////////////////////////////////////////////////////////
    // Search the web

    /**
     * Opens the default browser with a web search for the selected text; nothing is sent without a selection.
     */
    static void searchWeb(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        String selected = textArea.getSelectedText();
        if (CommonUtils.isEmptyTrimmed(selected)) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(SEARCH_WEB_ADDRESS_PREFIX + URLEncoder.encode(selected.strip(), StandardCharsets.UTF_8)));
            } else {
                UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            }
        } catch (Exception e) {
            log.warn("Can't open the web search", e);
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        }
    }

    //////////////////////////////////////////////////////////
    // Helpers

    @NotNull
    private static Element getLine(@NotNull SqlEditorPanel.SqlTextArea textArea, int offset) {
        Element root = textArea.getDocument().getDefaultRootElement();
        return root.getElement(root.getElementIndex(offset));
    }

    /**
     * End of a line's text, before its line break.
     */
    private static int lineEnd(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull Element line) {
        return Math.min(line.getEndOffset() - 1, textArea.getDocument().getLength());
    }

    @NotNull
    private static String getText(@NotNull SqlEditorPanel.SqlTextArea textArea, int start, int end) {
        try {
            return textArea.getDocument().getText(start, Math.max(0, end - start));
        } catch (BadLocationException e) {
            return ""; //$NON-NLS-1$
        }
    }

    private static void replaceRange(@NotNull SqlEditorPanel.SqlTextArea textArea, int start, int end, @NotNull String text) {
        if (getText(textArea, start, end).equals(text)) {
            return;
        }
        textArea.beginAtomicEdit();
        try {
            textArea.getDocument().remove(start, end - start);
            textArea.getDocument().insertString(start, text, null);
        } catch (BadLocationException e) {
            log.debug(e);
        } finally {
            textArea.endAtomicEdit();
        }
    }
}
