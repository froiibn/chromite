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
package com.seizia.chromite.runtime;

import com.seizia.chromite.model.DBPDataSourceContainer;
import org.jkiss.code.NotNull;

/**
 * Sessions that run SQL on the JDBC connection directly, so the query manager has no record of their statements and
 * transactions (the AI agent's own sessions). The idle connection close asks this service so that it neither cuts a
 * running statement nor discards an open transaction it cannot see.
 *
 * 설계: 유휴 연결 닫기({@code DataSourceMonitorJob})는 모델 번들에 있고 AI 도우미의 세션 기록({@code AgentSql})은 그 위 번들에
 * 있다. 모델이 위 번들을 알 수 없으므로 서비스({@code com.seizia.chromite.service} 확장)로 묻는다. 서비스가 없으면(AI 도구 번들이 없으면)
 * 쿼리 관리자 밖의 세션도 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 */
public interface DBServiceUnrecordedSessions {

    /**
     * One of these sessions runs a statement on the connection, or left a transaction open on it.
     */
    boolean isConnectionInUse(@NotNull DBPDataSourceContainer container);

    /**
     * When one of these sessions last finished work on the connection, or 0 when none did.
     */
    long getLastUseTime(@NotNull DBPDataSourceContainer container);
}
