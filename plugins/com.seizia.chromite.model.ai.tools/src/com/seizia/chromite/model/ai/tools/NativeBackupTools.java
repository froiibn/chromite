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
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLTable;
import com.seizia.chromite.ext.mysql.tasks.MySQLExportSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLScriptExecuteSettings;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.tasks.PostgreBackupRestoreSettings.ExportFormat;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolExecutor;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.ai.tools.internal.AIToolsMessages;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolSettings;
import org.jkiss.utils.CommonUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PostgreSQL and MySQL/MariaDB backup ({@code dumpDatabase}, WRITE, writes a file) and restore ({@code restoreDump}, DANGEROUS)
 * with the local client tools: pg_dump, pg_restore, psql, mysqldump, mysql.
 *
 * 설계 (docs/chromite/ai-agent.md "PostgreSQL·MySQL 백업·복원 도구"):
 * <ul>
 *     <li>Swing 백업·복원 대화상자와 같은 네이티브 도구 작업을 같은 경로({@link NativeBackupRestore}, model.admin)로 실행한다. 설정 객체만
 *     도구 인수로 채운다. 암호는 명령줄에 없다(처리기가 PGPASSWORD·MYSQL_PWD 환경 변수로 넘김). 확인 요청에 실제로 실행할 명령줄을 보인다.</li>
 *     <li>로컬 클라이언트는 <b>연결의 로컬 클라이언트 설정</b>({@code clientHomeId}, 백업·복원 대화상자의 로컬 클라이언트 페이지가 저장)만 쓴다.
 *     설정이 없거나, 내려받는 클라이언트를 가리키거나, 도구가 그 폴더에 없으면 무엇을 하면 되는지 알리고 실행하지 않는다. 모델이 실행 파일
 *     폴더를 인수로 고르게 하지 않은 것은 모델이 고른 실행 파일을 돌리는 길을 만들지 않으려는 것이다. 클라이언트를 내려받지 않는다.</li>
 *     <li>파일은 Chromite가 도는 이 컴퓨터의 파일이다. 경로는 절대 경로여야 하고, 백업은 있는 파일을 덮어쓰지 않는다(새 경로만).
 *     {@code ${…}} 변수는 받지 않는다(확인한 명령과 실행하는 명령이 같도록).</li>
 *     <li>진행: 채팅에서는 1초마다 지난 시간, 백업 파일 크기, 도구의 마지막 출력 줄을 도구 카드에 보인다
 *     ({@link AIToolCallContext#reportProgress}). MCP는 호출마다 응답 하나라 진행을 보내지 않고 결과에 시간과 출력 끝부분을 넣는다.</li>
 *     <li>취소하면 처리기가 도구 프로세스를 끝낸다. 이 호출이 만든(호출 전에 없던) 백업 파일·폴더는 지운다. 실패한 백업의 파일은 대화상자와
 *     같이 지우지 않고 오류에 경로와 크기를 알린다.</li>
 *     <li>pg_restore 종료 코드 1(오류 있음)과 psql·mysql의 실패는 오류다. 일반 SQL 스크립트를 psql로 복원할 때 {@code stopOnError}가 꺼져
 *     있으면 psql은 SQL 오류가 있어도 0으로 끝나므로 결과에 그 점과 도구 출력을 넣는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 1b단계에서 미룬 PostgreSQL·MySQL/MariaDB 백업·복원 도구
 */
public class NativeBackupTools extends AgentTool implements AIToolPreviewProvider {

    private static final String DUMP = "dumpDatabase"; //$NON-NLS-1$
    private static final String RESTORE = "restoreDump"; //$NON-NLS-1$
    private static final long PROGRESS_INTERVAL_MS = 1000;
    private static final int LOG_LINES = 60;
    private static final int ERROR_LOG_LINES = 20;
    private static final int MAX_LINE_CHARS = 400;

    /**
     * A prepared backup or restore: the filled settings and what the result reports.
     */
    private record Job(
        @NotNull AbstractNativeToolSettings<?> settings,
        boolean restore,
        @NotNull String toolName,
        @NotNull String database,
        @NotNull Path file,
        @Nullable String format,
        @NotNull DBPNativeClientLocation client,
        @NotNull String commandLine,
        @Nullable String note
    ) {
    }

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
            case DUMP, RESTORE -> run(context, prepare(context.getMonitor(), container, toolId.equals(RESTORE), args));
            default -> throw unknownTool(toolId);
        };
    }

    @Override
    public boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        return family == DbFamily.POSTGRESQL || family == DbFamily.MYSQL || family == DbFamily.MARIADB;
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        Job job = prepare(context.getMonitor(), container, context.getTool().getId().equals(RESTORE), arguments);
        StringBuilder text = new StringBuilder(job.commandLine());
        text.append("\n\n").append(job.restore() ? "Restore into database: " : "Database: ").append(job.database()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        text.append("\nFile on this computer: ").append(job.file()); //$NON-NLS-1$
        text.append("\nLocal client: ").append(job.client().getPath().getAbsolutePath()); //$NON-NLS-1$
        if (job.note() != null) {
            text.append("\n").append(job.note()); //$NON-NLS-1$
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ prepare

    @NotNull
    private static Job prepare(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        boolean restore,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        boolean postgres = dataSource instanceof PostgreDataSource;
        if (!postgres && !(dataSource instanceof MySQLDataSource)) {
            throw new DBException((restore ? RESTORE : DUMP) + " supports PostgreSQL and MySQL/MariaDB connections " +
                "(SQL Server: " + (restore ? "restoreDatabase" : "backupDatabase") + ")");
        }
        String databaseName = AgentArgs.require(args, "database").trim();
        // Argument mistakes first, so that they are reported as such (INVALID) before any setting of the connection
        Path file = absolutePath(AgentArgs.require(args, "file"));
        DBSObject database = postgres
            ? ((PostgreDataSource) dataSource).getDatabase(databaseName)
            : ((MySQLDataSource) dataSource).getCatalog(databaseName);
        if (database == null) {
            List<String> names = new ArrayList<>();
            for (DBSObject object : postgres ? ((PostgreDataSource) dataSource).getDatabases() : ((MySQLDataSource) dataSource).getCatalogs()) {
                names.add(object.getName());
            }
            throw new AIToolArgumentException("Database '" + databaseName + "' is not on connection '" + container.getName() +
                "'. Databases of this connection: " + String.join(", ", names) +
                (postgres ? ". A PostgreSQL connection that does not show all databases has only its own database." : ""));
        }
        if (!NativeBackupRestore.isSupported(database)) {
            throw new DBException("The server type of connection '" + container.getName() + "' does not support the native client tools");
        }
        DBPNativeClientLocation client = findClient(container, postgres, restore);

        AbstractNativeToolSettings<?> settings;
        String toolName;
        String format = null;
        String note = null;
        if (!restore) {
            if (Files.exists(file)) {
                throw new AIToolArgumentException("File " + file + " already exists; the backup never overwrites a file. Give a new file name");
            }
            Path folder = file.getParent();
            if (folder == null || !Files.isDirectory(folder)) {
                throw new AIToolArgumentException("Folder " + folder + " does not exist on this computer");
            }
            if (postgres) {
                ExportFormat exportFormat = pgFormat(AgentArgs.getString(args, "format"), ExportFormat.CUSTOM);
                PostgreDatabaseBackupSettings pg = new PostgreDatabaseBackupSettings(container.getProject());
                pg.loadSettingsFromInput(pgBackupObjects(monitor, (PostgreDatabase) database, AgentArgs.getList(args, "schemas")), Map.of());
                pg.setFormat(exportFormat);
                pg.setOutputFolderPattern(folder.toString());
                pg.setOutputFilePattern(file.getFileName().toString());
                pg.setNoOwner(AgentArgs.getBoolean(args, "noOwner", false));
                pg.setNoPrivileges(AgentArgs.getBoolean(args, "noPrivileges", false));
                pg.setUseInserts(AgentArgs.getBoolean(args, "inserts", false));
                pg.setDropObjects(AgentArgs.getBoolean(args, "clean", false));
                pg.setCreateDatabase(AgentArgs.getBoolean(args, "create", false));
                settings = pg;
                toolName = "pg_dump"; //$NON-NLS-1$
                format = exportFormat.name().toLowerCase(Locale.ROOT);
            } else {
                MySQLExportSettings my = new MySQLExportSettings(container.getProject());
                my.loadSettingsFromInput(mySqlBackupObjects(monitor, (MySQLCatalog) database, AgentArgs.getList(args, "tables")), Map.of());
                my.setMethod(mySqlMethod(AgentArgs.getString(args, "method")));
                my.setOutputFolderPattern(folder.toString());
                my.setOutputFilePattern(file.getFileName().toString());
                // The defaults of the Swing backup dialog
                my.setAddDropStatements(true);
                my.setDisableKeys(true);
                my.setExtendedInserts(true);
                my.setNoData(AgentArgs.getBoolean(args, "noData", false));
                my.setNoRoutines(!AgentArgs.getBoolean(args, "routines", true));
                my.setDumpEvents(AgentArgs.getBoolean(args, "events", false));
                my.setRemoveDefiner(AgentArgs.getBoolean(args, "removeDefiner", false));
                settings = my;
                toolName = "mysqldump"; //$NON-NLS-1$
                format = "sql"; //$NON-NLS-1$
            }
        } else {
            if (!Files.exists(file)) {
                throw new AIToolArgumentException("File " + file + " does not exist on this computer");
            }
            if (postgres) {
                ExportFormat detected = detectPgFormat(file);
                ExportFormat exportFormat = pgFormat(AgentArgs.getString(args, "format"), detected);
                boolean stopOnError = AgentArgs.getBoolean(args, "stopOnError", false);
                PostgreDatabaseRestoreSettings pg = new PostgreDatabaseRestoreSettings(container.getProject());
                pg.loadSettingsFromInput(List.of(database), Map.of());
                pg.setRestoreInfo(new PostgreDatabaseRestoreInfo((PostgreDatabase) database));
                pg.setFormat(exportFormat);
                pg.setInputFile(file.toString());
                pg.setCleanFirst(AgentArgs.getBoolean(args, "clean", false));
                pg.setNoOwner(AgentArgs.getBoolean(args, "noOwner", false));
                if (stopOnError) {
                    // As the Swing restore dialog: psql exits with 3 at the first error, pg_restore with 1
                    pg.setExtraCommandArgs(exportFormat == ExportFormat.PLAIN ? "-v ON_ERROR_STOP=1" : "--exit-on-error"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                settings = pg;
                toolName = exportFormat == ExportFormat.PLAIN ? "psql" : "pg_restore"; //$NON-NLS-1$ //$NON-NLS-2$
                format = exportFormat.name().toLowerCase(Locale.ROOT);
                if (exportFormat == ExportFormat.PLAIN && !stopOnError) {
                    note = "psql runs the whole script and exits with 0 even when statements fail (stopOnError is off): check the log for ERROR lines.";
                } else if (exportFormat == ExportFormat.PLAIN && AgentArgs.getBoolean(args, "clean", false)) {
                    note = "clean applies to pg_restore only; a plain script drops objects only if it was dumped with clean.";
                }
            } else {
                MySQLScriptExecuteSettings my = new MySQLScriptExecuteSettings(container.getProject());
                my.loadSettingsFromInput(List.of((MySQLCatalog) database), Map.of());
                my.setImport(true);
                my.setInputFile(file.toString());
                my.setIsForeignKeyCheckDisabled(AgentArgs.getBoolean(args, "disableForeignKeyChecks", false));
                my.setLogLevel(MySQLScriptExecuteSettings.LogLevel.Normal);
                settings = my;
                toolName = "mysql"; //$NON-NLS-1$
                format = "sql"; //$NON-NLS-1$
            }
        }
        if (settings.getDataSourceContainer() == null) {
            settings.setDataSourceContainer(container);
        }
        settings.setClientHome(client);
        String commandLine;
        try {
            commandLine = NativeBackupRestore.getCommandLine(settings);
        } catch (IOException e) {
            throw new DBException(NativeBackupRestore.getToolNames(postgres, restore) + " was not found in the local client folder " +
                client.getPath().getAbsolutePath() + " of connection '" + container.getName() + "' (" + e.getMessage() + ")");
        }
        return new Job(settings, restore, toolName, database.getName(), file, format, client, commandLine, note);
    }

    /**
     * The local client of the connection's Local Client setting; never one that would be downloaded.
     */
    @NotNull
    private static DBPNativeClientLocation findClient(
        @NotNull DBPDataSourceContainer container,
        boolean postgres,
        boolean restore
    ) throws DBException {
        String tools = NativeBackupRestore.getToolNames(postgres, restore);
        String howTo = "The folder of the client tools is set per connection on the Local Client page of the database's " +
            "Tasks > Back Up... / Restore... dialog in Object Explorer (it is saved for the connection when that dialog runs)";
        List<DBPNativeClientLocation> local = NativeBackupRestore.findLocalClients(container.getDriver());
        List<String> found = local.stream().map(l -> l.getPath().getAbsolutePath()).toList();
        String foundText = found.isEmpty()
            ? " No local client installation was found on this computer; " + tools + " must be installed first."
            : " Local client folders found on this computer: " + String.join(", ", found) + ".";
        String id = container.getConnectionConfiguration().getClientHomeId();
        if (CommonUtils.isEmpty(id)) {
            throw new DBException("Connection '" + container.getName() + "' has no Local Client setting, so Chromite Agent " +
                "does not know which " + tools + " to run. " + howTo + "." + foundText);
        }
        for (DBPNativeClientLocation location : local) {
            if (location.getName().equals(id)) {
                return location;
            }
        }
        for (DBPNativeClientLocation location : container.getDriver().getNativeClientLocations()) {
            if (location.getName().equals(id) && !NativeBackupRestore.isLocal(location)) {
                throw new DBException("The Local Client setting of connection '" + container.getName() + "' is a client that " +
                    "would be downloaded on first use; Chromite Agent does not download client tools. Choose a local folder " +
                    "instead. " + howTo + "." + foundText);
            }
        }
        throw new DBException("The Local Client '" + id + "' of connection '" + container.getName() + "' is not on this " +
            "computer any more. " + howTo + "." + foundText);
    }

    @NotNull
    private static Path absolutePath(@NotNull String text) throws AIToolArgumentException {
        String value = text.trim();
        if (value.contains("${")) {
            throw new AIToolArgumentException("Argument 'file' must be a plain path without ${...} variables");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new AIToolArgumentException("Argument 'file' is not a valid path: " + e.getMessage());
        }
        if (!path.isAbsolute()) {
            throw new AIToolArgumentException("Argument 'file' must be an absolute path on the computer where Chromite runs");
        }
        return path.normalize();
    }

    @NotNull
    private static ExportFormat pgFormat(@Nullable String value, @NotNull ExportFormat defaultFormat) throws AIToolArgumentException {
        if (CommonUtils.isEmptyTrimmed(value)) {
            return defaultFormat;
        }
        try {
            return ExportFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AIToolArgumentException("Argument 'format' must be plain, custom, directory or tar");
        }
    }

    /**
     * The format of a pg_dump output: a directory with toc.dat, the "PGDMP" header of the custom format, a tar header
     * ("ustar" at offset 257), otherwise a plain SQL script.
     */
    @NotNull
    private static ExportFormat detectPgFormat(@NotNull Path file) throws DBException {
        if (Files.isDirectory(file)) {
            if (!Files.exists(file.resolve("toc.dat"))) { //$NON-NLS-1$
                throw new AIToolArgumentException("Folder " + file + " is not a pg_dump directory-format backup (no toc.dat)");
            }
            return ExportFormat.DIRECTORY;
        }
        byte[] head = new byte[512];
        int read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; read < head.length && (n = in.read(head, read, head.length - read)) > 0; ) {
                read += n;
            }
        } catch (IOException e) {
            throw new DBException("Can't read " + file + ": " + e.getMessage(), e);
        }
        if (read >= 5 && new String(head, 0, 5, StandardCharsets.US_ASCII).equals("PGDMP")) { //$NON-NLS-1$
            return ExportFormat.CUSTOM;
        }
        if (read >= 262 && new String(head, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) { //$NON-NLS-1$
            return ExportFormat.TAR;
        }
        return ExportFormat.PLAIN;
    }

    @NotNull
    private static List<DBSObject> pgBackupObjects(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreDatabase database,
        @NotNull List<String> schemaNames
    ) throws DBException {
        if (schemaNames.isEmpty()) {
            return List.of(database);
        }
        List<DBSObject> schemas = new ArrayList<>();
        for (String name : schemaNames) {
            PostgreSchema schema = database.getSchema(monitor, name);
            if (schema == null) {
                throw new AIToolArgumentException("Schema '" + name + "' is not in database " + database.getName());
            }
            schemas.add(schema);
        }
        return schemas;
    }

    @NotNull
    private static List<DBSObject> mySqlBackupObjects(
        @NotNull DBRProgressMonitor monitor,
        @NotNull MySQLCatalog catalog,
        @NotNull List<String> tableNames
    ) throws DBException {
        if (tableNames.isEmpty()) {
            return List.of(catalog);
        }
        List<DBSObject> tables = new ArrayList<>();
        for (String name : tableNames) {
            MySQLTable table = catalog.getTable(monitor, name);
            if (table == null) {
                throw new AIToolArgumentException("Table '" + name + "' is not in database " + catalog.getName());
            }
            tables.add(table);
        }
        return tables;
    }

    @NotNull
    private static MySQLExportSettings.DumpMethod mySqlMethod(@Nullable String value) throws AIToolArgumentException {
        if (CommonUtils.isEmptyTrimmed(value)) {
            return MySQLExportSettings.DumpMethod.ONLINE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "online" -> MySQLExportSettings.DumpMethod.ONLINE; //$NON-NLS-1$
            case "lock" -> MySQLExportSettings.DumpMethod.LOCK_ALL_TABLES; //$NON-NLS-1$
            case "normal" -> MySQLExportSettings.DumpMethod.NORMAL; //$NON-NLS-1$
            default -> throw new AIToolArgumentException("Argument 'method' must be online, lock or normal");
        };
    }

    // ------------------------------------------------------------------ run

    @NotNull
    private static JsonElement run(@NotNull AIToolCallContext context, @NotNull Job job) throws DBException {
        DBRProgressMonitor monitor = context.getMonitor();
        ToolLog toolLog = new ToolLog();
        long start = System.currentTimeMillis();
        Thread progress = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(PROGRESS_INTERVAL_MS);
                    context.reportProgress(describeProgress(job, start, toolLog.lastLine()));
                }
            } catch (InterruptedException e) {
                // The tool finished
            }
        }, "Chromite Agent " + job.toolName() + " progress"); //$NON-NLS-1$ //$NON-NLS-2$
        progress.setDaemon(true);
        boolean restoreErrors;
        try (PrintStream logStream = new PrintStream(toolLog, true, StandardCharsets.UTF_8)) {
            progress.start();
            restoreErrors = NativeBackupRestore.execute(monitor, job.settings(),
                "Chromite Agent: " + job.toolName() + " " + job.database(), logStream); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (Exception e) {
            if (monitor.isCanceled()) {
                if (!job.restore()) {
                    deleteCreated(job.file());
                }
                throw new DBException("Canceled", e);
            }
            throw new DBException(job.toolName() + " failed: " + AIToolExecutor.describe(e) + partialFileNote(job) +
                toolLog.tail("Last output of " + job.toolName(), ERROR_LOG_LINES), e);
        } finally {
            progress.interrupt();
        }
        if (monitor.isCanceled()) {
            if (!job.restore()) {
                deleteCreated(job.file());
            }
            throw new DBException("Canceled");
        }
        if (restoreErrors) {
            throw new DBException("pg_restore reported errors (exit code 1), so the restore may be incomplete. With clean, " +
                "errors about dropping objects that do not exist yet can be ignored." +
                toolLog.tail("Last output of pg_restore", ERROR_LOG_LINES));
        }
        JsonObject json = new JsonObject();
        json.addProperty("completed", true);
        json.addProperty("tool", job.toolName());
        json.addProperty("database", job.database());
        json.addProperty("file", job.file().toString());
        if (job.format() != null) {
            json.addProperty("format", job.format());
        }
        long bytes = size(job.file());
        if (!job.restore()) {
            if (bytes <= 0) {
                throw new DBException(job.toolName() + " finished without writing " + job.file() +
                    toolLog.tail("Last output of " + job.toolName(), ERROR_LOG_LINES));
            }
            json.addProperty("bytes", bytes);
        } else {
            json.addProperty("fileBytes", bytes);
        }
        json.addProperty("durationMs", System.currentTimeMillis() - start);
        json.addProperty("command", job.commandLine());
        if (job.note() != null) {
            json.addProperty("note", job.note());
        }
        JsonArray lines = new JsonArray();
        for (String line : toolLog.lines(LOG_LINES)) {
            lines.add(line);
        }
        json.add("log", lines);
        return json;
    }

    /**
     * Progress text of the chat card, in the screen language: the dump's size so far, elapsed time and the tool's last output
     * line. The card already names the tool, and a narrow tool window cuts the end of the line, so the numbers come first.
     */
    @NotNull
    private static String describeProgress(@NotNull Job job, long start, @Nullable String lastLine) {
        String elapsed = formatElapsed(System.currentTimeMillis() - start);
        String text = job.restore()
            ? NLS.bind(AIToolsMessages.progress_native_restore, elapsed)
            : NLS.bind(AIToolsMessages.progress_native_dump, formatBytes(size(job.file())), elapsed);
        return lastLine == null ? text : text + " - " + lastLine; //$NON-NLS-1$
    }

    @NotNull
    private static String formatElapsed(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
            ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) //$NON-NLS-1$
            : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); //$NON-NLS-1$
    }

    @NotNull
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B"; //$NON-NLS-1$
        }
        String[] units = {"KB", "MB", "GB", "TB"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]); //$NON-NLS-1$
    }

    /**
     * Size of a file, or of all files of a directory-format dump; 0 when it does not exist (yet).
     */
    private static long size(@NotNull Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
                }
            }
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @NotNull
    private static String partialFileNote(@NotNull Job job) {
        if (job.restore() || !Files.exists(job.file())) {
            return "";
        }
        return ". An incomplete backup was left at " + job.file() + " (" + size(job.file()) + " bytes); it is not a usable backup";
    }

    /**
     * Deletes the backup file or directory-format folder of a canceled call. The call refuses an existing path, so whatever is
     * there now was created by this call.
     */
    private static void deleteCreated(@NotNull Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            // The cancel itself is reported; a file that can't be deleted stays
        }
    }

    /**
     * The handler's log: the command line (password masked), the tool's output and the handler's own messages. Keeps the last
     * lines without stack traces (the error itself is reported).
     */
    private static final class ToolLog extends OutputStream {
        private static final Pattern LOG_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} [\\d:.]+ - "); //$NON-NLS-1$
        private static final Pattern EXCEPTION_LINE = Pattern.compile("^[\\w.$]+(Exception|Error)(: .*)?$"); //$NON-NLS-1$
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final Deque<String> lines = new ArrayDeque<>();
        private volatile String lastLine;

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public synchronized void close() {
            flushLine();
        }

        private void flushLine() {
            String text = line.toString(StandardCharsets.UTF_8).strip();
            line.reset();
            if (text.isEmpty() || isStackTraceLine(text)) {
                return;
            }
            if (text.length() > MAX_LINE_CHARS) {
                text = text.substring(0, MAX_LINE_CHARS) + "..."; //$NON-NLS-1$
            }
            lines.addLast(text);
            while (lines.size() > LOG_LINES) {
                lines.removeFirst();
            }
            lastLine = text;
        }

        private static boolean isStackTraceLine(@NotNull String text) {
            // The handler's own log lines start with "yyyy-MM-dd HH:mm:ss.SSS - "
            String line = LOG_PREFIX.matcher(text).replaceFirst("");
            return line.startsWith("at ") || line.startsWith("Caused by:") || line.startsWith("... ") || //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                EXCEPTION_LINE.matcher(line).matches();
        }

        @Nullable
        String lastLine() {
            return lastLine;
        }

        @NotNull
        synchronized List<String> lines(int count) {
            List<String> all = new ArrayList<>(lines);
            return all.subList(Math.max(0, all.size() - count), all.size());
        }

        @NotNull
        String tail(@NotNull String title, int count) {
            List<String> last = lines(count);
            return last.isEmpty() ? "" : "\n" + title + ":\n" + String.join("\n", last); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
