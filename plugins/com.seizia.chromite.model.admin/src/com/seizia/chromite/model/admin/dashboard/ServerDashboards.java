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
package com.seizia.chromite.model.admin.dashboard;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.dashboard.DBDashboardDataType;
import org.jkiss.dbeaver.model.dashboard.registry.DashboardItemConfiguration;
import org.jkiss.dbeaver.model.dashboard.registry.DashboardRegistry;
import org.jkiss.utils.ArrayUtils;

import java.util.List;

/**
 * 연결 하나에 그릴 수 있는 서버 대시보드 차트 목록.
 *
 * 설계: 차트 정의는 DBeaver 대시보드 등록부({@link DashboardRegistry}, 확장 점 {@code org.jkiss.dbeaver.dashboard})를 그대로 쓴다.
 * DBeaver(PostgreSQL, MySQL)와 Chromite({@code plugin.xml}: SQL Server, 잠금·대기·캐시 차트)의 정의, 사용자가 SWT 제품에서 만든
 * {@code dashboards.xml}의 사용자 정의 차트가 모두 여기서 나온다. SWT 대시보드 보기({@code DashboardListViewer})처럼 연결의 드라이버로 고른다.
 * Swing 화면은 시계열 차트만 그리므로 질의가 없는 항목(웹 페이지를 보이는 {@code provided} 형식)은 뺀다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public final class ServerDashboards {

    private ServerDashboards() {
    }

    /**
     * Time series charts defined for the driver of the connection, in registry order.
     */
    @NotNull
    public static List<DashboardItemConfiguration> getItems(@NotNull DBPDataSourceContainer container) {
        return DashboardRegistry.getInstance().getDashboardItems(null, container, false).stream()
            .filter(ServerDashboards::isSupported)
            .toList();
    }

    private static boolean isSupported(@NotNull DashboardItemConfiguration item) {
        if (item.getDataType() != DBDashboardDataType.timeseries) {
            return false;
        }
        if (item.getMapQuery() != null) {
            return !ArrayUtils.isEmpty(item.getMapKeys()) || item.getMapFormulaExpr() != null;
        }
        return !item.getQueries().isEmpty();
    }
}
