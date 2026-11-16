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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.plan.DBCPlan;

import java.util.List;

/**
 * Actual execution plans of the statements a script runs, read while it runs: each statement runs once, in the
 * script's own session and transaction. A data source provides it as an adapter.
 *
 * 설계: {@link com.seizia.chromite.model.exec.plan.DBCQueryPlanner}는 계획만 따로 구하는 API라, 실행하면서 실제 계획을 받는
 * 방식(문장을 한 번만 실행)을 나타낼 수 없다. DB마다 실제 계획을 받는 때가 달라 {@link Run}에 세 자리를 두었다.
 * <ul>
 *     <li>결과 집합으로 받음(SQL Server {@code SET STATISTICS XML ON}): {@link Run#isPlanResultSet}, {@link Run#parsePlan}.</li>
 *     <li>문장이 열려 있는 동안 받음(Altibase {@code EXPLAIN PLAN = ON}: 드라이버가 그 문장의 계획을 줌): {@link Run#readOpenStatementPlan}.</li>
 *     <li>문장을 닫은 뒤 같은 세션에서 읽음(Oracle {@code statistics_level = ALL}: 커서를 닫아야 행 원천 통계가 끝남):
 *     {@link Run#readStatementPlan}. 커서를 찾는 데 필요한 것(Oracle SQL_ID)은 열려 있을 때 {@link Run#readOpenStatementPlan}에서
 *     기억해 둘 수 있다.</li>
 * </ul>
 * 켜기 전 세션 설정은 실행마다 다르므로 {@link #startRun}이 그 값을 담은 {@link Run}을 돌려주고, {@link Run#finish}가 실행이 끝날 때
 * (오류·취소 포함) 그 값으로 되돌린다. 세션 설정만 바꾸므로 트랜잭션은 건드리지 않는다(커밋·롤백 없음).
 * 계획 노드는 DBeaver 계획 모델({@link DBCPlan})이라 계획 화면은 하나다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: Swing SQL 편집기의 "Include Actual Execution Plan" (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-08 켜는 문장·끄는 문장 목록을 {@link #startRun}/{@link Run}으로 바꿈: Altibase(드라이버 API로 켜고 문장이 열려 있을 때 읽음)와
 *   Oracle(세션 설정을 읽어 두었다 되돌리고, 문장을 닫은 뒤 읽음)은 SQL 문장 목록으로 나타낼 수 없다
 *   (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 * - 2026-11-12 {@link Run#readOpenStatementPlan}이 닫은 뒤 읽을 계획을 위해 기억만 할 수 있음을 적음(Oracle SQL_ID, 앱 변경 #58)
 */
public interface SQLActualPlanProvider {

    /**
     * Turns actual plans on for the session that runs the script, before its first statement.
     *
     * @throws DBException when they can't be turned on; the session is then as it was
     */
    @NotNull
    Run startRun(@NotNull DBCSession session) throws DBException;

    /**
     * Actual plans of one script run. Its methods are called in the job thread, on the session of the run.
     */
    interface Run {

        /**
         * Whether a result set with these column labels is a plan rather than query data.
         */
        default boolean isPlanResultSet(@NotNull List<String> columnLabels) {
            return false;
        }

        /**
         * Parses a plan result set's value. The plan's query string is the statement it describes when the plan data
         * names it, else {@code query}.
         *
         * @param query    text that was executed (may be a batch of several statements)
         * @param planData value of the plan result set
         */
        @NotNull
        default DBCPlan parsePlan(@NotNull String query, @NotNull String planData) throws DBException {
            throw new DBException("This connection returns no plan result sets");
        }

        /**
         * The plan of a statement that ran and whose results were read, while the statement is still open. A provider
         * that reads the plan after the statement is closed may remember here what identifies it, and return null.
         *
         * @return null when the statement has no plan (for example DDL), or when it is read later
         */
        @Nullable
        default DBCPlan readOpenStatementPlan(
            @NotNull DBCSession session,
            @NotNull DBCStatement statement,
            @NotNull String query
        ) throws DBException {
            return null;
        }

        /**
         * The plan of a statement that ran without an error and was closed, read before anything else runs on the session.
         *
         * @return null when the statement has no plan (for example DDL or a PL/SQL block)
         */
        @Nullable
        default DBCPlan readStatementPlan(@NotNull DBCSession session, @NotNull String query) throws DBException {
            return null;
        }

        /**
         * Restores the session as it was before {@link SQLActualPlanProvider#startRun}, also when the script failed or
         * was canceled.
         */
        void finish(@NotNull DBCSession session) throws DBException;
    }
}
