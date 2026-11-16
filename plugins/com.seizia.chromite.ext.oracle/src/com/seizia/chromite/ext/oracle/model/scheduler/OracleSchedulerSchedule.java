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

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * A named schedule of the Oracle Scheduler ({@code *_SCHEDULER_SCHEDULES}) under the schema's Scheduler folder.
 *
 * 설계: DBeaver의 스케줄러 폴더에는 작업과 프로그램만 있어 일정 목록을 더했다. 읽기 전용(속성 창)이다. 날짜는
 * {@link OracleSchedulerManager#DATE_FORMAT}(TZR: 지역 이름이나 오프셋)으로 읽은 글자다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerSchedule implements DBSObject {

    private final OracleSchema schema;
    private final String name;
    private final String scheduleType;
    @Nullable
    private final String startDate;
    @Nullable
    private final String repeatInterval;
    @Nullable
    private final String endDate;
    @Nullable
    private final String comments;

    OracleSchedulerSchedule(@NotNull OracleSchema schema, @NotNull ResultSet resultSet) {
        this.schema = schema;
        name = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "SCHEDULE_NAME")); //$NON-NLS-1$
        scheduleType = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "SCHEDULE_TYPE")); //$NON-NLS-1$
        startDate = JDBCUtils.safeGetString(resultSet, "START_DATE"); //$NON-NLS-1$
        repeatInterval = JDBCUtils.safeGetString(resultSet, "REPEAT_INTERVAL"); //$NON-NLS-1$
        endDate = JDBCUtils.safeGetString(resultSet, "END_DATE"); //$NON-NLS-1$
        comments = JDBCUtils.safeGetString(resultSet, "COMMENTS"); //$NON-NLS-1$
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getScheduleType() {
        return scheduleType;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getStartDate() {
        return startDate;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getRepeatInterval() {
        return repeatInterval;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getEndDate() {
        return endDate;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 6)
    public String getDescription() {
        return comments;
    }

    @NotNull
    @Override
    public OracleSchema getParentObject() {
        return schema;
    }

    @NotNull
    @Override
    public OracleDataSource getDataSource() {
        return schema.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
