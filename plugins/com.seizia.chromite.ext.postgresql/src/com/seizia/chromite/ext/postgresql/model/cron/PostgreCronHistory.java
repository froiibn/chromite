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
package com.seizia.chromite.ext.postgresql.model.cron;

import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * The "Job Run Details" leaf under the pg_cron node. It has no data of its own: opening it shows the run history tab.
 *
 * 설계: SQL Server 에이전트의 작업 활동 모니터 잎과 같은 탐색기 항목이다. 기록({@code cron.job_run_details})은 행이 많아 탭의 표로 보인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronHistory implements DBSObject {

    private final PostgreCronScheduler scheduler;

    PostgreCronHistory(@NotNull PostgreCronScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @NotNull
    public PostgreCronScheduler getScheduler() {
        return scheduler;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return PostgreCronMessages.node_run_details;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public PostgreCronScheduler getParentObject() {
        return scheduler;
    }

    @NotNull
    @Override
    public PostgreDataSource getDataSource() {
        return scheduler.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String toString() {
        return getName();
    }
}
