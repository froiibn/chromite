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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import com.seizia.chromite.ui.swing.internal.TerminalMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 쿼리 창 하나의 결과 탭 줄: {@code Result1 | Result2 | … | Messages | Execution plan | Terminal}.
 *
 * 설계: 결과는 쿼리 창마다 따로 있다. 탭 줄도 쿼리 창마다 하나이고, 메인 창이 활성 쿼리 창의 탭 줄을 Results 카드에 통째로 끼운다
 * ({@link MainFrame}). 탭 줄이 쿼리 창의 것이라 고른 탭은 창을 바꿨다 돌아와도 그대로다(따로 기억할 상태가 없다).
 * <ul>
 *     <li>결과 탭(2026-11-08 사용자 결정, SSMS와 다르게): 결과 집합마다 탭 하나 "Result1", "Result2"…이고 탭은 결과 뷰어
 *     ({@link ResultSetViewerPanel}) 하나를 채운다. 결과가 하나여도 "Result1"이다. 번호는 실행마다 1부터, 결과가 나온 순서다.
 *     탭 도구 설명은 그 결과를 만든 SQL의 첫 줄이다. 전에는 결과 표를 한 탭 안에 위아래로 쌓고 높이를 나눴는데(SSMS), 결과가 많으면
 *     표마다 낮아지고 바깥 스크롤로 넘겨야 했다.</li>
 *     <li>그 뒤에 Messages, Execution plan, 켜져 있으면 Terminal(쿼리 창이 켰을 때만) 탭이다. 계획과 터미널 내용은 쿼리 창이 넘긴다.</li>
 *     <li>앞으로 오는 탭: 실행을 시작하면 Messages(아직 결과가 없다), 그 실행의 첫 결과가 나오면 Result1이다. 뒤의 결과는 탭만 생기고
 *     사용자가 고른 탭을 빼앗지 않는다. 오류로 멈췄거나 결과가 없으면 쿼리 창이 Messages를 앞으로 가져온다. 탭이 넘치면 FlatLaf의 탭 줄 스크롤과
 *     숨은 탭 목록이다.</li>
 *     <li>Messages: 쿼리별 행 수, 실행 시간, 경고(PRINT 출력 포함), 오류를 차례로 쓴다. 오류는 빨간색이다. 결과 표의 저장 결과와 저장 오류도 여기에 쓴다.
 *     오류가 있으면 탭에 오류 점과 개수를 붙인다(오류에서 멈추지 않고 이어 가는 설정에서도 오류가 있었음을 알린다). 다음 실행에서 지운다.</li>
 *     <li>결과 편집: 결과 표마다 바뀐 내용을 따로 가진다. 쿼리 창은 이 객체를 통해 저장하지 않은 변경이 있는지, 저장 중인지 묻고
 *     닫거나 다시 실행하기 전에 모두 저장한다({@link #saveChanges}).</li>
 *     <li>결과 고정(SWT의 결과 탭 고정, Query &gt; Pin Results): 고른 결과 탭(다른 탭이 앞에 있으면 마지막으로 고른 결과 탭)을 고정한다.
 *     탭에 핀 아이콘과 고정 시각이 붙고, 다음 실행은 고정하지 않은 결과 탭만 지우고 새 결과를 고정 탭 앞에 놓는다. 고정 탭은 닫을 수 있고,
 *     저장하지 않은 변경이 있으면 닫기 전에 묻는다(기본 Cancel). 고정 결과도 실행한 문맥으로 다시 읽고 저장하며, 창을 닫을 때의 저장 묻기
 *     ({@link #getChangeCount})에 들어간다. 다시 실행 전의 버리기 묻기는 지울 결과만 본다({@link #getCurrentChangeCount}).</li>
 *     <li>행 수(쿼리 상태 줄)는 고른 결과 탭의 행 수다({@link #getRowCount}). 결과가 아닌 탭이 앞에 있으면 쿼리 창이 실행에서 읽은 행 수를 보인다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 G단계: 편집할 수 있는 결과 표, 저장 메시지, 저장하지 않은 변경 묻기 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: Messages 글꼴 font.code, 오류 색 status.error (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 결과 뷰어: 결과 표마다 {@link ResultSetViewerPanel}(필터 줄, 값 패널, 레코드 보기, 상태 줄)로 감싸고, 지울 때 값 패널용 LOB 값을 놓아 줌
 *   (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 결과 뷰어가 다시 읽은 뒤의 행 수({@link #getRowCount()}, {@link Listener#resultsRowsChanged()}): 쿼리 상태 줄이 실행 때 행 수에
 *   머물던 문제 (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 결과 뷰어의 필터 줄이 없어져 최소 높이 주석을 고침(값은 그대로) (docs/chromite/swing-grid-column-filters.md)
 * - 2026-11-08 결과 고정({@link #pinResults}): 고정 탭, 지금 결과만 세는 {@link #getCurrentChangeCount}
 *   (docs/chromite/swing-sql-editor-ergonomics.md)
 * - 2026-11-08 오류 위치로 가기: 위치를 아는 오류 글자를 두 번 누르거나 오른쪽 클릭 "오류 위치로 가기"로 쿼리 창의 그 자리로
 *   ({@link Listener#goToSource}), Messages 오른쪽 클릭에 복사 (docs/chromite/swing-grid-navigation.md "오류 위치로 가기")
 * - 2026-11-08 결과 행에서 SQL 만들기: 만든 문장을 새 쿼리 창에 여는 {@link Listener#openScript} (docs/chromite/swing-result-generate-sql.md)
 * - 2026-11-08 결과 집합마다 탭(사용자 결정, 앱 변경 #7): 쿼리 창마다의 탭 줄 Result1… | Messages | Execution plan | Terminal이 메인 창의
 *   Results / Messages 바깥 탭과 결과 표 쌓기({@code GridStack})를 대신함, 고정은 고른 결과 탭 하나, Messages 탭의 오류 점·개수, 행 수는 고른 결과 탭
 *   (docs/chromite/swing-frontend.md "결과 탭")
 */
final class QueryResultsView {

    /**
     * The query window side: Messages tab and edit state of the grids.
     */
    interface Listener {
        /**
         * Brings the Messages tab to the front.
         */
        void showMessages();

        /**
         * Pending changes or the saving state of a grid changed.
         */
        void resultsEditStateChanged();

        /**
         * {@link #getRowCount()} may have changed: another result tab was selected, or a result viewer read its rows
         * again (filter, sort, more rows).
         */
        void resultsRowsChanged();

        /**
         * Go to Error: a double click on an error that knows its position in the query window's text.
         */
        default void goToSource(int offset) {
        }

        /**
         * Opens statements made from a result (Generate SQL) in a new query window, without running them.
         */
        void openScript(@NotNull DBPDataSourceContainer container, @NotNull String script);
    }

    /**
     * What Query &gt; Pin Results did.
     */
    enum PinResult {
        PINNED, NOTHING_TO_PIN, ALREADY_PINNED
    }

    /**
     * Character attribute of an error's text in Messages: its offset in the query window's text.
     */
    private static final Object ERROR_OFFSET_ATTRIBUTE = new Object() {
        @Override
        public String toString() {
            return "chromite.errorOffset"; //$NON-NLS-1$
        }
    };

    private static final DateTimeFormatter PIN_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    /**
     * Longest SQL line shown as a result tab's tool tip.
     */
    private static final int TOOL_TIP_SQL_LENGTH = 120;

    private final JTabbedPane tabs = new JTabbedPane();
    // The results of the last run, in tab order; a new run replaces them
    private final List<ResultTab> currentTabs = new ArrayList<>();
    // Pinned results, kept by the next runs until their tabs are closed
    private final List<ResultTab> pinnedTabs = new ArrayList<>();
    /**
     * The selected result tab, or the one selected last while another tab (Messages, plan, terminal) is in front: Pin
     * Results pins it, and showing the results brings it back.
     */
    @Nullable
    private ResultTab lastResultTab;
    private final JTextPane messagesPane = new JTextPane();
    private final JScrollPane messagesComponent;
    private final JComponent planComponent;
    // Errors written to Messages since the last clear
    private int errorCount;
    @Nullable
    private JComponent terminalComponent;
    private final Listener listener;
    private final ResultSetGrid.Host gridHost = new ResultSetGrid.Host() {
        @Override
        public void appendMessage(@NotNull String text) {
            QueryResultsView.this.appendMessage(text);
        }

        @Override
        public void appendError(@NotNull String text) {
            QueryResultsView.this.appendError(text);
        }

        @Override
        public void showMessages() {
            listener.showMessages();
        }

        @Override
        public void editStateChanged() {
            listener.resultsEditStateChanged();
        }

        @Override
        public void rowsChanged() {
            listener.resultsRowsChanged();
        }

        @Override
        public boolean canOpenScript() {
            return true;
        }

        @Override
        public void openScript(@NotNull DBPDataSourceContainer container, @NotNull String script) {
            listener.openScript(container, script);
        }
    };

    /**
     * @param planComponent the Execution plan tab's content
     */
    QueryResultsView(@NotNull Listener listener, @NotNull JComponent planComponent) {
        this.listener = listener;
        this.planComponent = planComponent;
        // size.tab.tool with a 3px underline (size.tabUnderline); one row that scrolls, with FlatLaf's list of hidden tabs
        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, (IntConsumer) this::closePinned);

        messagesPane.setEditable(false);
        // font.code, like the DataGrip console output
        messagesPane.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        installGoToError();
        messagesComponent = new JScrollPane(messagesPane);
        messagesComponent.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab(CoreMessages.main_messages, UiIcons.get("messages"), messagesComponent); //$NON-NLS-1$
        tabs.addTab(CoreMessages.main_execution_plan, UiIcons.get("plan"), planComponent); //$NON-NLS-1$
        clear();
        // After the first tabs: the query window is still being built while they are added
        tabs.addChangeListener(e -> {
            ResultTab selected = findTab(tabs.getSelectedComponent());
            if (selected != null) {
                lastResultTab = selected;
            }
            listener.resultsRowsChanged();
        });
    }

    /**
     * The tab row with its contents, put into the main window's Results card while the query window is active.
     */
    @NotNull
    JTabbedPane getComponent() {
        return tabs;
    }

    /**
     * Removes the results of the last run and clears the messages for a new run; pinned results stay.
     */
    void clear() {
        for (ResultTab tab : currentTabs) {
            removeTab(tab);
        }
        currentTabs.clear();
        messagesPane.setText("");
        errorCount = 0;
        updateMessagesTab();
    }

    private void removeTab(@NotNull ResultTab tab) {
        tab.viewer.dispose();
        tabs.remove(tab.viewer);
        if (tab == lastResultTab) {
            lastResultTab = null;
        }
    }

    @Nullable
    private ResultTab findTab(@Nullable Component component) {
        for (List<ResultTab> list : List.of(currentTabs, pinnedTabs)) {
            for (ResultTab tab : list) {
                if (tab.viewer == component) {
                    return tab;
                }
            }
        }
        return null;
    }

    /**
     * Adds a result of the running query as the next "ResultN" tab, before the pinned ones. The tab in front does not
     * change (the query window brings the run's first result to the front, {@link #showResult}).
     *
     * @param sql the query that gave the result; its first line is the tab's tool tip
     */
    void addGrid(@NotNull ResultSetGrid.Fetched fetched, @NotNull String sql) {
        ResultTab tab = new ResultTab(new ResultSetViewerPanel(fetched, gridHost),
            NLS.bind(EditorMessages.editor_results_tab, currentTabs.size() + 1), firstLine(sql));
        tabs.insertTab(tab.name, UiIcons.get("results"), tab.viewer, tab.sql, currentTabs.size()); //$NON-NLS-1$
        currentTabs.add(tab);
    }

    @NotNull
    private static String firstLine(@NotNull String sql) {
        for (String line : sql.strip().split("\\R")) { //$NON-NLS-1$
            if (!line.isBlank()) {
                String text = line.strip();
                return text.length() > TOOL_TIP_SQL_LENGTH ? text.substring(0, TOOL_TIP_SQL_LENGTH) + "…" : text; //$NON-NLS-1$
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Query &gt; Pin Results: pins the selected result tab (or the one selected last), so the next runs leave it.
     */
    @NotNull
    PinResult pinResults() {
        ResultTab tab = lastResultTab;
        if (tab == null) {
            return PinResult.NOTHING_TO_PIN;
        }
        if (pinnedTabs.contains(tab)) {
            return PinResult.ALREADY_PINNED;
        }
        // The tab stays where it is; the next run puts its results before the pinned tabs
        currentTabs.remove(tab);
        pinnedTabs.add(tab);
        String time = LocalTime.now().format(PIN_TIME_FORMAT);
        int index = tabs.indexOfComponent(tab.viewer);
        tab.viewer.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        tab.viewer.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, EditorMessages.editor_results_unpin_tip);
        tabs.setTitleAt(index, NLS.bind(EditorMessages.editor_results_pinned, tab.name, time));
        tabs.setIconAt(index, UiIcons.get("pin")); //$NON-NLS-1$
        tabs.setToolTipTextAt(index, NLS.bind(EditorMessages.editor_results_pinned_tip, tab.sql, time));
        tabs.setSelectedIndex(index);
        return PinResult.PINNED;
    }

    /**
     * Closes a pinned tab; unsaved changes of its grid are discarded after asking (Cancel is the default).
     */
    private void closePinned(int tabIndex) {
        ResultTab tab = findTab(tabs.getComponentAt(tabIndex));
        if (tab == null || !pinnedTabs.contains(tab) || tab.viewer.getGrid().isSaving()) {
            return;
        }
        int changes = tab.viewer.getGrid().getChangeCount();
        if (changes > 0) {
            String[] options = {EditorMessages.editor_results_unpin_discard, EditorMessages.editor_common_cancel};
            if (JOptionPane.showOptionDialog(tabs,
                NLS.bind(changes == 1 ? EditorMessages.editor_results_unpin_confirm_one : EditorMessages.editor_results_unpin_confirm_many, changes),
                tabs.getTitleAt(tabIndex), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
                return;
            }
        }
        pinnedTabs.remove(tab);
        removeTab(tab);
        listener.resultsEditStateChanged();
    }

    /**
     * Brings the Messages tab to the front.
     */
    void showMessages() {
        tabs.setSelectedComponent(messagesComponent);
    }

    /**
     * Brings a result to the front: the selected result tab stays, else the first result of the last run (a run's first
     * result), else the result tab selected last (a pinned one); Messages when there is no result.
     */
    void showResult() {
        if (findTab(tabs.getSelectedComponent()) != null) {
            return;
        }
        ResultTab tab = !currentTabs.isEmpty() ? currentTabs.get(0) : lastResultTab;
        tabs.setSelectedComponent(tab != null ? tab.viewer : messagesComponent);
    }

    void showPlan() {
        tabs.setSelectedComponent(planComponent);
    }

    /**
     * The Terminal tab's content while the query window has Query &gt; SQL Terminal on, or null to remove the tab. It is
     * there only while the terminal is on: the switch is per window, so a tab that is always there would be empty in
     * the other windows.
     */
    void setTerminalComponent(@Nullable JComponent component) {
        if (component == terminalComponent) {
            return;
        }
        if (terminalComponent != null) {
            boolean selected = tabs.getSelectedComponent() == terminalComponent;
            tabs.remove(terminalComponent);
            if (selected) {
                // The window's output without the terminal
                showResult();
            }
        }
        terminalComponent = component;
        if (component != null) {
            tabs.addTab(TerminalMessages.terminal_tab, UiIcons.get("terminal"), component); //$NON-NLS-1$
        }
    }

    void showTerminal() {
        if (terminalComponent != null) {
            tabs.setSelectedComponent(terminalComponent);
        }
    }

    /**
     * Result tabs of the last run (not the pinned ones).
     */
    int getGridCount() {
        return currentTabs.size();
    }

    /**
     * Rows shown now (after filters and fetching more rows) in the selected result tab; -1 while another tab is in
     * front.
     */
    long getRowCount() {
        ResultTab tab = findTab(tabs.getSelectedComponent());
        return tab != null ? tab.viewer.getRowCount() : -1;
    }

    /**
     * The grids of the current results and of the pinned ones.
     */
    @NotNull
    private List<ResultSetGrid> getGrids() {
        List<ResultSetGrid> grids = new ArrayList<>(getGrids(currentTabs));
        grids.addAll(getGrids(pinnedTabs));
        return grids;
    }

    @NotNull
    private static List<ResultSetGrid> getGrids(@NotNull List<ResultTab> list) {
        List<ResultSetGrid> grids = new ArrayList<>();
        for (ResultTab tab : list) {
            grids.add(tab.viewer.getGrid());
        }
        return grids;
    }

    /**
     * Pending (unsaved) changes in all result grids, pinned ones included (closing the window).
     */
    int getChangeCount() {
        int count = 0;
        for (ResultSetGrid grid : getGrids()) {
            count += grid.getChangeCount();
        }
        return count;
    }

    /**
     * Pending changes in the results of the last run, the ones a new run replaces.
     */
    int getCurrentChangeCount() {
        int count = 0;
        for (ResultSetGrid grid : getGrids(currentTabs)) {
            count += grid.getChangeCount();
        }
        return count;
    }

    boolean isSaving() {
        for (ResultSetGrid grid : getGrids()) {
            if (grid.isSaving()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves the pending changes of all grids, waiting for each in a progress dialog.
     *
     * @return false if a save failed (the error is in the Messages tab) or was canceled
     */
    boolean saveChanges() {
        for (ResultSetGrid grid : getGrids()) {
            if (grid.isDirty() && !grid.save(true)) {
                return false;
            }
        }
        return true;
    }

    void appendMessage(@NotNull String text) {
        append(text, null);
    }

    void appendError(@NotNull String text) {
        appendError(text, -1);
    }

    /**
     * @param sourceOffset the error's position in the query window's text (a double click goes there), or -1
     */
    void appendError(@NotNull String text, int sourceOffset) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        Color errorColor = UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
        StyleConstants.setForeground(attributes, errorColor != null ? errorColor : Color.RED);
        if (sourceOffset >= 0) {
            attributes.addAttribute(ERROR_OFFSET_ATTRIBUTE, sourceOffset);
        }
        append(text, attributes);
        errorCount++;
        updateMessagesTab();
    }

    /**
     * The Messages tab with an error dot and the number of errors while it has any, so they are seen also when a
     * result is in front (Options: skip failed statements and continue).
     */
    private void updateMessagesTab() {
        int index = tabs.indexOfComponent(messagesComponent);
        Icon icon = UiIcons.get("messages"); //$NON-NLS-1$
        if (errorCount == 0) {
            tabs.setTitleAt(index, CoreMessages.main_messages);
            tabs.setIconAt(index, icon);
            tabs.setToolTipTextAt(index, null);
        } else {
            tabs.setTitleAt(index, NLS.bind(EditorMessages.editor_results_messages_errors, CoreMessages.main_messages, errorCount));
            tabs.setIconAt(index, UiIcons.withStatusDot(icon, UiIcons.Status.ERROR));
            tabs.setToolTipTextAt(index, NLS.bind(EditorMessages.editor_results_messages_errors_tip, errorCount));
        }
    }

    /**
     * Go to Error (SWT {@code GoToErrorAction}): a double click on an error with a position, or its context menu item,
     * moves the query window's caret there. The pointer and the tool tip show which texts have a position.
     */
    private void installGoToError() {
        JPopupMenu menu = new JPopupMenu();
        int[] menuOffset = {-1};
        JMenuItem goToItem = new JMenuItem(GridNavMessages.gridnav_goto_error);
        goToItem.addActionListener(e -> {
            if (menuOffset[0] >= 0) {
                listener.goToSource(menuOffset[0]);
            }
        });
        JMenuItem copyItem = new JMenuItem(EditorMessages.editor_grid_copy);
        copyItem.addActionListener(e -> messagesPane.copy());
        menu.add(goToItem);
        menu.add(copyItem);
        messagesPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int offset = getErrorOffsetAt(e.getPoint());
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && offset >= 0) {
                    listener.goToSource(offset);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);
            }

            private void showMenu(@NotNull MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menuOffset[0] = getErrorOffsetAt(e.getPoint());
                    goToItem.setEnabled(menuOffset[0] >= 0);
                    copyItem.setEnabled(messagesPane.getSelectionStart() != messagesPane.getSelectionEnd());
                    menu.show(messagesPane, e.getX(), e.getY());
                }
            }
        });
        messagesPane.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean onError = getErrorOffsetAt(e.getPoint()) >= 0;
                messagesPane.setCursor(Cursor.getPredefinedCursor(onError ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                messagesPane.setToolTipText(onError ? GridNavMessages.gridnav_goto_error_tip : null);
            }
        });
    }

    /**
     * The query position of the error text at a point of Messages, or -1.
     */
    private int getErrorOffsetAt(@NotNull Point point) {
        int position = messagesPane.viewToModel2D(point);
        if (position < 0) {
            return -1;
        }
        Object offset = messagesPane.getStyledDocument().getCharacterElement(position).getAttributes().getAttribute(ERROR_OFFSET_ATTRIBUTE);
        return offset instanceof Integer value ? value : -1;
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
     * A result tab: its viewer (the tab's content), "ResultN", and the first line of the query that gave it.
     */
    private record ResultTab(@NotNull ResultSetViewerPanel viewer, @NotNull String name, @NotNull String sql) {
    }
}
