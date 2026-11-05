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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteJob;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPErrorAssistant;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryListener;
import org.jkiss.dbeaver.model.sql.SQLQueryResult;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 편집기 탭(SSMS의 쿼리 창 하나): 편집기, 실행/취소, 결과({@link QueryResultsView}), 상태 줄.
 *
 * 설계:
 * <ul>
 *     <li>편집기는 RSyntaxTextArea(SQL 구문 강조)다. 색은 테마마다 XML 테마 파일({@code SqlEditorLight.xml},
 *     {@code SqlEditorDark.xml})로 정하고, Look and Feel이 바뀌면({@code updateUI}) 현재 테마의 파일을 다시 적용한다.</li>
 *     <li>F5(메인 창 Query 메뉴)와 Execute 단추는 선택이 1자 이상이면 선택한 부분만, 아니면 전체를 실행한다
 *     (swing-frontend.md "쿼리 실행 사양"). 범위는 전체 텍스트 안의 위치로 넘겨 쿼리 위치가 편집기 위치와 같게 한다(오류 줄 번호).</li>
 *     <li>실행은 두 Job으로 나눈다. 준비 Job이 연결(필요하면 {@link SwingConnectionService}), 실행 문맥, 문장 나누기
 *     ({@link SQLScriptBatches}: SQL Server 등은 {@code GO} 배치 단위)를 하고, 실행 엔진 {@link SQLQueryExecuteJob}을 예약한다.
 *     엔진을 직접 실행하지 않고 예약하는 것은 {@code DataSourceJob}이 실행 중 연결을 잡아 두는 동작({@code acquire/release})을 그대로 쓰기 위해서다.
 *     EDT는 기다리지 않는다. Cancel은 지금 돌고 있는 Job을 취소한다(엔진 Job은 실행 중인 문장을 취소한다).</li>
 *     <li>엔진의 콜백({@link SQLQueryExecuteHandler}, {@link SQLQueryListener})은 Job 스레드에서 불리므로, 화면 변경은 모두
 *     {@link SwingUtilities#invokeLater}로 EDT에 넘긴다. 각 실행은 {@link Execution} 객체로 구분해, 끝난 실행의 늦은 결과가 다음 실행 화면에 섞이지 않게 한다.</li>
 *     <li>오류는 대화상자 대신 Messages 탭에 쓰고(오류 위치 = 편집기의 줄·열), 엔진의 "계속할까요" 질문
 *     ({@link SQLQueryExecuteHandler#handleExecutionError})에는 묻지 않고 멈춤으로 답한다. 오류가 나면 SSMS처럼 Messages 탭을 보여 준다.</li>
 *     <li>커밋 방식, 오류 처리 방식, 최대 행 수는 엔진 기본값(SWT 편집기 설정 기본값, 연결의 최대 행 수 설정)을 쓴다.
 *     실행 문맥은 연결의 기본(주) 문맥이다(편집기별 별도 연결은 아직 없음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 */
final class SqlEditorPanel extends JPanel {

    private static final Log log = Log.getLog(SqlEditorPanel.class);

    private static final DateTimeFormatter COMPLETION_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * The main window side of an editor.
     */
    interface Host {
        /**
         * The results of the editor changed; show them (if it is the active editor) on the Results or Messages tab.
         */
        void showResults(@NotNull SqlEditorPanel editor, boolean messages);

        /**
         * Execution started or finished (Query menu state).
         */
        void executionStateChanged(@NotNull SqlEditorPanel editor);
    }

    private final DBPDataSourceContainer dataSourceContainer;
    private final String title;
    private final Host host;
    private final SqlTextArea textArea;
    private final QueryResultsView resultsView = new QueryResultsView();
    private final Action executeAction;
    private final Action cancelAction;
    private final JLabel statusLabel = new JLabel();
    private final JLabel elapsedLabel = new JLabel();
    private final JLabel rowsLabel = new JLabel();
    private final Timer elapsedTimer;
    private final Object jobLock = new Object();
    // EDT only
    @Nullable
    private Execution execution;

    SqlEditorPanel(@NotNull DBPDataSourceContainer dataSourceContainer, @NotNull String fileName, @NotNull Host host) {
        super(new BorderLayout());
        this.dataSourceContainer = dataSourceContainer;
        this.title = fileName + " - " + dataSourceContainer.getName();
        this.host = host;

        executeAction = new AbstractAction("Execute") {
            @Override
            public void actionPerformed(ActionEvent e) {
                execute();
            }
        };
        executeAction.putValue(Action.SHORT_DESCRIPTION, "Execute the selection, or the whole script without a selection (F5)");
        cancelAction = new AbstractAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        };
        cancelAction.putValue(Action.SHORT_DESCRIPTION, "Cancel the executing query");
        cancelAction.setEnabled(false);

        textArea = new SqlTextArea();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();

        add(createToolBar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        elapsedTimer = new Timer(250, e -> {
            if (execution != null) {
                elapsedLabel.setText(formatElapsed(System.currentTimeMillis() - execution.startTime, false));
            }
        });
        statusLabel.setText("Ready");
    }

    @NotNull
    String getTitle() {
        return title;
    }

    @NotNull
    DBPDataSourceContainer getDataSourceContainer() {
        return dataSourceContainer;
    }

    @NotNull
    QueryResultsView getResultsView() {
        return resultsView;
    }

    boolean isExecuting() {
        return execution != null;
    }

    void focusEditor() {
        textArea.requestFocusInWindow();
    }

    /**
     * The tab is closing: stop a running query.
     */
    void dispose() {
        cancel();
        elapsedTimer.stop();
    }

    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(createToolBarButton(executeAction));
        toolBar.add(createToolBarButton(cancelAction));
        toolBar.addSeparator();
        JLabel connectionLabel = new JLabel(dataSourceContainer.getName());
        connectionLabel.setToolTipText("Connection of this query window");
        toolBar.add(connectionLabel);
        toolBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return toolBar;
    }

    @NotNull
    private static JButton createToolBarButton(@NotNull Action action) {
        JButton button = new JButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    /**
     * Query status strip at the bottom of the query window (SSMS): state, elapsed time, rows.
     */
    @NotNull
    private JComponent createStatusBar() {
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);
        right.add(elapsedLabel);
        right.add(rowsLabel);
        JPanel statusBar = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                Color separatorColor = UIManager.getColor("Separator.foreground"); //$NON-NLS-1$
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, separatorColor != null ? separatorColor : Color.GRAY),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
            }
        };
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(right, BorderLayout.EAST);
        return statusBar;
    }

    //////////////////////////////////////////////////////////
    // Execution (EDT)

    /**
     * Executes the selection if at least one character is selected, otherwise the whole editor text.
     */
    void execute() {
        if (execution != null) {
            return;
        }
        String text = textArea.getText();
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        int offset = 0;
        int length = text.length();
        if (selectionEnd - selectionStart >= 1) {
            offset = selectionStart;
            length = selectionEnd - selectionStart;
        }
        if (text.substring(offset, offset + length).isBlank()) {
            statusLabel.setText("Nothing to execute");
            return;
        }

        Execution run = new Execution(text, offset, length,
            dataSourceContainer.getPreferenceStore().getInt(ModelPreferences.RESULT_SET_MAX_ROWS));
        execution = run;
        resultsView.clear();
        statusLabel.setText(dataSourceContainer.isConnected() ? "Executing query…" : "Connecting…");
        elapsedLabel.setText(formatElapsed(0, false));
        rowsLabel.setText("");
        elapsedTimer.start();
        updateActions();
        host.showResults(this, false);

        AbstractJob prepareJob = new AbstractJob("Prepare query") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                prepareAndSchedule(run, monitor);
                return Status.OK_STATUS;
            }
        };
        synchronized (jobLock) {
            run.job = prepareJob;
        }
        prepareJob.schedule();
    }

    /**
     * Cancels the running job: the preparation (connecting) or the query execution.
     */
    void cancel() {
        Execution run = execution;
        if (run == null) {
            return;
        }
        synchronized (jobLock) {
            run.cancelRequested = true;
            if (run.job != null) {
                run.job.cancel();
            }
        }
        statusLabel.setText("Canceling…");
        cancelAction.setEnabled(false);
    }

    private void updateActions() {
        executeAction.setEnabled(execution == null);
        cancelAction.setEnabled(execution != null && !execution.cancelRequested);
        host.executionStateChanged(this);
    }

    /**
     * Called in the EDT when the run is over (the job finished, or preparation stopped early).
     */
    private void finishExecution(@NotNull Execution run, @NotNull IStatus status) {
        if (execution != run) {
            return;
        }
        execution = null;
        elapsedTimer.stop();
        long elapsed = System.currentTimeMillis() - run.startTime;

        if (status.getSeverity() == IStatus.ERROR && !run.hasErrors) {
            // Errors outside of a query (e.g. opening the session); query errors were already written by the listener
            run.hasErrors = true;
            resultsView.appendError(CommonUtils.notEmpty(status.getMessage()));
        }
        boolean canceled = run.cancelRequested || status.getSeverity() == IStatus.CANCEL;
        if (canceled) {
            resultsView.appendMessage("Query was canceled by user.");
        }
        resultsView.appendMessage("");
        resultsView.appendMessage("Completion time: " + OffsetDateTime.now().format(COMPLETION_TIME_FORMAT));

        if (run.hasErrors) {
            statusLabel.setText("Query completed with errors.");
        } else if (canceled) {
            statusLabel.setText("Query was canceled.");
        } else {
            statusLabel.setText("Query executed successfully.");
        }
        elapsedLabel.setText(formatElapsed(elapsed, true));
        long rows = run.rowsFetched.get();
        rowsLabel.setText(rows + (rows == 1 ? " row" : " rows"));
        updateActions();
        // As in SSMS: errors (or no result grids at all) bring the Messages tab to the front
        host.showResults(this, run.hasErrors || resultsView.getGridCount() == 0);
    }

    //////////////////////////////////////////////////////////
    // Execution (job threads)

    private void prepareAndSchedule(@NotNull Execution run, @NotNull DBRProgressMonitor monitor) {
        try {
            if (!dataSourceContainer.isConnected()) {
                // Reports connection errors itself (SwingConnectionService)
                DBUtils.initDataSource(monitor, dataSourceContainer, null);
            }
            DBPDataSource dataSource = dataSourceContainer.getDataSource();
            if (dataSource == null || !dataSourceContainer.isConnected()) {
                stopPreparation(run, run.cancelRequested ? Status.CANCEL_STATUS
                    : errorStatus("Not connected to '" + dataSourceContainer.getName() + "'"));
                return;
            }
            DBCExecutionContext context = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
            if (context == null) {
                stopPreparation(run, errorStatus("No execution context for '" + dataSourceContainer.getName() + "'"));
                return;
            }
            List<SQLScriptElement> elements = SQLScriptBatches.extractQueries(dataSource, run.text, run.offset, run.length);
            if (elements.isEmpty()) {
                stopPreparation(run, Status.OK_STATUS);
                return;
            }
            SQLScriptContext scriptContext = new SQLScriptContext(null, () -> context, null, new MessagesWriter(run), null);
            SQLQueryExecuteJob job = new SQLQueryExecuteJob(
                "Execute query", context, null, elements, scriptContext,
                new QueryListener(run), new QueryHandler(run, context, scriptContext));
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    IStatus result = event.getResult();
                    SwingUtilities.invokeLater(() -> finishExecution(run, result != null ? result : Status.OK_STATUS));
                }
            });
            synchronized (jobLock) {
                if (run.cancelRequested) {
                    stopPreparation(run, Status.CANCEL_STATUS);
                    return;
                }
                run.job = job;
                job.schedule();
            }
            post(run, () -> statusLabel.setText("Executing query…"));
        } catch (Throwable e) {
            log.debug("Query preparation failed", e);
            stopPreparation(run, errorStatus(CommonUtils.getAllExceptionMessages(e)));
        }
    }

    private void stopPreparation(@NotNull Execution run, @NotNull IStatus status) {
        SwingUtilities.invokeLater(() -> finishExecution(run, status));
    }

    @NotNull
    private static IStatus errorStatus(@NotNull String message) {
        return new Status(IStatus.ERROR, ChromiteSwingActivator.PLUGIN_ID, message);
    }

    /**
     * Runs a UI update in the EDT if the run is still the current one.
     */
    private void post(@NotNull Execution run, @NotNull Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (execution == run) {
                update.run();
            }
        });
    }

    /**
     * Messages of one finished query: warnings and PRINT output, rows affected, time, or the error with its position
     * in the editor.
     */
    private void reportQueryResult(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQueryResult result) {
        List<String> messages = new ArrayList<>();
        for (Throwable warning : CommonUtils.safeCollection(result.getWarnings())) {
            // SQL Server PRINT and RAISERROR (low severity), PostgreSQL NOTICE etc. arrive as SQLWarning
            messages.add(CommonUtils.notEmpty(warning.getMessage()));
        }
        Throwable error = result.getError();
        String errorText = null;
        if (error == null) {
            boolean anyCount = false;
            for (SQLQueryResult.ExecuteResult executeResult : result.getExecuteResults()) {
                Long count = executeResult.isResultSet() ? executeResult.getRowCount() : executeResult.getUpdateCount();
                if (count == null) {
                    continue;
                }
                anyCount = true;
                String line = "(" + count + (count == 1 ? " row affected)" : " rows affected)");
                if (executeResult.isResultSet() && run.maxRows > 0 && count >= run.maxRows) {
                    line += " - fetching stopped at the maximum of " + run.maxRows + " rows";
                }
                messages.add(line);
            }
            if (!anyCount && !run.cancelRequested) {
                // A canceled statement ends without an error and without counts; it did not complete
                messages.add("Commands completed successfully.");
            }
            messages.add("Execution time: " + result.getQueryTime() + " ms");
        } else {
            run.hasErrors = true;
            errorText = describeError(run, session, result.getStatement(), error);
            run.reportedErrors.add(error);
        }
        String finalErrorText = errorText;
        post(run, () -> {
            messages.forEach(resultsView::appendMessage);
            if (finalErrorText != null) {
                resultsView.appendError(finalErrorText);
            }
        });
    }

    /**
     * SSMS-like error header ({@code Msg 208, State 42S02, Line 3, Column 15}) followed by the error messages.
     * The position comes from the dialect's error assistant when it can locate the error, else it is the query start.
     */
    @NotNull
    private static String describeError(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQuery query, @NotNull Throwable error) {
        int errorOffset = -1;
        int line = -1;
        int column = -1;
        try {
            DBPErrorAssistant errorAssistant = DBUtils.getAdapter(DBPErrorAssistant.class, session.getDataSource());
            DBPErrorAssistant.ErrorPosition[] positions = errorAssistant == null ? null :
                errorAssistant.getErrorPosition(session.getProgressMonitor(), session.getExecutionContext(), query.getText(), error);
            if (positions != null && positions.length > 0) {
                DBPErrorAssistant.ErrorPosition position = positions[0];
                if (position.line < 0) {
                    if (position.position >= 0) {
                        errorOffset = query.getOffset() + position.position;
                    }
                } else {
                    // Line within the query, position within that line (both zero-based)
                    line = lineOf(run.text, query.getOffset()) + position.line;
                    column = position.position >= 0 ? position.position + 1 : -1;
                }
            }
        } catch (Throwable e) {
            log.debug("Can't get error position", e);
        }
        if (errorOffset >= 0 && errorOffset <= run.text.length()) {
            line = lineOf(run.text, errorOffset);
            column = errorOffset - run.text.lastIndexOf('\n', errorOffset - 1);
        } else if (line < 0) {
            line = lineOf(run.text, query.getOffset());
        }

        StringBuilder header = new StringBuilder();
        SQLException sqlException = findSqlException(error);
        if (sqlException != null) {
            header.append("Msg ").append(sqlException.getErrorCode());
            if (!CommonUtils.isEmpty(sqlException.getSQLState())) {
                header.append(", State ").append(sqlException.getSQLState());
            }
            header.append(", ");
        } else {
            header.append("Error, ");
        }
        header.append("Line ").append(line);
        if (column > 0) {
            header.append(", Column ").append(column);
        }
        return header + "\n" + CommonUtils.getAllExceptionMessages(error);
    }

    @Nullable
    private static SQLException findSqlException(@NotNull Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * One-based line number of an offset.
     */
    private static int lineOf(@NotNull String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    @NotNull
    private static String formatElapsed(long millis, boolean withMillis) {
        long seconds = millis / 1000;
        String text = String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60); //$NON-NLS-1$
        return withMillis ? text + String.format(".%03d", millis % 1000) : text; //$NON-NLS-1$
    }

    /**
     * State of one F5 run. Fields written in job threads are only read in the EDT after the job is done
     * (the done event is posted after all their updates), except the cancel flag which is guarded by {@code jobLock}.
     */
    private static final class Execution {
        final String text;
        final int offset;
        final int length;
        final long maxRows;
        final long startTime = System.currentTimeMillis();
        final AtomicLong rowsFetched = new AtomicLong();
        final List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();
        volatile boolean hasErrors;
        volatile boolean cancelRequested;
        @Nullable
        Job job;

        Execution(@NotNull String text, int offset, int length, long maxRows) {
            this.text = text;
            this.offset = offset;
            this.length = length;
            this.maxRows = maxRows;
        }
    }

    /**
     * Result receivers and user interaction of the execution engine (called in the job thread).
     */
    private final class QueryHandler implements SQLQueryExecuteHandler {
        private final Execution run;
        private final DBCExecutionContext context;
        private final SQLScriptContext scriptContext;

        QueryHandler(@NotNull Execution run, @NotNull DBCExecutionContext context, @NotNull SQLScriptContext scriptContext) {
            this.run = run;
            this.context = context;
            this.scriptContext = scriptContext;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            SQLQueryDataContainer dataContainer = new SQLQueryDataContainer(() -> context, query, scriptContext, log);
            return new ResultSetGridReceiver(dataContainer, data -> {
                run.rowsFetched.addAndGet(data.rows().size());
                post(run, () -> {
                    resultsView.addGrid(data);
                    rowsLabel.setText(run.rowsFetched.get() + " rows");
                    host.showResults(SqlEditorPanel.this, false);
                });
            });
        }

        @NotNull
        @Override
        public DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
            // The error is shown in the Messages tab instead of a Stop/Retry/Ignore dialog; the script stops (decision: see class doc)
            if (!run.reportedErrors.contains(error)) {
                // Failed before the query was sent (parameters, pragmas, control commands)
                run.hasErrors = true;
                post(run, () -> resultsView.appendError(CommonUtils.getAllExceptionMessages(error)));
            }
            return DBPPlatformUI.UserResponse.STOP;
        }
    }

    /**
     * Query events of the execution engine (called in the job thread).
     */
    private final class QueryListener implements SQLQueryListener {
        private final Execution run;

        QueryListener(@NotNull Execution run) {
            this.run = run;
        }

        @Override
        public void onEndQuery(@NotNull DBCSession session, @NotNull SQLQueryResult result, @NotNull DBCStatistics statistics) {
            reportQueryResult(run, session, result);
        }
    }

    /**
     * Output of script commands ({@code @echo} etc.) goes to the Messages tab.
     */
    private final class MessagesWriter extends Writer {
        private final Execution run;

        MessagesWriter(@NotNull Execution run) {
            this.run = run;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            String text = new String(buffer, offset, length);
            if (!text.isBlank()) {
                post(run, () -> resultsView.appendMessage(text.stripTrailing()));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * SQL text area with the Chromite colors of the current theme.
     */
    private static final class SqlTextArea extends RSyntaxTextArea {
        SqlTextArea() {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            setCodeFoldingEnabled(false);
            setTabSize(4);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getParent() != null) {
                // Look and feel switch: the tree update resets UI defaults first, then the theme colors are applied again
                SwingUtilities.invokeLater(this::applyTheme);
            }
        }

        void applyTheme() {
            String resource = ChromiteTheme.getCurrent() == ChromiteTheme.DARK ? "SqlEditorDark.xml" : "SqlEditorLight.xml"; //$NON-NLS-1$ //$NON-NLS-2$
            try (InputStream in = SqlEditorPanel.class.getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("SQL editor theme " + resource + " not found");
                    return;
                }
                Theme.load(in).apply(this);
            } catch (IOException e) {
                log.warn("Can't load SQL editor theme " + resource, e);
            }
        }
    }
}
