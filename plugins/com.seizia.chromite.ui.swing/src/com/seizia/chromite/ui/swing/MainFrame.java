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
import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvent;
import org.jkiss.dbeaver.model.DBPEventListener;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Chromite Swing 메인 창 (SSMS식 배치).
 *
 * 설계: 왼쪽 Object Explorer, 가운데 문서 탭, 문서 아래 Results / Messages, 오른쪽 Properties를 {@link JSplitPane}으로
 * 나눈다(도구 창 처리는 {@link ToolWindow}). 분할 구조는 바깥부터 Object Explorer | (문서·결과 | Properties)이고,
 * Properties가 SSMS처럼 문서와 결과 옆에 세로로 길게 붙는다.
 * <ul>
 *     <li>창 위치·크기·최대화 여부와 도구 창 크기·표시 여부는 이 번들의 설정 저장소에 저장한다({@link ChromiteTheme} 참고).
 *     저장은 창을 닫을 때({@link #dispose()}) 한 번 한다.</li>
 *     <li>도구 창 크기는 창이 실제 크기를 가진 뒤(windowOpened 이후) 바깥 분할부터 차례로 적용한다.</li>
 *     <li>모양은 DataGrip Islands다: 메인 창 바탕은 캔버스({@code Chromite.island.canvas})이고 Object Explorer, 문서 탭·편집기, Results, Properties가
 *     각각 둥근 카드({@link IslandPanel})다. 카드 사이 틈은 분할선(6px, 선 없음)과 좌우 바깥 여백이다. 주 도구 막대와 상태 표시줄은 캔버스 위에 있다.</li>
 *     <li>메뉴는 SSMS 순서로 File(새 연결·쿼리, 파일, Exit), Edit({@link EditMenu}), View(도구 창 토글, Theme), Query(실행, 취소, 자동완성),
 *     Tools(Options, {@link OptionsDialog}), Window(Reset Window Layout, 열린 문서 탭 목록), Help(About)이다. FlatLaf의 통합 제목 표시줄(Windows)에서
 *     메뉴 막대는 A단계처럼 제목 표시줄 안에 들어간다. Tools &gt; Options와 같은 설정을 보이는 메뉴 항목(Theme, Icon Style, Auto-Complete While Typing)은
 *     메뉴를 열 때 현재 값으로 맞춘다.
 *     도구 창 단축키는 SSMS와 같다(Object Explorer F8, Properties F4, Results Ctrl+R).</li>
 *     <li>문서 탭: 시작 탭과 SQL 편집기 탭({@link SqlEditorPanel}). 편집기는 Object Explorer의 "New Query", File > New Query(Ctrl+N),
 *     도구 막대의 New Query로 연다. 제목은 SSMS처럼 {@code SQLQueryN.sql - 연결 이름}이고, 도구 설명은 전체 파일 경로와 연결·서버다.
 *     닫기 단추는 DataGrip처럼 선택한 탭과 마우스를 올린 탭에만 보이고({@link IslandTabbedPaneUI}), 가운데 단추로 눌러도 닫는다.</li>
 *     <li>Query 메뉴의 Execute(F5)와 Cancel Executing Query(Alt+Break)는 활성 편집기에 적용된다. 메뉴 단축키라 포커스가 결과 표에 있어도 동작한다.
 *     Complete(Ctrl+Space)는 활성 편집기의 자동완성을 띄우고, Auto-Complete While Typing은 글자 입력 뒤 자동완성 설정을 켜고 끈다({@link SqlCompletionPopup}).</li>
 *     <li>결과는 SSMS처럼 쿼리 창마다 따로 있다({@link QueryResultsView}). 아래 Results / Messages 탭에는 활성 편집기의 결과를 끼우고,
 *     편집기가 없으면 자리 표시 내용을 보여 준다. 편집기마다 마지막으로 본 탭(Results 또는 Messages)을 기억한다. 탭에서 빠져 있던 결과 컴포넌트는 테마 전환을 받지 못했을 수 있어, 끼울 때 Look and Feel을 맞춘다.
 *     쿼리 상태 줄({@link QueryStatusLine})도 같은 방식으로, 활성 편집기의 줄을 Results 카드의 아래쪽(SSMS 쿼리 창 맨 아래)에 끼운다.
 *     Results가 숨겨져 있으면 문서 카드의 아래쪽(편집기 아래)으로 옮긴다. 시작 탭과 DDL 탭에는 없다.</li>
 *     <li>File > New Connection과 도구 막대의 New Connection은 Object Explorer의 같은 명령으로 연결 대화상자({@link ConnectionDialog})를 연다.</li>
 *     <li>상태 표시줄의 연결 수는 모든 프로젝트의 연결 레지스트리 이벤트({@link DBPEventListener}, 연결 추가·삭제)를 받아 다시 센다.
 *     같은 이벤트의 연결 변경(이름 바꾸기, 연결, 끊기)은 그 연결을 쓰는 편집기의 탭 제목과 기본 스키마 콤보에 알린다.</li>
 *     <li>SQL 파일(SSMS): File > Open File(Ctrl+O), Save(Ctrl+S), Save As, Recent Files(최근 10개, {@link RecentFiles}).
 *     연 파일은 Object Explorer에서 고른 연결(없으면 활성 편집기의 연결, 그것도 없으면 연결 없음)로 연다. 이미 열린 파일은 그 탭을 보여 준다.
 *     파일 대화상자의 폴더는 실행 중에만 기억한다.</li>
 *     <li>고친 편집기 탭을 닫거나 앱을 끝낼 때(창 닫기, File > Exit) SSMS처럼 Save / Don't Save / Cancel을 묻는다.
 *     결과 표에 저장하지 않은 변경이 있는 편집기도 같이 묻고, Save면 결과 변경을 진행 대화상자에서 저장한다(실패하면 닫지 않고 오류는 Messages에).
 *     결과를 저장하는 중인 편집기는 닫지 않는다.
 *     그래서 창 닫기는 직접 처리하고({@code DO_NOTHING_ON_CLOSE}), 끝내기로 하면 dispose한다. 외부 종료 요청({@code IApplication.stop})은 묻지 않고 닫는다.</li>
 *     <li>Object Explorer의 스크립트 명령({@link ObjectExplorerPanel.QueryWindows}): 스크립트를 담은 새 편집기, "View DDL"의 읽기 전용 탭({@link DdlViewerPanel}).</li>
 * </ul>
 * 창을 닫으면 dispose되고, {@link ChromiteSwingApplication}이 windowClosed 이벤트로 앱을 종료한다.
 * 창 아이콘은 Java 런타임(JBR) 기본 아이콘 대신 Chromite 아이콘을 쓴다({@link ChromiteIcons}).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-06 B단계: SSMS식 배치(Object Explorer, 문서 탭, Results/Messages, Properties), View/Help 메뉴, 테마 전환,
 *   창·도구 창 배치 저장, 알림 표시 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 C단계: SQL 편집기 탭, New Query(메뉴·도구 막대·Object Explorer), Query 메뉴(F5 실행, 취소),
 *   편집기별 Results / Messages 표시 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 D단계: New Connection(메뉴·도구 막대), 연결 추가·삭제 때 상태 표시줄 연결 수 갱신
 *   (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 E단계: SQL 파일 열기·저장·최근 파일, 닫을 때 저장 확인, 스크립트 편집기와 DDL 탭, 연결 변경을 편집기에 알림
 *   (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: Query > Complete(Ctrl+Space), Auto-Complete While Typing (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 닫을 때·끝낼 때 결과 표의 저장하지 않은 변경도 묻기 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: 아이콘 도구 막대(New Query만 글자), 문서 탭 40·밑줄 4·아이콘, 결과 탭 32·아이콘, 1px 분할선,
 *   상태 표시줄 28, View > Theme > Icon Style (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 Islands: 캔버스 위 둥근 카드(Object Explorer, 문서, Results, Properties), 카드 사이 6px 틈의 분할선, 문서 탭 알약 모양 선택,
 *   주 도구 막대·상태 표시줄은 캔버스 (docs/chromite/design-spec.md "Islands 적용")
 * - 2026-11-06 P1: Edit·Tools·Window 메뉴(SSMS 순서) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 쿼리 상태 줄을 결과 아래에 둠 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 문서 탭 닫기 단추를 선택·마우스 올린 탭에만, 가운데 단추로 닫기 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 */
final class MainFrame extends JFrame implements SqlEditorPanel.Host, ObjectExplorerPanel.QueryWindows {

    private static final String PREF_BOUNDS = "mainWindow.bounds"; //$NON-NLS-1$
    private static final String PREF_MAXIMIZED = "mainWindow.maximized"; //$NON-NLS-1$
    private static final Dimension DEFAULT_SIZE = new Dimension(1280, 800);
    private static final String UPDATED_LAF_KEY = "chromite.updatedLookAndFeel"; //$NON-NLS-1$
    private static final int RESULTS_TAB = 0;
    private static final int MESSAGES_TAB = 1;

    private final ObjectExplorerPanel explorerPanel;
    private final StatusBar statusBar;
    private final List<DBPDataSourceRegistry> listenedRegistries = new ArrayList<>();
    private final DBPEventListener registryListener = event -> {
        if ((event.getAction() == DBPEvent.Action.OBJECT_ADD || event.getAction() == DBPEvent.Action.OBJECT_REMOVE)
            && event.getObject() instanceof DBPDataSourceContainer) {
            // Registry events come from any thread
            SwingUtilities.invokeLater(this::updateStatusText);
        } else if (event.getAction() == DBPEvent.Action.OBJECT_UPDATE && event.getObject() instanceof DBPDataSourceContainer container) {
            SwingUtilities.invokeLater(() -> connectionUpdated(container));
        }
    };
    private final JTabbedPane documentTabs;
    private final JTabbedPane resultTabs;
    private final JComponent resultsPlaceholder;
    private final JComponent messagesPlaceholder;
    /**
     * Hold the active editor's query status line at the bottom of the query window (SSMS): inside the Results card below
     * the results, or at the bottom of the document card while the Results tool window is hidden.
     */
    private final JPanel resultsStatusHolder = new JPanel(new BorderLayout());
    private final JPanel documentStatusHolder = new JPanel(new BorderLayout());
    @Nullable
    private JComponent attachedStatusLine;
    private final ToolWindow resultsWindow;
    private final Action newQueryAction;
    private final Action openFileAction;
    private final Action saveAction;
    private final Action saveAsAction;
    private final Action executeQueryAction;
    private final Action cancelQueryAction;
    private final Action completeAction;
    private final EditMenu editMenu;
    private int queryCounter;
    /**
     * Folder of the last file dialog, for this run of the application.
     */
    @Nullable
    private Path lastDirectory;
    private boolean switchingResults;
    // Outer split first: inner split panes are sized from the outer layout
    private final List<ToolWindow> toolWindows;
    @Nullable
    private Rectangle normalBounds;
    private boolean layoutSaved;

    MainFrame(@NotNull String title, @NotNull String statusText) {
        super(title);
        setIconImages(ChromiteIcons.getWindowIcons());
        // Closing asks to save modified query windows first (requestExit)
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        PropertiesPanel propertiesPanel = new PropertiesPanel();
        explorerPanel = new ObjectExplorerPanel(propertiesPanel::showNode, this);
        resultsPlaceholder = createPlaceholder("No results. Query results will appear here.");
        messagesPlaceholder = createPlaceholder("No messages.");
        resultTabs = new JTabbedPane();
        // size.tab.tool 32 with a 3px underline (size.tabUnderline), icons results.grid / results.messages
        resultTabs.putClientProperty(FlatClientProperties.STYLE, "tabHeight: 32; tabSelectionHeight: 3"); //$NON-NLS-1$
        resultTabs.addTab("Results", UiIcons.get("results"), resultsPlaceholder); //$NON-NLS-2$
        resultTabs.addTab("Messages", UiIcons.get("messages"), messagesPlaceholder); //$NON-NLS-2$
        documentTabs = createDocumentTabs();

        JSplitPane documentSplit = createSplitPane(JSplitPane.VERTICAL_SPLIT);
        // Islands: the document tabs and their editors are one card; the query status line goes to its bottom only
        // while the Results card is hidden (attachStatusLine)
        JPanel documentArea = new JPanel(new BorderLayout());
        documentArea.add(documentTabs, BorderLayout.CENTER);
        documentArea.add(documentStatusHolder, BorderLayout.SOUTH);
        documentSplit.setTopComponent(new IslandPanel(documentArea));
        JPanel resultsArea = new JPanel(new BorderLayout());
        resultsArea.setOpaque(false);
        resultsArea.add(resultTabs, BorderLayout.CENTER);
        resultsArea.add(resultsStatusHolder, BorderLayout.SOUTH);
        // The status line follows the Results card when it is hidden or shown again (Ctrl+R, hide button)
        resultsArea.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                placeStatusLine();
            }
        });
        resultsStatusHolder.setVisible(false);
        documentStatusHolder.setVisible(false);
        JSplitPane propertiesSplit = createSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        propertiesSplit.setLeftComponent(documentSplit);
        JSplitPane explorerSplit = createSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        explorerSplit.setRightComponent(propertiesSplit);

        ToolWindow explorerWindow = new ToolWindow("objectExplorer", "Object Explorer", true, //$NON-NLS-1$
            explorerPanel, explorerSplit, true, 300,
            KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
        ToolWindow propertiesWindow = new ToolWindow("properties", "Properties", true, //$NON-NLS-1$
            propertiesPanel, propertiesSplit, false, 300,
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        // The Results/Messages tabs are their own header; the hide button sits next to the tabs
        resultsWindow = new ToolWindow("results", "Results", false, //$NON-NLS-1$
            resultsArea, documentSplit, false, 220,
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        // The trailing component fills the rest of the tab row; keep the button at its right end
        JPanel resultTabsTrailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        resultTabsTrailing.setOpaque(false);
        resultTabsTrailing.add(resultsWindow.createHideButton());
        resultTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, resultTabsTrailing);
        toolWindows = List.of(explorerWindow, propertiesWindow, resultsWindow);

        newQueryAction = createAction("New Query", "Open a SQL editor for the connection selected in Object Explorer (Ctrl+N)",
            KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), this::newQuery);
        openFileAction = createAction("Open File…", "Open a SQL file in a new query window (Ctrl+O)",
            KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), this::openFile);
        saveAction = createAction("Save", "Save the active query window (Ctrl+S)",
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    saveEditor(editor, false);
                }
            });
        saveAsAction = createAction("Save As…", "Save the active query window to another file", null, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null) {
                saveEditor(editor, true);
            }
        });
        executeQueryAction = createAction("Execute", "Execute the selection, or the whole script without a selection",
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.execute();
                }
            });
        cancelQueryAction = createAction("Cancel Executing Query", null,
            KeyStroke.getKeyStroke(KeyEvent.VK_PAUSE, InputEvent.ALT_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.cancel();
                }
            });
        completeAction = createAction("Complete", "Show code completion proposals at the caret of the active query window",
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.complete();
                }
            });
        editMenu = new EditMenu(this::getActiveTextArea);
        documentTabs.addChangeListener(e -> activeEditorChanged());
        resultTabs.addChangeListener(e -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null && !switchingResults) {
                editor.getResultsView().setMessagesTabSelected(resultTabs.getSelectedIndex() == MESSAGES_TAB);
            }
        });
        updateQueryActions();

        setJMenuBar(createMenuBar());

        JPanel content = new CanvasPanel(new BorderLayout());
        content.add(createToolBar(), BorderLayout.NORTH);
        // Islands: the canvas shows as a gap (Chromite.island.gap) left and right of the outer cards
        JPanel islands = new CanvasPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                int gap = UIScale.scale(Math.max(0, UIManager.getInt("Chromite.island.gap"))); //$NON-NLS-1$
                setBorder(BorderFactory.createEmptyBorder(0, gap, 0, gap));
            }
        };
        islands.add(explorerSplit, BorderLayout.CENTER);
        content.add(islands, BorderLayout.CENTER);
        statusBar = new StatusBar(statusText);
        content.add(statusBar, BorderLayout.SOUTH);
        setContentPane(content);
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            registry.addDataSourceListener(registryListener);
            listenedRegistries.add(registry);
        }

        restoreLayout();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // Split panes have their real size only after the window is shown and laid out
                SwingUtilities.invokeLater(() -> {
                    toolWindows.forEach(ToolWindow::applySize);
                    explorerPanel.focusTree();
                });
            }
        });
    }

    /**
     * Shows a non-modal notification in the lower right corner (used by {@link SwingPlatformUI#showNotification}).
     */
    void showNotification(@NotNull String title, @Nullable String message, @NotNull NotificationToast.Kind kind, @Nullable Runnable feedback) {
        NotificationToast.show(this, title, message, kind, feedback);
    }

    @Override
    public void dispose() {
        if (!layoutSaved && isDisplayable()) {
            layoutSaved = true;
            saveLayout();
        }
        explorerPanel.dispose();
        editMenu.dispose();
        for (DBPDataSourceRegistry registry : listenedRegistries) {
            registry.removeDataSourceListener(registryListener);
        }
        listenedRegistries.clear();
        super.dispose();
    }

    /**
     * Status bar text: the number of connections in all projects of the workspace.
     */
    @NotNull
    static String describeWorkspace(@NotNull DBPWorkspace workspace) {
        int connections = 0;
        for (DBPProject project : workspace.getProjects()) {
            connections += project.getDataSourceRegistry().getDataSources().size();
        }
        return connections + (connections == 1 ? " connection" : " connections") + " in workspace";
    }

    private void updateStatusText() {
        if (isDisplayable()) {
            statusBar.setText(describeWorkspace(DBWorkbench.getPlatform().getWorkspace()));
        }
    }

    @NotNull
    private static JSplitPane createSplitPane(int orientation) {
        JSplitPane splitPane = new LineSplitPane(orientation);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable String description, @Nullable KeyStroke accelerator, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        action.putValue(Action.ACCELERATOR_KEY, accelerator);
        return action;
    }

    /**
     * SSMS standard tool bar: [New Connection] [New Query (icon and text)] | [Open File] [Save]; the other buttons
     * are icons with tool tips (size.toolbar.main 36 = 26px buttons + 5px above and below).
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        // On the Islands canvas (MainToolbar.background = main-window-bg)
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(5), UIScale.scale(8), UIScale.scale(5), UIScale.scale(8)));
        toolBar.add(UiIcons.createActionButton(explorerPanel.getNewConnectionAction(), "new-connection")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(newQueryAction, "new-query")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(openFileAction, "open")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(saveAction, "save")); //$NON-NLS-1$
        return toolBar;
    }

    //////////////////////////////////////////////////////////
    // Query editors

    /**
     * File > New Query and the tool bar: the connection selected in Object Explorer, else the one of the active editor.
     */
    private void newQuery() {
        DBPDataSourceContainer dataSource = explorerPanel.getSelectedDataSource();
        if (dataSource == null) {
            SqlEditorPanel activeEditor = getActiveEditor();
            dataSource = activeEditor != null ? activeEditor.getDataSourceContainer() : null;
        }
        if (dataSource == null) {
            JOptionPane.showMessageDialog(this, "Select a connection (or any object under it) in Object Explorer first.",
                "New Query", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openQuery(dataSource);
    }

    @Override
    public void openQuery(@NotNull DBPDataSourceContainer dataSource) {
        openQueryEditor(dataSource, null);
    }

    @Override
    public void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute) {
        SqlEditorPanel editor = openQueryEditor(dataSource, null);
        editor.setContent(SqlFileContent.newFile(script), null);
        editor.setMaxRows(maxRows);
        if (execute) {
            editor.execute();
        }
    }

    @Override
    public void showDdl(@NotNull String objectName, @NotNull String ddl) {
        DdlViewerPanel viewer = new DdlViewerPanel(objectName, ddl);
        viewer.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(viewer.getTitle(), UiIcons.get("file-sql"), viewer); //$NON-NLS-1$
        int index = documentTabs.indexOfComponent(viewer);
        documentTabs.setToolTipTextAt(index, viewer.getTitle());
        documentTabs.setSelectedIndex(index);
    }

    /**
     * Adds a query window tab.
     *
     * @param dataSource connection of the window, or null (chosen in its tool bar)
     * @param fileName   name of the window, or null for the next {@code SQLQueryN.sql}
     */
    @NotNull
    private SqlEditorPanel openQueryEditor(@Nullable DBPDataSourceContainer dataSource, @Nullable String fileName) {
        if (fileName == null) {
            queryCounter++;
            fileName = "SQLQuery" + queryCounter + ".sql"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        SqlEditorPanel editor = new SqlEditorPanel(dataSource, fileName, this);
        editor.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(editor.getTitle(), editor.getTabIcon(), editor);
        int index = documentTabs.indexOfComponent(editor);
        documentTabs.setToolTipTextAt(index, editor.getToolTip());
        documentTabs.setSelectedIndex(index);
        SwingUtilities.invokeLater(editor::focusEditor);
        return editor;
    }

    private void closeDocumentTab(int index) {
        Component component = documentTabs.getComponentAt(index);
        if (component instanceof SqlEditorPanel editor) {
            if (!confirmClose(editor)) {
                return;
            }
            editor.dispose();
            documentTabs.remove(editor);
        } else if (component instanceof DdlViewerPanel) {
            documentTabs.removeTabAt(index);
        }
    }

    @NotNull
    private List<SqlEditorPanel> getEditors() {
        List<SqlEditorPanel> editors = new ArrayList<>();
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof SqlEditorPanel editor) {
                editors.add(editor);
            }
        }
        return editors;
    }

    private void connectionUpdated(@NotNull DBPDataSourceContainer container) {
        for (SqlEditorPanel editor : getEditors()) {
            if (editor.getDataSourceContainer() == container) {
                editor.connectionChanged();
            }
        }
    }

    //////////////////////////////////////////////////////////
    // SQL files

    private void openFile() {
        JFileChooser chooser = createSqlFileChooser("Open File");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    /**
     * Opens a SQL file in a new query window, or shows the window that has it open already.
     */
    private void openFile(@NotNull Path path) {
        Path file = path.toAbsolutePath().normalize();
        lastDirectory = file.getParent();
        for (SqlEditorPanel editor : getEditors()) {
            if (file.equals(editor.getFile())) {
                documentTabs.setSelectedComponent(editor);
                RecentFiles.add(file);
                return;
            }
        }
        if (!Files.isRegularFile(file)) {
            RecentFiles.remove(file);
            JOptionPane.showMessageDialog(this, "The file was not found:\n" + file, "Open File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        SqlFileContent content;
        try {
            content = SqlFileContent.read(file);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError("Open File", "Can't open " + file, e);
            return;
        }
        DBPDataSourceContainer dataSource = explorerPanel.getSelectedDataSource();
        if (dataSource == null) {
            SqlEditorPanel activeEditor = getActiveEditor();
            dataSource = activeEditor != null ? activeEditor.getDataSourceContainer() : null;
        }
        SqlEditorPanel editor = openQueryEditor(dataSource, file.getFileName().toString());
        editor.setContent(content, file);
        RecentFiles.add(file);
    }

    /**
     * Saves a query window to its file, or asks for a file (Save As, or never saved).
     *
     * @return false if it was not saved (canceled or failed)
     */
    private boolean saveEditor(@NotNull SqlEditorPanel editor, boolean saveAs) {
        Path file = editor.getFile();
        if (saveAs || file == null) {
            JFileChooser chooser = createSqlFileChooser(saveAs ? "Save As" : "Save");
            File directory = file != null ? file.getParent().toFile() : chooser.getCurrentDirectory();
            chooser.setSelectedFile(new File(directory, editor.getFileName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (!selected.getFileName().toString().contains(".")) {
                selected = selected.resolveSibling(selected.getFileName() + ".sql"); //$NON-NLS-1$
            }
            if (!selected.equals(file) && Files.exists(selected) && JOptionPane.showConfirmDialog(this,
                selected.getFileName() + " already exists.\nDo you want to replace it?", "Save As",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return false;
            }
            file = selected;
        }
        SqlFileContent content = editor.getContent();
        try {
            content.write(file);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError("Save", "Can't save " + file, e);
            return false;
        }
        editor.saved(file, content);
        lastDirectory = file.getParent();
        RecentFiles.add(file);
        return true;
    }

    @NotNull
    private JFileChooser createSqlFileChooser(@NotNull String title) {
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory.toFile() : null);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("SQL files (*.sql)", "sql")); //$NON-NLS-1$
        return chooser;
    }

    /**
     * Asks to save a modified query window before it is closed.
     *
     * @return true if the window may be closed
     */
    private boolean confirmClose(@NotNull SqlEditorPanel editor) {
        if (!confirmResultChanges(editor)) {
            return false;
        }
        if (!editor.isDirty()) {
            return true;
        }
        documentTabs.setSelectedComponent(editor);
        return switch (askSave("Save changes to " + editor.getFileName() + "?")) {
            case 0 -> saveEditor(editor, false);
            case 1 -> true;
            default -> false;
        };
    }

    /**
     * Asks to save the unsaved changes in a query window's result grids, like its text (Save / Don't Save / Cancel).
     *
     * @return true if the window may be closed
     */
    private boolean confirmResultChanges(@NotNull SqlEditorPanel editor) {
        if (!checkNotSavingResults(editor)) {
            return false;
        }
        if (!editor.hasPendingResultChanges()) {
            return true;
        }
        documentTabs.setSelectedComponent(editor);
        return switch (askSave("Save changes to the results of " + editor.getFileName() + "?\n\n" + describeResultChanges(editor))) {
            case 0 -> editor.saveResultChanges();
            case 1 -> true;
            default -> false;
        };
    }

    /**
     * A query window whose results are being saved stays open until the save is over.
     */
    private boolean checkNotSavingResults(@NotNull SqlEditorPanel editor) {
        if (!editor.isSavingResults()) {
            return true;
        }
        documentTabs.setSelectedComponent(editor);
        JOptionPane.showMessageDialog(this, "The results of " + editor.getFileName() + " are being saved.\nTry again when the save is over.",
            GeneralUtils.getProductName(), JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    @NotNull
    private static String describeResultChanges(@NotNull SqlEditorPanel editor) {
        int changes = editor.getPendingResultChangeCount();
        return "Results of " + editor.getFileName() + " (" + changes + (changes == 1 ? " unsaved change)" : " unsaved changes)");
    }

    /**
     * Window closing and File > Exit: asks to save the modified query windows and the unsaved changes of their
     * result grids (SSMS lists them in one question), then closes the window.
     */
    private void requestExit() {
        List<SqlEditorPanel> modified = new ArrayList<>();
        List<SqlEditorPanel> resultsModified = new ArrayList<>();
        for (SqlEditorPanel editor : getEditors()) {
            if (!checkNotSavingResults(editor)) {
                return;
            }
            if (editor.isDirty()) {
                modified.add(editor);
            }
            if (editor.hasPendingResultChanges()) {
                resultsModified.add(editor);
            }
        }
        if (!modified.isEmpty() || !resultsModified.isEmpty()) {
            StringBuilder message = new StringBuilder("Save changes to the following items?\n");
            for (SqlEditorPanel editor : modified) {
                message.append('\n').append(editor.getFile() != null ? editor.getFile().toString() : editor.getFileName());
            }
            for (SqlEditorPanel editor : resultsModified) {
                message.append('\n').append(describeResultChanges(editor));
            }
            int answer = askSave(message.toString());
            if (answer == 0) {
                for (SqlEditorPanel editor : resultsModified) {
                    documentTabs.setSelectedComponent(editor);
                    if (!editor.saveResultChanges()) {
                        return;
                    }
                }
                for (SqlEditorPanel editor : modified) {
                    documentTabs.setSelectedComponent(editor);
                    if (!saveEditor(editor, false)) {
                        return;
                    }
                }
            } else if (answer != 1) {
                return;
            }
        }
        dispose();
    }

    /**
     * @return 0 Save, 1 Don't Save, anything else Cancel
     */
    private int askSave(@NotNull String message) {
        String[] options = {"Save", "Don't Save", "Cancel"};
        return JOptionPane.showOptionDialog(this, message, GeneralUtils.getProductName(), JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE, null, options, options[0]);
    }

    /**
     * File > Recent Files, rebuilt each time the File menu opens.
     */
    private void fillRecentFilesMenu(@NotNull JMenu recentMenu) {
        recentMenu.removeAll();
        List<Path> files = RecentFiles.get();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            JMenuItem item = new JMenuItem((i + 1) + " " + file);
            if (i < 9) {
                item.setMnemonic(KeyEvent.VK_1 + i);
            }
            item.addActionListener(e -> openFile(file));
            recentMenu.add(item);
        }
        recentMenu.setEnabled(!files.isEmpty());
    }

    @Nullable
    private SqlEditorPanel getActiveEditor() {
        return documentTabs.getSelectedComponent() instanceof SqlEditorPanel editor ? editor : null;
    }

    /**
     * Text of the active document tab (query window or DDL tab), for Edit &gt; Find / Replace.
     */
    @Nullable
    private SqlEditorPanel.SqlTextArea getActiveTextArea() {
        Component component = documentTabs.getSelectedComponent();
        if (component instanceof SqlEditorPanel editor) {
            return editor.getTextArea();
        }
        return component instanceof DdlViewerPanel viewer ? viewer.getTextArea() : null;
    }

    private void activeEditorChanged() {
        SqlEditorPanel editor = getActiveEditor();
        switchingResults = true;
        try {
            attachResultTab(RESULTS_TAB, editor != null ? editor.getResultsView().getResultsComponent() : resultsPlaceholder);
            attachResultTab(MESSAGES_TAB, editor != null ? editor.getResultsView().getMessagesComponent() : messagesPlaceholder);
            resultTabs.setSelectedIndex(editor != null && editor.getResultsView().isMessagesTabSelected() ? MESSAGES_TAB : RESULTS_TAB);
        } finally {
            switchingResults = false;
        }
        attachStatusLine(editor != null ? editor.getStatusLine() : null);
        updateQueryActions();
    }

    /**
     * Puts an editor's results component into the Results / Messages tab. Components of inactive editors are not in
     * the window and miss theme switches, so their look and feel is brought up to date here (also for the status line).
     */
    private void attachResultTab(int index, @NotNull JComponent component) {
        if (resultTabs.getComponentAt(index) == component) {
            return;
        }
        updateLookAndFeel(component);
        resultTabs.setComponentAt(index, component);
    }

    private static void updateLookAndFeel(@NotNull JComponent component) {
        LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        if (component.getClientProperty(UPDATED_LAF_KEY) != lookAndFeel) {
            SwingUtilities.updateComponentTreeUI(component);
            component.putClientProperty(UPDATED_LAF_KEY, lookAndFeel);
        }
    }

    /**
     * Shows the active editor's query status line at the bottom of the query window; other documents (Welcome, DDL)
     * have none.
     */
    private void attachStatusLine(@Nullable JComponent statusLine) {
        if (statusLine == attachedStatusLine) {
            return;
        }
        attachedStatusLine = statusLine;
        if (statusLine != null) {
            updateLookAndFeel(statusLine);
        }
        placeStatusLine();
    }

    /**
     * Puts the status line below the results (SSMS), or below the editor while the Results card is hidden.
     */
    private void placeStatusLine() {
        JPanel target = resultsWindow != null && resultsWindow.isShown() ? resultsStatusHolder : documentStatusHolder;
        for (JPanel holder : List.of(resultsStatusHolder, documentStatusHolder)) {
            boolean show = holder == target && attachedStatusLine != null;
            if (show && (holder.getComponentCount() == 0 || holder.getComponent(0) != attachedStatusLine)) {
                holder.removeAll();
                holder.add(attachedStatusLine, BorderLayout.CENTER);
            } else if (!show) {
                holder.removeAll();
            }
            holder.setVisible(show);
            holder.revalidate();
            holder.repaint();
        }
    }

    private void updateQueryActions() {
        SqlEditorPanel editor = getActiveEditor();
        executeQueryAction.setEnabled(editor != null && !editor.isExecuting());
        cancelQueryAction.setEnabled(editor != null && editor.isExecuting());
        completeAction.setEnabled(editor != null);
        saveAction.setEnabled(editor != null);
        saveAsAction.setEnabled(editor != null);
        editMenu.activeDocumentChanged();
    }

    @Override
    public void showResults(@NotNull SqlEditorPanel editor, boolean messages) {
        if (editor != getActiveEditor()) {
            return;
        }
        editor.getResultsView().setMessagesTabSelected(messages);
        resultTabs.setSelectedIndex(messages ? MESSAGES_TAB : RESULTS_TAB);
        resultsWindow.setShown(true);
    }

    @Override
    public void executionStateChanged(@NotNull SqlEditorPanel editor) {
        if (editor == getActiveEditor()) {
            updateQueryActions();
        }
    }

    @Override
    public void titleChanged(@NotNull SqlEditorPanel editor) {
        int index = documentTabs.indexOfComponent(editor);
        if (index >= 0) {
            documentTabs.setTitleAt(index, editor.getTitle());
            documentTabs.setToolTipTextAt(index, editor.getToolTip());
            documentTabs.setIconAt(index, editor.getTabIcon());
        }
    }

    @NotNull
    private static JComponent createPlaceholder(@NotNull String text) {
        JLabel placeholder = new JLabel(text, SwingConstants.CENTER);
        placeholder.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        return placeholder;
    }

    /**
     * Center document area: the welcome tab and the SQL editor tabs (closable).
     */
    @NotNull
    private JTabbedPane createDocumentTabs() {
        JLabel titleLabel = new JLabel(GeneralUtils.getProductName());
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1"); //$NON-NLS-1$
        JLabel hintLabel = new JLabel("Expand a connection in Object Explorer to browse its objects.");
        JLabel nextLabel = new JLabel("Right-click a connection and choose New Query (Ctrl+N) to open a SQL editor.");
        nextLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        JPanel welcome = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.insets = new Insets(4, 0, 4, 0);
        Icon logo = ChromiteIcons.getLogoIcon(128);
        if (logo != null) {
            welcome.add(new JLabel(logo), constraints);
        }
        welcome.add(titleLabel, constraints);
        welcome.add(hintLabel, constraints);
        welcome.add(nextLabel, constraints);

        JTabbedPane tabs = new EditorTabbedPane();
        // One row that scrolls, like SSMS; wrapped rows would move the selected tab's row around
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // DataGrip editor tabs: size.tab.editor 40; the selected tab is an Islands pill (IslandTabbedPaneUI), no underline
        tabs.putClientProperty(FlatClientProperties.STYLE, "tabHeight: 40; tabSelectionHeight: 0"); //$NON-NLS-1$
        tabs.addTab("Welcome", welcome);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, (IntConsumer) this::closeDocumentTab);
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                // Middle click closes a tab, as in DataGrip and SSMS
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    int index = tabs.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0 && tabs.getComponentAt(index) instanceof JComponent component
                        && Boolean.TRUE.equals(component.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE))) {
                        closeDocumentTab(index);
                    }
                }
            }
        });
        return tabs;
    }

    @NotNull
    private JMenuBar createMenuBar() {
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> requestExit());

        JMenu recentMenu = new JMenu("Recent Files");
        recentMenu.setMnemonic(KeyEvent.VK_R);
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(new JMenuItem(explorerPanel.getNewConnectionAction()));
        fileMenu.add(new JMenuItem(newQueryAction));
        fileMenu.addSeparator();
        fileMenu.add(new JMenuItem(openFileAction));
        fileMenu.add(recentMenu);
        fileMenu.addSeparator();
        fileMenu.add(new JMenuItem(saveAction));
        fileMenu.add(new JMenuItem(saveAsAction));
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        onMenuSelected(fileMenu, () -> fillRecentFilesMenu(recentMenu));
        fillRecentFilesMenu(recentMenu);

        JMenu queryMenu = new JMenu("Query");
        queryMenu.setMnemonic(KeyEvent.VK_Q);
        queryMenu.add(new JMenuItem(executeQueryAction));
        queryMenu.add(new JMenuItem(cancelQueryAction));
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(completeAction));
        JCheckBoxMenuItem autoCompleteItem = new JCheckBoxMenuItem("Auto-Complete While Typing", SqlCompletionPopup.isAutoActivateOnLetters());
        autoCompleteItem.setToolTipText("Show completion proposals after typing letters (they always appear after a period)");
        autoCompleteItem.addActionListener(e -> SqlCompletionPopup.setAutoActivateOnLetters(autoCompleteItem.isSelected()));
        queryMenu.add(autoCompleteItem);
        // Tools > Options changes the same setting
        onMenuSelected(queryMenu, () -> autoCompleteItem.setSelected(SqlCompletionPopup.isAutoActivateOnLetters()));

        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);
        for (ToolWindow toolWindow : toolWindows) {
            viewMenu.add(new JCheckBoxMenuItem(toolWindow.getToggleAction()));
        }
        viewMenu.addSeparator();
        viewMenu.add(createThemeMenu());

        JMenuItem aboutItem = new JMenuItem("About " + GeneralUtils.getProductName());
        aboutItem.setMnemonic(KeyEvent.VK_A);
        aboutItem.addActionListener(e -> AboutDialog.open(this));

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);
        helpMenu.add(aboutItem);

        JMenuItem optionsItem = new JMenuItem("Options…");
        optionsItem.setMnemonic(KeyEvent.VK_O);
        optionsItem.addActionListener(e -> OptionsDialog.open(this));
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.setMnemonic(KeyEvent.VK_T);
        toolsMenu.add(optionsItem);

        JMenu windowMenu = new JMenu("Window");
        windowMenu.setMnemonic(KeyEvent.VK_W);
        onMenuSelected(windowMenu, () -> fillWindowMenu(windowMenu));
        fillWindowMenu(windowMenu);

        // SSMS order
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        menuBar.add(editMenu.getMenu());
        menuBar.add(viewMenu);
        menuBar.add(queryMenu);
        menuBar.add(toolsMenu);
        menuBar.add(windowMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    /**
     * Window menu (SSMS): Reset Window Layout, then the open document tabs (the active one checked), rebuilt each
     * time the menu opens.
     */
    private void fillWindowMenu(@NotNull JMenu windowMenu) {
        windowMenu.removeAll();
        JMenuItem resetItem = new JMenuItem("Reset Window Layout");
        resetItem.setMnemonic(KeyEvent.VK_R);
        resetItem.addActionListener(e -> resetWindowLayout());
        windowMenu.add(resetItem);
        if (documentTabs.getTabCount() > 0) {
            windowMenu.addSeparator();
        }
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            int index = i;
            // No tab icon: the radio mark shows the active tab
            JRadioButtonMenuItem item = new JRadioButtonMenuItem((i + 1) + " " + documentTabs.getTitleAt(i), i == documentTabs.getSelectedIndex());
            if (i < 9) {
                item.setMnemonic(KeyEvent.VK_1 + i);
            }
            item.setToolTipText(documentTabs.getToolTipTextAt(i));
            item.addActionListener(e -> {
                if (index < documentTabs.getTabCount()) {
                    documentTabs.setSelectedIndex(index);
                }
            });
            windowMenu.add(item);
        }
    }

    /**
     * Window &gt; Reset Window Layout: every tool window shown again with its default size.
     */
    private void resetWindowLayout() {
        // Outer split first: the inner split panes are sized from its new layout
        for (ToolWindow toolWindow : toolWindows) {
            toolWindow.reset();
        }
    }

    private static void onMenuSelected(@NotNull JMenu menu, @NotNull Runnable task) {
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                task.run();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
    }

    @NotNull
    private static JMenu createThemeMenu() {
        JMenu themeMenu = new JMenu("Theme");
        themeMenu.setMnemonic(KeyEvent.VK_T);
        ButtonGroup group = new ButtonGroup();
        ChromiteTheme current = ChromiteTheme.getCurrent();
        List<JRadioButtonMenuItem> themeItems = new ArrayList<>();
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(theme.getLabel(), theme == current);
            item.setMnemonic(theme.getLabel().charAt(0));
            item.addActionListener(e -> {
                if (ChromiteTheme.getCurrent() != theme) {
                    theme.apply();
                }
            });
            group.add(item);
            themeMenu.add(item);
            themeItems.add(item);
        }
        themeMenu.addSeparator();
        JMenu iconStyleMenu = new JMenu("Icon Style");
        iconStyleMenu.setMnemonic(KeyEvent.VK_I);
        ButtonGroup iconStyleGroup = new ButtonGroup();
        UiIconStyle currentIconStyle = UiIconStyle.getCurrent();
        for (UiIconStyle iconStyle : UiIconStyle.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(iconStyle.getLabel(), iconStyle == currentIconStyle);
            item.addActionListener(e -> {
                if (UiIconStyle.getCurrent() != iconStyle) {
                    iconStyle.apply();
                }
            });
            iconStyleGroup.add(item);
            iconStyleMenu.add(item);
        }
        themeMenu.add(iconStyleMenu);
        // Tools > Options changes the same settings
        onMenuSelected(themeMenu, () -> {
            ChromiteTheme theme = ChromiteTheme.getCurrent();
            UiIconStyle iconStyle = UiIconStyle.getCurrent();
            for (int i = 0; i < themeItems.size(); i++) {
                themeItems.get(i).setSelected(ChromiteTheme.values()[i] == theme);
            }
            for (int i = 0; i < iconStyleMenu.getItemCount(); i++) {
                iconStyleMenu.getItem(i).setSelected(UiIconStyle.values()[i] == iconStyle);
            }
        });
        return themeMenu;
    }

    private void rememberNormalBounds() {
        if (isShowing() && (getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            normalBounds = getBounds();
        }
    }

    /**
     * Restores the window bounds and the tool windows from the preferences. Saved bounds that are no longer
     * on any screen (e.g. a disconnected monitor) are ignored.
     */
    private void restoreLayout() {
        DBPPreferenceStore preferences = getPreferences();
        Rectangle bounds = parseBounds(preferences.getString(PREF_BOUNDS));
        if (bounds != null && isOnScreen(bounds)) {
            setBounds(bounds);
        } else {
            setSize(DEFAULT_SIZE);
            setLocationRelativeTo(null);
        }
        normalBounds = getBounds();
        if (preferences.getBoolean(PREF_MAXIMIZED)) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
        for (ToolWindow toolWindow : toolWindows) {
            toolWindow.load(preferences);
        }
    }

    private void saveLayout() {
        DBPPreferenceStore preferences = getPreferences();
        Rectangle bounds = normalBounds != null ? normalBounds : getBounds();
        preferences.setValue(PREF_BOUNDS, bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        preferences.setValue(PREF_MAXIMIZED, (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0);
        for (ToolWindow toolWindow : toolWindows) {
            toolWindow.save(preferences);
        }
        PrefUtils.savePreferenceStore(preferences);
    }

    @Nullable
    private static Rectangle parseBounds(@Nullable String value) {
        if (CommonUtils.isEmpty(value)) {
            return null;
        }
        String[] parts = value.split(","); //$NON-NLS-1$
        if (parts.length != 4) {
            return null;
        }
        try {
            Rectangle bounds = new Rectangle(
                Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
            return bounds.width >= 400 && bounds.height >= 300 ? bounds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOnScreen(@NotNull Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(bounds);
            if (visible.width >= 100 && visible.height >= 50) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Document tabs whose tab row has the editor background (bg.editor), like the DataGrip editor tabs.
     * <ul>
     *     <li>The background is set after every look and feel change as a plain (non-UIResource) color: the scrolling
     *     tab row copies it only from a background change event, which a FlatLaf style value does not always fire when
     *     the theme is switched at runtime (the tabs then kept the panel color).</li>
     *     <li>Opaque, because FlatLaf tabbed panes are not: the empty part of the tab row would show the parent's panel color.</li>
     *     <li>The UI is {@link IslandTabbedPaneUI} (Islands pill tabs, close buttons of the selected and the hovered tab only).</li>
     * </ul>
     */
    private static final class EditorTabbedPane extends JTabbedPane {
        EditorTabbedPane() {
            setOpaque(true);
        }

        @Override
        public void updateUI() {
            setUI(new IslandTabbedPaneUI());
            Color background = UIManager.getColor("Chromite.editorBackground"); //$NON-NLS-1$
            if (background != null) {
                setBackground(new Color(background.getRGB()));
            }
        }
    }

    /**
     * Islands editor tabs (EditorTabs.* of ManyIslands*.theme.json): the selected tab is a rounded pill with a 1px
     * border (Chromite.island.tabSelected*, or tabInactive* when the tabs don't have the focus), 6px below and above the
     * tab row edges (tabInsets -6); a hovered tab gets a pill of tab-bg-hovered. No underline.
     * The pill is painted as the tab border, which FlatLaf paints before the close button and the title.
     */
    private static final class IslandTabbedPaneUI extends FlatTabbedPaneUI {
        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            // The tab row background is the tabbed pane's own (opaque) background; pills are painted in paintTabBorder
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            boolean focused = isTabbedPaneOrChildFocused();
            Color fill;
            Color border = null;
            if (isSelected) {
                fill = UIManager.getColor(focused ? "Chromite.island.tabSelectedBackground" : "Chromite.island.tabInactiveBackground"); //$NON-NLS-1$ //$NON-NLS-2$
                border = UIManager.getColor(focused ? "Chromite.island.tabSelectedBorderColor" : "Chromite.island.tabInactiveBorderColor"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (tabIndex == getRolloverTab()) {
                fill = UIManager.getColor("Chromite.island.tabHoverBackground"); //$NON-NLS-1$
            } else {
                return;
            }
            int arc = UIScale.scale(UIManager.getInt("Chromite.island.tabArc")); //$NON-NLS-1$
            int insetY = UIScale.scale(6);
            int insetX = UIScale.scale(2);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float px = x + insetX;
                float py = y + insetY;
                float pw = w - 2 * insetX;
                float ph = h - 2 * insetY;
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fill(new RoundRectangle2D.Float(px, py, pw, ph, arc, arc));
                }
                if (border != null) {
                    g2.setColor(border);
                    g2.draw(new RoundRectangle2D.Float(px + 0.5f, py + 0.5f, pw - 1, ph - 1, arc, arc));
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintTabSelection(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h) {
            // No underline in Islands: the pill shows the selection
        }

        /**
         * DataGrip: the close button (×) shows on the selected tab and on the tab under the mouse only. Its space stays
         * reserved on every closable tab, so the tabs don't change width when the mouse moves over them. A click on a
         * hidden button can't happen: the tab under the mouse is the hovered one, whose button is shown.
         */
        @Override
        protected void paintTabCloseButton(Graphics g, int tabIndex, int x, int y, int w, int h) {
            if (tabIndex == tabPane.getSelectedIndex() || tabIndex == getRolloverTab()) {
                super.paintTabCloseButton(g, tabIndex, x, y, w, h);
            }
        }
    }

    /**
     * Islands canvas (main-window-bg): the background around the cards, under the main tool bar and the status bar.
     */
    private static class CanvasPanel extends JPanel {
        CanvasPanel(@NotNull LayoutManager layout) {
            super(layout);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
        }
    }

    /**
     * Split pane between islands: its divider is the 6px canvas gap between two cards (size.divider, Island.borderWidth),
     * draggable but without a line or FlatLaf's grip dots ({@code SplitPaneDivider.style = plain} paints nothing).
     * The split pane paints the canvas color, so the gap shows the canvas; set again on every look and feel change.
     */
    private static final class LineSplitPane extends JSplitPane {
        LineSplitPane(int orientation) {
            super(orientation);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
            if (getUI() instanceof BasicSplitPaneUI splitPaneUI && splitPaneUI.getDivider() != null) {
                splitPaneUI.getDivider().setBorder(BorderFactory.createEmptyBorder());
            }
        }
    }

    /**
     * Status bar (size.statusBar 28) on the Islands canvas, without a separator line (StatusBar.background =
     * main-window-bg, topBorderWidth 0); the background is set again on theme changes.
     */
    private static final class StatusBar extends JPanel {
        private static final int HEIGHT = 28;
        private final JLabel statusLabel;

        StatusBar(@NotNull String statusText) {
            super(new BorderLayout());
            statusLabel = new JLabel(statusText);
            statusLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, UIScale.scale(12)));
            statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            add(statusLabel, BorderLayout.WEST);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, UIScale.scale(HEIGHT));
        }

        void setText(@NotNull String text) {
            statusLabel.setText(text);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
        }
    }
}
