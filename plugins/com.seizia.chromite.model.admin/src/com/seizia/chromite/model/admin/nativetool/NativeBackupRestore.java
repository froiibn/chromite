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
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupHandler;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreHandler;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreSQLTasks;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.connection.DBPNativeClientLocationManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.MonitorRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskExecutionListener;
import com.seizia.chromite.model.task.DBTTaskHandler;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.registry.driver.RemoteNativeClientLocation;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PostgreSQL and MySQL/MariaDB backup and restore with the local client tools (pg_dump, pg_restore, psql, mysqldump, mysql)
 * through the native tool tasks of the model (tasks.native, ext.postgresql, ext.mysql).
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
 *     ({@link RemoteNativeClientLocation}, 처음 쓸 때 원격 저장소에서 받음)는 빼서, 묻지 않고 클라이언트를 내려받지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: NativeBackupRestoreDialog의 모델 부분(클라이언트 목록, 작업 형식, 명령줄, 처리기 실행, pg_restore 종료 코드 1)을
 *   옮겨 Chromite Agent 백업·복원 도구와 함께 씀
 */
public final class NativeBackupRestore {

    private static final Log log = Log.getLog(NativeBackupRestore.class);

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
        if (settings instanceof MySQLScriptExecuteSettings) {
            return MySQLTasks.TASK_DATABASE_RESTORE;
        }
        throw new IllegalArgumentException("Not a database backup or restore: " + settings.getClass().getName());
    }

    /**
     * The command line the handler will run, quoted for display; a MySQL backup or restore adds the output or input
     * redirection that the handler does with streams. No password is in it.
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
     * Runs the backup or restore in the calling thread: saves the settings into a temporary task, as the SWT wizards do, and
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
}
