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
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;

/**
 * Front-end side of {@link SQLQueryExecuteJob}: result receivers, user interaction and editor settings.
 *
 * 설계: SQL 실행 로직(SQLQueryExecuteJob)이 SWT 편집기와 Swing 편집기 양쪽에서 쓰이도록,
 * 실행 중 UI가 개입하는 지점(결과 수신기, 확인/오류 대화상자, 편집기 설정값)을 이 인터페이스 하나로 모았다.
 * 결과 수신기({@link #getDataReceiver})만 필수이고 나머지는 기본 구현이 있다.
 * 대화상자 기본 구현은 {@link DBPPlatformUI}(DBWorkbench.getPlatformUI())를 쓰므로 Swing은 DBPPlatformUI 구현만으로 동작한다.
 * 설정값 기본 구현은 SWT 편집기 설정(SQLPreferenceConstants, ResultSetPreferences)의 기본값과 같다.
 * SWT 편집기는 SQLQueryJob 안의 구현에서 기존과 같은 시점에 같은 설정과 대화상자를 쓴다.
 * 설정값을 실행 시점마다 묻는 이유: SQLQueryJob은 단일 쿼리 결과를 새로 고칠 때 같은 Job을 다시 쓰므로,
 * 그 사이에 바뀐 설정이 다음 실행에 반영되어야 한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성. SQLQueryJob의 UI 의존 부분을 분리(docs/chromite/swing-frontend.md "쿼리 실행 계층").
 */
public interface SQLQueryExecuteHandler {

    /**
     * Answer of {@link #confirmQueryExecution}.
     */
    enum QueryConfirmation {
        /** Execute this query. */
        YES,
        /** Skip this query and continue with the next one. */
        NO,
        /** Execute this query and do not ask again during this job. */
        YES_TO_ALL,
        /** Stop the script. */
        CANCEL
    }

    // Results

    /**
     * Returns the receiver for the given result set of the query, or null to skip fetching it.
     *
     * @param query           executing query
     * @param resultSetNumber zero-based result set number within the job
     */
    @Nullable
    DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber);

    /**
     * Returns the receiver for the execution statistics pseudo result set, or null to skip it.
     * Called only when {@link #isShowExecutionResult} is true.
     */
    @Nullable
    default DBDDataReceiver getStatisticsDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
        return getDataReceiver(query, resultSetNumber);
    }

    /**
     * Whether the results consumer works in "smart commit" mode
     * (switch to manual commit on the first modifying statement, see {@code DBExecUtils.checkSmartAutoCommit}).
     */
    default boolean isSmartAutoCommit() {
        return false;
    }

    // User interaction (called from the job thread)

    /**
     * Asks whether a modifying query may run on a connection type that requires confirmation.
     *
     * @param scriptMode true if more queries follow (YES_TO_ALL and CANCEL make sense)
     */
    @NotNull
    default QueryConfirmation confirmQueryExecution(@NotNull SQLQuery query, boolean scriptMode) {
        boolean confirmed = DBWorkbench.getPlatformUI().confirmAction(
            "Confirm query execution",
            "Do you confirm query execution?\n\n" + query.getText(),
            true);
        return confirmed ? QueryConfirmation.YES : QueryConfirmation.CANCEL;
    }

    /**
     * Asks what to do after a query failed.
     *
     * @param task  task description including the first error message
     * @param queue true if more queries follow
     */
    @NotNull
    default DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
        return DBWorkbench.getPlatformUI().showErrorStopRetryIgnore(task, error, queue);
    }

    /**
     * Asks whether to keep fetching after a single query returned {@code resultSetLimit} result sets.
     */
    default boolean confirmFetchManyResultSets(int resultSetLimit) {
        return DBWorkbench.getPlatformUI().confirmAction(
            "Query execution returned too many result sets",
            "Query had returned more than " + resultSetLimit + " result sets. Are you sure you want to open more?",
            true);
    }

    // Editor settings (read at the moment they are needed)

    /**
     * Whether the executed script is an included script (errors are then reported in queue mode).
     */
    default boolean isIncludedScript() {
        return false;
    }

    /**
     * Execution controller passed to {@link org.jkiss.dbeaver.model.exec.DBCExecutionSource}.
     */
    @Nullable
    default Object getExecutionController() {
        return null;
    }

    /**
     * Statement timeout in seconds, 0 for none.
     */
    default int getStatementTimeout() {
        return 0;
    }

    /**
     * Whether to invalidate the connection once before the first query of the job.
     */
    default boolean isInvalidateBeforeExecute() {
        return false;
    }

    /**
     * Number of result sets of one query after which {@link #confirmFetchManyResultSets} is asked.
     */
    default int getMaxResultSetsPerQuery() {
        return 20;
    }

    /**
     * Whether to keep the statement and cursors open after fetch (single query on a thread-safe driver only).
     */
    default boolean isKeepStatementOpen() {
        return false;
    }

    /**
     * Whether to produce the execution statistics pseudo result set at the end of the job.
     *
     * @param resultSetCount number of result sets fetched by the job
     * @param statistics     job statistics
     */
    default boolean isShowExecutionResult(int resultSetCount, @NotNull DBCStatistics statistics) {
        return false;
    }

    /**
     * Result set name used when it can not be derived from the query or metadata.
     */
    @NotNull
    default String getDefaultResultSetName() {
        return "Results";
    }

    /**
     * Name of the statistics pseudo result set of a multi-statement script.
     */
    @NotNull
    default String getStatisticsResultSetName() {
        return "Statistics";
    }
}
