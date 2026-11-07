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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swing side of the idle transaction handling ({@link IdleTransactionMonitor}): the reminder with Commit / Roll back /
 * Keep open, the notice after an automatic rollback, and the commit/rollback that the reminder and the query window's
 * open transaction indicator share.
 *
 * 설계:
 * <ul>
 *     <li>알림은 메인 창 오른쪽 아래의 {@link NotificationToast}이고 답 단추가 있는 "남는" 알림이다({@link NotificationToast#showWithActions}).
 *     모달 대화상자로 막지 않고, 아무 것도 저절로 고르지 않으며, 시간이 지나도 사라지지 않는다. 자리를 비운 동안에는 아무 일도 일어나지 않고
 *     돌아온 사용자가 그대로 본다. ×로 닫으면 Keep open과 같다.</li>
 *     <li>알림에는 연결, 쿼리 창(그 실행 문맥을 쓰는 쿼리 창의 파일 이름: 창의 자기 세션이거나 함께 쓰는 주 문맥, 없으면 문맥 이름), 유휴 시간, 문장 수를 적는다.</li>
 *     <li>모니터가 트랜잭션이 끝났거나 문맥이 다시 쓰였다고 알리면({@link #closeIdleTransactionReminder}) 알림을 닫는다.
 *     오래된 알림의 단추가 새 트랜잭션을 끝내지 않도록 커밋·롤백은 알림이 보여 준 트랜잭션(첫 문장 시각)일 때만 한다
 *     ({@link IdleTransactionMonitor#endTransaction}).</li>
 *     <li>자동 롤백 뒤의 알림은 닫을 때까지 남고 연결, 쿼리 창, 유휴 시간, 취소된 문장 수를 적는다. 실패하면 오류 알림이다.</li>
 *     <li>모든 화면 작업은 EDT에서 하고 커밋·롤백은 작업 스레드({@link AbstractJob})에서 한다. 모니터는 백그라운드 스레드에서 부른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md)
 * - 2026-11-07 {@link #endTransaction}을 Query 메뉴·도구 막대·단축키와 열린 트랜잭션 목록도 씀, 쿼리 창 이름 찾기({@link #describeQueryWindows})를
 *   목록과 나눔 (docs/chromite/transactions.md)
 * - 2026-11-07 쿼리 창 이름 찾기가 창의 실제 실행 문맥을 비교함(쿼리 창마다 별도 연결, 전에는 주 문맥만 창으로 보임)
 *   (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 */
public class SwingIdleTransactionService implements UIServiceIdleTransactions {

    private static final Log log = Log.getLog(SwingIdleTransactionService.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    // EDT only: the shown reminder of each context
    private final Map<DBCExecutionContext, NotificationToast> reminders = new IdentityHashMap<>();

    @Override
    public void showIdleTransactionReminder(@NotNull DBCExecutionContext context, @NotNull QMTransactionState transaction) {
        long start = transaction.getTransactionStartTime();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = findMainFrame();
            if (frame == null) {
                // No window to ask in (startup, shutdown): ask again after another period
                IdleTransactionMonitor.keepOpen(context, start);
                return;
            }
            dismissReminder(context);
            String target = describeContext(context);
            String message = NLS.bind(ConnectionMessages.idle_txn_reminder_message, new Object[]{
                target,
                formatDuration(System.currentTimeMillis() - transaction.getLastExecuteTime()),
                transaction.getExecuteCount(),
                formatTime(start)});
            log.info("Idle transaction reminder: " + target); //$NON-NLS-1$
            // Each answer closes the toast (NotificationToast), so it is forgotten here first
            List<Action> actions = new ArrayList<>();
            actions.add(createAction(ConnectionMessages.idle_txn_commit, () -> {
                reminders.remove(context);
                endTransaction(frame, context, start, true, null);
            }));
            actions.add(createAction(ConnectionMessages.idle_txn_rollback, () -> {
                reminders.remove(context);
                endTransaction(frame, context, start, false, null);
            }));
            Runnable keepOpen = () -> {
                reminders.remove(context);
                IdleTransactionMonitor.keepOpen(context, start);
            };
            actions.add(createAction(ConnectionMessages.idle_txn_keep_open, keepOpen));
            reminders.put(context, NotificationToast.showWithActions(frame, ConnectionMessages.idle_txn_reminder_title, message,
                NotificationToast.Kind.WARNING, actions, keepOpen));
        });
    }

    @Override
    public void closeIdleTransactionReminder(@NotNull DBCExecutionContext context) {
        SwingUtilities.invokeLater(() -> dismissReminder(context));
    }

    @Override
    public void showIdleTransactionRolledBack(
        @NotNull DBCExecutionContext context,
        @NotNull QMTransactionState transaction,
        long rollbackTime,
        @Nullable Throwable error
    ) {
        SwingUtilities.invokeLater(() -> {
            dismissReminder(context);
            JFrame frame = findMainFrame();
            if (frame == null) {
                return;
            }
            String target = describeContext(context);
            if (error == null) {
                NotificationToast.showWithActions(frame, ConnectionMessages.idle_txn_rolled_back_title,
                    NLS.bind(ConnectionMessages.idle_txn_rolled_back_message, new Object[]{
                        target,
                        formatDuration(rollbackTime - transaction.getLastExecuteTime()),
                        transaction.getExecuteCount(),
                        formatTime(transaction.getTransactionStartTime()),
                        transaction.getUpdateCount()}),
                    NotificationToast.Kind.WARNING, List.of(), null);
            } else {
                NotificationToast.showWithActions(frame, ConnectionMessages.idle_txn_rollback_failed_title,
                    NLS.bind(ConnectionMessages.idle_txn_rollback_failed_message, target, ErrorText.describe(error)),
                    NotificationToast.Kind.ERROR, List.of(), null);
            }
        });
    }

    /**
     * Commits or rolls back the transaction shown to the user, in a job, as the explicit answer of the reminder, of
     * the query window's open transaction indicator, of Query &gt; Commit / Roll Back (menu, tool bar, shortcut) or of
     * the open transactions list. Tells the user when nothing was done (the transaction already
     * ended or changed, or a statement runs in it) or when it failed. Call in the EDT.
     *
     * @param transactionStart the shown transaction ({@link QMTransactionState#getTransactionStartTime()})
     * @param afterwards       run in the EDT when done (also when nothing was done), or null
     */
    static void endTransaction(
        @Nullable Component parent,
        @NotNull DBCExecutionContext context,
        long transactionStart,
        boolean commit,
        @Nullable Runnable afterwards
    ) {
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        new AbstractJob((commit ? "Commit transaction of " : "Roll back transaction of ") + container.getName()) { //$NON-NLS-1$ //$NON-NLS-2$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                boolean done = false;
                Exception error = null;
                try {
                    done = IdleTransactionMonitor.endTransaction(monitor, context, transactionStart, commit);
                } catch (Exception e) {
                    error = e;
                }
                boolean finalDone = done;
                Exception finalError = error;
                SwingUtilities.invokeLater(() -> {
                    if (finalError != null) {
                        log.warn("Cannot end the transaction of " + container.getName(), finalError); //$NON-NLS-1$
                        // The transaction is still open: remind again after another period
                        IdleTransactionMonitor.keepOpen(context, transactionStart);
                        ErrorDialog.show(parent, ConnectionMessages.idle_txn_title,
                            NLS.bind(ConnectionMessages.idle_txn_error, container.getName()), finalError);
                    } else if (!finalDone) {
                        IdleTransactionMonitor.keepOpen(context, transactionStart);
                        JOptionPane.showMessageDialog(parent, ConnectionMessages.idle_txn_not_ended, ConnectionMessages.idle_txn_title,
                            JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        log.info((commit ? "Committed" : "Rolled back") + " the transaction of " + container.getName() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + " (" + context.getContextName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (afterwards != null) {
                        afterwards.run();
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void dismissReminder(@NotNull DBCExecutionContext context) {
        NotificationToast toast = reminders.remove(context);
        if (toast != null) {
            toast.dismiss();
        }
    }

    /**
     * The query windows working in the context ({@link #describeQueryWindows}), or the context's name, with the
     * connection.
     */
    @NotNull
    private static String describeContext(@NotNull DBCExecutionContext context) {
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        String windows = describeQueryWindows(context);
        if (windows != null) {
            return NLS.bind(ConnectionMessages.idle_txn_windows, windows, container.getName());
        }
        return NLS.bind(ConnectionMessages.idle_txn_session, context.getContextName(), container.getName());
    }

    /**
     * The file names of the query windows working in the context (a window's own session, or the connection's main
     * context shared by the windows without one), comma separated; null for another context or without such a window.
     * Also for the open transactions list, the transaction log and the questions before ending sessions. Call in the EDT.
     */
    @Nullable
    static String describeQueryWindows(@NotNull DBCExecutionContext context) {
        if (findMainFrame() instanceof MainFrame frame) {
            List<String> windows = new ArrayList<>();
            for (SqlEditorPanel editor : frame.getQueryWindows()) {
                if (editor.getExecutionContext() == context) {
                    // The file name only: the messages name the connection themselves
                    windows.add(editor.getFileName());
                }
            }
            if (!windows.isEmpty()) {
                return String.join(", ", windows); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Idle time for the messages: seconds below a minute, then minutes, then hours and minutes.
     */
    @NotNull
    static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return NLS.bind(ConnectionMessages.idle_txn_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return NLS.bind(ConnectionMessages.idle_txn_minutes, minutes);
        }
        return NLS.bind(ConnectionMessages.idle_txn_hours, minutes / 60, minutes % 60);
    }

    @NotNull
    private static String formatTime(long time) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable runnable) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                runnable.run();
            }
        };
    }

    @Nullable
    private static JFrame findMainFrame() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof MainFrame mainFrame && mainFrame.isDisplayable()) {
                return mainFrame;
            }
        }
        return null;
    }
}
