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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A SQL Server event alert as the alert dialog edits it, and the msdb procedure calls that create, change or drop it.
 *
 * 설계: SSMS 새 경고 창의 "SQL Server 이벤트 경고"만 다룬다(성능 조건·WMI 경고는 남긴 것). 응답의 운영자 알림은 전자 메일만이다
 * ({@code sp_add_notification @notification_method = 1}). 바꿀 때는 {@code sp_update_alert}에 바뀐 인자만 넘기고, 이름이 바뀌면
 * 알림 문은 새 이름을 쓴다. 데이터베이스는 빈 문자열, 작업은 0 uniqueidentifier로 지운다({@code sp_update_alert} 소스의 규칙).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentAlertDefinition {

    private static final String NO_JOB = "00000000-0000-0000-0000-000000000000"; //$NON-NLS-1$
    private static final int NOTIFY_EMAIL = 1;

    /** null for a new alert */
    @Nullable
    private String originalName;
    private String name = ""; //$NON-NLS-1$
    private boolean enabled = true;
    /** 0 when the alert is raised by severity */
    private int messageId;
    /** 0 when the alert is raised by message id */
    private int severity = 1;
    @Nullable
    private String databaseName;
    @Nullable
    private String jobName;
    private int delayBetweenResponses;
    private boolean includeEventDescriptionInEmail;
    private final Set<String> emailOperators = new LinkedHashSet<>();

    private SQLServerAgentAlertDefinition() {
    }

    @NotNull
    public static SQLServerAgentAlertDefinition newAlert() {
        return new SQLServerAgentAlertDefinition();
    }

    /**
     * The definition of an alert, with the operators it notifies by e-mail.
     */
    @NotNull
    public static SQLServerAgentAlertDefinition load(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgentAlert alert) throws DBException {
        SQLServerAgentAlertDefinition definition = new SQLServerAgentAlertDefinition();
        definition.originalName = alert.getName();
        definition.name = alert.getName();
        definition.enabled = alert.isEnabled();
        definition.messageId = alert.getMessageId();
        definition.severity = alert.getSeverity();
        definition.databaseName = alert.getDatabaseName();
        definition.jobName = alert.getJobName();
        definition.delayBetweenResponses = alert.getDelayBetweenResponses();
        definition.includeEventDescriptionInEmail = alert.isIncludeEventDescriptionInEmail();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, alert.getDataSource(), "Read alert notifications")) { //$NON-NLS-1$
            SQLServerAgentSql.readRows(session,
                "EXEC msdb.dbo.sp_help_notification @object_type = N'OPERATORS', @name = ?, @enum_type = N'ACTUAL', @notification_method = 7", //$NON-NLS-1$
                new Object[] {alert.getName()}, row -> {
                    if (JDBCUtils.safeGetInt(row, "use_email") != 0) { //$NON-NLS-1$
                        definition.emailOperators.add(CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "operator_name"))); //$NON-NLS-1$
                    }
                });
        }
        return definition;
    }

    @NotNull
    public SQLServerAgentAlertDefinition copy() {
        SQLServerAgentAlertDefinition copy = new SQLServerAgentAlertDefinition();
        copy.originalName = originalName;
        copy.name = name;
        copy.enabled = enabled;
        copy.messageId = messageId;
        copy.severity = severity;
        copy.databaseName = databaseName;
        copy.jobName = jobName;
        copy.delayBetweenResponses = delayBetweenResponses;
        copy.includeEventDescriptionInEmail = includeEventDescriptionInEmail;
        copy.emailOperators.addAll(emailOperators);
        return copy;
    }

    /**
     * Statements that create the alert, or change it from the original definition (empty if nothing changed).
     */
    @NotNull
    public List<String> generateStatements(@Nullable SQLServerAgentAlertDefinition original) {
        List<String> statements = new ArrayList<>();
        if (original == null || originalName == null) {
            SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_add_alert") //$NON-NLS-1$
                .arg("name", name) //$NON-NLS-1$
                .arg("message_id", messageId) //$NON-NLS-1$
                .arg("severity", severity) //$NON-NLS-1$
                .arg("enabled", enabled) //$NON-NLS-1$
                .arg("delay_between_responses", delayBetweenResponses) //$NON-NLS-1$
                .arg("include_event_description_in", includeEventDescriptionInEmail ? 1 : 0); //$NON-NLS-1$
            if (databaseName != null) {
                call.arg("database_name", databaseName); //$NON-NLS-1$
            }
            if (jobName != null) {
                call.arg("job_name", jobName); //$NON-NLS-1$
            }
            statements.add(call.toSql());
            for (String operator : emailOperators) {
                statements.add(notificationStatement("sp_add_notification", operator, true)); //$NON-NLS-1$
            }
            return statements;
        }
        SQLServerAgentSql.Call update = new SQLServerAgentSql.Call("sp_update_alert").arg("name", originalName); //$NON-NLS-1$ //$NON-NLS-2$
        if (!name.equals(original.name)) {
            update.arg("new_name", name); //$NON-NLS-1$
        }
        if (enabled != original.enabled) {
            update.arg("enabled", enabled); //$NON-NLS-1$
        }
        if (messageId != original.messageId || severity != original.severity) {
            // One of the two is 0: pass both so that switching from one to the other clears the old one
            update.arg("message_id", messageId).arg("severity", severity); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!Objects.equals(databaseName, original.databaseName)) {
            update.arg("database_name", CommonUtils.notEmpty(databaseName)); //$NON-NLS-1$
        }
        if (!Objects.equals(jobName, original.jobName)) {
            if (jobName == null) {
                update.arg("job_id", NO_JOB); //$NON-NLS-1$
            } else {
                update.arg("job_name", jobName); //$NON-NLS-1$
            }
        }
        if (delayBetweenResponses != original.delayBetweenResponses) {
            update.arg("delay_between_responses", delayBetweenResponses); //$NON-NLS-1$
        }
        if (includeEventDescriptionInEmail != original.includeEventDescriptionInEmail) {
            update.arg("include_event_description_in", includeEventDescriptionInEmail ? 1 : 0); //$NON-NLS-1$
        }
        if (update.hasArguments(1)) {
            statements.add(update.toSql());
        }
        for (String operator : original.emailOperators) {
            if (!emailOperators.contains(operator)) {
                statements.add(notificationStatement("sp_delete_notification", operator, false)); //$NON-NLS-1$
            }
        }
        for (String operator : emailOperators) {
            if (!original.emailOperators.contains(operator)) {
                statements.add(notificationStatement("sp_add_notification", operator, true)); //$NON-NLS-1$
            }
        }
        return statements;
    }

    @NotNull
    private String notificationStatement(@NotNull String procedure, @NotNull String operator, boolean withMethod) {
        SQLServerAgentSql.Call call = new SQLServerAgentSql.Call(procedure)
            .arg("alert_name", name) //$NON-NLS-1$
            .arg("operator_name", operator); //$NON-NLS-1$
        if (withMethod) {
            call.arg("notification_method", NOTIFY_EMAIL); //$NON-NLS-1$
        }
        return call.toSql();
    }

    @NotNull
    public static String generateDropStatement(@NotNull String alertName) {
        return new SQLServerAgentSql.Call("sp_delete_alert").arg("name", alertName).toSql(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public boolean isNew() {
        return originalName == null;
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

    public int getMessageId() {
        return messageId;
    }

    public int getSeverity() {
        return severity;
    }

    /**
     * Raised by a message number (the severity is cleared).
     */
    public void setMessageId(int messageId) {
        this.messageId = messageId;
        this.severity = 0;
    }

    /**
     * Raised by a severity (the message number is cleared).
     */
    public void setSeverity(int severity) {
        this.severity = severity;
        this.messageId = 0;
    }

    @Nullable
    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(@Nullable String databaseName) {
        this.databaseName = databaseName;
    }

    @Nullable
    public String getJobName() {
        return jobName;
    }

    public void setJobName(@Nullable String jobName) {
        this.jobName = jobName;
    }

    /**
     * Seconds between two responses to the same alert.
     */
    public int getDelayBetweenResponses() {
        return delayBetweenResponses;
    }

    public void setDelayBetweenResponses(int delayBetweenResponses) {
        this.delayBetweenResponses = delayBetweenResponses;
    }

    public boolean isIncludeEventDescriptionInEmail() {
        return includeEventDescriptionInEmail;
    }

    public void setIncludeEventDescriptionInEmail(boolean includeEventDescriptionInEmail) {
        this.includeEventDescriptionInEmail = includeEventDescriptionInEmail;
    }

    /**
     * Operators notified by e-mail; the set is edited in place.
     */
    @NotNull
    public Set<String> getEmailOperators() {
        return emailOperators;
    }
}
