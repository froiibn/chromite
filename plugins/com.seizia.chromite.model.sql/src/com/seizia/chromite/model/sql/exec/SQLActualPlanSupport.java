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
package com.seizia.chromite.model.sql.exec;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;

/**
 * Whether the data sources of a connection give {@link SQLActualPlanProvider}, known before connecting. A data source
 * provider implements it when its data sources can return that adapter; any other provider's connections give no actual plans.
 *
 * 설계: 실제 계획 어댑터는 데이터 소스가 만들고 데이터 소스는 연결할 때 생기므로, 연결 전에는 어댑터로 물을 수 없다. 어떤 데이터 소스 클래스가
 * 생기는지는 드라이버의 데이터 소스 제공자(일반 JDBC 제공자는 드라이버의 메타 모델)가 정하므로 그 제공자가 답한다. 어댑터를 주는 데이터 소스는
 * SQL Server(Babelfish 제외, 드라이버로 가림), Oracle(그 하위 클래스 YashanDB 포함), Altibase(일반 JDBC 메타 모델)뿐이다. 그래서 이 선언이 없는
 * 제공자는 실제 계획이 없다고 본다. 어댑터를 주는 데이터 소스를 새로 만들면 그 제공자가 이것을 구현해야 한다.
 * 연결해야 알 수 있는 조건(Oracle V$ 권한, SQL Server SHOWPLAN 권한)은 여기서 보지 않고 실행할 때 {@link SQLActualPlanProvider#startRun}이 알린다.
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 실제 계획이 없는 DB에서 Actual Plan 토글을 연결 전부터 끔(앱 변경 #70, 사용자 결정 "처음부터 비활성")
 *   (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글 점검")
 */
public interface SQLActualPlanSupport {

    /**
     * Whether a data source of this connection would give {@link SQLActualPlanProvider}. Reads only the connection's
     * settings and driver: it does not connect.
     */
    boolean isActualPlanSupported(@NotNull DBPDataSourceContainer container);

    /**
     * Asks the connection's data source provider ({@link SQLActualPlanSupport}); a provider without it gives no actual plans.
     */
    static boolean isSupported(@NotNull DBPDataSourceContainer container) {
        return container.getDriver().getDataSourceProvider() instanceof SQLActualPlanSupport support
            && support.isActualPlanSupported(container);
    }
}
