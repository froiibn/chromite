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
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
 *     <li>메뉴는 File(Exit), View(도구 창 토글, Theme), Help(About)이다. FlatLaf의 통합 제목 표시줄(Windows)에서
 *     메뉴 막대는 A단계처럼 제목 표시줄 안에 들어간다.
 *     도구 창 단축키는 SSMS와 같다(Object Explorer F8, Properties F4, Results Ctrl+R).</li>
 *     <li>문서 탭: 시작 탭과 SQL 편집기 탭({@link SqlEditorPanel}). 편집기는 Object Explorer의 "New Query", File > New Query(Ctrl+N),
 *     도구 막대의 New Query로 연다. 제목은 SSMS처럼 {@code SQLQueryN.sql - 연결 이름}이고 닫기 단추가 있다.</li>
 *     <li>Query 메뉴의 Execute(F5)와 Cancel Executing Query(Alt+Break)는 활성 편집기에 적용된다. 메뉴 단축키라 포커스가 결과 표에 있어도 동작한다.</li>
 *     <li>결과는 SSMS처럼 쿼리 창마다 따로 있다({@link QueryResultsView}). 아래 Results / Messages 탭에는 활성 편집기의 결과를 끼우고,
 *     편집기가 없으면 자리 표시 내용을 보여 준다. 편집기마다 마지막으로 본 탭(Results 또는 Messages)을 기억한다. 탭에서 빠져 있던 결과 컴포넌트는 테마 전환을 받지 못했을 수 있어, 끼울 때 Look and Feel을 맞춘다.</li>
 *     <li>File > New Connection과 도구 막대의 New Connection은 Object Explorer의 같은 명령으로 연결 대화상자({@link ConnectionDialog})를 연다.</li>
 *     <li>상태 표시줄의 연결 수는 모든 프로젝트의 연결 레지스트리 이벤트({@link DBPEventListener}, 연결 추가·삭제)를 받아 다시 센다.</li>
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
 */
