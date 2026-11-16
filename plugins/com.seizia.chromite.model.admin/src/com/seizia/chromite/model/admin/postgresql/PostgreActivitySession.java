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
package com.seizia.chromite.model.admin.postgresql;

import com.seizia.chromite.ext.postgresql.model.session.PostgreSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * Activity Monitor의 PostgreSQL 세션 한 행: DBeaver {@link PostgreSession}에 대기, 막는 세션, 트랜잭션 경과 시간을 더했다.
 *
 * 설계: SQL Server 세션 목록처럼 "무엇을 기다리는가, 누가 막는가, 트랜잭션을 얼마나 열어 두었나"를 한 표에서 보이려 한다.
 * DBeaver 세션 클래스는 upstream 파일이라 고치지 않고 상속한다. 값은 {@link PostgreActivitySessionManager}의 질의가 더한 열
 * ({@code blocked_by}, {@code xact_seconds})과 {@code pg_stat_activity}의 대기 열({@code wait_event_type}, {@code wait_event})이다.
 * 클라이언트 호스트는 DBeaver에서 표에 없던(viewable=false) 속성이라, 표에 보이도록 같은 이름으로 다시 선언한다(열 이름은 부모 번들의 것을 쓴다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreActivitySession extends PostgreSession {

    private static final String CAT_CLIENT = "Client"; //$NON-NLS-1$
    private static final String CAT_WAIT = "Wait"; //$NON-NLS-1$

    @Nullable
    private final String waitEvent;
    @Nullable
    private final String blockedBy;
    @Nullable
    private final Long transactionSeconds;
    @Nullable
    private final String backendType;

    public PostgreActivitySession(ResultSet dbResult) {
        super(dbResult);
        String waitType = JDBCUtils.safeGetString(dbResult, "wait_event_type"); //$NON-NLS-1$
        String event = JDBCUtils.safeGetString(dbResult, "wait_event"); //$NON-NLS-1$
        this.waitEvent = CommonUtils.isEmpty(waitType) ? event : CommonUtils.isEmpty(event) ? waitType : waitType + ": " + event; //$NON-NLS-1$
        this.blockedBy = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(dbResult, "blocked_by")); //$NON-NLS-1$
        this.transactionSeconds = JDBCUtils.safeGetLongNullable(dbResult, "xact_seconds"); //$NON-NLS-1$
        this.backendType = JDBCUtils.safeGetString(dbResult, "backend_type"); //$NON-NLS-1$
    }

    @Override
    @Property(viewable = true, category = CAT_CLIENT, order = 3)
    public String getClientHost() {
        return super.getClientHost();
    }

    @Nullable
    @Property(viewable = true, category = CAT_WAIT, order = 8)
    public String getWaitEvent() {
        return waitEvent;
    }

    @Nullable
    @Property(viewable = true, category = CAT_WAIT, order = 9)
    public String getBlockedBy() {
        return blockedBy;
    }

    @Nullable
    @Property(viewable = true, order = 10)
    public Long getTransactionSeconds() {
        return transactionSeconds;
    }

    @Nullable
    @Property(order = 11)
    public String getBackendType() {
        return backendType;
    }
}
