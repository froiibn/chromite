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
package com.seizia.chromite.ext.oracle.model.scheduler;

import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.List;

/**
 * The Chromite part of a schema's Scheduler folder: schedules, and the Job Run Details and Running Jobs leaves.
 *
 * 설계: DBeaver의 스키마 아래 "스케줄러" 폴더(작업, 프로그램)에 항목을 더하려고 스키마마다 이 객체 하나를 둔다. {@code plugin.xml}은
 * 점으로 이은 속성 경로({@code scheduler.schedules})로 읽으므로 DBeaver 원본 {@code OracleSchema}에는 게터 하나와 새로 고침 한 줄만 더한다.
 * 일정 목록은 스키마를 새로 고치면 지운다({@link #clearCache}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchemaScheduler {

    private final OracleSchema schema;
    private final List<OracleSchedulerMonitor> runDetails;
    private final List<OracleSchedulerMonitor> runningJobs;
    @Nullable
    private List<OracleSchedulerSchedule> schedules;

    public OracleSchemaScheduler(@NotNull OracleSchema schema) {
        this.schema = schema;
        runDetails = List.of(new OracleSchedulerMonitor(schema, OracleSchedulerMonitor.Kind.RUN_DETAILS));
        runningJobs = List.of(new OracleSchedulerMonitor(schema, OracleSchedulerMonitor.Kind.RUNNING_JOBS));
    }

    @NotNull
    public OracleSchema getSchema() {
        return schema;
    }

    @Association
    @NotNull
    public synchronized List<OracleSchedulerSchedule> getSchedules(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (schedules == null) {
            schedules = OracleSchedulerManager.readSchedules(monitor, schema);
        }
        return schedules;
    }

    /**
     * The Job Run Details leaf (a list of one, as tree items are lists).
     */
    @Association
    @NotNull
    public List<OracleSchedulerMonitor> getRunDetails() {
        return runDetails;
    }

    /**
     * The Running Jobs leaf.
     */
    @Association
    @NotNull
    public List<OracleSchedulerMonitor> getRunningJobs() {
        return runningJobs;
    }

    public synchronized void clearCache() {
        schedules = null;
    }
}
