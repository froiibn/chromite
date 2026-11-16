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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Outline 도구 창: 활성 쿼리 창 스크립트의 문장 목록. 고르면 편집기에서 그 문장으로 간다.
 *
 * 설계:
 * <ul>
 *     <li>SWT의 Outline 보기({@code SQLEditorOutlinePage})처럼 스크립트의 구조를 보이고 탐색한다. 문장 경계는 코드 접기와 같은
 *     {@link SqlFoldParser#findStatements}다(연결이 없는 창에서도 되고, 접기 범위와 목록이 같다). 문장마다 줄 번호와 주석을 뺀 첫 줄을 보인다.</li>
 *     <li>문서가 바뀌면 잠시({@value #REFRESH_DELAY}ms) 기다렸다가 다시 만들고, 커서가 움직이면 커서가 있는 문장을 고른다.
 *     목록에서 고르면(누르기, Enter) 편집기에서 그 문장을 선택하고 화면에 보이게 하며, 두 번 누르거나 Enter면 편집기로 초점을 옮긴다.</li>
 *     <li>활성 문서가 쿼리 창이 아니면(DDL 탭, 다른 도구 문서, 문서 없음) 빈 목록과 안내 글자를 보인다. DDL 탭의 읽기 전용 편집기도 보인다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-ergonomics.md)
 */
final class SqlOutlinePanel extends JPanel {

    private static final int REFRESH_DELAY = 400;
    private static final int MAX_SUMMARY_LENGTH = 120;

    private record Item(@NotNull SqlFoldParser.Statement statement, int line, @NotNull String summary) {
    }

    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JLabel placeholder = new JLabel(EditorMessages.editor_outline_none, SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final Timer refreshTimer;
    @Nullable
    private SqlEditorPanel.SqlTextArea textArea;
    // Selecting the caret's statement must not move the caret again
    private boolean followingCaret;
    private final DocumentListener documentListener = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            refreshTimer.restart();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            refreshTimer.restart();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
        }
    };
    private final CaretListener caretListener = e -> selectCaretStatement();

    SqlOutlinePanel() {
        setLayout(cards);
        refreshTimer = new Timer(REFRESH_DELAY, e -> refresh());
        refreshTimer.setRepeats(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !followingCaret) {
                goToSelected(false);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    goToSelected(e.getClickCount() == 2);
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    goToSelected(true);
                    e.consume();
                }
            }
        });
        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        placeholder.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        add(scrollPane, "list"); //$NON-NLS-1$
        add(placeholder, "none"); //$NON-NLS-1$
        cards.show(this, "none"); //$NON-NLS-1$
    }

    /**
     * The active document changed: the outline follows the text area of the active query window (or DDL tab), or none.
     */
    void setTextArea(@Nullable SqlEditorPanel.SqlTextArea newTextArea) {
        if (newTextArea == textArea) {
            return;
        }
        if (textArea != null) {
            textArea.getDocument().removeDocumentListener(documentListener);
            textArea.removeCaretListener(caretListener);
        }
        textArea = newTextArea;
        if (textArea != null) {
            textArea.getDocument().addDocumentListener(documentListener);
            textArea.addCaretListener(caretListener);
        }
        refreshTimer.stop();
        refresh();
    }

    private void refresh() {
        SqlEditorPanel.SqlTextArea area = textArea;
        model.clear();
        if (area == null || !isShowing()) {
            // Built again when shown (addNotify) or when the document changes while shown
            cards.show(this, "none"); //$NON-NLS-1$
            return;
        }
        List<SqlFoldParser.Statement> statements = SqlFoldParser.findStatements(area);
        String text = area.getText();
        for (SqlFoldParser.Statement statement : statements) {
            int line;
            try {
                line = area.getLineOfOffset(statement.start()) + 1;
            } catch (BadLocationException e) {
                continue;
            }
            model.addElement(new Item(statement, line, summarize(text.substring(statement.start(), Math.min(statement.end(), text.length())))));
        }
        cards.show(this, model.isEmpty() ? "none" : "list"); //$NON-NLS-1$ //$NON-NLS-2$
        selectCaretStatement();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // The outline is not built while its tool window is hidden
        SwingUtilities.invokeLater(this::refresh);
    }

    /**
     * The first line of the statement without comments, with runs of white space as one space.
     */
    @NotNull
    private static String summarize(@NotNull String statement) {
        for (String line : statement.split("\\R")) { //$NON-NLS-1$
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("--") && !stripped.startsWith("/*") && !stripped.startsWith("*")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String summary = stripped.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
                return summary.length() > MAX_SUMMARY_LENGTH ? summary.substring(0, MAX_SUMMARY_LENGTH) + "…" : summary; //$NON-NLS-1$
            }
        }
        return statement.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void selectCaretStatement() {
        SqlEditorPanel.SqlTextArea area = textArea;
        if (area == null || model.isEmpty()) {
            return;
        }
        int caret = area.getCaretPosition();
        int index = -1;
        for (int i = 0; i < model.size(); i++) {
            SqlFoldParser.Statement statement = model.get(i).statement();
            if (caret >= statement.start() && caret <= statement.end()) {
                index = i;
                break;
            }
        }
        followingCaret = true;
        try {
            if (index < 0) {
                list.clearSelection();
            } else if (list.getSelectedIndex() != index) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
            }
        } finally {
            followingCaret = false;
        }
    }

    /**
     * Selects the chosen statement in the editor and scrolls to it; with {@code focus} the editor gets the focus.
     */
    private void goToSelected(boolean focus) {
        Item item = list.getSelectedValue();
        SqlEditorPanel.SqlTextArea area = textArea;
        if (item == null || area == null) {
            return;
        }
        int length = area.getDocument().getLength();
        int start = Math.min(item.statement().start(), length);
        int end = Math.min(item.statement().end(), length);
        followingCaret = true;
        try {
            // Caret at the start first, so the start of a long statement is in view
            area.setCaretPosition(end);
            area.moveCaretPosition(start);
            try {
                Rectangle view = area.modelToView2D(start).getBounds();
                area.scrollRectToVisible(view);
            } catch (BadLocationException e) {
                // The selection is enough
            }
        } finally {
            followingCaret = false;
        }
        if (focus) {
            area.requestFocusInWindow();
        }
    }

    /**
     * The line number, then the statement's first line (the whole line in the tool tip when the column is narrow).
     */
    private static final class ItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Item item) {
                setText(item.line() + "   " + item.summary()); //$NON-NLS-1$
                setToolTipText(item.summary());
            }
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)));
            return this;
        }
    }
}
