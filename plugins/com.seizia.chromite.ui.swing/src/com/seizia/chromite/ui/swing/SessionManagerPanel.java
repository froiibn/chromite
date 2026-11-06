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
import com.seizia.chromite.model.admin.ServerAdminMessages;
import com.seizia.chromite.model.admin.ServerAdminProfile;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.DBPObjectWithDescription;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSession;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionDetails;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionDetailsProvider;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionManager;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionManagerSQL;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.properties.PropertyCollector;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity Monitor의 Processes 탭: 서버 세션 목록, 고른 세션의 현재 SQL과 속성, 세션 끝내기.
 *
 * 설계: SWT의 {@code SessionManagerViewer}(세션 표, SQL 미리 보기, 속성, 세부 정보 탭, 자동 새로 고침)와
 * DB별 세션 편집기({@code PostgreSessionEditor} 등의 필터·끝내기 단추)를 화면 하나로 옮겼다. DB별 차이는
 * {@link ServerAdminProfile}(모델 번들)이 정한다. 세션 목록은 {@code DBAServerSessionManager#getSessions}, 끝내기는
 * {@code #alterSession}이며 SWT와 같은 옵션을 넘긴다.
 * <ul>
 *     <li>필터 체크 상자는 세션 관리자의 읽기 옵션이라 바꾸면 서버에서 다시 읽는다. 값은 드라이버마다 설정 저장소에 둔다
 *     (SWT는 편집기 대화 설정에 둔다).</li>
 *     <li>"Only my sessions"는 연결 설정의 사용자와 세션의 사용자를 비교하는 화면 쪽 필터다(SWT에 없음). 연결 설정에 사용자가 없으면
 *     (Windows 인증 등) 쓸 수 없다.</li>
 *     <li>찾기 칸은 SWT 세션 편집기의 검색처럼 어느 열에든 그 글자가 있는 세션만 남긴다.</li>
 *     <li>끝내기 명령은 SWT처럼 확인을 받는다. Oracle의 "Immediate"는 SWT 확인 대화상자의 토글과 같이 확인 창의 체크 상자다.
 *     끝낸 뒤 목록을 다시 읽는다.</li>
 *     <li>세션 읽기 SQL을 만들 수 있으면({@link DBAServerSessionManagerSQL}) SWT의 "Open SQL editor"처럼 그 SQL을 새 쿼리 창에서 실행한다.</li>
 *     <li>아래 탭: Current SQL(읽기 전용 SQL 편집기), Details(세션의 모든 속성, {@link PropertyCollector}), 그리고 세션 관리자가 주는
 *     추가 세부 정보({@link DBAServerSessionDetailsProvider}, 예: Oracle long operations). 실행 계획 탭은 Swing 계획 보기가 없어 아직 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 찾기 칸과 "Only my sessions"가 테마 전환을 놓치던 문제: 패널과 함께 만들고 첫 읽기 뒤에야 붙이므로(createTools),
 *   붙일 때 현재 Look and Feel로 다시 설치함 (docs/chromite/swing-admin-tools.md)
 */
final class SessionManagerPanel extends JPanel {

    private static final Log log = Log.getLog(SessionManagerPanel.class);
    private static final String PREF_FILTER_PREFIX = "activityMonitor.sessions.filter."; //$NON-NLS-1$
    private static final String PREF_REFRESH = "activityMonitor.sessions.refreshSeconds"; //$NON-NLS-1$

    private final AdminConnection connection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final ObjectListTable<DBAServerSession> sessionTable;
    private final JToolBar toolBar = new JToolBar();
    /**
     * Second row under the tool bar: the read options (check boxes); one row would not fit next to the commands.
     */
    private final JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(12), 0));
    private final JLabel statusLabel = new JLabel(" ");
    private final SqlEditorPanel.SqlTextArea sqlText = new SqlEditorPanel.SqlTextArea();
    private final DefaultTableModel detailsModel = new DefaultTableModel(new Object[] {"Property", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTabbedPane detailTabs = new JTabbedPane();
    private final AutoRefreshBox autoRefresh;
    private final JCheckBox onlyMineCheck = new JCheckBox("Only my sessions");
    private final JTextField searchField = new JTextField(16);
    private final List<JButton> actionButtons = new ArrayList<>();
    private final List<ServerAdminProfile.SessionAction> actions = new ArrayList<>();
    @Nullable
    private ServerAdminProfile profile;
    private boolean loading;
    private boolean toolsCreated;
    @Nullable
    private DBAServerSession currentSession;

    SessionManagerPanel(@NotNull AdminConnection connection, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        super(new BorderLayout());
        this.connection = connection;
        this.queryWindows = queryWindows;
        sessionTable = new ObjectListTable<>(session -> CommonUtils.notEmpty(session.getSessionId()), connection.getContainer());
        sessionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                sessionSelected();
            }
        });

        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        Action refreshAction = new AbstractAction("Refresh") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refresh();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, "Read the sessions again");
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        autoRefresh = new AutoRefreshBox(PREF_REFRESH, this, () -> {
            // Not after a failed connect: that would ask for the password again at each interval
            if (!loading && connection.getContainer().isConnected()) {
                refresh();
            }
        });
        autoRefresh.setMaximumSize(autoRefresh.getPreferredSize());
        toolBar.add(autoRefresh);
        toolBar.addSeparator();

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Find in sessions");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setMaximumSize(searchField.getPreferredSize());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                searchChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                searchChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                searchChanged();
            }
        });

        onlyMineCheck.setOpaque(false);
        onlyMineCheck.addActionListener(e -> applyUserFilter());

        sqlText.setEditable(false);
        sqlText.setLineWrap(true);
        RTextScrollPane sqlScroll = new RTextScrollPane(sqlText, true);
        sqlScroll.setBorder(BorderFactory.createEmptyBorder());
        sqlText.applyTheme();
        JTable detailsTable = new JTable(detailsModel);
        detailsTable.setShowGrid(false);
        detailsTable.setFillsViewportHeight(true);
        detailsTable.setIntercellSpacing(new Dimension(0, 0));
        JScrollPane detailsScroll = new JScrollPane(detailsTable);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailTabs.putClientProperty(FlatClientProperties.STYLE, "tabHeight: 32; tabSelectionHeight: 3"); //$NON-NLS-1$
        detailTabs.addTab("Current SQL", UiIcons.get("file-sql"), sqlScroll); //$NON-NLS-2$
        detailTabs.addTab("Details", UiIcons.get("info"), detailsScroll); //$NON-NLS-2$
        detailTabs.addChangeListener(e -> loadSelectedDetails());

        JScrollPane tableScroll = new JScrollPane(sessionTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        // Most of the height for the sessions; the split starts from the preferred sizes
        tableScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(360)));
        detailTabs.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(180)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailTabs);
        split.setResizeWeight(0.65);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        filterBar.setOpaque(false);
        filterBar.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), UIScale.scale(4), UIScale.scale(8)));
        filterBar.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolBar, BorderLayout.NORTH);
        north.add(filterBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Reads the sessions (connecting first if needed).
     */
    void refresh() {
        loading = true;
        statusLabel.setText(connection.getContainer().isConnected() ? "Reading sessions…" : "Connecting…");
        connection.run("Read server sessions", (monitor, session, profile) -> {
            DBAServerSessionManager<DBAServerSession> manager = profile.getSessionManager();
            if (manager == null) {
                throw new DBException("Sessions of " + session.getDataSource().getContainer().getDriver().getName() + " are not supported");
            }
            Map<String, Object> options = new LinkedHashMap<>();
            for (ServerAdminProfile.FilterOption option : profile.getFilterOptions()) {
                options.put(option.optionKey(), getFilterValue(option));
            }
            return new ArrayList<>(manager.getSessions(session, options));
        }, this::showSessions, error -> {
            loading = false;
            createTools();
            updateStatus(error);
        });
    }

    void dispose() {
        autoRefresh.dispose();
    }

    private void searchChanged() {
        sessionTable.setSearchText(searchField.getText());
        updateStatus(null);
    }

    private void showSessions(@NotNull List<DBAServerSession> sessions) {
        loading = false;
        createTools();
        sessionTable.setObjects(sessions, sessions.isEmpty() ? DBAServerSession.class : sessions.get(0).getClass());
        DBAServerSession selected = sessionTable.getSelectedObject();
        // The same session (by id) is a new object after each read: show its current query and state
        if (selected != null) {
            showSession(selected);
        } else if (currentSession != null) {
            showSession(null);
        }
        updateStatus(null);
    }

    /**
     * Tool bar items that depend on the database: filters, commands, the session SQL; created once the first read
     * has connected (the profile needs the data source).
     */
    private void createTools() {
        ServerAdminProfile connectedProfile = connection.getProfile();
        if (toolsCreated || connectedProfile == null) {
            return;
        }
        toolsCreated = true;
        profile = connectedProfile;
        // Created with the panel but added to it only now: a theme switch since then (e.g. while the Locks tab was shown)
        // did not reach them, as it updates only the components in a window
        SwingUtilities.updateComponentTreeUI(searchField);
        SwingUtilities.updateComponentTreeUI(onlyMineCheck);
        for (ServerAdminProfile.FilterOption option : profile.getFilterOptions()) {
            JCheckBox check = new JCheckBox(option.label(), getFilterValue(option));
            check.setToolTipText(option.toolTip());
            check.setOpaque(false);
            check.addActionListener(e -> {
                DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
                preferences.setValue(getFilterPreference(option), check.isSelected());
                PrefUtils.savePreferenceStore(preferences);
                refresh();
            });
            filterBar.add(check);
        }
        if (profile.supportsUserFilter()) {
            String user = connection.getContainer().getActualConnectionConfiguration().getUserName();
            onlyMineCheck.setEnabled(!CommonUtils.isEmpty(user));
            onlyMineCheck.setToolTipText(CommonUtils.isEmpty(user)
                ? "The connection has no user name to compare with"
                : "Show only the sessions of " + user);
            filterBar.add(onlyMineCheck);
        }
        filterBar.setVisible(filterBar.getComponentCount() > 0);
        for (ServerAdminProfile.SessionAction action : profile.getSessionActions()) {
            Action swingAction = new AbstractAction(action.label()) {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    runAction(action);
                }
            };
            swingAction.putValue(Action.SHORT_DESCRIPTION, action.toolTip());
            JButton button = UiIcons.createToolButton(swingAction, action.queryOnly() ? "stop" : "disconnect"); //$NON-NLS-1$ //$NON-NLS-2$
            actions.add(action);
            actionButtons.add(button);
            toolBar.add(button);
        }
        DBAServerSessionManager<DBAServerSession> manager = profile.getSessionManager();
        if (manager instanceof DBAServerSessionManagerSQL sqlManager && sqlManager.canGenerateSessionReadQuery()) {
            toolBar.addSeparator();
            Action scriptAction = new AbstractAction("Open Session Query") {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    Map<String, Object> options = new LinkedHashMap<>();
                    for (ServerAdminProfile.FilterOption option : profile.getFilterOptions()) {
                        options.put(option.optionKey(), getFilterValue(option));
                    }
                    queryWindows.openScript(connection.getContainer(), sqlManager.generateSessionReadQuery(options), 0, true);
                }
            };
            scriptAction.putValue(Action.SHORT_DESCRIPTION, "Open the query that reads the sessions in a new query window and run it");
            toolBar.add(UiIcons.createActionButton(scriptAction, "new-query")); //$NON-NLS-1$
        }
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(searchField);
        if (manager instanceof DBAServerSessionDetailsProvider detailsProvider) {
            for (DBAServerSessionDetails details : detailsProvider.getSessionDetails()) {
                addDetailsTab(details);
            }
        }
        applyUserFilter();
        updateActions();
        revalidate();
        repaint();
    }

    private boolean getFilterValue(@NotNull ServerAdminProfile.FilterOption option) {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        String key = getFilterPreference(option);
        return preferences.contains(key) ? preferences.getBoolean(key) : option.defaultValue();
    }

    @NotNull
    private String getFilterPreference(@NotNull ServerAdminProfile.FilterOption option) {
        return PREF_FILTER_PREFIX + connection.getContainer().getDriver().getId() + "." + option.optionKey(); //$NON-NLS-1$
    }

    private void applyUserFilter() {
        String user = connection.getContainer().getActualConnectionConfiguration().getUserName();
        if (profile != null && onlyMineCheck.isSelected() && !CommonUtils.isEmpty(user)) {
            ServerAdminProfile currentProfile = profile;
            sessionTable.setExtraFilter(session -> user.equalsIgnoreCase(CommonUtils.notEmpty(currentProfile.getSessionUser(session))));
        } else {
            sessionTable.setExtraFilter(null);
        }
        updateStatus(null);
    }

    private void sessionSelected() {
        showSession(sessionTable.getSelectedObject());
        updateActions();
    }

    private void showSession(@Nullable DBAServerSession session) {
        currentSession = session;
        String query = session == null ? "" : CommonUtils.notEmpty(session.getActiveQuery());
        if (!query.equals(sqlText.getText())) {
            sqlText.setText(query);
            sqlText.setCaretPosition(0);
        }
        detailsModel.setRowCount(0);
        if (session != null) {
            PropertyCollector collector = new PropertyCollector(session, false);
            collector.collectProperties();
            for (DBPPropertyDescriptor property : collector.getProperties()) {
                Object value = collector.getPropertyValue(null, property.getId());
                detailsModel.addRow(new Object[] {property.getDisplayName(), ObjectListTable.formatValue(value)});
            }
        }
        loadSelectedDetails();
    }

    private void updateActions() {
        List<DBAServerSession> selected = sessionTable.getSelectedObjects();
        for (int i = 0; i < actions.size(); i++) {
            actionButtons.get(i).setEnabled(actions.get(i).isEnabled(selected));
        }
    }

    /**
     * Ends the selected sessions (or their queries) after a confirmation.
     */
    private void runAction(@NotNull ServerAdminProfile.SessionAction action) {
        List<DBAServerSession> sessions = sessionTable.getSelectedObjects();
        if (sessions.isEmpty() || profile == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (DBAServerSession session : sessions) {
            ids.add(session.getSessionId());
        }
        String what = (sessions.size() == 1 ? "session " : "sessions ") + String.join(", ", ids);
        JCheckBox immediateCheck = action.immediateOptionKey() == null ? null : new JCheckBox(
            ServerAdminMessages.action_immediate);
        Object message = immediateCheck == null
            ? action.label() + " for " + what + "?\n\n" + action.toolTip() + "."
            : new Object[] {action.label() + " for " + what + "?\n\n" + action.toolTip() + ".", immediateCheck};
        int answer = JOptionPane.showConfirmDialog(this, message, action.label(), JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        Map<String, Object> options = new LinkedHashMap<>(action.options());
        if (immediateCheck != null && immediateCheck.isSelected()) {
            options.put(action.immediateOptionKey(), true);
        }
        statusLabel.setText(action.label() + "…");
        connection.run(action.label(), (monitor, session, connectedProfile) -> {
            DBAServerSessionManager<DBAServerSession> manager = connectedProfile.getSessionManager();
            if (manager == null) {
                return null;
            }
            DBException lastError = null;
            for (DBAServerSession serverSession : sessions) {
                try {
                    manager.alterSession(session, serverSession.getSessionId(), options);
                } catch (DBException e) {
                    log.debug("Error altering session " + serverSession.getSessionId(), e);
                    lastError = e;
                }
            }
            if (lastError != null) {
                throw lastError;
            }
            return null;
        }, result -> refresh(), error -> {
            DBWorkbench.getPlatformUI().showError(action.label(), "Can't complete '" + action.label() + "' for " + what, error);
            refresh();
        });
    }

    /**
     * Extra details of the database ({@link DBAServerSessionDetailsProvider}): plain text, or a list of objects.
     */
    private void addDetailsTab(@NotNull DBAServerSessionDetails details) {
        JComponent content;
        Class<?> type = details.getDetailsType();
        if (DBPObjectWithDescription.class.isAssignableFrom(type)) {
            SqlEditorPanel.SqlTextArea text = new SqlEditorPanel.SqlTextArea();
            text.setEditable(false);
            RTextScrollPane scroll = new RTextScrollPane(text, false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            text.applyTheme();
            scroll.putClientProperty(DBAServerSessionDetails.class, details);
            content = scroll;
        } else {
            ObjectListTable<DBPObject> table = new ObjectListTable<>(object -> object, connection.getContainer());
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.putClientProperty(DBAServerSessionDetails.class, details);
            content = scroll;
        }
        detailTabs.addTab(details.getDetailsTitle(), NodeIcons.getIcon(details.getDetailsIcon()), content, details.getDetailsTooltip());
    }

    /**
     * Reads the extra details of the selected session when their tab is shown.
     */
    @SuppressWarnings("unchecked")
    private void loadSelectedDetails() {
        if (!(detailTabs.getSelectedComponent() instanceof JScrollPane scroll)
            || !(scroll.getClientProperty(DBAServerSessionDetails.class) instanceof DBAServerSessionDetails details)) {
            return;
        }
        DBAServerSession session = currentSession;
        Component view = scroll.getViewport().getView();
        if (session == null) {
            if (view instanceof ObjectListTable<?> table) {
                table.clearObjects();
            } else if (view instanceof JTextArea text) {
                text.setText("");
            }
            return;
        }
        connection.run("Read session details", (monitor, dbSession, connectedProfile) ->
            new ArrayList<DBPObject>(CommonUtils.safeCollection(details.getSessionDetails(dbSession, session))), objects -> {
            if (session != currentSession) {
                return;
            }
            if (view instanceof ObjectListTable<?> table) {
                ((ObjectListTable<DBPObject>) table).setObjects(objects, details.getDetailsType());
            } else if (view instanceof JTextArea text) {
                StringBuilder builder = new StringBuilder();
                for (DBPObject object : objects) {
                    if (object instanceof DBPObjectWithDescription described) {
                        builder.append(CommonUtils.notEmpty(described.getDescription())).append('\n');
                    }
                }
                text.setText(builder.toString());
                text.setCaretPosition(0);
            }
        }, error -> DBWorkbench.getPlatformUI().showError(details.getDetailsTitle(), "Can't read the session details", error));
    }

    private void updateStatus(@Nullable Throwable error) {
        if (error != null) {
            statusLabel.setText("Error: " + CommonUtils.notEmpty(error.getMessage()).replaceAll("\\s+", " ")); //$NON-NLS-1$ //$NON-NLS-2$
            statusLabel.setToolTipText(error.getMessage());
            log.debug("Error reading sessions", error);
            return;
        }
        if (loading) {
            return;
        }
        Collection<DBAServerSession> sessions = sessionTable.getObjects();
        int shown = sessionTable.getRowCount();
        String text = sessions.size() + (sessions.size() == 1 ? " session" : " sessions");
        if (shown != sessions.size()) {
            text += ", " + shown + " shown";
        }
        statusLabel.setText(text + " · Updated " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()));
        statusLabel.setToolTipText(null);
    }
}
