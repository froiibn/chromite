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
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
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
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.List;

/**
 * The "SQL Server Agent" node of a SQL Server connection: the agent service state and the folders below it (jobs, the job
 * activity monitor, alerts, operators, proxies, error logs).
 *
 * 설계: SSMS Object Explorer처럼 서버 노드 아래(보안 폴더 뒤)에 둔다. 탐색기 트리는 {@code plugin.xml}에서 데이터 원본의 {@code agent}
 * 항목 하나로 이 객체를 만들고({@link SQLServerDataSource#getAgent}), 그 아래 폴더의 목록은 이 객체의 게터가 msdb 도움말 프로시저
 * ({@code sp_help_job}, {@code sp_help_alert} …)로 읽는다. 프로시저는 SQLAgentUserRole 같은 msdb 역할의 권한 범위를 스스로 걸러 주므로
 * 시스템 테이블을 직접 읽지 않는다(msdb 역할에는 {@code sysjobs} SELECT 권한이 없다).
 * 폴더 노드를 새로 고치면 부모인 이 객체의 {@link #refreshObject}가 불려 상태와 모든 목록을 다시 읽는다(데이터 원본 전체를 다시 읽지 않는다).
 * <p>
 * 서비스 상태: {@code sys.dm_server_services}(VIEW SERVER STATE 권한)의 에이전트 행으로 판단한다. 그 권한이 없으면 "Agent XPs" 구성 값이
 * 꺼져 있을 때만 "중지됨"이고(에이전트가 시작할 때 켬), 켜져 있으면 "알 수 없음"이다: 에이전트가 멈춘 뒤에도 켜진 채 남기 때문이다
 * (2026-11-07 Linux SQL Server 2022에서 확인). 알 수 없을 때는 시작·중지를 막지 않고 서버의 응답에 맡긴다({@link #canRunJobs}).
 * Express 에디션과 Azure SQL Edge에는 에이전트가 없어 노드는 "사용할 수 없음"으로 남고,
 * 에이전트가 없는 Azure SQL Database·Synapse·Babelfish에는 노드를 만들지 않는다({@link #isAvailable}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgent implements DBSObject, DBPRefreshableObject, DBPStatefulObject {

    private static final Log log = Log.getLog(SQLServerAgent.class);

    /** SERVERPROPERTY('EngineEdition') values */
    private static final int EDITION_EXPRESS = 4;
    private static final int EDITION_AZURE_DATABASE = 5;
    private static final int EDITION_SYNAPSE = 6;
    private static final int EDITION_AZURE_EDGE = 9;
    private static final int EDITION_SYNAPSE_SERVERLESS = 11;

    public enum ServiceState {
        RUNNING,
        STOPPED,
        /** No agent in this edition (Express, Azure SQL Edge) */
        NOT_SUPPORTED,
        UNKNOWN;

        @NotNull
        public String getLabel() {
            return switch (this) {
                case RUNNING -> SQLServerAgentMessages.agent_state_running;
                case STOPPED -> SQLServerAgentMessages.agent_state_stopped;
                case NOT_SUPPORTED -> SQLServerAgentMessages.agent_state_not_supported;
                case UNKNOWN -> SQLServerAgentMessages.agent_state_unknown;
            };
        }
    }

    private final SQLServerDataSource dataSource;
    private volatile ServiceState state = ServiceState.UNKNOWN;
    private volatile int engineEdition = -1;
    @Nullable
    private List<SQLServerAgentJob> jobs;
    @Nullable
    private List<SQLServerAgentAlert> alerts;
    @Nullable
    private List<SQLServerAgentOperator> operators;
    @Nullable
    private List<SQLServerAgentProxy> proxies;
    @Nullable
    private List<SQLServerAgentErrorLog> errorLogs;
    private final SQLServerAgentActivityMonitor activityMonitor = new SQLServerAgentActivityMonitor(this);

    public SQLServerAgent(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * False for servers without SQL Server Agent at all (Azure SQL Database, Synapse, Babelfish): no node is shown.
     */
    public boolean isAvailable() {
        if (SQLServerUtils.isDriverBabelfish(dataSource.getContainer().getDriver()) || dataSource.isSynapseDatabase()) {
            return false;
        }
        return engineEdition != EDITION_AZURE_DATABASE && engineEdition != EDITION_SYNAPSE && engineEdition != EDITION_SYNAPSE_SERVERLESS;
    }

    /**
     * Reads the engine edition and the agent service state.
     */
    public void readState(@NotNull DBRProgressMonitor monitor) {
        if (SQLServerUtils.isDriverBabelfish(dataSource.getContainer().getDriver())) {
            state = ServiceState.NOT_SUPPORTED;
            return;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read SQL Server Agent state")) { //$NON-NLS-1$
            int agentXps = -1;
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery(
                     "SELECT CAST(SERVERPROPERTY('EngineEdition') AS int) AS engine_edition, " + //$NON-NLS-1$
                         "(SELECT CAST(value_in_use AS int) FROM sys.configurations WHERE name = N'Agent XPs') AS agent_xps")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    engineEdition = JDBCUtils.safeGetInt(resultSet, "engine_edition"); //$NON-NLS-1$
                    Integer value = JDBCUtils.safeGetInteger(resultSet, "agent_xps"); //$NON-NLS-1$
                    agentXps = value == null ? -1 : value;
                }
            }
            if (engineEdition == EDITION_EXPRESS || engineEdition == EDITION_AZURE_EDGE) {
                state = ServiceState.NOT_SUPPORTED;
                return;
            }
            String serviceStatus = null;
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery(
                     "SELECT status_desc FROM sys.dm_server_services WHERE servicename LIKE N'SQL Server Agent%'")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    serviceStatus = JDBCUtils.safeGetString(resultSet, "status_desc"); //$NON-NLS-1$
                }
            } catch (SQLException e) {
                // No VIEW SERVER STATE permission, or a platform without the view: fall back to Agent XPs
                log.debug("Can't read the agent service state: " + e.getMessage()); //$NON-NLS-1$
            }
            if (serviceStatus != null) {
                state = "Running".equalsIgnoreCase(serviceStatus) ? ServiceState.RUNNING : ServiceState.STOPPED; //$NON-NLS-1$
            } else if (agentXps == 0) {
                // The agent turns Agent XPs on when it starts: off means it has not run since the setting was reset
                state = ServiceState.STOPPED;
            } else {
                // Agent XPs stays on after the agent stops (seen 2026-11-07 on SQL Server 2022 for Linux): not a proof that it runs
                state = ServiceState.UNKNOWN;
            }
        } catch (Exception e) {
            log.debug("Can't read the SQL Server Agent state", e); //$NON-NLS-1$
            state = ServiceState.UNKNOWN;
        }
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return SQLServerAgentMessages.agent_node_name;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getServiceStateName() {
        return state.getLabel();
    }

    @NotNull
    public ServiceState getServiceState() {
        return state;
    }

    /**
     * False when the agent is known not to run (stopped, or no agent in this edition): jobs can't be started or stopped.
     * An unknown state (no permission to see the service) lets the server decide.
     */
    public boolean canRunJobs() {
        return state == ServiceState.RUNNING || state == ServiceState.UNKNOWN;
    }

    /**
     * False on editions without an agent: nothing can be changed there.
     */
    public boolean isSupported() {
        return state != ServiceState.NOT_SUPPORTED;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return dataSource.getContainer();
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return switch (state) {
            case RUNNING -> DBSObjectState.ACTIVE;
            case STOPPED, NOT_SUPPORTED -> DBSObjectState.INVALID;
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
            alerts = null;
            operators = null;
            proxies = null;
            errorLogs = null;
        }
        readState(monitor);
        return this;
    }

    //////////////////////////////////////////////////////////
    // Children (navigator tree)

    @Association
    @NotNull
    public synchronized List<SQLServerAgentJob> getJobs(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (jobs == null) {
            jobs = SQLServerAgentManager.readJobs(monitor, this);
        }
        return jobs;
    }

    /**
     * The Job Activity Monitor leaf (a list of one, as tree items are lists).
     */
    @Association
    @NotNull
    public List<SQLServerAgentActivityMonitor> getActivityMonitors() {
        return List.of(activityMonitor);
    }

    @Association
    @NotNull
    public synchronized List<SQLServerAgentAlert> getAlerts(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (alerts == null) {
            alerts = SQLServerAgentManager.readAlerts(monitor, this);
        }
        return alerts;
    }

    @Association
    @NotNull
    public synchronized List<SQLServerAgentOperator> getOperators(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (operators == null) {
            operators = SQLServerAgentManager.readOperators(monitor, this);
        }
        return operators;
    }

    @Association
    @NotNull
    public synchronized List<SQLServerAgentProxy> getProxies(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (proxies == null) {
            proxies = SQLServerAgentManager.readProxies(monitor, this);
        }
        return proxies;
    }

    @Association
    @NotNull
    public synchronized List<SQLServerAgentErrorLog> getErrorLogs(@NotNull DBRProgressMonitor monitor) {
        if (errorLogs == null) {
            errorLogs = SQLServerAgentManager.readErrorLogs(monitor, this);
        }
        return errorLogs;
    }

    @Override
    public String toString() {
        return getName() + " (" + CommonUtils.notEmpty(dataSource.getContainer().getName()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
