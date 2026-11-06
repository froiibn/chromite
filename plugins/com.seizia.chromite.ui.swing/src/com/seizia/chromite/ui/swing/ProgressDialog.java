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
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.runtime.ProxyProgressMonitor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 모달 진행 대화상자. EDT에서 오래 걸리는 작업을 기다릴 때 쓴다.
 *
 * 설계: {@code DBPPlatformUI.runWithProgress}/{@code executeWithProgressBlocking}을 EDT에서 부르면 작업이 끝날 때까지
 * 결과를 돌려줄 수 없다. EDT를 그냥 막으면 화면이 멈추므로, 작업은 백그라운드 Job에서 돌리고 EDT는 모달 대화상자의
 * 이벤트 루프에서 기다린다. 그동안 작업이 {@code invokeAndWait}로 띄우는 대화상자(비밀번호 입력 등)도 정상으로 처리된다.
 * SWT의 {@code ProgressMonitorDialog}와 같이 Cancel 단추와 창 닫기는 Job을 취소한다.
 * 짧은 작업에서 창이 깜빡이지 않도록 처음 {@value #SHOW_DELAY_MS}ms는 대화상자 없이 기다린다
 * (SWT {@code DesktopUI.executeWithProgressBlocking}의 짧은 대기와 같은 방식).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 감싼 오류의 영어 문구("Internal error")를 지역화된 문구로 바꿈 (docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 취소 중 문구("Canceling…")를 CoreMessages로 옮김 (docs/chromite/swing-followup-fixes.md "남은 영어 문구")
 */
final class ProgressDialog {

    private static final Log log = Log.getLog(ProgressDialog.class);

    private static final long SHOW_DELAY_MS = 300;

    private final JDialog dialog;
    private final JLabel taskLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton(CoreMessages.common_cancel);

    private ProgressDialog(@Nullable Window owner, @NotNull String title) {
        dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setIconImages(ChromiteIcons.getWindowIcons());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(taskLabel, BorderLayout.NORTH);
        content.add(progressBar, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(420, 0));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    /**
     * Runs a task in a background job and waits for it with a progress dialog. Must be called on the EDT.
     */
    static <T> T runTask(@NotNull String title, @NotNull DBRRunnableWithReturn<T> runnable) throws DBException {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        ProgressDialog progressDialog = new ProgressDialog(getActiveWindow(), title);
        AbstractJob job = new AbstractJob(title) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    result[0] = runnable.runTask(progressDialog.reportTo(monitor));
                } catch (Throwable e) {
                    error[0] = e;
                }
                return Status.OK_STATUS;
            }
        };
        progressDialog.waitFor(job);
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
        cancelButton.setEnabled(false);
        taskLabel.setText(CoreMessages.progress_canceling);
        job.cancel();
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
