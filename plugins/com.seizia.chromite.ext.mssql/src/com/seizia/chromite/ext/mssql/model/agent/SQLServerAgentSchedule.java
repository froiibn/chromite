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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A job schedule (editable copy of a {@code msdb.dbo.sp_help_jobschedule} row). The fields are the {@code sysschedules}
 * columns, with the same codes.
 *
 * 설계: 값은 msdb의 숫자 그대로 둔다({@code freq_type} 1 한 번, 4 매일, 8 매주, 16 매월, 32 매월 상대, 64 에이전트 시작, 128 유휴 등).
 * 화면은 이 값을 칸으로 풀어 보여 주고, 스크립트는 그대로 {@code sp_add_jobschedule}·{@code sp_update_schedule} 인자가 된다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentSchedule {

    public static final int FREQ_ONCE = 1;
    public static final int FREQ_DAILY = 4;
    public static final int FREQ_WEEKLY = 8;
    public static final int FREQ_MONTHLY = 16;
    public static final int FREQ_MONTHLY_RELATIVE = 32;
    public static final int FREQ_AGENT_START = 64;
    public static final int FREQ_IDLE = 128;

    /** freq_subday_type */
    public static final int SUBDAY_ONCE = 1;
    public static final int SUBDAY_SECONDS = 2;
    public static final int SUBDAY_MINUTES = 4;
    public static final int SUBDAY_HOURS = 8;

    /** active_end_date of a schedule without an end date */
    public static final int NO_END_DATE = 99991231;
    public static final int END_OF_DAY = 235959;

    /** null for a schedule not saved yet */
    @Nullable
    private Integer scheduleId;
    private String name = ""; //$NON-NLS-1$
    private boolean enabled = true;
    private int freqType = FREQ_DAILY;
    private int freqInterval = 1;
    private int freqSubdayType = SUBDAY_ONCE;
    private int freqSubdayInterval;
    private int freqRelativeInterval;
    private int freqRecurrenceFactor;
    private int activeStartDate = SQLServerAgentSql.toDateInt(LocalDate.now());
    private int activeEndDate = NO_END_DATE;
    private int activeStartTime;
    private int activeEndTime = END_OF_DAY;

    public SQLServerAgentSchedule() {
    }

    @NotNull
    static SQLServerAgentSchedule read(@NotNull ResultSet row) {
        SQLServerAgentSchedule schedule = new SQLServerAgentSchedule();
        schedule.scheduleId = JDBCUtils.safeGetInt(row, "schedule_id"); //$NON-NLS-1$
        schedule.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "schedule_name")); //$NON-NLS-1$
        schedule.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
        schedule.freqType = JDBCUtils.safeGetInt(row, "freq_type"); //$NON-NLS-1$
        schedule.freqInterval = JDBCUtils.safeGetInt(row, "freq_interval"); //$NON-NLS-1$
        schedule.freqSubdayType = JDBCUtils.safeGetInt(row, "freq_subday_type"); //$NON-NLS-1$
        schedule.freqSubdayInterval = JDBCUtils.safeGetInt(row, "freq_subday_interval"); //$NON-NLS-1$
        schedule.freqRelativeInterval = JDBCUtils.safeGetInt(row, "freq_relative_interval"); //$NON-NLS-1$
        schedule.freqRecurrenceFactor = JDBCUtils.safeGetInt(row, "freq_recurrence_factor"); //$NON-NLS-1$
        schedule.activeStartDate = JDBCUtils.safeGetInt(row, "active_start_date"); //$NON-NLS-1$
        schedule.activeEndDate = JDBCUtils.safeGetInt(row, "active_end_date"); //$NON-NLS-1$
        schedule.activeStartTime = JDBCUtils.safeGetInt(row, "active_start_time"); //$NON-NLS-1$
        schedule.activeEndTime = JDBCUtils.safeGetInt(row, "active_end_time"); //$NON-NLS-1$
        return schedule;
    }

    @NotNull
    public SQLServerAgentSchedule copy() {
        SQLServerAgentSchedule copy = new SQLServerAgentSchedule();
        copy.scheduleId = scheduleId;
        copy.name = name;
        copy.enabled = enabled;
        copy.freqType = freqType;
        copy.freqInterval = freqInterval;
        copy.freqSubdayType = freqSubdayType;
        copy.freqSubdayInterval = freqSubdayInterval;
        copy.freqRelativeInterval = freqRelativeInterval;
        copy.freqRecurrenceFactor = freqRecurrenceFactor;
        copy.activeStartDate = activeStartDate;
        copy.activeEndDate = activeEndDate;
        copy.activeStartTime = activeStartTime;
        copy.activeEndTime = activeEndTime;
        return copy;
    }

    /**
     * Adds the frequency arguments ({@code freq_*}, {@code active_*}) of the schedule procedures.
     */
    void addFrequencyArguments(@NotNull SQLServerAgentSql.Call call) {
        call.arg("enabled", enabled) //$NON-NLS-1$
            .arg("freq_type", freqType) //$NON-NLS-1$
            .arg("freq_interval", freqInterval) //$NON-NLS-1$
            .arg("freq_subday_type", freqSubdayType) //$NON-NLS-1$
            .arg("freq_subday_interval", freqSubdayInterval) //$NON-NLS-1$
            .arg("freq_relative_interval", freqRelativeInterval) //$NON-NLS-1$
            .arg("freq_recurrence_factor", freqRecurrenceFactor) //$NON-NLS-1$
            .arg("active_start_date", activeStartDate) //$NON-NLS-1$
            .arg("active_end_date", activeEndDate) //$NON-NLS-1$
            .arg("active_start_time", activeStartTime) //$NON-NLS-1$
            .arg("active_end_time", activeEndTime); //$NON-NLS-1$
    }

    /**
     * True when the frequency is the same (everything but the id and the name).
     */
    boolean sameFrequency(@NotNull SQLServerAgentSchedule other) {
        return enabled == other.enabled && freqType == other.freqType && freqInterval == other.freqInterval
            && freqSubdayType == other.freqSubdayType && freqSubdayInterval == other.freqSubdayInterval
            && freqRelativeInterval == other.freqRelativeInterval && freqRecurrenceFactor == other.freqRecurrenceFactor
            && activeStartDate == other.activeStartDate && activeEndDate == other.activeEndDate
            && activeStartTime == other.activeStartTime && activeEndTime == other.activeEndTime;
    }

    @Nullable
    public Integer getScheduleId() {
        return scheduleId;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFreqType() {
        return freqType;
    }

    public void setFreqType(int freqType) {
        this.freqType = freqType;
    }

    public int getFreqInterval() {
        return freqInterval;
    }

    public void setFreqInterval(int freqInterval) {
        this.freqInterval = freqInterval;
    }

    public int getFreqSubdayType() {
        return freqSubdayType;
    }

    public void setFreqSubdayType(int freqSubdayType) {
        this.freqSubdayType = freqSubdayType;
    }

    public int getFreqSubdayInterval() {
        return freqSubdayInterval;
    }

    public void setFreqSubdayInterval(int freqSubdayInterval) {
        this.freqSubdayInterval = freqSubdayInterval;
    }

    public int getFreqRelativeInterval() {
        return freqRelativeInterval;
    }

    public void setFreqRelativeInterval(int freqRelativeInterval) {
        this.freqRelativeInterval = freqRelativeInterval;
    }

    public int getFreqRecurrenceFactor() {
        return freqRecurrenceFactor;
    }

    public void setFreqRecurrenceFactor(int freqRecurrenceFactor) {
        this.freqRecurrenceFactor = freqRecurrenceFactor;
    }

    public int getActiveStartDate() {
        return activeStartDate;
    }

    public void setActiveStartDate(int activeStartDate) {
        this.activeStartDate = activeStartDate;
    }

    public int getActiveEndDate() {
        return activeEndDate;
    }

    public void setActiveEndDate(int activeEndDate) {
        this.activeEndDate = activeEndDate;
    }

    public int getActiveStartTime() {
        return activeStartTime;
    }

    public void setActiveStartTime(int activeStartTime) {
        this.activeStartTime = activeStartTime;
    }

    public int getActiveEndTime() {
        return activeEndTime;
    }

    public void setActiveEndTime(int activeEndTime) {
        this.activeEndTime = activeEndTime;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SQLServerAgentSchedule other && Objects.equals(scheduleId, other.scheduleId)
            && name.equals(other.name) && sameFrequency(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheduleId, name);
    }
}
