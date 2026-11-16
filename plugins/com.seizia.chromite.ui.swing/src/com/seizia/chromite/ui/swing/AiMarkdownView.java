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
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the model's Markdown answer: text blocks as HTML, fenced code blocks as read-only SQL editors with Copy and
 * Open in Editor buttons.
 *
 * 설계:
 * <ul>
 *     <li>Markdown 라이브러리를 더하지 않고 모델 답에 실제로 나오는 부분만 해석한다: 제목, 굵게·기울임, 인라인 코드, 목록, 인용, 표, 가로줄,
 *     울타리 코드 블록. 글 블록은 {@link JEditorPane}(HTML)이고 글꼴·색은 컴포넌트 값을 따른다({@link JEditorPane#HONOR_DISPLAY_PROPERTIES}).
 *     모델 글은 HTML로 쓰기 전에 모두 이스케이프한다(모델이 낸 HTML·스크립트는 글자로만 보인다).</li>
 *     <li>코드 블록은 쿼리 창과 같은 SQL 편집기 색({@code SqlEditorPanel.SqlTextArea#applyTheme})으로 보이고, "쿼리 창에서 열기"는
 *     대화의 연결로 새 쿼리 창을 연다(실행하지 않음).</li>
 *     <li>스트리밍: 글이 늘 때마다 블록 목록을 다시 해석하되, 바뀌지 않은 앞 블록의 컴포넌트는 그대로 두고 바뀐 블록부터 고친다. 긴 답에서도
 *     이미 읽은 부분이 깜빡이지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 */
final class AiMarkdownView extends JPanel {

    private static final Pattern FENCE = Pattern.compile("^\\s*```\\s*([\\w+-]*)\\s*$"); //$NON-NLS-1$
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$"); //$NON-NLS-1$
    private static final Pattern RULE = Pattern.compile("^\\s*(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$"); //$NON-NLS-1$

    /**
     * Opens SQL in a new query window of the conversation's connection.
     */
    private final Consumer<String> openInEditor;
    private final List<Block> blocks = new ArrayList<>();
    private final List<JComponent> components = new ArrayList<>();

    private record Block(boolean code, @NotNull String language, @NotNull String text) {
    }

    AiMarkdownView(@NotNull Consumer<String> openInEditor) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        this.openInEditor = openInEditor;
    }

    /**
     * Shows the text, updating only the blocks that changed.
     */
    void setMarkdown(@NotNull String markdown) {
        List<Block> parsed = parse(markdown);
        int keep = 0;
        while (keep < parsed.size() && keep < blocks.size() && parsed.get(keep).equals(blocks.get(keep))) {
            keep++;
        }
        // The first changed block: update it in place when its kind did not change (the streamed last block)
        if (keep < parsed.size() && keep < blocks.size() && keep == blocks.size() - 1
            && parsed.get(keep).code() == blocks.get(keep).code() && parsed.get(keep).language().equals(blocks.get(keep).language())) {
            update(components.get(keep), parsed.get(keep));
            blocks.set(keep, parsed.get(keep));
            keep++;
        }
        while (blocks.size() > keep) {
            blocks.removeLast();
            remove(components.removeLast());
        }
        for (int i = keep; i < parsed.size(); i++) {
            Block block = parsed.get(i);
            JComponent component = block.code() ? new CodeBlock(block) : createTextPane(block.text());
            component.setAlignmentX(LEFT_ALIGNMENT);
            blocks.add(block);
            components.add(component);
            add(component);
        }
        revalidate();
        repaint();
    }

    private void update(@NotNull JComponent component, @NotNull Block block) {
        if (component instanceof CodeBlock codeBlock) {
            codeBlock.setCode(block.text());
        } else if (component instanceof JEditorPane pane) {
            pane.setText(toHtml(block.text()));
        }
    }

    @NotNull
    private static JEditorPane createTextPane(@NotNull String markdown) {
        JEditorPane pane = new JEditorPane() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        pane.setEditorKit(new HTMLEditorKit());
        pane.setEditable(false);
        pane.setOpaque(false);
        // The component's font and color (from the look and feel, so a theme switch updates them) instead of the HTML defaults
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        pane.setText(toHtml(markdown));
        return pane;
    }

    //////////////////////////////////////////////////////////
    // Markdown

    /**
     * Splits the text into text blocks and fenced code blocks; an unclosed fence (still streaming) is a code block too.
     */
    @NotNull
    static List<Block> parse(@NotNull String markdown) {
        List<Block> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder code = null;
        String language = ""; //$NON-NLS-1$
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Matcher fence = FENCE.matcher(line);
            if (code == null && fence.matches()) {
                if (!text.toString().isBlank()) {
                    result.add(new Block(false, "", text.toString().strip())); //$NON-NLS-1$
                }
                text.setLength(0);
                code = new StringBuilder();
                language = fence.group(1).toLowerCase(Locale.ROOT);
            } else if (code != null && line.strip().equals("```")) { //$NON-NLS-1$
                result.add(new Block(true, language, stripLastNewline(code)));
                code = null;
            } else if (code != null) {
                code.append(line).append('\n');
            } else {
                text.append(line).append('\n');
            }
        }
        if (code != null) {
            result.add(new Block(true, language, stripLastNewline(code)));
        } else if (!text.toString().isBlank()) {
            result.add(new Block(false, "", text.toString().strip())); //$NON-NLS-1$
        }
        return result;
    }

    @NotNull
    private static String stripLastNewline(@NotNull StringBuilder code) {
        int length = code.length();
        return length > 0 && code.charAt(length - 1) == '\n' ? code.substring(0, length - 1) : code.toString();
    }

    /**
     * HTML of a text block. Everything from the model is escaped first; only the Markdown marks become tags.
     */
    @NotNull
    static String toHtml(@NotNull String markdown) {
        // Compact spacing for a narrow tool window (the HTML defaults are for pages)
        StringBuilder html = new StringBuilder("<html><head><style>" //$NON-NLS-1$
            + "p { margin-top: 2px; margin-bottom: 4px; } ul, ol { margin-top: 2px; margin-bottom: 4px; margin-left: 18px; } " //$NON-NLS-1$
            + "h3 { margin-top: 6px; margin-bottom: 2px; font-size: 115%; } h4 { margin-top: 4px; margin-bottom: 2px; font-size: 100%; } " //$NON-NLS-1$
            + "blockquote { margin-left: 8px; } td, th { padding: 2px 4px; }" //$NON-NLS-1$
            + "</style></head><body>"); //$NON-NLS-1$
        String[] lines = markdown.split("\n", -1); //$NON-NLS-1$
        StringBuilder paragraph = new StringBuilder();
        String listTag = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean rule = RULE.matcher(line).matches();
            Matcher heading = HEADING.matcher(line);
            // "* * *" is a rule, not a list item
            Matcher bullet = BULLET.matcher(rule ? "" : line); //$NON-NLS-1$
            Matcher numbered = NUMBERED.matcher(line);
            boolean table = line.trim().startsWith("|") && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches(); //$NON-NLS-1$
            if (line.isBlank() || heading.matches() || bullet.matches() || numbered.matches() || table
                || line.startsWith(">") || rule) { //$NON-NLS-1$
                flushParagraph(html, paragraph);
            }
            String wantedList = bullet.matches() ? "ul" : numbered.matches() ? "ol" : null; //$NON-NLS-1$ //$NON-NLS-2$
            if (listTag != null && (wantedList == null || !wantedList.equals(listTag)) && !line.isBlank()) {
                html.append("</").append(listTag).append('>'); //$NON-NLS-1$
                listTag = null;
            }
            if (line.isBlank()) {
                continue;
            }
            if (heading.matches()) {
                // # and ## are the section titles of an answer; deeper levels are bold lines
                int level = heading.group(1).length() <= 2 ? 3 : 4;
                html.append("<h").append(level).append('>').append(inline(heading.group(2))).append("</h").append(level).append('>'); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (wantedList != null) {
                if (listTag == null) {
                    html.append('<').append(wantedList).append('>');
                    listTag = wantedList;
                }
                html.append("<li>").append(inline(bullet.matches() ? bullet.group(2) : numbered.group(2))).append("</li>"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (table) {
                i = appendTable(html, lines, i);
            } else if (line.startsWith(">")) { //$NON-NLS-1$
                html.append("<blockquote>").append(inline(line.substring(1).strip())).append("</blockquote>"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (rule) {
                html.append("<hr>"); //$NON-NLS-1$
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append("<br>"); //$NON-NLS-1$
                }
                paragraph.append(inline(line.strip()));
            }
        }
        flushParagraph(html, paragraph);
        if (listTag != null) {
            html.append("</").append(listTag).append('>'); //$NON-NLS-1$
        }
        return html.append("</body></html>").toString(); //$NON-NLS-1$
    }

    private static void flushParagraph(@NotNull StringBuilder html, @NotNull StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            html.append("<p>").append(paragraph).append("</p>"); //$NON-NLS-1$ //$NON-NLS-2$
            paragraph.setLength(0);
        }
    }

    // Returns the index of the table's last line
    private static int appendTable(@NotNull StringBuilder html, @NotNull String[] lines, int start) {
        html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\">"); //$NON-NLS-1$
        appendRow(html, lines[start], "th"); //$NON-NLS-1$
        int i = start + 2;
        while (i < lines.length && lines[i].trim().startsWith("|")) { //$NON-NLS-1$
            appendRow(html, lines[i], "td"); //$NON-NLS-1$
            i++;
        }
        html.append("</table>"); //$NON-NLS-1$
        return i - 1;
    }

    private static void appendRow(@NotNull StringBuilder html, @NotNull String line, @NotNull String cellTag) {
        String row = line.trim();
        if (row.startsWith("|")) { //$NON-NLS-1$
            row = row.substring(1);
        }
        if (row.endsWith("|")) { //$NON-NLS-1$
            row = row.substring(0, row.length() - 1);
        }
        html.append("<tr>"); //$NON-NLS-1$
        for (String cell : row.split("\\|", -1)) { //$NON-NLS-1$
            html.append('<').append(cellTag).append('>').append(inline(cell.strip())).append("</").append(cellTag).append('>'); //$NON-NLS-1$
        }
        html.append("</tr>"); //$NON-NLS-1$
    }

    /**
     * Inline marks: `code`, **bold**, *italic* / _italic_, [text](url) shown as text.
     */
    @NotNull
    static String inline(@NotNull String text) {
        StringBuilder out = new StringBuilder();
        String[] parts = text.split("`", -1); //$NON-NLS-1$
        for (int i = 0; i < parts.length; i++) {
            String part = escape(parts[i]);
            if (i % 2 == 1 && i < parts.length - 1) {
                out.append("<code>").append(part).append("</code>"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (i % 2 == 1) {
                // An unpaired backtick (still streaming)
                out.append('`');
            }
            part = part.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>"); //$NON-NLS-1$ //$NON-NLS-2$
            part = part.replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "<i>$1</i>"); //$NON-NLS-1$ //$NON-NLS-2$
            part = part.replaceAll("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)", "<i>$1</i>"); //$NON-NLS-1$ //$NON-NLS-2$
            part = part.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1"); //$NON-NLS-1$ //$NON-NLS-2$
            out.append(part);
        }
        return out.toString();
    }

    @NotNull
    private static String escape(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;"); //$NON-NLS-1$
                case '>' -> out.append("&gt;"); //$NON-NLS-1$
                case '&' -> out.append("&amp;"); //$NON-NLS-1$
                case '"' -> out.append("&quot;"); //$NON-NLS-1$
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A fenced code block: language, Copy and Open in Editor above a read-only SQL editor.
     */
    private final class CodeBlock extends JPanel {
        private final SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();

        CodeBlock(@NotNull Block block) {
            super(new BorderLayout());
            setOpaque(false);
            putClientProperty(FlatClientProperties.STYLE, "border: 2,2,2,2,$Component.borderColor,1,8"); //$NON-NLS-1$
            JPanel bar = new JPanel(new BorderLayout());
            bar.setOpaque(false);
            JLabel language = new JLabel(block.language().isEmpty() ? AiMessages.ai_code_block : block.language());
            language.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            language.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            language.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            bar.add(language, BorderLayout.WEST);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            buttons.setOpaque(false);
            buttons.add(createButton(AiMessages.ai_code_copy, "copy", //$NON-NLS-1$
                () -> DBWorkbench.getPlatformUI().copyTextToClipboard(textArea.getText(), false)));
            buttons.add(createButton(AiMessages.ai_code_open, "new-query", () -> openInEditor.accept(textArea.getText()))); //$NON-NLS-1$
            bar.add(buttons, BorderLayout.EAST);
            add(bar, BorderLayout.NORTH);
            if (!isSql(block.language())) {
                textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            }
            textArea.setEditable(false);
            // Read-only text without a caret; Copy and Open in Query Window take the whole block
            textArea.setFocusable(false);
            textArea.setHighlightCurrentLine(false);
            // Long lines wrap: a horizontal scroll bar in a narrow tool window would hide the last line
            textArea.setLineWrap(true);
            textArea.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
            setCode(block.text());
            textArea.applyTheme();
            add(textArea, BorderLayout.CENTER);
        }

        void setCode(@NotNull String code) {
            textArea.setText(code);
            textArea.setCaretPosition(0);
            textArea.discardAllEdits();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private static boolean isSql(@Nullable String language) {
            return language == null || language.isEmpty() || language.contains("sql") || language.equals("plsql"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @NotNull
        private static JButton createButton(@NotNull String toolTip, @NotNull String icon, @NotNull Runnable task) {
            JButton button = new JButton(UiIcons.get(icon));
            button.setToolTipText(toolTip);
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setFocusable(false);
            button.addActionListener(e -> task.run());
            return button;
        }
    }
}
