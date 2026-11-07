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
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLEvent;
import com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventDefinition;
import com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventScheduler;
import com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventSchedulerException;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchedulerJob;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerException;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerManager;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerMonitor;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerRunDetail;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerRunningJob;
import com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerSchedule;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronException;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronHistory;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronJob;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronManager;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronRunDetail;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronScheduler;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Object Explorer commands of the job schedulers other than SQL Server Agent: Oracle Scheduler jobs, pg_cron jobs and
 * MySQL / MariaDB events (New Job / New Event…, Run Now…, Stop Job, Enable, Disable, View History, View Running Jobs,
 * Event Scheduler Status…, and the jobs' Properties and Delete).
 *
 * 설계: SQL Server 에이전트의 {@link AgentCommands}와 같은 모양이다. 탐색기({@link ObjectExplorerPanel})에는 메뉴를 붙이는 줄과 Delete·속성·
 * 두 번 클릭·보조 글자의 갈래만 두고 판단과 실행은 이 클래스가 한다. 메뉴 항목은 고른 노드에 맞는 것만 보인다.
 * <ul>
 *     <li>권한 먼저: 로그인의 권한(Oracle: CREATE JOB·CREATE ANY JOB·MANAGE SCHEDULER, MySQL: 데이터베이스의 EVENT, pg_cron: cron 스키마
 *     USAGE)이 모자라면 메뉴를 끄고 도구 설명으로 이유를 알린다. Oracle·MySQL의 권한과 스케줄러 상태는 노드 객체가 없는 DBeaver 폴더에
 *     붙으므로 여기서 Job으로 읽어 데이터 원본(데이터베이스)마다 잠시 담아 둔다({@link #getOracleAccess}, {@link #getMySQLAccess}).
 *     읽기 전이거나 읽지 못했으면 막지 않고 서버의 응답에 맡긴다. pg_cron은 노드 객체({@link PostgreCronScheduler})가 상태를 들고 있다.</li>
 *     <li>모든 DB 호출은 진행 대화상자({@link ProgressDialog})의 작업 스레드나 Job에서 한다. 바꾸는 문은 모델이 격리된 연결에서 실행한다
 *     (사용자 편집기 세션의 트랜잭션은 건드리지 않는다).</li>
 *     <li>지금 실행·중지·사용·사용 안 함은 SQL Server 에이전트처럼 바로 실행하고 알림으로 결과를 알린다. 속성 창과 삭제는 실행할
 *     스크립트를 먼저 보여 준다.</li>
 *     <li>실행 뒤에는 탐색기 노드(작업 폴더, pg_cron 노드)를 모델에서 다시 읽는다. 다른 곳에서 새로 읽은 노드 이벤트이므로 Object Explorer는
 *     펼친 폴더를 그대로 두고 자식만 다시 그린다.</li>
 *     <li>Oracle 명령은 Oracle 데이터 원본에만 둔다(같은 모델을 물려받은 다른 DB는 DBMS_SCHEDULER가 다를 수 있다). MySQL도 같다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 * - 2026-11-08 삭제 확인은 삭제 / 취소, 지금 실행은 지금 실행 / 취소 단추(취소가 기본), 스크립트 미리 보기도 취소가 기본 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class SchedulerCommands {

    private static final Log log = Log.getLog(SchedulerCommands.class);

    /** Folder id of the Oracle schema's Scheduler folder in the Oracle plugin.xml */
    private static final String ORACLE_SCHEDULER_FOLDER_ID = "schedulerFolder"; //$NON-NLS-1$
    /** How long the read access state is used before it is read again */
    private static final long ACCESS_STALE_MILLIS = 60_000;

    /**
     * What differs between the databases when a script runs: its format and how it is executed.
     */
    interface Backend {
        @NotNull
        DBPDataSourceContainer container();

        @NotNull
        String formatScript(@NotNull List<String> statements);

        void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<String> statements, @Nullable Consumer<String> messages) throws DBException;

        /**
         * Called after a successful change: the cached access state is read again.
         */
        default void changed() {
        }
    }

    private record CachedAccess(@Nullable Object value, long time) {
    }

    private interface AccessLoader {
        @NotNull
        Object load(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    private static final Map<Object, CachedAccess> ACCESS = new WeakHashMap<>();
    private static final Set<Object> LOADING = new HashSet<>();
    private static final List<Runnable> ACCESS_LISTENERS = new CopyOnWriteArrayList<>();

    private final Supplier<DBNNode> selection;
    private final Component owner;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final List<JMenuItem> items = new ArrayList<>();
    private final JMenuItem newItem;
    private final JMenuItem runItem;
    private final JMenuItem stopItem;
    private final JMenuItem enableItem;
    private final JMenuItem disableItem;
    private final JMenuItem historyItem;
    private final JMenuItem runningItem;
    private final JMenuItem statusItem;

    SchedulerCommands(@NotNull Supplier<DBNNode> selection, @NotNull Component owner, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        this.selection = selection;
        this.owner = owner;
        this.queryWindows = queryWindows;
        newItem = createItem(SchedulerMessages.scheduler_new_job, () -> newObject(selection.get()));
        runItem = createItem(SchedulerMessages.scheduler_run_now, () -> {
            if (getObject(selection.get()) instanceof OracleSchedulerJob job) {
                runOracleJob(job, findRefreshNode(selection.get()));
            }
        });
        stopItem = createItem(SchedulerMessages.scheduler_stop_job, () -> {
            if (getObject(selection.get()) instanceof OracleSchedulerJob job) {
                stopOracleJob(owner, job.getDataSource(), job.getSchema().getName(), job.getName(), false, findRefreshNode(selection.get()));
            }
        });
        enableItem = createItem(SchedulerMessages.scheduler_enable, () -> setEnabled(selection.get(), true));
        disableItem = createItem(SchedulerMessages.scheduler_disable, () -> setEnabled(selection.get(), false));
        historyItem = createItem(SchedulerMessages.scheduler_view_history, () -> viewHistory(selection.get()));
        runningItem = createItem(SchedulerMessages.scheduler_view_running, () -> {
            OracleSchema schema = getOracleSchema(selection.get());
            if (schema != null) {
                openOracleRunningJobs(schema);
            }
        });
        statusItem = createItem(SchedulerMessages.scheduler_event_status, () -> {
            MySQLCatalog catalog = getMySQLCatalog(selection.get());
            if (catalog != null) {
                showEventSchedulerStatus(catalog);
            }
        });
        // The access state arrives after the rows were laid out and the menu was built: lay the shown rows out again (the
        // tree keeps each row's width, a new secondary text would be cut off) and read the selection's commands again
        ACCESS_LISTENERS.add(() -> {
            relayoutRows(owner);
            update(selection.get());
        });
    }

    @NotNull
    private JMenuItem createItem(@NotNull String text, @NotNull Runnable task) {
        JMenuItem item = new JMenuItem(new AbstractAction(text) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        });
        items.add(item);
        return item;
    }

    /**
     * Adds the scheduler items to the explorer's context menu (they are shown only on scheduler nodes, see {@link #update}).
     */
    void addTo(@NotNull JPopupMenu menu) {
        for (JMenuItem item : items) {
            menu.add(item);
        }
    }

    /**
     * Shows the items that apply to the selected node and enables them by the login's permissions and the object state.
     */
    void update(@Nullable DBNNode node) {
        for (JMenuItem item : items) {
            item.setVisible(false);
            item.setEnabled(true);
            item.setToolTipText(null);
        }
        if (!updateOracle(node) && !updatePgCron(node)) {
            updateMySQL(node);
        }
    }

    private boolean updateOracle(@Nullable DBNNode node) {
        OracleSchema schema = getOracleSchema(node);
        if (schema == null) {
            return false;
        }
        DBSObject object = getObject(node);
        boolean schedulerFolder = isOracleSchedulerFolder(node);
        boolean jobsFolder = getFolderChildren(node) == OracleSchedulerJob.class;
        OracleSchedulerJob job = object instanceof OracleSchedulerJob schedulerJob ? schedulerJob : null;
        boolean history = object instanceof OracleSchedulerMonitor monitor && monitor.getKind() == OracleSchedulerMonitor.Kind.RUN_DETAILS;
        boolean running = object instanceof OracleSchedulerMonitor runningMonitor && runningMonitor.getKind() == OracleSchedulerMonitor.Kind.RUNNING_JOBS;
        if (!schedulerFolder && !jobsFolder && job == null && !history && !running) {
            return false;
        }
        newItem.setText(SchedulerMessages.scheduler_new_job);
        newItem.setVisible(schedulerFolder || jobsFolder || job != null);
        runItem.setVisible(job != null);
        stopItem.setVisible(job != null);
        enableItem.setVisible(job != null);
        disableItem.setVisible(job != null);
        historyItem.setVisible(true);
        runningItem.setVisible(!history);
        if (job != null) {
            enableItem.setEnabled(!job.getEnabled());
            disableItem.setEnabled(job.getEnabled());
        }
        OracleSchedulerManager.Access access = getOracleAccess(schema.getDataSource());
        if (access != null) {
            if (!access.canCreate(schema.getName())) {
                disable(newItem, NLS.bind(SchedulerMessages.scheduler_tip_oracle_create, schema.getName()));
            }
            if (job != null && !access.canAlter(schema.getName())) {
                for (JMenuItem item : List.of(runItem, stopItem, enableItem, disableItem)) {
                    disable(item, NLS.bind(SchedulerMessages.scheduler_tip_oracle_alter, schema.getName()));
                }
            }
        }
        disableIfReadOnly(schema.getDataSource().getContainer(), newItem, runItem, stopItem, enableItem, disableItem);
        return true;
    }

    private boolean updatePgCron(@Nullable DBNNode node) {
        PostgreCronScheduler scheduler = getCronScheduler(node);
        if (scheduler == null) {
            return false;
        }
        DBSObject object = getObject(node);
        PostgreCronJob job = object instanceof PostgreCronJob cronJob ? cronJob : null;
        newItem.setText(SchedulerMessages.scheduler_new_job);
        newItem.setVisible(!(object instanceof PostgreCronHistory));
        enableItem.setVisible(job != null);
        disableItem.setVisible(job != null);
        historyItem.setVisible(true);
        String reason = getCronUnavailableReason(scheduler);
        if (reason != null) {
            disable(newItem, reason);
            disable(historyItem, reason);
            return true;
        }
        if (!scheduler.hasRunDetails()) {
            disable(historyItem, SchedulerMessages.scheduler_tip_cron_no_history);
        }
        if (job != null) {
            enableItem.setEnabled(!job.isActive());
            disableItem.setEnabled(job.isActive());
            if (!scheduler.hasAlterJob() || !scheduler.hasActive()) {
                disable(enableItem, SchedulerMessages.scheduler_tip_cron_no_alter);
                disable(disableItem, SchedulerMessages.scheduler_tip_cron_no_alter);
            }
        }
        disableIfReadOnly(scheduler.getDataSource().getContainer(), newItem, enableItem, disableItem);
        return true;
    }

    private void updateMySQL(@Nullable DBNNode node) {
        MySQLCatalog catalog = getMySQLCatalog(node);
        if (catalog == null) {
            return;
        }
        MySQLEvent event = getObject(node) instanceof MySQLEvent mySQLEvent ? mySQLEvent : null;
        newItem.setText(SchedulerMessages.scheduler_new_event);
        newItem.setVisible(true);
        enableItem.setVisible(event != null);
        disableItem.setVisible(event != null);
        statusItem.setVisible(true);
        if (event != null) {
            boolean enabled = MySQLEventDefinition.STATUS_ENABLED.equals(event.getStatus());
            enableItem.setEnabled(!enabled);
            disableItem.setEnabled(enabled);
        }
        MySQLEventScheduler.Access access = getMySQLAccess(catalog);
        if (access != null && access.isDenied()) {
            for (JMenuItem item : List.of(newItem, enableItem, disableItem)) {
                disable(item, NLS.bind(SchedulerMessages.scheduler_tip_mysql_event, catalog.getName()));
            }
        }
        disableIfReadOnly(catalog.getDataSource().getContainer(), newItem, enableItem, disableItem);
    }

    private static void disable(@NotNull JMenuItem item, @NotNull String reason) {
        item.setEnabled(false);
        item.setToolTipText(reason);
    }

    private static void disableIfReadOnly(@NotNull DBPDataSourceContainer container, @NotNull JMenuItem... changing) {
        if (!container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA)) {
            for (JMenuItem item : changing) {
                disable(item, NLS.bind(SchedulerMessages.scheduler_error_metadata_restricted, container.getName()));
            }
        }
    }

    /**
     * Why pg_cron can't be used in the database (not installed, no permission), or null.
     */
    @Nullable
    private static String getCronUnavailableReason(@NotNull PostgreCronScheduler scheduler) {
        return switch (scheduler.getState()) {
            case INSTALLED -> null;
            case NOT_INSTALLED -> SchedulerMessages.scheduler_tip_cron_not_installed;
            case NO_ACCESS -> SchedulerMessages.scheduler_tip_cron_no_access;
            case UNKNOWN -> SchedulerMessages.scheduler_tip_cron_unknown;
        };
    }

    //////////////////////////////////////////////////////////
    // Explorer hooks (Delete, Properties, double click, labels, icons)

    /**
     * True for scheduler objects Delete and Properties apply to: Oracle Scheduler jobs, pg_cron jobs, MySQL events.
     */
    static boolean canDelete(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        return object instanceof OracleSchedulerJob job && isOracle(job.getDataSource())
            || object instanceof PostgreCronJob
            || object instanceof MySQLEvent event && isMySQL(event.getDataSource());
    }

    static boolean canOpenProperties(@Nullable DBNNode node) {
        return canDelete(node);
    }

    void delete(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (!canDelete(node) || object == null) {
            return;
        }
        if (!SwingPlatformUI.confirmChange(owner, NLS.bind(SchedulerMessages.scheduler_delete_question, object.getName()),
            SchedulerMessages.scheduler_delete_title, NavigatorMessages.navigator_delete_button, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        Backend backend;
        String statement;
        String denied = null;
        if (object instanceof OracleSchedulerJob job) {
            backend = oracle(job.getDataSource());
            statement = OracleSchedulerManager.dropJobStatement(job);
            OracleSchedulerManager.Access access = getOracleAccess(job.getDataSource());
            if (access != null && !access.canAlter(job.getSchema().getName())) {
                denied = NLS.bind(SchedulerMessages.scheduler_tip_oracle_alter, job.getSchema().getName());
            }
        } else if (object instanceof PostgreCronJob job) {
            backend = pgCron(job.getScheduler());
            statement = PostgreCronManager.unscheduleStatement(job);
        } else {
            MySQLEvent event = (MySQLEvent) object;
            backend = mysql(event.getCatalog());
            statement = MySQLEventScheduler.dropStatement(event);
            MySQLEventScheduler.Access access = getMySQLAccess(event.getCatalog());
            if (access != null && access.isDenied()) {
                denied = NLS.bind(SchedulerMessages.scheduler_tip_mysql_event, event.getCatalog().getName());
            }
        }
        String title = NLS.bind(SchedulerMessages.scheduler_delete_title_object, object.getName());
        if (!checkEditAllowed(owner, backend.container(), title)) {
            return;
        }
        if (denied != null) {
            JOptionPane.showMessageDialog(owner, denied, title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        String script = backend.formatScript(List.of(statement));
        ScriptPreviewDialog.Result choice = ScriptPreviewDialog.show(owner, NLS.bind(SchedulerMessages.scheduler_script_title, title), script, true);
        if (choice == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(backend.container(), script, 0, false);
        } else if (choice == ScriptPreviewDialog.Result.EXECUTE
            && run(title, monitor -> backend.execute(monitor, List.of(statement), null))) {
            backend.changed();
            refreshNode(findRefreshNode(node));
        }
    }

    void openProperties(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (!canOpenProperties(node)) {
            return;
        }
        DBNDatabaseNode refreshNode = findRefreshNode(node);
        if (object instanceof OracleSchedulerJob job) {
            OracleJobDialog.open(owner, job.getSchema(), job, refreshNode, queryWindows);
        } else if (object instanceof PostgreCronJob job) {
            PgCronJobDialog.open(owner, job.getScheduler(), job, refreshNode, queryWindows);
        } else if (object instanceof MySQLEvent event) {
            MySQLEventDialog.open(owner, event.getCatalog(), event, refreshNode, queryWindows);
        }
    }

    /**
     * Double click: opens a history or running jobs tab, or the Properties of a job or event.
     *
     * @return true if the node had a default command
     */
    boolean openDefault(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (object instanceof OracleSchedulerMonitor monitor && isOracle(monitor.getDataSource())) {
            if (monitor.getKind() == OracleSchedulerMonitor.Kind.RUN_DETAILS) {
                openOracleHistory(monitor.getSchema(), null);
            } else {
                openOracleRunningJobs(monitor.getSchema());
            }
        } else if (object instanceof PostgreCronHistory history) {
            openCronHistory(history.getScheduler(), null);
        } else if (canOpenProperties(node)) {
            openProperties(node);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Secondary text of scheduler nodes in Object Explorer: the scheduler state of the Oracle Scheduler and MySQL Events
     * folders and the pg_cron node, "Disabled" for disabled jobs and events, the cron schedule of pg_cron jobs; null for
     * other nodes.
     */
    @Nullable
    static String getSecondaryText(@NotNull DBNNode node) {
        if (isOracleSchedulerFolder(node)) {
            OracleSchema schema = getOracleSchema(node);
            OracleSchedulerManager.Access access = schema == null ? null : getOracleAccess(schema.getDataSource());
            return access == null || access.state() == OracleSchedulerManager.SchedulerState.UNKNOWN ? null : access.state().getLabel();
        }
        if (getFolderChildren(node) == MySQLEvent.class) {
            MySQLCatalog catalog = getMySQLCatalog(node);
            MySQLEventScheduler.Access access = catalog == null ? null : getMySQLAccess(catalog);
            return access == null ? null : access.state().getLabel();
        }
        DBSObject object = getObject(node);
        if (object instanceof OracleSchedulerJob job && isOracle(job.getDataSource())) {
            return !job.getEnabled() ? SchedulerMessages.scheduler_disabled_suffix
                : "RUNNING".equals(job.getState()) ? SchedulerMessages.scheduler_running_suffix : null; //$NON-NLS-1$
        }
        if (object instanceof PostgreCronScheduler scheduler) {
            return scheduler.isInstalled() && scheduler.isLauncherStopped()
                ? NLS.bind(SchedulerMessages.scheduler_cron_launcher_stopped, scheduler.getStateName())
                : scheduler.getStateName();
        }
        if (object instanceof PostgreCronJob job) {
            return job.isActive() ? job.getSchedule() : NLS.bind(SchedulerMessages.scheduler_disabled_schedule, job.getSchedule());
        }
        if (object instanceof MySQLEvent event && isMySQL(event.getDataSource())) {
            return MySQLEventDefinition.STATUS_ENABLED.equals(event.getStatus()) ? null : SchedulerMessages.scheduler_disabled_suffix;
        }
        return null;
    }

    /**
     * Icon of scheduler nodes (the icon pack; the Scheduler and Events folders and the pg_cron node with a state dot), or
     * null for other nodes.
     *
     * @param expanded the tree node is expanded (folders show an open folder)
     */
    @Nullable
    static Icon getNodeIcon(@NotNull DBNNode node, boolean expanded) {
        Icon folder = UiIcons.get(expanded ? "folder-open" : "folder"); //$NON-NLS-1$ //$NON-NLS-2$
        if (isOracleSchedulerFolder(node)) {
            OracleSchema schema = getOracleSchema(node);
            OracleSchedulerManager.Access access = schema == null ? null : getOracleAccess(schema.getDataSource());
            if (access == null) {
                return null;
            }
            return switch (access.state()) {
                case RUNNING -> UiIcons.withStatusDot(folder, UiIcons.Status.CONNECTED);
                case STOPPED, DISABLED -> UiIcons.withStatusDot(folder, UiIcons.Status.ERROR);
                case UNKNOWN -> null;
            };
        }
        if (getFolderChildren(node) == MySQLEvent.class) {
            MySQLCatalog catalog = getMySQLCatalog(node);
            MySQLEventScheduler.Access access = catalog == null ? null : getMySQLAccess(catalog);
            if (access == null) {
                return null;
            }
            return switch (access.state()) {
                case ON -> UiIcons.withStatusDot(folder, UiIcons.Status.CONNECTED);
                case OFF, DISABLED -> UiIcons.withStatusDot(folder, UiIcons.Status.ERROR);
                case UNKNOWN -> null;
            };
        }
        DBSObject object = getObject(node);
        if (object instanceof PostgreCronScheduler scheduler) {
            Icon icon = UiIcons.get("schedule"); //$NON-NLS-1$
            return switch (scheduler.getState()) {
                case INSTALLED -> scheduler.isLauncherStopped() ? UiIcons.withStatusDot(icon, UiIcons.Status.ERROR)
                    : UiIcons.withStatusDot(icon, UiIcons.Status.CONNECTED);
                case NO_ACCESS -> UiIcons.withStatusDot(icon, UiIcons.Status.ERROR);
                case NOT_INSTALLED, UNKNOWN -> icon;
            };
        }
        if (object instanceof OracleSchedulerJob && isOracle(object.getDataSource()) || object instanceof PostgreCronJob) {
            return UiIcons.get("run"); //$NON-NLS-1$
        }
        if (object instanceof OracleSchedulerMonitor monitor) {
            return UiIcons.get(monitor.getKind() == OracleSchedulerMonitor.Kind.RUN_DETAILS ? "history" : "activity-monitor"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (object instanceof PostgreCronHistory) {
            return UiIcons.get("history"); //$NON-NLS-1$
        }
        if (object instanceof OracleSchedulerSchedule) {
            return UiIcons.get("schedule"); //$NON-NLS-1$
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Commands

    private void newObject(@Nullable DBNNode node) {
        DBNDatabaseNode refreshNode = findRefreshNode(node);
        OracleSchema schema = getOracleSchema(node);
        if (schema != null) {
            OracleJobDialog.open(owner, schema, null, refreshNode, queryWindows);
            return;
        }
        PostgreCronScheduler scheduler = getCronScheduler(node);
        if (scheduler != null) {
            PgCronJobDialog.open(owner, scheduler, null, refreshNode, queryWindows);
            return;
        }
        MySQLCatalog catalog = getMySQLCatalog(node);
        if (catalog != null) {
            MySQLEventDialog.open(owner, catalog, null, refreshNode, queryWindows);
        }
    }

    private void setEnabled(@Nullable DBNNode node, boolean enable) {
        DBSObject object = getObject(node);
        String title = enable ? SchedulerMessages.scheduler_enable : SchedulerMessages.scheduler_disable;
        Backend backend;
        String statement;
        if (object instanceof OracleSchedulerJob job) {
            backend = oracle(job.getDataSource());
            statement = OracleSchedulerManager.enableJobStatement(job, enable);
        } else if (object instanceof PostgreCronJob job) {
            backend = pgCron(job.getScheduler());
            statement = PostgreCronManager.setActiveStatement(job, enable);
        } else if (object instanceof MySQLEvent event) {
            backend = mysql(event.getCatalog());
            statement = MySQLEventScheduler.enableStatement(event, enable);
        } else {
            return;
        }
        if (run(title, monitor -> backend.execute(monitor, List.of(statement), null))) {
            DBWorkbench.getPlatformUI().showNotification(title,
                NLS.bind(enable ? SchedulerMessages.scheduler_enabled : SchedulerMessages.scheduler_disabled, object.getName()), false, null);
            backend.changed();
            refreshNode(findRefreshNode(node));
        }
    }

    /**
     * Run Now…: asks whether to wait for the job (it then runs in this tool's own connection and reports its error) or to
     * start it in a scheduler job session; waiting is the only way when the scheduler runs no jobs.
     */
    private void runOracleJob(@NotNull OracleSchedulerJob job, @Nullable DBNDatabaseNode refreshNode) {
        OracleSchedulerManager.Access access = getOracleAccess(job.getDataSource());
        boolean schedulerRuns = access == null || access.canRunJobs();
        JCheckBox waitCheck = new JCheckBox(SchedulerMessages.scheduler_run_wait);
        waitCheck.setSelected(!schedulerRuns);
        waitCheck.setEnabled(schedulerRuns);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(NLS.bind(SchedulerMessages.scheduler_run_prompt, job.getName())), BorderLayout.NORTH);
        panel.add(waitCheck, BorderLayout.CENTER);
        if (!schedulerRuns) {
            panel.add(new JLabel(SchedulerMessages.scheduler_run_scheduler_stopped, UiIcons.get("warning"), SwingConstants.LEADING), //$NON-NLS-1$
                BorderLayout.SOUTH);
        }
        if (!SwingPlatformUI.confirmChange(owner, panel, SchedulerMessages.scheduler_run_now_title, SchedulerMessages.scheduler_run_now_title,
            JOptionPane.PLAIN_MESSAGE)) {
            return;
        }
        boolean wait = waitCheck.isSelected();
        String statement = OracleSchedulerManager.runJobStatement(job, wait);
        if (!run(SchedulerMessages.scheduler_run_now_title, monitor -> OracleSchedulerManager.execute(monitor, job.getDataSource(), List.of(statement), null))) {
            return;
        }
        DBWorkbench.getPlatformUI().showNotification(SchedulerMessages.scheduler_run_now_title,
            NLS.bind(wait ? SchedulerMessages.scheduler_run_finished : SchedulerMessages.scheduler_run_started, job.getName()), false, null);
        refreshNode(refreshNode);
    }

    /**
     * Stop Job (from the explorer or the Running Jobs tab).
     *
     * @return true if the job was stopped
     */
    static boolean stopOracleJob(
        @NotNull Component owner,
        @NotNull OracleDataSource dataSource,
        @NotNull String jobOwner,
        @NotNull String name,
        boolean force,
        @Nullable DBNDatabaseNode refreshNode
    ) {
        if (!checkEditAllowed(owner, dataSource.getContainer(), SchedulerMessages.scheduler_stop_job)) {
            return false;
        }
        String statement = OracleSchedulerManager.stopJobStatement(dataSource, jobOwner, name, force);
        if (!run(SchedulerMessages.scheduler_stop_job, monitor -> OracleSchedulerManager.execute(monitor, dataSource, List.of(statement), null))) {
            return false;
        }
        DBWorkbench.getPlatformUI().showNotification(SchedulerMessages.scheduler_stop_job,
            NLS.bind(SchedulerMessages.scheduler_stopped, name), false, null);
        refreshNode(refreshNode);
        return true;
    }

    private void viewHistory(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        OracleSchema schema = getOracleSchema(node);
        if (schema != null) {
            openOracleHistory(schema, object instanceof OracleSchedulerJob job ? job.getName() : null);
            return;
        }
        PostgreCronScheduler scheduler = getCronScheduler(node);
        if (scheduler != null) {
            openCronHistory(scheduler, object instanceof PostgreCronJob job ? job : null);
        }
    }

    private void openOracleHistory(@NotNull OracleSchema schema, @Nullable String jobName) {
        DBPDataSourceContainer container = schema.getDataSource().getContainer();
        String scope = jobName == null ? schema.getName() : schema.getName() + "." + jobName; //$NON-NLS-1$
        String key = "oracle-scheduler-history:" + container.getId() + ":" + scope; //$NON-NLS-1$ //$NON-NLS-2$
        queryWindows.openToolDocument(key, () -> new SchedulerTablePanel<>(new SchedulerTablePanel.Spec<>(
            container,
            NLS.bind(SchedulerMessages.scheduler_history_title, scope),
            NLS.bind(SchedulerMessages.scheduler_history_tooltip, scope, container.getName()),
            "history", //$NON-NLS-1$
            OracleSchedulerRunDetail.class,
            OracleSchedulerRunDetail::getLogId,
            monitor -> {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, schema, "Read Oracle Scheduler job run details")) { //$NON-NLS-1$
                    return OracleSchedulerManager.readRunDetails(session, schema, jobName);
                }
            },
            SchedulerCommands::describeOracleRun,
            null,
            NLS.bind(SchedulerMessages.scheduler_history_note_oracle, OracleSchedulerManager.MAX_HISTORY_ROWS),
            List.of())));
    }

    @NotNull
    private static String describeOracleRun(@NotNull OracleSchedulerRunDetail run) {
        StringBuilder text = new StringBuilder();
        appendSection(text, SchedulerMessages.scheduler_detail_errors, run.getErrors());
        appendSection(text, SchedulerMessages.scheduler_detail_info, run.getAdditionalInfo());
        appendSection(text, SchedulerMessages.scheduler_detail_output, run.getOutput());
        return text.toString();
    }

    private static void appendSection(@NotNull StringBuilder text, @NotNull String title, @Nullable String value) {
        if (CommonUtils.isEmptyTrimmed(value)) {
            return;
        }
        if (!text.isEmpty()) {
            text.append("\n\n"); //$NON-NLS-1$
        }
        text.append(title).append('\n').append(value.trim());
    }

    private void openOracleRunningJobs(@NotNull OracleSchema schema) {
        DBPDataSourceContainer container = schema.getDataSource().getContainer();
        String key = "oracle-scheduler-running:" + container.getId() + ":" + schema.getName(); //$NON-NLS-1$ //$NON-NLS-2$
        OracleSchedulerManager.Access access = getOracleAccess(schema.getDataSource());
        boolean canStop = access == null || access.canAlter(schema.getName());
        boolean canForce = access == null || access.canStopWithForce();
        queryWindows.openToolDocument(key, () -> new SchedulerTablePanel<>(new SchedulerTablePanel.Spec<>(
            container,
            NLS.bind(SchedulerMessages.scheduler_running_title, schema.getName()),
            NLS.bind(SchedulerMessages.scheduler_running_tooltip, schema.getName(), container.getName()),
            "activity-monitor", //$NON-NLS-1$
            OracleSchedulerRunningJob.class,
            job -> job.getJobName() + "/" + job.getSessionId(), //$NON-NLS-1$
            monitor -> {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, schema, "Read Oracle Scheduler running jobs")) { //$NON-NLS-1$
                    return OracleSchedulerManager.readRunningJobs(session, schema);
                }
            },
            null,
            "oracle.scheduler.running.refreshSeconds", //$NON-NLS-1$
            null,
            List.of(
                new SchedulerTablePanel.RowAction<>(SchedulerMessages.scheduler_stop_job, "stop", //$NON-NLS-1$
                    canStop ? SchedulerMessages.scheduler_stop_job_tip : NLS.bind(SchedulerMessages.scheduler_tip_oracle_alter, schema.getName()),
                    job -> canStop,
                    job -> stopOracleJob(owner, schema.getDataSource(), schema.getName(), job.getJobName(), false, null)),
                new SchedulerTablePanel.RowAction<>(SchedulerMessages.scheduler_stop_job_force, "close", //$NON-NLS-1$
                    canForce ? SchedulerMessages.scheduler_stop_job_force_tip : SchedulerMessages.scheduler_tip_oracle_force,
                    job -> canStop && canForce,
                    job -> stopOracleJob(owner, schema.getDataSource(), schema.getName(), job.getJobName(), true, null))))));
    }

    private void openCronHistory(@NotNull PostgreCronScheduler scheduler, @Nullable PostgreCronJob job) {
        PostgreDatabase database = scheduler.getDatabase();
        DBPDataSourceContainer container = scheduler.getDataSource().getContainer();
        String scope = job == null ? database.getName() : database.getName() + "." + job.getName(); //$NON-NLS-1$
        String key = "pgcron-history:" + container.getId() + ":" + database.getName() + ":" + (job == null ? "" : job.getJobId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Long jobId = job == null ? null : job.getJobId();
        queryWindows.openToolDocument(key, () -> new SchedulerTablePanel<>(new SchedulerTablePanel.Spec<>(
            container,
            NLS.bind(SchedulerMessages.scheduler_history_title, scope),
            NLS.bind(SchedulerMessages.scheduler_history_tooltip, scope, container.getName()),
            "history", //$NON-NLS-1$
            PostgreCronRunDetail.class,
            PostgreCronRunDetail::getRunId,
            monitor -> {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, database, "Read pg_cron job run details")) { //$NON-NLS-1$
                    return PostgreCronManager.readRunDetails(session, scheduler, jobId);
                }
            },
            run -> {
                StringBuilder text = new StringBuilder();
                appendSection(text, SchedulerMessages.scheduler_detail_message, run.getReturnMessage());
                appendSection(text, SchedulerMessages.scheduler_detail_command, run.getCommand());
                return text.toString();
            },
            "pgcron.history.refreshSeconds", //$NON-NLS-1$
            NLS.bind(SchedulerMessages.scheduler_history_note_cron, PostgreCronManager.MAX_HISTORY_ROWS),
            List.of())));
    }

    /**
     * Event Scheduler Status…: the event_scheduler state, the command that turns it on (shown, never run: it is a server
     * setting), and that MySQL keeps no run history.
     */
    private void showEventSchedulerStatus(@NotNull MySQLCatalog catalog) {
        MySQLEventScheduler.Access access;
        try {
            access = ProgressDialog.runTask(SchedulerMessages.scheduler_event_status, monitor -> MySQLEventScheduler.readAccess(monitor, catalog));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SchedulerMessages.scheduler_event_status, SchedulerMessages.scheduler_error_read, e);
            return;
        }
        synchronized (ACCESS) {
            ACCESS.put(catalog, new CachedAccess(access, System.currentTimeMillis()));
        }
        fireAccessChanged();
        String stateText = switch (access.state()) {
            case ON -> SchedulerMessages.scheduler_event_status_on;
            case OFF -> SchedulerMessages.scheduler_event_status_off;
            case DISABLED -> SchedulerMessages.scheduler_event_status_disabled;
            case UNKNOWN -> SchedulerMessages.scheduler_event_status_unknown;
        };
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JTextArea text = new JTextArea(stateText + "\n\n" + SchedulerMessages.scheduler_event_no_history); //$NON-NLS-1$
        text.setEditable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setColumns(48);
        panel.add(text, BorderLayout.NORTH);
        boolean showCommand = access.state() == MySQLEventScheduler.State.OFF;
        if (showCommand) {
            JTextField command = new JTextField(MySQLEventScheduler.ENABLE_SCHEDULER_COMMAND);
            command.setEditable(false);
            JPanel commandPanel = new JPanel(new BorderLayout(0, 4));
            commandPanel.add(new JLabel(SchedulerMessages.scheduler_event_command_label), BorderLayout.NORTH);
            commandPanel.add(command, BorderLayout.CENTER);
            panel.add(commandPanel, BorderLayout.CENTER);
        }
        int messageType = access.state() == MySQLEventScheduler.State.ON ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
        if (!showCommand) {
            JOptionPane.showMessageDialog(owner, panel, SchedulerMessages.scheduler_event_status, messageType);
            return;
        }
        Object[] options = {SchedulerMessages.scheduler_event_open_command, SchedulerMessages.scheduler_event_copy_command, SchedulerMessages.scheduler_close};
        int choice = JOptionPane.showOptionDialog(owner, panel, SchedulerMessages.scheduler_event_status, JOptionPane.DEFAULT_OPTION,
            messageType, null, options, options[2]);
        if (choice == 0) {
            // Opened in a query window for the user to run, never run here: it is a server setting
            queryWindows.openScript(catalog.getDataSource().getContainer(), MySQLEventScheduler.ENABLE_SCHEDULER_COMMAND + "\n", 0, false); //$NON-NLS-1$
        } else if (choice == 1) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(MySQLEventScheduler.ENABLE_SCHEDULER_COMMAND), null);
        }
    }

    //////////////////////////////////////////////////////////
    // Backends and execution

    @NotNull
    static Backend oracle(@NotNull OracleDataSource dataSource) {
        return new Backend() {
            @NotNull
            @Override
            public DBPDataSourceContainer container() {
                return dataSource.getContainer();
            }

            @NotNull
            @Override
            public String formatScript(@NotNull List<String> statements) {
                return OracleSchedulerManager.formatScript(statements);
            }

            @Override
            public void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<String> statements, @Nullable Consumer<String> messages) throws DBException {
                OracleSchedulerManager.execute(monitor, dataSource, statements, messages);
            }
        };
    }

    @NotNull
    static Backend pgCron(@NotNull PostgreCronScheduler scheduler) {
        return new Backend() {
            @NotNull
            @Override
            public DBPDataSourceContainer container() {
                return scheduler.getDataSource().getContainer();
            }

            @NotNull
            @Override
            public String formatScript(@NotNull List<String> statements) {
                return PostgreCronManager.formatScript(statements);
            }

            @Override
            public void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<String> statements, @Nullable Consumer<String> messages) throws DBException {
                PostgreCronManager.execute(monitor, scheduler.getDatabase(), statements, messages);
            }
        };
    }

    @NotNull
    static Backend mysql(@NotNull MySQLCatalog catalog) {
        return new Backend() {
            @NotNull
            @Override
            public DBPDataSourceContainer container() {
                return catalog.getDataSource().getContainer();
            }

            @NotNull
            @Override
            public String formatScript(@NotNull List<String> statements) {
                return MySQLEventScheduler.formatScript(statements);
            }

            @Override
            public void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<String> statements, @Nullable Consumer<String> messages) throws DBException {
                MySQLEventScheduler.execute(monitor, catalog.getDataSource(), statements, messages);
            }

            @Override
            public void changed() {
                // A new event may change nothing in the access state, but the user may have turned the scheduler on meanwhile
                forgetAccess(catalog);
            }
        };
    }

    /**
     * The script statement that failed, from a scheduler exception in the cause chain, or null.
     */
    @Nullable
    static String getFailedStatement(@NotNull Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof OracleSchedulerException oracleError) {
                return oracleError.getStatement();
            }
            if (e instanceof PostgreCronException cronError) {
                return cronError.getStatement();
            }
            if (e instanceof MySQLEventSchedulerException eventError) {
                return eventError.getStatement();
            }
        }
        return null;
    }

    /**
     * A change in the database.
     */
    interface Change {
        void run(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    /**
     * Runs a change in a progress dialog; errors (missing permissions, scheduler not running) are shown.
     *
     * @return true if it succeeded
     */
    static boolean run(@NotNull String title, @NotNull Change change) {
        try {
            ProgressDialog.runTask(title, monitor -> {
                change.run(monitor);
                return null;
            });
            return true;
        } catch (DBInterruptedException e) {
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, SchedulerMessages.scheduler_error_apply, e);
            return false;
        }
    }

    /**
     * False (after telling the user) when the connection does not allow metadata changes.
     */
    static boolean checkEditAllowed(@NotNull Component owner, @NotNull DBPDataSourceContainer container, @NotNull String title) {
        if (container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA)) {
            return true;
        }
        JOptionPane.showMessageDialog(owner, NLS.bind(SchedulerMessages.scheduler_error_metadata_restricted, container.getName()),
            title, JOptionPane.WARNING_MESSAGE);
        return false;
    }

    /**
     * Reads a navigator node again in the background. The explorer shows it as a refresh done elsewhere and keeps its
     * expanded folders.
     */
    static void refreshNode(@Nullable DBNDatabaseNode node) {
        if (node == null || node.isDisposed()) {
            return;
        }
        new AbstractJob("Refresh job scheduler node") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    node.refreshNode(monitor, SchedulerCommands.class);
                } catch (DBException e) {
                    log.debug("Refresh of the job scheduler node failed", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    //////////////////////////////////////////////////////////
    // Access state (Oracle privileges and scheduler state, MySQL EVENT privilege and event_scheduler)

    /**
     * The Oracle Scheduler access of a connection, or null while it is read (or could not be read).
     */
    @Nullable
    static OracleSchedulerManager.Access getOracleAccess(@NotNull OracleDataSource dataSource) {
        return (OracleSchedulerManager.Access) getAccess(dataSource, dataSource.getContainer(), monitor -> OracleSchedulerManager.readAccess(monitor, dataSource));
    }

    /**
     * The event scheduler state and EVENT privilege of a MySQL database, or null while it is read.
     */
    @Nullable
    static MySQLEventScheduler.Access getMySQLAccess(@NotNull MySQLCatalog catalog) {
        return (MySQLEventScheduler.Access) getAccess(catalog, catalog.getDataSource().getContainer(), monitor -> MySQLEventScheduler.readAccess(monitor, catalog));
    }

    @Nullable
    private static Object getAccess(@NotNull Object key, @NotNull DBPDataSourceContainer container, @NotNull AccessLoader loader) {
        CachedAccess cached;
        synchronized (ACCESS) {
            cached = ACCESS.get(key);
            boolean fresh = cached != null && System.currentTimeMillis() - cached.time() < ACCESS_STALE_MILLIS;
            if (fresh || !container.isConnected() || !LOADING.add(key)) {
                return cached == null ? null : cached.value();
            }
        }
        new AbstractJob("Read job scheduler access") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Object value = null;
                try {
                    value = loader.load(monitor);
                } catch (Throwable e) {
                    // Unknown: the commands stay on and the server decides
                    log.debug("Can't read the job scheduler access state: " + e.getMessage()); //$NON-NLS-1$
                }
                synchronized (ACCESS) {
                    LOADING.remove(key);
                    ACCESS.put(key, new CachedAccess(value, System.currentTimeMillis()));
                }
                fireAccessChanged();
                return Status.OK_STATUS;
            }
        }.schedule();
        return cached == null ? null : cached.value();
    }

    private static void forgetAccess(@NotNull Object key) {
        synchronized (ACCESS) {
            ACCESS.remove(key);
        }
    }

    /**
     * Makes the explorer tree measure its shown rows again (their secondary text may have changed).
     */
    private static void relayoutRows(@NotNull Component owner) {
        JTree tree = findTree(owner);
        if (tree == null || !(tree.getModel() instanceof DefaultTreeModel model)) {
            owner.repaint();
            return;
        }
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (path != null && path.getLastPathComponent() instanceof TreeNode treeNode) {
                model.nodeChanged(treeNode);
            }
        }
    }

    @Nullable
    private static JTree findTree(@NotNull Component component) {
        if (component instanceof JTree tree) {
            return tree;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTree tree = findTree(child);
                if (tree != null) {
                    return tree;
                }
            }
        }
        return null;
    }

    private static void fireAccessChanged() {
        SwingUtilities.invokeLater(() -> {
            for (Runnable listener : ACCESS_LISTENERS) {
                listener.run();
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Node helpers

    @Nullable
    private static DBSObject getObject(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseNode databaseNode && !(node instanceof DBNDatabaseFolder) ? databaseNode.getObject() : null;
    }

    @Nullable
    private static Class<?> getFolderChildren(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseFolder folder ? folder.getChildrenClass() : null;
    }

    private static boolean isOracle(@Nullable Object dataSource) {
        // Exactly Oracle: databases built on the Oracle model (YashanDB) may have another DBMS_SCHEDULER
        return dataSource != null && dataSource.getClass() == OracleDataSource.class;
    }

    private static boolean isMySQL(@Nullable Object dataSource) {
        // MySQL and MariaDB; servers built on the MySQL model (TiDB, OceanBase) handle events differently or not at all
        return dataSource != null && dataSource.getClass() == MySQLDataSource.class;
    }

    private static boolean isOracleSchedulerFolder(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseFolder folder && ORACLE_SCHEDULER_FOLDER_ID.equals(folder.getMeta().getId())
            && folder.getValueObject() instanceof OracleSchema schema && isOracle(schema.getDataSource());
    }

    /**
     * The Oracle schema of a node in a Scheduler folder (the folder, the jobs folder, a job, a leaf), or null.
     */
    @Nullable
    private static OracleSchema getOracleSchema(@Nullable DBNNode node) {
        OracleSchema schema = null;
        DBSObject object = getObject(node);
        if (object instanceof OracleSchedulerJob job) {
            schema = job.getSchema();
        } else if (object instanceof OracleSchedulerMonitor monitor) {
            schema = monitor.getSchema();
        } else if (isOracleSchedulerFolder(node) || getFolderChildren(node) == OracleSchedulerJob.class) {
            schema = ((DBNDatabaseFolder) node).getValueObject() instanceof OracleSchema folderSchema ? folderSchema : null;
        }
        return schema != null && isOracle(schema.getDataSource()) ? schema : null;
    }

    @Nullable
    private static PostgreCronScheduler getCronScheduler(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        if (object instanceof PostgreCronScheduler scheduler) {
            return scheduler;
        }
        if (object instanceof PostgreCronJob job) {
            return job.getScheduler();
        }
        if (object instanceof PostgreCronHistory history) {
            return history.getScheduler();
        }
        if (getFolderChildren(node) == PostgreCronJob.class && ((DBNDatabaseFolder) node).getValueObject() instanceof PostgreCronScheduler scheduler) {
            return scheduler;
        }
        return null;
    }

    @Nullable
    private static MySQLCatalog getMySQLCatalog(@Nullable DBNNode node) {
        MySQLCatalog catalog = null;
        if (getObject(node) instanceof MySQLEvent event) {
            catalog = event.getCatalog();
        } else if (getFolderChildren(node) == MySQLEvent.class && ((DBNDatabaseFolder) node).getValueObject() instanceof MySQLCatalog folderCatalog) {
            catalog = folderCatalog;
        }
        return catalog != null && isMySQL(catalog.getDataSource()) ? catalog : null;
    }

    /**
     * The node read again after a change: the folder of a job or event, the folder itself, or the pg_cron node.
     */
    @Nullable
    private static DBNDatabaseNode findRefreshNode(@Nullable DBNNode node) {
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBNDatabaseNode databaseNode && !(current instanceof DBNDatabaseFolder)
                && databaseNode.getObject() instanceof PostgreCronScheduler) {
                return databaseNode;
            }
        }
        if (node instanceof DBNDatabaseFolder folder) {
            return folder;
        }
        return node != null && node.getParentNode() instanceof DBNDatabaseNode parent ? parent : null;
    }
}
