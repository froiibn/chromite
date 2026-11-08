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

package com.seizia.chromite.model.struct;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionSource;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;

import java.util.List;
import java.util.Map;

/**
 * Data manipulator.
 * Extends data container and provides additional methods to manipulate underlying data.
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: {@link ExecuteBatch#skipFailedRows}, {@link ExecuteBatch#restoreAppliedRows}. 실행이 실패한 일괄 실행을
 *   다시 시도·복구·건너뛰기할 때 실패한 행을 잃거나 이미 넣은 행을 다시 넣지 않도록 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-09 Chromite: 주석만: {@link ExecuteBatch#restoreAppliedRows}는 실행 전에 둔 저장점으로 되돌린 뒤에도 부름
 *   (docs/chromite/swing-data-transfer.md "오류가 트랜잭션을 끝내는 DB의 건너뛰기")
 */
public interface DBSDataManipulator extends DBSDataContainer {

    String FEATURE_DATA_INSERT = "data.insert";
    String FEATURE_DATA_UPDATE = "data.update";
    String FEATURE_DATA_DELETE = "data.delete";
    String FEATURE_DATA_TRUNCATE = "data.truncate";
    /** Saving a row can change other rows (for example, spreadsheet formulas). Reload the full result after saving. */
    String FEATURE_DATA_REFRESH_AFTER_UPDATE = "data.refresh.after.update";

    String INSERT_NONE_METHOD = "<None>";

    String OPTION_USE_MULTI_INSERT = "data.manipulate.useMultiInsert";//$NON-NLS-1$
    String OPTION_MULTI_INSERT_BATCH_SIZE = "data.manipulate.multiInsertBatchSize";//$NON-NLS-1$
    String OPTION_SKIP_BIND_VALUES = "data.manipulate.skipBindValues";//$NON-NLS-1$
    String OPTION_DISABLE_BATCHES = "data.manipulate.disableBatches";//$NON-NLS-1$
    String OPTION_INSERT_REPLACE_METHOD = "data.manipulate.insertReplaceMethod";//$NON-NLS-1$
    String OPTION_USE_CURRENT_DIALECT_SETTINGS = "data.manipulate.useCurrentDialect";//$NON-NLS-1$

    interface ExecuteBatch extends AutoCloseable {
        @NotNull
        ExecuteBatch add(@NotNull Object[] attributeValues) throws DBCException;

        @NotNull
        DBCStatistics execute(@NotNull DBCSession session, @NotNull Map<String, Object> options) throws DBException;

        void generatePersistActions(
            @NotNull DBCSession session,
            @NotNull List<DBEPersistAction> actions,
            @NotNull Map<String, Object> options
        ) throws DBException;

        /**
         * After {@link #execute} failed: drops the rows that failed (the failing row, or the rows of the failing JDBC batch)
         * and the rows the failed execution already executed. The rows it did not reach stay for the next execution.
         * Without this call the next execution goes on from the failed row (Retry).
         */
        default void skipFailedRows() {
        }

        /**
         * After {@link #execute} failed and the session's transaction was then rolled back (the error recovery, or back to a
         * savepoint set before the execution): the rows the failed execution already executed are gone, so the next
         * execution runs them again.
         */
        default void restoreAppliedRows() {
        }

        void close();
    }

    @NotNull
    ExecuteBatch insertData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] attributes,
        @Nullable DBDDataReceiver keysReceiver,
        @NotNull DBCExecutionSource source,
        @NotNull Map<String, Object> options)
        throws DBException;

    @NotNull
    ExecuteBatch updateData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] updateAttributes,
        @NotNull DBSAttributeBase[] keyAttributes,
        @Nullable DBDDataReceiver keysReceiver,
        @NotNull DBCExecutionSource source)
        throws DBException;

    @NotNull
    ExecuteBatch deleteData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] keyAttributes,
        @NotNull DBCExecutionSource source)
        throws DBException;

    @NotNull
    DBCStatistics truncateData(
        @NotNull DBCSession session,
        @NotNull DBCExecutionSource source)
        throws DBException;

}
