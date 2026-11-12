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

import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteJob;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCAttributeMetaData;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryListener;
import com.seizia.chromite.model.sql.SQLQueryResult;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query Library 예약 실행기(Chromite가 실행 중일 때만).
 *
 * 설계:
 * <ul>
 *     <li>타이머, 겹침 건너뛰기, 일시 정지, 시작과 정지는 저장된 작업 예약과 같은 {@link RunScheduler}가 한다. 이 클래스는 쿼리 항목
 *     ({@link Entry})이 무엇을 실행하고 어떻게 기록하는지만 정한다. 겹쳐 건너뛴 실행은 기록에 {@code SKIPPED}로 남는다.</li>
 *     <li>예약 목록은 라이브러리를 읽을 때마다 바뀐다({@link #update}). 이 PC에서 켠 예약({@link QueryLibraryState#isScheduleActive})만 돈다.
 *     다음 실행 시각은 지금 이후의 첫 시각이다(기록의 마지막 실행을 기준으로 간격을 이어 감). 꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.</li>
 *     <li>실행은 쿼리 창 없이 같은 엔진({@link SQLQueryExecuteJob}, GO 배치는 {@link SQLScriptBatches})과 같은 바인딩({@link QueryParameterBinder})을 쓴다.
 *     매개변수 값은 예약의 값, 없으면 정의의 기본값이다. 결과는 첫 결과 집합의 행 수와 앞 {@value #SNAPSHOT_ROWS}행만 받는다.</li>
 *     <li>실행마다 격리된 실행 문맥(연결)을 열고 닫는다({@code DBSInstance.openIsolatedContext}). 오래 걸리는 예약 쿼리가 쿼리 창이 쓰는 기본 문맥을
 *     잡고 있지 않게, 그리고 쿼리의 기본 DB/스키마가 쿼리 창의 것을 바꾸지 않게 하기 위해서다(2026-11-06 확인: 기본 문맥을 같이 쓰면 80초 쿼리 뒤에 다른 예약이 줄을 섰다).</li>
 *     <li>대상 연결이 끊겨 있으면 저장된 자격 증명이 있을 때만 연결한다. 백그라운드에서 비밀번호를 묻는 대화상자를 띄우지 않기 위해서다.
 *     확인이 필요한 문장(운영 연결의 변경 문장)은 묻지 않고 건너뛴다.</li>
 *     <li>조건이 맞으면 알림 토스트를 띄우고 쿼리에 경고 표시를 남긴다({@link QueryLibraryState#setAlert}, 트리 배지). 메일 등 외부 발송은 없다.</li>
 *     <li>앱을 끝낼 때 {@link RunScheduler#stop}이 타이머를 멈추고 돌고 있는 실행과 엔진 Job을 취소한다({@link Entry#cancel}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-12 타이머·겹침·일시 정지·시작과 정지를 {@link RunScheduler}로 옮김(저장된 작업 예약과 스케줄러 하나, 앱 변경 #104,
 *   docs/chromite/task-scheduling.md "하나의 스케줄러"). 시작과 정지는 Query Library 창이 아니라 메인 창이 부름
 */
final class QueryScheduler {

    private static final Log log = Log.getLog(QueryScheduler.class);

    static final int SNAPSHOT_ROWS = 20;
    // Keys of the query entries in the shared scheduler
    private static final String KEY_PREFIX = "query:"; //$NON-NLS-1$

    /**
     * State of one scheduled query on this computer.
     */
    static final class Entry extends RunScheduler.Entry {
        volatile QueryLibraryItem item;
        volatile QueryLibraryHistory.Status lastStatus;
        volatile String lastMessage = ""; //$NON-NLS-1$
        // The engine job of the running query, canceled when the application closes
        volatile SQLQueryExecuteJob engineJob;

        Entry(@NotNull QueryLibraryItem item) {
            this.item = item;
        }

        @NotNull
        @Override
        String getKey() {
            return KEY_PREFIX + item.getStateKey();
        }

        @Nullable
        @Override
        ScheduleTiming getTiming() {
            return item.getSchedule();
        }

        @Override
        boolean isActive() {
            return QueryLibraryState.isScheduleActive(item);
        }

        @Override
        void takeOver(@NotNull RunScheduler.Entry newer) {
            item = ((Entry) newer).item;
        }

        @Override
        void skipped(@NotNull String message) {
            log.info("Scheduled query '" + item.getName() + "': " + message);
            QueryLibraryHistory.append(item.getStateKey(), new QueryLibraryHistory.Run(LocalDateTime.now().withNano(0), "", 0, 0,
                QueryLibraryHistory.Status.SKIPPED, message, List.of()));
            lastMessage = message;
        }

        @NotNull
        @Override
        AbstractJob createJob(boolean manual) {
            QueryLibraryItem runItem = item;
            AbstractJob job = new AbstractJob("Scheduled query " + runItem.getName()) {
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    getInstance().runQuery(Entry.this, runItem, monitor);
                    return Status.OK_STATUS;
                }
            };
            job.setUser(false);
            job.setSystem(!manual);
            return job;
        }

        @Override
        void cancel() {
            SQLQueryExecuteJob running = engineJob;
            if (running != null) {
                running.cancel();
            }
        }
    }

    private static QueryScheduler instance;

    private QueryScheduler() {
    }

    @NotNull
    static synchronized QueryScheduler getInstance() {
        if (instance == null) {
            instance = new QueryScheduler();
        }
        return instance;
    }

    /**
     * Called in the EDT when the scheduled queries, their state or their history changed.
     */
    void addListener(@NotNull Runnable listener) {
        RunScheduler.getInstance().addListener(listener);
    }

    void removeListener(@NotNull Runnable listener) {
        RunScheduler.getInstance().removeListener(listener);
    }

    /**
     * The queries with a schedule (enabled in the file) after the library was read: new ones get their next run time
     * (an interval follows the last run of the history), known ones keep it unless their schedule changed.
     */
    void update(@NotNull Collection<QueryLibraryItem> scheduledItems) {
        List<Entry> newer = new ArrayList<>();
        for (QueryLibraryItem item : scheduledItems) {
            if (item.getSchedule() == null) {
                continue;
            }
            Entry entry = new Entry(item);
            List<QueryLibraryHistory.Run> runs = QueryLibraryHistory.getRuns(item.getStateKey());
            if (!runs.isEmpty()) {
                QueryLibraryHistory.Run last = runs.getLast();
                entry.lastRun = last.time();
                entry.lastStatus = last.status();
                entry.lastMessage = last.message();
            }
            newer.add(entry);
        }
        RunScheduler.getInstance().update(KEY_PREFIX, newer);
    }

    /**
     * Scheduled queries for the overview, by name.
     */
    @NotNull
    List<Entry> getEntries() {
        List<Entry> result = new ArrayList<>();
        for (RunScheduler.Entry entry : RunScheduler.getInstance().getEntries(KEY_PREFIX)) {
            result.add((Entry) entry);
        }
        result.sort((a, b) -> QueryLibrary.compareNatural(a.item.getName(), b.item.getName()));
        return result;
    }

    /**
     * Pause (inactive here) or resume a scheduled query on this computer. A resumed query runs at its next time from now.
     */
    void setActive(@NotNull Entry entry, boolean active) {
        QueryLibraryState.setScheduleActive(entry.item, active);
        if (active) {
            RunScheduler.getInstance().resume(entry);
        } else {
            RunScheduler.getInstance().fireChanged();
        }
    }

    /**
     * Runs a scheduled query now (overview "Run Now"), unless it is running.
     */
    void runNow(@NotNull Entry entry) {
        RunScheduler.getInstance().runNow(entry);
    }
    //////////////////////////////////////////////////////////
    // A run (job thread)

    private void runQuery(@NotNull Entry entry, @NotNull QueryLibraryItem item, @NotNull DBRProgressMonitor monitor) {
        QueryLibrarySchedule schedule = item.getSchedule();
        if (schedule == null) {
            return;
        }
        List<QueryLibrarySchedule.Target> targets = schedule.getTargets();
        if (targets.isEmpty()) {
            record(entry, item, new QueryLibraryHistory.Run(LocalDateTime.now().withNano(0), "", 0, 0, QueryLibraryHistory.Status.ERROR,
                ToolsMessages.scheduler_no_target, List.of()), schedule);
            return;
        }
        for (QueryLibrarySchedule.Target target : targets) {
            if (monitor.isCanceled()) {
                return;
            }
            record(entry, item, runOnTarget(entry, item, schedule, target, monitor), schedule);
        }
    }

    @NotNull
    private QueryLibraryHistory.Run runOnTarget(
        @NotNull Entry entry,
        @NotNull QueryLibraryItem item,
        @NotNull QueryLibrarySchedule schedule,
        @NotNull QueryLibrarySchedule.Target target,
        @NotNull DBRProgressMonitor monitor
    ) {
        LocalDateTime start = LocalDateTime.now().withNano(0);
        long startMillis = System.currentTimeMillis();
        DBPDataSourceContainer container = QueryLibraryEditor.findConnection(target.id(), target.name());
        if (container == null) {
            return failed(start, target.name(), 0, NLS.bind(ToolsMessages.scheduler_connection_not_found, target.name()));
        }
        String server = container.getName();
        try {
            if (!container.isConnected()) {
                if (!container.isCredentialsSaved() && !CommonUtils.isEmpty(container.getConnectionConfiguration().getUserName())) {
                    return new QueryLibraryHistory.Run(start, server, 0, 0, QueryLibraryHistory.Status.SKIPPED,
                        ToolsMessages.scheduler_password_not_saved, List.of());
                }
                DBUtils.initDataSource(monitor, container, null);
            }
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null || !container.isConnected()) {
                return failed(start, server, System.currentTimeMillis() - startMillis, ToolsMessages.library_not_connected);
            }
            // An own connection: a long scheduled query must not hold the query windows' connection, and its
            // default database/schema must not change theirs
            DBCExecutionContext context = dataSource.getDefaultInstance().openIsolatedContext(monitor, "Chromite scheduled query", null);
            try {
                return execute(item, schedule, dataSource, context, server, start, startMillis, monitor, entry);
            } finally {
                context.close();
            }
        } catch (Throwable e) {
            log.debug("Scheduled query failed", e);
            return failed(start, server, System.currentTimeMillis() - startMillis, ErrorText.describe(e));
        }
    }

    @NotNull
    private QueryLibraryHistory.Run execute(
        @NotNull QueryLibraryItem item,
        @NotNull QueryLibrarySchedule schedule,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context,
        @NotNull String server,
        @NotNull LocalDateTime start,
        long startMillis,
        @NotNull DBRProgressMonitor monitor,
        @NotNull Entry entry
    ) throws DBException {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String name : QueryParameterBinder.detect(item.getSql()).keySet()) {
                String value = schedule.getParameterValues().get(name);
                QueryLibraryParameter definition = item.findParameter(name);
                values.put(name, value != null ? value : definition != null ? CommonUtils.notEmpty(definition.getDefaultValue()) : ""); //$NON-NLS-1$
            }
            QueryParameterBinder binder = new QueryParameterBinder(item, values);
            List<SQLScriptElement> elements = binder.prepare(monitor, context,
                SQLScriptBatches.extractQueries(dataSource, item.getSql(), 0, item.getSql().length()));
            if (elements.isEmpty()) {
                return failed(start, server, 0, ToolsMessages.scheduler_query_empty);
            }
            Collector collector = new Collector();
            SQLScriptContext scriptContext = new SQLScriptContext(null, () -> context, null, new StringWriter(), null);
            SQLQueryExecuteJob job = new SQLQueryExecuteJob("Scheduled query " + item.getName(), context, null, elements, scriptContext,
                collector, new Handler(collector, binder));
            job.setUser(false);
            job.setSystem(true);
            entry.engineJob = job;
            job.schedule();
            try {
                job.join();
            } finally {
                entry.engineJob = null;
            }
            long duration = System.currentTimeMillis() - startMillis;
            if (collector.error != null) {
                return failed(start, server, duration, collector.error);
            }
            if (monitor.isCanceled()) {
                return new QueryLibraryHistory.Run(start, server, duration, collector.rows, QueryLibraryHistory.Status.SKIPPED,
                    ToolsMessages.scheduler_canceled, collector.snapshot);
            }
            QueryLibrarySchedule.Condition condition = schedule.getCondition();
            String firstValue = collector.snapshot.size() > 1 && !collector.snapshot.get(1).isEmpty() ? collector.snapshot.get(1).getFirst() : null;
            boolean alert = condition != null && collector.resultSets > 0 && condition.matches(collector.rows, firstValue);
            String message = NLS.bind(collector.rows == 1 ? ToolsMessages.scheduler_result_row : ToolsMessages.scheduler_result_rows, collector.rows);
            if (condition != null) {
                message = NLS.bind(alert ? ToolsMessages.scheduler_result_condition_met : ToolsMessages.scheduler_result_condition_not_met,
                    message, condition.describe());
            }
            return new QueryLibraryHistory.Run(start, server, duration, collector.rows,
                alert ? QueryLibraryHistory.Status.ALERT : QueryLibraryHistory.Status.OK, message, collector.snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new QueryLibraryHistory.Run(start, server, System.currentTimeMillis() - startMillis, 0, QueryLibraryHistory.Status.SKIPPED,
                ToolsMessages.scheduler_canceled, List.of());
        }
    }

    @NotNull
    private static QueryLibraryHistory.Run failed(@NotNull LocalDateTime start, @NotNull String server, long duration, @NotNull String message) {
        return new QueryLibraryHistory.Run(start, server, duration, 0, QueryLibraryHistory.Status.ERROR, message, List.of());
    }

    /**
     * Writes the run to the history, remembers it, and alerts (toast and tree badge) if the condition matched.
     */
    private void record(@NotNull Entry entry, @NotNull QueryLibraryItem item, @NotNull QueryLibraryHistory.Run run, @NotNull QueryLibrarySchedule schedule) {
        QueryLibraryHistory.append(item.getStateKey(), run);
        entry.lastRun = run.time();
        entry.lastStatus = run.status();
        entry.lastMessage = run.message();
        if (run.status() == QueryLibraryHistory.Status.ALERT) {
            QueryLibraryState.setAlert(item, true);
            DBWorkbench.getPlatformUI().showNotification(item.getName() + " - " + run.server(), run.message(), true,
                () -> SwingUtilities.invokeLater(() -> QueryRunHistoryDialog.open(null, item)));
        } else if (run.status() == QueryLibraryHistory.Status.ERROR) {
            DBWorkbench.getPlatformUI().showWarningNotification(item.getName() + " - " + run.server(), run.message());
        }
        RunScheduler.getInstance().fireChanged();
    }

    /**
     * Counts the rows of the first result set and keeps its column names and first rows as text; collects errors.
     */
    private static final class Collector implements SQLQueryListener {
        final List<List<String>> snapshot = new ArrayList<>();
        volatile long rows;
        volatile int resultSets;
        @Nullable
        volatile String error;

        @Override
        public void onEndQuery(@NotNull DBCSession session, @NotNull SQLQueryResult result, @NotNull DBCStatistics statistics) {
            if (result.getError() != null && error == null) {
                error = ErrorText.describe(result.getError());
            }
        }

        @Nullable
        DBDDataReceiver createReceiver() {
            if (resultSets++ > 0) {
                // Only the first result set counts
                return null;
            }
            return new DBDDataReceiver() {
                private int columns;

                @Override
                public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) throws DBException {
                    List<String> names = new ArrayList<>();
                    if (resultSet.getMeta() != null) {
                        for (DBCAttributeMetaData attribute : resultSet.getMeta().getAttributes()) {
                            names.add(clean(attribute.getLabel()));
                        }
                    }
                    columns = names.size();
                    snapshot.add(names);
                }

                @Override
                public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
                    rows++;
                    if (snapshot.size() <= SNAPSHOT_ROWS) {
                        List<String> row = new ArrayList<>(columns);
                        for (int i = 0; i < columns; i++) {
                            row.add(clean(CommonUtils.toString(resultSet.getAttributeValue(i))));
                        }
                        snapshot.add(row);
                    }
                }

                @Override
                public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
                }

                @Override
                public void close() {
                }
            };
        }

        @NotNull
        private static String clean(@Nullable String value) {
            // The history file separates cells and rows with these characters
            return CommonUtils.notEmpty(value).replace('\u001F', ' ').replace('\u001E', ' ');
        }
    }

    /**
     * Engine callbacks of a scheduled run: no dialogs (a background run can't ask), binding by the parameter binder.
     */
    private static final class Handler implements SQLQueryExecuteHandler {
        private final Collector collector;
        private final QueryParameterBinder binder;

        Handler(@NotNull Collector collector, @NotNull QueryParameterBinder binder) {
            this.collector = collector;
            this.binder = binder;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            return collector.createReceiver();
        }

        @NotNull
        @Override
        public QueryConfirmation confirmQueryExecution(@NotNull SQLQuery query, boolean scriptMode) {
            if (collector.error == null) {
                collector.error = ToolsMessages.scheduler_confirmation_needed;
            }
            return QueryConfirmation.CANCEL;
        }

        @NotNull
        @Override
        public DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
            if (collector.error == null) {
                collector.error = ErrorText.describe(error);
            }
            return DBPPlatformUI.UserResponse.STOP;
        }

        @Override
        public boolean confirmFetchManyResultSets(int resultSetLimit) {
            return false;
        }

        @Override
        public boolean hasBoundParameters(@NotNull SQLQuery query) {
            return binder.isBound(query);
        }

        @Override
        public void bindParameters(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException {
            binder.bind(statement, query);
        }
    }
}
