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

import com.seizia.chromite.ui.swing.internal.AdminViewMessages;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Disk Usage·Server Logs·Maintenance·Replication 명령: Tools 메뉴와 Object Explorer 오른쪽 클릭 메뉴의 항목.
 *
 * 설계: 네 화면은 Activity Monitor 문서의 탭이라 여는 길은 기존 "Activity Monitor를 이 탭으로 열기"다. 여러 작업이 함께 고치는
 * {@code MainFrame}과 {@code ObjectExplorerPanel}에는 이 클래스를 부르는 줄만 두어 병합 충돌을 줄인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량·서버 로그 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 Maintenance·Replication 탭 명령을 더함(DB 관리 축 4·6, docs/chromite/swing-admin-tools.md)
 */
final class AdminViewCommands {

    private AdminViewCommands() {
    }

    /**
     * Actions that open the tabs; {@code openTab} receives the tab index of {@link ActivityMonitorPanel}.
     */
    @NotNull
    static List<Action> createActions(@NotNull IntConsumer openTab) {
        return List.of(
            createAction(AdminViewMessages.admin_space_command, AdminViewMessages.admin_space_command_tip,
                () -> openTab.accept(ActivityMonitorPanel.DISK_USAGE_TAB)),
            createAction(AdminViewMessages.admin_logs_command, AdminViewMessages.admin_logs_command_tip,
                () -> openTab.accept(ActivityMonitorPanel.SERVER_LOGS_TAB)),
            createAction(MaintenanceViewMessages.maintview_maint_command, MaintenanceViewMessages.maintview_maint_command_tip,
                () -> openTab.accept(ActivityMonitorPanel.MAINTENANCE_TAB)),
            createAction(MaintenanceViewMessages.maintview_repl_command, MaintenanceViewMessages.maintview_repl_command_tip,
                () -> openTab.accept(ActivityMonitorPanel.REPLICATION_TAB)));
    }

    /**
     * Adds the commands to a menu.
     */
    static void addMenuItems(@NotNull JMenu menu, @NotNull IntConsumer openTab) {
        for (Action action : createActions(openTab)) {
            menu.add(action);
        }
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String toolTip, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, toolTip);
        return action;
    }
}
