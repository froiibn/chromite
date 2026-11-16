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

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLEvent;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The event scheduler of a MySQL / MariaDB server: its state, whether the login may manage the events of a database,
 * and the execution of event statements.
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentManager}와 같은 역할이다. 이벤트 목록은 DBeaver의 이벤트 폴더({@code MySQLCatalog#getEvents})를
 * 그대로 쓰고, 이 클래스는 그 폴더에 없는 것만 더한다.
 * <ul>
 *     <li>{@code event_scheduler}가 OFF면 이벤트가 실행되지 않는다. 켜는 것은 서버 설정이라(SUPER/SYSTEM_VARIABLES_ADMIN, DISABLED면 설정 파일과
 *     재시작) 명령({@link #ENABLE_SCHEDULER_COMMAND})만 보여 주고 실행하지 않는다.</li>
 *     <li>권한: 데이터베이스의 EVENT 권한(전역 또는 데이터베이스 수준, 데이터베이스 이름 패턴 포함)을 {@code information_schema}에서 읽는다.
 *     역할로 받은 권한은 여기에 보이지 않으므로, 활성 역할이 있으면 "알 수 없음"으로 두고 서버에 맡긴다({@link Access#canManage}).</li>
 *     <li>바꾸는 문은 격리된 실행 문맥(새 연결)에서 실행한다. CREATE/ALTER/DROP EVENT는 암묵적으로 커밋하므로 사용자의 편집기 세션에서
 *     실행하면 그 세션의 진행 중인 트랜잭션까지 커밋된다(트랜잭션 안전 규칙).</li>
 *     <li>MySQL·MariaDB는 이벤트 실행 기록을 남기지 않는다. 마지막 실행 시각({@code LAST_EXECUTED})만 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public final class MySQLEventScheduler {

    private static final Log log = Log.getLog(MySQLEventScheduler.class);

    /**
     * The command that turns the event scheduler on: shown to the user, never run by Chromite.
     */
    public static final String ENABLE_SCHEDULER_COMMAND = "SET GLOBAL event_scheduler = ON;"; //$NON-NLS-1$

    public enum State {
        ON,
        OFF,
        /** Turned off at startup (--event-scheduler=DISABLED): needs the server configuration and a restart */
        DISABLED,
        UNKNOWN;

        @NotNull
        public String getLabel() {
            return switch (this) {
                case ON -> MySQLEventSchedulerMessages.state_on;
                case OFF -> MySQLEventSchedulerMessages.state_off;
                case DISABLED -> MySQLEventSchedulerMessages.state_disabled;
                case UNKNOWN -> MySQLEventSchedulerMessages.state_unknown;
            };
        }
    }

    /**
     * The scheduler state and what the login may do with the events of one database.
     *
     * @param canManage true / false when known, null when roles may grant it (the server decides)
     */
    public record Access(@NotNull State state, @Nullable Boolean canManage) {
        public boolean isDenied() {
            return Boolean.FALSE.equals(canManage);
        }
    }

    private MySQLEventScheduler() {
    }

    /**
     * Reads the event_scheduler state and the login's EVENT privilege on a database.
     */
    @NotNull
    public static Access readAccess(@NotNull DBRProgressMonitor monitor, @NotNull MySQLCatalog catalog) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, catalog, "Read MySQL event scheduler state")) { //$NON-NLS-1$
            State state = State.UNKNOWN;
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery("SELECT @@global.event_scheduler")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    String value = CommonUtils.notEmpty(resultSet.getString(1)).toUpperCase(Locale.ROOT);
                    state = switch (value) {
                        case "ON", "1" -> State.ON; //$NON-NLS-1$ //$NON-NLS-2$
                        case "OFF", "0" -> State.OFF; //$NON-NLS-1$ //$NON-NLS-2$
                        case "DISABLED" -> State.DISABLED; //$NON-NLS-1$
                        default -> State.UNKNOWN;
                    };
                }
            }
            return new Access(state, readCanManage(session, catalog.getName()));
        } catch (SQLException e) {
            throw MySQLEventSchedulerException.wrap(e, catalog.getDataSource(), null);
        }
    }

    @Nullable
    private static Boolean readCanManage(@NotNull JDBCSession session, @NotNull String database) {
        // GRANTEE is written as 'user'@'host'
        String grantee = "CONCAT('''', SUBSTRING_INDEX(CURRENT_USER(), '@', 1), '''@''', SUBSTRING_INDEX(CURRENT_USER(), '@', -1), '''')"; //$NON-NLS-1$
        try (JDBCPreparedStatement statement = session.prepareStatement(
            "SELECT (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES WHERE PRIVILEGE_TYPE = 'EVENT' AND GRANTEE = " + grantee + ") + " //$NON-NLS-1$ //$NON-NLS-2$
                + "(SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE PRIVILEGE_TYPE = 'EVENT' AND GRANTEE = " + grantee //$NON-NLS-1$
                + " AND ? LIKE TABLE_SCHEMA)")) { //$NON-NLS-1$
            statement.setString(1, database);
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && resultSet.getLong(1) > 0) {
                    return true;
                }
            }
        } catch (SQLException e) {
            log.debug("Can't read the EVENT privilege: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        // Privileges of roles are not listed for the user: with an active role the answer is unknown
        try (JDBCStatement statement = session.createStatement();
             JDBCResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM information_schema.ENABLED_ROLES")) { //$NON-NLS-1$
            if (resultSet.next() && resultSet.getLong(1) > 0) {
                return null;
            }
        } catch (SQLException e) {
            // Servers without roles
            log.debug("Can't read the enabled roles: " + e.getMessage()); //$NON-NLS-1$
        }
        return false;
    }

    //////////////////////////////////////////////////////////
    // Statements

    @NotNull
    static String eventName(@NotNull MySQLCatalog catalog, @NotNull String name) {
        MySQLDataSource dataSource = catalog.getDataSource();
        return DBUtils.getQuotedIdentifier(dataSource, catalog.getName()) + "." + DBUtils.getQuotedIdentifier(dataSource, name); //$NON-NLS-1$
    }

    @NotNull
    public static String enableStatement(@NotNull MySQLEvent event, boolean enable) {
        return "ALTER EVENT " + eventName(event.getCatalog(), event.getName()) + (enable ? " ENABLE" : " DISABLE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    public static String dropStatement(@NotNull MySQLEvent event) {
        return "DROP EVENT " + eventName(event.getCatalog(), event.getName()); //$NON-NLS-1$
    }

    /**
     * The statements as a script: what the preview shows and a query window opens.
     */
    @NotNull
    public static String formatScript(@NotNull List<String> statements) {
        StringBuilder script = new StringBuilder();
        for (String statement : statements) {
            script.append(statement).append(";\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    /**
     * Runs statements in an isolated connection, one after the other; stops at the first failure (event statements
     * commit on their own, so earlier ones stay applied).
     *
     * @param messages receives the server's warnings, or null
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull MySQLDataSource dataSource,
        @NotNull List<String> statements,
        @Nullable Consumer<String> messages
    ) throws DBException {
        DBCExecutionContext context = dataSource.getDefaultInstance().openIsolatedContext(monitor, "MySQL event changes", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) context.openSession(monitor, DBCExecutionPurpose.UTIL, "Apply MySQL event changes")) { //$NON-NLS-1$
            monitor.beginTask("Apply MySQL event changes", statements.size()); //$NON-NLS-1$
            for (String statement : statements) {
                if (monitor.isCanceled()) {
                    break;
                }
                monitor.subTask(statement);
                try (JDBCStatement dbStatement = session.createStatement()) {
                    dbStatement.execute(statement);
                    for (SQLWarning w = dbStatement.getWarnings(); w != null && messages != null; w = w.getNextWarning()) {
                        if (!CommonUtils.isEmpty(w.getMessage())) {
                            messages.accept(w.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    throw MySQLEventSchedulerException.wrap(e, dataSource, statement);
                }
                monitor.worked(1);
            }
        } finally {
            monitor.done();
            context.close();
        }
    }
}
