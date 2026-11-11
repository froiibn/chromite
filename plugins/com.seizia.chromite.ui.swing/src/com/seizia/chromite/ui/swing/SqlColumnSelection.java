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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;
import org.fife.ui.rtextarea.RTextAreaEditorKit;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * Column (rectangular) selection of the query editor ({@link SqlEditorPanel.SqlTextArea}): Alt+drag (Option+drag on macOS),
 * Shift+Alt+arrows (Shift+Option+Command+arrows on macOS), typing on every line of the rectangle.
 *
 * 설계 (앱 변경 #49, docs/chromite/swing-sql-column-selection.md):
 * <ul>
 *     <li>RSyntaxTextArea에는 사각형 선택도 다중 캐럿도 없다. 그래서 Swing 선택은 비워 두고(캐럿은 사각형의 캐럿 쪽 모서리) 사각형은 이 클래스가
 *     줄·열 번호로 따로 기억하고 그린다. Swing 선택을 비워 두기 때문에 선택을 읽는 기존 코드(실행, 찾기, 자동완성)는 사각형을 보지 않고
 *     캐럿 하나로 동작하며, 열 선택이 꺼져 있으면 이 클래스는 Alt+누르기 말고는 아무 입력도 가로채지 않는다(보통 선택은 예전 그대로).</li>
 *     <li>열은 글자 칸이다. 탭은 다음 탭 위치까지, 한글·한자 같은 전각 글자({@link #getCellWidth})는 2칸, 결합 문자는 0칸이다. 칸 경계가
 *     전각 글자나 탭 가운데에 떨어지면 사각형의 왼쪽 끝은 그 글자 앞, 오른쪽 끝은 그 글자 뒤로 넓힌다(걸친 글자는 선택된다). 폭이 0인 세로 캐럿은
 *     가까운 경계에 놓는다. 줄 끝보다 오른쪽은 빈 칸(가상 공간)이다: 그리기·마우스는 공백 폭으로 재고, 입력하면 그 줄을 공백으로 채운 뒤 넣는다.</li>
 *     <li>실제 글자의 화면 위치는 편집기 보기({@code modelToView2D}, {@code viewToModel2D})에서 읽는다. 그래서 대체 글꼴의 한글 폭이 정확히 2칸이 아니어도
 *     사각형이 글자를 따라 그려지고 마우스가 가리킨 글자가 선택된다.</li>
 *     <li>접힌 줄(코드 접기)은 사각형에 들지 않는다: 보이지 않는 줄을 몰래 고치지 않기 위해서다. 자동 줄 바꿈이 켜져 있으면 한 줄이 여러 화면 줄로
 *     나뉘어 열이 정해지지 않으므로 열 선택을 쓰지 않는다(Alt+끌기는 보통 끌기 선택).</li>
 *     <li>고치기는 문서를 아래 줄부터 직접 바꾸고({@link #replaceRectangle}) 전체를 {@code beginAtomicEdit}로 묶어 Ctrl+Z 한 번에 되돌린다.
 *     고치는 동안 클라이언트 속성 {@link #EDITING_PROPERTY}를 켜 자동완성이 줄마다의 한 글자 넣기를 입력으로 보지 않게 한다({@link #isEditing}).</li>
 *     <li>입력 경로: 글자는 키 리스너의 {@code keyTyped}에서 받는다(따옴표·괄호 짝 넣기 동작을 거치지 않게). IME(한글 조합)의 확정 글자와 붙여넣기는
 *     {@code replaceSelection}으로 들어오므로 편집 영역이 그것을 {@link #replaceSelection}에 먼저 넘긴다. 조합 중인 글자는 캐럿 줄에만 보이고,
 *     확정되면 모든 줄에 들어간다. Backspace·Delete는 키 리스너가 키 바인딩보다 먼저 받는다.</li>
 *     <li>끝내기: 이 클래스가 하지 않은 캐럿 이동·문서 변경(클릭, 방향키, 되돌리기, 자동완성 넣기 등)이 있으면 열 선택을 끝낸다. 줄 단위 명령
 *     (주석, 들여쓰기, 서식, 줄 옮기기)은 사각형의 줄을 보통 선택으로 바꾼 뒤 실행하고({@link #runCommand}, {@link #LINE_ACTIONS}),
 *     대문자·소문자는 사각형 안의 글자만 바꾼다.</li>
 *     <li>Windows에서 Alt를 눌렀다 떼면 메뉴 막대가 선택된다(FlatLaf {@code MnemonicHandler}, 마우스 이벤트는 이를 취소하지 않음). 그래서 Alt+끌기 뒤의
 *     Alt 떼기는 소비한다.</li>
 *     <li>수정자 키는 {@link UiKeys}가 정한다({@link UiKeys#isColumnSelectDown}, {@link UiKeys#columnSelect}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #49)
 */
final class SqlColumnSelection {

    /**
     * Client property of the text area while a column edit changes the document (one insertion per line).
     */
    private static final String EDITING_PROPERTY = "chromite.columnEditing"; //$NON-NLS-1$

    /**
     * Rightmost column the keys can reach (virtual space beyond the line ends).
     */
    private static final int MAX_COLUMN = 10000;

    /**
     * Actions that work on the lines of the selection: they get the rectangle's lines as a normal selection.
     */
    private static final Set<String> LINE_ACTIONS = Set.of(
        RSyntaxTextAreaEditorKit.rstaToggleCommentAction,
        RSyntaxTextAreaEditorKit.rstaDecreaseIndentAction,
        RTextAreaEditorKit.rtaLineUpAction,
        RTextAreaEditorKit.rtaLineDownAction,
        RTextAreaEditorKit.rtaDeleteLineAction);

    /**
     * Key binding names of Copy and Cut (Ctrl+C is RSyntaxTextArea's styled copy, Ctrl+Insert / Shift+Delete the Swing ones).
     */
    private static final Set<Object> COPY_ACTIONS = Set.of(
        RSyntaxTextAreaEditorKit.rstaCopyAsStyledTextAction, DefaultEditorKit.copyAction, "copy"); //$NON-NLS-1$
    private static final Set<Object> CUT_ACTIONS = Set.of(
        RSyntaxTextAreaEditorKit.rstaCutAsStyledTextAction, DefaultEditorKit.cutAction, "cut"); //$NON-NLS-1$

    private enum Snap {
        // The left edge of a selection: a wide character or tab under the edge is selected
        LEFT,
        // The right edge of a selection
        RIGHT,
        // A zero-width caret: the closer character boundary
        NEAREST
    }

    /**
     * Rectangle part of one line: document offsets and the virtual spaces beyond the line end before each.
     */
    private record Span(int line, int lineStart, int start, int end, int startVirtual, int endVirtual) {
    }

    private final RSyntaxTextArea textArea;
    private final KeyStroke[] keys;
    private final Highlighter.HighlightPainter painter = this::paint;

    // EDT only: the rectangle by line and column (cells); the caret corner is (caretLine, caretColumn)
    private boolean active;
    private int anchorLine;
    private int anchorColumn;
    private int caretLine;
    private int caretColumn;
    // Alt+drag in progress; the press was ours (its click is ours too)
    private boolean dragging;
    private boolean ownPress;
    // A column drag happened while Alt was down: the Alt release must not select the menu bar
    private boolean altUsed;
    // Caret and document changes made here, and inside an input method event (composed text): they keep the selection
    private int adjusting;
    private int inputMethod;
    @Nullable
    private Object highlightTag;

    SqlColumnSelection(@NotNull RSyntaxTextArea textArea) {
        this.textArea = textArea;
        keys = new KeyStroke[]{
            UiKeys.columnSelect(KeyEvent.VK_UP), UiKeys.columnSelect(KeyEvent.VK_DOWN),
            UiKeys.columnSelect(KeyEvent.VK_LEFT), UiKeys.columnSelect(KeyEvent.VK_RIGHT)};
        bindKey(keys[0], "chromite.columnSelectUp", -1, 0); //$NON-NLS-1$
        bindKey(keys[1], "chromite.columnSelectDown", 1, 0); //$NON-NLS-1$
        bindKey(keys[2], "chromite.columnSelectLeft", 0, -1); //$NON-NLS-1$
        bindKey(keys[3], "chromite.columnSelectRight", 0, 1); //$NON-NLS-1$
        // Key listeners run before the key bindings: Backspace, Delete and typing reach the rectangle first
        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPressed(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                handleKeyTyped(e);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ALT && altUsed) {
                    altUsed = false;
                    e.consume();
                }
            }
        });
        CaretListener caretListener = e -> foreignChange();
        textArea.addCaretListener(caretListener);
        DocumentListener documentListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                foreignChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                foreignChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attributes only
            }
        };
        textArea.getDocument().addDocumentListener(documentListener);
        textArea.addPropertyChangeListener("document", e -> { //$NON-NLS-1$
            if (e.getOldValue() instanceof Document old) {
                old.removeDocumentListener(documentListener);
            }
            if (e.getNewValue() instanceof Document document) {
                document.addDocumentListener(documentListener);
            }
            exit();
        });
        textArea.addPropertyChangeListener("lineWrap", e -> exit()); //$NON-NLS-1$
        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (active) {
                    // After the caret's own focus handling, which shows it again
                    SwingUtilities.invokeLater(() -> {
                        if (active) {
                            textArea.getCaret().setVisible(false);
                        }
                    });
                }
                textArea.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                altUsed = false;
                textArea.repaint();
            }
        });
    }

    /**
     * Whether a column selection is shown.
     */
    boolean isActive() {
        return active;
    }

    /**
     * Whether the rectangle has a width (Copy and Cut take its text); a zero-width caret selects nothing.
     */
    boolean hasSelectedText() {
        return active && getLeftColumn() != getRightColumn();
    }

    /**
     * Whether a column edit is changing the document of the text area now (one change per line, not typing).
     */
    static boolean isEditing(@NotNull JTextComponent textArea) {
        return Boolean.TRUE.equals(textArea.getClientProperty(EDITING_PROPERTY));
    }

    // ---- Input from the text area

    /**
     * Mouse events of the text area before its own handling (caret, drag selection).
     *
     * @return true when the event was handled here and the text area must not handle it
     */
    boolean processMouseEvent(@NotNull MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> {
                ownPress = false;
                if (SwingUtilities.isLeftMouseButton(e) && UiKeys.isColumnSelectDown(e) && canSelect()) {
                    ownPress = true;
                    dragging = true;
                    altUsed = true;
                    textArea.requestFocusInWindow();
                    int[] cell = getCellAt(e.getPoint());
                    // Shift+Alt+click extends the shown rectangle
                    if (!active || !e.isShiftDown()) {
                        anchorLine = cell[0];
                        anchorColumn = cell[1];
                    }
                    activate();
                    moveCaretCorner(cell[0], cell[1]);
                    return true;
                }
                if (active && SwingUtilities.isLeftMouseButton(e)) {
                    exit();
                }
                return false;
            }
            case MouseEvent.MOUSE_DRAGGED -> {
                if (!dragging) {
                    return false;
                }
                int[] cell = getCellAt(e.getPoint());
                moveCaretCorner(cell[0], cell[1]);
                // Dragging past the visible part scrolls
                textArea.scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
                return true;
            }
            case MouseEvent.MOUSE_RELEASED -> {
                if (!dragging) {
                    return ownPress;
                }
                dragging = false;
                if (anchorLine == caretLine && anchorColumn == caretColumn) {
                    // Alt+click without a drag: a normal caret at the click
                    exit();
                }
                return true;
            }
            case MouseEvent.MOUSE_CLICKED -> {
                // Not the double click word selection of the caret
                return ownPress;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Wraps the text area's input method event handling: the composed text it inserts at the caret keeps the column selection;
     * the committed text comes back through {@link #replaceSelection}.
     */
    void processInputMethodEvent(@NotNull Runnable handling) {
        inputMethod++;
        try {
            handling.run();
        } finally {
            inputMethod--;
        }
    }

    /**
     * The text area's {@code replaceSelection} (input method text, paste, Tab, inserted text of commands).
     *
     * @return true when the text went into the rectangle and the text area must not insert it
     */
    boolean replaceSelection(@Nullable String content) {
        if (!active || adjusting > 0) {
            return false;
        }
        if (!textArea.isEditable()) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return true;
        }
        List<String> parts = splitLines(content == null ? "" : content); //$NON-NLS-1$
        List<Integer> lines = getLines();
        if (parts.size() == 1) {
            String text = parts.get(0);
            replaceRectangle(i -> text);
            return true;
        }
        if (parts.size() == lines.size()) {
            // Pasted lines go to the lines of the rectangle one by one
            replaceRectangle(parts::get);
            return true;
        }
        // Other multi-line text: replaces the rectangle's text and goes in once at the caret corner
        replaceRectangle(i -> ""); //$NON-NLS-1$
        exit();
        return false;
    }

    /**
     * Copy and Cut (keys, Edit menu, context menu): the rectangle's text line by line.
     *
     * @return true when the column selection took it
     */
    boolean copy(boolean cut) {
        if (!active) {
            return false;
        }
        List<Span> spans = getSpans();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(getText(spans.get(i).start(), spans.get(i).end()));
        }
        if (getLeftColumn() == getRightColumn()) {
            // A zero-width caret selects nothing: the clipboard stays as it is
            return true;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
        } catch (IllegalStateException e) {
            // The clipboard is in use by another application
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return true;
        }
        if (cut) {
            if (textArea.isEditable()) {
                replaceRectangle(i -> ""); //$NON-NLS-1$
            } else {
                UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            }
        }
        return true;
    }

    /**
     * An editing command of the query editor (Edit &gt; Advanced, its keys, the SQL Editor tool bar) while the rectangle is shown.
     *
     * @return true when the command was done here
     */
    boolean runCommand(@NotNull SqlEditorPanel.SqlTextArea.Command command) {
        if (!active || !command.isEdit()) {
            return false;
        }
        switch (command) {
            case UPPER_CASE -> {
                transform(String::toUpperCase);
                return true;
            }
            case LOWER_CASE -> {
                transform(String::toLowerCase);
                return true;
            }
            case INSERT_TEMPLATE -> {
                // The template goes in at the caret corner
                exit();
                return false;
            }
            default -> {
                // Comment, indent, format, trim: the lines of the rectangle
                selectLines();
                return false;
            }
        }
    }

    // ---- Keys

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, int lineDelta, int columnDelta) {
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
        textArea.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                extend(lineDelta, columnDelta);
            }
        });
    }

    private void handleKeyPressed(@NotNull KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ALT) {
            altUsed = false;
        }
        if (!active || e.isConsumed() || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
            || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH) {
            return;
        }
        KeyStroke keyStroke = KeyStroke.getKeyStrokeForEvent(e);
        for (KeyStroke key : keys) {
            if (key.equals(keyStroke)) {
                return;
            }
        }
        boolean plain = (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0;
        switch (code) {
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                if (plain) {
                    e.consume();
                    deleteCharacters(code == KeyEvent.VK_BACK_SPACE);
                }
            }
            case KeyEvent.VK_ENTER -> {
                if (plain && textArea.isEditable() && getLeftColumn() != getRightColumn()) {
                    // The rectangle's text goes; the line break goes in at the caret corner (the key binding)
                    replaceRectangle(i -> ""); //$NON-NLS-1$
                }
                exit();
            }
            case KeyEvent.VK_ESCAPE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
                 KeyEvent.VK_KP_LEFT, KeyEvent.VK_KP_RIGHT, KeyEvent.VK_KP_UP, KeyEvent.VK_KP_DOWN,
                 KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN -> {
                // The caret moves on from the caret corner
                exit();
            }
            default -> {
                Object action = textArea.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke);
                if (COPY_ACTIONS.contains(action) || CUT_ACTIONS.contains(action)) {
                    // The text area's own Copy and Cut copy its (empty) selection
                    e.consume();
                    copy(CUT_ACTIONS.contains(action));
                } else if (action instanceof String name && LINE_ACTIONS.contains(name)) {
                    selectLines();
                }
            }
        }
    }

    private void handleKeyTyped(@NotNull KeyEvent e) {
        if (!active || e.isConsumed()) {
            return;
        }
        char c = e.getKeyChar();
        int modifiers = e.getModifiersEx();
        // The text area's typing filter (DefaultKeyTypedAction): no control characters, Ctrl only with Alt (AltGr)
        boolean alt = (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
        boolean ctrl = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
        boolean meta = (modifiers & InputEvent.META_DOWN_MASK) != 0;
        if (c == KeyEvent.CHAR_UNDEFINED || c < 0x20 || c == 0x7F || alt != ctrl || meta) {
            return;
        }
        e.consume();
        if (!textArea.isEditable()) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        String text = String.valueOf(c);
        replaceRectangle(i -> text);
    }

    /**
     * Shift+Alt+arrow: starts a column selection at the caret, or moves its caret corner.
     */
    private void extend(int lineDelta, int columnDelta) {
        if (!canSelect()) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        if (!active) {
            int dot = textArea.getCaretPosition();
            int line = getLineOfOffset(dot);
            anchorLine = line;
            anchorColumn = getColumn(getLineText(line), dot - getLineStart(line));
            caretLine = anchorLine;
            caretColumn = anchorColumn;
            activate();
        }
        int line = caretLine;
        if (lineDelta != 0) {
            line = getNextVisibleLine(caretLine, lineDelta);
        }
        int column = Math.max(0, Math.min(MAX_COLUMN, caretColumn + columnDelta));
        moveCaretCorner(line, column);
        scrollToCaretCorner();
    }

    // ---- State

    private boolean canSelect() {
        return textArea.isEnabled() && !textArea.getLineWrap();
    }

    private void activate() {
        if (!active) {
            active = true;
            // The rectangle draws one caret per line; the text area's own caret would blink at the line end in virtual space
            textArea.getCaret().setVisible(false);
            Highlighter highlighter = textArea.getHighlighter();
            boolean installed = false;
            if (highlightTag != null) {
                for (Highlighter.Highlight highlight : highlighter.getHighlights()) {
                    if (highlight == highlightTag) {
                        installed = true;
                        break;
                    }
                }
            }
            if (!installed) {
                // Painted with the text area's other highlights, under the text; the offsets do not matter
                try {
                    highlightTag = highlighter.addHighlight(0, 0, painter);
                } catch (BadLocationException e) {
                    highlightTag = null;
                }
            }
        }
        textArea.repaint();
    }

    /**
     * Ends the column selection; the text area's caret stays at the caret corner.
     */
    private void exit() {
        dragging = false;
        if (!active) {
            return;
        }
        active = false;
        if (textArea.isFocusOwner()) {
            textArea.getCaret().setVisible(true);
        }
        textArea.repaint();
    }

    private void foreignChange() {
        if (active && adjusting == 0 && inputMethod == 0) {
            exit();
        }
    }

    private void moveCaretCorner(int line, int column) {
        caretLine = line;
        caretColumn = column;
        syncCaret();
        textArea.repaint();
    }

    /**
     * Puts the text area's caret at the caret corner (the nearest offset; the line end in virtual space): the status line, the
     * current line highlight and the input method use it.
     */
    private void syncCaret() {
        String text = getLineText(caretLine);
        int offset = getLineStart(caretLine) + getIndex(text, caretColumn, Snap.NEAREST)[0];
        adjusting++;
        try {
            textArea.setCaretPosition(offset);
        } finally {
            adjusting--;
        }
    }

    private void scrollToCaretCorner() {
        Rectangle2D caret = getCaretBounds(getSpan(caretLine));
        if (caret != null) {
            Rectangle bounds = caret.getBounds();
            bounds.width = (int) Math.ceil(getSpaceWidth());
            textArea.scrollRectToVisible(bounds);
        }
    }

    // ---- Edits

    /**
     * Replaces the rectangle's text on each line with the text for that line (index in {@link #getLines()}) in one undoable
     * edit; the rectangle becomes a zero-width caret after the text on the caret line.
     */
    private void replaceRectangle(@NotNull IntFunction<String> textOfLine) {
        List<Span> spans = getSpans();
        Document document = textArea.getDocument();
        int caretIndex = -1;
        int caretVirtual = 0;
        beginEdit();
        try {
            // Bottom up: the offsets of the lines above stay valid
            for (int i = spans.size() - 1; i >= 0; i--) {
                Span span = spans.get(i);
                String text = textOfLine.apply(i);
                if (span.end() > span.start()) {
                    document.remove(span.start(), span.end() - span.start());
                }
                String insert = text.isEmpty() || span.startVirtual() == 0 ? text : " ".repeat(span.startVirtual()) + text; //$NON-NLS-1$
                if (!insert.isEmpty()) {
                    document.insertString(span.start(), insert, null);
                }
                if (span.line() == caretLine) {
                    caretIndex = span.start() - span.lineStart() + insert.length();
                    // Nothing inserted in virtual space: the caret stays there, not at the line end
                    caretVirtual = insert.isEmpty() ? span.startVirtual() : 0;
                }
            }
        } catch (BadLocationException e) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        } finally {
            endEdit();
        }
        int column = caretIndex >= 0 ? getColumn(getLineText(caretLine), caretIndex) + caretVirtual : getLeftColumn();
        anchorColumn = column;
        caretColumn = column;
        moveCaretCorner(caretLine, column);
    }

    /**
     * Backspace and Delete: the rectangle's text, or with a zero-width caret the character before or after it on every line.
     * Nothing is deleted in virtual space, and Delete at a line end does not join the lines.
     */
    private void deleteCharacters(boolean backward) {
        if (!textArea.isEditable()) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        if (getLeftColumn() != getRightColumn()) {
            replaceRectangle(i -> ""); //$NON-NLS-1$
            return;
        }
        int column = caretColumn;
        if (backward && column == 0) {
            return;
        }
        List<Span> spans = getSpans();
        Document document = textArea.getDocument();
        int caretIndex = -1;
        beginEdit();
        try {
            for (int i = spans.size() - 1; i >= 0; i--) {
                Span span = spans.get(i);
                if (span.startVirtual() > 0) {
                    continue;
                }
                String text = getLineText(span.line());
                int index = span.start() - span.lineStart();
                if (backward && index > 0) {
                    int from = text.offsetByCodePoints(index, -1);
                    document.remove(span.lineStart() + from, index - from);
                    if (span.line() == caretLine) {
                        caretIndex = from;
                    }
                } else if (!backward && index < text.length()) {
                    int to = text.offsetByCodePoints(index, 1);
                    document.remove(span.start(), to - index);
                }
            }
        } catch (BadLocationException e) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        } finally {
            endEdit();
        }
        if (backward) {
            // In virtual space the caret moves one cell left
            column = caretIndex >= 0 ? getColumn(getLineText(caretLine), caretIndex) : column - 1;
        }
        anchorColumn = column;
        caretColumn = column;
        moveCaretCorner(caretLine, column);
    }

    /**
     * Upper and lower case of the rectangle's text; the rectangle stays.
     */
    private void transform(@NotNull UnaryOperator<String> operator) {
        List<Span> spans = getSpans();
        List<String> texts = new ArrayList<>();
        for (Span span : spans) {
            texts.add(operator.apply(getText(span.start(), span.end())));
        }
        int anchor = anchorColumn;
        int caret = caretColumn;
        Document document = textArea.getDocument();
        beginEdit();
        try {
            for (int i = spans.size() - 1; i >= 0; i--) {
                Span span = spans.get(i);
                if (span.end() > span.start()) {
                    document.remove(span.start(), span.end() - span.start());
                    document.insertString(span.start(), texts.get(i), null);
                }
            }
        } catch (BadLocationException e) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        } finally {
            endEdit();
        }
        anchorColumn = anchor;
        moveCaretCorner(caretLine, caret);
    }

    /**
     * The lines of the rectangle as a normal selection, for the commands that work on lines.
     */
    private void selectLines() {
        int top = Math.min(anchorLine, caretLine);
        int bottom = Math.max(anchorLine, caretLine);
        exit();
        textArea.select(getLineStart(top), getLineStart(bottom) + getLineText(bottom).length());
    }

    private void beginEdit() {
        adjusting++;
        textArea.putClientProperty(EDITING_PROPERTY, Boolean.TRUE);
        textArea.beginAtomicEdit();
    }

    private void endEdit() {
        textArea.endAtomicEdit();
        textArea.putClientProperty(EDITING_PROPERTY, null);
        adjusting--;
    }

    // ---- Painting

    private void paint(@NotNull Graphics g, int p0, int p1, @NotNull Shape bounds, @NotNull JTextComponent c) {
        if (!active) {
            return;
        }
        Rectangle clip = g.getClipBounds();
        if (clip == null) {
            clip = textArea.getVisibleRect();
        }
        int top = Math.max(Math.min(anchorLine, caretLine), getLineOfOffset(textArea.viewToModel2D(new Point(0, clip.y))));
        int bottom = Math.min(Math.max(anchorLine, caretLine), getLineOfOffset(textArea.viewToModel2D(new Point(0, clip.y + clip.height))));
        boolean focused = textArea.isFocusOwner();
        for (int line = top; line <= bottom; line++) {
            if (isHidden(line)) {
                continue;
            }
            Span span = getSpan(line);
            Rectangle2D start = getBounds(span.start(), span.startVirtual());
            Rectangle2D end = getBounds(span.end(), span.endVirtual());
            if (start == null || end == null) {
                continue;
            }
            int height = textArea.getLineHeight();
            if (end.getX() > start.getX()) {
                g.setColor(textArea.getSelectionColor());
                g.fillRect((int) Math.round(start.getX()), (int) start.getY(), (int) Math.round(end.getX() - start.getX()), height);
            }
            if (focused) {
                Rectangle2D caret = caretColumn == getLeftColumn() ? start : end;
                g.setColor(textArea.getCaretColor());
                g.fillRect((int) Math.round(caret.getX()), (int) caret.getY(), 1, height);
            }
        }
    }

    // ---- Geometry

    private int getLeftColumn() {
        return Math.min(anchorColumn, caretColumn);
    }

    private int getRightColumn() {
        return Math.max(anchorColumn, caretColumn);
    }

    /**
     * The lines of the rectangle that are shown (not inside a collapsed fold), top down.
     */
    @NotNull
    private List<Integer> getLines() {
        List<Integer> lines = new ArrayList<>();
        int bottom = Math.max(anchorLine, caretLine);
        for (int line = Math.min(anchorLine, caretLine); line <= bottom; line++) {
            if (!isHidden(line)) {
                lines.add(line);
            }
        }
        return lines;
    }

    @NotNull
    private List<Span> getSpans() {
        List<Span> spans = new ArrayList<>();
        for (int line : getLines()) {
            spans.add(getSpan(line));
        }
        return spans;
    }

    @NotNull
    private Span getSpan(int line) {
        String text = getLineText(line);
        int lineStart = getLineStart(line);
        int left = getLeftColumn();
        int right = getRightColumn();
        int[] start = getIndex(text, left, left == right ? Snap.NEAREST : Snap.LEFT);
        int[] end = left == right ? start : getIndex(text, right, Snap.RIGHT);
        return new Span(line, lineStart, lineStart + start[0], lineStart + end[0], start[1], end[1]);
    }

    /**
     * Bounds of an offset moved right by virtual spaces, in the text area.
     */
    @Nullable
    private Rectangle2D getBounds(int offset, int virtual) {
        try {
            Rectangle2D bounds = textArea.modelToView2D(offset);
            if (bounds == null) {
                return null;
            }
            return new Rectangle2D.Double(bounds.getX() + virtual * getSpaceWidth(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        } catch (BadLocationException e) {
            return null;
        }
    }

    @Nullable
    private Rectangle2D getCaretBounds(@NotNull Span span) {
        return caretColumn == getLeftColumn() ? getBounds(span.start(), span.startVirtual()) : getBounds(span.end(), span.endVirtual());
    }

    /**
     * Line and column of the cell under a point of the text area (virtual space beyond the line end).
     */
    @NotNull
    private int[] getCellAt(@NotNull Point point) {
        int offset = textArea.viewToModel2D(point);
        int line = getLineOfOffset(Math.max(0, offset));
        String text = getLineText(line);
        int lineStart = getLineStart(line);
        Rectangle2D end = getBounds(lineStart + text.length(), 0);
        int column;
        if (end != null && point.x > end.getX()) {
            column = getColumn(text, text.length()) + (int) Math.round((point.x - end.getX()) / getSpaceWidth());
        } else {
            column = getColumn(text, Math.max(0, Math.min(text.length(), offset - lineStart)));
        }
        return new int[]{line, Math.min(MAX_COLUMN, column)};
    }

    /**
     * Advance of a space as the text area lays out its text: with the render context of its font metrics it is fractional like
     * the text positions (7.33 at 150%, measured); {@code charWidth} (whole pixels) would drift over long virtual space.
     */
    private double getSpaceWidth() {
        Font font = textArea.getFont();
        double width = font.getStringBounds(" ", textArea.getFontMetrics(font).getFontRenderContext()).getWidth(); //$NON-NLS-1$
        return width > 0 ? width : 1;
    }

    /**
     * Column (cells) before {@code index} of the line text.
     */
    private int getColumn(@NotNull String text, int index) {
        int tabSize = Math.max(1, textArea.getTabSize());
        int column = 0;
        for (int i = 0; i < index && i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            column += codePoint == '\t' ? tabSize - column % tabSize : getCellWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return column;
    }

    /**
     * Index in the line text at a column, and the virtual spaces still needed when the line is shorter.
     */
    @NotNull
    private int[] getIndex(@NotNull String text, int column, @NotNull Snap snap) {
        int tabSize = Math.max(1, textArea.getTabSize());
        int cell = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int width = codePoint == '\t' ? tabSize - cell % tabSize : getCellWidth(codePoint);
            if (cell == column && width > 0) {
                // A boundary; combining marks stay with their base character
                return new int[]{i, 0};
            }
            if (cell + width > column) {
                int next = i + Character.charCount(codePoint);
                boolean after = switch (snap) {
                    case LEFT -> false;
                    case RIGHT -> true;
                    case NEAREST -> (column - cell) * 2 > width;
                };
                return new int[]{after ? next : i, 0};
            }
            cell += width;
            i += Character.charCount(codePoint);
        }
        return new int[]{text.length(), column - cell};
    }

    /**
     * Cells of a character in the monospaced editor: 2 for East Asian wide and full-width characters (Hangul, CJK, full-width
     * forms, emoji), 0 for combining marks and format characters, else 1.
     */
    static int getCellWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT
            || (codePoint >= 0x1160 && codePoint <= 0x11FF)) {
            // Hangul medial vowels and final consonants join the preceding initial consonant
            return 0;
        }
        boolean wide = (codePoint >= 0x1100 && codePoint <= 0x115F)
            || (codePoint >= 0x2E80 && codePoint <= 0x303E)
            || (codePoint >= 0x3041 && codePoint <= 0x33FF)
            || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
            || (codePoint >= 0xA000 && codePoint <= 0xA4CF)
            || (codePoint >= 0xA960 && codePoint <= 0xA97F)
            || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
            || (codePoint >= 0xFE30 && codePoint <= 0xFE4F)
            || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
            || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
            || (codePoint >= 0x1F300 && codePoint <= 0x1F64F)
            || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)
            || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
        return wide ? 2 : 1;
    }

    private boolean isHidden(int line) {
        FoldManager foldManager = textArea.getFoldManager();
        return foldManager != null && foldManager.isLineHidden(line);
    }

    private int getNextVisibleLine(int line, int delta) {
        int next = line;
        do {
            next += delta;
        } while (next >= 0 && next < textArea.getLineCount() && isHidden(next));
        return next < 0 || next >= textArea.getLineCount() ? line : next;
    }

    private int getLineOfOffset(int offset) {
        try {
            return textArea.getLineOfOffset(Math.min(offset, textArea.getDocument().getLength()));
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private int getLineStart(int line) {
        try {
            return textArea.getLineStartOffset(line);
        } catch (BadLocationException e) {
            return 0;
        }
    }

    /**
     * Text of a line without its line break.
     */
    @NotNull
    private String getLineText(int line) {
        try {
            int start = textArea.getLineStartOffset(line);
            int end = textArea.getLineEndOffset(line);
            String text = textArea.getText(start, end - start);
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text; //$NON-NLS-1$
        } catch (BadLocationException e) {
            return ""; //$NON-NLS-1$
        }
    }

    @NotNull
    private String getText(int start, int end) {
        try {
            return textArea.getText(start, end - start);
        } catch (BadLocationException e) {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Lines of a text without one trailing line break ("a\nb\n" is two lines).
     */
    @NotNull
    static List<String> splitLines(@NotNull String text) {
        String body = text.endsWith("\r\n") ? text.substring(0, text.length() - 2) //$NON-NLS-1$
            : text.endsWith("\n") || text.endsWith("\r") ? text.substring(0, text.length() - 1) : text; //$NON-NLS-1$ //$NON-NLS-2$
        return List.of(body.split("\r\n|\r|\n", -1)); //$NON-NLS-1$
    }
}
