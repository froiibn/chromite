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
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;

/**
 * A SQL Server Agent alert, as one row of {@code msdb.dbo.sp_help_alert}.
 *
 * 설계: 목록과 속성 창에 보일 값과 편집 창이 처음 채울 값({@link SQLServerAgentAlertDefinition#load})을 함께 담는다.
 * 알릴 운영자 목록만 편집 창을 열 때 따로 읽는다({@code sp_help_notification}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentAlert implements DBSObject, DBPStatefulObject {

    /** sp_help_alert type of a SQL Server event alert */
    public static final int TYPE_EVENT = 1;

    private final SQLServerAgent agent;
    private final String name;
    private final boolean enabled;
    private final int type;
    private final int messageId;
    private final int severity;
    @Nullable
    private final String databaseName;
    @Nullable
    private final String jobId;
    @Nullable
    private final String jobName;
    private final int delayBetweenResponses;
    private final boolean includeEventDescriptionInEmail;
    @Nullable
    private final String notificationMessage;
    private final int occurrenceCount;
    @Nullable
    private final Date lastOccurrence;

    SQLServerAgentAlert(@NotNull SQLServerAgent agent, @NotNull ResultSet row) {
        this.agent = agent;
        this.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "name")); //$NON-NLS-1$
        this.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
        this.type = JDBCUtils.safeGetInt(row, "type"); //$NON-NLS-1$
        this.messageId = JDBCUtils.safeGetInt(row, "message_id"); //$NON-NLS-1$
        this.severity = JDBCUtils.safeGetInt(row, "severity"); //$NON-NLS-1$
        this.databaseName = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(row, "database_name")); //$NON-NLS-1$
        String job = JDBCUtils.safeGetString(row, "job_id"); //$NON-NLS-1$
        // No job: the zero uniqueidentifier
        this.jobId = job == null || job.startsWith("00000000") ? null : job; //$NON-NLS-1$
        this.jobName = this.jobId == null ? null : CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(row, "job_name")); //$NON-NLS-1$
        this.delayBetweenResponses = JDBCUtils.safeGetInt(row, "delay_between_responses"); //$NON-NLS-1$
        // Bit 1 (e-mail) of include_event_description
        this.includeEventDescriptionInEmail = (JDBCUtils.safeGetInt(row, "include_event_description") & 1) != 0; //$NON-NLS-1$
        this.notificationMessage = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(row, "notification_message")); //$NON-NLS-1$
        this.occurrenceCount = JDBCUtils.safeGetInt(row, "occurrence_count"); //$NON-NLS-1$
        this.lastOccurrence = SQLServerAgentSql.toTimestamp(
            JDBCUtils.safeGetInt(row, "last_occurrence_date"), JDBCUtils.safeGetInt(row, "last_occurrence_time")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public SQLServerAgent getAgent() {
        return agent;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 1 SQL Server event, 2 performance condition, 3 WMI event.
     */
    public int getType() {
        return type;
    }

    @Property(viewable = true, order = 3)
    public int getSeverity() {
        return severity;
    }

    @Property(viewable = true, order = 4)
    public int getMessageId() {
        return messageId;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getDatabaseName() {
        return databaseName;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getJobName() {
        return jobName;
    }

    @Nullable
    public String getJobId() {
        return jobId;
    }

    @Property(order = 7)
    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    @Nullable
    @Property(order = 8)
    public Date getLastOccurrence() {
        return lastOccurrence;
    }

    @Property(order = 9)
    public int getDelayBetweenResponses() {
        return delayBetweenResponses;
    }

    public boolean isIncludeEventDescriptionInEmail() {
        return includeEventDescriptionInEmail;
    }

    @Nullable
    public String getNotificationMessage() {
        return notificationMessage;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public SQLServerAgent getParentObject() {
        return agent;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return agent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return enabled ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
    }

    @Override
    public String toString() {
        return name;
    }
}
