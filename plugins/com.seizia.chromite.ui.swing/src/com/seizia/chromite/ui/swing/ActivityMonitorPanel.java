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
import com.seizia.chromite.ui.swing.internal.AdminViewMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.DashboardMessages;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;

import javax.swing.*;
import java.awt.*;

/**
 * Activity Monitor 문서 탭: 연결 하나의 Overview(서버 대시보드), Processes(세션), Locks(잠금 대기), Disk Usage(공간 사용량),
 * Server Logs(서버 로그), Maintenance(유지 관리), Replication(복제).
 *
 * 설계: SSMS Activity Monitor처럼 서버 활동을 문서 탭 하나에 모은다. SWT에서는 세션 관리자({@code AbstractSessionEditor})와
 * 잠금 관리자({@code AbstractLockEditor})가 따로 여는 편집기다. 두 탭은 서버 연결({@link AdminConnection}, 격리된 실행 컨텍스트 하나)을
 * 함께 써서 창 하나가 서버에 연결 하나만 더한다. 각 탭은 처음 보일 때 읽고, 그 뒤로는 자기 자동 새로 고침 간격을 따른다.
 * SSMS처럼 서버 활동 차트(개요, {@link DashboardPanel})를 첫 탭에 둔다. SWT에서는 따로 여는 대시보드 보기({@code DataSourceDashboardView})다.
 * 공간 사용량({@link DiskUsagePanel})과 서버 로그({@link ServerLogPanel})도 같은 서버 연결을 쓰는 탭으로 둔다(새 문서 종류를 만들지 않고
 * Tools·Object Explorer 메뉴에서 이 문서를 그 탭으로 연다, {@link AdminViewCommands}). 유지 관리({@link MaintenancePanel})와 복제 상태
 * ({@link ReplicationPanel})도 같은 방식의 탭이다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 개요 탭(서버 대시보드)을 첫 탭으로 더함 (docs/chromite/swing-dashboard.md)
 * - 2026-11-07 탭 스타일을 밀도 B 값({@link UiSizes#TOOL_TABS_STYLE}) 하나로: 두 브랜치를 합칠 때 남은 옛 고정값(tabHeight 32) 줄 삭제
 * - 2026-11-07 Disk Usage·Server Logs 탭을 더함(DB 관리 축 3·5, docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 Maintenance·Replication 탭을 더함(DB 관리 축 4·6, docs/chromite/swing-admin-tools.md)
 */
final class ActivityMonitorPanel extends JPanel implements ToolDocument {

    static final int OVERVIEW_TAB = 0;
    static final int PROCESSES_TAB = 1;
    static final int LOCKS_TAB = 2;
    static final int DISK_USAGE_TAB = 3;
    static final int SERVER_LOGS_TAB = 4;
    static final int MAINTENANCE_TAB = 5;
    static final int REPLICATION_TAB = 6;

    private final DBPDataSourceContainer container;
    private final AdminConnection connection;
    private final SessionManagerPanel sessionPanel;
    private final LockManagerPanel lockPanel;
    private final DashboardPanel dashboardPanel;
    private final DiskUsagePanel diskUsagePanel;
    private final ServerLogPanel serverLogPanel;
    private final MaintenancePanel maintenancePanel;
    private final ReplicationPanel replicationPanel;
    private final JTabbedPane tabs = new JTabbedPane();
    private final boolean[] loaded = new boolean[7];

    ActivityMonitorPanel(@NotNull DBPDataSourceContainer container, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        super(new BorderLayout());
        this.container = container;
        connection = new AdminConnection(container);
        sessionPanel = new SessionManagerPanel(connection, queryWindows);
        lockPanel = new LockManagerPanel(connection);
        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        dashboardPanel = new DashboardPanel(connection);
        tabs.addTab(DashboardMessages.dashboard_tab, UiIcons.get("activity-monitor"), dashboardPanel); //$NON-NLS-1$
        tabs.addTab(ConnectionMessages.activity_tab_processes, UiIcons.get("user"), sessionPanel); //$NON-NLS-1$
        tabs.addTab(ConnectionMessages.activity_tab_locks, UiIcons.get("warning"), lockPanel); //$NON-NLS-1$
        diskUsagePanel = new DiskUsagePanel(connection);
        serverLogPanel = new ServerLogPanel(connection);
        tabs.addTab(AdminViewMessages.admin_space_tab, UiIcons.get("database"), diskUsagePanel); //$NON-NLS-1$
        tabs.addTab(AdminViewMessages.admin_logs_tab, UiIcons.get("messages"), serverLogPanel); //$NON-NLS-1$
        maintenancePanel = new MaintenancePanel(connection);
        replicationPanel = new ReplicationPanel(connection);
        tabs.addTab(MaintenanceViewMessages.maintview_maint_tab, UiIcons.get("settings"), maintenancePanel); //$NON-NLS-1$
        tabs.addTab(MaintenanceViewMessages.maintview_repl_tab, UiIcons.get("connection"), replicationPanel); //$NON-NLS-1$
        tabs.addChangeListener(e -> loadSelectedTab());
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * Shows a tab and reads it the first time.
     */
    void showTab(int index) {
        if (tabs.getSelectedIndex() != index) {
            tabs.setSelectedIndex(index);
        }
        loadSelectedTab();
    }

    private void loadSelectedTab() {
        int index = tabs.getSelectedIndex();
        if (index < 0 || loaded[index]) {
            return;
        }
        loaded[index] = true;
        if (index == OVERVIEW_TAB) {
            dashboardPanel.refresh();
        } else if (index == PROCESSES_TAB) {
            sessionPanel.refresh();
        } else if (index == LOCKS_TAB) {
            lockPanel.refresh();
        } else if (index == DISK_USAGE_TAB) {
            diskUsagePanel.refresh();
        } else if (index == SERVER_LOGS_TAB) {
            serverLogPanel.refresh();
        } else if (index == MAINTENANCE_TAB) {
            maintenancePanel.refresh();
        } else {
            replicationPanel.refresh();
        }
    }

    @NotNull
    DBPDataSourceContainer getContainer() {
        return container;
    }

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(ConnectionMessages.activity_title, container.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(ConnectionMessages.activity_tooltip, container.getName());
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("user"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        sessionPanel.dispose();
        lockPanel.dispose();
        dashboardPanel.dispose();
        replicationPanel.dispose();
        connection.close();
    }
}
