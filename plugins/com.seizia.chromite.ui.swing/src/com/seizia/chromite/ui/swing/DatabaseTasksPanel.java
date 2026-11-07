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
import com.seizia.chromite.runtime.DBWorkbench;
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
 *     <li>편집: 백업·복원·스크립트 실행 작업은 같은 대화상자로 연다({@link NativeBackupRestoreDialog#openTask}). 다른 형식(예전 SWT에서
 *     저장한 데이터 전송 등)은 아직 편집 화면이 없어 실행·삭제만 된다.</li>
 *     <li>삭제는 확인한다(Delete / Cancel, Cancel이 기본).</li>
 *     <li>작업 관리자의 변경 알림({@link DBTTaskListener})으로 다른 곳에서 저장한 작업도 바로 보인다. 알림은 아무 스레드에서나 오므로
 *     EDT에서 다시 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-database-tasks.md)
 */
final class DatabaseTasksPanel extends JPanel implements DBTTaskListener {

    private static final Log log = Log.getLog(DatabaseTasksPanel.class);

    // The log viewer reads at most the end of a long log
    private static final int MAX_LOG_CHARS = 1_000_000;

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
    @Nullable
    private DBTTaskManager listenedManager;

    DatabaseTasksPanel() {
        super(new BorderLayout());
        runAction = createAction(DatabaseTasksMessages.tasks_run, DatabaseTasksMessages.tasks_run_tip, this::runSelected);
        editAction = createAction(DatabaseTasksMessages.tasks_edit, DatabaseTasksMessages.tasks_edit_tip, this::editSelected);
        showLogAction = createAction(DatabaseTasksMessages.tasks_show_log, DatabaseTasksMessages.tasks_show_log_tip, this::showLog);
        deleteAction = createAction(DatabaseTasksMessages.tasks_delete, DatabaseTasksMessages.tasks_delete_tip, this::deleteSelected);
        refreshAction = createAction(DatabaseTasksMessages.tasks_refresh, DatabaseTasksMessages.tasks_refresh_tip, this::reload);

        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(runAction, "run")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(editAction, "properties")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(showLogAction, "history")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        add(toolBar, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> updateActions());
        // Name and last result first: the window opens in a narrow column; the rest scrolls sideways
        int[] widths = {160, 200, 140, 120, 150, 80};
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
        reload();
    }

    @Override
    public void removeNotify() {
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

    private void updateActions() {
        DBTTask task = getSelectedTask();
        boolean selected = task != null;
        runAction.setEnabled(selected && !running.contains(task.getId()));
        editAction.setEnabled(selected && NativeBackupRestore.isEditableTaskType(task.getType()));
        editAction.putValue(Action.SHORT_DESCRIPTION, !selected || editAction.isEnabled() ? DatabaseTasksMessages.tasks_edit_tip
            : NLS.bind(DatabaseTasksMessages.tasks_edit_unsupported, task.getType().getName()));
        showLogAction.setEnabled(selected && task.getLastRun() != null);
        deleteAction.setEnabled(selected && !running.contains(task.getId()));
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
            menu.addSeparator();
            menu.add(deleteAction);
            menu.addSeparator();
        }
        menu.add(refreshAction);
        menu.show(table, e.getX(), e.getY());
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
        if (task == null || running.contains(task.getId())) {
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
        } else if (!SwingPlatformUI.confirmChange(this,
            NLS.bind(DatabaseTasksMessages.tasks_run_confirm, task.getName(), task.getType().getName()),
            DatabaseTasksMessages.tasks_run_confirm_title, DatabaseTasksMessages.tasks_run, JOptionPane.QUESTION_MESSAGE)) {
            return;
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
    private static String describeError(@NotNull DBTTaskRun run) {
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

    private void editSelected() {
        DBTTask task = getSelectedTask();
        if (task != null && NativeBackupRestore.isEditableTaskType(task.getType())) {
            NativeBackupRestoreDialog.openTask(this, task);
            reload();
        }
    }

    private void deleteSelected() {
        DBTTask task = getSelectedTask();
        if (task == null || running.contains(task.getId())) {
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

    //////////////////////////////////////////////////////////
    // Table

    private final class TasksModel extends AbstractTableModel {
        private final String[] columns = {
            DatabaseTasksMessages.tasks_column_name, DatabaseTasksMessages.tasks_column_result, DatabaseTasksMessages.tasks_column_last_run,
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
                case 3 -> {
                    DBPDataSourceContainer container = NativeBackupRestore.getTaskConnection(task);
                    yield container == null ? "" : container.getName(); //$NON-NLS-1$
                }
                case 4 -> task.getType().getName();
                default -> run == null || running.contains(task.getId()) ? "" : RuntimeUtils.formatExecutionTime(run.getRunDuration()); //$NON-NLS-1$
            };
        }

        @NotNull
        private String describeResult(@NotNull DBTTask task, @Nullable DBTTaskRun run) {
            if (running.contains(task.getId())) {
                return DatabaseTasksMessages.tasks_result_running;
            }
            if (run == null) {
                return DatabaseTasksMessages.tasks_result_never;
            }
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
