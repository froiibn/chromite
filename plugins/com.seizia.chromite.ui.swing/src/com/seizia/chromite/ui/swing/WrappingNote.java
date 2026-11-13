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

import com.formdev.flatlaf.ui.FlatTextAreaUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * An explanatory sentence that looks like a label and wraps at the spaces between words when its place is narrower than
 * the sentence: one line where it fits, more lines where it does not, never cut.
 *
 * 설계:
 * <ul>
 *     <li>한 줄 {@link JLabel}은 배치가 준 폭이 글보다 좁으면 말줄임표로 잘린다. 옵션 페이지의 안내 글(MCP 서버 설명, 템플릿 안내)이
 *     대화상자 폭과 글꼴(13)에 따라 그렇게 잘렸다(앱 변경 #71 b). 이 글은 줄을 바꾼다.</li>
 *     <li>HTML 라벨은 접히지만 높이가 마지막으로 그린 폭을 따라가 배치가 한 번 늦거나(좁게 접힌 채 남음) 처음 배치에서 한 줄 높이만 받는다.
 *     그래서 선호 크기를 직접 정한다: 폭은 한 줄로 놓을 때의 폭(자리가 있으면 한 줄), 높이는 배치가 준 지금 폭에서 접은 줄 수의 높이다.
 *     폭이 바뀌면 다시 배치를 한 번 요청해 높이가 줄 수를 따라간다({@code OptionsForm}의 카드와 같은 방법).</li>
 *     <li>{@code pack()}하는 대화상자에서는 한 줄 폭이 그대로 대화상자 폭이 되므로, 부르는 쪽이 가장 넓은 폭({@code wrapWidth})을 줄 수 있다.
 *     그러면 선호 폭은 한 줄 폭과 그 폭 중 작은 것이고, 배치 전 높이는 그 폭에서 접은 줄 수다(대신하는 HTML 라벨의 {@code body} 폭과 같은 뜻).
 *     배치가 더 넓은 폭을 주면 거기서 접고 높이가 줄어든다.</li>
 *     <li>줄은 공백에서만 바꾼다(모든 언어, 한국어의 keep-all). Swing의 줄 바꿈({@code JTextArea}의 단어 줄 바꿈, HTML)은 유니코드 줄 나눔 규칙을
 *     따라 한글 음절 사이 어디서나 끊어 "받습니 / 다."처럼 낱말을 갈랐다. 그래서 줄 나누기({@link #wrap})와 그리기를 이 클래스가 직접 한다.
 *     줄 하나보다 넓은 낱말 하나만 글자 사이에서 끊는다(잘리거나 밖으로 그려지는 일이 없게). 숨은 문자를 넣지 않아 글꼴에 따라 달라지지 않는다.</li>
 *     <li>줄 끝을 정하는 규칙은 {@link #breakOffset} 하나다. 메시지·확인·오류 대화상자의 글({@link MessageArea},
 *     {@code SwingPlatformUI.createMessageComponent})도 같은 규칙을 쓴다. 그 글은 선택·복사가 되어야 해서 직접 그리지 않고, Swing의 줄 바꿈
 *     보기({@code WrappedPlainView})에서 줄 끝 위치만 이 규칙으로 바꾼다.</li>
 *     <li>아이콘이 붙은 안내({@link #withIcon})는 라벨이 아이콘과 글을 놓던 대로 아이콘을 글 묶음의 세로 가운데에 둔다. 글 칸이 아이콘 높이만큼
 *     늘어나 위에서부터 그리면 한 줄 안내가 아이콘보다 위에 붙는다.</li>
 *     <li>줄을 바꾸는 다른 글 상자(설명·세부 칸, 입력 칸, 크기를 스스로 정하는 안내)는 글 상자 그대로 {@link MessageArea}를 쓴다: 크기·선택·
 *     입력은 Swing 그대로이고 줄 끝만 이 규칙이다.</li>
 *     <li>최소 폭은 가장 긴 낱말의 폭이다.</li>
 *     <li>읽기 전용 {@link JTextArea}로 둔다(부르는 쪽의 형식과 접근성 글 그대로). 그리기는 줄마다 FlatLaf와 같은 글자 그리기다.
 *     글꼴은 라벨 글꼴(테마·밀도 전환을 따름)을 기본값으로 두고 그 위에 부르는 쪽의 FlatLaf 스타일이 적용된다(스타일 클래스 {@code small}),
 *     색은 부르는 쪽이 FlatLaf 스타일로 정한다(보조 글자색, 경고 글자색).
 *     포커스를 받지 않고 고칠 수 없으며 바탕을 칠하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 옵션의 한 줄 안내가 잘리던 것(앱 변경 #71 b, docs/chromite/swing-options.md "잘리던 한 줄 안내")
 * - 2026-11-12 Chromite Agent 패널의 두 안내(제공자 설정, 도구 없음)도 씀: HTML 라벨이 좁은 칸으로 접혀 잘렸다(앱 변경 #71 f)
 * - 2026-11-12 줄을 공백에서만 바꿈({@link #wrap}, 직접 그림): {@code JTextArea}의 줄 바꿈이 한국어 낱말 가운데서 끊었다
 *   ("받습니 / 다.", "설정 / 하세요.", 앱 변경 #71 검토)
 * - 2026-11-12 줄 끝 규칙을 {@link #breakOffset}으로 떼어 메시지 대화상자의 글({@link MessageArea})과 함께 씀: 메시지 영역이 Swing 줄 바꿈으로
 *   "…입니 / 다.", "넣 / 으십시오."처럼 끊었다(앱 변경 #82, docs/chromite/swing-options.md "잘리던 한 줄 안내")
 * - 2026-11-12 {@link MessageArea}는 낱말 단위로 줄을 바꿀 때({@code lineWrap}, {@code wrapStyleWord})만 이 줄 바꿈 보기를 씀: 줄을 바꾸지 않는
 *   글 상자도 늘 줄을 바꾸게 되던 것(#82 검토 후속)
 * - 2026-11-13 가장 넓은 폭({@code wrapWidth})과 라벨 글꼴을 기본값으로 두는 UI: 폭을 정한 HTML 라벨로 접던 안내(연결·설정 내보내기와
 *   가져오기, 셸 명령 승인 등)가 Swing 줄 바꿈으로 한국어 낱말 가운데서 끊었다. 그 안내를 이 클래스로 옮기면서 {@code pack()}하는 대화상자의
 *   폭과 작은 글꼴(스타일 클래스)을 그대로 두려고(앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
 * - 2026-11-13 {@link #withIcon}: 경고 아이콘과 안내를 라벨처럼 세로 가운데로 맞춤(BorderLayout에서는 한 줄 안내가 아이콘 가운데보다 위에
 *   있었다, #90 검토)
 * - 2026-11-13 {@code wrapWidth}는 글의 폭이고 테두리는 그 위에 더함: 테두리가 있는 업데이트 실패 메뉴 설명이 HTML 라벨의 {@code div} 폭보다
 *   좁게 접혔다(#90 2차)
 * - 2026-11-13 {@link MessageArea}를 final에서 풀고 행·열 생성자를 둠: Swing 단어 줄 바꿈을 쓰던 글 상자(작업 대화상자의 안내, 설명·세부 칸,
 *   커밋 메시지)가 같은 규칙을 쓰도록(#90 2차)
 */
final class WrappingNote extends JTextArea {

    // The widest text the note asks for, its insets come on top (0: its one-line width)
    private final int wrapWidth;

    WrappingNote(@NotNull String text) {
        this(text, 0);
    }

    /**
     * @param wrapWidth the widest text the note asks for (its insets, a border, come on top, as around an HTML label's body):
     *                  a longer text wraps at this width before the layout gives the note a width, so a packed dialog keeps
     *                  its width and gets the height of the wrapped lines
     */
    WrappingNote(@NotNull String text, int wrapWidth) {
        super(text);
        this.wrapWidth = wrapWidth;
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getDefaultCursor());
    }

    /**
     * An icon beside a note, as a label with an icon and a text showed them: the icon is centred on the text block (a
     * one-line note is centred on the icon) and the note takes the rest of the width.
     */
    @NotNull
    static JComponent withIcon(@Nullable Icon icon, @NotNull String text, int wrapWidth) {
        JLabel iconLabel = new JLabel(icon);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        // Each cell keeps its preferred height and is centred in the row (the taller of the icon and the text)
        panel.add(iconLabel, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, iconLabel.getIconTextGap(), 0, 0);
        panel.add(new WrappingNote(text, wrapWidth), c);
        return panel;
    }

    @Override
    public void updateUI() {
        // The theme's text area UI with a label's font as the default font, also after a theme or density change (the
        // text area's own font is the editor font). The caller's FlatLaf style (style class "small") is applied after
        // the defaults, so it still sets the font.
        setUI(new FlatTextAreaUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                getComponent().setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        Insets insets = getInsets();
        int oneLine = 0;
        for (String paragraph : getText().split("\n", -1)) { //$NON-NLS-1$
            oneLine = Math.max(oneLine, measure(paragraph));
        }
        oneLine += insets.left + insets.right;
        int width = wrapWidth > 0 ? Math.min(oneLine, wrapWidth + insets.left + insets.right) : oneLine;
        // The height at the width the layout gave (the lines it wraps into there); before it has a width, the height at
        // the width it asks for
        return new Dimension(width, getHeightAt(getWidth() > 0 ? getWidth() : width));
    }

    @Override
    public Dimension getMinimumSize() {
        if (isMinimumSizeSet()) {
            return super.getMinimumSize();
        }
        Insets insets = getInsets();
        int word = 0;
        for (String part : getText().split("\\s+")) { //$NON-NLS-1$
            word = Math.max(word, measure(part));
        }
        return new Dimension(word + insets.left + insets.right, getPreferredSize().height);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        int oldWidth = getWidth();
        super.setBounds(x, y, width, height);
        if (oldWidth != width) {
            // The height depends on the width: lay the parent out again for the new number of lines
            SwingUtilities.invokeLater(this::revalidate);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        // The lines of wrap(), not the text area's own view (its wrapping breaks inside Korean words)
        Insets insets = getInsets();
        FontMetrics metrics = getFontMetrics(getFont());
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setFont(getFont());
            g2.setColor(getForeground());
            int y = insets.top + metrics.getAscent();
            for (String line : getLines(getWidth())) {
                FlatUIUtils.drawString(this, g2, line, insets.left, y);
                y += metrics.getHeight();
            }
        } finally {
            g2.dispose();
        }
    }

    @NotNull
    private List<String> getLines(int width) {
        Insets insets = getInsets();
        return wrap(getText(), Math.max(1, width - insets.left - insets.right), text -> measureExact(text));
    }

    private int getHeightAt(int width) {
        Insets insets = getInsets();
        return getLines(width).size() * getFontMetrics(getFont()).getHeight() + insets.top + insets.bottom;
    }

    // Width of a text in the component's font rendering, as it is drawn
    private double measureExact(@NotNull String text) {
        FontMetrics metrics = getFontMetrics(getFont());
        return getFont().getStringBounds(text, metrics.getFontRenderContext()).getWidth();
    }

    // Rounded up, so that a line measured to fit is not wrapped
    private int measure(@NotNull String text) {
        return (int) Math.ceil(measureExact(text));
    }

    /**
     * The lines of a text at a width: paragraphs at line feeds, lines only at whitespace (one space between the words of
     * a line), a word wider than the whole width alone broken between characters. Whitespace at the ends of a line is
     * dropped.
     *
     * @param measure width of a text, in the same unit as {@code width}
     */
    @NotNull
    static List<String> wrap(@NotNull String text, double width, @NotNull ToDoubleFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) { //$NON-NLS-1$
            String rest = paragraph.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
            if (rest.isEmpty()) {
                lines.add(rest);
                continue;
            }
            while (!rest.isEmpty()) {
                int end = breakOffset(rest, width, measure);
                lines.add(rest.substring(0, end).stripTrailing());
                rest = rest.substring(end);
            }
        }
        return lines;
    }

    /**
     * Where the first line of a text ends at a width (the line breaking rule of this class, shared by
     * {@link #wrap} and the message areas, {@link MessageArea}): after the last whole word that fits, with the whitespace
     * after it, so that the next line starts with a word. Only a first word wider than the whole width breaks inside,
     * between characters (whole code points, at least one), so that nothing is cut or drawn outside.
     *
     * @param measure width of a beginning of the text (only beginnings are measured), in the same unit as {@code width}
     * @return the length of the first line, {@code text.length()} when the whole text fits
     */
    static int breakOffset(@NotNull String text, double width, @NotNull ToDoubleFunction<String> measure) {
        if (measure.applyAsDouble(text.stripTrailing()) <= width) {
            return text.length();
        }
        int fit = 0;
        int index = 0;
        while (index < text.length()) {
            int wordStart = index;
            while (wordStart < text.length() && Character.isWhitespace(text.charAt(wordStart))) {
                wordStart++;
            }
            int wordEnd = wordStart;
            while (wordEnd < text.length() && !Character.isWhitespace(text.charAt(wordEnd))) {
                wordEnd++;
            }
            if (wordEnd == wordStart || measure.applyAsDouble(text.substring(0, wordEnd)) > width) {
                break;
            }
            fit = wordEnd;
            index = wordEnd;
        }
        if (fit > 0) {
            // The spaces after the last word stay at the end of this line
            while (fit < text.length() && Character.isWhitespace(text.charAt(fit))) {
                fit++;
            }
            return fit;
        }
        // The first word alone is wider than the line: break it between characters
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = text.offsetByCodePoints(start, 1);
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            int next = text.offsetByCodePoints(end, 1);
            if (measure.applyAsDouble(text.substring(0, next)) > width) {
                break;
            }
            end = next;
        }
        return end;
    }

    /**
     * A selectable, read-only message text (message, confirmation and error dialogs) that wraps by the rule of
     * {@link #breakOffset}: the text area's own word wrapping breaks between any two Hangul syllables. Only the line
     * breaking changes; selecting and copying stay as in any text area. Only while the area wraps at words
     * ({@code lineWrap} and {@code wrapStyleWord}): otherwise its views are Swing's, as for any text area. The base UI
     * builds the views again when either property changes ({@code BasicTextAreaUI.propertyChange} → {@code modelChanged}),
     * so the choice follows them.
     * <p>
     * Any wrapping text area of the application uses this class (details, descriptions, a commit message), and so do
     * notes whose layout relies on the text area's own size: not final, so that such a note can keep its size rules
     * ({@code DatabaseTaskDialog.FormPanel.note}).
     */
    static class MessageArea extends JTextArea {

        MessageArea(@NotNull String text) {
            super(text);
        }

        MessageArea(@NotNull String text, int rows, int columns) {
            super(text, rows, columns);
        }

        @Override
        public void updateUI() {
            // Also after a theme change: the theme's text area UI, with this class's wrapped view
            setUI(new FlatTextAreaUI() {
                @Override
                public View create(Element element) {
                    // No wrapping or wrapping at any character: Swing's views (PlainView, WrappedPlainView by characters)
                    if (!getLineWrap() || !getWrapStyleWord()
                        // AbstractDocument's (package-private) I18NProperty, as BasicTextAreaUI.create reads it
                        || Boolean.TRUE.equals(element.getDocument().getProperty("i18n"))) { //$NON-NLS-1$
                        // Bidirectional or complex text: Swing's own views lay it out (glyph by glyph)
                        return super.create(element);
                    }
                    return new SpaceWrappedView(element);
                }
            });
        }
    }

    /**
     * The wrapped view of {@link MessageArea}: Swing's wrapped plain text view with the line break of {@link #breakOffset}.
     */
    private static final class SpaceWrappedView extends WrappedPlainView {

        SpaceWrappedView(@NotNull Element element) {
            super(element, true);
        }

        @Override
        protected int calculateBreakPosition(int p0, int p1) {
            Segment segment = new Segment();
            try {
                getDocument().getText(p0, p1 - p0, segment);
            } catch (BadLocationException e) {
                return super.calculateBreakPosition(p0, p1);
            }
            Container container = getContainer();
            FontMetrics metrics = container.getFontMetrics(container.getFont());
            String text = segment.toString();
            // Measured as the view draws a line (tabs expanded by this view)
            int end = breakOffset(text, getWidth(), part -> Utilities.getTabbedTextWidth(
                new Segment(segment.array, segment.offset, part.length()), metrics, 0f, this, p0));
            return p0 + end;
        }
    }
}
