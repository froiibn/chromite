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
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs nobody watches (scheduled saved tasks): no question is shown and none is answered by itself.
 *
 * 설계:
 * <ul>
 *     <li>예약 실행의 Job을 {@link #begin}으로 표시한다. 표시된 Job이 일정에 올리는 Job도 표시된다(전역 Job 알림의 {@code scheduled}는 일정에 올리는
 *     스레드에서 불린다). 데이터 전송은 파이프를 다른 Job({@code DataTransferJob})에서 돌리므로 스레드만 보아서는 모자란다.</li>
 *     <li>모델이 화면에 묻는 입구(플랫폼 UI의 확인·선택·입력, 셸 명령 승인, 드라이버 내려받기, 초기화 SQL 승인, 확인이 필요한 쿼리)는 표시된 Job에서
 *     불리면 묻지 않고 {@link #refuseQuestion}이 {@link QuestionRefused}를 던진다: 실행은 "손으로 실행해 정해야 함"과 그 질문으로 실패한다.
 *     취소 같은 답을 대신 돌려주지 않는 것은, 취소를 받은 코드가 그 뒤에 무엇을 할지(건너뛰고 계속, 열어 두기) 질문마다 달라 "대신 답함"이
 *     되기 때문이다.</li>
 *     <li>모달 메시지 상자(오류, 알림)와 화면을 바꾸는 요청(개체 탐색기에서 보이기, 파일 탐색기 열기)은 {@link #skip}이 로그에만 남긴다.</li>
 *     <li>작업 자신의 명령(네이티브 도구의 명령줄, 데이터 전송 끝의 프로그램 실행)은 묻지 않고 승인된 것으로 본다({@link #acceptTaskCommand}):
 *     사용자가 그 작업의 일정을 켠 것이 변수가 무엇으로 풀리든 그 명령을 무인으로 실행하는 데 대한 동의다(사용자 결정 2026-11-12, "규칙은 레거시처럼").
 *     연결 이벤트 명령처럼 작업 밖의 명령은 그대로 거절한다.</li>
 *     <li>거절한 질문, 승인으로 본 명령, 건너뛴 화면은 실행마다 모아 두었다가({@link Run#getNotes}) 작업 로그에 덧붙인다({@link TaskScheduler}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #104, docs/chromite/task-scheduling.md "무인 실행")
 */
final class UnattendedRuns {

    private static final Log log = Log.getLog(UnattendedRuns.class);

    /**
     * One unattended run: its name and what it could not ask or show.
     */
    static final class Run {
        private final String name;
        // Approval scope of the task's own command line (UIServiceShellCommands.getTaskScope)
        @Nullable
        private final String taskScope;
        private final List<String> notes = new CopyOnWriteArrayList<>();

        Run(@NotNull String name, @Nullable String taskScope) {
            this.name = name;
            this.taskScope = taskScope;
        }

        @NotNull
        List<String> getNotes() {
            return notes;
        }
    }

    /**
     * A question an unattended run can't ask: the run fails with this message.
     */
    static final class QuestionRefused extends RuntimeException {
        QuestionRefused(@NotNull String message) {
            super(message);
        }
    }

    private static final Map<Job, Run> RUNS = new ConcurrentHashMap<>();
    private static volatile boolean installed;

    private UnattendedRuns() {
    }

    /**
     * Marks a job, before it is scheduled, as an unattended run; the jobs it schedules belong to the run too.
     */
    static void begin(@NotNull Job job, @NotNull Run run) {
        install();
        RUNS.put(job, run);
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        Job.getJobManager().addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void scheduled(IJobChangeEvent event) {
                // Called in the thread that schedules the job
                Run run = current();
                if (run != null && event.getJob() != null) {
                    RUNS.putIfAbsent(event.getJob(), run);
                }
            }

            @Override
            public void done(IJobChangeEvent event) {
                RUNS.remove(event.getJob());
            }
        });
        installed = true;
    }

    /**
     * The unattended run of the calling thread's job, or null.
     */
    @Nullable
    static Run current() {
        if (RUNS.isEmpty()) {
            return null;
        }
        Job job = Job.getJobManager().currentJob();
        return job == null ? null : RUNS.get(job);
    }

    /**
     * Called first by every question to the user: in an unattended run it is not asked and the run fails.
     *
     * @throws QuestionRefused in an unattended run
     */
    static void refuseQuestion(@Nullable String title, @Nullable String question) {
        Run run = current();
        if (run == null) {
            return;
        }
        String text = describe(title, question);
        String message = "A scheduled run can't ask, decide it in a run by hand: " + text; //$NON-NLS-1$
        run.notes.add(message);
        log.warn("Scheduled run '" + run.name + "': " + message); //$NON-NLS-1$ //$NON-NLS-2$
        throw new QuestionRefused(message);
    }

    /**
     * Whether a shell command that asks for approval is the scheduled task's own command: its native tool command line
     * (the task's approval scope) or the command of its data transfer's "execute process" step (no scope). The schedule
     * the user turned on is the consent to run it; the command is noted in the run's log.
     *
     * @param scope the approval scope of the command
     * @return false outside an unattended run or for any other command (a connection event command)
     */
    static boolean acceptTaskCommand(@Nullable String scope, @NotNull String command) {
        Run run = current();
        if (run == null || scope != null && !scope.equals(run.taskScope)) {
            return false;
        }
        String text = "Run without asking (the schedule was turned on for this task): " + command; //$NON-NLS-1$
        run.notes.add(text);
        log.info("Scheduled run '" + run.name + "': " + text); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    /**
     * Called first by a modal message or a request that changes the screen: in an unattended run it is only logged.
     *
     * @return true when the caller must not show it
     */
    static boolean skip(@Nullable String title, @Nullable String message) {
        Run run = current();
        if (run == null) {
            return false;
        }
        String text = "Not shown in a scheduled run: " + describe(title, message); //$NON-NLS-1$
        run.notes.add(text);
        log.warn("Scheduled run '" + run.name + "': " + text); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    @NotNull
    private static String describe(@Nullable String title, @Nullable String text) {
        String body = CommonUtils.notEmpty(text).strip().replaceAll("\\s*\\R\\s*", " "); //$NON-NLS-1$ //$NON-NLS-2$
        if (CommonUtils.isEmpty(title)) {
            return body;
        }
        return body.isEmpty() ? "\"" + title + "\"" : "\"" + title + "\" " + body; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
