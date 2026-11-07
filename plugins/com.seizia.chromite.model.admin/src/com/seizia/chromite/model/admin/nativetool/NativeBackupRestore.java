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
package com.seizia.chromite.model.admin.nativetool;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.tasks.MySQLDatabaseExportHandler;
import com.seizia.chromite.ext.mysql.tasks.MySQLDatabaseExportInfo;
import com.seizia.chromite.ext.mysql.tasks.MySQLExportSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLScriptExecuteHandler;
import com.seizia.chromite.ext.mysql.tasks.MySQLScriptExecuteSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLTasks;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.tasks.OracleScriptExecuteHandler;
import com.seizia.chromite.ext.oracle.tasks.OracleScriptExecuteSettings;
import com.seizia.chromite.ext.oracle.tasks.OracleTasks;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupHandler;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreHandler;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreSQLTasks;
import com.seizia.chromite.ext.postgresql.tasks.PostgreScriptExecuteHandler;
import com.seizia.chromite.ext.postgresql.tasks.PostgreScriptExecuteSettings;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.connection.DBPNativeClientLocationManager;
import com.seizia.chromite.model.connection.LocalNativeClientLocation;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.MonitorRunnableContext;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskExecutionListener;
import com.seizia.chromite.model.task.DBTTaskHandler;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.RemoteNativeClientLocation;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolHandler;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolSettings;
import com.seizia.chromite.tasks.nativetool.AbstractScriptExecuteSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL and MySQL/MariaDB backup and restore with the local client tools (pg_dump, pg_restore, psql, mysqldump, mysql),
 * and running a script file with the native client (psql, mysql, sqlplus), through the native tool tasks of the model
 * (tasks.native, ext.postgresql, ext.mysql, ext.oracle). Also the saved tasks of these types (load, save).
 *
 * 설계: Swing 백업·복원 대화상자({@code NativeBackupRestoreDialog})와 Chromite Agent 도구({@code NativeBackupTools})가 같은
 * 실행 경로를 쓰도록 화면과 상관없는 부분을 이 모델 클래스에 모았다(docs/chromite/swing-backup-restore.md,
 * docs/chromite/ai-agent.md "PostgreSQL·MySQL 백업·복원 도구"). 설정 객체는 호출자가 채운다(대화상자는 화면 값, 도구는 인수).
 * <ul>
 *     <li>실행: SWT 마법사처럼 설정을 임시 작업에 저장하고 작업 형식의 처리기로 {@code executeTask}를 부른다. 처리기가 설정을
 *     작업에서 다시 읽고, 연결의 클라이언트 홈({@code clientHomeId})으로 도구를 찾는다. 암호는 명령줄에 넣지 않는다
 *     (처리기가 PGPASSWORD·MYSQL_PWD 환경 변수로 넘김).</li>
 *     <li>pg_restore는 치명적인 오류(로그인 실패 등)에도 종료 코드 1로 끝나는데, 모델 처리기는 1을 경고 알림으로만 보내고 정상
 *     종료한다. 그래서 PostgreSQL 복원은 {@link CheckedPostgreRestore}로 실행해 종료 코드 1을 결과로 돌려준다. 실패 문구는
 *     호출자가 정한다(대화상자는 화면 언어, 도구는 모델이 읽는 영어). 모델 처리기는 DBeaver 코드라 고치지 않았다.</li>
 *     <li>명령줄: 처리기의 {@code getCommandLine}(보호 메서드)을 하위 클래스로 불러 실제로 실행할 명령을 만든다(미리 보기, 확인 요청).</li>
 *     <li>로컬 클라이언트: 드라이버의 클라이언트 위치와 클라이언트 관리자가 찾은 설치. 내려받는 클라이언트
 *     ({@link RemoteNativeClientLocation}, 처음 쓸 때 원격 저장소에서 받음)는 빼서, 묻지 않고 클라이언트를 내려받지 않는다.
 *     찾아보기로 고른 폴더는 드라이버의 사용자 위치로 저장한다({@link #addLocalClient}, 대화상자와 연결 편집기가 함께 씀).</li>
 *     <li>스크립트 실행: 모델의 스크립트 실행 작업 형식(pgScriptExecute, mysqlScriptExecute, oracleScriptExecute)을 같은 경로로
 *     실행한다. 도구는 따로 접속하는 새 세션이라 쿼리 창의 세션·트랜잭션과 상관없다. SQL*Plus는 암호를 명령줄이 아니라 표준 입력의
 *     로그온 명령으로 받는다({@link OracleScriptExecuteHandler#getLogonScript}, {@link #getStandardInputPreview}).</li>
 *     <li>저장된 작업: 위 일곱 형식은 대화상자에서 작업으로 저장하고({@link #saveTask}) 다시 읽어 고칠 수 있다({@link #loadTaskSettings}).
 *     저장된 작업을 실행하면 처리기가 명령줄 승인을 묻는다({@code AbstractNativeToolHandler}, docs/chromite/shell-command-approval.md).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: NativeBackupRestoreDialog의 모델 부분(클라이언트 목록, 작업 형식, 명령줄, 처리기 실행, pg_restore 종료 코드 1)을
 *   옮겨 Chromite Agent 백업·복원 도구와 함께 씀
 * - 2026-11-08 네이티브 클라이언트로 스크립트 실행(psql, mysql, sqlplus), 저장된 작업 읽기·저장, 클라이언트 폴더 더하기를 대화상자에서 옮김
 *   (docs/chromite/swing-database-tasks.md)
 */
public final class NativeBackupRestore {

    private static final Log log = Log.getLog(NativeBackupRestore.class);

    /**
     * Task types that the Back Up / Restore and Run Script dialogs save and edit.
     */
    private static final Set<String> SAVED_TASK_TYPES = Set.of(
        PostgreSQLTasks.TASK_DATABASE_BACKUP, PostgreSQLTasks.TASK_DATABASE_RESTORE, PostgreSQLTasks.TASK_SCRIPT_EXECUTE,
        MySQLTasks.TASK_DATABASE_BACKUP, MySQLTasks.TASK_DATABASE_RESTORE, MySQLTasks.TASK_SCRIPT_EXECUTE,
        OracleTasks.TASK_SCRIPT_EXECUTE);

    private NativeBackupRestore() {
    }

    /**
     * The object is a database that the native backup and restore apply to: a PostgreSQL database whose server type supports
     * the native client, or a MySQL/MariaDB catalog of a server that supports native clients (the task conditions of the
     * ext.postgresql and ext.mysql plugin.xml).
     */
    public static boolean isSupported(@Nullable DBSObject database) {
        if (database instanceof PostgreDatabase pg) {
            return pg.getDataSource().getServerType().supportsNativeClient();
        }
        if (database instanceof MySQLCatalog catalog) {
            return catalog.getDataSource().supportsNativeClients();
        }
        return false;
    }

    /**
     * The client tools a backup or restore runs, for messages.
     */
    @NotNull
    public static String getToolNames(boolean postgres, boolean restore) {
        if (postgres) {
            return restore ? "pg_restore, psql" : "pg_dump"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return restore ? "mysql / mariadb" : "mysqldump / mariadb-dump"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The object is a target of Run Script with Native Client: a PostgreSQL database or a MySQL/MariaDB catalog (the same
     * conditions as backup and restore), an Oracle schema or an Oracle connection (the object types of the model task types
     * pgScriptExecute, mysqlScriptExecute and oracleScriptExecute).
     */
    public static boolean isScriptSupported(@Nullable DBSObject target) {
        return isSupported(target) || target instanceof OracleSchema || target instanceof OracleDataSource;
    }

    /**
     * The client tool that runs a script on the target, for messages.
     */
    @NotNull
    public static String getScriptToolName(@NotNull DBSObject target) {
        if (target instanceof PostgreDatabase) {
            return "psql"; //$NON-NLS-1$
        }
        if (target instanceof MySQLCatalog) {
            return "mysql / mariadb"; //$NON-NLS-1$
        }
        return "sqlplus"; //$NON-NLS-1$
    }

    /**
     * Settings of a script run on the target (a PostgreSQL database, MySQL/MariaDB catalog, Oracle schema or connection),
     * without the input file and options.
     */
    @NotNull
    public static AbstractScriptExecuteSettings<?> createScriptSettings(@NotNull DBSObject target) {
        DBPDataSourceContainer container = target.getDataSource().getContainer();
        DBPProject project = container.getProject();
        AbstractScriptExecuteSettings<?> settings;
        if (target instanceof PostgreDatabase database) {
            PostgreScriptExecuteSettings pg = new PostgreScriptExecuteSettings(project);
            pg.loadSettingsFromInput(List.of(database), Map.of());
            pg.setDatabase(database);
            settings = pg;
        } else if (target instanceof MySQLCatalog catalog) {
            MySQLScriptExecuteSettings my = new MySQLScriptExecuteSettings(project);
            my.loadSettingsFromInput(List.of(catalog), Map.of());
            my.setImport(false);
            settings = my;
        } else {
            OracleScriptExecuteSettings oracle = new OracleScriptExecuteSettings(project);
            oracle.loadSettingsFromInput(List.of(target), Map.of());
            settings = oracle;
        }
        settings.setDataSourceContainer(container);
        return settings;
    }

    /**
     * Local client locations of the driver: its own (custom) locations and the installations the client manager finds,
     * without the locations downloaded from a remote repository. One entry per location name.
     */
    @NotNull
    public static List<DBPNativeClientLocation> findLocalClients(@NotNull DBPDriver driver) {
        Map<String, DBPNativeClientLocation> locations = new LinkedHashMap<>();
        for (DBPNativeClientLocation location : driver.getNativeClientLocations()) {
            if (isLocal(location)) {
                locations.put(location.getName(), location);
            }
        }
        DBPNativeClientLocationManager manager = driver.getNativeClientManager();
        if (manager != null) {
            for (DBPNativeClientLocation location : manager.findLocalClientLocations()) {
                locations.putIfAbsent(location.getName(), location);
            }
        }
        return new ArrayList<>(locations.values());
    }

    /**
     * The location is on this computer already (not one that is downloaded on first use).
     */
    public static boolean isLocal(@NotNull DBPNativeClientLocation location) {
        return !(location instanceof RemoteNativeClientLocation);
    }

    /**
     * The driver can run native client tools: its provider finds installed clients, or it has client locations.
     */
    public static boolean hasNativeClients(@NotNull DBPDriver driver) {
        return driver.getNativeClientManager() != null || !driver.getNativeClientLocations().isEmpty();
    }

    /**
     * Adds a client folder chosen by the user to the driver's own client locations and saves the drivers, as the SWT client
     * homes panel does. The location's name is the folder; it is what a connection stores as its client home.
     */
    public static void addLocalClient(@NotNull DBPDriver driver, @NotNull String path) {
        if (!(driver instanceof DriverDescriptor descriptor)) {
            return;
        }
        // Keep the driver's custom locations and add this one, as the SWT client homes panel saves them
        List<DBPNativeClientLocation> custom = new ArrayList<>();
        for (DBPNativeClientLocation location : driver.getNativeClientLocations()) {
            if (isLocal(location) && !location.getName().equals(path)) {
                custom.add(location);
            }
        }
        custom.add(new LocalNativeClientLocation(path, path));
        descriptor.setNativeClientLocations(custom);
        // Only modified drivers are written to drivers.xml; without this the folder is gone after a restart
        // (the SWT client homes panel relies on the driver being modified already)
        descriptor.setModified(true);
        try {
            descriptor.getProviderDescriptor().getRegistry().saveDrivers();
        } catch (DBException e) {
            log.debug("Can't save the client location", e);
        }
    }

    /**
     * Task type of the settings: PostgreSQL or MySQL, backup or restore.
     */
    @NotNull
    public static String getTaskTypeId(@NotNull AbstractNativeToolSettings<?> settings) {
        if (settings instanceof PostgreDatabaseBackupSettings) {
            return PostgreSQLTasks.TASK_DATABASE_BACKUP;
        }
        if (settings instanceof PostgreDatabaseRestoreSettings) {
            return PostgreSQLTasks.TASK_DATABASE_RESTORE;
        }
        if (settings instanceof MySQLExportSettings) {
            return MySQLTasks.TASK_DATABASE_BACKUP;
        }
        if (settings instanceof MySQLScriptExecuteSettings my) {
            return my.isImport() ? MySQLTasks.TASK_DATABASE_RESTORE : MySQLTasks.TASK_SCRIPT_EXECUTE;
        }
        if (settings instanceof PostgreScriptExecuteSettings) {
            return PostgreSQLTasks.TASK_SCRIPT_EXECUTE;
        }
        if (settings instanceof OracleScriptExecuteSettings) {
            return OracleTasks.TASK_SCRIPT_EXECUTE;
        }
        throw new IllegalArgumentException("Not a database backup, restore or script run: " + settings.getClass().getName());
    }

    /**
     * The command line the handler will run, quoted for display; a MySQL backup or restore and a script run add the output
     * or input redirection that the handler does with streams. No password is in it.
     *
     * @throws IOException the tool is not in the client location
     */
    @NotNull
    public static String getCommandLine(@NotNull AbstractNativeToolSettings<?> settings) throws IOException {
        List<String> command;
        String redirect = null;
        if (settings instanceof PostgreDatabaseBackupSettings pg) {
            // A local output file is passed with --file
            command = new PreviewPostgreBackup().preview(pg, pg.getExportObjects().get(0));
        } else if (settings instanceof PostgreDatabaseRestoreSettings pg) {
            command = new PreviewPostgreRestore().preview(pg, pg.getRestoreInfo());
        } else if (settings instanceof MySQLExportSettings my) {
            MySQLDatabaseExportInfo info = my.getExportObjects().get(0);
            command = new PreviewMySQLExport().preview(my, info);
            redirect = "> " + my.getOutputFile(info); //$NON-NLS-1$
        } else if (settings instanceof PostgreScriptExecuteSettings pg) {
            command = new PreviewPostgreScript().preview(pg, pg.getDatabase());
            redirect = "< " + pg.getInputFile(); //$NON-NLS-1$
        } else if (settings instanceof OracleScriptExecuteSettings oracle) {
            command = new PreviewOracleScript().preview(oracle);
            redirect = "< " + oracle.getInputFile(); //$NON-NLS-1$
        } else {
            MySQLScriptExecuteSettings my = (MySQLScriptExecuteSettings) settings;
            command = new PreviewMySQLRestore().preview(my, my.getDatabaseObjects().get(0));
            redirect = "< " + my.getInputFile(); //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder();
        for (String arg : command) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(arg.isEmpty() || arg.indexOf(' ') >= 0 ? "\"" + arg + "\"" : arg); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (redirect != null) {
            text.append(' ').append(redirect);
        }
        return text.toString();
    }

    /**
     * What the handler writes to the tool's standard input before the script, with the password masked: the SQL*Plus logon
     * commands. Null for the other tools (they read the password from an environment variable).
     */
    @Nullable
    public static String getStandardInputPreview(@NotNull AbstractNativeToolSettings<?> settings) throws IOException {
        if (settings instanceof OracleScriptExecuteSettings oracle) {
            return new OracleScriptExecuteHandler().getLogonScript(oracle, true);
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Saved tasks

    /**
     * The Back Up / Restore and Run Script dialogs can save and edit tasks of this type.
     */
    public static boolean isEditableTaskType(@NotNull DBTTaskType type) {
        return SAVED_TASK_TYPES.contains(type.getId());
    }

    /**
     * Tasks of this type run a native client tool (any native tool task type, also those saved by older versions such as
     * pgDatabaseBackupAll): the handler runs the connection's client home.
     */
    public static boolean isNativeToolTask(@NotNull DBTTaskType type) {
        Class<?> handlerClass = type.getHandlerClass();
        return handlerClass != null && AbstractNativeToolHandler.class.isAssignableFrom(handlerClass);
    }

    /**
     * The connection a native tool task runs on, from the settings it was saved with (no connecting).
     */
    @Nullable
    public static DBPDataSourceContainer getTaskConnection(@NotNull DBTTask task) {
        // The key AbstractNativeToolSettings.saveSettings writes
        Object id = task.getProperties().get("dataSource"); //$NON-NLS-1$
        return id instanceof String dataSourceId ? task.getProject().getDataSourceRegistry().getDataSource(dataSourceId) : null;
    }

    /**
     * The connection's client home when it is a local client. Without one the handler would take the first location of the
     * driver, which may be a client that is downloaded on first use: a saved task must not run then.
     */
    @Nullable
    public static DBPNativeClientLocation findConnectionClient(@NotNull DBPDataSourceContainer container) {
        String clientHomeId = container.getConnectionConfiguration().getClientHomeId();
        if (clientHomeId == null) {
            return null;
        }
        for (DBPNativeClientLocation location : findLocalClients(container.getDriver())) {
            if (location.getName().equals(clientHomeId)) {
                return location;
            }
        }
        return null;
    }

    /**
     * Reads the settings of a saved task as its handler does: finds the database objects by their IDs (this connects).
     *
     * @throws DBException the task type is not one of the dialogs' types, or its objects are gone
     */
    @NotNull
    public static AbstractNativeToolSettings<?> loadTaskSettings(@NotNull DBRProgressMonitor monitor, @NotNull DBTTask task) throws DBException {
        DBPProject project = task.getProject();
        String typeId = task.getType().getId();
        AbstractNativeToolSettings<?> settings = switch (typeId) {
            case PostgreSQLTasks.TASK_DATABASE_BACKUP -> new PostgreDatabaseBackupSettings(project);
            case PostgreSQLTasks.TASK_DATABASE_RESTORE -> new PostgreDatabaseRestoreSettings(project);
            case PostgreSQLTasks.TASK_SCRIPT_EXECUTE -> new PostgreScriptExecuteSettings(project);
            case MySQLTasks.TASK_DATABASE_BACKUP -> new MySQLExportSettings(project);
            case MySQLTasks.TASK_DATABASE_RESTORE, MySQLTasks.TASK_SCRIPT_EXECUTE -> {
                // As MySQLScriptExecuteHandler: the task type decides between a restore and a script run
                MySQLScriptExecuteSettings my = new MySQLScriptExecuteSettings(project);
                my.setImport(MySQLTasks.TASK_DATABASE_RESTORE.equals(typeId));
                yield my;
            }
            case OracleTasks.TASK_SCRIPT_EXECUTE -> new OracleScriptExecuteSettings(project);
            default -> throw new DBException("Task type " + typeId + " can't be edited"); //$NON-NLS-1$ //$NON-NLS-2$
        };
        settings.loadSettings(new MonitorRunnableContext(monitor), new TaskPreferenceStore(task));
        if (settings.getDataSourceContainer() == null) {
            throw new DBException("The connection of task '" + task.getName() + "' was not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return settings;
    }

    /**
     * The database object a saved task runs on (a PostgreSQL database, MySQL/MariaDB catalog, Oracle schema or connection).
     */
    @Nullable
    public static DBSObject getTaskTarget(@NotNull AbstractNativeToolSettings<?> settings) {
        if (!settings.getDatabaseObjects().isEmpty()) {
            return settings.getDatabaseObjects().get(0);
        }
        if (settings instanceof OracleScriptExecuteSettings && settings.getDataSourceContainer().getDataSource() instanceof OracleDataSource oracle) {
            return oracle;
        }
        return null;
    }

    /**
     * Saves the settings as a task: a new task with the name, or the settings (and the name) of an existing task.
     * No passwords are saved (the tool account fields stay empty).
     *
     * @throws DBException another task has the name
     */
    @NotNull
    public static DBTTask saveTask(
        @NotNull AbstractNativeToolSettings<?> settings,
        @NotNull String name,
        @Nullable DBTTask existing
    ) throws DBException {
        DBTTaskManager taskManager = settings.getDataSourceContainer().getProject().getTaskManager();
        Map<String, Object> properties = new LinkedHashMap<>();
        settings.saveSettings(new MonitorRunnableContext(new VoidProgressMonitor()), new TaskPreferenceStore(properties));
        DBTTask other = taskManager.getTaskByName(name);
        if (other != null && other != existing) {
            throw new DBException("A task named '" + name + "' already exists"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DBTTask task;
        if (existing != null) {
            task = existing;
            if (task instanceof DBPNamedObject2 named) {
                named.setName(name);
            }
            task.setProperties(properties);
        } else {
            String typeId = getTaskTypeId(settings);
            DBTTaskType type = taskManager.getRegistry().getTaskType(typeId);
            if (type == null) {
                throw new DBException("Task type " + typeId + " is not registered"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            task = taskManager.createTask(type, name, null, null, properties);
        }
        // Adds a new task to the project's task list and writes the tasks file
        taskManager.updateTaskConfiguration(task);
        return task;
    }

    /**
     * Runs the backup, restore or script in the calling thread: saves the settings into a temporary task, as the SWT wizards do, and
     * runs the task type's handler. The handler writes the command line (password masked), the tool's output and its own log
     * to {@code logStream}. The monitor's cancel stops the tool process.
     *
     * @return true when pg_restore exited with code 1: it reports any error that way, a failed login too, and the restore
     * may be incomplete (the model handler would only show a warning toast and report success)
     * @throws Exception the handler or the tool failed
     */
    public static boolean execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AbstractNativeToolSettings<?> settings,
        @NotNull String title,
        @NotNull PrintStream logStream
    ) throws Exception {
        DBPProject project = settings.getDataSourceContainer().getProject();
        String taskTypeId = getTaskTypeId(settings);
        DBTTaskType taskType = project.getTaskManager().getRegistry().getTaskType(taskTypeId);
        if (taskType == null) {
            throw new DBException("Task type " + taskTypeId + " is not registered"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DBTTask task = project.getTaskManager().createTemporaryTask(taskType, title);
        MonitorRunnableContext context = new MonitorRunnableContext(monitor);
        // As the SWT wizards: the settings are saved into the task and the handler loads them from it
        Map<String, Object> properties = new LinkedHashMap<>();
        settings.saveSettings(context, new TaskPreferenceStore(properties));
        task.setProperties(properties);
        Throwable[] error = new Throwable[1];
        DBTTaskExecutionListener listener = new DBTTaskExecutionListener() {
            @Override
            public void taskStarted(@Nullable DBTTask task) {
            }

            @Override
            public void taskFinished(@Nullable DBTTask task, @Nullable Object result, @Nullable Throwable e, @Nullable Object s) {
                error[0] = e;
            }

            @Override
            public void subTaskFinished(@Nullable DBTTask task, @Nullable Throwable e, @Nullable Object s) {
            }
        };
        DBTTaskHandler handler = PostgreSQLTasks.TASK_DATABASE_RESTORE.equals(taskTypeId)
            ? new CheckedPostgreRestore() : taskType.createHandler();
        handler.executeTask(context, task, Locale.getDefault(), log, logStream, listener);
        if (error[0] != null) {
            if (error[0] instanceof Exception e) {
                throw e;
            }
            throw new DBException("Native tool failed", error[0]); //$NON-NLS-1$
        }
        return handler instanceof CheckedPostgreRestore restoreHandler && restoreHandler.errorsReported;
    }

    /**
     * The handler of the PostgreSQL restore task type that remembers an exit code 1 of pg_restore (errors occurred)
     * instead of passing it to the model handler, which would only show a warning toast and report success.
     */
    private static final class CheckedPostgreRestore extends PostgreDatabaseRestoreHandler {
        private volatile boolean errorsReported;

        @Override
        public void validateErrorCode(int exitCode) throws IOException {
            if (exitCode == 1) {
                errorsReported = true;
            } else {
                super.validateErrorCode(exitCode);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Preview handlers: the command line building of the model handlers is protected

    private static final class PreviewPostgreBackup extends PostgreDatabaseBackupHandler {
        @NotNull
        List<String> preview(@NotNull PostgreDatabaseBackupSettings settings, @NotNull PostgreDatabaseBackupInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewPostgreRestore extends PostgreDatabaseRestoreHandler {
        @NotNull
        List<String> preview(@NotNull PostgreDatabaseRestoreSettings settings, @NotNull PostgreDatabaseRestoreInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewMySQLExport extends MySQLDatabaseExportHandler {
        @NotNull
        List<String> preview(@NotNull MySQLExportSettings settings, @NotNull MySQLDatabaseExportInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewMySQLRestore extends MySQLScriptExecuteHandler {
        @NotNull
        List<String> preview(@NotNull MySQLScriptExecuteSettings settings, @NotNull MySQLCatalog catalog) throws IOException {
            return getCommandLine(settings, catalog);
        }
    }

    private static final class PreviewPostgreScript extends PostgreScriptExecuteHandler {
        @NotNull
        List<String> preview(@NotNull PostgreScriptExecuteSettings settings, @NotNull PostgreDatabase database) throws IOException {
            return getCommandLine(settings, database);
        }
    }

    private static final class PreviewOracleScript extends OracleScriptExecuteHandler {
        @NotNull
        List<String> preview(@NotNull OracleScriptExecuteSettings settings) throws IOException {
            // The data source argument is not used to build the command line
            return getCommandLine(settings, null);
        }
    }
}
