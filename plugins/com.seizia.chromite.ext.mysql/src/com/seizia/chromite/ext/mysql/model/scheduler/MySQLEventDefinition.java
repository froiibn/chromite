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
package com.seizia.chromite.ext.mysql.model.scheduler;

import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLEvent;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The editable state of a MySQL / MariaDB event (the New Event / Event Properties dialog) and the CREATE EVENT or
 * ALTER EVENT statement.
 *
 * 설계: 기존 이벤트는 바뀐 절만 ALTER EVENT에 넣는다(일정 칸 하나라도 바뀌면 ON SCHEDULE 절 전체, 이름은 RENAME TO). 날짜는
 * {@code information_schema.EVENTS}의 값(이벤트의 시간대 기준)을 글자로 다룬다. CREATE/ALTER EVENT는 날짜를 세션 시간대로 읽고
 * 일정을 바꾸면 이벤트의 시간대도 세션 시간대가 되므로, 기존 이벤트의 일정을 바꿀 때는 먼저 세션 시간대를 이벤트의 시간대로 맞춘다
 * ({@code SET time_zone}, 격리된 연결이라 다른 세션에는 영향이 없다). 새 이벤트는 그 연결의 시간대(서버 기본값)를 따른다.
 * 본문은 {@code information_schema.EVENTS.EVENT_DEFINITION}이고(DBeaver {@link MySQLEvent}에는 본문 게터가 없다) 창을 열 때 새로 읽는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class MySQLEventDefinition {

    public static final String STATUS_ENABLED = "ENABLED"; //$NON-NLS-1$
    public static final String STATUS_DISABLED = "DISABLED"; //$NON-NLS-1$
    /** Disabled on a replica (MySQL SLAVESIDE_DISABLED) */
    public static final String STATUS_REPLICA_DISABLED = "SLAVESIDE_DISABLED"; //$NON-NLS-1$

    /**
     * Interval units of {@code EVERY n unit}.
     */
    public static final List<String> INTERVAL_UNITS = List.of(
        "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "MINUTE_SECOND", "HOUR_MINUTE", "HOUR_SECOND", "DAY_HOUR", "DAY_MINUTE", "DAY_SECOND", "YEAR_MONTH"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    private final MySQLCatalog catalog;
    @Nullable
    private final MySQLEventDefinition original;
    private String name = ""; //$NON-NLS-1$
    private boolean recurring = true;
    @Nullable
    private String executeAt;
    private String intervalValue = "1"; //$NON-NLS-1$
    private String intervalField = "DAY"; //$NON-NLS-1$
    @Nullable
    private String starts;
    @Nullable
    private String ends;
    private boolean preserve = true;
    private String status = STATUS_ENABLED;
    @Nullable
    private String comment;
    private String body = ""; //$NON-NLS-1$
    @Nullable
    private String timeZone;
    @Nullable
    private String lastExecuted;

    /**
     * A new event in a database.
     */
    public MySQLEventDefinition(@NotNull MySQLCatalog catalog) {
        this.catalog = catalog;
        this.original = null;
    }

    private MySQLEventDefinition(@NotNull MySQLCatalog catalog, @NotNull MySQLEventDefinition original) {
        this.catalog = catalog;
        this.original = original;
        name = original.name;
        recurring = original.recurring;
        executeAt = original.executeAt;
        intervalValue = original.intervalValue;
        intervalField = original.intervalField;
        starts = original.starts;
        ends = original.ends;
        preserve = original.preserve;
        status = original.status;
        comment = original.comment;
        body = original.body;
        timeZone = original.timeZone;
        lastExecuted = original.lastExecuted;
    }

    /**
     * Reads an event as it is now.
     */
    @NotNull
    public static MySQLEventDefinition load(@NotNull DBRProgressMonitor monitor, @NotNull MySQLEvent event) throws DBException {
        MySQLCatalog catalog = event.getCatalog();
        MySQLEventDefinition loaded = new MySQLEventDefinition(catalog);
        String dateFormat = "'%Y-%m-%d %H:%i:%s'"; //$NON-NLS-1$
        try (JDBCSession session = DBUtils.openMetaSession(monitor, catalog, "Read MySQL event")) { //$NON-NLS-1$
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT EVENT_TYPE, DATE_FORMAT(EXECUTE_AT, " + dateFormat + ") AS EXECUTE_AT, INTERVAL_VALUE, INTERVAL_FIELD, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "DATE_FORMAT(STARTS, " + dateFormat + ") AS STARTS, DATE_FORMAT(ENDS, " + dateFormat + ") AS ENDS, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "STATUS, ON_COMPLETION, EVENT_COMMENT, EVENT_DEFINITION, TIME_ZONE, " //$NON-NLS-1$
                    + "DATE_FORMAT(LAST_EXECUTED, " + dateFormat + ") AS LAST_EXECUTED " //$NON-NLS-1$ //$NON-NLS-2$
                    + "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ? AND EVENT_NAME = ?")) { //$NON-NLS-1$
                statement.setString(1, catalog.getName());
                statement.setString(2, event.getName());
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DBException("Event " + event.getName() + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    loaded.name = event.getName();
                    loaded.recurring = "RECURRING".equals(JDBCUtils.safeGetString(resultSet, "EVENT_TYPE")); //$NON-NLS-1$ //$NON-NLS-2$
                    loaded.executeAt = JDBCUtils.safeGetString(resultSet, "EXECUTE_AT"); //$NON-NLS-1$
                    loaded.intervalValue = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "INTERVAL_VALUE")); //$NON-NLS-1$
                    loaded.intervalField = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "INTERVAL_FIELD")); //$NON-NLS-1$
                    loaded.starts = JDBCUtils.safeGetString(resultSet, "STARTS"); //$NON-NLS-1$
                    loaded.ends = JDBCUtils.safeGetString(resultSet, "ENDS"); //$NON-NLS-1$
                    loaded.status = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "STATUS")); //$NON-NLS-1$
                    loaded.preserve = "PRESERVE".equals(JDBCUtils.safeGetString(resultSet, "ON_COMPLETION")); //$NON-NLS-1$ //$NON-NLS-2$
                    loaded.comment = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(resultSet, "EVENT_COMMENT")); //$NON-NLS-1$
                    loaded.body = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "EVENT_DEFINITION")); //$NON-NLS-1$
                    loaded.timeZone = JDBCUtils.safeGetString(resultSet, "TIME_ZONE"); //$NON-NLS-1$
                    loaded.lastExecuted = JDBCUtils.safeGetString(resultSet, "LAST_EXECUTED"); //$NON-NLS-1$
                }
            }
        } catch (SQLException e) {
            throw MySQLEventSchedulerException.wrap(e, catalog.getDataSource(), null);
        }
        return new MySQLEventDefinition(catalog, loaded);
    }

    public boolean isNew() {
        return original == null;
    }

    @NotNull
    public MySQLCatalog getCatalog() {
        return catalog;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name.trim();
    }

    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }

    @Nullable
    public String getExecuteAt() {
        return executeAt;
    }

    public void setExecuteAt(@Nullable String executeAt) {
        this.executeAt = trimToNull(executeAt);
    }

    @NotNull
    public String getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(@NotNull String intervalValue) {
        this.intervalValue = intervalValue.trim();
    }

    @NotNull
    public String getIntervalField() {
        return intervalField;
    }

    public void setIntervalField(@NotNull String intervalField) {
        this.intervalField = intervalField;
    }

    @Nullable
    public String getStarts() {
        return starts;
    }

    public void setStarts(@Nullable String starts) {
        this.starts = trimToNull(starts);
    }

    @Nullable
    public String getEnds() {
        return ends;
    }

    public void setEnds(@Nullable String ends) {
        this.ends = trimToNull(ends);
    }

    public boolean isPreserve() {
        return preserve;
    }

    public void setPreserve(boolean preserve) {
        this.preserve = preserve;
    }

    @NotNull
    public String getStatus() {
        return status;
    }

    public void setStatus(@NotNull String status) {
        this.status = status;
    }

    @Nullable
    public String getComment() {
        return comment;
    }

    public void setComment(@Nullable String comment) {
        this.comment = trimToNull(comment);
    }

    @NotNull
    public String getBody() {
        return body;
    }

    public void setBody(@NotNull String body) {
        this.body = body.trim();
    }

    /**
     * The time zone the event's dates are in (existing events), or null.
     */
    @Nullable
    public String getTimeZone() {
        return timeZone;
    }

    /**
     * When the event last ran (the only run information MySQL keeps), or null.
     */
    @Nullable
    public String getLastExecuted() {
        return lastExecuted;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null ? null : CommonUtils.nullIfEmpty(value.trim());
    }

    //////////////////////////////////////////////////////////
    // Statements

    /**
     * CREATE EVENT for a new event; for an existing one the ALTER EVENT of the changed clauses (with the session time zone
     * set first when the schedule changes); empty when nothing changed.
     */
    @NotNull
    public List<String> generateStatements() {
        if (original == null) {
            StringBuilder sql = new StringBuilder("CREATE EVENT ").append(MySQLEventScheduler.eventName(catalog, name)); //$NON-NLS-1$
            sql.append("\nON SCHEDULE ").append(scheduleClause()); //$NON-NLS-1$
            sql.append("\nON COMPLETION ").append(preserve ? "PRESERVE" : "NOT PRESERVE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sql.append('\n').append(statusClause());
            if (comment != null) {
                sql.append("\nCOMMENT ").append(literal(comment)); //$NON-NLS-1$
            }
            sql.append("\nDO ").append(body); //$NON-NLS-1$
            return List.of(sql.toString());
        }
        List<String> clauses = new ArrayList<>();
        boolean scheduleChanged = recurring != original.recurring
            || (recurring
                ? !intervalValue.equals(original.intervalValue) || !intervalField.equals(original.intervalField)
                    || !Objects.equals(starts, original.starts) || !Objects.equals(ends, original.ends)
                : !Objects.equals(executeAt, original.executeAt));
        if (scheduleChanged) {
            clauses.add("ON SCHEDULE " + scheduleClause()); //$NON-NLS-1$
        }
        if (preserve != original.preserve) {
            clauses.add("ON COMPLETION " + (preserve ? "PRESERVE" : "NOT PRESERVE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (!name.equals(original.name)) {
            clauses.add("RENAME TO " + MySQLEventScheduler.eventName(catalog, name)); //$NON-NLS-1$
        }
        if (!status.equals(original.status)) {
            clauses.add(statusClause());
        }
        if (!Objects.equals(comment, original.comment)) {
            clauses.add("COMMENT " + literal(CommonUtils.notEmpty(comment))); //$NON-NLS-1$
        }
        if (!body.equals(original.body)) {
            clauses.add("DO " + body); //$NON-NLS-1$
        }
        if (clauses.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        if (scheduleChanged && !CommonUtils.isEmpty(timeZone)) {
            // The dates are in the event's time zone; ALTER EVENT reads them in the session time zone
            statements.add("SET time_zone = " + literal(timeZone)); //$NON-NLS-1$
        }
        statements.add("ALTER EVENT " + MySQLEventScheduler.eventName(catalog, original.name) + "\n" + String.join("\n", clauses)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return statements;
    }

    @NotNull
    private String scheduleClause() {
        if (!recurring) {
            return "AT " + (executeAt == null ? "CURRENT_TIMESTAMP" : literal(executeAt)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringBuilder clause = new StringBuilder("EVERY "); //$NON-NLS-1$
        // A compound value ('1:30' for HOUR_MINUTE) must be quoted; a plain number may be
        clause.append(intervalValue.matches("\\d+") ? intervalValue : literal(intervalValue)).append(' ').append(intervalField); //$NON-NLS-1$
        if (starts != null) {
            clause.append(" STARTS ").append(literal(starts)); //$NON-NLS-1$
        }
        if (ends != null) {
            clause.append(" ENDS ").append(literal(ends)); //$NON-NLS-1$
        }
        return clause.toString();
    }

    @NotNull
    private String statusClause() {
        return switch (status) {
            case STATUS_DISABLED -> "DISABLE"; //$NON-NLS-1$
            case STATUS_REPLICA_DISABLED -> "DISABLE ON SLAVE"; //$NON-NLS-1$
            default -> "ENABLE"; //$NON-NLS-1$
        };
    }

    @NotNull
    private String literal(@NotNull String value) {
        return SQLUtils.quoteString(catalog.getDataSource(), value);
    }
}
