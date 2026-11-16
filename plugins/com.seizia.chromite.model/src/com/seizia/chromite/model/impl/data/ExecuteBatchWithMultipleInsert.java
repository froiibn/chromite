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
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Inserts the rows with multi-row INSERT statements ({@code INSERT ... VALUES (...), (...)}).
 *
 * 변경 이력:
 * - 2026-11-12 Chromite: 실패해도 행을 버리지 않고 실패한 범위를 남김({@code ExecuteBatchImpl.finishExecution}). 전에는 실패한
 *   실행의 행을 모두 남긴 채 처음부터 다시 실행해, 자동 커밋이면 이미 실행한 문장의 행이 다시 시도·복구 때 두 번 들어갔고, 건너뛰기가
 *   아무 행도 빼지 못해 다시 시도와 같았다 (docs/chromite/swing-data-transfer.md "연결이 끊긴 넣기")
 */
public class ExecuteBatchWithMultipleInsert extends ExecuteInsertBatchImpl {

    private final DBCExecutionSource source;
    private DBSTable table;

    /**
     * Constructs new batch
     *
     * @param attributes     array of attributes used in batch
     * @param keysReceiver   keys receiver (or null)
     * @param reuseStatement true if engine should reuse single prepared statement for each execution.
     */
    public ExecuteBatchWithMultipleInsert(@NotNull DBSAttributeBase[] attributes, @Nullable DBDDataReceiver keysReceiver, boolean reuseStatement, @NotNull DBCSession session, @NotNull final DBCExecutionSource source, @NotNull DBSTable table) {
        super(attributes, keysReceiver, reuseStatement, session, source, table, false);
        this.source = source;
        this.table = table;
    }

    @Override
    protected void bindStatement(@NotNull DBDValueHandler[] handlers, @NotNull DBCStatement statement, Object[] attributeValues) throws DBCException {
        int paramIndex = 0;
        int handlersLength = handlers.length;
        int attributeCount = 0;
        for (Object attribute : attributeValues) {
            if (DBUtils.isPseudoAttribute(attributes[attributeCount])) {
                continue;
            }
            handlers[attributeCount].bindValueObject(statement.getSession(), statement, attributes[attributeCount], paramIndex++, attribute);
            attributeCount++;
            if (attributeCount == handlersLength) {
                attributeCount = 0;
            }
        }
    }

    @NotNull
    @Override
    protected DBCStatement prepareStatement(@NotNull DBCSession session, DBDValueHandler[] handlers, Object[] attributeValues, Map<String, Object> options) throws DBCException {
        StringBuilder queryForStatement = prepareQueryForStatement(session, handlers, attributeValues, attributes, table, true, options);
        // Execute
        DBCStatement dbStat = session.prepareStatement(DBCStatementType.QUERY, queryForStatement.toString(), false, false, keysReceiver != null);
        dbStat.setStatementSource(source);
        return dbStat;
    }

    @NotNull
    @Override
    public DBCStatistics processBatch(@NotNull DBCSession session, @Nullable List<DBEPersistAction> actions, Map<String, Object> options) throws
            DBException {
        int attributesLength = attributes.length;
        DBDValueHandler[] handlers = new DBDValueHandler[attributesLength];
        for (int i = 0; i < attributesLength; i++) {
            if (attributes[i] instanceof DBDAttributeBinding) {
                handlers[i] = ((DBDAttributeBinding) attributes[i]).getValueHandler();
            } else {
                handlers[i] = DBUtils.findValueHandler(session, attributes[i]);
            }
        }

        DBCStatistics statistics = new DBCStatistics();

        int multiRowInsertBatchSize = CommonUtils.toInt(options.get(DBSDataManipulator.OPTION_MULTI_INSERT_BATCH_SIZE), 100);
        boolean skipBindValues = CommonUtils.toBoolean(options.get(DBSDataManipulator.OPTION_SKIP_BIND_VALUES));

        int rowsCount = values.size();
        // The statements a failed execution already executed are not run again; each statement carries the rows from
        // applied to the row before the next statement starts
        int applied = getAppliedRowCount();
        int failFrom = applied;
        int failTo = applied;
        boolean completed = false;
        List<Object> multiRowInsertBatchValuesList = new ArrayList<>();
        try {
            for (int i = applied; i < rowsCount; i++) {
                if (session.getProgressMonitor().isCanceled()) {
                    break;
                }
                Object[] objects = values.get(i);
                // Execute batch if it has a suitable size, or this are the last values
                if (i == rowsCount - 1 || (i != applied && i % multiRowInsertBatchSize == 0)) {
                    // We can reuse statement, but not for the last values (their amount can be different from previous batches)
                    if (i == rowsCount - 1) {
                        Collections.addAll(multiRowInsertBatchValuesList, objects);
                        Object[] allMultiInsertValues = multiRowInsertBatchValuesList.toArray(new Object[0]);
                        failFrom = applied;
                        failTo = i + 1;
                        try (DBCStatement statement = prepareStatement(session, handlers, allMultiInsertValues, options)) {
                            bindAndFlushStatement(handlers, statistics, statement, allMultiInsertValues, skipBindValues);
                            multiRowInsertBatchValuesList.clear();
                            applied = i + 1;
                            break;
                        }
                    }
                    Object[] allMultiInsertValuesBatch = multiRowInsertBatchValuesList.toArray(new Object[0]);
                    failFrom = applied;
                    failTo = i;
                    try (DBCStatement batchStatement = prepareStatement(session, handlers, allMultiInsertValuesBatch, options)) {
                        bindAndFlushStatement(handlers, statistics, batchStatement, allMultiInsertValuesBatch, skipBindValues);
                        multiRowInsertBatchValuesList.clear();
                        applied = i;
                    }
                }
                Collections.addAll(multiRowInsertBatchValuesList, objects);
            }
            values.clear();
            completed = true;
        } finally {
            finishExecution(completed, applied, failFrom, failTo);
        }
        return statistics;
    }

    private void bindAndFlushStatement(DBDValueHandler[] handlers, DBCStatistics statistics, DBCStatement batchStatement, Object[] allMultiInsertValues, boolean skipBindValues) throws DBCException {
        statistics.setQueryText(batchStatement.getQueryString());
        statistics.addStatementsCount();
        if (!skipBindValues) {
            bindStatement(handlers, batchStatement, allMultiInsertValues);
        }
        batchStatement.addToBatch();
        flushBatch(statistics, batchStatement);
    }
}
