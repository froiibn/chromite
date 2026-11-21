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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ErrorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRBlockingObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.runtime.ProxyProgressMonitor;
import com.seizia.chromite.runtime.DBInterruptedException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * 모달 진행 대화상자. EDT에서 오래 걸리는 작업을 기다릴 때 쓴다.
 *
 * 설계: {@code DBPPlatformUI.runWithProgress}/{@code executeWithProgressBlocking}을 EDT에서 부르면 작업이 끝날 때까지
 * 결과를 돌려줄 수 없다. EDT를 그냥 막으면 화면이 멈추므로, 작업은 백그라운드 Job에서 돌리고 EDT는 모달 대화상자의
 * 이벤트 루프에서 기다린다. 그동안 작업이 {@code invokeAndWait}로 띄우는 대화상자(비밀번호 입력 등)도 정상으로 처리된다.
 * SWT의 {@code ProgressMonitorDialog}와 같이 Cancel 단추와 창 닫기는 Job을 취소한다.
 * 짧은 작업에서 창이 깜빡이지 않도록 처음 {@value #SHOW_DELAY_MS}ms는 대화상자 없이 기다린다
 * (SWT {@code DesktopUI.executeWithProgressBlocking}의 짧은 대기와 같은 방식).
 * <p>
 * 취소가 작업을 멈추지 못할 때({@link #runTask}만): 메타데이터 연결이 다른 작업의 문장으로 바쁘면 그 연결이 필요한 작업은 드라이버의 연결 잠금에서
 * 기다리고, 그 기다림은 어느 드라이버에서도 인터럽트할 수 없다. 연결이 끝내 응답하지 않으면 작업도 끝나지 않는다. 그래서 취소 뒤
 * {@value #RELEASE_AFTER_CANCEL_MS}ms가 지나도 작업이 메타데이터 세션({@link DBCExecutionPurpose#META})의 DB 호출 안에 있으면 창을 닫고 호출한 쪽에
 * 취소({@link DBInterruptedException})를 돌려준다. 작업 스레드는 그대로 두고(죽이지 않음) 나중에 끝나면 결과와 오류를 버린다. 메타데이터 읽기로 한정한
 * 이유: 커밋·저장 같은 쓰기와 DB 밖 작업(드라이버 내려받기 등)은 사용자가 취소했다고 본 뒤에 끝나면 안 되므로 전처럼 끝날 때까지 기다린다
 * (docs/chromite/cancel-busy-connection.md).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 감싼 오류의 영어 문구("Internal error")를 지역화된 문구로 바꿈 (docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 취소 중 문구("Canceling…")를 CoreMessages로 옮김 (docs/chromite/swing-followup-fixes.md "남은 영어 문구")
 * - 2026-11-12 길이를 모르는 막대를 작업 문구 앞의 도넛 스피너({@link SpinnerIcon})로 (앱 변경 #60,
 *   docs/chromite/swing-sql-editor-session-ui.md "실행 중 표시")
 * - 2026-11-22 취소한 메타데이터 작업이 바쁜 연결을 기다리면 1.5초 뒤 창을 닫고 취소로 돌아감(앱 변경 #161,
 *   docs/chromite/cancel-busy-connection.md)
 */
final class ProgressDialog {

    private static final Log log = Log.getLog(ProgressDialog.class);

    private static final long SHOW_DELAY_MS = 300;
    // About two seconds from Cancel to a usable window, as the cancel of a running statement takes
    private static final int RELEASE_AFTER_CANCEL_MS = 1500;
    private static final int RELEASE_CHECK_MS = 250;

    private final JDialog dialog;
    private final JLabel taskLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton(CoreMessages.common_cancel);
    // The task's monitor (runTask only): its active blocks tell whether the task is inside a metadata call
    @Nullable
    private volatile DBRProgressMonitor taskMonitor;
    private volatile boolean jobDone;
    // Closed after Cancel while the task was still inside a metadata call: the caller gets a cancel, the task ends unseen
    private volatile boolean released;

    private ProgressDialog(@Nullable Window owner, @NotNull String title) {
        dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setIconImages(ChromiteIcons.getWindowIcons());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        // The length of the task is not known: a spinner before the task text, not an indeterminate bar (app change 60)
        JPanel task = new JPanel(new BorderLayout(10, 0));
        task.add(SpinnerIcon.createLabel(), BorderLayout.WEST);
        task.add(taskLabel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(task, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(420, 0));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    /**
     * Runs a task in a background job and waits for it with a progress dialog. Must be called on the EDT.
     *
     * @throws DBInterruptedException when the dialog was closed after Cancel while the task was still inside a metadata call
     */
    static <T> T runTask(@NotNull String title, @NotNull DBRRunnableWithReturn<T> runnable) throws DBException {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        ProgressDialog progressDialog = new ProgressDialog(getActiveWindow(), title);
        AbstractJob job = new AbstractJob(title) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                progressDialog.taskMonitor = monitor;
                try {
                    result[0] = runnable.runTask(progressDialog.reportTo(monitor));
                } catch (Throwable e) {
                    error[0] = e;
                }
                if (progressDialog.released) {
                    // Nobody waits for it any more: no late error window, no late result
                    log.debug("Canceled task '" + title + "' ended after its dialog was closed"
                        + (error[0] == null ? "" : ": " + error[0].getMessage()));
                }
                return Status.OK_STATUS;
            }
        };
        progressDialog.waitFor(job);
        if (progressDialog.released) {
            throw new DBInterruptedException("Canceled while the database call was still running");
        }
        if (error[0] instanceof DBException dbe) {
            throw dbe;
        } else if (error[0] != null) {
            throw new DBException(ErrorMessages.error_unexpected, error[0]);
        }
        @SuppressWarnings("unchecked")
        T typedResult = (T) result[0];
        return typedResult;
    }

    /**
     * Schedules the job and waits until it is done, showing the progress dialog if it takes a while.
     * Must be called on the EDT.
     */
    static void waitForJob(@NotNull String title, @NotNull Job job) {
        new ProgressDialog(getActiveWindow(), title).waitFor(job);
    }

    private void waitFor(@NotNull Job job) {
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                jobDone = true;
                SwingUtilities.invokeLater(dialog::dispose);
            }
        });
        cancelButton.addActionListener(e -> cancel(job));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel(job);
            }
        });
        taskLabel.setText(job.getName());
        job.schedule();
        try {
            if (job.join(SHOW_DELAY_MS, null)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for " + job.getName());
        }
        // Blocks in a modal event loop until the job's done listener disposes the dialog
        dialog.setVisible(true);
    }

    private void cancel(@NotNull Job job) {
        if (!cancelButton.isEnabled()) {
            // Closing the window after Cancel: one release check is enough
            return;
        }
        cancelButton.setEnabled(false);
        taskLabel.setText(CoreMessages.progress_canceling);
        job.cancel();
        if (taskMonitor != null) {
            // The task may enter a metadata call after the first check, so look again until it ends or the window closes
            Timer releaseCheck = new Timer(RELEASE_CHECK_MS, null);
            releaseCheck.setInitialDelay(RELEASE_AFTER_CANCEL_MS);
            releaseCheck.addActionListener(e -> {
                if (jobDone || !dialog.isDisplayable()) {
                    releaseCheck.stop();
                } else if (isInMetadataCall(taskMonitor)) {
                    releaseCheck.stop();
                    released = true;
                    dialog.dispose();
                }
            });
            releaseCheck.start();
        }
    }

    /**
     * Whether the task waits inside a call of a metadata session: the statement of a {@link DBCExecutionPurpose#META} session or
     * the session itself (waiting for the driver's connection lock, or in a {@code DatabaseMetaData} call).
     */
    private static boolean isInMetadataCall(@Nullable DBRProgressMonitor monitor) {
        List<DBRBlockingObject> blocks = monitor == null ? null : monitor.getActiveBlocks();
        if (blocks == null) {
            return false;
        }
        for (DBRBlockingObject block : blocks) {
            DBCSession session = block instanceof DBCStatement statement ? statement.getSession()
                : block instanceof DBCSession blockSession ? blockSession : null;
            if (session != null && session.getPurpose() == DBCExecutionPurpose.META) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps the job monitor so that task names appear in the dialog.
     */
    @NotNull
    DBRProgressMonitor reportTo(@NotNull DBRProgressMonitor monitor) {
        return new ProxyProgressMonitor(monitor) {
            @Override
            public void beginTask(@NotNull String name, int totalWork) {
                super.beginTask(name, totalWork);
                showTask(name);
            }

            @Override
            public void subTask(@NotNull String name) {
                super.subTask(name);
                showTask(name);
            }
        };
    }

    private void showTask(@Nullable String name) {
        if (!CommonUtils.isEmpty(name)) {
            SwingUtilities.invokeLater(() -> {
                if (cancelButton.isEnabled()) {
                    taskLabel.setText(name);
                }
            });
        }
    }

    @Nullable
    private static Window getActiveWindow() {
        Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (window != null) {
            return window;
        }
        for (Frame frame : Frame.getFrames()) {
            if (frame.isVisible()) {
                return frame;
            }
        }
        return null;
    }
}
