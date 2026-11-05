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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;

/**
 * 쿼리 창 하나의 Results / Messages 내용(SSMS의 결과 창).
 *
 * 설계: SSMS처럼 결과는 쿼리 창마다 따로 있다. 메인 창 아래의 Results / Messages 탭은 하나이므로, 쿼리 창이 이 객체를 갖고
 * 메인 창이 활성 쿼리 창의 두 컴포넌트를 탭에 끼운다({@link MainFrame}).
 * <ul>
 *     <li>Results: 결과 집합마다 {@link ResultSetGrid} 하나를 위아래로 쌓는다. 모두 들어가면 높이를 똑같이 나눠 채우고
 *     (SSMS와 같음), 표마다 최소 높이보다 작아지면 바깥 세로 스크롤로 넘긴다.</li>
 *     <li>Messages: 쿼리별 행 수, 실행 시간, 경고(PRINT 출력 포함), 오류를 차례로 쓴다. 오류는 빨간색이다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 */
final class QueryResultsView {

    private static final int MIN_GRID_HEIGHT = 140;

    private final GridStack gridStack = new GridStack();
    private final JScrollPane resultsComponent;
    private final JLabel placeholder;
    private final JTextPane messagesPane = new JTextPane();
    private final JScrollPane messagesComponent;
    private boolean messagesTabSelected;

    QueryResultsView() {
        placeholder = new JLabel("No results.", SwingConstants.CENTER);
        placeholder.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        resultsComponent = new JScrollPane(gridStack);
        resultsComponent.setBorder(BorderFactory.createEmptyBorder());
        resultsComponent.getVerticalScrollBar().setUnitIncrement(16);

        messagesPane.setEditable(false);
        messagesComponent = new JScrollPane(messagesPane);
        messagesComponent.setBorder(BorderFactory.createEmptyBorder());
        clear();
    }

    @NotNull
    JComponent getResultsComponent() {
        return resultsComponent;
    }

    @NotNull
    JComponent getMessagesComponent() {
        return messagesComponent;
    }

    void clear() {
        gridStack.removeAll();
        gridStack.add(placeholder);
        gridStack.revalidate();
        gridStack.repaint();
        messagesPane.setText("");
    }

    /**
     * Which of the two tabs the user looked at last for this query window, restored when the window becomes active again.
     */
    boolean isMessagesTabSelected() {
        return messagesTabSelected;
    }

    void setMessagesTabSelected(boolean messagesTabSelected) {
        this.messagesTabSelected = messagesTabSelected;
    }

    int getGridCount() {
        return gridStack.getComponentCount() == 1 && gridStack.getComponent(0) == placeholder ? 0 : gridStack.getComponentCount();
    }

    void addGrid(@NotNull ResultSetGrid.Data data) {
        if (getGridCount() == 0) {
            gridStack.removeAll();
        }
        gridStack.add(new ResultSetGrid(data));
        gridStack.revalidate();
        gridStack.repaint();
    }

    void appendMessage(@NotNull String text) {
        append(text, null);
    }

    void appendError(@NotNull String text) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        Color errorColor = UIManager.getColor("Actions.Red"); //$NON-NLS-1$
        StyleConstants.setForeground(attributes, errorColor != null ? errorColor : Color.RED);
        append(text, attributes);
    }

    private void append(@NotNull String text, @Nullable SimpleAttributeSet attributes) {
        StyledDocument document = messagesPane.getStyledDocument();
        try {
            document.insertString(document.getLength(), text.endsWith("\n") ? text : text + "\n", attributes);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Grids stacked vertically. While every grid gets at least {@link #MIN_GRID_HEIGHT}, the stack tracks the
     * viewport height and the grids share it equally; otherwise it is taller than the viewport and scrolls.
     */
    private static final class GridStack extends JPanel implements Scrollable {
        GridStack() {
            super(new GridLayout(0, 1, 0, 6));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, getComponentCount() * MIN_GRID_HEIGHT);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() >= getComponentCount() * MIN_GRID_HEIGHT;
        }
    }
}
