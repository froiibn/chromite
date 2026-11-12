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
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one scheduler of Chromite's scheduled runs (library queries, {@link QueryScheduler}; saved tasks, {@link TaskScheduler}),
 * only while Chromite runs.
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-06, 작업 예약에도 적용 2026-11-12): 예약 실행은 Chromite가 실행 중일 때 앱 안에서만 한다. OS 스케줄러 등록과
 *     헤드리스 실행기는 쓰지 않는다(docs/chromite/task-scheduling.md "OS 스케줄러").</li>
 *     <li>시각 규칙은 {@link ScheduleTiming} 하나, 타이머·겹침 건너뛰기·일시 정지·변경 알림·시작과 정지는 이 클래스 하나다. 쿼리와 작업은
 *     항목({@link Entry})의 하위 클래스로 무엇을 실행하고 어떻게 기록하는지만 정한다. 전에는 {@code QueryScheduler}가 모두 가졌다.</li>
 *     <li>데몬 스레드 하나의 타이머가 {@value #TICK_SECONDS}초마다 다음 실행 시각이 지난 항목을 고르고, 실행은 항목마다 {@link AbstractJob}
 *     하나로 한다(EDT 아님). 같은 항목의 앞 실행이 아직 돌면 이번 실행은 건너뛰고 항목이 기록한다(겹쳐 돌지 않게).</li>
 *     <li>꺼진 예약(사용 안 함, 이 PC에서 끔, 이 PC에서 돌 수 없음)은 다음 시각을 계속 미래로 옮긴다. 다시 켜도 지난 시각 때문에 바로 돌지 않는다.
 *     꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.</li>
 *     <li>메인 창이 열릴 때 {@link #start}, 닫힐 때 {@link #stop}(타이머를 멈추고 돌고 있는 실행을 취소). 끝내기 전의 "실행 중인 작업" 질문은
 *     시스템 Job이 아닌 실행을 보여 준다({@code RunningJobsCheck}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@code QueryScheduler}의 타이머·겹침·일시 정지·알림을 떼어 저장된 작업 예약과 함께 씀(앱 변경 #104,
 *   docs/chromite/task-scheduling.md "하나의 스케줄러")
 */
final class RunScheduler {

    private static final Log log = Log.getLog(RunScheduler.class);

    static final int TICK_SECONDS = 15;

    /**
     * One scheduled thing (a library query, a saved task) and its run state on this computer.
     */
    abstract static class Entry {
        volatile LocalDateTime nextRun;
        volatile LocalDateTime lastRun;
        volatile AbstractJob job;

        boolean isRunning() {
            return job != null;
        }

        /**
         * Unique among all entries of the scheduler.
         */
        @NotNull
        abstract String getKey();

        /**
         * When it runs, or null when it has no schedule (any more).
         */
        @Nullable
        abstract ScheduleTiming getTiming();

        /**
         * Whether it runs at its times: on this computer and able to run here (the timing's own enabled flag is checked apart).
         */
        abstract boolean isActive();

        /**
         * Takes over the definition of a newer entry with the same key (the library or the tasks were read again);
         * the run state (next and last run, running job) stays.
         */
        abstract void takeOver(@NotNull Entry newer);

        /**
         * The previous run still runs at the next time: this run is skipped; record why.
         */
        abstract void skipped(@NotNull String message);

        /**
         * The job of one run, not yet scheduled.
         *
         * @param manual run now by the user (not at a scheduled time)
         */
        @NotNull
        abstract AbstractJob createJob(boolean manual);

        /**
         * Stops what the run started besides its job (an engine job); called when Chromite closes.
         */
        void cancel() {
        }
    }

    private static RunScheduler instance;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    @Nullable
    private ScheduledExecutorService timer;

    private RunScheduler() {
    }

    @NotNull
    static synchronized RunScheduler getInstance() {
        if (instance == null) {
            instance = new RunScheduler();
        }
        return instance;
    }

    /**
     * Starts the timer (the main window opened).
     */
    synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Chromite scheduler"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the timer and cancels running runs (the main window closes).
     */
    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        for (Entry entry : entries.values()) {
            AbstractJob job = entry.job;
            if (job != null) {
                job.cancel();
            }
            entry.cancel();
        }
    }

    /**
     * Called in the EDT when the scheduled entries, their state or their runs changed.
     */
    void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    void fireChanged() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }

    /**
     * The scheduled entries of one kind after their definitions were read: new ones get their next run time, known ones
     * keep it unless their times changed, gone ones are dropped (unless they are running).
     *
     * @param prefix key prefix of the kind: the entries of other kinds stay
     */
    void update(@NotNull String prefix, @NotNull Collection<? extends Entry> newer) {
        Map<String, Entry> byKey = new ConcurrentHashMap<>();
        for (Entry entry : newer) {
            byKey.put(entry.getKey(), entry);
        }
        entries.keySet().removeIf(key -> key.startsWith(prefix) && !byKey.containsKey(key) && entries.get(key).job == null);
        LocalDateTime now = LocalDateTime.now();
        for (Entry entry : byKey.values()) {
            ScheduleTiming timing = entry.getTiming();
            if (timing == null) {
                continue;
            }
            Entry known = entries.get(entry.getKey());
            if (known == null) {
                entry.nextRun = timing.nextRun(now, entry.lastRun);
                entries.put(entry.getKey(), entry);
            } else {
                ScheduleTiming oldTiming = known.getTiming();
                known.takeOver(entry);
                if (!timing.isSameTiming(oldTiming)) {
                    known.nextRun = timing.nextRun(now, known.lastRun);
                }
            }
        }
        fireChanged();
    }

    /**
     * The entries of one kind.
     */
    @NotNull
    List<Entry> getEntries(@NotNull String prefix) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry);
            }
        }
        return result;
    }

    @Nullable
    Entry getEntry(@NotNull String key) {
        return entries.get(key);
    }

    /**
     * A paused entry was turned on again: it runs at its next time from now.
     */
    void resume(@NotNull Entry entry) {
        ScheduleTiming timing = entry.getTiming();
        if (timing != null) {
            entry.nextRun = timing.nextRun(LocalDateTime.now(), entry.lastRun);
        }
        fireChanged();
    }

    /**
     * Runs an entry now (Run Now), unless it is running.
     */
    void runNow(@NotNull Entry entry) {
        startRun(entry, true);
    }

    private void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Entry entry : entries.values()) {
                ScheduleTiming timing = entry.getTiming();
                if (timing == null) {
                    continue;
                }
                if (!timing.isEnabled() || !entry.isActive()) {
                    // Paused: keep the next time in the future, so resuming does not run at once for a time long past
                    entry.nextRun = timing.nextRun(now, entry.lastRun);
                    continue;
                }
                if (entry.nextRun == null || entry.nextRun.isAfter(now)) {
                    continue;
                }
                entry.nextRun = timing.nextRun(now, now);
                startRun(entry, false);
            }
        } catch (Throwable e) {
            // The timer stops for good if a tick throws
            log.error("Scheduler tick failed", e);
        }
    }

    private void startRun(@NotNull Entry entry, boolean manual) {
        synchronized (entry) {
            if (entry.job != null) {
                entry.skipped(ToolsMessages.scheduler_skipped_running);
                fireChanged();
                return;
            }
            AbstractJob job = entry.createJob(manual);
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    entry.job = null;
                    fireChanged();
                }
            });
            entry.job = job;
            job.schedule();
        }
        fireChanged();
    }
}
