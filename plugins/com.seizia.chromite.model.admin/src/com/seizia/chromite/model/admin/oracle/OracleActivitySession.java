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
package com.seizia.chromite.model.admin.oracle;

import com.seizia.chromite.ext.oracle.model.session.OracleServerSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.Nullable;

import java.sql.ResultSet;

/**
 * Activity Monitor의 Oracle 세션 한 행: DBeaver {@link OracleServerSession}에 막는 세션과 트랜잭션 경과 시간을 더했다.
 *
 * 설계: DBeaver 세션에는 SQL, 상태, 대기 이벤트, 클라이언트 호스트·프로그램이 이미 있고 "누가 막는가"와 "트랜잭션을 얼마나 열어 두었나"가 없다.
 * upstream 클래스를 고치지 않고 상속해, {@link OracleActivitySessionManager}의 질의가 더한 열({@code BLOCKING_SESSION},
 * {@code XACT_SECONDS})을 읽는다.
 * <p>
 * 세션 번호({@link #getSessionId()}): DBeaver는 인스턴스 번호가 1이면 '@1'을 빼서 'SID,SERIAL#'만 쓴다. RAC에서 2번 인스턴스에 연결한 채
 * 1번 인스턴스의 세션을 끝내면 이 문장은 연결한 인스턴스에서 그 SID를 찾는다. 그래서 인스턴스가 둘 이상이면 언제나 '@INST_ID'를 붙인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Oracle 세션·잠금 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 RAC에서 세션 번호에 언제나 인스턴스를 붙임 (docs/chromite/swing-admin-tools.md "Oracle RAC와 쿼리 취소")
 */
public class OracleActivitySession extends OracleServerSession {

    @Nullable
    private final Long blockingSession;
    @Nullable
    private final Long transactionSeconds;
    private final boolean multiInstance;

    /**
     * @param multiInstance the database has more than one instance (RAC)
     */
    public OracleActivitySession(ResultSet dbResult, boolean multiInstance) {
        super(dbResult);
        this.blockingSession = JDBCUtils.safeGetLongNullable(dbResult, "BLOCKING_SESSION"); //$NON-NLS-1$
        this.transactionSeconds = JDBCUtils.safeGetLongNullable(dbResult, "XACT_SECONDS"); //$NON-NLS-1$
        this.multiInstance = multiInstance;
    }

    /**
     * 'SID,SERIAL#' for {@code ALTER SYSTEM KILL/DISCONNECT SESSION} and {@code CANCEL SQL}; on RAC always with ',@INST_ID'.
     */
    @Override
    public String getSessionId() {
        return multiInstance ? sid + "," + serial + ",@" + getInstId() : super.getSessionId(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Nullable
    @Property(category = CAT_WAIT, viewable = true, order = 43)
    public Long getBlockingSession() {
        return blockingSession;
    }

    @Nullable
    @Property(category = CAT_SESSION, viewable = true, order = 12)
    public Long getTransactionSeconds() {
        return transactionSeconds;
    }
}
