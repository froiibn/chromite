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
import com.seizia.chromite.model.admin.replication.ReplicationDialect;
import com.seizia.chromite.model.admin.replication.ReplicationReport;
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

/**
 * Activity Monitor의 Replication(복제) 탭: 서버의 복제 역할과 상태(대기 서버·지연, 슬롯, 복제본 연결, Data Guard, 가용성 그룹)를 읽기 전용으로 보인다.
 *
 * 설계: 표마다 하위 탭 하나({@link AdminGridTabs})이고 첫 탭은 요약(역할, 구성 여부, 주요 설정)이다. 복제가 구성되지 않은 서버는 오류가 아니므로
 * 요약과 각 표가 "구성되지 않음"을 말한다. 읽기는 Activity Monitor의 서버 연결({@link AdminConnection})로 EDT 밖에서 한다.
 * 지연은 시시각각 바뀌므로 Processes·Locks 탭과 같은 자동 새로 고침 간격({@link AutoRefreshBox})을 둔다(탭이 보일 때만 읽음).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
final class ReplicationPanel extends JPanel {

    private static final Log log = Log.getLog(ReplicationPanel.class);
    private static final String REFRESH_PREFERENCE = "activityMonitor.replication.refreshSeconds"; //$NON-NLS-1$

    private final AdminConnection connection;
    private final AccessNoticeBar noticeBar = new AccessNoticeBar();
    private final AdminGridTabs gridTabs = new AdminGridTabs();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final Action refreshAction;
    private final AutoRefreshBox autoRefresh;
    private boolean loading;
    private boolean unsupported;

    ReplicationPanel(@NotNull AdminConnection connection) {
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
        refreshAction.putValue(Action.SHORT_DESCRIPTION, MaintenanceViewMessages.maintview_repl_refresh_tip);
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.addSeparator();
        // Skip a tick while reading or after a failed connection (no password prompt at every interval)
        autoRefresh = new AutoRefreshBox(REFRESH_PREFERENCE, this, () -> {
            if (!loading && !unsupported && connection.getContainer().isConnected()) {
                refresh();
            }
        });
        autoRefresh.setMaximumSize(autoRefresh.getPreferredSize());
        toolBar.add(autoRefresh);
        JLabel readOnly = new JLabel(MaintenanceViewMessages.maintview_repl_read_only);
        readOnly.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, 0));
        readOnly.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        toolBar.add(readOnly);

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
     * Reads the replication status (connecting first if needed).
     */
    void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        refreshAction.setEnabled(false);
        statusLabel.setText(connection.getContainer().isConnected() ? MaintenanceViewMessages.maintview_repl_reading : ConnectionMessages.common_connecting);
        statusLabel.setToolTipText(null);
        connection.run("Read replication status", (monitor, session, profile) -> { //$NON-NLS-1$
            ReplicationDialect dialect = ReplicationDialect.forDataSource(session.getDataSource());
            if (dialect == null || !(session instanceof JDBCSession jdbcSession)) {
                return null;
            }
            monitor.subTask(MaintenanceViewMessages.maintview_repl_reading);
            return dialect.read(monitor, jdbcSession);
        }, this::showReport, error -> {
            loading = false;
            refreshAction.setEnabled(true);
            statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
            statusLabel.setToolTipText(ErrorText.describeLine(error));
            log.debug("Error reading replication status", error); //$NON-NLS-1$
        });
    }

    private void showReport(@Nullable ReplicationReport report) {
        loading = false;
        refreshAction.setEnabled(true);
        if (report == null) {
            unsupported = true;
            refreshAction.setEnabled(false);
            autoRefresh.setEnabled(false);
            noticeBar.setNotice(NLS.bind(MaintenanceViewMessages.maintview_repl_not_supported, connection.getContainer().getDriver().getName()));
            statusLabel.setText(" "); //$NON-NLS-1$
            return;
        }
        noticeBar.setNotice(report.notices().isEmpty() ? null : String.join("\n", report.notices())); //$NON-NLS-1$
        gridTabs.showGrids(report.grids());
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_updated,
            NLS.bind(MaintenanceViewMessages.maintview_status_lists, report.grids().size()), time));
        statusLabel.setToolTipText(null);
    }

    void dispose() {
        autoRefresh.dispose();
    }
}
