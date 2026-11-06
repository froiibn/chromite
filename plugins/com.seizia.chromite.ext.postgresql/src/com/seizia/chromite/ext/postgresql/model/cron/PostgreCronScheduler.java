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

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.List;

/**
 * The "pg_cron" node of a PostgreSQL database: whether the extension is installed and usable, its jobs and run history.
 *
 * 설계: SQL Server 에이전트 노드({@code SQLServerAgent})처럼 데이터베이스 노드 아래 항목 하나다({@link PostgreDatabase#getCronScheduler}).
 * pg_cron은 {@code cron.database_name} 데이터베이스 한 곳에만 설치되므로 노드는 모든 데이터베이스에 두고 상태를 보조 글자로 보인다:
 * 설치되지 않았으면 "설치되지 않음"이고 아래 폴더가 없다(확장은 설치하지 않는다, 서버 설정과 재시작이 필요하다).
 * <ul>
 *     <li>권한: cron 스키마의 USAGE가 없으면 "권한 없음"이고 작업을 읽지 않는다. {@code cron.job}은 행 보안 정책으로 자기 작업만
 *     보이므로(슈퍼유저는 모두) 목록은 따로 거르지 않는다.</li>
 *     <li>버전 차이: 확장 버전마다 함수와 열이 달라(작업 이름 1.3, {@code alter_job}·{@code job_run_details}·{@code active} 1.4,
 *     {@code schedule_in_database} 1.4) 카탈로그에서 실제로 있는지 읽어 명령을 켜고 끈다.</li>
 *     <li>스케줄러 상태: 작업을 실행하는 백그라운드 작업자("pg_cron launcher")가 {@code pg_stat_activity}에 있으면 실행 중이다.
 *     {@code shared_preload_libraries}에 없거나 멈추면 작업이 돌지 않으므로 "중지됨"으로 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronScheduler implements DBSObject, DBPRefreshableObject, DBPStatefulObject {

    private static final Log log = Log.getLog(PostgreCronScheduler.class);

    public enum State {
        INSTALLED,
        NOT_INSTALLED,
        /** Installed, but the login has no USAGE on the cron schema */
        NO_ACCESS,
        UNKNOWN
    }

    private final PostgreDatabase database;
    private final List<PostgreCronHistory> history;
    private volatile State state = State.UNKNOWN;
    @Nullable
    private volatile String version;
    @Nullable
    private volatile Boolean launcherRunning;
    private volatile String currentUser = ""; //$NON-NLS-1$
    private volatile boolean superuser;
    private volatile boolean hasJobName;
    private volatile boolean hasActive;
    private volatile boolean hasAlterJob;
    private volatile boolean hasRunDetails;
    private volatile boolean hasScheduleInDatabase;
    @Nullable
    private List<PostgreCronJob> jobs;

    public PostgreCronScheduler(@NotNull PostgreDatabase database) {
        this.database = database;
        this.history = List.of(new PostgreCronHistory(this));
    }

    /**
     * Reads whether pg_cron is installed in the database, what the login may do, and what the installed version offers.
     */
    public void readState(@NotNull DBRProgressMonitor monitor) {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, database, "Read pg_cron state")) { //$NON-NLS-1$
            String installedVersion = null;
            boolean installed = false;
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery("SELECT extversion FROM pg_catalog.pg_extension WHERE extname = 'pg_cron'")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    installed = true;
                    installedVersion = resultSet.getString(1);
                }
            }
            version = installedVersion;
            if (!installed) {
                state = State.NOT_INSTALLED;
                return;
            }
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery(
                     "SELECT has_schema_privilege('cron', 'USAGE') AS cron_usage, current_user AS login, " + //$NON-NLS-1$
                         "(SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) AS superuser, " + //$NON-NLS-1$
                         "EXISTS (SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " + //$NON-NLS-1$
                         "WHERE n.nspname = 'cron' AND p.proname = 'alter_job') AS has_alter_job, " + //$NON-NLS-1$
                         "EXISTS (SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " + //$NON-NLS-1$
                         "WHERE n.nspname = 'cron' AND p.proname = 'schedule_in_database') AS has_schedule_in_database, " + //$NON-NLS-1$
                         "to_regclass('cron.job_run_details') IS NOT NULL AS has_run_details, " + //$NON-NLS-1$
                         "EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid = to_regclass('cron.job') AND attname = 'jobname' " + //$NON-NLS-1$
                         "AND NOT attisdropped) AS has_job_name, " + //$NON-NLS-1$
                         "EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid = to_regclass('cron.job') AND attname = 'active' " + //$NON-NLS-1$
                         "AND NOT attisdropped) AS has_active")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    boolean usage = JDBCUtils.safeGetBoolean(resultSet, "cron_usage"); //$NON-NLS-1$
                    currentUser = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "login")); //$NON-NLS-1$
                    superuser = JDBCUtils.safeGetBoolean(resultSet, "superuser"); //$NON-NLS-1$
                    hasAlterJob = JDBCUtils.safeGetBoolean(resultSet, "has_alter_job"); //$NON-NLS-1$
                    hasScheduleInDatabase = JDBCUtils.safeGetBoolean(resultSet, "has_schedule_in_database"); //$NON-NLS-1$
                    hasRunDetails = JDBCUtils.safeGetBoolean(resultSet, "has_run_details"); //$NON-NLS-1$
                    hasJobName = JDBCUtils.safeGetBoolean(resultSet, "has_job_name"); //$NON-NLS-1$
                    hasActive = JDBCUtils.safeGetBoolean(resultSet, "has_active"); //$NON-NLS-1$
                    state = usage || superuser ? State.INSTALLED : State.NO_ACCESS;
                }
            }
            launcherRunning = readLauncherRunning(session);
        } catch (Exception e) {
            log.debug("Can't read the pg_cron state", e); //$NON-NLS-1$
            state = State.UNKNOWN;
        }
    }

    @Nullable
    private static Boolean readLauncherRunning(@NotNull JDBCSession session) {
        try (JDBCStatement statement = session.createStatement();
             JDBCResultSet resultSet = statement.executeQuery(
                 "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity WHERE backend_type = 'pg_cron launcher')")) { //$NON-NLS-1$
            return resultSet.next() ? resultSet.getBoolean(1) : null;
        } catch (SQLException e) {
            // Servers before PostgreSQL 10 have no backend_type
            log.debug("Can't read the pg_cron launcher state: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return PostgreCronMessages.node_name;
    }

    /**
     * The state as the node's secondary text: the version when usable, otherwise why not.
     */
    @NotNull
    @Property(viewable = true, order = 2)
    public String getStateName() {
        return switch (state) {
            case INSTALLED -> NLS.bind(PostgreCronMessages.state_installed, CommonUtils.notEmpty(version));
            case NOT_INSTALLED -> PostgreCronMessages.state_not_installed;
            case NO_ACCESS -> PostgreCronMessages.state_no_access;
            case UNKNOWN -> PostgreCronMessages.state_unknown;
        };
    }

    /**
     * Whether the pg_cron background worker runs jobs.
     */
    @NotNull
    @Property(viewable = true, order = 3)
    public String getLauncherStateName() {
        Boolean running = launcherRunning;
        return running == null ? PostgreCronMessages.launcher_unknown
            : running ? PostgreCronMessages.launcher_running : PostgreCronMessages.launcher_stopped;
    }

    @NotNull
    public State getState() {
        return state;
    }

    /**
     * Installed and usable by the login (visibleIf of the folders below the node).
     */
    public boolean isInstalled() {
        return state == State.INSTALLED;
    }

    /**
     * False when the launcher is known not to run (jobs are scheduled but nothing executes them).
     */
    public boolean isLauncherStopped() {
        return Boolean.FALSE.equals(launcherRunning);
    }

    @Nullable
    public String getVersion() {
        return version;
    }

    @NotNull
    public String getCurrentUser() {
        return currentUser;
    }

    public boolean isSuperuser() {
        return superuser;
    }

    public boolean hasJobName() {
        return hasJobName;
    }

    public boolean hasActive() {
        return hasActive;
    }

    public boolean hasAlterJob() {
        return hasAlterJob;
    }

    public boolean hasRunDetails() {
        return hasRunDetails;
    }

    public boolean hasScheduleInDatabase() {
        return hasScheduleInDatabase;
    }

    @NotNull
    public PostgreDatabase getDatabase() {
        return database;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public PostgreDatabase getParentObject() {
        return database;
    }

    @NotNull
    @Override
    public PostgreDataSource getDataSource() {
        return database.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return switch (state) {
            case INSTALLED -> isLauncherStopped() ? DBSObjectState.INVALID : DBSObjectState.ACTIVE;
            case NOT_INSTALLED, NO_ACCESS -> DBSObjectState.INVALID;
            case UNKNOWN -> DBSObjectState.UNKNOWN;
        };
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        readState(monitor);
    }

    @NotNull
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) {
        synchronized (this) {
            jobs = null;
        }
        readState(monitor);
        return this;
    }

    //////////////////////////////////////////////////////////
    // Children (navigator tree)

    @Association
    @NotNull
    public synchronized List<PostgreCronJob> getJobs(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isInstalled()) {
            return List.of();
        }
        if (jobs == null) {
            jobs = PostgreCronManager.readJobs(monitor, this);
        }
        return jobs;
    }

    /**
     * The Job Run Details leaf (a list of one, as tree items are lists); empty on versions without run details.
     */
    @Association
    @NotNull
    public List<PostgreCronHistory> getRunDetails() {
        return isInstalled() && hasRunDetails ? history : List.of();
    }

    @Override
    public String toString() {
        return getName() + " (" + database.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
