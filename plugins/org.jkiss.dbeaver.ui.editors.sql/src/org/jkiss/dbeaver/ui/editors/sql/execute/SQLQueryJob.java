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
package org.jkiss.dbeaver.ui.editors.sql.execute;

import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteJob;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.data.resultset.DBCSmartTransactionManager;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.*;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer;
import org.jkiss.dbeaver.ui.UITask;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetPreferences;
import org.jkiss.dbeaver.ui.dialogs.ConfirmationDialog;
import org.jkiss.dbeaver.ui.dialogs.exec.ExecutionQueueErrorJob;
import org.jkiss.dbeaver.ui.editors.IncludedScriptFileEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLPreferenceConstants;
import org.jkiss.dbeaver.ui.editors.sql.SQLPreferenceConstants.StatisticsTabOnExecutionBehavior;
import org.jkiss.dbeaver.ui.editors.sql.SQLResultsConsumer;
import org.jkiss.dbeaver.ui.editors.sql.internal.SQLEditorActivator;
import org.jkiss.dbeaver.ui.editors.sql.internal.SQLEditorMessages;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * SQLQueryJob
 *
 * 설계: 실행 로직은 SWT와 무관한 {@link SQLQueryExecuteJob}(model.sql)에 있고,
 * 이 클래스는 SQL 편집기 설정값(SQLPreferenceConstants, ResultSetPreferences), SWT 대화상자,
 * 결과 탭({@link SQLResultsConsumer})을 {@link SQLQueryExecuteHandler}로 넘기기만 한다.
 * 설정값과 대화상자는 분리 전과 같은 시점에 같은 방식으로 읽고 띄운다.
 * 공개 메서드(extractData, getCurrentStatement 등)와 STATS_RESULTS는 상위 클래스에서 그대로 물려받는다.
 *
 * 변경 이력:
 * - 2026-11-06 실행 로직을 SQLQueryExecuteJob으로 옮기고 UI 부분만 남김(docs/chromite/swing-frontend.md "쿼리 실행 계층").
 *
 * @author Serge Rider
 */
public class SQLQueryJob extends SQLQueryExecuteJob {

    private static final int MAX_QUERY_PREVIEW_LENGTH = 8192;

    public SQLQueryJob(
        @NotNull IWorkbenchPartSite partSite,
        @NotNull String name,
        @NotNull DBCExecutionContext executionContext,
        @Nullable DBSDataContainer dataContainer,
        @NotNull List<SQLScriptElement> queries,
        @NotNull SQLScriptContext scriptContext,
        @Nullable SQLResultsConsumer resultsConsumer,
        @Nullable SQLQueryListener listener,
        boolean isDisableFetchResultSet)
    {
        super(name, executionContext, dataContainer, queries, scriptContext, listener,
            new EditorExecuteHandler(partSite, resultsConsumer, executionContext.getDataSource().getContainer()));

        {
            // Read config form preference store
            DBPPreferenceStore preferenceStore = getDataSourceContainer().getPreferenceStore();
            setCommitType(SQLScriptCommitType.valueOf(preferenceStore.getString(SQLPreferenceConstants.SCRIPT_COMMIT_TYPE)));
            setErrorHandling(SQLScriptErrorHandling.valueOf(preferenceStore.getString(SQLPreferenceConstants.SCRIPT_ERROR_HANDLING)));
            setFetchResultSets(queries.size() == 1 || (
                preferenceStore.getBoolean(SQLPreferenceConstants.SCRIPT_FETCH_RESULT_SETS) && !isDisableFetchResultSet
            ));
        }
    }

    @NotNull
    @Override
    protected String getStatusPluginId() {
        return SQLEditorActivator.PLUGIN_ID;
    }

