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
import com.seizia.chromite.model.admin.space.ServerSpaceDialect;
import com.seizia.chromite.model.admin.space.SpaceGrid;
import com.seizia.chromite.model.admin.space.SpaceReport;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.ui.swing.internal.AdminViewMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity Monitor의 Disk Usage(공간 사용량) 탭: 데이터베이스·파일·테이블스페이스 크기와 큰 테이블.
 *
 * 설계: Activity Monitor 문서의 탭으로 둔다(별도 문서가 아니다). 연결마다 하나인 관리 문서가 이미 서버 연결({@link AdminConnection},
 * 격리된 실행 컨텍스트 하나)과 Tools·Object Explorer 메뉴의 "탭 열기" 길을 가지고 있어, 새 문서 종류를 MainFrame에 더하지 않고
 * 메뉴 줄 몇 개로 열 수 있다. SSMS에서는 보고서(Disk Usage)로 따로 열지만 같은 서버의 관리 정보를 한 문서에 모으는 쪽을 골랐다.
 * <ul>
 *     <li>DB별 질의는 모델의 {@link ServerSpaceDialect}가 한다. 표마다 하위 탭 하나({@link AdminGridTable})이고, 로그인 권한으로 읽을 수 없는 표는
 *     탭 안에 이유를 보인다. 무엇이 빠졌고 무엇을 받아야 하는지는 위의 안내 줄({@link AccessNoticeBar})에 보인다.</li>
 *     <li>큰 테이블 표의 범위(데이터베이스·스키마·소유자)와 개수를 도구 막대에서 고른다. 바꾸면 다시 읽는다.</li>
 *     <li>크기는 자주 바뀌지 않아 자동 새로 고침이 없다. 탭을 처음 볼 때와 Refresh를 누를 때 읽는다.</li>
 *     <li>모든 질의는 EDT 밖의 작업에서 진행 모니터와 함께 실행한다({@link AdminConnection#run}). 읽기 전용이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 3 공간 사용량 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-12 아이콘 단추의 도구 설명을 설명문 대신 기능 이름(동작·메뉴의 이름)과 단축키로 (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class DiskUsagePanel extends JPanel {

    private static final Log log = Log.getLog(DiskUsagePanel.class);
    private static final Integer[] TABLE_LIMITS = {25, 50, 100, 250, 500};
    private static final int DEFAULT_TABLE_LIMIT = 50;

    private final AdminConnection connection;
    private final JLabel scopeLabel = new JLabel();
    private final JComboBox<String> scopeCombo = new JComboBox<>();
    private final JComboBox<Integer> limitCombo = new JComboBox<>(TABLE_LIMITS);
    private final AccessNoticeBar noticeBar = new AccessNoticeBar();
    private final JTabbedPane gridTabs = new JTabbedPane();
    private final Map<SpaceGrid.Kind, AdminGridTable> tables = new LinkedHashMap<>();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final Action refreshAction;
    private boolean loading;
    private boolean updatingScopes;
    private boolean allScopes = true;
    @Nullable
    private String scope;

    DiskUsagePanel(@NotNull AdminConnection connection) {
        super(new BorderLayout());
        this.connection = connection;

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        refreshAction = new AbstractAction(ConnectionMessages.common_refresh) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refresh();
            }
        };
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.addSeparator();
        scopeLabel.setText(AdminViewMessages.admin_space_scope_database);
        scopeLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), 0, UIScale.scale(4)));
        toolBar.add(scopeLabel);
        scopeCombo.setToolTipText(AdminViewMessages.admin_space_scope_tip);
        scopeCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXXXXXX"); //$NON-NLS-1$
        scopeCombo.setMaximumSize(scopeCombo.getPreferredSize());
        scopeCombo.setEnabled(false);
        scopeCombo.addActionListener(e -> {
            if (!updatingScopes && !loading) {
                int index = scopeCombo.getSelectedIndex();
                scope = index < 0 || allScopes && index == 0 ? null : (String) scopeCombo.getSelectedItem();
                refresh();
            }
        });
        toolBar.add(scopeCombo);
        JLabel limitLabel = new JLabel(AdminViewMessages.admin_space_tables_label);
        limitLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, UIScale.scale(4)));
        toolBar.add(limitLabel);
        limitCombo.setSelectedItem(DEFAULT_TABLE_LIMIT);
        limitCombo.setToolTipText(AdminViewMessages.admin_space_tables_tip);
        limitCombo.setMaximumSize(limitCombo.getPreferredSize());
        limitCombo.addActionListener(e -> {
            if (!loading) {
                refresh();
            }
        });
        toolBar.add(limitCombo);

        gridTabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolBar, BorderLayout.NORTH);
        north.add(noticeBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(gridTabs, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Reads the sizes (connecting first if needed).
     */
    void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        refreshAction.setEnabled(false);
        statusLabel.setText(connection.getContainer().isConnected() ? AdminViewMessages.admin_space_reading : ConnectionMessages.common_connecting);
        statusLabel.setToolTipText(null);
        String requestedScope = scope;
        int limit = limitCombo.getSelectedItem() instanceof Integer value ? value : DEFAULT_TABLE_LIMIT;
        connection.run("Read disk usage", (monitor, session, profile) -> { //$NON-NLS-1$
            ServerSpaceDialect dialect = ServerSpaceDialect.forDataSource(session.getDataSource());
            if (dialect == null || !(session instanceof JDBCSession jdbcSession)) {
                return null;
            }
            monitor.subTask(AdminViewMessages.admin_space_reading);
            return new Result(dialect.getScopeLabel(), dialect.supportsAllScopes(), dialect.read(monitor, jdbcSession, requestedScope, limit));
        }, this::showReport, error -> {
            loading = false;
            refreshAction.setEnabled(true);
            statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
            statusLabel.setToolTipText(ErrorText.describeLine(error));
            log.debug("Error reading disk usage", error); //$NON-NLS-1$
        });
    }

    private record Result(@NotNull String scopeLabel, boolean allScopes, @NotNull SpaceReport report) {
    }

    private void showReport(@Nullable Result result) {
        loading = false;
        refreshAction.setEnabled(true);
        if (result == null) {
            // Not a database this view knows: say so and keep the commands off
            refreshAction.setEnabled(false);
            scopeCombo.setEnabled(false);
            limitCombo.setEnabled(false);
            noticeBar.setNotice(NLS.bind(AdminViewMessages.admin_space_not_supported, connection.getContainer().getDriver().getName()));
            statusLabel.setText(" "); //$NON-NLS-1$
            return;
        }
        SpaceReport report = result.report();
        noticeBar.setNotice(report.notices().isEmpty() ? null : String.join("\n", report.notices())); //$NON-NLS-1$
        scopeLabel.setText(result.scopeLabel() + ":"); //$NON-NLS-1$
        fillScopes(result.allScopes(), report.scopes(), report.scope());
        showGrids(report.grids());
        int rows = 0;
        for (SpaceGrid grid : report.grids()) {
            if (grid.getKind() == SpaceGrid.Kind.TABLES) {
                rows = grid.getRows().size();
            }
        }
        String text = NLS.bind(rows == 1 ? AdminViewMessages.admin_space_status_one : AdminViewMessages.admin_space_status_many, rows);
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_updated, text, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date())));
        statusLabel.setToolTipText(null);
    }

    private void fillScopes(boolean all, @NotNull List<String> scopes, @Nullable String selected) {
        updatingScopes = true;
        try {
            allScopes = all;
            scopeCombo.removeAllItems();
            if (all) {
                scopeCombo.addItem(AdminViewMessages.admin_space_scope_all);
            }
            for (String name : scopes) {
                scopeCombo.addItem(name);
            }
            if (selected != null && scopes.contains(selected)) {
                scopeCombo.setSelectedItem(selected);
            } else if (scopeCombo.getItemCount() > 0) {
                scopeCombo.setSelectedIndex(0);
            }
            scope = selected;
            scopeCombo.setEnabled(scopeCombo.getItemCount() > 1);
        } finally {
            updatingScopes = false;
        }
    }

    /**
     * One tab per grid; the tabs stay (with their sort and selection) when the same grids are read again.
     */
    private void showGrids(@NotNull List<SpaceGrid> grids) {
        List<SpaceGrid.Kind> kinds = new ArrayList<>();
        for (SpaceGrid grid : grids) {
            kinds.add(grid.getKind());
        }
        SpaceGrid.Kind selectedKind = null;
        int selectedIndex = gridTabs.getSelectedIndex();
        if (selectedIndex >= 0 && gridTabs.getComponentAt(selectedIndex) instanceof JComponent component
            && component.getClientProperty(SpaceGrid.Kind.class) instanceof SpaceGrid.Kind kind) {
            selectedKind = kind;
        }
        if (!kinds.equals(new ArrayList<>(tables.keySet())) || gridTabs.getTabCount() != grids.size()) {
            gridTabs.removeAll();
            tables.clear();
            for (SpaceGrid grid : grids) {
                gridTabs.addTab(grid.getTitle(), new JPanel(new BorderLayout()));
                tables.put(grid.getKind(), new AdminGridTable(false));
            }
        }
        for (int i = 0; i < grids.size(); i++) {
            SpaceGrid grid = grids.get(i);
            JPanel page = (JPanel) gridTabs.getComponentAt(i);
            page.putClientProperty(SpaceGrid.Kind.class, grid.getKind());
            page.removeAll();
            gridTabs.setTitleAt(i, grid.getTitle());
            AdminGridTable table = tables.get(grid.getKind());
            if (grid.getUnavailableReason() != null) {
                table.clearRows();
                gridTabs.setToolTipTextAt(i, grid.getUnavailableReason());
                page.add(createUnavailableLabel(grid.getUnavailableReason()), BorderLayout.CENTER);
            } else {
                table.setRows(grid.getColumns(), grid.getRows());
                gridTabs.setToolTipTextAt(i, null);
                JScrollPane scroll = new JScrollPane(table);
                scroll.setBorder(BorderFactory.createEmptyBorder());
                page.add(scroll, BorderLayout.CENTER);
            }
            page.revalidate();
            page.repaint();
            if (grid.getKind() == selectedKind) {
                gridTabs.setSelectedIndex(i);
            }
        }
    }

    @NotNull
    private static JComponent createUnavailableLabel(@NotNull String reason) {
        JTextArea text = new JTextArea(reason);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFocusable(false);
        text.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(16), UIScale.scale(16), UIScale.scale(16), UIScale.scale(16)));
        text.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        return text;
    }
}
