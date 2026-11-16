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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The editable state of a pg_cron job (the New Job / Job Properties dialog) and the statement that schedules or alters it.
 *
 * 설계: 새 작업은 {@code cron.schedule}(이름 있음, 1.3 이상)이고, 다른 데이터베이스에서 실행하면 {@code cron.schedule_in_database}다.
 * 사용 안 함으로 만들면 {@code cron.alter_job(job_id := cron.schedule(...), active := false)}로 한 문에 만든다(새 작업 번호를 따로 읽지 않으려고).
 * 기존 작업은 바뀐 인자만 {@code cron.alter_job}에 넘긴다. pg_cron에는 작업 이름 바꾸기가 없어 기존 작업의 이름은 바꿀 수 없다.
 * 같은 이름으로 {@code cron.schedule}을 부르면 pg_cron이 그 작업을 덮어쓰므로 창이 이름이 겹치는지 먼저 확인한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronJobDefinition {

    private final PostgreCronScheduler scheduler;
    @Nullable
    private final PostgreCronJob job;
    private String name = ""; //$NON-NLS-1$
    private String schedule = ""; //$NON-NLS-1$
    private String command = ""; //$NON-NLS-1$
    private String database;
    private boolean active = true;

    /**
     * @param job the job to edit, or null for a new job
     */
    public PostgreCronJobDefinition(@NotNull PostgreCronScheduler scheduler, @Nullable PostgreCronJob job) {
        this.scheduler = scheduler;
        this.job = job;
        database = scheduler.getDatabase().getName();
        if (job != null) {
            name = CommonUtils.notEmpty(job.getJobName());
            schedule = job.getSchedule();
            command = job.getCommand();
            database = job.getDatabase();
            active = job.isActive();
        }
    }

    public boolean isNew() {
        return job == null;
    }

    @NotNull
    public PostgreCronScheduler getScheduler() {
        return scheduler;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name.trim();
    }

    @NotNull
    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(@NotNull String schedule) {
        this.schedule = schedule.trim();
    }

    @NotNull
    public String getCommand() {
        return command;
    }

    public void setCommand(@NotNull String command) {
        this.command = command;
    }

    @NotNull
    public String getDatabase() {
        return database;
    }

    public void setDatabase(@NotNull String database) {
        this.database = database;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * The statement that schedules the new job or alters the job; empty when nothing changed.
     */
    @NotNull
    public List<String> generateStatements() {
        if (job == null) {
            boolean otherDatabase = !database.equals(scheduler.getDatabase().getName());
            String call;
            if (otherDatabase) {
                call = "cron.schedule_in_database(job_name := " + literal(name) + ", schedule := " + literal(schedule) //$NON-NLS-1$ //$NON-NLS-2$
                    + ", command := " + literal(command) + ", database := " + literal(database) //$NON-NLS-1$ //$NON-NLS-2$
                    + (active ? "" : ", active := false") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return List.of("SELECT " + call); //$NON-NLS-1$
            }
            call = name.isEmpty()
                ? "cron.schedule(" + literal(schedule) + ", " + literal(command) + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : "cron.schedule(" + literal(name) + ", " + literal(schedule) + ", " + literal(command) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return List.of(active ? "SELECT " + call : "SELECT cron.alter_job(job_id := " + call + ", active := false)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> args = new ArrayList<>();
        if (!schedule.equals(job.getSchedule())) {
            args.add("schedule := " + literal(schedule)); //$NON-NLS-1$
        }
        if (!command.equals(job.getCommand())) {
            args.add("command := " + literal(command)); //$NON-NLS-1$
        }
        if (!Objects.equals(database, job.getDatabase())) {
            args.add("database := " + literal(database)); //$NON-NLS-1$
        }
        if (active != job.isActive()) {
            args.add("active := " + active); //$NON-NLS-1$
        }
        if (args.isEmpty()) {
            return List.of();
        }
        return List.of("SELECT cron.alter_job(job_id := " + job.getJobId() + "::bigint, " + String.join(", ", args) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    private String literal(@NotNull String value) {
        return PostgreCronManager.literal(scheduler, value);
    }
}
