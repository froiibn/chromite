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
package com.seizia.chromite.model.admin.mssql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mssql.model.SQLServerDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 백업·복원 실행: 서버 정보와 백업 파일 읽기, 일괄 처리 실행과 진행 메시지.
 *
 * 설계:
 * <ul>
 *     <li>읽기(서버 기본 경로, 압축 지원, 복구 모델, 백업 기록, {@code RESTORE HEADERONLY/FILELISTONLY})는 연결의 유틸리티
 *     세션에서 한다. 짧은 조회라 쿼리 창이 쓰는 문맥을 잠깐 써도 된다.</li>
 *     <li>실행({@link #execute})은 따로 연 격리 문맥에서 한다({@code DBSInstance#openIsolatedContext}, Activity Monitor와 같음).
 *     긴 백업이 쿼리 창의 연결을 붙잡지 않고, 복원 일괄 처리의 {@code USE [master]}가 쿼리 창의 현재 데이터베이스를 바꾸지 않게 하려는
 *     것이다. 연결 설정이 "단일 연결"이면 기본 문맥을 쓴다. BACKUP/RESTORE는 트랜잭션 안에서 실행할 수 없으므로 자동 커밋으로
 *     실행한다.</li>
 *     <li>진행 메시지: {@code STATS}가 보내는 "N percent processed."(메시지 3211)와 완료 메시지는 JDBC 경고로 온다. 드라이버는
 *     응답을 읽으면서 경고를 문장에 붙이므로, 실행하는 동안 다른 스레드에서 경고 사슬을 짧은 간격으로 읽어 새 것만 넘긴다.
 *     진행률은 메시지 번호(언어와 상관없음)로 알아낸다({@link #PROGRESS_MESSAGE}).</li>
 *     <li>취소: 모니터가 취소되면 {@link Statement#cancel()}(서버에 attention)을 보낸다. 서버가 멈추면 문장이 오류로 끝난다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
public final class SQLServerBackupRestore {

    private static final Log log = Log.getLog(SQLServerBackupRestore.class);

    /**
     * Number of the "%d percent processed." message sent by BACKUP/RESTORE ... WITH STATS.
     */
    public static final int PROGRESS_MESSAGE = 3211;

    private static final long POLL_INTERVAL_MS = 200;

    /**
     * Receives the server messages of a running batch, in order.
     */
    public interface MessageListener {
        /**
         * @param number  server message number (0 if unknown)
         * @param text    message text
         * @param percent progress of a {@link #PROGRESS_MESSAGE} message, else -1
         */
        void message(int number, @NotNull String text, int percent);
    }

    /**
     * Server facts the dialogs need.
     *
     * @param backupFolder        default backup folder (InstanceDefaultBackupPath, else the data folder)
     * @param dataFolder          default data file folder
     * @param logFolder           default log file folder
     * @param compressionSupported the edition supports backup compression
     * @param compressionDefault  the "backup compression default" server option is on
     * @param backupSupported     the server can back up to disk (not Azure SQL Database or Synapse)
     */
    public record ServerInfo(
        @NotNull String backupFolder,
        @NotNull String dataFolder,
        @NotNull String logFolder,
        boolean compressionSupported,
        boolean compressionDefault,
        boolean backupSupported
    ) {
        /**
         * The path separator of the server's file system (from the default folders).
         */
        public char getSeparator() {
            return backupFolder.indexOf('/') >= 0 || dataFolder.indexOf('/') >= 0 ? '/' : '\\';
        }

        /**
         * A file in the backup folder.
         */
        @NotNull
        public String backupPath(@NotNull String fileName) {
            if (backupFolder.isEmpty()) {
                return fileName;
            }
            char last = backupFolder.charAt(backupFolder.length() - 1);
            return last == '/' || last == '\\' ? backupFolder + fileName : backupFolder + getSeparator() + fileName;
        }
    }

    /**
     * Facts of the database being backed up.
     *
     * @param recoveryModel SIMPLE, FULL or BULK_LOGGED
     * @param state         ONLINE, RESTORING, ...
     */
    public record DatabaseInfo(@NotNull String recoveryModel, @NotNull String state) {
        public boolean isSimpleRecovery() {
            return "SIMPLE".equalsIgnoreCase(recoveryModel); //$NON-NLS-1$
        }
    }

    private SQLServerBackupRestore() {
    }

    /**
     * [name] with ] doubled (the quoting SSMS scripts use).
     */
    @NotNull
    public static String quoteName(@NotNull String name) {
        return "[" + name.replace("]", "]]") + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * N'text' with ' doubled.
     */
    @NotNull
    public static String quoteString(@NotNull String text) {
        return "N'" + text.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * The batches as one script to show or open in a query window (GO between batches).
     */
    @NotNull
    public static String toScript(@NotNull List<String> batches) {
        StringBuilder script = new StringBuilder();
        for (String batch : batches) {
            script.append(batch).append("\nGO\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    @NotNull
    public static ServerInfo readServerInfo(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerDataSource dataSource) throws DBException {
        String query =
            "SELECT CAST(SERVERPROPERTY('EngineEdition') AS INT) AS engine_edition,\n" + //$NON-NLS-1$
            "CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS NVARCHAR(4000)) AS backup_path,\n" + //$NON-NLS-1$
            "CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS NVARCHAR(4000)) AS data_path,\n" + //$NON-NLS-1$
            "CAST(SERVERPROPERTY('InstanceDefaultLogPath') AS NVARCHAR(4000)) AS log_path,\n" + //$NON-NLS-1$
            "(SELECT CAST(value_in_use AS INT) FROM sys.configurations WHERE name = 'backup compression default') AS compression_default"; //$NON-NLS-1$
        try (JDBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Read backup settings")) { //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement(query);
                 JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next()) {
                    throw new DBException("No server properties"); //$NON-NLS-1$
                }
                int edition = dbResult.getInt("engine_edition"); //$NON-NLS-1$
                String dataFolder = CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "data_path")); //$NON-NLS-1$
                String logFolder = CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "log_path")); //$NON-NLS-1$
                String backupFolder = JDBCUtils.safeGetString(dbResult, "backup_path"); //$NON-NLS-1$
                if (CommonUtils.isEmpty(backupFolder)) {
                    // Before SQL Server 2019 there is no InstanceDefaultBackupPath
                    backupFolder = dataFolder;
                }
                return new ServerInfo(
                    trimFolder(backupFolder),
                    trimFolder(dataFolder),
                    trimFolder(CommonUtils.isEmpty(logFolder) ? dataFolder : logFolder),
                    // Standard (2), Enterprise/Developer (3), Managed Instance (8); not Express (4)
                    edition == 2 || edition == 3 || edition == 8,
                    JDBCUtils.safeGetInt(dbResult, "compression_default") == 1, //$NON-NLS-1$
                    // Azure SQL Database (5) and Synapse (6, 11) can't BACKUP TO DISK
                    edition != 5 && edition != 6 && edition != 11);
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @NotNull
    private static String trimFolder(@NotNull String folder) {
        String trimmed = folder.trim();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) { //$NON-NLS-1$ //$NON-NLS-2$
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Recovery model and state of a database; null if there is no such database.
     */
    @Nullable
    public static DatabaseInfo readDatabaseInfo(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @NotNull String databaseName
    ) throws DBException {
        try (JDBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Read database state")) { //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT recovery_model_desc, state_desc FROM sys.databases WHERE name = ?")) { //$NON-NLS-1$
                dbStat.setString(1, databaseName);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (!dbResult.next()) {
                        return null;
                    }
                    return new DatabaseInfo(
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "recovery_model_desc")), //$NON-NLS-1$
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "state_desc"))); //$NON-NLS-1$
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    /**
     * Disk backup files recorded in msdb, newest first (the restore dialog offers them as sources).
     *
     * @param databaseName only files with backups of this database, or null for all
     */
    @NotNull
    public static List<String> readBackupHistoryFiles(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @Nullable String databaseName
    ) throws DBException {
        // device_type 2 = disk
        String query =
            "SELECT TOP 50 mf.physical_device_name, MAX(bs.backup_finish_date) AS last_finish\n" + //$NON-NLS-1$
            "FROM msdb.dbo.backupset bs JOIN msdb.dbo.backupmediafamily mf ON mf.media_set_id = bs.media_set_id\n" + //$NON-NLS-1$
            "WHERE mf.device_type = 2" + (databaseName == null ? "" : " AND bs.database_name = ?") + "\n" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "GROUP BY mf.physical_device_name ORDER BY last_finish DESC"; //$NON-NLS-1$
        List<String> files = new ArrayList<>();
        try (JDBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Read backup history")) { //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement(query)) {
                if (databaseName != null) {
                    dbStat.setString(1, databaseName);
                }
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String file = JDBCUtils.safeGetString(dbResult, "physical_device_name"); //$NON-NLS-1$
                        if (!CommonUtils.isEmpty(file)) {
                            files.add(file);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // No access to msdb is not an error for the dialog: the path can be typed
            log.debug("Can't read backup history", e);
        }
        return files;
    }

    /**
     * The backup sets of a backup file on the server (RESTORE HEADERONLY).
     */
    @NotNull
    public static List<SQLServerBackupSet> readBackupSets(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @NotNull String path
    ) throws DBException {
        List<SQLServerBackupSet> sets = new ArrayList<>();
        try (JDBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Read backup sets")) { //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement("RESTORE HEADERONLY FROM DISK = " + quoteString(path)); //$NON-NLS-1$
                 JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    sets.add(new SQLServerBackupSet(
                        JDBCUtils.safeGetInt(dbResult, "Position"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "BackupName"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "BackupDescription"), //$NON-NLS-1$
                        JDBCUtils.safeGetInt(dbResult, "BackupType"), //$NON-NLS-1$
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "DatabaseName")), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "ServerName"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "UserName"), //$NON-NLS-1$
                        JDBCUtils.safeGetTimestamp(dbResult, "BackupStartDate"), //$NON-NLS-1$
                        JDBCUtils.safeGetTimestamp(dbResult, "BackupFinishDate"), //$NON-NLS-1$
                        JDBCUtils.safeGetBigDecimal(dbResult, "FirstLSN"), //$NON-NLS-1$
                        JDBCUtils.safeGetBigDecimal(dbResult, "LastLSN"), //$NON-NLS-1$
                        JDBCUtils.safeGetBigDecimal(dbResult, "CheckpointLSN"), //$NON-NLS-1$
                        JDBCUtils.safeGetBigDecimal(dbResult, "DatabaseBackupLSN"), //$NON-NLS-1$
                        JDBCUtils.safeGetLong(dbResult, "BackupSize"), //$NON-NLS-1$
                        JDBCUtils.safeGetBoolean(dbResult, "IsCopyOnly"))); //$NON-NLS-1$
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        return sets;
    }

    /**
     * The database files of a backup set (RESTORE FILELISTONLY).
     */
    @NotNull
    public static List<SQLServerBackupFile> readBackupFiles(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @NotNull String path,
        int position
    ) throws DBException {
        List<SQLServerBackupFile> files = new ArrayList<>();
        try (JDBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Read backup files")) { //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "RESTORE FILELISTONLY FROM DISK = " + quoteString(path) + " WITH FILE = " + position); //$NON-NLS-1$ //$NON-NLS-2$
                 JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    BigDecimal size = JDBCUtils.safeGetBigDecimal(dbResult, "Size"); //$NON-NLS-1$
                    files.add(new SQLServerBackupFile(
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "LogicalName")), //$NON-NLS-1$
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "PhysicalName")), //$NON-NLS-1$
                        CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "Type")), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "FileGroupName"), //$NON-NLS-1$
                        size == null ? 0 : size.longValue()));
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        return files;
    }

    /**
     * Runs the batches one after another on an own connection, passing the server messages to the listener.
     * Stops at the first failing batch.
     *
     * @throws DBException the failing batch's error (its message includes all server error messages)
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @NotNull List<String> batches,
        @NotNull MessageListener listener
    ) throws DBException {
        boolean ownContext = !dataSource.getContainer().isForceUseSingleConnection();
        DBCExecutionContext context = ownContext
            ? dataSource.getDefaultInstance().openIsolatedContext(monitor, "Chromite backup/restore", null) //$NON-NLS-1$
            : DBUtils.getDefaultContext(dataSource, false);
        try (JDBCSession session = (JDBCSession) context.openSession(monitor, DBCExecutionPurpose.UTIL, "Backup/restore")) { //$NON-NLS-1$
            Connection connection = session.getOriginal();
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                connection.setAutoCommit(true);
            }
            try {
                for (String batch : batches) {
                    if (monitor.isCanceled()) {
                        throw new DBCException("Canceled"); //$NON-NLS-1$
                    }
                    monitor.subTask(CommonUtils.truncateString(batch, 200));
                    executeBatch(monitor, connection, batch, listener);
                }
            } finally {
                if (!autoCommit && !ownContext) {
                    connection.setAutoCommit(false);
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        } finally {
            if (ownContext) {
                context.close();
            }
        }
    }

    private static void executeBatch(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Connection connection,
        @NotNull String batch,
        @NotNull MessageListener listener
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            WarningReader reader = new WarningReader(statement, listener);
            Thread watcher = new Thread(() -> {
                boolean canceled = false;
                while (!reader.finished) {
                    reader.poll();
                    if (!canceled && monitor.isCanceled()) {
                        canceled = true;
                        try {
                            statement.cancel();
                        } catch (SQLException e) {
                            log.debug("Can't cancel the backup/restore statement", e);
                        }
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "Backup/restore messages"); //$NON-NLS-1$
            watcher.setDaemon(true);
            watcher.start();
            try {
                boolean hasResults = statement.execute(batch);
                // Read every result of the batch (the messages of later statements come with them)
                while (true) {
                    if (hasResults) {
                        try (ResultSet ignored = statement.getResultSet()) {
                            // Not used
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    hasResults = statement.getMoreResults();
                }
            } finally {
                reader.finished = true;
                watcher.interrupt();
                try {
                    watcher.join(POLL_INTERVAL_MS * 5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reader.poll();
            }
        }
    }

    /**
     * Passes on the warnings of a statement that were not passed on yet. Synchronized: called from the watcher thread
     * while the statement runs and once more after it.
     */
    private static final class WarningReader {
        private final Statement statement;
        private final MessageListener listener;
        private int reported;
        volatile boolean finished;

        WarningReader(@NotNull Statement statement, @NotNull MessageListener listener) {
            this.statement = statement;
            this.listener = listener;
        }

        synchronized void poll() {
            SQLWarning warning;
            try {
                warning = statement.getWarnings();
            } catch (SQLException e) {
                return;
            }
            int index = 0;
            for (; warning != null; warning = warning.getNextWarning(), index++) {
                if (index < reported) {
                    continue;
                }
                reported++;
                String text = CommonUtils.notEmpty(warning.getMessage());
                int number = warning.getErrorCode();
                listener.message(number, text, number == PROGRESS_MESSAGE ? parsePercent(text) : -1);
            }
        }
    }

    /**
     * The number in a "N percent processed." message (any language: the first number of the text).
     */
    static int parsePercent(@NotNull String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                return CommonUtils.toInt(text.substring(start, i), -1);
            }
        }
        return start >= 0 ? CommonUtils.toInt(text.substring(start), -1) : -1;
    }

    /**
     * Error text of a failed batch with all server messages (SQL Server reports the cause first and
     * "BACKUP DATABASE is terminating abnormally." after it).
     */
    @NotNull
    public static String getErrorText(@NotNull Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable last = error;
        for (Throwable e = error; e != null && e != e.getCause(); e = e.getCause()) {
            if (e instanceof SQLException sqlError) {
                for (SQLException next = sqlError; next != null; next = next.getNextException()) {
                    appendLine(text, next.getMessage());
                }
                return text.toString();
            }
            last = e;
        }
        appendLine(text, last.getMessage());
        return text.toString();
    }

    private static void appendLine(@NotNull StringBuilder text, @Nullable String line) {
        if (!CommonUtils.isEmpty(line) && text.indexOf(line) < 0) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line);
        }
    }
}
