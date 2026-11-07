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
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Before Chromite closes: lists the jobs that are still running (queries, exports, maintenance, database tasks) and asks
 * Wait / Stop and Exit / Cancel. Cancel is the default and keeps Chromite open.
 *
 * 설계: SWT는 끝낼 때 작업 관리자의 실행 중인 작업이 있으면 취소할지 묻고({@code ApplicationWorkbenchAdvisor.cancelRunningTasks}) 아니면
 * 앱을 닫지 않았다. Swing은 묻지 않고 닫아 실행 중인 쿼리·내보내기를 플랫폼 종료가 끊었다(docs/chromite/swt-parity/A-platform.md A-S2).
 * <ul>
 *     <li>대상: 실행 중이거나 곧 실행될(대기) Job 중 시스템 Job이 아닌 것(연결 감시, 자동 완성 분석 같은 내부 작업은 시스템 Job이다)과
 *     현재 프로젝트 작업 관리자의 실행 중인 작업. Job은 이름으로 보인다(SQL 실행, 데이터 내보내기 등).</li>
 *     <li>기다리기: 진행 대화상자에서 모두 끝날 때까지 기다린 뒤 끝내기를 계속한다. 기다리는 동안 취소하면 앱을 닫지 않는다.</li>
 *     <li>멈추고 끝내기: Job을 취소하고(문장 취소) 작업 관리자의 작업도 취소한 뒤, 멈출 때까지 잠시 기다리고 끝내기를 계속한다.
 *     이 순서는 트랜잭션 질문({@link ExitTransactionCheck}) 앞이다: 문장이 멈춰야 커밋·롤백을 할 수 있고, 멈춘 문장이 남긴 트랜잭션도
 *     그 질문에 나온다.</li>
 *     <li>취소(기본 단추): 앱을 닫지 않고 Job은 그대로 실행된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "끝낼 때 실행 중인 작업")
 */
final class RunningJobsCheck {

    private static final Log log = Log.getLog(RunningJobsCheck.class);

    private static final int MAX_LISTED = 10;
    private static final long POLL_MS = 200;
    // How long Stop and Exit waits for the canceled jobs before it goes on
    private static final long STOP_WAIT_MS = 10000;

    private RunningJobsCheck() {
    }

    /**
     * Call on the EDT before the transaction question of the exit.
     *
     * @return true when Chromite may go on closing: nothing runs, the jobs ended while waiting, or they were stopped
     */
    static boolean confirm(@NotNull Component parent) {
        List<Job> jobs = findRunningJobs();
        DBTTaskManager taskManager = getTaskManager();
        boolean tasksRunning = taskManager != null && taskManager.hasRunningTasks();
        if (jobs.isEmpty() && !tasksRunning) {
            return true;
        }
        String[] options = {LifecycleMessages.exit_jobs_wait, LifecycleMessages.exit_jobs_stop, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent,
            NLS.bind(LifecycleMessages.exit_jobs_message, describe(jobs, tasksRunning)),
            LifecycleMessages.exit_jobs_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (answer == 0) {
            return waitFor(LifecycleMessages.exit_jobs_waiting, Long.MAX_VALUE);
        }
        if (answer == 1) {
            for (Job job : jobs) {
                log.info("Stop running job at exit: " + job.getName()); //$NON-NLS-1$
                job.cancel();
            }
            if (tasksRunning) {
                taskManager.cancelRunningTasks();
            }
            // The canceled statements need a moment to stop; what does not stop in time is ended by the platform shutdown
            waitFor(LifecycleMessages.exit_jobs_stopping, STOP_WAIT_MS);
            return true;
        }
        return false;
    }

    /**
     * Waits with a progress dialog until no job or task runs, or the time is up.
     *
     * @return false when the user canceled the waiting
     */
    private static boolean waitFor(@NotNull String title, long maxMillis) {
        long deadline = maxMillis == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + maxMillis;
        try {
            Boolean ended = ProgressDialog.runTask(title, monitor -> {
                while (!monitor.isCanceled() && System.currentTimeMillis() < deadline) {
                    List<Job> running = findRunningJobs();
                    DBTTaskManager taskManager = getTaskManager();
                    if (running.isEmpty() && (taskManager == null || !taskManager.hasRunningTasks())) {
                        return true;
                    }
                    monitor.subTask(running.isEmpty() ? LifecycleMessages.exit_jobs_tasks : running.getFirst().getName());
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return !monitor.isCanceled();
            });
            return Boolean.TRUE.equals(ended);
        } catch (DBException e) {
            log.debug("Waiting for running jobs failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Jobs that are running or about to run, without the system jobs (connection monitoring, completion analysis) and the
     * progress dialog's own job.
     */
    @NotNull
    private static List<Job> findRunningJobs() {
        List<Job> jobs = new ArrayList<>();
        Job current = Job.getJobManager().currentJob();
        for (Job job : Job.getJobManager().find(null)) {
            int state = job.getState();
            if ((state == Job.RUNNING || state == Job.WAITING) && !job.isSystem() && job != current) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    @NotNull
    private static String describe(@NotNull List<Job> jobs, boolean tasksRunning) {
        Set<String> names = new LinkedHashSet<>();
        for (Job job : jobs) {
            names.add(job.getName());
        }
        List<String> lines = new ArrayList<>();
        for (String name : names) {
            if (lines.size() == MAX_LISTED) {
                lines.add(NLS.bind(LifecycleMessages.exit_jobs_more, names.size() - MAX_LISTED));
                break;
            }
            lines.add(name);
        }
        if (tasksRunning) {
            lines.add(LifecycleMessages.exit_jobs_tasks);
        }
        return String.join("\n", lines); //$NON-NLS-1$
    }

    @Nullable
    private static DBTTaskManager getTaskManager() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? null : project.getTaskManager(false);
    }
}
