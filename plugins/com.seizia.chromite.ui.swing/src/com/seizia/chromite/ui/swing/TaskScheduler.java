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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskEvent;
import com.seizia.chromite.model.task.DBTTaskExecutionListener;
import com.seizia.chromite.model.task.DBTTaskFolderEvent;
import com.seizia.chromite.model.task.DBTTaskListener;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.model.task.DBTTaskRun;
import com.seizia.chromite.model.task.DBTaskUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceShellCommands;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.transfer.task.DTSavedTasks;
import com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled runs of the saved tasks of the active project (app change #104): the task side of the one {@link RunScheduler}.
 *
 * 설계:
 * <ul>
 *     <li>정의는 작업과 함께 {@code tasks.json}에 있다({@link DBTTask#getSchedule}: 시각 키와 {@code enabled}, 저장 시각 {@code since}).
 *     이 PC에서 실제로 돌지는 이 번들 설정 {@code databaseTasks.schedule.active.<프로젝트>/<작업>}이다(기본 꺼짐). 공유한 프로젝트의 예약이
 *     다른 PC마다 저절로 돌지 않게 하기 위해서다(쿼리 예약과 같은 규칙). 설정 내보내기에서도 뺀다({@code SettingsTransferCommands}).</li>
 *     <li>규칙은 레거시(DBeaver)처럼(사용자 결정 2026-11-12): 모든 작업 형식을 예약할 수 있고 예약 실행은 저장된 작업을 손으로 실행할 때와 같이
 *     돌린다. 손으로 실행할 때마다 묻는 확인(복원 확인, 대상 비우기·다시 만들기 경고, 스크립트 목록, 끝나고 실행할 프로그램)과 작업의 명령줄은
 *     일정을 켤 때 한 번 보이고 답을 받는다({@link #getConfirmations}, {@link TaskScheduleDialog}). 그 답은 동의 지문({@link #getConsentKey},
 *     {@code consent})으로 예약과 함께 저장하고, 작업을 고쳐 지문이 바뀌면 다시 켤 때까지 예약이 쉰다. 지문은 그 확인들이 근거로 삼는 설정만으로
 *     만든다: 네이티브 도구 작업은 설정 전체(명령줄이 거기서 나옴), 데이터 전송은 대상 비우기·다시 만들 테이블·끝나고 실행할 명령, SQL 스크립트
 *     작업은 스크립트와 연결. 다른 설정(출력 폴더 등)을 고쳐도 예약은 계속된다.</li>
 *     <li>실행: 예약 Job을 무인 실행으로 표시하고({@link UnattendedRuns}) 그 Job 안에서 작업 관리자의 {@code runTask}로 바로 실행한다.
 *     실행 기록의 "시작한 것"은 {@value #STARTED_BY}이다. 처리기 전에 {@link #checkBeforeRun}이 무인으로 할 수 없는 것(연결 없음, 바뀐 작업,
 *     저장되지 않은 비밀번호, 로컬 클라이언트 없음, 수동 커밋 세션의 SQL 스크립트)을 확인하고, 걸리면 처리기를 부르지 않고 실패한 실행으로 기록한다.
 *     같은 것을 일정을 켤 때 {@link #getWarnings}로 미리 알린다.</li>
 *     <li>SQL 스크립트 작업은 세션이 수동 커밋이면(작업의 "문장마다 커밋"이 꺼져 있으면) 예약 실행을 하지 않는다. 끝에 남은 트랜잭션을 물을 수
 *     없고, 묻지 않고 커밋·롤백하지 않는다는 규칙 때문에 열린 트랜잭션을 남기게 되기 때문이다.</li>
 *     <li>알림: 실패하면 토스트(오류), 성공하면 조용히(마지막 결과 열과 로그에 남음).</li>
 *     <li>놓친 실행: Chromite가 꺼져 있던 동안의 마지막 시각을 화면에 "놓침"으로 보이고({@link TaskEntry#getMissed}) 몰아서 실행하지 않는다.</li>
 *     <li>활성 프로젝트의 작업만 예약한다(Database Tasks 창이 보이는 작업과 같다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #104, docs/chromite/task-scheduling.md)
 */
final class TaskScheduler implements DBTTaskListener {

    private static final Log log = Log.getLog(TaskScheduler.class);

    /**
     * "Started by" of the run record of a scheduled run.
     */
    static final String STARTED_BY = "Chromite scheduler"; //$NON-NLS-1$
    static final String KEY_SINCE = "since"; //$NON-NLS-1$
    static final String KEY_CONSENT = "consent"; //$NON-NLS-1$

    private static final String KEY_PREFIX = "task:"; //$NON-NLS-1$
    private static final String PREF_ACTIVE = "databaseTasks.schedule.active."; //$NON-NLS-1$
    // Saved data transfer settings read to tell what the run would do (DTSavedTasks keeps its own names private)
    private static final String PROP_CONFIGURATION = "configuration"; //$NON-NLS-1$
    private static final String STREAM_CONSUMER = "StreamTransferConsumer"; //$NON-NLS-1$
    private static final String DATABASE_CONSUMER = "DatabaseTransferConsumer"; //$NON-NLS-1$

    /**
     * One scheduled task on this computer.
     */
    static final class TaskEntry extends RunScheduler.Entry {
        volatile DBTTask task;
        volatile ScheduleTiming timing;
        @Nullable
        volatile LocalDateTime since;
        // Why the last time was skipped, shown in the panel until the next run
        @Nullable
        volatile String note;

        TaskEntry(@NotNull DBTTask task, @NotNull ScheduleTiming timing, @Nullable LocalDateTime since) {
            this.task = task;
            this.timing = timing;
            this.since = since;
        }

        @NotNull
        @Override
        String getKey() {
            return getEntryKey(task);
        }

        @Nullable
        @Override
        ScheduleTiming getTiming() {
            return timing;
        }

        @Override
        boolean isActive() {
            return isActiveHere(task) && getNotRunnableReason(task) == null;
        }

        @Override
        void takeOver(@NotNull RunScheduler.Entry newer) {
            TaskEntry entry = (TaskEntry) newer;
            task = entry.task;
            timing = entry.timing;
            since = entry.since;
        }

        @Override
        void skipped(@NotNull String message) {
            log.info("Scheduled task '" + task.getName() + "': " + message); //$NON-NLS-1$ //$NON-NLS-2$
            note = NLS.bind(DatabaseTasksMessages.tasks_schedule_skipped, DatabaseTasksPanel.formatShort(LocalDateTime.now()));
        }

        @NotNull
        @Override
        AbstractJob createJob(boolean manual) {
            DBTTask runTask = task;
            AbstractJob job = new AbstractJob(NLS.bind(DatabaseTasksMessages.tasks_schedule_job, runTask.getName())) {
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    runScheduled(TaskEntry.this, runTask, monitor);
                    return Status.OK_STATUS;
                }
            };
            job.setUser(false);
            // Not a system job: the exit question lists it ("running jobs")
            job.setSystem(false);
            UnattendedRuns.begin(job, new UnattendedRuns.Run(runTask.getName(), UIServiceShellCommands.getTaskScope(runTask)));
            return job;
        }

        /**
         * The last time Chromite was not running at (no catch-up), or null.
         */
        @Nullable
        LocalDateTime getMissed(@NotNull LocalDateTime schedulerStart) {
            if (!timing.isEnabled()) {
                return null;
            }
            LocalDateTime previous = timing.previousRun(LocalDateTime.now(), lastRun);
            if (previous == null || !previous.isBefore(schedulerStart)) {
                return null;
            }
            if (since != null && !previous.isAfter(since)) {
                // The schedule did not exist yet
                return null;
            }
            return lastRun == null || lastRun.isBefore(previous) ? previous : null;
        }
    }

    private static TaskScheduler instance;

    @Nullable
    private DBTTaskManager listenedManager;
    private volatile LocalDateTime startTime = LocalDateTime.now();

    private TaskScheduler() {
    }

    @NotNull
    static synchronized TaskScheduler getInstance() {
        if (instance == null) {
            instance = new TaskScheduler();
        }
        return instance;
    }

    /**
     * Reads the schedules of the active project and follows its task changes (the main window opened).
     */
    synchronized void start() {
        startTime = LocalDateTime.now();
        DBTTaskManager manager = getTaskManager();
        if (manager != null && manager != listenedManager) {
            manager.getRegistry().addTaskListener(this);
            listenedManager = manager;
        }
        reload();
    }

    /**
     * Stops following the task changes (the main window closes; {@link RunScheduler#stop} cancels the runs).
     */
    synchronized void stop() {
        if (listenedManager != null) {
            listenedManager.getRegistry().removeTaskListener(this);
            listenedManager = null;
        }
    }

    @NotNull
    LocalDateTime getStartTime() {
        return startTime;
    }

    @Override
    public void handleTaskEvent(@NotNull DBTTaskEvent event) {
        reload();
    }

    @Override
    public void handleTaskFolderEvent(@NotNull DBTTaskFolderEvent event) {
    }

    @Nullable
    private static DBTTaskManager getTaskManager() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? null : project.getTaskManager();
    }

    /**
     * The tasks with a schedule, read again.
     */
    void reload() {
        DBTTaskManager manager = getTaskManager();
        List<TaskEntry> entries = new ArrayList<>();
        if (manager != null) {
            for (DBTTask task : manager.getAllTasks()) {
                ScheduleTiming timing = readTiming(task);
                if (timing == null) {
                    continue;
                }
                TaskEntry entry = new TaskEntry(task, timing, readSince(task));
                entry.lastRun = findLastScheduledRun(task);
                entries.add(entry);
            }
        }
        RunScheduler.getInstance().update(KEY_PREFIX, entries);
    }

    @Nullable
    TaskEntry getEntry(@NotNull DBTTask task) {
        return (TaskEntry) RunScheduler.getInstance().getEntry(getEntryKey(task));
    }

    @NotNull
    List<TaskEntry> getEntries() {
        List<TaskEntry> result = new ArrayList<>();
        for (RunScheduler.Entry entry : RunScheduler.getInstance().getEntries(KEY_PREFIX)) {
            result.add((TaskEntry) entry);
        }
        return result;
    }

    @NotNull
    private static String getEntryKey(@NotNull DBTTask task) {
        return KEY_PREFIX + getStateKey(task);
    }

    @NotNull
    private static String getStateKey(@NotNull DBTTask task) {
        return task.getProject().getId() + "/" + task.getId(); //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Definition

    /**
     * The schedule of the task, or null when it has none.
     */
    @Nullable
    static ScheduleTiming readTiming(@NotNull DBTTask task) {
        Map<String, String> schedule = task.getSchedule();
        if (schedule.isEmpty()) {
            return null;
        }
        ScheduleTiming timing = new ScheduleTiming();
        schedule.forEach(timing::readKey);
        return timing;
    }

    @Nullable
    private static LocalDateTime readSince(@NotNull DBTTask task) {
        String since = task.getSchedule().get(KEY_SINCE);
        try {
            return CommonUtils.isEmpty(since) ? null : LocalDateTime.parse(since);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Saves the schedule with the task (null removes it) and whether it runs on this computer.
     */
    static void saveSchedule(@NotNull DBTTask task, @Nullable ScheduleTiming timing, boolean active) throws DBException {
        Map<String, String> schedule = new LinkedHashMap<>();
        if (timing != null) {
            schedule.put(timing.getWhenKey(), timing.formatWhen());
            schedule.put(ScheduleTiming.KEY_ENABLED, String.valueOf(timing.isEnabled()));
            schedule.put(KEY_SINCE, LocalDateTime.now().withSecond(0).withNano(0).toString());
            // The user answered the task's confirmations for these settings (TaskScheduleDialog)
            schedule.put(KEY_CONSENT, getConsentKey(task));
        }
        task.setSchedule(schedule);
        // Fires a task event: the scheduler and the panel read the tasks again
        task.getProject().getTaskManager().updateTaskConfiguration(task);
        setActiveHere(task, timing != null && active);
    }

    static boolean isActiveHere(@NotNull DBTTask task) {
        return getPreferences().getBoolean(PREF_ACTIVE + getStateKey(task));
    }

    static void setActiveHere(@NotNull DBTTask task, boolean active) {
        DBPPreferenceStore preferences = getPreferences();
        if (active) {
            preferences.setValue(PREF_ACTIVE + getStateKey(task), true);
        } else {
            preferences.setToDefault(PREF_ACTIVE + getStateKey(task));
        }
        PrefUtils.savePreferenceStore(preferences);
        TaskEntry entry = getInstance().getEntry(task);
        if (entry != null && active) {
            RunScheduler.getInstance().resume(entry);
        }
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * The settings the confirmations of a hand run are about, as a fingerprint: the schedule stores it when the user turns
     * it on, and a different one later means the task was changed so that it would ask something new.
     */
    @NotNull
    static String getConsentKey(@NotNull DBTTask task) {
        StringBuilder material = new StringBuilder(task.getType().getId());
        if (NativeBackupRestore.isNativeToolTask(task.getType())) {
            // The command line (and the restore's input) comes from these settings
            material.append('\n').append(task.getProperties());
        } else if (DTSavedTasks.isScriptTask(task.getType())) {
            material.append('\n').append(task.getProperties().get("scriptFiles")) //$NON-NLS-1$
                .append('\n').append(task.getProperties().get("dataSources")); //$NON-NLS-1$
        } else if (DTSavedTasks.isTransferTask(task.getType())) {
            DTSavedTasks.TargetChanges changes = DTSavedTasks.getTargetChanges(task);
            material.append('\n').append(changes.truncate()).append('\n').append(changes.recreated())
                .append('\n').append(CommonUtils.notEmpty(getFinishCommand(task)));
        } else {
            material.append('\n').append(task.getProperties());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.toString().getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether the user answered the task's confirmations for its present settings when turning the schedule on.
     */
    static boolean isConsentValid(@NotNull DBTTask task) {
        return getConsentKey(task).equals(task.getSchedule().get(KEY_CONSENT));
    }

    /**
     * What a hand run of the task asks or warns about every time, in the same words: asked once when the schedule is turned on.
     */
    @NotNull
    static List<String> getConfirmations(@NotNull DBTTask task) {
        List<String> result = new ArrayList<>();
        StringBuilder model = new StringBuilder();
        if (DBTaskUtils.collectConfirmationMessages(model, task, (builder, t) -> false)) {
            result.add(model.toString().strip());
        }
        String details = DatabaseTasksPanel.describeRun(task);
        if (details != null) {
            result.add(details);
        }
        String command = getFinishCommand(task);
        if (!CommonUtils.isEmpty(command)) {
            result.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_finish_command, command));
        }
        return result;
    }

    /**
     * The command a data export runs when it ends ("execute process"), or null.
     */
    @Nullable
    private static String getFinishCommand(@NotNull DBTTask task) {
        Map<String, Object> stream = JSONUtils.getObject(JSONUtils.getObject(task.getProperties(), PROP_CONFIGURATION), STREAM_CONSUMER);
        Map<String, Object> processor = JSONUtils.getObject(JSONUtils.getObject(stream, "eventProcessors"), "executeCommand"); //$NON-NLS-1$ //$NON-NLS-2$
        return CommonUtils.nullIfEmpty(CommonUtils.toString(processor.get("command"))); //$NON-NLS-1$
    }

    /**
     * Why a scheduled run of the task may fail, as far as it can be told now (no connecting): shown when the schedule is
     * turned on, so that the user does not find out at the scheduled time.
     */
    @NotNull
    static List<String> getWarnings(@NotNull DBTTask task) {
        List<String> result = new ArrayList<>();
        String notRunnable = getNotRunnableReason(task);
        if (notRunnable != null) {
            result.add(notRunnable);
        }
        List<DBPDataSourceContainer> connections = new ArrayList<>();
        boolean autoCommitScript = false;
        if (DTSavedTasks.isScriptTask(task.getType())) {
            try {
                SQLScriptExecuteSettings script = DTSavedTasks.loadScriptTask(task);
                connections.addAll(script.getDataSources());
                autoCommitScript = script.isAutoCommit();
            } catch (DBException e) {
                log.debug("Can't read the script task", e);
            }
        } else {
            DBPDataSourceContainer container = getConnection(task);
            if (container != null) {
                connections.add(container);
            }
        }
        for (DBPDataSourceContainer container : connections) {
            if (isPasswordMissing(container)) {
                result.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_no_password, container.getName()));
            }
            if (DTSavedTasks.isScriptTask(task.getType()) && !autoCommitScript && isManualCommit(container)) {
                result.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_manual_commit, container.getName()));
            }
            if (hasOpenTransaction(container) && (DTSavedTasks.isScriptTask(task.getType()) || isDatabaseTarget(task))) {
                result.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_open_transaction, container.getName()));
            }
        }
        Map<String, Object> stream = JSONUtils.getObject(JSONUtils.getObject(task.getProperties(), PROP_CONFIGURATION), STREAM_CONSUMER);
        if (!stream.isEmpty() && !CommonUtils.toBoolean(stream.get("outputClipboard")) //$NON-NLS-1$
            && "ASK".equals(CommonUtils.toString(stream.get("dataFileConflictBehavior"), "ASK"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            result.add(DatabaseTasksMessages.tasks_schedule_file_ask);
        }
        return result;
    }

    /**
     * Whether a data transfer task loads into a database (import, table copy), whose session may hold a transaction.
     */
    private static boolean isDatabaseTarget(@NotNull DBTTask task) {
        return DTSavedTasks.isTransferTask(task.getType())
            && !JSONUtils.getObject(JSONUtils.getObject(task.getProperties(), PROP_CONFIGURATION), DATABASE_CONSUMER).isEmpty();
    }

    private static boolean isPasswordMissing(@NotNull DBPDataSourceContainer container) {
        if (container.isConnected() || CommonUtils.isEmpty(container.getConnectionConfiguration().getUserName())) {
            return false;
        }
        try {
            return !container.isCredentialsSaved();
        } catch (DBException e) {
            log.debug("Can't read whether the credentials are saved", e);
            // Unknown: a scheduled run must not end up asking for them
            return true;
        }
    }

    private static boolean hasOpenTransaction(@NotNull DBPDataSourceContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null || !container.isConnected()) {
            return false;
        }
        DBCExecutionContext context = DBUtils.getDefaultContext(dataSource, false);
        // Also an explicit BEGIN in an auto-commit session (the query manager's transaction state, as the data load checks)
        return context != null && (QMUtils.isTransactionActive(context) || QMUtils.getTransactionState(context).isOpen());
    }

    /**
     * Why the scheduled task does not run on this computer now, or null: its connection is not here, the native tool has
     * no local client (Chromite does not download one), or the task was changed after the schedule was turned on.
     */
    @Nullable
    static String getNotRunnableReason(@NotNull DBTTask task) {
        DBPDataSourceContainer container = getConnection(task);
        if (container == null) {
            return DatabaseTasksMessages.tasks_schedule_no_connection;
        }
        if (!task.getSchedule().isEmpty() && !isConsentValid(task)) {
            return DatabaseTasksMessages.tasks_schedule_changed;
        }
        if (NativeBackupRestore.isNativeToolTask(task.getType()) && NativeBackupRestore.findConnectionClient(container) == null) {
            return NLS.bind(DatabaseTasksMessages.tasks_schedule_no_client, container.getName());
        }
        return null;
    }

    @Nullable
    private static DBPDataSourceContainer getConnection(@NotNull DBTTask task) {
        return NativeBackupRestore.isNativeToolTask(task.getType()) ? NativeBackupRestore.getTaskConnection(task)
            : DTSavedTasks.findTaskConnection(task);
    }

    //////////////////////////////////////////////////////////
    // A run (job thread)

    /**
     * The start time of the task's last scheduled run, or null (an interval schedule keeps its rhythm over a restart).
     */
    @Nullable
    private static LocalDateTime findLastScheduledRun(@NotNull DBTTask task) {
        DBTTaskRun[] runs = task.getAllRuns();
        for (int i = runs.length - 1; i >= 0; i--) {
            if (STARTED_BY.equals(runs[i].getStartedBy())) {
                return LocalDateTime.ofInstant(runs[i].getStartTime().toInstant(), ZoneId.systemDefault()).withNano(0);
            }
        }
        return null;
    }

    private static void runScheduled(@NotNull TaskEntry entry, @NotNull DBTTask task, @NotNull DBRProgressMonitor monitor) {
        entry.lastRun = LocalDateTime.now().withNano(0);
        entry.note = null;
        UnattendedRuns.Run unattended = UnattendedRuns.current();
        // A transaction open before the run is not this run's (the pre-check then fails the run without running it)
        List<String> openBefore = findLeftOpenTransactions(task);
        Throwable failure = null;
        try {
            task.getProject().getTaskManager().runTask(monitor, task, new DBTTaskExecutionListener() {
                @Override
                public void taskStarted(@Nullable DBTTask t) {
                }

                @Override
                public void taskFinished(@Nullable DBTTask t, @Nullable Object result, @Nullable Throwable error, @Nullable Object settings) {
                }

                @Override
                public void subTaskFinished(@Nullable DBTTask t, @Nullable Throwable error, @Nullable Object settings) {
                }

                @NotNull
                @Override
                public String getStartedBy() {
                    return STARTED_BY;
                }

                @Override
                public void checkBeforeRun(@NotNull DBTTask t) throws DBException {
                    TaskScheduler.checkBeforeRun(t);
                }

                @Override
                public boolean isTaskConfirmed(@NotNull DBTTask t) {
                    // checkBeforeRun failed the run otherwise
                    return isConsentValid(t);
                }
            });
        } catch (Throwable e) {
            failure = e;
            log.debug("Scheduled task failed", e);
        }
        DBTTaskRun run = task.getLastRun();
        List<String> leftOpen = findLeftOpenTransactions(task);
        leftOpen.removeAll(openBefore);
        if (unattended != null) {
            unattended.getNotes().addAll(leftOpen);
        }
        if (unattended != null && run != null && !unattended.getNotes().isEmpty()) {
            appendToLog(task, run, unattended.getNotes());
        }
        boolean success = failure == null && run != null && run.isRunSuccess() && leftOpen.isEmpty();
        log.info("Scheduled task '" + task.getName() + "' " + (success ? "succeeded" : "failed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!success && !monitor.isCanceled()) {
            String reason = failure != null ? ErrorText.describe(failure)
                : !leftOpen.isEmpty() ? String.join("\n", leftOpen) //$NON-NLS-1$
                : run == null ? "" : DatabaseTasksPanel.describeError(run); //$NON-NLS-1$
            DBWorkbench.getPlatformUI().showNotification(NLS.bind(DatabaseTasksMessages.tasks_schedule_failed, task.getName()), reason, true, null);
        }
    }

    /**
     * What an unattended run can't do, checked before the handler starts: the run fails with the reason in its log.
     */
    private static void checkBeforeRun(@NotNull DBTTask task) throws DBException {
        String reason = getNotRunnableReason(task);
        if (reason != null) {
            throw new DBException(reason);
        }
        List<DBPDataSourceContainer> connections = new ArrayList<>();
        SQLScriptExecuteSettings script = null;
        if (DTSavedTasks.isScriptTask(task.getType())) {
            script = DTSavedTasks.loadScriptTask(task);
            connections.addAll(script.getDataSources());
        } else {
            connections.add(getConnection(task));
        }
        for (DBPDataSourceContainer container : connections) {
            if (isPasswordMissing(container)) {
                throw new DBException(NLS.bind(DatabaseTasksMessages.tasks_schedule_no_password, container.getName()));
            }
            if (script != null && !script.isAutoCommit() && isManualCommit(container)) {
                throw new DBException(NLS.bind(DatabaseTasksMessages.tasks_schedule_manual_commit, container.getName()));
            }
            // The script handler asks only about a transaction of manual-commit mode; an explicit BEGIN left open in an
            // auto-commit session would take the scheduled script's changes
            if (script != null && hasOpenTransaction(container)) {
                throw new DBException(NLS.bind(DatabaseTasksMessages.tasks_schedule_open_transaction, container.getName()));
            }
        }
    }

    /**
     * Whether the scripts would run in a manual-commit session: the main session's mode when connected, else the
     * connection's setting.
     */
    private static boolean isManualCommit(@NotNull DBPDataSourceContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource != null && container.isConnected()) {
            DBCExecutionContext context = DBUtils.getDefaultContext(dataSource, false);
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            return txnManager != null && txnManager.isSupportsTransactions() && !isAutoCommit(txnManager);
        }
        return !container.isDefaultAutoCommit();
    }

    private static boolean isAutoCommit(@NotNull DBCTransactionManager txnManager) {
        try {
            return txnManager.isAutoCommit();
        } catch (Exception e) {
            log.debug("Can't read the commit mode", e);
            // Unknown: treated as manual commit, so that the run does not leave a transaction
            return false;
        }
    }

    /**
     * The sessions an SQL script task left with an open transaction (an explicit BEGIN in an auto-commit session: the script
     * handler asks only in manual-commit mode, and a scheduled run of a manual-commit session does not start). Nothing is
     * committed or rolled back: the run is reported as failed with this, so the transaction is not left silently.
     */
    @NotNull
    private static List<String> findLeftOpenTransactions(@NotNull DBTTask task) {
        List<String> result = new ArrayList<>();
        if (!DTSavedTasks.isScriptTask(task.getType())) {
            return result;
        }
        try {
            for (DBPDataSourceContainer container : DTSavedTasks.loadScriptTask(task).getDataSources()) {
                if (hasOpenTransaction(container)) {
                    result.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_left_open, container.getName()));
                }
            }
        } catch (DBException e) {
            log.debug("Can't read the script task", e);
        }
        return result;
    }

    /**
     * Adds what the run could not ask or show to the end of its log.
     */
    private static void appendToLog(@NotNull DBTTask task, @NotNull DBTTaskRun run, @NotNull List<String> notes) {
        Path logFile = task.getRunLog(run);
        if (logFile == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String note : notes) {
            text.append(note).append(System.lineSeparator());
        }
        try {
            Files.writeString(logFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Can't add to the task log", e);
        }
    }
}