    /**
     * SQL editor implementation: results go to the editor result tabs, settings come from the connection
     * preference store, questions are asked with SWT dialogs.
     */
    private static class EditorExecuteHandler implements SQLQueryExecuteHandler {
        private final IWorkbenchPartSite partSite;
        private final SQLResultsConsumer resultsConsumer;
        private final DBPDataSourceContainer dataSourceContainer;

        EditorExecuteHandler(
            @NotNull IWorkbenchPartSite partSite,
            @Nullable SQLResultsConsumer resultsConsumer,
            @NotNull DBPDataSourceContainer dataSourceContainer
        ) {
            this.partSite = partSite;
            this.resultsConsumer = resultsConsumer;
            this.dataSourceContainer = dataSourceContainer;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            return resultsConsumer.getDataReceiver(query, resultSetNumber);
        }

        @Nullable
        @Override
        public DBDDataReceiver getStatisticsDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            DBDDataReceiver dataReceiver = resultsConsumer.getDataReceiver(query, resultSetNumber);
            // Data export does not need the statistics pseudo result set
            return dataReceiver instanceof IDataTransferConsumer ? null : dataReceiver;
        }

        @Override
        public boolean isSmartAutoCommit() {
            return resultsConsumer instanceof DBCSmartTransactionManager && ((DBCSmartTransactionManager) resultsConsumer).isSmartAutoCommit();
        }

        @NotNull
        @Override
        public QueryConfirmation confirmQueryExecution(@NotNull SQLQuery query, boolean scriptMode) {
            switch (confirmQueryExecutionDialog(query, scriptMode)) {
                case IDialogConstants.NO_ID:
                    return QueryConfirmation.NO;
                case IDialogConstants.YES_ID:
                    return QueryConfirmation.YES;
                case IDialogConstants.YES_TO_ALL_ID:
                    return QueryConfirmation.YES_TO_ALL;
                default:
                    return QueryConfirmation.CANCEL;
            }
        }

