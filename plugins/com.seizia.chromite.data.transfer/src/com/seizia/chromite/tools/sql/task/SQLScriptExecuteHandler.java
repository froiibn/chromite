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
package com.seizia.chromite.tools.sql.task;

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.fs.DBFUtils;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNProject;
import com.seizia.chromite.model.navigator.DBNStreamData;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.rm.RMControllerProvider;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.PrintStreamProgressMonitor;
import com.seizia.chromite.model.sql.SQLScriptCommitType;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLScriptErrorHandling;
import com.seizia.chromite.model.sql.exec.SQLScriptProcessor;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.model.task.*;
import com.seizia.chromite.registry.ApplicationPolicyProvider;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.transfer.DTUtils;
import com.seizia.chromite.tools.transfer.internal.DTMessages;
import org.jkiss.utils.IOUtils;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQLScriptExecuteHandler
 *
 * 설계 (Chromite, 사용자 규칙 "트랜잭션 안전"): 스크립트는 SWT처럼 연결의 주 실행 문맥에서 돌지만 Chromite는 그 세션의 트랜잭션을
 * 묻지 않고 끝내지 않는다.
 * <ul>
 *     <li>SWT는 "자동 커밋"을 끄면 커밋 방식 {@code AT_END}로 스크립트 끝에 커밋하고 오류면 롤백했으며, 자동 커밋 연결에서는
 *     세션을 수동 커밋으로 바꿨다가 되돌려(JDBC가 그때 커밋) 결국 묻지 않고 커밋했다. 이제 세션의 커밋 방식은 바꾸지 않는다:
 *     자동 커밋 세션은 {@code AUTOCOMMIT}, 수동 커밋 세션은 {@code NO_COMMIT}로 돌린다. 작업의 "자동 커밋"을 켜면 사용자가 고른
 *     대로 문장마다 커밋한다(세션을 자동 커밋으로 바꿨다 되돌림, 아래 확인 뒤라 끝낼 트랜잭션이 없음).</li>
 *     <li>오류에서 멈춤은 {@code STOP_COMMIT}이다. {@code NO_COMMIT}과 함께면 멈추기만 하고 커밋·롤백하지 않는다({@code STOP_ROLLBACK}은 롤백함).</li>
 *     <li>한 세션에서 처음 실행하기 전에 그 세션에 열린 트랜잭션이 있으면 커밋 / 롤백 / 취소를 묻는다(취소가 기본, 취소하면 실행하지 않음).
 *     스크립트가 남의 트랜잭션에 섞이지 않게 하기 위해서다.</li>
 *     <li>끝나면(오류로 멈춰도) 실행한 세션에 열린 트랜잭션이 있으면 커밋 / 롤백 / 열어 두기를 묻는다(열어 두기가 기본).
 *     열어 둔 트랜잭션은 열린 트랜잭션 목록, 유휴 트랜잭션 알림, 연결을 끊을 때의 질문이 보인다.</li>
 *     <li>열린 트랜잭션은 데이터 가져오기와 같은 규칙으로 찾는다({@link #hasOpenTransaction}): 수동 커밋의 쿼리 관리자 기록과, 상태 줄이 보이는
 *     DB의 답({@link QMUtils#getTransactionState}). 그래야 자동 커밋 세션에서 스크립트가 {@code BEGIN TRAN}으로 열고 끝내지 않은 트랜잭션도 묻는다.
 *     끝내기는 다른 질문과 같은 공용 경로({@link IdleTransactionMonitor#commitOrRollback})다: 자동 커밋에서 드라이버가 {@code commit()}을 거절한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 트랜잭션 안전: 커밋 방식을 바꾸거나 묻지 않고 커밋·롤백하지 않음, 실행 전·후 열린 트랜잭션 질문
 *   (docs/chromite/swing-database-tasks.md "SQL 스크립트 작업")
 * - 2026-11-12 자동 커밋 세션의 명시적 트랜잭션도 실행 전·후에 물음. 수동 커밋 모드의 기록({@code isTransactionActive})만 보아
 *   {@code BEGIN TRAN}을 끝내지 않은 스크립트가 아무 질문 없이 트랜잭션을 열어 두었다(앱 변경 #106, 같은 문서)
 */
public class SQLScriptExecuteHandler implements DBTTaskHandler, DBTTaskImportTransformer {

    private final DBCStatistics totalStatistics = new DBCStatistics();

    @Override
    public boolean transformTaskState(@NotNull Map<String, Object> state, @NotNull Map<String, String> projectNames) {
        boolean changed = DBTTaskImportTransformer.remapProjectReference(state, projectNames);
        for (Object dataSource : JSONUtils.getObjectList(state, "dataSources")) {
            changed |= DBTTaskImportTransformer.remapProjectReference(dataSource, projectNames);
        }
        return changed;
    }

    @Override
    @NotNull
    public DBTTaskRunStatus executeTask(
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBTTask task,
        @NotNull Locale locale,
        @NotNull Log log,
        @NotNull PrintStream logStream,
        @NotNull DBTTaskExecutionListener listener) throws DBException
    {
        if (ApplicationPolicyProvider.getInstance().isPolicyEnabled(ApplicationPolicyProvider.POLICY_SQL_EXECUTION)) {
            throw new DBException("Error: SQL script execution is restricted by policy"); //$NON-NLS-1$
        }
        SQLScriptExecuteSettings settings = new SQLScriptExecuteSettings();
        settings.loadConfiguration(task);
        executeWithSettings(runnableContext, task, locale, log, logStream, listener, settings);
        return DBTTaskRunStatus.makeStatisticsStatus(totalStatistics);
    }

    private void executeWithSettings(
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBTTask task,
        @NotNull Locale locale,
        @NotNull Log log,
        @NotNull PrintStream logStream,
        @NotNull DBTTaskExecutionListener listener,
        @NotNull SQLScriptExecuteSettings settings
    ) throws DBException {
        log.debug("SQL Scripts Execute");

        // Start consumers
        listener.taskStarted(task);
        Throwable error = null;
        try {
            runnableContext.run(true, true, monitor -> {
                try {
                    runScripts(new PrintStreamProgressMonitor(monitor, logStream), task, settings, log, logStream);
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            error = e.getTargetException();
        } catch (InterruptedException e) {
            log.debug("Task canceled");
        }
        if (error != null) {
            log.error(error);
        }
        listener.taskFinished(task, null, error, settings);

        log.debug("SQL script execute completed");
    }

    private void runScripts(DBRProgressMonitor monitor, DBTTask task, SQLScriptExecuteSettings settings, Log log, PrintStream logStream) throws DBException {
        List<DBPDataSourceContainer> dataSources = settings.getDataSources();
        // The sessions the scripts ran in: their open transactions are asked about at the end, also after an error
        Set<DBCExecutionContext> usedContexts = new LinkedHashSet<>();
        try {
            runScripts(monitor, task, settings, log, logStream, dataSources, usedContexts);
        } finally {
            for (DBCExecutionContext context : usedContexts) {
                askEndTransaction(monitor, task, context, logStream);
            }
        }
    }

    private void runScripts(
        DBRProgressMonitor monitor,
        DBTTask task,
        SQLScriptExecuteSettings settings,
        Log log,
        PrintStream logStream,
        List<DBPDataSourceContainer> dataSources,
        Set<DBCExecutionContext> usedContexts
    ) throws DBException {
        for (String filePath : settings.getScriptFiles()) {
            try {
                for (DBPDataSourceContainer dataSourceContainer : dataSources) {
                    var sqlScriptContent = readScriptContents(monitor, task.getProject(), filePath);
                    if (!dataSourceContainer.isConnected()) {
                        dataSourceContainer.connect(monitor, true, true);
                    }
                    DBPDataSource dataSource = dataSourceContainer.getDataSource();
                    if (dataSource == null) {
                        throw new DBException("Can't obtain data source connection");
                    }
                    DBCExecutionContext executionContext = dataSource.getDefaultInstance().getDefaultContext(monitor, false);
                    if (!usedContexts.contains(executionContext)) {
                        if (!askOpenTransaction(monitor, task, executionContext, logStream)) {
                            // Not run: TaskRunJob records a canceled run
                            monitor.getNestedMonitor().setCanceled(true);
                            return;
                        }
                        usedContexts.add(executionContext);
                    }

                    log.debug("> Execute script [" + filePath + "] in [" + dataSourceContainer.getName() + "]");
                    DBCExecutionContextDefaults contextDefaults = executionContext.getContextDefaults();
                    if (contextDefaults != null) {
                        DBSCatalog defaultCatalog = contextDefaults.getDefaultCatalog();
                        if (defaultCatalog != null) {
                            log.debug("> Default catalog: " + defaultCatalog.getName());
                        }
                        DBSSchema defaultSchema = contextDefaults.getDefaultSchema();
                        if (defaultSchema != null) {
                            log.debug("> Default schema: " + defaultSchema.getName());
                        }
                    }

                    processScript(monitor, task, settings, executionContext, filePath, sqlScriptContent, log, logStream);
                }
            } catch (Throwable e) {
                Throwable error = e instanceof InvocationTargetException ? ((InvocationTargetException) e).getTargetException() : e;
                throw new DBException("Error executing script '" + filePath + "'", error);
            }
        }
    }

    private void processScript(DBRProgressMonitor monitor, DBTTask task, SQLScriptExecuteSettings settings, DBCExecutionContext executionContext, String filePath, String sqlScriptContent, Log log, PrintStream logStream) throws DBException {
        PrintWriter logWriter = new PrintWriter(logStream, true);
        List<SQLScriptElement> scriptElements = SQLScriptParser.parseScript(executionContext.getDataSource(), sqlScriptContent);
        SQLScriptContext scriptContext = new SQLScriptContext(null, () -> executionContext, null, logWriter, null);
        scriptContext.setVariables(DBTaskUtils.getVariables(task));
        SQLScriptDataReceiver dataReceiver = new SQLScriptDataReceiver();
        SQLScriptProcessor scriptProcessor = new SQLScriptProcessor(executionContext, scriptElements, scriptContext, dataReceiver, log);

        // The session keeps its commit mode and its transaction (see the class comment)
        scriptProcessor.setCommitType(settings.isAutoCommit() || !isManualCommit(executionContext)
            ? SQLScriptCommitType.AUTOCOMMIT : SQLScriptCommitType.NO_COMMIT);
        scriptProcessor.setErrorHandling(settings.isIgnoreErrors() ? SQLScriptErrorHandling.IGNORE : SQLScriptErrorHandling.STOP_COMMIT);
        if (settings.isDumpQueryResultsToLog()) {
            dataReceiver.setDumpWriter(logWriter);
        }

        scriptProcessor.runScript(monitor);

        totalStatistics.accumulate(scriptProcessor.getTotalStatistics());
    }

    private static boolean isManualCommit(@NotNull DBCExecutionContext context) throws DBCException {
        DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
        return txnManager != null && txnManager.isSupportsTransactions() && !txnManager.isAutoCommit();
    }

    /**
     * Before the first script runs in a session: a transaction of the session that is not committed would take the script's
     * changes. Asks Commit / Roll back / Cancel (Cancel is the default).
     *
     * @return false when the task must not run
     */
    private static boolean askOpenTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBTTask task,
        @NotNull DBCExecutionContext context,
        @NotNull PrintStream logStream
    ) throws DBException {
        if (!hasOpenTransaction(context)) {
            return true;
        }
        String connection = context.getDataSource().getContainer().getName();
        List<String> labels = List.of(DTMessages.sql_script_task_txn_commit, DTMessages.sql_script_task_txn_rollback,
            DTMessages.sql_script_task_txn_cancel);
        int choice = DBWorkbench.getPlatformUI().showUserChoice(DTMessages.sql_script_task_txn_title,
            NLS.bind(DTMessages.sql_script_task_txn_before, connection, task.getName()), labels, List.of(), 2, 2).choiceIndex;
        if (choice != 0 && choice != 1) {
            logStream.println("Not run: the session of " + connection + " has a transaction that is not committed"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        endTransaction(monitor, context, choice == 0, logStream);
        return true;
    }

    /**
     * After the scripts (also after an error or a cancel): asks Commit / Roll back / Keep open when they left a transaction
     * that is not committed in the session (Keep open is the default). Nothing is committed or rolled back without the answer.
     */
    private static void askEndTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBTTask task,
        @NotNull DBCExecutionContext context,
        @NotNull PrintStream logStream
    ) {
        if (!hasOpenTransaction(context)) {
            return;
        }
        String connection = context.getDataSource().getContainer().getName();
        List<String> labels = List.of(DTMessages.sql_script_task_txn_commit, DTMessages.sql_script_task_txn_rollback,
            DTMessages.sql_script_task_txn_keep);
        int choice = DBWorkbench.getPlatformUI().showUserChoice(DTMessages.sql_script_task_txn_title,
            NLS.bind(DTMessages.sql_script_task_txn_after, task.getName(), connection), labels, List.of(), 2, 2).choiceIndex;
        if (choice != 0 && choice != 1) {
            logStream.println("The transaction in the session of " + connection + " was left open"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try {
            endTransaction(monitor, context, choice == 0, logStream);
        } catch (DBException e) {
            logStream.println("Can't end the transaction in the session of " + connection + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            DBWorkbench.getPlatformUI().showError(DTMessages.sql_script_task_txn_title,
                NLS.bind(DTMessages.sql_script_task_txn_error, connection), e);
        }
    }

    /**
     * The rule of the data import ({@code DatabaseTransferConsumer.endOpenTransaction}): the query manager's record of a
     * manual commit transaction, or the transaction the database holds as the status line shows it, which includes one
     * begun explicitly in auto-commit mode.
     */
    private static boolean hasOpenTransaction(@NotNull DBCExecutionContext context) {
        return QMUtils.isTransactionActive(context) || QMUtils.getTransactionState(context).isOpen();
    }

    private static void endTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        boolean commit,
        @NotNull PrintStream logStream
    ) throws DBException {
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End script task transaction")) { //$NON-NLS-1$
            IdleTransactionMonitor.commitOrRollback(session, commit);
        }
        logStream.println((commit ? "Committed" : "Rolled back") + " the transaction in the session of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + context.getDataSource().getContainer().getName());
    }

    public static String readScriptContents(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPProject project,
        @NotNull String filePath
    ) throws DBException, IOException {
        DBNModel navigatorModel = project.getNavigatorModel();
        if (navigatorModel != null) {
            DBNProject projectNode = navigatorModel.getRoot().getProjectNode(project);
            if (projectNode != null) {
                DBNNode fileNode = projectNode.findNodeByRelativePath(monitor, filePath);
                if (fileNode instanceof DBNStreamData sd) {
                    try (Reader reader = new InputStreamReader(sd.openInputStream())) {
                        return IOUtils.readToString(reader);
                    }
                }
            }
        }

        Path nioPath = DBFUtils.resolvePathFromString(monitor, project, filePath);
        if (!IOUtils.isLocalPath(nioPath)) {
            // Remote file
            return Files.readString(nioPath);
        }

        RMControllerProvider rmControllerProvider = DBUtils.getAdapter(RMControllerProvider.class, project);
        if (rmControllerProvider != null) {
            var rmController = rmControllerProvider.getResourceController();
            return new String(rmController.getResourceContents(project.getId(), filePath), StandardCharsets.UTF_8);
        }
        var sqlFile = DTUtils.findProjectFile(project, filePath);
        if (sqlFile == null) {
            throw new DBException("File " + filePath + " is not found in project " + project.getId());
        }
        try (Reader fileReader = Files.newBufferedReader(sqlFile)) {
            return IOUtils.readToString(fileReader);
        }
    }

}
