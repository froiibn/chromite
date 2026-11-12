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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.LineNumberFormatter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * 두 글을 나란히 놓고 차이를 칠하는 보기. 비교 문서 탭({@link TextComparePanel})과 스키마 비교의 DDL 보기({@link SchemaComparePanel})가 함께 쓴다.
 *
 * 설계:
 * <ul>
 *     <li>두 칸은 쿼리 창과 같은 {@link SqlEditorPanel.SqlTextArea}(구문 색, 테마, 글꼴)를 읽기 전용으로 쓰고, 줄 번호를 보인다. 접기는 끈다
 *     (한쪽만 접히면 좌우 줄이 어긋난다).</li>
 *     <li>좌우 맞춤은 빈 채움 행으로 한다: 한쪽에만 있는 줄의 맞은편에 빈 행을 넣어 같은 줄이 늘 마주 보게 한다. 두 칸의 행 수와 줄 높이가 같으므로
 *     세로 스크롤을 같은 값으로 맞추면 되고(연결 띠를 그리는 방식보다 단순하다), 가로 스크롤도 함께 움직인다. 줄 번호 칸은 원래 줄 번호를 보이고
 *     채움 행은 비운다(RSyntaxTextArea {@code LineNumberFormatter}).</li>
 *     <li>색은 스키마 비교의 색 그대로다: 왼쪽(원본)에만 있거나 바뀐 줄은 초록, 오른쪽(대상)은 빨강, 반투명이라 밝은·어두운 테마 모두에서 읽힌다.
 *     바뀐 줄 안의 다른 낱말은 같은 색을 더 진하게 칠하고, 채움 행은 옅은 회색이다. 쿼리 창의 실행 중 문장 색
 *     ({@code Chromite.editor.runningStatementBackground}, 연두·올리브)과는 색상이 다르고, 이 보기에는 실행 중 표시가 없다.</li>
 *     <li>오른쪽 칸 옆의 개요 띠는 차이가 있는 행의 자리를 보인다(왼쪽 반은 왼쪽 칸, 오른쪽 반은 오른쪽 칸의 차이). 글이 띠보다 길면 띠 전체에
 *     비례해 놓고, 짧으면 행 높이 그대로 놓아 표시가 그 행 옆에 선다. 누르면 그 행으로 간다.
 *     RSyntaxTextArea의 {@code ErrorStrip}은 파서 알림으로만 표시를 받아 차이마다 파서를 붙여야 하므로 쓰지 않았다.</li>
 *     <li>비교({@link TextDiff})는 EDT 밖의 작업에서 하고, 마지막으로 요청한 비교의 결과만 보인다(번호로 확인). 2만 줄도 화면이 멈추지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 스키마 비교의 두 DDL 보기를 옮겨 텍스트 비교와 함께 씀 (docs/chromite/text-compare.md)
 */
final class TextDiffView extends JPanel {

    private static final Log log = Log.getLog(TextDiffView.class);

    /**
     * Lines of the left side (the schema compare's source) and of the right side (target): translucent, so they read on light
     * and dark editor backgrounds. The schema compare used these colours before the view was shared.
     */
    private static final Color LEFT_LINE_COLOR = new Color(0x2E, 0xA0, 0x43, 0x48);
    private static final Color RIGHT_LINE_COLOR = new Color(0xE5, 0x53, 0x4B, 0x48);
    /**
     * The differing words of a changed line: the side's colour, stronger.
     */
    private static final Color LEFT_WORD_COLOR = new Color(0x2E, 0xA0, 0x43, 0x90);
    private static final Color RIGHT_WORD_COLOR = new Color(0xE5, 0x53, 0x4B, 0x90);
    /**
     * Empty rows that keep the lines of the two sides opposite each other.
     */
    private static final Color FILLER_COLOR = new Color(0x80, 0x80, 0x80, 0x28);
    private static final int STRIP_WIDTH = 10;

    /**
     * What a comparison showed, for the caller's status (EDT).
     *
     * @param differences  the number of differences (runs of differing rows)
     * @param tooLarge     a side was too large to compare ({@link TextDiff#MAX_LINES}, {@link TextDiff#MAX_CHARS})
     * @param exactlyEqual no difference even without the options
     * @param millis       the time the comparison took
     */
    record Outcome(int differences, boolean tooLarge, boolean exactlyEqual, long millis) {
    }

    private final Side left = new Side(LEFT_LINE_COLOR, LEFT_WORD_COLOR);
    private final Side right = new Side(RIGHT_LINE_COLOR, RIGHT_WORD_COLOR);
    private final OverviewStrip strip = new OverviewStrip();
    @Nullable
    private TextDiff diff;
    private int rowCount;
    @Nullable
    private AbstractJob job;
    private int serial;
    private boolean syncing;
    private Side lastFocused = left;

