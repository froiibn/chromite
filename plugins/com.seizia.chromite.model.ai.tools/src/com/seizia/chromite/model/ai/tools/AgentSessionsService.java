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
package com.seizia.chromite.model.ai.tools;

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.runtime.DBServiceUnrecordedSessions;
import org.jkiss.code.NotNull;

/**
 * Tells the idle connection close about the AI agent's sessions, whose statements the query manager does not record.
 *
 * 설계: {@link AgentSql}는 JDBC 연결을 직접 써서 쿼리 관리자에 기록이 없다. 이 서비스가 없으면 유휴 연결 닫기가 에이전트의
 * 열린 트랜잭션을 보지 못해 연결을 닫으며 묻지 않고 롤백하게 된다(사용자 규칙 "트랜잭션 안전"). plugin.xml의
 * {@code com.seizia.chromite.service} 확장으로 등록한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 */
public class AgentSessionsService implements DBServiceUnrecordedSessions {

    @Override
    public boolean isConnectionInUse(@NotNull DBPDataSourceContainer container) {
        return AgentSql.isInUse(container);
    }

    @Override
    public long getLastUseTime(@NotNull DBPDataSourceContainer container) {
        return AgentSql.getLastUseTime(container);
    }
}
