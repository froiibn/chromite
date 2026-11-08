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
package com.seizia.chromite.model.admin.objects;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.altibase.model.AltibaseMaterializedView;
import com.seizia.chromite.ext.altibase.model.AltibasePackage;
import com.seizia.chromite.ext.altibase.model.AltibaseProcedureStandAlone;
import com.seizia.chromite.ext.altibase.model.AltibaseReplication;
import com.seizia.chromite.ext.altibase.model.AltibaseTable;
import com.seizia.chromite.ext.altibase.model.AltibaseView;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolMViewRefresh;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolPackageCompile;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolProcedureCompile;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolReplicationStart;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolReplicationStop;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolTableTruncate;
import com.seizia.chromite.ext.altibase.tasks.AltibaseToolViewCompile;
import com.seizia.chromite.ext.mssql.model.SQLServerTable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableTrigger;
import com.seizia.chromite.ext.mssql.tasks.SQLServerToolTableTriggerDisable;
import com.seizia.chromite.ext.mssql.tasks.SQLServerToolTableTriggerEnable;
import com.seizia.chromite.ext.mysql.model.MySQLTable;
import com.seizia.chromite.ext.mysql.tasks.MySQLToolTableTruncate;
import com.seizia.chromite.ext.oracle.model.OracleDataType;
import com.seizia.chromite.ext.oracle.model.OracleMaterializedView;
import com.seizia.chromite.ext.oracle.model.OracleObjectStatus;
import com.seizia.chromite.ext.oracle.model.OraclePackage;
import com.seizia.chromite.ext.oracle.model.OracleProcedureStandalone;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleTable;
import com.seizia.chromite.ext.oracle.model.OracleTableTrigger;
import com.seizia.chromite.ext.oracle.model.OracleTrigger;
import com.seizia.chromite.ext.oracle.model.OracleView;
import com.seizia.chromite.ext.oracle.model.source.OracleSourceObject;
import com.seizia.chromite.ext.oracle.tasks.OracleToolMViewRefresh;
import com.seizia.chromite.ext.oracle.tasks.OracleToolTableTriggerDisable;
import com.seizia.chromite.ext.oracle.tasks.OracleToolTableTriggerEnable;
import com.seizia.chromite.ext.oracle.tasks.OracleToolTableTruncate;
import com.seizia.chromite.ext.postgresql.model.PostgreMaterializedView;
import com.seizia.chromite.ext.postgresql.model.PostgreTable;
import com.seizia.chromite.ext.postgresql.model.PostgreTableForeign;
import com.seizia.chromite.ext.postgresql.model.PostgreTriggerBase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreToolMViewRefresh;
import com.seizia.chromite.ext.postgresql.tasks.PostgreToolTableTriggerDisable;
import com.seizia.chromite.ext.postgresql.tasks.PostgreToolTableTriggerEnable;
import com.seizia.chromite.ext.postgresql.tasks.PostgreToolTableTruncate;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPEvent;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistActionComment;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.task.SQLToolExecuteHandler;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Object Explorer maintenance commands on single objects: TRUNCATE a table, enable or disable a trigger, refresh a
 * materialized view, compile stored code.
 *
 * 설계: SWT는 이 명령들을 작업(task) 확장점의 도구로 실행했다({@code pgToolTableTruncate}, {@code oracleToolTriggerEnable} 등, 도구 대화상자
 * {@code SQLToolTaskWizard}). 문 모양은 모델 번들의 도구 처리기({@link SQLToolExecuteHandler#generateObjectQueries})에 있으므로 그대로 불러
 * 스크립트를 만든다(같은 SQL, 새 SQL 문법을 만들지 않음). 실행은 도구 처리기의 {@code executeTask} 대신 여기서 한다: 처리기는 작업 실행기와
 * 진행 기록(작업 로그)을 전제로 하고, 연결의 기본 실행 문맥에서 실행해 사용자의 열린 트랜잭션과 섞일 수 있기 때문이다.
 * <ul>
 *     <li>지원: TRUNCATE — PostgreSQL({@link PostgreToolTableTruncate}, 외부 테이블 제외), MySQL/MariaDB({@link MySQLToolTableTruncate}),
 *     Oracle({@link OracleToolTableTruncate}), Altibase({@link AltibaseToolTableTruncate}), SQL Server(모델에 도구가 없어
 *     {@code TRUNCATE TABLE <이름>}, SWT도 SQL Server TRUNCATE 도구는 없었다).
 *     트리거 사용/사용 안 함 — PostgreSQL(테이블·이벤트 트리거), SQL Server, Oracle의 도구. 구체화된 뷰 새로 고침 — PostgreSQL, Oracle,
 *     Altibase의 도구.</li>
 *     <li>컴파일 — Oracle은 SWT Compile 명령({@code CompileHandler})과 같이 모델의 {@link OracleSourceObject#getCompileActions}
 *     ({@code ALTER … COMPILE}, 패키지는 선언과 본문), Altibase는 SWT 도구({@link AltibaseToolViewCompile},
 *     {@link AltibaseToolProcedureCompile}, {@link AltibaseToolPackageCompile})의 문장이다. Oracle은 컴파일 오류가 예외가 아니라 경고로
 *     오므로 실행 뒤 {@link #readCompileErrors}로 {@code ALL_ERRORS}를 읽는다. Altibase는 컴파일 오류가 예외로 온다(7.3에서 확인).
 *     실행 뒤 {@link #refreshObjectStates}가 객체의 유효 상태를 다시 읽고 바뀌었으면 알린다(SWT {@code CompileHandler.compileUnit}과 같음).</li>
 *     <li>Altibase 복제 시작·중지·동기화 — 시작·중지는 SWT 도구({@link AltibaseToolReplicationStart}, {@link AltibaseToolReplicationStop})의
 *     {@code ALTER REPLICATION r START|STOP}이다. 동기화는 모델에 도구가 없어 같은 모양으로 {@code ALTER REPLICATION r SYNC}를 쓴다
 *     (Altibase 문서: 복제 대상 테이블의 모든 행을 원격 서버로 보낸 뒤 복제를 시작한다). 시작 여부는 모델이 읽은
 *     {@link AltibaseReplication#getIsStarted()}다. 모델의 {@code AltibaseReplication.refreshObjectState}는 복제가 아니라 프로시저
 *     카탈로그를 읽으므로(범위 밖 모델 버그) 상태 다시 읽기는 노드 새로 고침({@code refreshObject})에 맡긴다.</li>
 *     <li>도구 설정은 처리기의 기본값을 쓴다. 다만 PostgreSQL 구체화된 뷰 새로 고침의 기본값은 {@code WITH NO DATA}(뷰를 비움)라
 *     새로 고침의 뜻에 맞게 {@code WITH DATA}로 둔다.</li>
 *     <li>실행은 보안 편집기({@code SecurityDialect#execute})처럼 대상 인스턴스의 격리된 실행 문맥에서 한다. 사용자 쿼리 창의 세션과 트랜잭션을
 *     건드리지 않는다. 연결이 수동 커밋이면 끝에서 커밋하고 실패하면 되돌린다(화면이 실행 전에 열린 트랜잭션을 물어 끝낸다).</li>
 *     <li>암시적 커밋 여부는 JDBC {@code DatabaseMetaData#dataDefinitionCausesTransactionCommit}로 판단한다(MySQL·MariaDB·Oracle은 참,
 *     PostgreSQL·SQL Server는 거짓). Object Explorer 삭제 미리 보기({@code ExplorerObjectCommands})와 같은 판단이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: TRUNCATE, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침 (docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-08 Altibase TRUNCATE·구체화된 뷰 새로 고침(A5), Oracle·Altibase 컴파일(O11, A6), 컴파일 오류 읽기, 상태 다시 읽기
 *   (docs/chromite/swing-routines.md)
 * - 2026-11-08 Altibase 복제 시작·중지·동기화(SWT 감사 D-A7) (docs/chromite/altibase.md "복제 시작·중지·동기화")
 */
public final class ObjectMaintenanceActions {

    private static final Log log = Log.getLog(ObjectMaintenanceActions.class);

    /**
     * Commands of this class.
     */
    public enum Action {
        TRUNCATE,
        ENABLE_TRIGGER,
        DISABLE_TRIGGER,
        REFRESH_MATERIALIZED_VIEW,
        COMPILE,
        START_REPLICATION,
        STOP_REPLICATION,
        SYNC_REPLICATION
    }

    /**
     * One statement of a script and the object it works on.
     */
    public record Statement(@NotNull DBSObject object, @NotNull String sql) {
    }

    private ObjectMaintenanceActions() {
    }

    /**
     * Whether the command applies to the object (its type only; no database access).
     */
    public static boolean supports(@NotNull Action action, @Nullable DBSObject object) {
        if (object == null || !object.isPersisted()) {
            return false;
        }
        return switch (action) {
            case TRUNCATE -> object instanceof PostgreTable && !(object instanceof PostgreTableForeign)
                || object instanceof MySQLTable
                || object instanceof OracleTable
                || object instanceof AltibaseTable
                || object instanceof SQLServerTable;
            case ENABLE_TRIGGER, DISABLE_TRIGGER -> object instanceof PostgreTriggerBase
                || object instanceof SQLServerTableTrigger
                || object instanceof OracleTableTrigger;
            case REFRESH_MATERIALIZED_VIEW -> object instanceof PostgreMaterializedView || object instanceof OracleMaterializedView
                || object instanceof AltibaseMaterializedView;
            case COMPILE -> object instanceof OracleProcedureStandalone
                || object instanceof OraclePackage
                || object instanceof OracleView
                || object instanceof OracleMaterializedView
                || object instanceof OracleTrigger<?>
                // Predefined types (NUMBER, …) have no compile action
                || object instanceof OracleDataType type && !type.isPredefined()
                || object instanceof AltibaseView
                || object instanceof AltibaseProcedureStandAlone
                || object instanceof AltibasePackage;
            case START_REPLICATION, STOP_REPLICATION, SYNC_REPLICATION -> object instanceof AltibaseReplication;
        };
    }

    /**
     * Whether an Altibase replication was started when the model read it, or null if the object is not a replication.
     */
    @Nullable
    public static Boolean isReplicationStarted(@NotNull DBSObject object) {
        return object instanceof AltibaseReplication replication ? replication.getIsStarted() : null;
    }

    /**
     * Reads the state (valid or invalid) of compiled objects again after {@link #execute}, and tells the navigator when it
     * changed so the tree shows it. Objects without a state are skipped; errors only go to the log.
     */
    public static void refreshObjectStates(@NotNull DBRProgressMonitor monitor, @NotNull List<? extends DBSObject> objects) {
        for (DBSObject object : objects) {
            if (!(object instanceof DBPStatefulObject stateful)) {
                continue;
            }
            try {
                DBSObjectState oldState = stateful.getObjectState();
                stateful.refreshObjectState(monitor);
                if (stateful.getObjectState() != oldState) {
                    object.getDataSource().getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, object));
                }
            } catch (DBException | RuntimeException e) {
                log.debug("Cannot read the state of " + object.getName(), e); //$NON-NLS-1$
            }
        }
    }

    /**
     * The Oracle compilation errors ({@code ALL_ERRORS}) of the objects after a compile; empty for other databases, which
     * report compile errors as statement errors.
     */
    @NotNull
    public static List<OracleCompileErrors.CompileError> readCompileErrors(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<? extends DBSObject> objects
    ) throws DBException {
        List<OracleCompileErrors.CompileError> errors = new ArrayList<>();
        for (DBSObject object : objects) {
            List<String> types = OracleCompileErrors.getErrorTypes(object);
            // The owner; a table trigger's parent is its table
            OracleSchema schema = DBUtils.getParentOfType(OracleSchema.class, object);
            if (types.isEmpty() || schema == null) {
                continue;
            }
            try (JDBCSession session = DBUtils.openMetaSession(monitor, object, "Read compilation errors")) { //$NON-NLS-1$
                errors.addAll(OracleCompileErrors.read(session, schema.getName(), object.getName(), types));
            } catch (SQLException e) {
                throw new DBDatabaseException(e, object.getDataSource());
            }
        }
        return errors;
    }

    /**
     * Whether a trigger is enabled as the model read it, or null if unknown (not a trigger, or no state read).
     */
    @Nullable
    public static Boolean isTriggerEnabled(@NotNull DBSObject object) {
        if (object instanceof PostgreTriggerBase trigger) {
            // pg_trigger.tgenabled / pg_event_trigger.evtenabled: D = disabled; O, R, A = enabled
            String state = trigger.getEnabledState();
            return CommonUtils.isEmpty(state) ? null : !"D".equals(state); //$NON-NLS-1$
        } else if (object instanceof SQLServerTableTrigger trigger) {
            return !trigger.isDisabled();
        } else if (object instanceof OracleTableTrigger trigger) {
            OracleObjectStatus status = trigger.getStatus();
            return status == OracleObjectStatus.ENABLED ? Boolean.TRUE : status == OracleObjectStatus.DISABLED ? Boolean.FALSE : null;
        }
        return null;
    }

    /**
     * The statements of the command for the objects, in order, as the model's tools write them.
     */
    @NotNull
    public static List<Statement> generateScript(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Action action,
        @NotNull List<? extends DBSObject> objects
    ) throws DBException {
        List<Statement> statements = new ArrayList<>();
        for (DBSObject object : objects) {
            if (!supports(action, object)) {
                throw new DBException("The command is not supported for " + DBUtils.getObjectFullName(object, DBPEvaluationContext.UI)); //$NON-NLS-1$
            }
            List<DBEPersistAction> actions = new ArrayList<>();
            // The tools' signature takes a session; only the PostgreSQL TRUNCATE tool uses it, and only while a task runs.
            // Oracle compile actions use its monitor (a package reads whether it has a body)
            try (DBCSession session = DBUtils.openMetaSession(monitor, object, "Generate maintenance script")) { //$NON-NLS-1$
                addActions(session, action, object, actions);
            }
            for (DBEPersistAction persistAction : actions) {
                if (!(persistAction instanceof SQLDatabasePersistActionComment) && !CommonUtils.isEmpty(persistAction.getScript())) {
                    statements.add(new Statement(object, persistAction.getScript()));
                }
            }
        }
        return statements;
    }

    /**
     * True when the database commits a DDL statement (TRUNCATE included) at once: it cannot be rolled back.
     */
    public static boolean isDdlCommitting(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, object, "Read DDL transaction behavior")) { //$NON-NLS-1$
            return session.getMetaData().dataDefinitionCausesTransactionCommit();
        } catch (SQLException | DBException | RuntimeException e) {
            log.debug("Cannot read the DDL transaction behavior", e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Runs the statements in an isolated connection of each object's instance (committed at the end when the connection
     * is not in auto-commit mode, rolled back when a statement fails).
     *
     * @throws ExecutionException with the failed statement
     */
    public static void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<Statement> statements) throws DBException {
        monitor.beginTask("Execute maintenance script", statements.size()); //$NON-NLS-1$
        try {
            int index = 0;
            while (index < statements.size() && !monitor.isCanceled()) {
                // Statements of one instance (PostgreSQL: one database) run in one connection
                DBSInstance instance = getInstance(statements.get(index).object());
                int end = index;
                while (end < statements.size() && getInstance(statements.get(end).object()) == instance) {
                    end++;
                }
                executeInInstance(monitor, instance, statements.subList(index, end));
                index = end;
            }
        } finally {
            monitor.done();
        }
    }

    /**
     * A failed statement of {@link #execute}; the message is the database error.
     */
    public static final class ExecutionException extends DBDatabaseException {
        @NotNull
        private final Statement statement;

        ExecutionException(@NotNull Statement statement, @NotNull Throwable cause, @NotNull DBPDataSource dataSource) {
            super(cause.getMessage(), cause, dataSource);
            this.statement = statement;
        }

        @NotNull
        public Statement getStatement() {
            return statement;
        }
    }

    private static void executeInInstance(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSInstance instance,
        @NotNull List<Statement> statements
    ) throws DBException {
        DBCExecutionContext executionContext = instance.openIsolatedContext(monitor, "Object maintenance", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) executionContext.openSession(monitor, DBCExecutionPurpose.UTIL, "Execute maintenance script")) { //$NON-NLS-1$
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(executionContext);
            boolean commit = txnManager != null && txnManager.isSupportsTransactions() && !txnManager.isAutoCommit();
            Statement current = null;
            try {
                for (Statement statement : statements) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    current = statement;
                    monitor.subTask(statement.sql());
                    JDBCUtils.executeStatement(session, statement.sql());
                    monitor.worked(1);
                }
                if (commit) {
                    txnManager.commit(session);
                }
            } catch (SQLException e) {
                if (commit) {
                    try {
                        txnManager.rollback(session, null);
                    } catch (DBCException rollbackError) {
                        log.debug("Rollback after a failed maintenance statement failed", rollbackError); //$NON-NLS-1$
                    }
                }
                throw current == null ? new DBDatabaseException(e, instance.getDataSource())
                    : new ExecutionException(current, e, instance.getDataSource());
            }
        } finally {
            executionContext.close();
        }
    }

    @NotNull
    private static DBSInstance getInstance(@NotNull DBSObject object) throws DBException {
        DBSInstance instance = DBUtils.getObjectOwnerInstance(object);
        if (instance == null) {
            throw new DBException("Not connected to " + object.getDataSource().getContainer().getName()); //$NON-NLS-1$
        }
        return instance;
    }

    private static void addActions(
        @NotNull DBCSession session,
        @NotNull Action action,
        @NotNull DBSObject object,
        @NotNull List<DBEPersistAction> actions
    ) throws DBException {
        switch (action) {
            case TRUNCATE -> {
                if (object instanceof PostgreTable table) {
                    generate(new PostgreToolTableTruncate(), null, session, table, actions);
                } else if (object instanceof MySQLTable table) {
                    generate(new MySQLToolTableTruncate(), null, session, table, actions);
                } else if (object instanceof OracleTable table) {
                    generate(new OracleToolTableTruncate(), null, session, table, actions);
                } else if (object instanceof AltibaseTable table) {
                    generate(new AltibaseToolTableTruncate(), null, session, table, actions);
                } else if (object instanceof SQLServerTable table) {
                    actions.add(new SQLDatabasePersistAction(
                        "TRUNCATE TABLE " + DBUtils.getObjectFullName(table, DBPEvaluationContext.DDL))); //$NON-NLS-1$
                }
            }
            case ENABLE_TRIGGER -> {
                if (object instanceof PostgreTriggerBase trigger) {
                    generate(new PostgreToolTableTriggerEnable(), null, session, trigger, actions);
                } else if (object instanceof SQLServerTableTrigger trigger) {
                    generate(new SQLServerToolTableTriggerEnable(), null, session, trigger, actions);
                } else if (object instanceof OracleTableTrigger trigger) {
                    generate(new OracleToolTableTriggerEnable(), null, session, trigger, actions);
                }
            }
            case DISABLE_TRIGGER -> {
                if (object instanceof PostgreTriggerBase trigger) {
                    generate(new PostgreToolTableTriggerDisable(), null, session, trigger, actions);
                } else if (object instanceof SQLServerTableTrigger trigger) {
                    generate(new SQLServerToolTableTriggerDisable(), null, session, trigger, actions);
                } else if (object instanceof OracleTableTrigger trigger) {
                    generate(new OracleToolTableTriggerDisable(), null, session, trigger, actions);
                }
            }
            case REFRESH_MATERIALIZED_VIEW -> {
                if (object instanceof PostgreMaterializedView view) {
                    // The tool's default is WITH NO DATA, which empties the view
                    generate(new PostgreToolMViewRefresh(), settings -> settings.setWithData(true), session, view, actions);
                } else if (object instanceof OracleMaterializedView view) {
                    generate(new OracleToolMViewRefresh(), null, session, view, actions);
                } else if (object instanceof AltibaseMaterializedView view) {
                    generate(new AltibaseToolMViewRefresh(), null, session, view, actions);
                }
            }
            case COMPILE -> {
                if (object instanceof OracleSourceObject sourceObject) {
                    actions.addAll(List.of(sourceObject.getCompileActions(session.getProgressMonitor())));
                } else if (object instanceof AltibaseView view) {
                    generate(new AltibaseToolViewCompile(), null, session, view, actions);
                } else if (object instanceof AltibaseProcedureStandAlone procedure) {
                    generate(new AltibaseToolProcedureCompile(), null, session, procedure, actions);
                } else if (object instanceof AltibasePackage altibasePackage) {
                    generate(new AltibaseToolPackageCompile(), null, session, altibasePackage, actions);
                }
            }
            case START_REPLICATION -> {
                if (object instanceof AltibaseReplication replication) {
                    generate(new AltibaseToolReplicationStart(), null, session, replication, actions);
                }
            }
            case STOP_REPLICATION -> {
                if (object instanceof AltibaseReplication replication) {
                    generate(new AltibaseToolReplicationStop(), null, session, replication, actions);
                }
            }
            case SYNC_REPLICATION -> {
                if (object instanceof AltibaseReplication replication) {
                    // Written like the START and STOP tools (the name as the catalog holds it)
                    actions.add(new SQLDatabasePersistAction(AltibaseConstants.ALTER_REPL_PREFIX + " " + replication.getName() + " SYNC")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private static <T extends DBSObject, S extends SQLToolExecuteSettings<T>> void generate(
        @NotNull SQLToolExecuteHandler<T, S> tool,
        @Nullable Consumer<S> configure,
        @NotNull DBCSession session,
        @NotNull T object,
        @NotNull List<DBEPersistAction> actions
    ) throws DBCException {
        S settings = tool.createToolSettings();
        if (configure != null) {
            configure.accept(settings);
        }
        tool.generateObjectQueries(session, settings, actions, object);
    }
}