    TextDiffView() {
        super(new BorderLayout());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left.pane, right.pane);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        // Beside the right text, so that its top is the first row's top
        right.pane.add(strip, BorderLayout.EAST);
        syncScrolling(left.scrollPane.getVerticalScrollBar().getModel(), right.scrollPane.getVerticalScrollBar().getModel());
        syncScrolling(left.scrollPane.getHorizontalScrollBar().getModel(), right.scrollPane.getHorizontalScrollBar().getModel());
    }

    /**
     * The header of a side: a plain text (the schema compare).
     */
    void setHeader(boolean leftSide, @NotNull String text) {
        (leftSide ? left : right).header.set(null, text, null, null, null);
    }

    /**
     * The header of a side: icon and title, the connection's name in its colour pill, and a note after it.
     */
    void setHeader(boolean leftSide, @Nullable Icon icon, @NotNull String prefix, @Nullable DBPDataSourceContainer connection,
                   @NotNull String suffix, @Nullable String note) {
        (leftSide ? left : right).header.set(icon, prefix, connection, suffix, note);
    }

    /**
     * Compares two texts off the EDT and shows them with their differences; a null text shows the placeholder on its side and
     * the other side as a whole difference.
     *
     * @param leftConnection  connection of the left text, for its SQL dialect, or null
     * @param rightConnection connection of the right text, or null
     * @param done            called on the EDT once the texts are shown (not for a comparison that a newer one replaced)
     */
    void compare(@Nullable String leftText, @Nullable String rightText, @Nullable DBPDataSourceContainer leftConnection,
                 @Nullable DBPDataSourceContainer rightConnection, @NotNull String absentText, @NotNull TextDiff.Options options,
                 @Nullable Consumer<Outcome> done) {
        int current = ++serial;
        cancelJob();
        job = new AbstractJob("Compare texts") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                long start = System.nanoTime();
                TextDiff computed = TextDiff.compute(leftText == null ? "" : leftText, rightText == null ? "" : rightText, options); //$NON-NLS-1$ //$NON-NLS-2$
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.debug("Text compare: " + computed.getLeftLines().length + " / " + computed.getRightLines().length //$NON-NLS-1$ //$NON-NLS-2$
                    + " lines in " + millis + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
                SwingUtilities.invokeLater(() -> {
                    if (current != serial) {
                        return;
                    }
                    show(computed, leftText == null ? absentText : null, rightText == null ? absentText : null, leftConnection, rightConnection);
                    if (done != null) {
                        done.accept(new Outcome(computed.getHunks().size(), computed.isTooLarge(), computed.isExactlyEqual(), millis));
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    /**
     * Empties both sides (no comparison).
     */
    void clear() {
        serial++;
        cancelJob();
        diff = null;
        rowCount = 0;
        left.clear();
        right.clear();
        strip.repaint();
    }

    int getDifferenceCount() {
        return diff == null ? 0 : diff.getHunks().size();
    }

    /**
     * Moves to the next difference after the caret of the side used last, from the last to the first.
     */
    void nextDifference() {
        List<TextDiff.Hunk> hunks = diff == null ? List.of() : diff.getHunks();
        if (hunks.isEmpty()) {
            return;
        }
        int row = lastFocused.getCaretRow();
        for (TextDiff.Hunk hunk : hunks) {
            if (hunk.firstRow() > row) {
                showRow(hunk.firstRow());
                return;
            }
        }
        showRow(hunks.getFirst().firstRow());
    }

    /**
     * Moves to the difference before the one at the caret, from the first to the last.
     */
    void previousDifference() {
        List<TextDiff.Hunk> hunks = diff == null ? List.of() : diff.getHunks();
        if (hunks.isEmpty()) {
            return;
        }
        int row = lastFocused.getCaretRow();
        for (int i = hunks.size() - 1; i >= 0; i--) {
            if (hunks.get(i).lastRow() < row) {
                showRow(hunks.get(i).firstRow());
                return;
            }
        }
        showRow(hunks.getLast().firstRow());
    }

    /**
     * The two text areas, for key bindings of the caller.
     */
    @NotNull
    List<JComponent> getTextAreas() {
        return List.of(left.textArea, right.textArea);
    }

    void dispose() {
        serial++;
        cancelJob();
    }

    private void cancelJob() {
        if (job != null) {
            job.cancel();
            job = null;
        }
    }

    //////////////////////////////////////////////////////////
    // Showing a comparison

    private void show(@NotNull TextDiff computed, @Nullable String leftAbsent, @Nullable String rightAbsent,
                      @Nullable DBPDataSourceContainer leftConnection, @Nullable DBPDataSourceContainer rightConnection) {
        diff = computed;
        left.textArea.setDataSourceContainer(leftConnection);
        right.textArea.setDataSourceContainer(rightConnection);
        if (computed.isTooLarge()) {
            rowCount = 0;
            left.showPlain(String.join("\n", computed.getLeftLines()), false); //$NON-NLS-1$
            right.showPlain(String.join("\n", computed.getRightLines()), false); //$NON-NLS-1$
        } else if (leftAbsent != null || rightAbsent != null) {
            // One side is missing (the schema compare's object on one side only): the other side is a difference as a whole
            rowCount = Math.max(computed.getLeftLines().length, computed.getRightLines().length);
            left.showPlain(leftAbsent != null ? leftAbsent : String.join("\n", computed.getLeftLines()), leftAbsent == null); //$NON-NLS-1$
            right.showPlain(rightAbsent != null ? rightAbsent : String.join("\n", computed.getRightLines()), rightAbsent == null); //$NON-NLS-1$
        } else {
            List<TextDiff.Row> rows = computed.getRows();
            rowCount = rows.size();
            left.showRows(rows, computed.getLeftLines(), true, computed);
            right.showRows(rows, computed.getRightLines(), false, computed);
        }
        strip.repaint();
        List<TextDiff.Hunk> hunks = computed.getHunks();
        if (!computed.isTooLarge() && leftAbsent == null && rightAbsent == null && !hunks.isEmpty()) {
            showRow(hunks.getFirst().firstRow());
        } else {
            left.textArea.setCaretPosition(0);
            right.textArea.setCaretPosition(0);
        }
    }

    /**
     * Puts both carets on a row and scrolls it into the upper part of the view.
     */
    private void showRow(int row) {
        left.setCaretRow(row);
        right.setCaretRow(row);
        try {
            Rectangle rowBounds = left.textArea.modelToView2D(left.textArea.getLineStartOffset(Math.min(row, left.textArea.getLineCount() - 1)))
                .getBounds();
            JViewport viewport = left.scrollPane.getViewport();
            int y = Math.max(0, rowBounds.y - viewport.getExtentSize().height / 4);
            left.scrollPane.getVerticalScrollBar().setValue(y);
            left.scrollPane.getHorizontalScrollBar().setValue(0);
        } catch (BadLocationException e) {
            // The row is beyond the text: nothing to scroll to
        }
        strip.repaint();
    }

    private void syncScrolling(@NotNull BoundedRangeModel first, @NotNull BoundedRangeModel second) {
        first.addChangeListener(e -> follow(first, second));
        second.addChangeListener(e -> follow(second, first));
    }

    private void follow(@NotNull BoundedRangeModel source, @NotNull BoundedRangeModel target) {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            target.setValue(source.getValue());
        } finally {
            syncing = false;
        }
    }

    //////////////////////////////////////////////////////////
    // A side

    private final class Side {
        private final SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();
        private final RTextScrollPane scrollPane;
        private final HeaderLabel header = new HeaderLabel();
        private final JPanel pane = new JPanel(new BorderLayout());
        private final Color lineColor;
        private final Highlighter.HighlightPainter wordPainter;
        /**
         * The line number shown for each row (1-based), 0 for a filler row; null when the rows are the lines.
         */
        @Nullable
        private int[] lineNumbers;

        Side(@NotNull Color lineColor, @NotNull Color wordColor) {
            this.lineColor = lineColor;
            this.wordPainter = new DefaultHighlighter.DefaultHighlightPainter(wordColor);
            textArea.setEditable(false);
            textArea.setHighlightCurrentLine(false);
            // A fold on one side only would move its lines against the other side's
            textArea.setCodeFoldingEnabled(false);
            scrollPane = new RTextScrollPane(textArea, true);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
            textArea.applyTheme();
            Gutter gutter = scrollPane.getGutter();
            gutter.setLineNumberFormatter(new LineNumberFormatter() {
                @Override
                public String format(int line) {
                    int[] numbers = lineNumbers;
                    if (numbers == null) {
                        return Integer.toString(line);
                    }
                    return line - 1 < numbers.length && numbers[line - 1] > 0 ? Integer.toString(numbers[line - 1]) : ""; //$NON-NLS-1$
                }

                @Override
                public int getMaxLength(int maxLineNumber) {
                    // A line number is never larger than the row number (the filler rows have none)
                    return Integer.toString(maxLineNumber).length();
                }
            });
            textArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    lastFocused = Side.this;
                }
            });
            header.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
            // Both sides ask for the same width, so the split starts in the middle whatever the lines' lengths
            pane.setPreferredSize(new Dimension(UIScale.scale(300), UIScale.scale(200)));
            pane.add(header, BorderLayout.NORTH);
            pane.add(scrollPane, BorderLayout.CENTER);
        }

        void clear() {
            lineNumbers = null;
            textArea.removeAllLineHighlights();
            textArea.getHighlighter().removeAllHighlights();
            textArea.setText(""); //$NON-NLS-1$
            textArea.discardAllEdits();
        }

        /**
         * The text as it is, all of it painted as a difference or none of it.
         */
        void showPlain(@NotNull String text, boolean difference) {
            clear();
            textArea.setText(text);
            textArea.discardAllEdits();
            if (difference) {
                for (int line = 0; line < textArea.getLineCount(); line++) {
                    addLineHighlight(line, lineColor);
                }
            }
        }

        /**
         * The aligned rows: this side's line or an empty filler row each, painted by the row's kind.
         */
        void showRows(@NotNull List<TextDiff.Row> rows, @NotNull String[] lines, boolean leftSide, @NotNull TextDiff computed) {
            clear();
            int[] numbers = new int[rows.size()];
            StringBuilder text = new StringBuilder();
            for (int row = 0; row < rows.size(); row++) {
                int line = leftSide ? rows.get(row).left() : rows.get(row).right();
                if (row > 0) {
                    text.append('\n');
                }
                if (line >= 0) {
                    text.append(lines[line]);
                    numbers[row] = line + 1;
                }
            }
            lineNumbers = numbers;
            textArea.setText(text.toString());
            textArea.discardAllEdits();
            // The gutter sizes itself to the widest number when the text changes; the formatter changed with it
            scrollPane.getGutter().revalidate();
            for (int row = 0; row < rows.size(); row++) {
                TextDiff.Row current = rows.get(row);
                boolean here = (leftSide ? current.left() : current.right()) >= 0;
                switch (current.kind()) {
                    case CHANGED -> {
                        addLineHighlight(row, lineColor);
                        int[][] marks = computed.getMarks(row);
                        if (marks != null) {
                            addWordHighlights(row, marks[leftSide ? 0 : 1]);
                        }
                    }
                    case LEFT_ONLY, RIGHT_ONLY -> addLineHighlight(row, here ? lineColor : FILLER_COLOR);
                    default -> {
                        // Equal rows and ignored blank lines are not painted
                    }
                }
            }
        }

        private void addLineHighlight(int line, @NotNull Color color) {
            try {
                textArea.addLineHighlight(line, color);
            } catch (BadLocationException e) {
                // Beyond the text
            }
        }

        private void addWordHighlights(int row, @NotNull int[] ranges) {
            try {
                int lineStart = textArea.getLineStartOffset(row);
                for (int i = 0; i + 1 < ranges.length; i += 2) {
                    textArea.getHighlighter().addHighlight(lineStart + ranges[i], lineStart + ranges[i + 1], wordPainter);
                }
            } catch (BadLocationException e) {
                // Beyond the text
            }
        }

        int getCaretRow() {
            try {
                return textArea.getLineOfOffset(textArea.getCaretPosition());
            } catch (BadLocationException e) {
                return 0;
            }
        }

        void setCaretRow(int row) {
            try {
                textArea.setCaretPosition(textArea.getLineStartOffset(Math.min(row, textArea.getLineCount() - 1)));
            } catch (BadLocationException e) {
                textArea.setCaretPosition(0);
            }
        }
    }

    /**
     * A side's header: icon and title, the connection's name in its colour pill (as the Object Explorer draws a server), the
     * rest of the title, and a muted note (selection, closed window).
     */
    static final class HeaderLabel extends JPanel {
        private final JLabel titleLabel = new JLabel();
        private final PillLabel connectionLabel = new PillLabel();
        private final JLabel suffixLabel = new JLabel();
        private final JLabel noteLabel = new JLabel();

        HeaderLabel() {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            add(titleLabel);
            add(connectionLabel);
            add(suffixLabel);
            add(noteLabel);
            noteLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
            noteLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), 0, 0));
            // As high as a header with a pill, so that both sides' texts start on the same row
            connectionLabel.setText(" "); //$NON-NLS-1$
        }

        void set(@Nullable Icon icon, @NotNull String prefix, @Nullable DBPDataSourceContainer connection, @Nullable String suffix,
                 @Nullable String note) {
            titleLabel.setIcon(icon);
            titleLabel.setText(prefix);
            connectionLabel.setConnection(connection);
            suffixLabel.setText(suffix == null ? "" : suffix); //$NON-NLS-1$
            noteLabel.setText(note == null ? "" : note); //$NON-NLS-1$
            noteLabel.setVisible(note != null);
            revalidate();
            repaint();
        }

        /**
         * The colour of the title and the note (a selected list row); the connection's name keeps its pill's colour.
         */
        void setTextForeground(@NotNull Color color) {
            titleLabel.setForeground(color);
            suffixLabel.setForeground(color);
            if (connectionLabel.pill == null) {
                connectionLabel.setForeground(color);
            }
        }

        /**
         * The plain text of the header (tests and accessibility).
         */
        @NotNull
        String getText() {
            return titleLabel.getText() + connectionLabel.getText().strip() + suffixLabel.getText()
                + (noteLabel.isVisible() ? " " + noteLabel.getText() : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A connection's name in its colour pill ({@link ConnectionPill}), or the name without a pill when it has no colour. The
     * pill reaches past the text line, so the label is that much higher and has the pill's padding on both sides.
     */
    static final class PillLabel extends JLabel {
        @Nullable
        private ConnectionPill pill;

        void setConnection(@Nullable DBPDataSourceContainer connection) {
            pill = connection == null ? null : ConnectionPill.of(connection);
            setText(connection == null ? " " : connection.getName()); //$NON-NLS-1$
            setForeground(null);
            if (pill != null) {
                setForeground(pill.getTextColor(UIManager.getColor("Label.foreground"))); //$NON-NLS-1$
            }
            Insets overhang = ConnectionPill.getOverhang(this, getFont());
            int vertical = Math.max(overhang.top, overhang.bottom);
            int padding = pill != null ? ConnectionPill.getPaddingX() : 0;
            setBorder(BorderFactory.createEmptyBorder(vertical, padding, vertical, padding));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int baseline = getBaseline(getWidth(), getHeight());
            if (pill != null && baseline >= 0) {
                Insets insets = getInsets();
                int textWidth = getFontMetrics(getFont()).stringWidth(getText());
                pill.paint(g, this, insets.left, textWidth, baseline);
            }
            super.paintComponent(g);
        }
    }

    /**
     * Marks where the rows with differences are (left half: the left side's differing rows, right half: the right side's). A
     * text taller than the strip is mapped onto its whole height; a shorter one keeps its rows' own heights, so the marks stand
     * beside their rows. A click shows that row.
     */
    private final class OverviewStrip extends JComponent {
        OverviewStrip() {
            setPreferredSize(new Dimension(UIScale.scale(STRIP_WIDTH), 0));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (rowCount > 0 && getHeight() > 0) {
                        showRow(Math.clamp((long) (e.getY() / getRowHeight()), 0, rowCount - 1));
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            TextDiff shown = diff;
            if (shown == null || rowCount == 0 || shown.isTooLarge()) {
                return;
            }
            int half = getWidth() / 2;
            int minimum = Math.max(2, UIScale.scale(2));
            // A missing side (the schema compare) was compared as an empty text: its rows are the present side's lines
            List<TextDiff.Row> rows = shown.getRows();
            paintRuns(g, rows, true, 0, half, minimum);
            paintRuns(g, rows, false, half, getWidth() - half, minimum);
        }

        /**
         * The strip's height of a row: the text's row height, or less when the text is taller than the strip.
         */
        private double getRowHeight() {
            double lineHeight = left.textArea.getLineHeight();
            return Math.min(lineHeight, (double) getHeight() / rowCount);
        }

        private void paintRuns(@NotNull Graphics g, @NotNull List<TextDiff.Row> rows, boolean leftSide, int x, int width, int minimum) {
            g.setColor(leftSide ? LEFT_WORD_COLOR : RIGHT_WORD_COLOR);
            double rowHeight = getRowHeight();
            int start = -1;
            for (int row = 0; row <= rows.size(); row++) {
                boolean marked = row < rows.size() && isMarked(rows.get(row), leftSide);
                if (marked && start < 0) {
                    start = row;
                } else if (!marked && start >= 0) {
                    int top = (int) (start * rowHeight);
                    int bottom = (int) (row * rowHeight);
                    g.fillRect(x, top, width, Math.max(minimum, bottom - top));
                    start = -1;
                }
            }
        }

        private static boolean isMarked(@NotNull TextDiff.Row row, boolean leftSide) {
            return row.kind() == TextDiff.Kind.CHANGED || row.kind() == (leftSide ? TextDiff.Kind.LEFT_ONLY : TextDiff.Kind.RIGHT_ONLY);
        }
    }
}