final class MainFrame extends JFrame implements SqlEditorPanel.Host {

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
        }
    };
    private final JTabbedPane documentTabs;
    private final JTabbedPane resultTabs;
    private final JComponent resultsPlaceholder;
    private final JComponent messagesPlaceholder;
    private final ToolWindow resultsWindow;
    private final Action newQueryAction;
    private final Action executeQueryAction;
    private final Action cancelQueryAction;
    private int queryCounter;
    private boolean switchingResults;
    // Outer split first: inner split panes are sized from the outer layout
    private final List<ToolWindow> toolWindows;
    @Nullable
    private Rectangle normalBounds;
    private boolean layoutSaved;

    MainFrame(@NotNull String title, @NotNull String statusText) {
        super(title);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        PropertiesPanel propertiesPanel = new PropertiesPanel();
        explorerPanel = new ObjectExplorerPanel(propertiesPanel::showNode, this::openQueryEditor);
        resultsPlaceholder = createPlaceholder("No results. Query results will appear here.");
        messagesPlaceholder = createPlaceholder("No messages.");
        resultTabs = new JTabbedPane();
        resultTabs.addTab("Results", resultsPlaceholder);
        resultTabs.addTab("Messages", messagesPlaceholder);
        documentTabs = createDocumentTabs();

        JSplitPane documentSplit = createSplitPane(JSplitPane.VERTICAL_SPLIT);
        documentSplit.setTopComponent(documentTabs);
        JSplitPane propertiesSplit = createSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        propertiesSplit.setLeftComponent(documentSplit);
        JSplitPane explorerSplit = createSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        explorerSplit.setRightComponent(propertiesSplit);

        ToolWindow explorerWindow = new ToolWindow("objectExplorer", "Object Explorer", true, //$NON-NLS-1$
            explorerPanel, explorerPanel.createRefreshButton(), explorerSplit, true, 300,
            KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
        ToolWindow propertiesWindow = new ToolWindow("properties", "Properties", true, //$NON-NLS-1$
            propertiesPanel, null, propertiesSplit, false, 300,
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        // The Results/Messages tabs are their own header; the hide button sits next to the tabs
        resultsWindow = new ToolWindow("results", "Results", false, //$NON-NLS-1$
            resultTabs, null, documentSplit, false, 220,
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        // The trailing component fills the rest of the tab row; keep the button at its right end
        JPanel resultTabsTrailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        resultTabsTrailing.setOpaque(false);
        resultTabsTrailing.add(resultsWindow.createHideButton());
        resultTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, resultTabsTrailing);
        toolWindows = List.of(explorerWindow, propertiesWindow, resultsWindow);

        newQueryAction = createAction("New Query", "Open a SQL editor for the connection selected in Object Explorer (Ctrl+N)",
            KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), this::newQuery);
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
        documentTabs.addChangeListener(e -> activeEditorChanged());
        resultTabs.addChangeListener(e -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null && !switchingResults) {
                editor.getResultsView().setMessagesTabSelected(resultTabs.getSelectedIndex() == MESSAGES_TAB);
            }
        });
        updateQueryActions();

        setJMenuBar(createMenuBar());

        JPanel content = new JPanel(new BorderLayout());
        content.add(createToolBar(), BorderLayout.NORTH);
        content.add(explorerSplit, BorderLayout.CENTER);
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
        JSplitPane splitPane = new JSplitPane(orientation);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable String description, @NotNull KeyStroke accelerator, @NotNull Runnable task) {
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

    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        for (Action action : List.of(explorerPanel.getNewConnectionAction(), newQueryAction)) {
            JButton button = new JButton(action);
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setFocusable(false);
            toolBar.add(button);
        }
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
        openQueryEditor(dataSource);
    }

    private void openQueryEditor(@NotNull DBPDataSourceContainer dataSource) {
        queryCounter++;
        SqlEditorPanel editor = new SqlEditorPanel(dataSource, "SQLQuery" + queryCounter + ".sql", this); //$NON-NLS-1$ //$NON-NLS-2$
        editor.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(editor.getTitle(), editor);
        int index = documentTabs.indexOfComponent(editor);
        documentTabs.setToolTipTextAt(index, editor.getTitle());
        documentTabs.setSelectedIndex(index);
        SwingUtilities.invokeLater(editor::focusEditor);
    }

    private void closeDocumentTab(int index) {
        if (documentTabs.getComponentAt(index) instanceof SqlEditorPanel editor) {
            editor.dispose();
            documentTabs.removeTabAt(index);
        }
    }

    @Nullable
    private SqlEditorPanel getActiveEditor() {
        return documentTabs.getSelectedComponent() instanceof SqlEditorPanel editor ? editor : null;
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
        updateQueryActions();
    }

    /**
     * Puts an editor's results component into the Results / Messages tab. Components of inactive editors are not in
     * the window and miss theme switches, so their look and feel is brought up to date here.
     */
    private void attachResultTab(int index, @NotNull JComponent component) {
        if (resultTabs.getComponentAt(index) == component) {
            return;
        }
        LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        if (component.getClientProperty(UPDATED_LAF_KEY) != lookAndFeel) {
            SwingUtilities.updateComponentTreeUI(component);
            component.putClientProperty(UPDATED_LAF_KEY, lookAndFeel);
        }
        resultTabs.setComponentAt(index, component);
    }

    private void updateQueryActions() {
        SqlEditorPanel editor = getActiveEditor();
        executeQueryAction.setEnabled(editor != null && !editor.isExecuting());
        cancelQueryAction.setEnabled(editor != null && editor.isExecuting());
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

        JTabbedPane tabs = new JTabbedPane();
        // One row that scrolls, like SSMS; wrapped rows would move the selected tab's row around
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Welcome", welcome);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, (IntConsumer) this::closeDocumentTab);
        return tabs;
    }

    @NotNull
    private JMenuBar createMenuBar() {
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> dispose());

        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(new JMenuItem(explorerPanel.getNewConnectionAction()));
        fileMenu.add(new JMenuItem(newQueryAction));
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenu queryMenu = new JMenu("Query");
        queryMenu.setMnemonic(KeyEvent.VK_Q);
        queryMenu.add(new JMenuItem(executeQueryAction));
        queryMenu.add(new JMenuItem(cancelQueryAction));

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

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(queryMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    @NotNull
    private static JMenu createThemeMenu() {
        JMenu themeMenu = new JMenu("Theme");
        themeMenu.setMnemonic(KeyEvent.VK_T);
        ButtonGroup group = new ButtonGroup();
        ChromiteTheme current = ChromiteTheme.getCurrent();
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
        }
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
     * Status bar with a top separator line; the border is recreated on theme changes, because its color comes
     * from the look and feel.
     */
    private static final class StatusBar extends JPanel {
        private final JLabel statusLabel;

        StatusBar(@NotNull String statusText) {
            super(new BorderLayout());
            statusLabel = new JLabel(statusText);
            statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            add(statusLabel, BorderLayout.WEST);
        }

        void setText(@NotNull String text) {
            statusLabel.setText(text);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color separatorColor = UIManager.getColor("Separator.foreground"); //$NON-NLS-1$
            setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separatorColor != null ? separatorColor : Color.GRAY));
        }
    }
}
