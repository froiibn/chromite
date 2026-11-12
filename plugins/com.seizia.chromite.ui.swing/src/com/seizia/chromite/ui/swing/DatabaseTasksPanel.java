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
import com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskEvent;
import com.seizia.chromite.model.task.DBTTaskExecutionListener;
import com.seizia.chromite.model.task.DBTTaskFolderEvent;
import com.seizia.chromite.model.task.DBTTaskListener;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.model.task.DBTTaskRun;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.stream.StreamTransferProducer;
import com.seizia.chromite.tools.transfer.task.DTSavedTasks;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Database Tasks 도구 창: 프로젝트에 저장한 작업(백업, 복원, 네이티브 클라이언트 스크립트 실행 등)의 목록과 실행·편집·삭제, 마지막 결과.
 *
 * 설계: SWT의 Database Tasks 보기({@code DatabaseTasksView})에서 매일 쓰는 부분만 옮겼다. 작업은 모델의 작업 관리자
 * ({@link DBTTaskManager}, 프로젝트의 tasks.json과 실행 기록)에 있고 이 창은 그것을 표로 보인다.
 * <ul>
 *     <li>열: 이름, 마지막 결과(성공 / 실패와 오류 문구 / 취소 / 실행 중), 마지막 실행 시각, 연결(네이티브 도구 작업은 저장된 설정의
 *     연결), 형식, 걸린 시간. 좁은 오른쪽 열에서 열리므로 이름과 결과를 앞에 둔다. 실행 기록은 모델이 남긴다({@link DBTTask#getLastRun}).</li>
 *     <li>실행: 백그라운드 작업({@link DBTTaskManager#scheduleTask})으로 돌리고, 끝나면 행을 다시 읽고 알림을 띄운다. 네이티브 도구 작업은
 *     처리기가 도구를 시작하기 전에 실제 명령줄을 셸 명령 승인 대화상자(Cancel이 기본)로 묻는다(저장된 설정이므로,
 *     docs/chromite/shell-command-approval.md). 그 밖의 형식은 이 창이 먼저 실행할지 묻는다(Cancel이 기본).
 *     연결에 로컬 클라이언트가 정해져 있지 않으면 실행하지 않는다: 처리기가 내려받는 클라이언트를 고를 수 있다.</li>
 *     <li>편집: 백업·복원·스크립트 실행 작업은 같은 대화상자로 연다({@link NativeBackupRestoreDialog#openTask}). 데이터 전송 작업(내보내기,
 *     가져오기, 테이블 복사; Swing이나 SWT에서 저장한 것)은 모델이 설정을 읽은 뒤(원본·대상을 찾으므로 연결함) 원본·대상 노드에 맞는 마법사를
 *     그 설정으로 연다: 파일 원본이면 가져오기, 아니면 내보내기·복사({@link DataImportWizard#openForTask}, {@link DataExportWizard#openForTask}).
 *     SQL 스크립트 작업은 {@link SqlScriptTaskDialog}로 연다. 그 밖의 형식은 편집 화면이 없어 실행·삭제만 된다.</li>
 *     <li>새 SQL 스크립트 작업: 여러 스크립트 파일을 연결에서 차례로 실행하는 작업은 저장할 다른 화면이 없으므로 이 창에서 만든다.</li>
 *     <li>네이티브 도구가 아닌 작업의 실행 확인에는 그 작업이 하는 일 중 되돌릴 수 없는 것을 적는다: 데이터 전송이 대상 테이블을 비우거나
 *     다시 만드는지, SQL 스크립트 작업이 어떤 스크립트를 어느 연결에서 돌리는지(저장된 JSON에서 읽음, 연결하지 않음).</li>
 *     <li>삭제는 확인한다(Delete / Cancel, Cancel이 기본).</li>
 *     <li>작업 관리자의 변경 알림({@link DBTTaskListener})으로 다른 곳에서 저장한 작업도 바로 보인다. 알림은 아무 스레드에서나 오므로
 *     EDT에서 다시 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-database-tasks.md)
 * - 2026-11-08 데이터 전송·SQL 스크립트 작업 편집, 새 SQL 스크립트 작업, 실행 확인의 작업 내용, 그 작업들의 연결 열
 *   (docs/chromite/swing-database-tasks.md "데이터 전송 작업", "SQL 스크립트 작업")
 * - 2026-11-12 예약 실행(앱 변경 #104, docs/chromite/task-scheduling.md "화면"): 일정 명령({@link TaskScheduleDialog}), 일정·다음 실행 열
 *   (놓친 시각, 건너뛴 실행, 이 PC에서 돌지 않는 이유), 마지막 결과에 예약 실행 표시, 아래에 사용 중인 일정 수와 다음 실행. 예약 실행이 도는 동안도
 *   실행 중으로 보고 실행·삭제를 막는다. 실행 확인의 작업 내용({@link #describeRun})과 오류 문구({@link #describeError})는 예약 쪽도 씀
 */
final class DatabaseTasksPanel extends JPanel implements DBTTaskListener {

    private static final Log log = Log.getLog(DatabaseTasksPanel.class);

    // The log viewer reads at most the end of a long log
    private static final int MAX_LOG_CHARS = 1_000_000;
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm"); //$NON-NLS-1$

    private final TasksModel model = new TasksModel();
    private final JTable table = new JTable(model);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    // Task IDs running from this window
    private final Set<String> running = new HashSet<>();
    private final Action runAction;
    private final Action editAction;
    private final Action showLogAction;
    private final Action deleteAction;
    private final Action refreshAction;
    private final Action newScriptAction;
    private final Action scheduleAction;
    private final JLabel scheduleStatus = new JLabel();
    private final Runnable schedulerListener = this::schedulesChanged;
    @Nullable
    private DBTTaskManager listenedManager;

    DatabaseTasksPanel() {
        super(new BorderLayout());
        newScriptAction = createAction(DatabaseTasksMessages.tasks_new_script, DatabaseTasksMessages.tasks_new_script_tip,
            () -> SqlScriptTaskDialog.openNew(this));
        runAction = createAction(DatabaseTasksMessages.tasks_run, DatabaseTasksMessages.tasks_run_tip, this::runSelected);
        editAction = createAction(DatabaseTasksMessages.tasks_edit, DatabaseTasksMessages.tasks_edit_tip, this::editSelected);
        showLogAction = createAction(DatabaseTasksMessages.tasks_show_log, DatabaseTasksMessages.tasks_show_log_tip, this::showLog);
        deleteAction = createAction(DatabaseTasksMessages.tasks_delete, DatabaseTasksMessages.tasks_delete_tip, this::deleteSelected);
        refreshAction = createAction(DatabaseTasksMessages.tasks_refresh, DatabaseTasksMessages.tasks_refresh_tip, this::reload);
        // The tool bar tooltip is the command's name (app change #103)
        scheduleAction = createAction(DatabaseTasksMessages.tasks_schedule, DatabaseTasksMessages.tasks_schedule, this::scheduleSelected);

        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(runAction, "run")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(editAction, "properties")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(showLogAction, "history")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(scheduleAction, "schedule")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(newScriptAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        add(toolBar, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> updateActions());
        // Name and last result first: the window opens in a narrow column; the rest scrolls sideways
        int[] widths = {160, 200, 140, 130, 150, 120, 150, 80};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectRowAt(e);
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && getSelectedTask() != null) {
                    // As the other object lists: double-click opens the settings
                    if (editAction.isEnabled()) {
                        editSelected();
                    } else {
                        showLog();
                    }
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteTask"); //$NON-NLS-1$
        table.getActionMap().put("deleteTask", deleteAction); //$NON-NLS-1$
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refreshTasks"); //$NON-NLS-1$
        table.getActionMap().put("refreshTasks", refreshAction); //$NON-NLS-1$

        JTextArea empty = DatabaseTaskDialog.FormPanel.note(DatabaseTasksMessages.tasks_empty);
        JPanel emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        emptyPanel.add(empty, BorderLayout.NORTH);
        body.add(new JScrollPane(table), "table"); //$NON-NLS-1$
        body.add(emptyPanel, "empty"); //$NON-NLS-1$
        add(body, BorderLayout.CENTER);
        scheduleStatus.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        scheduleStatus.setVisible(false);
        add(scheduleStatus, BorderLayout.SOUTH);
        updateActions();
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String description, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // The list is read when the window is shown, and follows the task manager while it is
        DBTTaskManager manager = getTaskManager();
        if (manager != null && manager != listenedManager) {
            manager.getRegistry().addTaskListener(this);
            listenedManager = manager;
        }
        RunScheduler.getInstance().addListener(schedulerListener);
        reload();
    }

    @Override
    public void removeNotify() {
        RunScheduler.getInstance().removeListener(schedulerListener);
        if (listenedManager != null) {
            listenedManager.getRegistry().removeTaskListener(this);
            listenedManager = null;
        }
        super.removeNotify();
    }

    @Override
    public void handleTaskEvent(@NotNull DBTTaskEvent event) {
        SwingUtilities.invokeLater(this::reload);
    }

    @Override
    public void handleTaskFolderEvent(@NotNull DBTTaskFolderEvent event) {
        SwingUtilities.invokeLater(this::reload);
    }

    @Nullable
    private static DBTTaskManager getTaskManager() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? null : project.getTaskManager();
    }

    /**
     * Reads the saved tasks of the active project again (sorted by name), keeping the selection.
     */
    private void reload() {
        DBTTask selected = getSelectedTask();
        DBTTaskManager manager = getTaskManager();
        List<DBTTask> tasks = new ArrayList<>();
        if (manager != null) {
            tasks.addAll(Arrays.asList(manager.getAllTasks()));
            tasks.sort(Comparator.comparing(DBTTask::getName, String.CASE_INSENSITIVE_ORDER));
        }
        model.setTasks(tasks);
        cards.show(body, tasks.isEmpty() ? "empty" : "table"); //$NON-NLS-1$ //$NON-NLS-2$
        updateScheduleStatus();
        if (selected != null) {
            int row = tasks.indexOf(selected);
            if (row >= 0) {
                int viewRow = table.convertRowIndexToView(row);
                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            }
        }
        updateActions();
    }

    @Nullable
    private DBTTask getSelectedTask() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : model.getTask(table.convertRowIndexToModel(viewRow));
    }

    /**
     * A scheduled run started or ended, or a schedule changed: the rows and the status line are drawn again (rows updated,
     * not the table changed: that would clear the selection).
     */
    private void schedulesChanged() {
        if (model.getRowCount() > 0) {
            model.fireTableRowsUpdated(0, model.getRowCount() - 1);
        }
        updateScheduleStatus();
        updateActions();
    }

    /**
     * The line below the list: how many schedules are on here and which task runs next, or nothing.
     */
    private void updateScheduleStatus() {
        int count = 0;
        TaskScheduler.TaskEntry next = null;
        for (TaskScheduler.TaskEntry entry : TaskScheduler.getInstance().getEntries()) {
            if (entry.timing.isEnabled() && entry.isActive()) {
                count++;
                if (entry.nextRun != null && (next == null || entry.nextRun.isBefore(next.nextRun))) {
                    next = entry;
                }
            }
        }
        scheduleStatus.setVisible(count > 0);
        scheduleStatus.setText(next == null ? NLS.bind(DatabaseTasksMessages.tasks_schedule_status_none, count)
            : NLS.bind(DatabaseTasksMessages.tasks_schedule_status, new Object[]{count, next.task.getName(), formatShort(next.nextRun)}));
    }

    private boolean isRunning(@NotNull DBTTask task) {
        if (running.contains(task.getId())) {
            return true;
        }
        TaskScheduler.TaskEntry entry = TaskScheduler.getInstance().getEntry(task);
        return entry != null && entry.isRunning();
    }

    private void updateActions() {
        DBTTask task = getSelectedTask();
        boolean selected = task != null;
        scheduleAction.setEnabled(selected);
        runAction.setEnabled(selected && !isRunning(task));
        editAction.setEnabled(selected && isEditable(task));
        editAction.putValue(Action.SHORT_DESCRIPTION, !selected || editAction.isEnabled() ? DatabaseTasksMessages.tasks_edit_tip
            : NLS.bind(DatabaseTasksMessages.tasks_edit_unsupported, task.getType().getName()));
        showLogAction.setEnabled(selected && task.getLastRun() != null);
        deleteAction.setEnabled(selected && !isRunning(task));
    }

    private void selectRowAt(@NotNull MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
    }

    private void showPopup(@NotNull MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        if (getSelectedTask() != null) {
            menu.add(runAction);
            menu.add(editAction);
            menu.add(showLogAction);
            menu.add(scheduleAction);
            menu.addSeparator();
            menu.add(deleteAction);
            menu.addSeparator();
        }
        menu.add(newScriptAction);
        menu.add(refreshAction);
        menu.show(table, e.getX(), e.getY());
    }

    private static boolean isEditable(@NotNull DBTTask task) {
        return NativeBackupRestore.isEditableTaskType(task.getType()) || DTSavedTasks.isTransferTask(task.getType())
            || DTSavedTasks.isScriptTask(task.getType());
    }

    /**
     * The connection of a task for the list, from its saved settings (no connecting).
     */
    @Nullable
    private static DBPDataSourceContainer getTaskConnection(@NotNull DBTTask task) {
        return NativeBackupRestore.isNativeToolTask(task.getType()) ? NativeBackupRestore.getTaskConnection(task)
            : DTSavedTasks.findTaskConnection(task);
    }

    //////////////////////////////////////////////////////////
    // Commands

    /**
     * Runs the selected task in the background. A native tool task needs a local client on its connection (the handler
     * would otherwise take the driver's first client, which may be downloaded); its command line is approved in the handler.
     * Other task types are confirmed here.
     */
    private void runSelected() {
        DBTTask task = getSelectedTask();
        if (task == null || isRunning(task)) {
            return;
        }
        if (NativeBackupRestore.isNativeToolTask(task.getType())) {
            DBPDataSourceContainer container = NativeBackupRestore.getTaskConnection(task);
            DBPNativeClientLocation client = container == null ? null : NativeBackupRestore.findConnectionClient(container);
            if (container != null && client == null) {
                JOptionPane.showMessageDialog(this, NLS.bind(DatabaseTasksMessages.tasks_no_client, container.getName()),
                    task.getName(), JOptionPane.WARNING_MESSAGE);
                return;
            }
        } else {
            String details = describeRun(task);
            String question = NLS.bind(DatabaseTasksMessages.tasks_run_confirm, task.getName(), task.getType().getName());
            if (!SwingPlatformUI.confirmChange(this, details == null ? question : question + "\n\n" + details, //$NON-NLS-1$
                DatabaseTasksMessages.tasks_run_confirm_title, DatabaseTasksMessages.tasks_run,
                details == null ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE)) {
                return;
            }
        }
        Job job;
        try {
            job = task.getProject().getTaskManager().scheduleTask(task, new DBTTaskExecutionListener() {
                @Override
                public void taskStarted(@Nullable DBTTask t) {
                }

                @Override
                public void taskFinished(@Nullable DBTTask t, @Nullable Object result, @Nullable Throwable error, @Nullable Object settings) {
                }

                @Override
                public void subTaskFinished(@Nullable DBTTask t, @Nullable Throwable error, @Nullable Object settings) {
                }
            });
        } catch (DBException e) {
            log.debug("Can't run the task", e);
            ErrorDialog.show(this, task.getName(), ErrorText.describe(e), e);
            return;
        }
        running.add(task.getId());
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                SwingUtilities.invokeLater(() -> taskDone(task));
            }
        });
        // Rows updated, not the whole table changed: that would clear the selection
        if (model.getRowCount() > 0) {
            model.fireTableRowsUpdated(0, model.getRowCount() - 1);
        }
        updateActions();
    }

    private void taskDone(@NotNull DBTTask task) {
        running.remove(task.getId());
        reload();
        if (!(SwingUtilities.getWindowAncestor(this) instanceof JFrame frame)) {
            return;
        }
        DBTTaskRun run = task.getLastRun();
        boolean success = run != null && run.isRunSuccess();
        NotificationToast.show(frame,
            NLS.bind(success ? DatabaseTasksMessages.tasks_finished : DatabaseTasksMessages.tasks_failed, task.getName()),
            success || run == null ? null : describeError(run),
            success ? NotificationToast.Kind.INFO : NotificationToast.Kind.ERROR, null);
    }

    /**
     * Why a run failed. The run keeps the message of the outermost error, which for a native tool is the handler's generic
     * "Error executing process"; the innermost cause in the saved stack trace says what happened (the tool's exit code,
     * a missing file, a declined approval).
     */
    @NotNull
    static String describeError(@NotNull DBTTaskRun run) {
        String message = CommonUtils.notEmpty(run.getErrorMessage());
        String stackTrace = run.getErrorStackTrace();
        if (stackTrace != null) {
            String cause = null;
            for (String line : stackTrace.split("\\R")) { //$NON-NLS-1$
                if (line.startsWith("Caused by: ")) { //$NON-NLS-1$
                    cause = line.substring("Caused by: ".length()); //$NON-NLS-1$
                }
            }
            // "class.Name: message"
            int separator = cause == null ? -1 : cause.indexOf(": "); //$NON-NLS-1$
            if (separator > 0 && separator + 2 < cause.length()) {
                message = cause.substring(separator + 2);
            }
        }
        return ErrorText.clean(message);
    }

    /**
     * What the run of a saved transfer or script task does that can't be undone, from its saved settings (no connecting),
     * or null: a transfer that empties or recreates destination tables, the scripts of a script task and where they run.
     */
    @Nullable
    static String describeRun(@NotNull DBTTask task) {
        if (DTSavedTasks.isTransferTask(task.getType())) {
            DTSavedTasks.TargetChanges changes = DTSavedTasks.getTargetChanges(task);
            List<String> lines = new ArrayList<>();
            if (changes.truncate()) {
                lines.add(DatabaseTasksMessages.tasks_run_confirm_truncate);
            }
            if (!changes.recreated().isEmpty()) {
                lines.add(NLS.bind(DatabaseTasksMessages.tasks_run_confirm_recreate, String.join(", ", changes.recreated()))); //$NON-NLS-1$
            }
            return lines.isEmpty() ? null : String.join("\n", lines); //$NON-NLS-1$
        }
        if (DTSavedTasks.isScriptTask(task.getType())) {
            try {
                SQLScriptExecuteSettings settings = DTSavedTasks.loadScriptTask(task);
                List<String> connections = new ArrayList<>();
                settings.getDataSources().forEach(connection -> connections.add(connection.getName()));
                return NLS.bind(DatabaseTasksMessages.tasks_run_confirm_scripts,
                    String.join("\n", settings.getScriptFiles()), String.join(", ", connections)); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (DBException e) {
                log.debug("Can't read the script task settings", e);
            }
        }
        return null;
    }

    private void editSelected() {
        DBTTask task = getSelectedTask();
        if (task == null) {
            return;
        }
        if (NativeBackupRestore.isEditableTaskType(task.getType())) {
            NativeBackupRestoreDialog.openTask(this, task);
        } else if (DTSavedTasks.isTransferTask(task.getType())) {
            editTransferTask(task);
        } else if (DTSavedTasks.isScriptTask(task.getType())) {
            SqlScriptTaskDialog.openTask(this, task);
        }
        reload();
    }

    /**
     * Reads a saved transfer (this connects to its source and destination) and opens the wizard of its kind with it:
     * Import Data for a file source, else Export Data or Copy Tables.
     */
    private void editTransferTask(@NotNull DBTTask task) {
        DataTransferSettings settings;
        try {
            settings = ProgressDialog.runTask(NLS.bind(DatabaseTasksMessages.tasks_loading, task.getName()),
                monitor -> DataTransferWizard.loadTaskSettings(monitor, task));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            log.debug("Can't read the task settings", e);
            ErrorDialog.show(this, task.getName(), ErrorText.describe(e), e);
            return;
        }
        boolean fileSource = settings.getProducer() != null && settings.getProducer().getNodeClass() == StreamTransferProducer.class;
        boolean opened = fileSource ? DataImportWizard.openForTask(this, task, settings) : DataExportWizard.openForTask(this, task, settings, null);
        if (!opened) {
            JOptionPane.showMessageDialog(this, NLS.bind(DatabaseTasksMessages.tasks_edit_transfer_unsupported, task.getName()),
                task.getName(), JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Schedule…: the schedule of the selected task (when, on or off, on this computer), turned on with the answers to what a
     * hand run asks every time.
     */
    private void scheduleSelected() {
        DBTTask task = getSelectedTask();
        if (task != null) {
            TaskScheduleDialog.open(this, task);
            reload();
        }
    }

    private void deleteSelected() {
        DBTTask task = getSelectedTask();
        if (task == null || isRunning(task)) {
            return;
        }
        if (!SwingPlatformUI.confirmChange(this, NLS.bind(DatabaseTasksMessages.tasks_delete_confirm, task.getName()),
            DatabaseTasksMessages.tasks_delete_title, DatabaseTasksMessages.tasks_delete_button, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        try {
            task.getProject().getTaskManager().deleteTaskConfiguration(task);
        } catch (DBException e) {
            log.debug("Can't delete the task", e);
            ErrorDialog.show(this, DatabaseTasksMessages.tasks_delete_title, ErrorText.describe(e), e);
        }
        reload();
    }

    /**
     * Shows the log of the last run (the tool's output and the task runner's lines) in a read-only window.
     */
    private void showLog() {
        DBTTask task = getSelectedTask();
        DBTTaskRun run = task == null ? null : task.getLastRun();
        if (run == null) {
            return;
        }
        String text;
        try (InputStream input = task.getRunLogInputStream(run)) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (DBException | IOException e) {
            log.debug("Can't read the task log", e);
            text = DatabaseTasksMessages.tasks_log_none;
        }
        if (text.length() > MAX_LOG_CHARS) {
            text = text.substring(text.length() - MAX_LOG_CHARS);
        }
        JTextArea area = new JTextArea(text, 24, 100);
        area.setEditable(false);
        area.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        area.setCaretPosition(area.getDocument().getLength());
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
            NLS.bind(DatabaseTasksMessages.tasks_log_title, task.getName(), formatTime(run)), Dialog.ModalityType.MODELESS);
        dialog.setIconImages(ChromiteIcons.getWindowIcons());
        dialog.setContentPane(new JScrollPane(area));
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        dialog.setVisible(true);
    }

    @NotNull
    private static String formatTime(@NotNull DBTTaskRun run) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(run.getStartTime());
    }

    /**
     * A schedule time in the list: "11-13 02:00".
     */
    @NotNull
    static String formatShort(@NotNull LocalDateTime time) {
        return SHORT_TIME.format(time);
    }

    /**
     * The Schedule column: when it runs, and why it does not (off, not on this computer, a reason it can't run here).
     */
    @NotNull
    private static String describeSchedule(@NotNull DBTTask task) {
        ScheduleTiming timing = TaskScheduler.readTiming(task);
        if (timing == null) {
            return ""; //$NON-NLS-1$
        }
        String text = timing.describe();
        if (!timing.isEnabled()) {
            return NLS.bind(DatabaseTasksMessages.tasks_schedule_off, text);
        }
        if (!TaskScheduler.isActiveHere(task)) {
            return NLS.bind(DatabaseTasksMessages.tasks_schedule_not_here, text);
        }
        String reason = TaskScheduler.getNotRunnableReason(task);
        return reason == null ? text : NLS.bind(DatabaseTasksMessages.tasks_schedule_inactive, text, reason);
    }

    /**
     * The Next Run column: the next time of an active schedule, then a missed or skipped time.
     */
    @NotNull
    private static String describeNextRun(@NotNull DBTTask task) {
        TaskScheduler.TaskEntry entry = TaskScheduler.getInstance().getEntry(task);
        if (entry == null || !entry.timing.isEnabled()) {
            return ""; //$NON-NLS-1$
        }
        List<String> parts = new ArrayList<>();
        if (entry.isRunning()) {
            parts.add(DatabaseTasksMessages.tasks_schedule_running);
        } else if (entry.isActive() && entry.nextRun != null) {
            parts.add(formatShort(entry.nextRun));
        }
        LocalDateTime missed = entry.getMissed(TaskScheduler.getInstance().getStartTime());
        if (missed != null) {
            parts.add(NLS.bind(DatabaseTasksMessages.tasks_schedule_missed, formatShort(missed)));
        }
        String note = entry.note;
        if (note != null) {
            parts.add(note);
        }
        return String.join(" · ", parts); //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Table

    private final class TasksModel extends AbstractTableModel {
        private final String[] columns = {
            DatabaseTasksMessages.tasks_column_name, DatabaseTasksMessages.tasks_column_result, DatabaseTasksMessages.tasks_column_last_run,
            DatabaseTasksMessages.tasks_column_schedule, DatabaseTasksMessages.tasks_column_next_run,
            DatabaseTasksMessages.tasks_column_connection, DatabaseTasksMessages.tasks_column_type, DatabaseTasksMessages.tasks_column_duration};
        private List<DBTTask> tasks = List.of();

        void setTasks(@NotNull List<DBTTask> tasks) {
            this.tasks = tasks;
            fireTableDataChanged();
        }

        @Nullable
        DBTTask getTask(int row) {
            return row >= 0 && row < tasks.size() ? tasks.get(row) : null;
        }

        @Override
        public int getRowCount() {
            return tasks.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBTTask task = tasks.get(row);
            DBTTaskRun run = task.getLastRun();
            return switch (column) {
                case 0 -> task.getName();
                case 1 -> describeResult(task, run);
                case 2 -> run == null ? "" : formatTime(run); //$NON-NLS-1$
                case 3 -> describeSchedule(task);
                case 4 -> describeNextRun(task);
                case 5 -> {
                    DBPDataSourceContainer container = getTaskConnection(task);
                    yield container == null ? "" : container.getName(); //$NON-NLS-1$
                }
                case 6 -> task.getType().getName();
                default -> run == null || isRunning(task) ? "" : RuntimeUtils.formatExecutionTime(run.getRunDuration()); //$NON-NLS-1$
            };
        }

        @NotNull
        private String describeResult(@NotNull DBTTask task, @Nullable DBTTaskRun run) {
            if (isRunning(task)) {
                return DatabaseTasksMessages.tasks_result_running;
            }
            if (run == null) {
                return DatabaseTasksMessages.tasks_result_never;
            }
            String result = describeRunResult(run);
            // A run by the scheduler is marked; a run by hand is not
            return TaskScheduler.STARTED_BY.equals(run.getStartedBy()) ? NLS.bind(DatabaseTasksMessages.tasks_result_scheduled, result) : result;
        }

        @NotNull
        private String describeRunResult(@NotNull DBTTaskRun run) {
            if (run.isRunSuccess()) {
                return DatabaseTasksMessages.tasks_result_success;
            }
            if ("Canceled".equals(run.getErrorMessage())) { //$NON-NLS-1$
                // TaskRunJob records a canceled run with this fixed text
                return DatabaseTasksMessages.tasks_result_canceled;
            }
            // One line in the table (psql reports each error over several lines); the whole text is in the toast and the log
            String error = describeError(run).strip();
            int lineEnd = error.indexOf('\n');
            return NLS.bind(DatabaseTasksMessages.tasks_result_failed, lineEnd > 0 ? error.substring(0, lineEnd).strip() : error);
        }
    }
}
