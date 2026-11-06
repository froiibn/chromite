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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.exec.plan.DBCPlan;

import java.util.List;

/**
 * Actual execution plans that the server returns while the statements run, as an extra result set after each
 * statement (SQL Server {@code SET STATISTICS XML ON}). A data source provides it as an adapter.
 *
 * 설계: {@link com.seizia.chromite.model.exec.plan.DBCQueryPlanner}는 계획만 따로 구하는 API라, 실행하면서 실제 계획을 받는
 * 방식(문장을 한 번만 실행)을 나타낼 수 없다. 그래서 "실행 전에 켜는 문장, 실행 뒤에 끄는 문장, 어떤 결과 집합이 계획인지,
 * 계획 데이터를 계획 노드로 바꾸기"를 이 어댑터 하나로 모았다. 편집기는 이 어댑터가 있는 연결에서만 실행 중 계획을 받고,
 * 없는 연결은 계획기(EXPLAIN ANALYZE 같은 설정)로 구한다. 계획 노드는 DBeaver 계획 모델({@link DBCPlan})이라 계획 화면은 하나다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: Swing SQL 편집기의 "Include Actual Execution Plan" (docs/chromite/swing-sql-editor-plus.md)
 */
public interface SQLActualPlanProvider {

    /**
     * Statements executed on the connection before the script (each one alone).
     */
    @NotNull
    List<String> getEnableStatements();

    /**
     * Statements executed on the connection after the script, also when it failed or was canceled.
     */
    @NotNull
    List<String> getDisableStatements();

    /**
     * Whether a result set with these column labels is a plan rather than query data.
     */
    boolean isPlanResultSet(@NotNull List<String> columnLabels);

    /**
     * Parses the plan of one statement. The plan's query string is the statement it describes when the plan data names
     * it, else {@code query}.
     *
     * @param query    text that was executed (may be a batch of several statements)
     * @param planData value of the plan result set
     */
    @NotNull
    DBCPlan parsePlan(@NotNull String query, @NotNull String planData) throws DBException;
}
