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
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

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
 *     메뉴 막대는 A단계처럼 제목 표시줄 안에 들어간다. F5는 쿼리 실행용으로 남겨 두고 쓰지 않는다.
 *     도구 창 단축키는 SSMS와 같다(Object Explorer F8, Properties F4, Results Ctrl+R).</li>
 *     <li>문서 탭에는 지금은 시작 탭만 있다. SQL 편집기는 다음 단계(C단계)에서 넣는다.</li>
 * </ul>
 * 창을 닫으면 dispose되고, {@link ChromiteSwingApplication}이 windowClosed 이벤트로 앱을 종료한다.
 * 창 아이콘은 Java 런타임(JBR) 기본 아이콘 대신 Chromite 아이콘을 쓴다({@link ChromiteIcons}).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-06 B단계: SSMS식 배치(Object Explorer, 문서 탭, Results/Messages, Properties), View/Help 메뉴, 테마 전환,
 *   창·도구 창 배치 저장, 알림 표시 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 */
final class MainFrame extends JFrame {

    private static final String PREF_BOUNDS = "mainWindow.bounds"; //$NON-NLS-1$
    private static final String PREF_MAXIMIZED = "mainWindow.maximized"; //$NON-NLS-1$
    private static final Dimension DEFAULT_SIZE = new Dimension(1280, 800);

    private final ObjectExplorerPanel explorerPanel;
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
        explorerPanel = new ObjectExplorerPanel(propertiesPanel::showNode);
        JTabbedPane resultTabs = createResultTabs();

        JSplitPane documentSplit = createSplitPane(JSplitPane.VERTICAL_SPLIT);
        documentSplit.setTopComponent(createDocumentTabs());
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
        ToolWindow resultsWindow = new ToolWindow("results", "Results", false, //$NON-NLS-1$
            resultTabs, null, documentSplit, false, 220,
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        // The trailing component fills the rest of the tab row; keep the button at its right end
        JPanel resultTabsTrailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        resultTabsTrailing.setOpaque(false);
        resultTabsTrailing.add(resultsWindow.createHideButton());
        resultTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, resultTabsTrailing);
        toolWindows = List.of(explorerWindow, propertiesWindow, resultsWindow);

        setJMenuBar(createMenuBar());

        JPanel content = new JPanel(new BorderLayout());
        content.add(explorerSplit, BorderLayout.CENTER);
        content.add(new StatusBar(statusText), BorderLayout.SOUTH);
        setContentPane(content);

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
        super.dispose();
    }

    @NotNull
    private static JSplitPane createSplitPane(int orientation) {
        JSplitPane splitPane = new JSplitPane(orientation);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    /**
     * Center document area. Only a welcome tab for now; SQL editor tabs come with the next phase.
     */
    @NotNull
    private static JTabbedPane createDocumentTabs() {
        JLabel titleLabel = new JLabel(GeneralUtils.getProductName());
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1"); //$NON-NLS-1$
        JLabel hintLabel = new JLabel("Expand a connection in Object Explorer to browse its objects.");
        JLabel nextLabel = new JLabel("SQL editor tabs will open here.");
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
        tabs.addTab("Welcome", welcome);
        return tabs;
    }

    /**
     * Bottom Results / Messages tabs. Placeholders until query execution exists.
     */
    @NotNull
    private static JTabbedPane createResultTabs() {
        JLabel resultsPlaceholder = new JLabel("No results. Query results will appear here.", SwingConstants.CENTER);
        resultsPlaceholder.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        JTextArea messagesArea = new JTextArea();
        messagesArea.setEditable(false);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Results", resultsPlaceholder);
        tabs.addTab("Messages", new JScrollPane(messagesArea));
        return tabs;
    }

    @NotNull
    private JMenuBar createMenuBar() {
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> dispose());

        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(exitItem);

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
        StatusBar(@NotNull String statusText) {
            super(new BorderLayout());
            JLabel statusLabel = new JLabel(statusText);
            statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            add(statusLabel, BorderLayout.WEST);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color separatorColor = UIManager.getColor("Separator.foreground"); //$NON-NLS-1$
            setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separatorColor != null ? separatorColor : Color.GRAY));
        }
    }
}