        @NotNull
        @Override
        public DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
            return ExecutionQueueErrorJob.showError(task, error, queue);
        }

        @Override
        public boolean confirmFetchManyResultSets(int resultSetLimit) {
            return DBWorkbench.getPlatformUI().confirmAction(
                SQLEditorMessages.editors_sql_warning_many_subtables_title,
                NLS.bind(
                    SQLEditorMessages.editors_sql_warning_many_subtables_text,
                    resultSetLimit
                ),
                true
            );
        }

        @Override
        public boolean isIncludedScript() {
            return partSite.getPart() instanceof IEditorPart ep &&
                ep.getEditorInput() instanceof IncludedScriptFileEditorInput;
        }

        @Nullable
        @Override
        public Object getExecutionController() {
            return partSite.getPart();
        }

        @Override
        public int getStatementTimeout() {
            return dataSourceContainer.getPreferenceStore().getInt(SQLPreferenceConstants.STATEMENT_TIMEOUT);
        }

        @Override
        public boolean isInvalidateBeforeExecute() {
            return dataSourceContainer.getPreferenceStore().getBoolean(SQLPreferenceConstants.STATEMENT_INVALIDATE_BEFORE_EXECUTE);
        }

        @Override
        public int getMaxResultSetsPerQuery() {
            return dataSourceContainer.getPreferenceStore().getInt(SQLPreferenceConstants.RESULT_SET_MAX_TABS_PER_QUERY);
        }

        @Override
        public boolean isKeepStatementOpen() {
            return dataSourceContainer.getPreferenceStore().getBoolean(ResultSetPreferences.KEEP_STATEMENT_OPEN);
        }

        @Override
        public boolean isShowExecutionResult(int resultSetCount, @NotNull DBCStatistics statistics) {
            final DBPPreferenceStore store = dataSourceContainer.getPreferenceStore();
            StatisticsTabOnExecutionBehavior statisticsTabOnExecutionBehavior = StatisticsTabOnExecutionBehavior.getByName(
                store.getString(SQLPreferenceConstants.SHOW_STATISTICS_ON_EXECUTION));
            switch (statisticsTabOnExecutionBehavior) {
                case ALWAYS:
                    return true;
                case NEVER:
                    return resultSetCount <= 0 || statistics.getRowsFetched() <= 0;
                case FOR_MULTIPLE_QUERIES:
                    if (resultSetCount <= 0 || statistics.getRowsUpdated() >= 0) {
                        // If there are no results or we have updated some rows, always display statistics
                        return true;
                    } else {
                        // Otherwise, display statistics if the option is set
                        return statistics.getStatementsCount() > 1;
                    }
                default:
                    return false;
            }

        }

        @NotNull
        @Override
        public String getDefaultResultSetName() {
            return SQLEditorMessages.editors_sql_data_grid;
        }

        @NotNull
        @Override
        public String getStatisticsResultSetName() {
            return SQLEditorMessages.editors_sql_statistics;
        }

        private int confirmQueryExecutionDialog(@NotNull final SQLQuery query, final boolean scriptMode) {
            final DBPConnectionType connectionType = dataSourceContainer.getConnectionConfiguration().getConnectionType();
            return new UITask<Integer>() {
                @Override
                protected Integer runTask() {
                    MessageDialogWithToggle dialog = new MessageDialogWithToggle(
                            UIUtils.getActiveWorkbenchShell(),
                            "Confirm query execution",
                            null,
                            "You are in '" + connectionType.getName() + "' connection.\nDo you confirm query execution?",
                            MessageDialog.WARNING, ConfirmationDialog.getButtonLabels(ConfirmationDialog.QUESTION_WITH_CANCEL), 0,
                            "Do not ask for " + connectionType.getName() + " connections", false)
                    {
                        @Override
                        protected boolean isResizable() {
                            return true;
                        }

                        @Override
                        protected IDialogSettings getDialogBoundsSettings() {
                            return UIUtils.getDialogSettings("DBeaver.SQLQueryConfirmDialog"); //$NON-NLS-1$
                        }

                        @Override
                        protected void createDialogAndButtonArea(Composite parent) {
                            dialogArea = createDialogArea(parent);
                            if (dialogArea.getLayoutData() instanceof GridData) {
                                ((GridData) dialogArea.getLayoutData()).grabExcessVerticalSpace = false;
                            }
                            Text messageText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
                            String text = query.getText();
                            if (text.length() > MAX_QUERY_PREVIEW_LENGTH) {
                                // Truncate string. Too big strings may freeze UI
                                text = CommonUtils.truncateString(text, MAX_QUERY_PREVIEW_LENGTH) +
                                    "... (truncated " + (text.length() - MAX_QUERY_PREVIEW_LENGTH) + " characters)";
                            }
                            messageText.setText(text);
                            GridData gd = new GridData(GridData.FILL_BOTH);
                            gd.heightHint = UIUtils.getFontHeight(messageText) * 4 + 10;
                            gd.horizontalSpan = 2;
                            messageText.setLayoutData(gd);
                            buttonBar = createButtonBar(parent);
                            // Apply to the parent so that the message gets it too.
                            applyDialogFont(parent);
                        }

                        @Override
                        protected void createButtonsForButtonBar(Composite parent)
                        {
                            createButton(parent, IDialogConstants.YES_ID, IDialogConstants.YES_LABEL, true);
                            createButton(parent, IDialogConstants.NO_ID, IDialogConstants.NO_LABEL, false);
                            if (scriptMode) {
                                createButton(parent, IDialogConstants.YES_TO_ALL_ID, IDialogConstants.YES_TO_ALL_LABEL, false);
                                createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
                            }
                        }
                    };
                    int result = dialog.open();
                    if (dialog.getToggleState()) {
                        connectionType.setConfirmExecute(false);
                        DBWorkbench.getPlatform().getDataSourceProviderRegistry().saveConnectionTypes();
                    }
                    return result;
                }
            }.execute();
        }
    }

}
