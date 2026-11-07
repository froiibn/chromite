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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupFile;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupRestore;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupSet;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupSettings;
import com.seizia.chromite.model.admin.mssql.SQLServerRestoreSettings;
import com.seizia.chromite.model.ai.agent.AIPendingTransaction;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationAnswer;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.ai.agent.AIToolTransactionGuard;
import com.seizia.chromite.model.ai.tools.internal.AIToolsMessages;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Administration tools: end a session (DANGEROUS), SQL Server backup (WRITE) and restore (DANGEROUS).
 *
 * 설계:
 * <ul>
 *     <li>세션 끝내기: 세션 번호를 정수(Oracle은 {@code sid,serial#})로 검사한 뒤 DB 명령을 만든다. 대상 세션에 열린 트랜잭션이 있으면
 *     끝내는 순간 서버가 롤백하므로, 실행기가 자동 실행 설정과 상관없이 Roll back / Cancel을 묻는다(커밋은 남의 세션이라 불가).</li>
 *     <li>백업·복원은 Swing 백업·복원 대화상자와 같은 모델({@code model.admin}의 {@link SQLServerBackupSettings},
 *     {@link SQLServerRestoreSettings}, {@link SQLServerBackupRestore#execute})을 써서 같은 T-SQL을 만든다(docs/chromite/swing-backup-restore.md).
 *     확인 요청에 만든 스크립트 전체를 보인다. PostgreSQL·MySQL 네이티브 도구 백업은 1b단계.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 확인 대화상자의 열린 트랜잭션 소유자·설명을 AIToolsMessages로 옮김(화면 언어를 따름)
 */
public class AdminTools extends AgentTool implements AIToolTransactionGuard, AIToolPreviewProvider {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,10}");
    private static final Pattern ORACLE_SESSION = Pattern.compile("\\d{1,10},\\d{1,10}");

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        return switch (toolId) {
            case "killSession" -> kill(context, container, args);
            case "backupDatabase" -> backup(context, container, args, true);
            case "restoreDatabase" -> restore(context, container, args, true);
            default -> throw unknownTool(toolId);
        };
    }

    // ------------------------------------------------------------------ kill

    @NotNull
    private static String killStatement(@NotNull DbFamily family, @NotNull String session) throws DBException {
        String s = session.trim();
        return switch (family) {
            case SQLSERVER, MYSQL, MARIADB -> {
                checkNumber(s);
                yield "KILL " + s;
            }
            case POSTGRESQL -> {
                checkNumber(s);
                yield "SELECT pg_terminate_backend(" + s + ")";
            }
            case ORACLE -> {
                if (!ORACLE_SESSION.matcher(s).matches()) {
                    throw new AIToolArgumentException("Oracle sessions are given as 'sid,serial#' (see listSessions)");
                }
                yield "ALTER SYSTEM KILL SESSION '" + s + "' IMMEDIATE";
            }
            default -> throw new DBException("killSession is not available for this database");
        };
    }

    private static void checkNumber(@NotNull String session) throws AIToolArgumentException {
        if (!NUMBER.matcher(session).matches()) {
            throw new AIToolArgumentException("Argument 'session' must be a session number (see listSessions)");
        }
    }

    @NotNull
    private static JsonElement kill(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String sql = killStatement(DbFamily.of(container), AgentArgs.require(args, "session"));
        JsonObject result = AgentSql.run(context.getMonitor(), container, null, AgentSql.Mode.NATIVE,
            (connection, runner) -> runner.execute(connection, sql, null, AgentLimits.of(Map.of(), 1), true));
        result.addProperty("statement", sql);
        return result;
    }

    // ------------------------------------------------------------------ backup / restore (SQL Server)

    @NotNull
    private static SQLServerDataSource sqlServer(@NotNull DBPDataSourceContainer container) throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        if (!(dataSource instanceof SQLServerDataSource sqlServer)) {
            throw new DBException("Backup and restore tools support SQL Server in this phase (PostgreSQL/MySQL native tools: phase 1b)");
        }
        return sqlServer;
    }

    @NotNull
    private static List<String> backupBatches(@NotNull Map<String, Object> args) throws AIToolArgumentException {
        SQLServerBackupSettings settings = new SQLServerBackupSettings(AgentArgs.require(args, "database"));
        settings.setDestination(AgentArgs.require(args, "file"));
        String type = AgentArgs.getString(args, "type");
        if (type != null) {
            try {
                settings.setBackupType(SQLServerBackupSettings.BackupType.valueOf(type.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new AIToolArgumentException("Argument 'type' must be FULL, DIFFERENTIAL or LOG");
            }
        }
        settings.setCopyOnly(AgentArgs.getBoolean(args, "copyOnly", true));
        // SSMS's default backup set name
        settings.setBackupSetName(settings.getDatabaseName() + "-" +
            settings.getBackupType().name().charAt(0) + settings.getBackupType().name().substring(1).toLowerCase(Locale.ROOT) +
            " Database Backup");
        settings.setChecksum(true);
        String problem = settings.validate();
        if (problem != null) {
            throw new AIToolArgumentException(problem);
        }
        return settings.buildBatches();
    }

    @NotNull
    private static JsonElement backup(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull Map<String, Object> args,
        boolean execute
    ) throws DBException {
        SQLServerDataSource dataSource = sqlServer(container);
        List<String> batches = backupBatches(args);
        return runBatches(context, dataSource, batches, execute);
    }

    @NotNull
    private static JsonElement restore(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull Map<String, Object> args,
        boolean execute
    ) throws DBException {
        SQLServerDataSource dataSource = sqlServer(container);
        List<String> batches = restoreBatches(context, dataSource, args);
        return runBatches(context, dataSource, batches, execute);
    }

    @NotNull
    private static List<String> restoreBatches(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerDataSource dataSource,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String path = AgentArgs.require(args, "file");
        String target = AgentArgs.require(args, "database");
        // Same sequence as the Swing restore dialog (SqlServerRestoreDialog.readBackupSets/resetFileTargets)
        List<SQLServerBackupSet> sets = SQLServerBackupRestore.readBackupSets(context.getMonitor(), dataSource, path);
        SQLServerRestoreSettings settings = new SQLServerRestoreSettings();
        settings.setSourcePath(path);
        settings.setBackupSets(sets);
        settings.selectDefaultPlan();
        SQLServerBackupSet base = settings.getBaseSet();
        if (base == null) {
            throw new DBException("No full backup set found in " + path);
        }
        List<SQLServerBackupFile> files = SQLServerBackupRestore.readBackupFiles(context.getMonitor(), dataSource, path, base.position());
        settings.setFiles(files);
        settings.setTargetDatabase(target);
        SQLServerBackupRestore.ServerInfo info = SQLServerBackupRestore.readServerInfo(context.getMonitor(), dataSource);
        settings.resetTargetPaths(info.dataFolder(), info.logFolder());
        settings.setReplace(AgentArgs.getBoolean(args, "replace", false));
        settings.setRecovery(true);
        settings.setTargetExists(SQLServerBackupRestore.readDatabaseInfo(context.getMonitor(), dataSource, target) != null);
        String problem = settings.validate();
        if (problem != null) {
            throw new AIToolArgumentException(problem);
        }
        return settings.buildBatches();
    }

    @NotNull
    private static JsonElement runBatches(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerDataSource dataSource,
        @NotNull List<String> batches,
        boolean execute
    ) throws DBException {
        JsonObject json = new JsonObject();
        json.addProperty("script", SQLServerBackupRestore.toScript(batches));
        if (!execute) {
            return json;
        }
        JsonArray messages = new JsonArray();
        SQLServerBackupRestore.execute(context.getMonitor(), dataSource, batches, (number, text, percent) -> {
            if (messages.size() < 200) {
                messages.add(text);
            }
        });
        json.addProperty("completed", true);
        json.add("messages", messages);
        return json;
    }

    // ------------------------------------------------------------------ policy hooks

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        return switch (context.getTool().getId()) {
            case "killSession" -> killStatement(DbFamily.of(container), AgentArgs.require(arguments, "session"));
            case "backupDatabase" -> SQLServerBackupRestore.toScript(backupBatches(arguments));
            case "restoreDatabase" -> SQLServerBackupRestore.toScript(restoreBatches(context, sqlServer(container), arguments));
            default -> null;
        };
    }

    @NotNull
    @Override
    public List<AIPendingTransaction> findPendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments
    ) throws DBException {
        List<AIPendingTransaction> result = new ArrayList<>();
        if (!context.getTool().getId().equals("killSession")) {
            return result;
        }
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        String session = AgentArgs.require(arguments, "session").trim();
        String sql = switch (family) {
            case SQLSERVER -> "SELECT open_transaction_count FROM sys.dm_exec_sessions WHERE session_id = ?";
            case POSTGRESQL -> "SELECT CASE WHEN xact_start IS NULL THEN 0 ELSE 1 END FROM pg_stat_activity WHERE pid = ?";
            case MYSQL, MARIADB -> "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = ?";
            case ORACLE -> "SELECT COUNT(*) FROM v$session WHERE sid = ? AND taddr IS NOT NULL";
            default -> null;
        };
        if (sql == null) {
            return result;
        }
        String id = family == DbFamily.ORACLE && session.contains(",") ? session.substring(0, session.indexOf(',')) : session;
        if (!NUMBER.matcher(id).matches()) {
            return result;
        }
        boolean open = AgentSql.run(context.getMonitor(), container, null, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, Long.parseLong(id));
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() && rs.getLong(1) > 0;
                }
            }
        });
        if (open) {
            result.add(new AIPendingTransaction(NLS.bind(AIToolsMessages.pending_owner_server_session, session),
                NLS.bind(AIToolsMessages.pending_server_session_work, session), false));
        }
        return result;
    }

    @Override
    public void resolvePendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments,
        @NotNull AIToolConfirmationAnswer answer
    ) {
        // ROLLBACK: the server rolls the session's transaction back when the session ends; nothing to do before
    }
}
