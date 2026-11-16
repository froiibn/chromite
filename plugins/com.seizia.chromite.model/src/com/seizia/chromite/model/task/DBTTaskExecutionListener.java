/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.task;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

/**
 * Task execution listener
 *
 * 변경 이력(Chromite):
 * - 2026-11-12 {@link #getStartedBy}, {@link #checkBeforeRun}, {@link #isTaskConfirmed}: 예약 실행이 실행 기록에 "예약"으로 남고, 무인 실행에서
 *   할 수 없는 것(저장되지 않은 비밀번호, 수동 커밋 세션 등)을 처리기 전에 실패한 실행으로 기록하고, 사용자가 일정을 켤 때 한 번 답한 작업 확인(복원)을
 *   다시 묻지 않기 위해서(앱 변경 #104, docs/chromite/task-scheduling.md "무인 실행")
 */
public interface DBTTaskExecutionListener {

    void taskStarted(@Nullable DBTTask task);

    void taskFinished(@Nullable DBTTask task, @Nullable Object result, @Nullable Throwable error, @Nullable Object settings);

    void subTaskFinished(@Nullable DBTTask task, @Nullable Throwable error, @Nullable Object settings);

    /**
     * Who started the run, kept in the run record; null for the product (a run by the user).
     */
    @Nullable
    default String getStartedBy() {
        return null;
    }

    /**
     * Called by the task runner after the run record and its log were created and before the task's handler starts:
     * an exception fails the run with its message in the log.
     */
    default void checkBeforeRun(@NotNull DBTTask task) throws DBException {
    }

    /**
     * Whether the user already answered the task's run confirmation (the restore confirmation) for this run: the user
     * gave it once when turning the task's schedule on. False asks as before.
     */
    default boolean isTaskConfirmed(@NotNull DBTTask task) {
        return false;
    }

}
