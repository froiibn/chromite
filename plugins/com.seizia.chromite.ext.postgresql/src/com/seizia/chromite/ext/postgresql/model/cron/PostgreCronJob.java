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
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * A pg_cron job (a row of {@code cron.job}).
 *
 * 설계: 이름이 없는 작업(이름은 pg_cron 1.3부터, 이름 없이도 만들 수 있다)은 탐색기에 "#작업 번호"로 보인다. {@code active} 열이 없는
 * 버전에서는 늘 사용으로 본다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronJob implements DBSObject {

    private final PostgreCronScheduler scheduler;
    private final long jobId;
    @Nullable
    private final String jobName;
    private final String schedule;
    private final String command;
    private final String database;
    private final String userName;
    private final String nodeName;
    private final int nodePort;
    private final boolean active;

    PostgreCronJob(@NotNull PostgreCronScheduler scheduler, @NotNull ResultSet resultSet) {
        this.scheduler = scheduler;
        jobId = JDBCUtils.safeGetLong(resultSet, "jobid"); //$NON-NLS-1$
        jobName = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(resultSet, "jobname")); //$NON-NLS-1$
        schedule = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "schedule")); //$NON-NLS-1$
        command = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "command")); //$NON-NLS-1$
        database = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "database")); //$NON-NLS-1$
        userName = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "username")); //$NON-NLS-1$
        nodeName = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "nodename")); //$NON-NLS-1$
        nodePort = JDBCUtils.safeGetInt(resultSet, "nodeport"); //$NON-NLS-1$
        active = !scheduler.hasActive() || JDBCUtils.safeGetBoolean(resultSet, "active"); //$NON-NLS-1$
    }

    @NotNull
    public PostgreCronScheduler getScheduler() {
        return scheduler;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return jobName != null ? jobName : "#" + jobId; //$NON-NLS-1$
    }

    /**
     * The job name, or null for an unnamed job.
     */
    @Nullable
    public String getJobName() {
        return jobName;
    }

    @Property(viewable = true, order = 2)
    public long getJobId() {
        return jobId;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getSchedule() {
        return schedule;
    }

    @NotNull
    @Property(viewable = true, order = 4)
    public String getCommand() {
        return command;
    }

    @NotNull
    @Property(viewable = true, order = 5)
    public String getDatabase() {
        return database;
    }

    @NotNull
    @Property(viewable = true, order = 6)
    public String getUserName() {
        return userName;
    }

    @Property(viewable = true, order = 7)
    public boolean isActive() {
        return active;
    }

    @NotNull
    @Property(order = 8)
    public String getNodeName() {
        return nodeName;
    }

    @Property(order = 9)
    public int getNodePort() {
        return nodePort;
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
