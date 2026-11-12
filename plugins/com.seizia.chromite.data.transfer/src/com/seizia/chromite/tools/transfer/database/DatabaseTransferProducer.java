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

package com.seizia.chromite.tools.transfer.database;

import org.eclipse.core.runtime.IAdaptable;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.impl.DataSourceContextProvider;
import com.seizia.chromite.model.meta.DBSerializable;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.DBRRunnableParametrized;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTaskUtils;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.IDataTransferConsumer;
import com.seizia.chromite.tools.transfer.IDataTransferNodePrimary;
import com.seizia.chromite.tools.transfer.IDataTransferProcessor;
import com.seizia.chromite.tools.transfer.IDataTransferProducer;
import com.seizia.chromite.tools.transfer.internal.DTMessages;
import com.seizia.chromite.tools.transfer.serialize.DTObjectSerializer;
import com.seizia.chromite.tools.transfer.serialize.SerializerContext;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data container transfer producer
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: 구간으로 나눠 읽을 때 마지막(덜 찬) 구간의 통계도 더함. 전에는 그 구간의 행이 파일에는 쓰였지만 읽은 행 수에서
 *   빠졌다(1000행을 300행씩 읽으면 900) (docs/chromite/swing-data-transfer.md "고급 옵션")
 * - 2026-11-12 Chromite: 소비자에게 행을 넘긴 뒤에는 읽기를 복구로 되풀이하지 않음({@code DBExecUtils.preventRecovery}). 원본 연결이
 *   읽는 중에 끊기면 복구가 다시 연결해 처음 행부터 다시 읽어, 이미 넣은 행이 키 없는 테이블에 두 번 들어갔다. 소비자(넣기)의 오류가
 *   원본 쪽 분류로 연결 끊김이 될 때도 같았다 (docs/chromite/swing-data-transfer.md "연결이 끊긴 넣기")
 */
@DBSerializable("databaseTransferProducer")
public class DatabaseTransferProducer implements IDataTransferProducer<DatabaseProducerSettings>, IDataTransferNodePrimary {

    private static final Log log = Log.getLog(DatabaseTransferProducer.class);

    private final DBCStatistics producerStatistics = new DBCStatistics();

    private DBPDataSourceContainer dataSourceContainer;
    private DBSDataContainer dataContainer;
    private String objectId;
    @Nullable
    private DBDDataFilter dataFilter;
    @Nullable
    private String defaultCatalog;
    @Nullable
    private String defaultSchema;

    public DatabaseTransferProducer() {
    }

    public DatabaseTransferProducer(@NotNull DBSDataContainer dataContainer)
    {
        this.dataContainer = dataContainer;
    }

    public DatabaseTransferProducer(@NotNull DBSDataContainer dataContainer, @Nullable DBDDataFilter dataFilter)
    {
        this.dataContainer = dataContainer;
        this.dataFilter = dataFilter;
    }

    public void setDataContainer(@NotNull DBSDataContainer dataContainer) {
        this.dataContainer = dataContainer;
    }

    @Override
    public DBSDataContainer getDatabaseObject()
    {
        return dataContainer;
    }

    @Nullable
    @Override
    public DBPProject getProject() {
        return dataContainer == null ? null : DBUtils.getObjectOwnerProject(dataContainer);
    }

    @Override
    public String getObjectName() {
        final SQLQueryContainer queryContainer = GeneralUtils.adapt(dataContainer, SQLQueryContainer.class);
        if (queryContainer != null) {
            return CommonUtils.getSingleLineString(queryContainer.getQuery().toString());
        }
        return dataContainer == null ? objectId : DBUtils.getObjectFullName(dataContainer, DBPEvaluationContext.DML);
    }

    @Override
    public String getObjectFullName(@NotNull DBRProgressMonitor monitor) throws IOException {
        DBPDataSourceContainer container = getDataSourceContainer();
        return getObjectName() + (container == null ? "" : "@" + container.getName());
    }

