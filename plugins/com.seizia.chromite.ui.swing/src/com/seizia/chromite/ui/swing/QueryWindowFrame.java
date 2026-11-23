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
import com.seizia.chromite.ui.swing.internal.DocumentTabMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 메인 창의 쿼리 창 하나를 따로 띄운 창(앱 변경 #173): 쿼리 창을 두 창에서 나란히 보기.
 *
 * 설계:
 * <ul>
 *     <li>새 쿼리 창을 만들지 않고 탭에 있던 {@link SqlEditorPanel} 객체를 그대로 옮겨 온다. 편집기, 그 결과 탭 줄({@link QueryResultsView}),
 *     상태 칸({@link QueryStatusLine}), DB 콤보는 원래 쿼리 창마다의 컴포넌트라 메인 창이 활성 탭의 것을 끼워 보여 주던 것을 이 창이 늘 끼워 둔다.
 *     그래서 옮겨도 세션, 실행 중인 쿼리, 결과, 트랜잭션, 고침 상태가 같은 객체로 남고 다시 연결하거나 문장을 보내지 않는다.</li>
 *     <li>배치는 메인 창의 쿼리 창 부분과 같다: 위에 SQL Editor 도구 막대({@link SqlEditorToolBar}, Query 메뉴 동작의 이 창만의 사본을 쓰는 둘째
 *     인스턴스라 켜짐·선택·도구 설명과 DB 콤보는 이 창 편집기의 것이고, 누르면 이 창의 쿼리 창에 적용된다), 가운데 편집기 카드와 그 아래 결과 카드(분할선), 아래 상태 표시줄(연결 색 띠). Islands 캔버스·카드
 *     모양도 메인 창과 같다.</li>
 *     <li>편집기 카드는 메인 창의 문서 카드처럼 위에 탭 줄을 두고 그 탭 하나가 이 창의 쿼리 창이다(앱 변경 #178). 탭 줄은 메인 창과 같은
 *     {@link MainFrame.EditorTabbedPane}({@code MainFrame#createDocumentTabs}: 같은 UI·스타일·닫기 단추·가운데 단추 닫기)이라 탭이 메인 창에서와 같게
 *     그려지고, 제목·아이콘·도구 설명은 메인 창이 탭에 하는 것처럼 {@link #updateTitle}이 맞춘다. 메인 창의 탭 위치·여러 행 설정은 따르지 않는다(탭이
 *     하나라 늘 위쪽 한 행). 나가는 길(탭 메뉴, 탭 줄 밖으로 끌기)과 같게 돌아오는 길도 탭에 있다: 탭을 메인 창의 탭 줄로 끌어 놓으면 그 자리로
 *     돌아가고({@link Host#queryTabDropped}), 오른쪽 클릭은 메인 창의 탭 메뉴({@link DocumentTabMenu#showQueryWindowMenu}, 주 창으로 이동 포함)다.
 *     예전의 도구 막대 ⋮ 단추와 그 메뉴는 같은 항목이 탭 메뉴에 있어 지웠다(한 메뉴는 한 곳에).</li>
 *     <li>단축키: 메뉴 막대는 메인 창에만 있으므로, 메인 창의 File·Edit·Query·Window 메뉴 항목의 단축키를 이 창의 루트 창에 같은 항목으로 건다
 *     (항목이 꺼져 있으면 아무것도 하지 않음). Execute의 Alt+X처럼 메인 창 루트 창에 따로 건 키도 같은 동작이면 건다. 그 동작들은 "활성 편집기"
 *     ({@link MainFrame}: 마지막으로 활성이 된 창의 쿼리 창)에 적용되므로 이 창에서 누르면 이 창의 쿼리 창에 적용된다. View·Tools 메뉴(메인 창의
 *     결과 카드와 도구 창)는 걸지 않는다: 이 창에서 누르면 아무 일도 없다.</li>
 *     <li>창 닫기(X)는 탭 닫기와 같은 경로({@link Host#closeQueryWindow}: 결과 표 변경, 저장하지 않은 변경, 열린 트랜잭션 질문)이고, 취소하면 창이
 *     남는다. 그래서 {@code DO_NOTHING_ON_CLOSE}로 직접 처리한다.</li>
 *     <li>제목은 탭 제목과 제품 이름, 아이콘은 제품 창 아이콘. 작업 표시줄에 따로 보이는 보통 창이고 항상 위가 아니다(소유 창 없음). 크기는 쿼리
 *     창이 메인 창에서 차지하던 크기다({@link #place}): 편집기는 문서 탭의 편집기 영역(탭 줄은 그 위에 더함), 결과 카드는 그 아래 Results 카드의 크기이고 분할선도 그
 *     자리다. 보던 것을 그대로 보이려는 것이다(메인 창 크기의 비율로 정하면 결과 영역의 고정 줄만 남아 그리드 행이 보이지 않았다). 카드마다 편집기
 *     {@link #MIN_EDITOR_LINES}줄, 그리드 {@link #MIN_GRID_ROWS}행은 보이게 하고, 폭은 도구 막대 줄과 상태 표시줄이 다 들어가게 한다. 메인 창이
 *     편집기 아래에 보이지 않는 결과 카드(숨김, 옆)는 그 최소 높이를 편집기 영역에서 덜어 창이 쿼리 창이 차지하던 높이를 넘지 않게 한다. 창은 화면
 *     작업 영역의 {@link #SCREEN_SHARE} 이하다(메인 창 위가 아니라 옆에 두는 창이라, 최대화한 메인 창에서도 화면을 덮지 않게). 줄이는 높이는 편집기에서
 *     뺀다. 최소 줄·행과 도구 막대 폭이 이 한도보다 앞선다. 위치는 놓은 자리(끌어서 뗄 때) 또는 메인 창에서 계단식이며, 어느 화면에도 다 들어가지 않으면 그 점이
 *     있는 화면(없으면 메인 창의 화면) 안으로 옮기고 줄인다({@link #fitToScreen}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #173) (docs/chromite/swing-frontend.md "쿼리 창을 별도 창으로")
 * - 2026-11-22 크기를 메인 창의 60%(편집기 60%) 대신 메인 창에서의 편집기 영역과 Results 카드 크기로, 최소 줄·행 수는 컴포넌트의 줄·행 높이로,
 *   화면 작업 영역의 2/3 이하로, 화면 고르기를 {@link #fitToScreen}과 함께 쓰는 getUsableArea로 (앱 변경 #174, docs/chromite/swing-frontend.md "쿼리 창을 별도 창으로")
 * - 2026-11-23 주석만: 도구 막대가 Query 메뉴 동작 대신 이 창만의 사본을 써서 이 창의 쿼리 창 상태를 보임({@code MainFrame#createSqlEditorToolBar},
 *   앱 변경 #175, docs/chromite/swing-frontend.md "쿼리 창을 별도 창으로")
 * - 2026-11-23 편집기 카드 위에 탭 하나의 탭 줄(메인 창의 문서 탭과 같은 것), 탭을 메인 창 탭 줄로 끌어 넣기, 탭의 오른쪽 클릭 메뉴는 메인 창의
 *   탭 메뉴, ⋮ 단추와 창 메뉴 삭제, 창이 탭 줄만큼 높아짐 (앱 변경 #178, docs/chromite/swing-frontend.md "쿼리 창을 별도 창으로")
 */
final class QueryWindowFrame extends JFrame {

    /**
     * The main window side.
     */
    interface Host {
        /**
         * This window became the active window: its query window is the active editor now.
         */
        void queryWindowActivated(@NotNull QueryWindowFrame frame);

        /**
         * Closes the query window through the questions of closing its tab; the window is disposed when it closes.
         *
         * @return false if the user canceled
         */
        boolean closeQueryWindow(@NotNull SqlEditorPanel editor);

        /**
         * The window's tab is dragged to a screen point: the main window shows where it would land there.
         */
        void queryTabDragged(@NotNull SqlEditorPanel editor, @NotNull Point screen);

        /**
         * The window's tab was dropped at a screen point: over the main window's tab strip the query window goes back
         * there, at that place; anywhere else nothing happens.
         */
        void queryTabDropped(@NotNull SqlEditorPanel editor, @NotNull Point screen);

        /**
         * Shows the tab menu of the window's tab.
         */
        void showQueryTabMenu(@NotNull SqlEditorPanel editor, @NotNull Component invoker, int x, int y);
    }

    /**
     * Share of a change of the window's height that goes to the editor above the results (resize weight).
     */
    private static final double EDITOR_SHARE = 0.6;
    /**
     * Lines of the editor and rows of the result grid a new window shows at least, and the size of a card the main window
     * does not give (the results when the Results card is hidden or beside the documents, both cards when the main window
     * is minimized). Counted in the components' own line and row heights, so that they hold at every scale and density.
     */
    private static final int MIN_EDITOR_LINES = 10;
    private static final int MIN_GRID_ROWS = 5;
    /**
     * The result viewer's own lines around the grid rows (edit line, column header, a status line of up to two lines),
     * about one grid row high each; the results add two tool tab rows (Grid… and Result N).
     */
    private static final int RESULT_VIEWER_LINES = 4;
    /**
     * Largest share of the screen's usable area, in each dimension, a new window takes: a window to put beside the main
     * window, not over it. A maximized main window would otherwise give a window as large as the screen.
     */
    private static final double SCREEN_SHARE = 2.0 / 3;
    private static final int CASCADE_STEP = 32;

    private final SqlEditorPanel editor;
    private final SqlEditorToolBar toolBar;
    // The tab strip with the query window's one tab, the main window's kind of document tabs
    private final MainFrame.EditorTabbedPane tabs;
    private final JPanel editorCard;
    private final JPanel resultsCard;
    private final JSplitPane split;
    private final MainFrame.StatusBar statusBar;
    // The results card's height when the window was sized (place), applied by the divider once it is shown
    private int resultsCardHeight;

    /**
     * @param toolBar      a SQL Editor tool bar of its own, with its own copies of the main window's Query actions
     * @param menus        the main window's menus whose shortcuts work in this window too
     * @param mainRootPane the main window's root pane, whose extra keys of those actions are bound too
     */
    QueryWindowFrame(@NotNull SqlEditorPanel editor, @NotNull SqlEditorToolBar toolBar, @NotNull Host host, @NotNull List<JMenu> menus,
                     @NotNull JRootPane mainRootPane) {
        this.editor = editor;
        this.toolBar = toolBar;
        setIconImages(ChromiteIcons.getWindowIcons());
        // Closing asks the questions of closing the tab first
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        toolBar.setActiveEditor(editor);
        // The close button and a middle click close the window as its X does
        tabs = MainFrame.createDocumentTabs(() -> { }, index -> host.closeQueryWindow(editor));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        editor.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        tabs.addTab(editor.getTitle(), editor.getTabIcon(), editor);
        installTabDrag(host);

        editorCard = new IslandPanel(tabs);
        resultsCard = new IslandPanel(editor.getResultsView().getComponent());
        split = new MainFrame.LineSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setContinuousLayout(true);
        split.setResizeWeight(EDITOR_SHARE);
        split.setTopComponent(editorCard);
        split.setBottomComponent(resultsCard);
        // The canvas gap at the sides, as around the main window's cards (the divider size)
        int gap = UIScale.scale(UIManager.getInt("SplitPane.dividerSize")); //$NON-NLS-1$
        split.setBorder(BorderFactory.createEmptyBorder(0, gap, 0, gap));

        statusBar = new MainFrame.StatusBar(""); //$NON-NLS-1$
        statusBar.setLine(editor.getStatusLine());

        JPanel content = new MainFrame.CanvasPanel(new BorderLayout());
        content.add(toolBar.getComponent(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(statusBar, BorderLayout.SOUTH);
        setContentPane(content);
        bindShortcuts(menus, mainRootPane);
        updateTitle();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                host.queryWindowActivated(QueryWindowFrame.this);
            }

            @Override
            public void windowClosing(WindowEvent e) {
                host.closeQueryWindow(editor);
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // The divider where place() put it. A window the screen made lower takes the height from the editor: the
                // results keep their rows, which the editor (the larger card when the window does not fit) can spare
                split.setDividerLocation(Math.max(0, split.getHeight() - split.getDividerSize() - resultsCardHeight));
            }
        });
    }

    /**
     * The tab's right click menu (the main window's tab menu, {@link Host#showQueryTabMenu}) and its dragging: a left
     * press on the tab, not on its close button, moved past the platform's drag threshold, as on the main window's strip
     * ({@link DocumentTabDrag}). While it is dragged the main window shows where it lands; the release decides there.
     */
    private void installTabDrag(@NotNull Host host) {
        MouseAdapter listener = new MouseAdapter() {
            @Nullable
            private Point pressedPoint;
            private boolean dragging;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedPoint = null;
                dragging = false;
                if (e.isPopupTrigger()) {
                    showMenu(e);
                    return;
                }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && !tabs.isCloseButtonAt(index, e.getPoint())) {
                    pressedPoint = e.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedPoint == null) {
                    return;
                }
                if (!dragging) {
                    if (pressedPoint.distance(e.getPoint()) < DragSource.getDragThreshold()) {
                        return;
                    }
                    dragging = true;
                }
                host.queryTabDragged(editor, e.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                }
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                boolean dropped = dragging;
                pressedPoint = null;
                dragging = false;
                if (dropped) {
                    host.queryTabDropped(editor, e.getLocationOnScreen());
                }
            }

            private void showMenu(@NotNull MouseEvent e) {
                if (tabs.indexAtLocation(e.getX(), e.getY()) >= 0) {
                    host.showQueryTabMenu(editor, tabs, e.getX(), e.getY());
                }
            }
        };
        tabs.addMouseListener(listener);
        tabs.addMouseMotionListener(listener);
    }

    @NotNull
    SqlEditorPanel getEditor() {
        return editor;
    }

    @NotNull
    SqlEditorToolBar getToolBar() {
        return toolBar;
    }

    /**
     * The window's title (the tab's title: file, connection, modified mark; and the product name) and its tab, as the
     * main window sets a tab ({@code MainFrame#titleChanged}).
     */
    void updateTitle() {
        setTitle(NLS.bind(DocumentTabMessages.tab_window_title, editor.getTitle(), GeneralUtils.getProductName()));
        int index = tabs.indexOfComponent(editor);
        if (index >= 0) {
            tabs.setTitleAt(index, editor.getTitle());
            tabs.setToolTipTextAt(index, editor.getToolTip());
            tabs.setIconAt(index, editor.getTabIcon());
        }
    }

    /**
     * Lets the query window's components go, before they go back to the main window or the window closes: the picker
     * leaves the tool bar, the status cells the status bar, the editor its tab and the results their card.
     */
    void release() {
        toolBar.setActiveEditor(null);
        statusBar.setLine(null);
        tabs.remove(editor);
        resultsCard.remove(editor.getResultsView().getComponent());
    }

    /**
     * Sizes and places a new window: at the point where the tab was dropped, or cascaded from the main window.
     * <p>
     * The cards get the size the query window had in the main window, so that the user keeps seeing what they saw: the
     * editor area of the document tabs above, the Results card below, the divider between them. A Results card the main
     * window does not show below the editor (hidden, beside) gets its floor out of the editor area, so the window is as
     * high as the area the query window had. Floors: the editor {@link #MIN_EDITOR_LINES} lines, the results
     * {@link #MIN_GRID_ROWS} grid rows, the width of the tool bar row and status bar. The frame adds those two rows, the
     * tab strip above the editor and its decoration ({@link #pack()}). The frame is at most {@link #SCREEN_SHARE} of the usable screen area in each
     * dimension; the height it takes away comes from the editor, down to its floor. The floors win over that limit, and
     * {@link #fitToScreen} over everything.
     *
     * @param drop        screen point where the tab was dropped, or null
     * @param index       how many separate windows are open already (the cascade step)
     * @param editorSize  the editor area of the main window's document tabs, or null if the main window gives none
     * @param resultsSize the main window's Results card content below the editor, or null if it is not there
     */
    void place(@Nullable Point drop, @NotNull Window main, int index, @Nullable Dimension editorSize, @Nullable Dimension resultsSize) {
        int minEditorHeight = getMinEditorHeight();
        int width = Math.max(editorSize != null ? editorSize.width : 0, resultsSize != null ? resultsSize.width : 0);
        int resultsHeight = Math.max(resultsSize != null ? resultsSize.height : 0, getMinResultsHeight());
        int editorHeight = editorSize == null ? 0 : resultsSize != null ? editorSize.height
            // The results come out of the column the editor had alone
            : editorSize.height - resultsHeight - split.getDividerSize();
        editorHeight = Math.max(editorHeight, minEditorHeight);
        Point location;
        if (drop != null) {
            // The pointer stays over the window's title bar, near its left end
            location = new Point(drop.x - UIScale.scale(48), drop.y - UIScale.scale(12));
        } else {
            int step = UIScale.scale(CASCADE_STEP) * (index + 1);
            location = new Point(main.getX() + step, main.getY() + step);
        }
        packCards(width, editorHeight, resultsHeight);
        Rectangle area = getUsableArea(new Rectangle(location, getSize()), main.getGraphicsConfiguration());
        int extraWidth = getWidth() - (int) (area.width * SCREEN_SHARE);
        int extraHeight = getHeight() - (int) (area.height * SCREEN_SHARE);
        if (extraWidth > 0 || extraHeight > 0) {
            // pack() keeps the tool bar row's and status bar's width; the results keep their height
            packCards(Math.max(0, width - Math.max(0, extraWidth)), Math.max(minEditorHeight, editorHeight - Math.max(0, extraHeight)), resultsHeight);
        }
        resultsCardHeight = resultsHeight + resultsCard.getInsets().top + resultsCard.getInsets().bottom;
        setBounds(fitToScreen(new Rectangle(location, getSize()), main.getGraphicsConfiguration()));
    }

    /**
     * Packs the frame around cards of these content sizes; the preferred sizes are only for this size, later the user's
     * resizing and the divider decide. The editor's size is the editor's own, below the tab strip (app change #178): the
     * tab pane adds the strip, so the window is higher by it and the editor keeps the size it had in the main window.
     */
    private void packCards(int width, int editorHeight, int resultsHeight) {
        Insets resultsInsets = resultsCard.getInsets();
        Dimension editorPreferred = editor.isPreferredSizeSet() ? editor.getPreferredSize() : null;
        editor.setPreferredSize(new Dimension(width, editorHeight));
        resultsCard.setPreferredSize(new Dimension(width + resultsInsets.left + resultsInsets.right, resultsHeight + resultsInsets.top + resultsInsets.bottom));
        pack();
        editor.setPreferredSize(editorPreferred);
        resultsCard.setPreferredSize(null);
    }

    /**
     * {@link #MIN_EDITOR_LINES} lines of the editor's text, and what the editor has besides its text (a document kind's
     * header) as it was laid out in the main window.
     */
    private int getMinEditorHeight() {
        SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
        Component scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, textArea);
        int other = scrollPane != null ? Math.max(0, editor.getHeight() - scrollPane.getHeight()) : 0;
        return MIN_EDITOR_LINES * textArea.getLineHeight() + other;
    }

    /**
     * The result tab rows, the result viewer's own lines and {@link #MIN_GRID_ROWS} rows, in the grid's row height before
     * zoom (FlatLaf gives tables the theme's {@code Table.rowHeight}, scaled).
     */
    private static int getMinResultsHeight() {
        int rowHeight = UIScale.scale(UIManager.getInt("Table.rowHeight")); //$NON-NLS-1$
        return 2 * UiSizes.get(UiSizes.TOOL_TAB_HEIGHT) + (RESULT_VIEWER_LINES + MIN_GRID_ROWS) * rowHeight;
    }

    /**
     * Bounds moved (and shrunk if needed) into the usable area of a screen: the one that holds their top left corner, else
     * the one they overlap most, else the given screen (a disconnected monitor, a point far off every screen).
     */
    @NotNull
    static Rectangle fitToScreen(@NotNull Rectangle bounds, @Nullable GraphicsConfiguration fallback) {
        Rectangle area = getUsableArea(bounds, fallback);
        int width = Math.min(bounds.width, area.width);
        int height = Math.min(bounds.height, area.height);
        int x = Math.clamp(bounds.x, area.x, area.x + area.width - width);
        int y = Math.clamp(bounds.y, area.y, area.y + area.height - height);
        return new Rectangle(x, y, width, height);
    }

    /**
     * The usable area (bounds without the task bar and the like) of the screen that bounds belong to: the one that holds
     * their top left corner, else the one they overlap most, else the given screen, else the default one.
     */
    @NotNull
    private static Rectangle getUsableArea(@NotNull Rectangle bounds, @Nullable GraphicsConfiguration fallback) {
        GraphicsConfiguration screen = null;
        int overlap = 0;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle area = configuration.getBounds();
            if (area.contains(bounds.getLocation())) {
                screen = configuration;
                break;
            }
            Rectangle common = area.intersection(bounds);
            if (!common.isEmpty() && common.width * common.height > overlap) {
                overlap = common.width * common.height;
                screen = configuration;
            }
        }
        if (screen == null) {
            screen = fallback != null ? fallback
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle area = screen.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        return new Rectangle(area.x + insets.left, area.y + insets.top, area.width - insets.left - insets.right,
            area.height - insets.top - insets.bottom);
    }

    /**
     * The main window's menu shortcuts that apply to a query window, bound in this window to the same menu items, and the
     * main window's extra keys of the same actions.
     */
    private void bindShortcuts(@NotNull List<JMenu> menus, @NotNull JRootPane mainRootPane) {
        InputMap keys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = getRootPane().getActionMap();
        Set<Action> menuActions = new HashSet<>();
        for (JMenu menu : menus) {
            bindItems(menu, keys, actions, menuActions);
        }
        InputMap mainKeys = mainRootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        KeyStroke[] mainStrokes = mainKeys.keys();
        if (mainStrokes != null) {
            for (KeyStroke key : mainStrokes) {
                Object name = mainKeys.get(key);
                Action action = name != null ? mainRootPane.getActionMap().get(name) : null;
                if (action != null && menuActions.contains(action)) {
                    keys.put(key, name);
                    actions.put(name, action);
                }
            }
        }
    }

    private static void bindItems(@NotNull JMenu menu, @NotNull InputMap keys, @NotNull ActionMap actions, @NotNull Set<Action> menuActions) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenu submenu) {
                bindItems(submenu, keys, actions, menuActions);
            } else if (component instanceof JMenuItem item && item.getAccelerator() != null) {
                // The item itself, so that what it does and whether it is enabled stay the main window's
                Action click = new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (item.isEnabled()) {
                            item.doClick(0);
                        }
                    }
                };
                keys.put(item.getAccelerator(), click);
                actions.put(click, click);
                if (item.getAction() != null) {
                    menuActions.add(item.getAction());
                }
            }
        }
    }
}
