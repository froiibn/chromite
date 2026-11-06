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
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * T-SQL helpers of the SQL Server Agent model: msdb procedure calls as script text, and reading the result set of a
 * procedure.
 *
 * 설계: 에이전트는 msdb 저장 프로시저({@code sp_add_job} 등)로만 바꾼다(SSMS와 같음). 만든 스크립트는 미리 보기·쿼리 창·실행에 같은 글을
 * 쓰므로 문마다 독립된 {@code EXEC msdb.dbo.…}로 만든다. SSMS "Script Job as"처럼 변수({@code @jobId})와 {@code GOTO}로 묶은 한 배치는
 * 쿼리 창이 문 단위로 나눠 실행하면 변수가 사라지므로 쓰지 않고, 작업은 이름(새 작업)이나 {@code @job_id}(기존 작업)로 가리킨다.
 * 시스템 프로시저는 {@code SET NOCOUNT}를 하지 않는 것이 있어 결과 집합 앞에 갱신 수가 올 수 있으므로 첫 결과 집합까지 넘긴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentSql {

    /**
     * Reads one row of a procedure result.
     */
    @FunctionalInterface
    public interface RowReader {
        void read(@NotNull ResultSet resultSet) throws SQLException;
    }

    private SQLServerAgentSql() {
    }

    /**
     * A Unicode string literal ({@code N'…'}), or {@code NULL}.
     */
    @NotNull
    public static String literal(@Nullable String value) {
        return value == null ? "NULL" : "N'" + value.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * Builds {@code EXEC msdb.dbo.<procedure> @a = …, @b = …}, one parameter per line.
     */
    public static final class Call {
        private final String procedure;
        private final List<String> arguments = new ArrayList<>();

        public Call(@NotNull String procedure) {
            this.procedure = procedure;
        }

        @NotNull
        public Call arg(@NotNull String name, @Nullable String value) {
            arguments.add("@" + name + " = " + literal(value)); //$NON-NLS-1$ //$NON-NLS-2$
            return this;
        }

        @NotNull
        public Call arg(@NotNull String name, int value) {
            arguments.add("@" + name + " = " + value); //$NON-NLS-1$ //$NON-NLS-2$
            return this;
        }

        @NotNull
        public Call arg(@NotNull String name, boolean value) {
            return arg(name, value ? 1 : 0);
        }

        public boolean hasArguments(int count) {
            return arguments.size() > count;
        }

        @NotNull
        public String toSql() {
            StringBuilder sql = new StringBuilder("EXEC msdb.dbo.").append(procedure); //$NON-NLS-1$
            for (int i = 0; i < arguments.size(); i++) {
                sql.append(i == 0 ? " " : ",\n    ").append(arguments.get(i)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return sql.toString();
        }
    }

    /**
     * Executes a statement (a procedure call with {@code ?} parameters) and reads its first result set.
     */
    public static void readRows(
        @NotNull JDBCSession session,
        @NotNull String sql,
        @NotNull Object[] parameters,
        @NotNull RowReader reader
    ) throws DBException {
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            boolean hasResultSet = statement.execute();
            while (true) {
                if (hasResultSet) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        while (resultSet.next()) {
                            reader.read(resultSet);
                        }
                    }
                    return;
                }
                if (statement.getUpdateCount() == -1) {
                    // No more results
                    return;
                }
                hasResultSet = statement.getMoreResults();
            }
        } catch (SQLException e) {
            throw SQLServerAgentException.wrap(e, session.getDataSource(), null);
        }
    }

    /**
     * Date and time of the agent tables ({@code run_date} {@code yyyymmdd}, {@code run_time} {@code hhmmss}, server local
     * time), or null for 0 (never).
     */
    @Nullable
    public static Timestamp toTimestamp(int date, int time) {
        if (date <= 0) {
            return null;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.of(
                LocalDate.of(date / 10000, date / 100 % 100, date % 100),
                LocalTime.of(time / 10000, time / 100 % 100, time % 100));
            // A wall clock value: shown as the server wrote it, whatever the time zone of this computer
            return Timestamp.valueOf(dateTime);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * {@code yyyymmdd} of the agent schedule tables.
     */
    public static int toDateInt(@NotNull LocalDate date) {
        return date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    /**
     * {@code hhmmss} of the agent schedule tables.
     */
    public static int toTimeInt(@NotNull LocalTime time) {
        return time.getHour() * 10000 + time.getMinute() * 100 + time.getSecond();
    }

    @Nullable
    public static LocalDate toLocalDate(int date) {
        try {
            return date <= 0 ? null : LocalDate.of(date / 10000, date / 100 % 100, date % 100);
        } catch (DateTimeException e) {
            return null;
        }
    }

    @NotNull
    public static LocalTime toLocalTime(int time) {
        try {
            return LocalTime.of(time / 10000, time / 100 % 100, time % 100);
        } catch (DateTimeException e) {
            return LocalTime.MIDNIGHT;
        }
    }

    /**
     * Seconds of a {@code run_duration} ({@code hhmmss}, hours may exceed 99).
     */
    public static long durationSeconds(int duration) {
        return duration / 10000 * 3600L + duration / 100 % 100 * 60L + duration % 100;
    }
}