    @Override
    public DBPImage getObjectIcon() {
        if (dataContainer instanceof DBPImageProvider) {
            return DBValueFormatting.getObjectImage(dataContainer);
        }
        return DBIcon.TREE_TABLE;
    }

    @Override
    public String getObjectContainerName() {
        DBPDataSourceContainer container = getDataSourceContainer();
        return container != null ? container.getName() : CommonUtils.notNull(objectId, "?");
    }

    @Override
    public DBPImage getObjectContainerIcon() {
        DBPDataSourceContainer container = getDataSourceContainer();
        return container != null ? container.getDriver().getIcon() : null;
    }

    @Override
    public boolean isConfigurationComplete() {
        return dataContainer != null;
    }

    @Override
    public DBPDataSourceContainer getDataSourceContainer() {
        if (dataContainer != null) {
            DBPDataSource dataSource = dataContainer.getDataSource();
            return dataSource == null ? null : dataSource.getContainer();
        }
        return dataSourceContainer;
    }

    @Nullable
    public String getDefaultCatalog() {
        return defaultCatalog;
    }

    public void setDefaultCatalog(@Nullable String defaultCatalog) {
        this.defaultCatalog = defaultCatalog;
    }

    @Nullable
    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(@Nullable String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    @Override
    public void transferData(
        @NotNull DBRProgressMonitor monitor1,
        @NotNull IDataTransferConsumer consumer,
        @Nullable IDataTransferProcessor processor,
        @NotNull DatabaseProducerSettings settings,
        @Nullable DBTTask task,
        long maxRows
    ) throws DBException {
        String contextTask = DTMessages.data_transfer_wizard_job_task_export;

        DBSDataContainer databaseObject = getDatabaseObject();
        if (databaseObject == null) {
            throw new DBException("No input database object found");
        }
        DBPDataSource dataSource = databaseObject.getDataSource();
        assert (dataSource != null);

        // Rows handed to the consumer over all tries of the read: once there are any, the read is not repeated
        DeliveryCounter delivered = new DeliveryCounter(consumer);
        DBRRunnableParametrized<DBRProgressMonitor> read = monitor -> {
            checkCanceled(monitor);

            long readFlags = DBSDataContainer.FLAG_NONE;

            var useFetchedRows = settings.getFetchedRowsPolicy();
            if (useFetchedRows != null) {
                readFlags |= DBSDataContainer.FLAG_USE_FETCHED_ROWS;
                if (useFetchedRows.selectedRowsOnly()) {
                    readFlags |= DBSDataContainer.FLAG_USE_SELECTED_ROWS;
                }
                if (useFetchedRows.selectedColumnsOnly()) {
                    readFlags |= DBSDataContainer.FLAG_USE_SELECTED_COLUMNS;
                }
            }

            boolean newConnection = settings.isOpenNewConnections() && !getDatabaseObject().getDataSource().getContainer().getDriver()
                .isEmbedded();
            boolean forceDataReadTransactions
                = Boolean.TRUE.equals(dataSource.getDataSourceFeature(DBPDataSource.FEATURE_LOB_REQUIRE_TRANSACTIONS));
            boolean selectiveExportFromUI = useFetchedRows != null;
            boolean extractInSegments = useFetchedRows == null &&
                settings.getExtractType() == DatabaseProducerSettings.ExtractType.SEGMENTS;

            DBCExecutionContext context;
            if (dataContainer instanceof DBPContextProvider contextProvider) {
                context = contextProvider.getExecutionContext();
            } else {
                context = DBUtils.getDefaultContext(dataContainer, false);
            }
            if (context == null) {
                throw new DBCException("Can't retrieve execution context from data container " + dataContainer);
            }
            if (!selectiveExportFromUI && newConnection) {
                context = DBUtils.getObjectOwnerInstance(getDatabaseObject())
                    .openIsolatedContext(monitor, "Data transfer producer", context);
                DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context, defaultCatalog, null, defaultSchema);
            }
            if (task != null) {
                DBTaskUtils.initFromContext(monitor, task, context);
            }

            try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, contextTask)) {
                Boolean oldAutoCommit = null;
                DBCSavepoint savepoint = null;
                try {
                    AbstractExecutionSource transferSource = new AbstractExecutionSource(dataContainer, context, consumer);
                    if (!selectiveExportFromUI && (newConnection || forceDataReadTransactions)) {
                        // Turn off auto-commit in source DB
                        // Auto-commit has to be turned off because some drivers allows to read LOBs and
                        // other complex structures only in transactional mode
                        try {
                            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                            if (txnManager != null && txnManager.isSupportsTransactions()) {
                                oldAutoCommit = txnManager.isAutoCommit();
                                txnManager.setAutoCommit(monitor, false);
                                if (txnManager.supportsSavepoints()) {
                                    savepoint = txnManager.setSavepoint(monitor, "Data transfer start");
                                }
                            }
                        } catch (DBCException e) {
                            log.warn("Can't change auto-commit", e);
                        }

                    }
                    long totalRows = 0;
                    if (settings.isQueryRowCount() && dataContainer.isFeatureSupported(DBSDataContainer.FEATURE_DATA_COUNT)) {
                        checkCanceled(monitor);
                        monitor.beginTask(DTMessages.data_transfer_wizard_job_task_retrieve, 1);
                        try {
                            totalRows = dataContainer.countData(transferSource, session, dataFilter, readFlags);
                            checkCanceled(monitor);
                        } catch (Throwable e) {
                            log.warn("Can't retrieve row count from '" + dataContainer.getName() + "'", e);
                            try {
                                DBCTransactionManager txnManager = DBUtils.getTransactionManager(session.getExecutionContext());
                                if (txnManager != null && !txnManager.isAutoCommit()) {
                                    txnManager.rollback(session, savepoint);
                                }
                            } catch (Throwable e1) {
                                log.warn("Error rolling back transaction", e1);
                            }
                        } finally {
                            monitor.done();
                        }
                    }

                    monitor.beginTask(DTMessages.data_transfer_wizard_job_task_export_table_data, (int) totalRows);

                    try {
                        monitor.subTask("Read data");

                        // Perform export
                        if (!extractInSegments) {
                            checkCanceled(monitor);
                            // Just do it in single query
                            producerStatistics.accumulate(dataContainer.readData(
                                transferSource,
                                session,
                                delivered,
                                dataFilter,
                                -1,
                                maxRows,
                                readFlags,
                                settings.getFetchSize()
                            ));
                            checkCanceled(monitor);
                        } else {
                            // Read all data by segments
                            long offset = 0;
                            int segmentSize = settings.getSegmentSize();
                            for (; ; ) {
                                checkCanceled(monitor);
                                DBCStatistics statistics = dataContainer.readData(
                                    transferSource, session, delivered, dataFilter, offset, segmentSize, readFlags, settings.getFetchSize());
                                checkCanceled(monitor);
                                // The last segment is not full: its rows count too
                                producerStatistics.accumulate(statistics);
                                if (statistics.getRowsFetched() < segmentSize) {
                                    // Done
                                    break;
                                }
                                offset += statistics.getRowsFetched();
                            }
                        }
                    } finally {
                        monitor.done();
                    }

                } finally {
                    if (!selectiveExportFromUI && (newConnection || forceDataReadTransactions)) {
                        DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                        if (txnManager != null && txnManager.isSupportsTransactions()) {
                            if (!txnManager.isAutoCommit()) {
                                txnManager.rollback(session, savepoint);
                            }
                            if (savepoint != null) {
                                txnManager.releaseSavepoint(monitor, savepoint);
                            }
                            if (oldAutoCommit != null) {
                                txnManager.setAutoCommit(monitor, oldAutoCommit);
                            }
                        }
                    }
                    if (!selectiveExportFromUI && newConnection) {
                        context.close();
                    }
                }
            }
        };
        DBExecUtils.tryExecuteRecover(monitor1, dataSource, monitor -> {
            try {
                read.run(monitor);
            } catch (Exception e) {
                if (delivered.rows > 0) {
                    // The recovery would read again from the first row and hand the rows over a second time (written
                    // twice into a table without a key). The rows already handed over stay where the consumer put them
                    log.debug("Reading " + getObjectName() + " failed after " + delivered.rows + " rows were handed over: not read again");
                    DBExecUtils.preventRecovery();
                    if (!delivered.consumerFailed && !monitor.isCanceled()) {
                        throw DatabaseTransferConsumer.stopException(NLS.bind(DTMessages.database_transfer_producer_read_failed,
                            new Object[]{getObjectName(), delivered.rows, e.getMessage()}), e);
                    }
                }
                throw e;
            }
        });
    }

    /**
     * Counts the rows a read hands to the consumer, for the recovery to know whether the read may be repeated.
     */
    private static final class DeliveryCounter implements DBDDataReceiver {
        private final DBDDataReceiver consumer;
        private long rows;
        // The consumer failed (its own error is told as it is); otherwise a failed read is the source's
        private boolean consumerFailed;

        DeliveryCounter(@NotNull DBDDataReceiver consumer) {
            this.consumer = consumer;
        }

        @Override
        public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) throws DBException {
            consumer.fetchStart(session, resultSet, offset, maxRows);
        }

        @Override
        public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
            rows++;
            try {
                consumer.fetchRow(session, resultSet);
            } catch (Throwable e) {
                consumerFailed = true;
                throw e;
            }
        }

        @Override
        public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
            try {
                consumer.fetchEnd(session, resultSet);
            } catch (Throwable e) {
                consumerFailed = true;
                throw e;
            }
        }

        @Override
        public void close() throws DBException {
            consumer.close();
        }

        @NotNull
        @Override
        public DBCStatistics getStatistics() {
            return consumer.getStatistics();
        }

        @Override
        public String toString() {
            return String.valueOf(consumer);
        }
    }

    private static void checkCanceled(@NotNull DBRProgressMonitor monitor) throws DBInterruptedException {
        if (monitor.isCanceled()) {
            throw new DBInterruptedException("Data transfer was canceled");
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DatabaseTransferProducer &&
            CommonUtils.equalObjects(dataContainer, ((DatabaseTransferProducer) obj).dataContainer) &&
            CommonUtils.equalObjects(dataFilter, ((DatabaseTransferProducer) obj).dataFilter);
    }

    @Override
    @NotNull
    public DBCStatistics getStatistics() {
        return producerStatistics;
    }

    public static class ObjectSerializer implements DTObjectSerializer<DBTTask, DatabaseTransferProducer> {

        @Override
        public void serializeObject(@NotNull DBRRunnableContext runnableContext, @NotNull DBTTask context, @NotNull DatabaseTransferProducer object, @NotNull Map<String, Object> state) {
            DBSDataContainer dataContainer = object.dataContainer;
            if (dataContainer instanceof IAdaptable) {
                DBSDataContainer nestedDataContainer = ((IAdaptable) dataContainer).getAdapter(DBSDataContainer.class);
                if (nestedDataContainer != null) {
                    dataContainer = nestedDataContainer;
                }
            }
            if (dataContainer instanceof DBSEntity) {
                state.put("type", "entity");
                if (dataContainer.getDataSource() != null) {
                    state.put("project", dataContainer.getDataSource().getContainer().getProject().getName());
                }
                state.put("entityId", DBUtils.getObjectFullId(dataContainer));
            } else if (dataContainer instanceof SQLQueryContainer) {
                state.put("type", "query");
                SQLQueryContainer queryContainer = (SQLQueryContainer) dataContainer;
                DBPDataSourceContainer dataSource = queryContainer.getDataSourceContainer();
                if (dataSource != null) {
                    state.put("project", dataSource.getProject().getName());
                    state.put("dataSource", dataSource.getId());
                    if (object.defaultCatalog != null) {
                        state.put("defaultCatalog", object.defaultCatalog);
                    }
                    if (object.defaultSchema != null) {
                        state.put("defaultSchema", object.defaultSchema);
                    }
                }
                SQLScriptElement query = queryContainer.getQuery();
                state.put("query", query.getOriginalText());
            } else {
                state.put("type", "unknown");
                log.error("Unsupported producer data container: " + dataContainer);
            }
            if (object.dataFilter != null) {
                Map<String, Object> dataFilterState = new LinkedHashMap<>();
                object.dataFilter.serialize(dataFilterState);
                state.put("dataFilter", dataFilterState);
            }
        }

        @Override
        public DatabaseTransferProducer deserializeObject(
            @NotNull DBRRunnableContext runnableContext,
            @NotNull SerializerContext serializeContext,
            @NotNull DBTTask objectContext,
            @NotNull Map<String, Object> state
        ) throws DBException {
            DatabaseTransferProducer producer = new DatabaseTransferProducer();
            try {
                runnableContext.run(true, true, monitor -> {
                    try {
                        String selType = CommonUtils.toString(state.get("type"));
                        String projectName = CommonUtils.toString(state.get("project"));
                        DBPProject project = CommonUtils.isEmpty(projectName) ? null : DBWorkbench.getPlatform().getWorkspace().getProject(projectName);
                        if (project == null) {
                            project = objectContext.getProject();
                        }
                        switch (selType) {
                            case "entity": {
                                String id = CommonUtils.toString(state.get("entityId"));
                                String[] pathItems = id.split("/");
                                if (pathItems.length > 2) {
                                    producer.objectId = String.join("/", List.of(pathItems).subList(2, pathItems.length));
                                } else {
                                    producer.objectId = id;
                                }
                                producer.dataSourceContainer = DBUtils.findDataSourceByObjectId(project, id);
                                if (producer.dataSourceContainer != null && !serializeContext.isDataSourceFailed(producer.dataSourceContainer)) {
                                    try {
                                        producer.dataContainer = (DBSDataContainer) DBUtils.findObjectById(monitor, project, id, true);
                                    } catch (DBException e) {
                                        serializeContext.addError(e);
                                        serializeContext.addDataSourceFail(producer.dataSourceContainer);
                                    }
                                }
                                break;
                            }
                            case "query": {
                                String dsId = CommonUtils.toString(state.get("dataSource"));
                                String queryText = CommonUtils.toString(state.get("query"));
                                DBPDataSourceContainer ds = project.getDataSourceRegistry().getDataSource(dsId);
                                if (ds == null) {
                                    throw new DBCException("Can't find datasource "+ dsId);
                                }
                                if (!ds.isConnected()) {
                                    ds.connect(monitor, true, true);
                                }
                                DBPDataSource dataSource = ds.getDataSource();
                                SQLQuery query = new SQLQuery(dataSource, queryText);
                                DataSourceContextProvider taskContextProvider = new DataSourceContextProvider(dataSource);
                                SQLScriptContext scriptContext = new SQLScriptContext(null,
                                    taskContextProvider, null, new PrintWriter(System.err, true), null);
                                scriptContext.setVariables(DBTaskUtils.getVariables(objectContext));
                                producer.defaultCatalog = CommonUtils.toString(state.get("defaultCatalog"), null);
                                producer.defaultSchema = CommonUtils.toString(state.get("defaultSchema"), null);
                                producer.dataContainer = new SQLQueryDataContainer(
                                    taskContextProvider,
                                    query,
                                    scriptContext,
                                    log);
                                break;
                            }
                            default:
                                serializeContext.addError(new DBException("Unsupported selector type: " + selType));
                                break;
                        }
                    } catch (Exception e) {
                        throw new InvocationTargetException(e);
                    }
                });
            } catch (InvocationTargetException e) {
                throw new DBCException("Error instantiating data producer", e.getTargetException());
            } catch (InterruptedException e) {
                throw new DBCException("Deserialization canceled", e);
            }

            return producer;
        }
    }
}
