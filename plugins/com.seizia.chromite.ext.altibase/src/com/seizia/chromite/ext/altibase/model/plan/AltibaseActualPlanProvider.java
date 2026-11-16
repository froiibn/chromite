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
package com.seizia.chromite.ext.altibase.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.impl.jdbc.exec.JDBCStatementImpl;
import com.seizia.chromite.model.sql.exec.SQLActualPlanProvider;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Altibase actual plans: the session's explain plan mode is {@code EXPLAIN PLAN = ON} while the script runs, so each
 * statement executes and the driver gives its plan with the real access counts.
 *
 * 설계:
 * <ul>
 *     <li>모드는 SQL({@code ALTER SESSION SET EXPLAIN PLAN = ON})이 아니라 드라이버 API {@code setExplainPlan(byte)}로 바꾼다.
 *     드라이버가 모드를 따로 기억하고, 그 값이 꺼짐이면 {@code getExplainPlan()}이 오류(334404)를 낸다(드라이버 7.3 바이트코드로 확인).
 *     드라이버 API는 공개 인터페이스가 아니어서 예상 계획({@link AltibaseExecutionPlan})과 같이 리플렉션으로 부른다.</li>
 *     <li>켜기 전 모드는 드라이버의 {@code explainPlanMode()}(패키지 전용)로 읽어 두었다가 끝날 때(오류·취소 포함) 되돌린다.
 *     읽지 못하면 되돌릴 값을 모르므로 켜지 않는다.</li>
 *     <li>계획은 문장이 열려 있을 때만 받을 수 있다({@code getExplainPlan()}은 준비된 문장의 id로 서버에 묻는다). 그래서
 *     {@link Run#readOpenStatementPlan}에서 읽는다. 결과를 다 읽은 뒤라 ACCESS는 그 실행의 실제 접근 수다.</li>
 *     <li>모드 바꾸기는 세션 속성만 보내므로 트랜잭션을 끝내지 않는다(열린 트랜잭션에서 바꾼 뒤 다른 세션에서 미확정 값이 안 보이고 롤백되는 것을 확인).</li>
 *     <li>예상 계획(Ctrl+L)은 그대로 {@code EXPLAIN PLAN = ONLY}다({@link AltibaseConstants#PREF_EXPLAIN_PLAN_TYPE}, Options에 두지 않음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 */
public class AltibaseActualPlanProvider implements SQLActualPlanProvider {

    private static final Log log = Log.getLog(AltibaseActualPlanProvider.class);

    private static final String SET_EXPLAIN_PLAN = "setExplainPlan"; //$NON-NLS-1$
    private static final String EXPLAIN_PLAN_MODE = "explainPlanMode"; //$NON-NLS-1$
    private static final String GET_EXPLAIN_PLAN = "getExplainPlan"; //$NON-NLS-1$

    private final AltibaseDataSource dataSource;

    public AltibaseActualPlanProvider(@NotNull AltibaseDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public Run startRun(@NotNull DBCSession session) throws DBException {
        Connection connection = getConnection(session);
        byte previousMode = readExplainPlanMode(connection);
        setExplainPlanMode(session, connection, AltibaseConstants.ExplainPlan.ON.getArgValue());
        return new Run() {
            @Nullable
            @Override
            public DBCPlan readOpenStatementPlan(
                @NotNull DBCSession session,
                @NotNull DBCStatement statement,
                @NotNull String query
            ) throws DBException {
                if (!(statement instanceof JDBCStatementImpl<?> jdbcStatement)) {
                    return null;
                }
                String planText;
                try {
                    Object original = jdbcStatement.getOriginal();
                    planText = (String) original.getClass().getMethod(GET_EXPLAIN_PLAN).invoke(original);
                } catch (InvocationTargetException e) {
                    // Statements without a plan (DDL, session commands) have no plan on the server
                    log.debug("No explain plan for the statement", e.getTargetException());
                    return null;
                } catch (ReflectiveOperationException e) {
                    throw new DBException("The Altibase driver does not give statement plans (getExplainPlan)", e);
                }
                if (CommonUtils.isEmptyTrimmed(planText)) {
                    return null;
                }
                return new AltibaseExecutionPlan(dataSource, query, planText);
            }

            @Override
            public void finish(@NotNull DBCSession session) throws DBException {
                setExplainPlanMode(session, getConnection(session), previousMode);
            }
        };
    }

    @NotNull
    private static Connection getConnection(@NotNull DBCSession session) throws DBException {
        try {
            return ((JDBCSession) session).getOriginal();
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    /**
     * The driver's explain plan mode of the connection (OFF, ON or ONLY), kept by the driver since it was set last.
     */
    private static byte readExplainPlanMode(@NotNull Connection connection) throws DBException {
        for (Class<?> type = connection.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(EXPLAIN_PLAN_MODE);
                method.setAccessible(true);
                return ((Number) method.invoke(connection)).byteValue();
            } catch (NoSuchMethodException e) {
                // Declared by a super class
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new DBException("Can't read the explain plan mode of the session, so it could not be restored afterwards", e);
            }
        }
        throw new DBException("The Altibase driver does not report the explain plan mode of the session (explainPlanMode)");
    }

    private static void setExplainPlanMode(@NotNull DBCSession session, @NotNull Connection connection, byte mode) throws DBException {
        try {
            connection.getClass().getMethod(SET_EXPLAIN_PLAN, byte.class).invoke(connection, mode);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException sqlError) {
                throw new DBCException(sqlError, session.getExecutionContext());
            }
            throw new DBException("Can't set the explain plan mode of the session", e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new DBException("The Altibase driver can't set the explain plan mode (setExplainPlan)", e);
        }
    }
}
