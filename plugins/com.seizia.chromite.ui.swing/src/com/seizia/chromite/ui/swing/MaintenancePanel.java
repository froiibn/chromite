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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.admin.maintenance.MaintenanceAction;
import com.seizia.chromite.model.admin.maintenance.MaintenanceDialect;
import com.seizia.chromite.model.admin.maintenance.MaintenanceReport;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Activity Monitor의 Maintenance(유지 관리) 탭: 유지 관리 후보와 상태 목록(조각난 인덱스, 오래된 통계, 무효 객체, VACUUM이 필요한 테이블)과
 * 행마다 바로 실행할 수 있는 권장 작업.
 *
 * 설계: 공간 사용량 탭({@link DiskUsagePanel})과 같은 모양이다(도구 막대의 범위·개수, 권한 안내 줄, 표마다 하위 탭, 상태 줄).
 * <ul>
 *     <li>DB별 질의는 모델의 {@link MaintenanceDialect}가 하고, 목록은 Activity Monitor의 서버 연결({@link AdminConnection})로 EDT 밖에서 읽는다(읽기 전용).</li>
 *     <li>행을 두 번 누르거나 "작업 실행"을 누르면 그 행의 권장 작업으로 {@link MaintenanceDialog}를 연다. 작업 실행은 대화상자가 자기 연결에서 하고
 *     (이 탭의 연결이 아님), 끝나면 이 탭을 다시 읽어 전후 숫자를 보인다.</li>
 *     <li>목록은 크고 자주 바뀌지 않아 자동 새로 고침이 없다. 탭을 처음 볼 때, Refresh, 범위·개수를 바꿀 때, 작업이 끝날 때 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-09 한국어 단추 이름의 끝 말줄임표를 없앤 것에 맞춰 주석의 단추 이름을 고침 (docs/chromite/swing-l10n.md)
 * - 2026-11-12 아이콘 단추의 도구 설명을 설명문 대신 기능 이름(동작·메뉴의 이름)과 단축키로 (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class MaintenancePanel extends JPanel {

    private static final Log log = Log.getLog(MaintenancePanel.class);
    private static final Integer[] LIMITS = {25, 50, 100, 250, 500};
    private static final int DEFAULT_LIMIT = 100;

    private final AdminConnection connection;
    private final JLabel scopeLabel = new JLabel();
    private final JComboBox<String> scopeCombo = new JComboBox<>();
    private final JComboBox<Integer> limitCombo = new JComboBox<>(LIMITS);
    private final AccessNoticeBar noticeBar = new AccessNoticeBar();
    private final AdminGridTabs gridTabs = new AdminGridTabs();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final Action refreshAction;
    private final Action runAction;
    private boolean loading;
    private boolean updatingScopes;
    private boolean allScopes = true;
    @Nullable
    private String scope;

    MaintenancePanel(@NotNull AdminConnection connection) {
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
        scopeLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), 0, UIScale.scale(4)));
        toolBar.add(scopeLabel);
        scopeCombo.setToolTipText(MaintenanceViewMessages.maintview_scope_tip);
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
        JLabel limitLabel = new JLabel(MaintenanceViewMessages.maintview_limit_label);
        limitLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, UIScale.scale(4)));
        toolBar.add(limitLabel);
        limitCombo.setSelectedItem(DEFAULT_LIMIT);
        limitCombo.setToolTipText(MaintenanceViewMessages.maintview_limit_tip);
        limitCombo.setMaximumSize(limitCombo.getPreferredSize());
        limitCombo.addActionListener(e -> {
            if (!loading) {
                refresh();
            }
        });
        toolBar.add(limitCombo);
        toolBar.addSeparator();
        runAction = new AbstractAction(MaintenanceViewMessages.maintview_run_suggested) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openAction(gridTabs.getSelectedTag());
            }
        };
        runAction.setEnabled(false);
        toolBar.add(UiIcons.createActionButton(runAction, "run")); //$NON-NLS-1$

        gridTabs.setRowAction(this::openAction);
        gridTabs.addSelectionListener(() -> runAction.setEnabled(gridTabs.getSelectedTag() instanceof MaintenanceAction));

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
     * Reads the lists (connecting first if needed).
     */
    void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        refreshAction.setEnabled(false);
        statusLabel.setText(connection.getContainer().isConnected() ? MaintenanceViewMessages.maintview_reading : ConnectionMessages.common_connecting);
        statusLabel.setToolTipText(null);
        String requestedScope = scope;
        int limit = limitCombo.getSelectedItem() instanceof Integer value ? value : DEFAULT_LIMIT;
        connection.run("Read maintenance status", (monitor, session, profile) -> { //$NON-NLS-1$
            MaintenanceDialect dialect = MaintenanceDialect.forDataSource(session.getDataSource());
            if (dialect == null || !(session instanceof JDBCSession jdbcSession)) {
                return null;
            }
            monitor.subTask(MaintenanceViewMessages.maintview_reading);
            return new Result(dialect.getScopeLabel(), dialect.supportsAllScopes(), dialect.readOverview(monitor, jdbcSession, requestedScope, limit));
        }, this::showReport, error -> {
            loading = false;
            refreshAction.setEnabled(true);
            statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
            statusLabel.setToolTipText(ErrorText.describeLine(error));
            log.debug("Error reading maintenance status", error); //$NON-NLS-1$
        });
    }

    private record Result(@NotNull String scopeLabel, boolean allScopes, @NotNull MaintenanceReport report) {
    }

    private void showReport(@Nullable Result result) {
        loading = false;
        refreshAction.setEnabled(true);
        if (result == null) {
            // Not a database this view knows: say so and keep the commands off
            refreshAction.setEnabled(false);
            scopeCombo.setEnabled(false);
            limitCombo.setEnabled(false);
            noticeBar.setNotice(NLS.bind(MaintenanceViewMessages.maintview_not_supported, connection.getContainer().getDriver().getName()));
            statusLabel.setText(" "); //$NON-NLS-1$
            return;
        }
        MaintenanceReport report = result.report();
        noticeBar.setNotice(report.notices().isEmpty() ? null : String.join("\n", report.notices())); //$NON-NLS-1$
        scopeLabel.setText(result.scopeLabel() + ":"); //$NON-NLS-1$
        fillScopes(result.allScopes(), report.scopes(), report.scope());
        gridTabs.showGrids(report.grids());
        String text = NLS.bind(MaintenanceViewMessages.maintview_status_lists, report.grids().size());
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_updated, text, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date())));
        statusLabel.setToolTipText(null);
    }

    private void fillScopes(boolean all, @NotNull List<String> scopes, @Nullable String selected) {
        updatingScopes = true;
        try {
            allScopes = all;
            scopeCombo.removeAllItems();
            if (all) {
                scopeCombo.addItem(MaintenanceViewMessages.maintview_scope_all);
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
     * Opens the maintenance dialog for a row's suggested action; reads the lists again when it has run.
     */
    private void openAction(@Nullable Object tag) {
        DBPDataSource dataSource = connection.getContainer().getDataSource();
        if (!(tag instanceof MaintenanceAction action) || dataSource == null) {
            return;
        }
        MaintenanceDialect dialect = MaintenanceDialect.forDataSource(dataSource);
        if (dialect == null) {
            return;
        }
        // The lists come from the default database of the connection (PostgreSQL: the connected database)
        MaintenanceDialog.open(this, connection.getContainer(), dataSource.getDefaultInstance(), dialect, action.target(), action.operationId(),
            this::refresh);
    }
}
