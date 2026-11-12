/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.impl.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.*;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Execute batch.
 * Can be used in JDBC or any other underlying DB APIs
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: NULL인 열이 앞 행과 다르면 일괄 처리가 아니어도(행마다 실행, 스크립트) 문장을 새로 준비함.
 *   일괄 처리를 끈 데이터 전송에서 앞 행의 값이 NULL 열에 들어가고 뒤 매개변수가 밀렸다 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-08 Chromite: 실행이 실패해도 행을 버리지 않음. 일괄 처리를 쓰지 않을 때(한 행씩) 실패하면 값 목록을 비워서,
 *   다시 시도와 자동 복구가 빈 목록을 실행하고 실패한 행(과 그 뒤 행)이 조용히 빠졌다. 이제 이미 실행한 앞 행 수
 *   ({@link #appliedRows})와 실패한 행의 범위를 기억해, 다음 실행은 실패한 행부터 하고, 건너뛰기({@link #skipFailedRows})는 실패한 행만
 *   버리며, 롤백된 뒤({@link #restoreAppliedRows})에는 앞 행부터 다시 한다 (docs/chromite/swing-data-transfer.md "한 행씩 넣기의 다시 시도와 복구")
 * - 2026-11-12 Chromite: 실패한 실행의 행 범위를 호출자에게 알림({@link #getRowCount}, {@link #getAppliedRowCount},
 *   {@link #getFailedRowCount}). 자동 커밋 넣기는 연결이 끊긴 묶음의 어느 원본 행이 결과를 모르는지 알려야 한다. 여러 행 INSERT
 *   ({@code ExecuteBatchWithMultipleInsert})도 같은 기록을 남기도록 {@link #finishExecution}으로 뺌
 *   (docs/chromite/swing-data-transfer.md "연결이 끊긴 넣기")
*/
public abstract class ExecuteBatchImpl implements DBSDataManipulator.ExecuteBatch {

    private static final Log log = Log.getLog(ExecuteBatchImpl.class);

    protected final DBSAttributeBase[] attributes;
    protected final List<Object[]> values = new ArrayList<>();
    protected final DBDDataReceiver keysReceiver;
    protected final boolean reuseStatement;
    // After a failed execution: the leading rows of values it already executed (they are not run again), and the rows
    // that failed (the failing row, or the rows of the failing batch), as indexes into values
    private int appliedRows;
    private int failedFrom;
    private int failedTo;

    /**
     * Constructs new batch
     * @param attributes array of attributes used in batch
     * @param keysReceiver keys receiver (or null)
     * @param reuseStatement true if engine should reuse single prepared statement for each execution.
     */
    protected ExecuteBatchImpl(@NotNull DBSAttributeBase[] attributes, @Nullable DBDDataReceiver keysReceiver, boolean reuseStatement)
    {
        this.attributes = attributes;
        this.keysReceiver = keysReceiver;
        this.reuseStatement = reuseStatement;
    }

    @NotNull
    @Override
    public DBSDataManipulator.ExecuteBatch add(@NotNull Object[] attributeValues) throws DBCException {
        if (!ArrayUtils.isEmpty(attributes) && ArrayUtils.isEmpty(attributeValues)) {
            throw new DBCException("Bad attribute values: " + Arrays.toString(attributeValues));
        }
        values.add(attributeValues);
        return this;
    }

    @NotNull
    @Override
    public DBCStatistics execute(@NotNull DBCSession session, @NotNull Map<String, Object> options) throws DBException {
        return processBatch(session, null, options);
    }

    @NotNull
    @Override
    public void generatePersistActions(@NotNull DBCSession session, @NotNull List<DBEPersistAction> actions, @NotNull Map<String, Object> options) throws DBException {
        processBatch(session, actions, options);
    }

    @Override
    public void skipFailedRows() {
        // The rows executed before the failure are not run again either: the caller goes on (and commits) without them
        // The failed rows always follow the executed ones
        values.subList(failedFrom, failedTo).clear();
        values.subList(0, appliedRows).clear();
        appliedRows = 0;
        failedFrom = 0;
        failedTo = 0;
    }

    @Override
    public void restoreAppliedRows() {
        appliedRows = 0;
    }

    @Override
    public int getRowCount() {
        return values.size();
    }

    @Override
    public int getAppliedRowCount() {
        return appliedRows;
    }

    @Override
    public int getFailedRowCount() {
        return failedTo - failedFrom;
    }

    /**
     * Records how an execution ended, for the next one and for the caller: completed (the rows are done), or failed
     * after executing the first {@code applied} rows, on the rows from {@code failFrom} to {@code failTo} (exclusive).
     */
    protected void finishExecution(boolean completed, int applied, int failFrom, int failTo) {
        if (completed) {
            appliedRows = 0;
            failedFrom = 0;
            failedTo = 0;
        } else {
            // Kept for Retry and the recovery: they go on from the failed row
            appliedRows = applied;
            failedFrom = failFrom;
            failedTo = failTo;
        }
    }

    /**
     * Execute batch OR generate batch script.
     * @param session    session
     * @param actions    script actions. If not null then no execution will be done
     * @param options
     * @return execution statistics
     * @throws DBCException
     */
    @NotNull
    DBCStatistics processBatch(@NotNull DBCSession session, @Nullable List<DBEPersistAction> actions, Map<String, Object> options) throws DBException
    {
        //session.getProgressMonitor().subTask("Save batch (" + values.size() + ")");
        DBDValueHandler[] handlers = new DBDValueHandler[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i] instanceof DBDAttributeBinding) {
                handlers[i] = ((DBDAttributeBinding)attributes[i]).getValueHandler();
            } else {
                handlers[i] = DBUtils.findValueHandler(session, attributes[i]);
            }
        }

        boolean useBatch = session.getDataSource().getInfo().supportsBatchUpdates() && reuseStatement && !CommonUtils.toBoolean(options.get(DBSDataManipulator.OPTION_DISABLE_BATCHES));
        if (values.size() <= 1) {
            useBatch = false;
        }
        boolean skipBindValues = CommonUtils.toBoolean(options.get(DBSDataManipulator.OPTION_SKIP_BIND_VALUES));
        if (skipBindValues) {
            useBatch = false;
        }

        DBCStatistics statistics = new DBCStatistics();
        DBCStatement statement = null;
        // Rows executed so far (a failed execution's executed rows are not run again) and the rows the next step may fail on
        int applied = appliedRows;
        int failFrom = applied;
        int failTo = applied;
        boolean completed = false;

        try {
            // Here we'll try to reuse prepared statement.
            // It makes a great sense in case of data transfer where we need millions of inserts.
            // We must be aware of nulls because actual insert statements may differ depending on null values.
            // So if row nulls aren't the same as in previous row we need to prepare new statement and restart batch.
            // Quite complicated but works.
            boolean[] prevNulls = new boolean[attributes.length];
            boolean[] nulls = new boolean[attributes.length];
            int statementsInBatch = 0;

            for (int rowIndex = applied; rowIndex < values.size(); rowIndex++) {
                Object[] rowValues = values.get(rowIndex);
                if (session.getProgressMonitor().isCanceled()) {
                    break;
                }
                if (rowIndex > 0 && rowIndex % 100 == 0) {
                    //session.getProgressMonitor().subTask("Save batch (" + rowIndex + " of " + values.size() + ")");
                }

                boolean reuse = !skipBindValues && reuseStatement;
                if (reuse) {
                    for (int i = 0; i < rowValues.length; i++) {
                        nulls[i] = DBUtils.isNullValue(rowValues[i]);
                    }
                    // Checked against the prepared statement, not the batch: without batches (rows executed one by one,
                    // or a script) the statement of other NULL columns was reused, kept the previous row's value for the
                    // new NULL column and shifted the following parameters
                    if (!Arrays.equals(prevNulls, nulls) && statement != null) {
                        reuse = false;
                    }
                    System.arraycopy(nulls, 0, prevNulls, 0, nulls.length);
                    if (!reuse && statement != null) {
                        // Flush batch
                        if (actions == null && statementsInBatch > 0) {
                            failFrom = applied;
                            failTo = rowIndex;
                            flushBatch(statistics, statement);
                            applied = rowIndex;
                        }
                        DBUtils.closeSafely(statement);
                        statement = null;
                        statementsInBatch = 0;
                        reuse = true;
                    }
                }
                failFrom = rowIndex;
                failTo = rowIndex + 1;
                if (statement == null || !reuse) {
                    statement = prepareStatement(session, handlers, rowValues, options);
                    statistics.setQueryText(statement.getQueryString());
                    statistics.addStatementsCount();
                }
                try {
                    if (!skipBindValues) {
                        bindStatement(handlers, statement, rowValues);
                    }
                    if (actions == null) {
                        if (useBatch) {
                            statement.addToBatch();
                            statementsInBatch++;
                        } else {
                            // Execute each row separately
                            long startTime = System.currentTimeMillis();
                            executeStatement(statistics, statement);
                            applied = rowIndex + 1;
                            statistics.addExecuteTime(System.currentTimeMillis() - startTime);

                            long rowCount = statement.getUpdateRowCount();
                            if (rowCount >= 0) {
                                statistics.addRowsUpdated(rowCount);
                            }

                            // Read keys
                            if (keysReceiver != null) {
                                try {
                                    readKeys(statement.getSession(), statement, keysReceiver);
                                } catch (Exception e) {
                                    log.warn("Error reading auto-generated keys", e);
                                }
                            }
                        }
                    } else {
                        String queryString = formatQueryParameters(session, statement.getQueryString(), handlers, rowValues);
                        actions.add(
                            new SQLDatabasePersistAction(
                                "Execute statement",
                                queryString));
                    }
                } finally {
                    if (!reuse && !useBatch) {
                        DBUtils.closeSafely(statement);
                    }
                }
            }

            if (statementsInBatch > 0) {
                if (actions == null) {
                    failFrom = applied;
                    failTo = values.size();
                    flushBatch(statistics, statement);
                }
                DBUtils.closeSafely(statement);
                statement = null;
            }
            values.clear();
            completed = true;
        } finally {
            if (reuseStatement && statement != null) {
                DBUtils.closeSafely(statement);
            }
            finishExecution(completed, applied, failFrom, failTo);
        }

        return statistics;
    }

    protected int getNextUsedParamIndex(Object[] attributeValues, int paramIndex) {
        paramIndex++;
        // we need to skip all nullable values because they are already set in the statement
        while (paramIndex < attributeValues.length && attributeValues[paramIndex] instanceof DBDNull) {
            paramIndex++;
        }
        return paramIndex;
    }

    String formatQueryParameters(DBCSession session, String queryString, DBDValueHandler[] handlers, Object[] rowValues) {
        if (handlers.length == 0) {
            return queryString;
        }
        if (CommonUtils.isEmpty(queryString)) {
            return queryString;
        }
        int length = queryString.length();
        StringBuilder formatted = new StringBuilder(length * 2);
        int paramIndex = -1;

        for (int i = 0; i < length; i++) {
            char c = queryString.charAt(i);
            switch (c) {
                case '?': {
                    paramIndex = getNextUsedParamIndex(rowValues, paramIndex);
                    if (paramIndex >= handlers.length) {
                        log.error("Parameter index out of range (" + paramIndex + " > " + handlers.length + ")");
                        continue;
                    }
                    Object paramValue = SQLUtils.convertValueToSQL(
                        session.getDataSource(),
                        attributes[paramIndex],
                        handlers[paramIndex],
                        rowValues[paramIndex],
                        DBDDisplayFormat.NATIVE,
                        false);
                    formatted.append(paramValue);
                    continue;
                }
                case ':': {
                    // FIXME: process named parameters
                    break;
                }
                case '\'':
                case '"': {
                    formatted.append(c);
                    for (int k = i + 1; k < length; k++) {
                        char c2 = queryString.charAt(k);
                        if (c2 == c && queryString.charAt(k - 1) != '\\') {
                            i = k;
                            c = c2;
                            break;
                        } else {
                            formatted.append(c2);
                        }
                    }
                    break;
                }
            }
            formatted.append(c);
        }

        return formatted.toString();
    }

    void flushBatch(DBCStatistics statistics, DBCStatement statement) throws DBCException {
        long startTime = System.currentTimeMillis();
        long[] updatedRows = statement.executeStatementBatch();
        statistics.addExecuteTime(System.currentTimeMillis() - startTime);
        if (!ArrayUtils.isEmpty(updatedRows)) {
            for (long rows : updatedRows) {
                if (rows < 0) {
                    // In some cases (e.g. JDBC API) negative means "unknown".
                    // "Statement.SUCCESS_NO_INFO — the command was processed successfully, but the number of rows affected is unknown"
                    // But we are quite sure that it has to be 1 (because each statement inserts/deletes/updates a single row)
                    // The only exception is bulk delete  (without WHERE condition)
                    if (!ArrayUtils.isEmpty(attributes)) {
                        rows = 1;
                    }
                }
                statistics.addRowsUpdated(rows);
            }
        }
        saveExecuteWarnings(statistics, statement);
    }

    protected void executeStatement(DBCStatistics statistics, DBCStatement statement) throws DBCException {
        statement.executeStatement();
        saveExecuteWarnings(statistics, statement);
    }

    private void saveExecuteWarnings(DBCStatistics statistics, DBCStatement statement) throws DBCException {
        Throwable[] warnings = statement.getStatementWarnings();
        if (warnings != null) {
            for (Throwable w : warnings) {
                statistics.addWarning(w);
            }
        }
    }

    @Override
    public void close()
    {
    }

    private void readKeys(@NotNull DBCSession session, @NotNull DBCStatement dbStat, @NotNull DBDDataReceiver keysReceiver)
        throws DBException
    {
        DBCResultSet dbResult;
        try {
            dbResult = dbStat.openGeneratedKeysResultSet();
        }
        catch (Throwable e) {
            log.debug("Error obtaining generated keys", e); //$NON-NLS-1$
            return;
        }
        if (dbResult == null) {
            return;
        }
        try (dbResult) {
            DBDDataReceiver.startFetchWorkflow(keysReceiver, session, dbResult, -1, -1);
            while (dbResult.nextRow()) {
                keysReceiver.fetchRow(session, dbResult);
            }
        }
    }

    @NotNull
    protected abstract DBCStatement prepareStatement(@NotNull DBCSession session, DBDValueHandler[] handlers, Object[] attributeValues, Map<String, Object> options) throws DBCException;

    protected abstract void bindStatement(@NotNull DBDValueHandler[] handlers, @NotNull DBCStatement statement, Object[] attributeValues) throws DBCException;


}
