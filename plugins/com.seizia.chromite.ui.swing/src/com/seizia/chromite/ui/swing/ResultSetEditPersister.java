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
package com.seizia.chromite.ui.swing;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.data.DBDValue;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.resultset.DBDDataStatementInfo;
import com.seizia.chromite.model.data.resultset.DBDResultSetDataUpdater;
import com.seizia.chromite.model.data.resultset.DataUpdaterJob;
import com.seizia.chromite.model.data.resultset.ResultSetSaveSettings;
import com.seizia.chromite.model.exec.DBCAttributeMetaData;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCExecutionSource;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCResultSetMetaData;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.RowDataReceiver;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.DBSDocumentContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.rdb.DBSManipulationType;
import com.seizia.chromite.runtime.jobs.DataSourceJob;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 결과 표의 바뀐 내용을 DB에 저장한다. 모델 계층의 저장 엔진 {@link DBDResultSetDataUpdater}를 상속한다.
 *
 * 설계:
 * <ul>
 *     <li>INSERT/UPDATE/DELETE는 직접 만들지 않는다. 엔진이 테이블의 {@link DBSDataManipulator}({@code insertData}, {@code updateData},
 *     {@code deleteData})로 문장을 만들고 실행한다. 이 클래스는 SWT {@code ResultSetPersister}처럼 행 목록, 바뀐 셀, 문장 정보,
 *     생성된 키 수신, 실행 원본만 채운다(SWT 클래스는 {@code ResultSetViewer}와 SWT 대화상자에 묶여 있어 쓰지 않았다, swing-frontend.md "결과 모델 분석").</li>
 *     <li>저장 전 검사({@link #checkEntityIdentifiers})는 SWT와 같은 규칙이다. 다만 키가 없을 때 가상 키를 정하는 대화상자는 없고 오류로 끝낸다.</li>
 *     <li>트랜잭션: 연결이 자동 커밋이면 저장하는 동안 자동 커밋을 끄고 모든 문장을 한 트랜잭션으로 실행한 뒤 커밋한다. 하나라도 실패하면 모두 롤백하고
 *     자동 커밋을 되돌린다(전부 저장되거나 하나도 저장되지 않음, 바뀐 내용은 화면에 남는다). 연결이 수동 커밋이면 SWT처럼 열린 트랜잭션 안에서 실행하고
 *     (엔진이 저장점을 잡고 실패하면 저장점까지 롤백) 커밋은 사용자에게 맡긴다. 트랜잭션을 지원하지 않는 연결은 문장마다 바로 적용된다.</li>
 *     <li>INSERT: 원본 테이블 열만 넣고({@link #shouldInsertAttribute}), 생성된 키는 SWT와 같은 규칙으로 받는다. 새 행에 키 값이 없으면 열이 자동 생성으로
 *     표시되지 않았어도 생성된 키를 요청한다(SQLite {@code INTEGER} 기본 키 = rowid 별칭). 그래야 새 행을 키로 다시 읽고 이어서 고칠 수 있다.</li>
 *     <li>저장 Job은 엔진의 {@link DataUpdaterJob}({@code DataSourceJob})이다. 문장 준비({@code prepareStatements})도 그 Job 안에서 해서 EDT는 기다리지 않는다.
 *     끝나면 결과를 EDT로 넘긴다({@link Outcome}).</li>
 *     <li>저장 뒤 새로 고침: SWT 기본 설정({@code resultset.edit.refreshAfterUpdate} = 켬)처럼 추가·수정한 행만 키로 다시 읽는다({@link #refreshRows}).
 *     원래 쿼리를 키 조건으로 걸러 다시 실행하므로({@code SQLQueryDataContainer.readData}) 열 구성이 같다. 스크립트 전체를 다시 실행하지 않는 것은
 *     스크립트에 다른 문장(DML 포함)이 있을 수 있고, 다른 결과 표와 화면 위치를 그대로 두기 위해서다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 G단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 */
final class ResultSetEditPersister extends DBDResultSetDataUpdater<ResultSetEditPersister.StatementInfo, ResultSetEditModel.Row, ResultSetEditModel> {

    private static final Log log = Log.getLog(ResultSetEditPersister.class);

    /**
     * Result of a save, handed to the EDT.
     *
     * @param error          the first error, or null if everything was saved
     * @param executedRows   rows whose statements ran and stay applied
     * @param zeroRowChanges statements that changed no rows
     * @param transaction    how the changes were committed
     */
    record Outcome(
        @Nullable Throwable error,
        @NotNull List<ResultSetEditModel.Row> executedRows,
        int inserted,
        int updated,
        int deleted,
        int zeroRowChanges,
        @NotNull TransactionMode transaction
    ) {
    }

    enum TransactionMode {
        /** Auto-commit was switched off for the save and the transaction committed or rolled back. */
        SAVE_TRANSACTION,
        /** Manual commit mode: the changes are in the open transaction. */
        OPEN_TRANSACTION,
        /** No transactions: each statement applied on its own. */
        NONE
    }

    /**
     * One statement and the row it saves. Generated key values ({@link KeyDataReceiver}) wait here until the row is
     * updated in the EDT.
     */
    static final class StatementInfo extends DBDDataStatementInfo {
        @NotNull
        final DBSManipulationType type;
        @NotNull
        final ResultSetEditModel.Row row;
        final Map<Integer, Object> updatedCells = new HashMap<>();

        StatementInfo(@NotNull DBSManipulationType type, @NotNull ResultSetEditModel.Row row, @NotNull DBSEntity entity) {
            super(entity);
            this.type = type;
            this.row = row;
        }
    }

    private static final int NO_COLUMN = -1;
    private static final int SEVERAL_COLUMNS = -2;

    private int zeroRowChanges;

    ResultSetEditPersister(@NotNull ResultSetEditModel model) {
        super(model, model.getExecutionContext());
        collectChanges();
    }

    /**
     * Same checks as SWT {@code ResultSetPersister.checkEntityIdentifiers}: inserts and deletes need the single
     * source table, deletes and updates a complete unique key.
     */
    void checkEntityIdentifiers() throws DBException {
        if ((hasInserts() || hasDeletes()) && model.getSingleSource() == null) {
            throw new DBCException("Can't detect source entity");
        }
        if (hasDeletes()) {
            DBDRowIdentifier identifier = model.getDefaultRowIdentifier();
            if (identifier == null || identifier.isIncomplete()) {
                throw new DBCException("No unique row identifier is result set. Cannot proceed with row(s) delete.");
            } else if (!identifier.isValidIdentifier()) {
                throw new DBCException("Attributes of unique key '" +
                    DBUtils.getObjectFullName(identifier.getUniqueKey(), DBPEvaluationContext.UI) +
                    "' are missing in result set. Cannot proceed with row(s) delete.");
            }
        }
        for (ResultSetEditModel.Row row : changedRows) {
            for (Integer column : row.originalValues.keySet()) {
                DBDAttributeBinding attribute = model.getAttributes()[column];
                DBDRowIdentifier identifier = attribute.getRowIdentifier();
                if (identifier == null || identifier.isIncomplete()) {
                    throw new DBCException("Attribute " + attribute.getName() + " was changed but it hasn't associated unique key");
                } else if (!identifier.isValidIdentifier()) {
                    throw new DBCException("Can't update attribute '" + attribute.getName() + "' - attributes of key '" +
                        DBUtils.getObjectFullName(identifier.getUniqueKey(), DBPEvaluationContext.UI) + "' are missing in result set");
                }
            }
        }
    }

    /**
     * Creates the save job; the caller schedules it (or waits for it in a progress dialog, which schedules it).
     * {@code done} runs in the EDT when it is over.
     */
    @NotNull
    DataUpdaterJob createSaveJob(@NotNull Consumer<Outcome> done) {
        return new SaveJob(new ResultSetSaveSettings(), done);
    }

    //////////////////////////////////////////////////////////
    // DBDResultSetDataUpdater

    @Override
    protected void collectUpdatedRows() {
        deletedRows.clear();
        addedRows.clear();
        changedRows.clear();
        for (ResultSetEditModel.Row row : model.getAllRows()) {
            switch (row.state) {
                case NORMAL -> {
                    if (row.isChanged()) {
                        changedRows.add(row);
                    }
                }
                case ADDED -> addedRows.add(row);
                case REMOVED -> deletedRows.add(row);
            }
        }
    }

    /**
     * Changed attributes with their first values (flat columns: the grid has no nested values).
     */
    @Nullable
    @Override
    protected Map<DBDAttributeBinding, Object> collectUpdateChanges(@NotNull ResultSetEditModel.Row row) {
        if (!row.isChanged()) {
            return null;
        }
        Map<DBDAttributeBinding, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : row.originalValues.entrySet()) {
            changes.put(model.getAttributes()[entry.getKey()], entry.getValue());
        }
        return changes;
    }

    @NotNull
    @Override
    protected StatementInfo getDataStatementInfo(
        @NotNull DBSManipulationType type,
        @NotNull ResultSetEditModel.Row row,
        @NotNull DBSEntity entity
    ) {
        StatementInfo statement = new StatementInfo(type, row, entity);
        if (type == DBSManipulationType.INSERT && findMissingKeyColumn(row) != NO_COLUMN) {
            // The database assigns the key: read it back even when the model does not mark the column auto-generated
            // (a SQLite INTEGER PRIMARY KEY is a rowid alias); otherwise the new row could not be found again
            statement.setKeysRequired(true);
        }
        return statement;
    }

    /**
     * The key column of the default row identifier whose value the row lacks.
     *
     * @return its index, {@link #NO_COLUMN} if the row has all key values, {@link #SEVERAL_COLUMNS} if it lacks several
     */
    private int findMissingKeyColumn(@NotNull ResultSetEditModel.Row row) {
        DBDRowIdentifier identifier = model.getDefaultRowIdentifier();
        int missing = NO_COLUMN;
        if (identifier != null) {
            for (DBDAttributeBinding keyAttribute : identifier.getAttributes()) {
                if (DBUtils.isNullValue(model.getCellValue(keyAttribute, row))) {
                    if (missing != NO_COLUMN) {
                        return SEVERAL_COLUMNS;
                    }
                    missing = keyAttribute.getOrdinalPosition();
                }
            }
        }
        return missing;
    }

    /**
     * Only columns of the source table go into an INSERT (not expressions or pseudo columns). The model's insert
     * batch leaves NULL values out itself, so columns not set in the new row get their database defaults.
     */
    @Override
    protected boolean shouldInsertAttribute(@NotNull DBDAttributeBinding attribute, @Nullable Object value) {
        DBDRowIdentifier identifier = attribute.getRowIdentifier();
        return !attribute.isPseudoAttribute() && attribute.getEntityAttribute() != null
            && identifier != null && identifier.getEntity() == model.getSingleSource();
    }

    @NotNull
    @Override
    protected DBCExecutionSource createExecutionSource(@NotNull DBSDataManipulator dataContainer) {
        return new AbstractExecutionSource(dataContainer, model.getExecutionContext(), this);
    }

    @Nullable
    @Override
    protected DBDDataReceiver getKeyReceiver(@NotNull DBDDataStatementInfo statement) {
        return statement instanceof StatementInfo info ? new KeyDataReceiver(info) : null;
    }

    @Override
    protected void notifyContainer(@NotNull DBCStatistics statistics) {
        if (statistics.getRowsUpdated() == 0) {
            zeroRowChanges++;
        }
    }

    //////////////////////////////////////////////////////////
    // Save job

    /**
     * The model's update job with the statement preparation and the save transaction around it.
     */
    private final class SaveJob extends DataUpdaterJob {
        private final ResultSetSaveSettings settings;
        private final Consumer<Outcome> done;
        private TransactionMode transaction = TransactionMode.NONE;

        SaveJob(@NotNull ResultSetSaveSettings settings, @NotNull Consumer<Outcome> done) {
            super(ResultSetEditPersister.this, false, settings, null, model.getExecutionContext());
            this.settings = settings;
            this.done = done;
        }

        @Nullable
        @Override
        protected Throwable executeUpdate(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) {
            zeroRowChanges = 0;
            try {
                prepareStatements(monitor, settings);
            } catch (DBException e) {
                return e;
            }
            DBCExecutionContext context = model.getExecutionContext();
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            boolean switchedAutoCommit = false;
            try {
                if (txnManager != null && txnManager.isSupportsTransactions()) {
                    if (txnManager.isAutoCommit()) {
                        monitor.subTask("Disable auto-commit mode");
                        txnManager.setAutoCommit(monitor, false);
                        switchedAutoCommit = true;
                        transaction = TransactionMode.SAVE_TRANSACTION;
                    } else {
                        transaction = TransactionMode.OPEN_TRANSACTION;
                    }
                }
            } catch (DBCException e) {
                return e;
            }
            Throwable error = super.executeUpdate(monitor, options);
            if (error == null && monitor.isCanceled()) {
                // The engine stops between statements without an error when canceled; the save is incomplete
                error = new DBCException("Save was canceled");
            }
            if (switchedAutoCommit) {
                error = endSaveTransaction(monitor, context, txnManager, error);
            }
            return error;
        }

        @Nullable
        private Throwable endSaveTransaction(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext context,
            @NotNull DBCTransactionManager txnManager,
            @Nullable Throwable error
        ) {
            try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End results save transaction")) {
                if (error == null) {
                    try {
                        txnManager.commit(session);
                    } catch (DBCException e) {
                        error = e;
                    }
                }
                if (error != null) {
                    try {
                        txnManager.rollback(session, null);
                    } catch (DBCException e) {
                        log.debug("Error rolling back results save", e);
                    }
                }
            }
            try {
                txnManager.setAutoCommit(monitor, true);
            } catch (DBCException e) {
                log.warn("Can't restore auto-commit mode after saving results", e);
                if (error == null) {
                    error = e;
                }
            }
            return error;
        }

        @Override
        protected void handleCompletion(@Nullable Throwable error) {
            // Statements of a rolled back save transaction are not applied, whatever ran before the error
            boolean keepExecuted = error == null || transaction == TransactionMode.NONE;
            List<ResultSetEditModel.Row> executedRows = new ArrayList<>();
            int inserted = 0;
            int updated = 0;
            int deleted = 0;
            if (keepExecuted) {
                for (List<StatementInfo> statements : List.of(deleteStatements, insertStatements, updateStatements)) {
                    for (StatementInfo statement : statements) {
                        if (statement.isExecuted() && !executedRows.contains(statement.row)) {
                            executedRows.add(statement.row);
                        }
                    }
                }
                // Statistics without executed statements report -1
                inserted = (int) Math.max(0, getInsertStats().getRowsUpdated());
                updated = (int) Math.max(0, getUpdateStats().getRowsUpdated());
                deleted = (int) Math.max(0, getDeleteStats().getRowsUpdated());
            }
            List<StatementInfo> keyStatements = keepExecuted ? new ArrayList<>(insertStatements) : List.of();
            Outcome outcome = new Outcome(error, executedRows, inserted, updated, deleted, zeroRowChanges, transaction);
            SwingUtilities.invokeLater(() -> {
                // Generated keys of the inserted rows, as SWT reflectKeysUpdate
                for (StatementInfo statement : keyStatements) {
                    if (statement.isExecuted()) {
                        for (Map.Entry<Integer, Object> entry : statement.updatedCells.entrySet()) {
                            statement.row.values[entry.getKey()] = entry.getValue();
                            statement.row.display[entry.getKey()] = model.toDisplay(entry.getKey(), entry.getValue());
                        }
                    }
                }
                done.accept(outcome);
            });
            super.handleCompletion(error);
        }
    }

    /**
     * Receives generated keys of an INSERT (SWT {@code ResultSetPersister.KeyDataReceiver}).
     */
    private final class KeyDataReceiver implements DBDDataReceiver {
        private final StatementInfo statement;

        KeyDataReceiver(@NotNull StatementInfo statement) {
            this.statement = statement;
        }

        @Override
        public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) {
        }

        @Override
        public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBCException {
            DBCResultSetMetaData metaData = resultSet.getMeta();
            List<? extends DBCAttributeMetaData> keyAttributes = metaData.getAttributes();
            DBDAttributeBinding[] bindings = model.getAttributes();
            for (int i = 0; i < keyAttributes.size(); i++) {
                DBCAttributeMetaData keyAttribute = keyAttributes.get(i);
                DBDValueHandler valueHandler = DBUtils.findValueHandler(session, keyAttribute);
                Object keyValue = valueHandler.fetchValueObject(session, resultSet, keyAttribute, i);
                if (keyValue == null) {
                    // Some drivers return empty generated keys; nothing to update
                    continue;
                }
                int column = -1;
                if (!CommonUtils.isEmpty(keyAttribute.getName())) {
                    for (DBDAttributeBinding binding : bindings) {
                        DBDRowIdentifier identifier = binding.getRowIdentifier();
                        if (identifier != null && identifier.getEntity() == statement.getEntity()
                            && binding.getName().equals(keyAttribute.getName())) {
                            column = binding.getOrdinalPosition();
                            break;
                        }
                    }
                }
                if (column < 0) {
                    // The driver's key column name does not match: the auto-generated column
                    for (int k = 0; k < bindings.length; k++) {
                        if (bindings[k].isAutoGenerated()) {
                            column = k;
                            break;
                        }
                    }
                }
                if (column < 0 && keyAttributes.size() == 1) {
                    // A single generated value under a driver-made name (SQLite: last_insert_rowid()) belongs to the one key
                    // column the new row lacks
                    int missing = findMissingKeyColumn(statement.row);
                    if (missing >= 0) {
                        column = missing;
                    }
                }
                if (column >= 0) {
                    statement.updatedCells.put(column, keyValue);
                } else {
                    log.debug("Can't find target column for auto-generated key '" + keyAttribute.getName() + "'");
                }
            }
        }

        @Override
        public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        }

        @Override
        public void close() {
        }
    }

    //////////////////////////////////////////////////////////
    // Refresh after save

    /**
     * Reads the given rows again by their key (SWT {@code ResultSetPersister.refreshInsertedRows}): the original
     * query filtered by the key values. {@code done} gets, in the EDT, the new values and display strings by row.
     */
    static void refreshRows(
        @NotNull ResultSetEditModel model,
        @NotNull List<ResultSetEditModel.Row> rows,
        @NotNull Consumer<Map<ResultSetEditModel.Row, Object[][]>> done
    ) {
        DBDRowIdentifier identifier = model.getDefaultRowIdentifier();
        if (rows.isEmpty() || identifier == null || identifier.isIncomplete()) {
            return;
        }
        DBSEntity entity = identifier.getEntity();
        if (entity instanceof DBSDocumentContainer || entity.getDataSource().getInfo().isDynamicMetadata()) {
            // As SWT: documents are not refreshed
            return;
        }
        DBCExecutionContext context = model.getExecutionContext();
        DBSDataContainer dataContainer = model.getDataContainer();
        DBDAttributeBinding[] bindings = model.getAttributes();
        // Key values are read in the EDT, where the rows live
        List<ResultSetEditModel.Row> keyedRows = new ArrayList<>();
        List<DBDDataFilter> filters = new ArrayList<>();
        for (ResultSetEditModel.Row row : rows) {
            List<DBDAttributeConstraint> constraints = new ArrayList<>();
            for (DBDAttributeBinding keyAttribute : identifier.getAttributes()) {
                Object keyValue = model.getCellValue(keyAttribute, row);
                if (DBUtils.isNullValue(keyValue)) {
                    constraints = null;
                    break;
                }
                DBDAttributeConstraint constraint = new DBDAttributeConstraint(keyAttribute);
                constraint.setOperator(DBCLogicalOperator.EQUALS);
                constraint.setValue(keyValue);
                constraints.add(constraint);
            }
            if (constraints != null) {
                keyedRows.add(row);
                filters.add(new DBDDataFilter(constraints));
            }
        }
        if (keyedRows.isEmpty()) {
            return;
        }
        new DataSourceJob("Refresh saved rows", context) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Map<ResultSetEditModel.Row, Object[][]> refreshed = new LinkedHashMap<>();
                try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Refresh row(s) after insert/update")) {
                    AbstractExecutionSource source = new AbstractExecutionSource(dataContainer, context, model);
                    for (int i = 0; i < keyedRows.size(); i++) {
                        DisplayRowReceiver receiver = new DisplayRowReceiver(bindings);
                        dataContainer.readData(source, session, receiver, filters.get(i), 0, 0, DBSDataContainer.FLAG_REFRESH, 0);
                        if (receiver.getRowValues() != null) {
                            refreshed.put(keyedRows.get(i), new Object[][]{receiver.getRowValues(), receiver.display});
                        }
                    }
                } catch (Throwable e) {
                    // The save itself succeeded; the grid keeps the values as entered
                    log.warn("Error refreshing rows after save", e);
                }
                SwingUtilities.invokeLater(() -> done.accept(refreshed));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Reads one row of the refresh query, with display strings made while the result set is open (like
     * {@link ResultSetGridReceiver}); value objects (LOBs etc.) are released and kept as null.
     */
    private static final class DisplayRowReceiver extends RowDataReceiver {
        Object[] display;

        DisplayRowReceiver(@NotNull DBDAttributeBinding[] bindings) {
            super(bindings);
        }

        @Override
        protected void fetchRowValues(DBCSession session, DBCResultSet resultSet) throws DBCException {
            super.fetchRowValues(session, resultSet);
            display = new Object[curAttributes.length];
            for (int i = 0; i < curAttributes.length; i++) {
                Object value = rowValues[i];
                DBDAttributeBinding binding = curAttributes[i];
                display[i] = DBUtils.isNullValue(value)
                    ? ResultSetGrid.NULL_VALUE
                    : binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
                if (value instanceof DBDValue || binding.getDataKind() == DBPDataKind.CONTENT) {
                    DBUtils.releaseValue(value);
                    rowValues[i] = null;
                }
            }
        }
    }
}
