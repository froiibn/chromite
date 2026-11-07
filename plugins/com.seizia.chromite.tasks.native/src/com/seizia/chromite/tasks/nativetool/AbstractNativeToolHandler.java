/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.tasks.nativetool;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.access.DBAAuthCredentials;
import com.seizia.chromite.model.access.DBAAuthModel;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.connection.DBPNativeClientLocationManager;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNative;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.*;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ProgressStreamReader;
import com.seizia.chromite.runtime.ui.UIServiceShellCommands;
import com.seizia.chromite.runtime.ui.UIServiceSystemAgent;
import com.seizia.chromite.tasks.nativetool.messages.NativeToolMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;

/**
 * Runs a native database tool (dump, restore) for a task.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: {@link DumpCopierJob}이 아무것도 받지 못한 출력 파일(0바이트)을 지움. 도구가 출력 없이 실패하면
 *   빈 파일이 남아 MySQL 백업을 같은 이름으로 다시 실행할 수 없었다 (docs/chromite/swing-backup-restore.md "실패한 백업과 psql 오류")
 * - 2026-11-08 Chromite: 저장된 작업(임시 작업이 아닌 것)은 도구를 시작하기 전에 실제로 실행할 명령줄을 셸 명령 승인
 *   ({@link UIServiceShellCommands#validate})으로 묻는다({@link #approveSavedTaskCommand}). 저장된 작업의 설정은 사용자가 방금 입력한
 *   것이 아니므로 연결 이벤트 명령과 같이 다룬다. 대화상자와 Chromite Agent 도구가 만든 임시 작업은 묻지 않는다(사용자가 그 자리에서
 *   설정을 보고 실행함) (docs/chromite/shell-command-approval.md "저장된 네이티브 도구 작업")
 */
public abstract class AbstractNativeToolHandler<SETTINGS extends AbstractNativeToolSettings<BASE_OBJECT>, BASE_OBJECT extends DBSObject, PROCESS_ARG> implements DBTTaskHandler, DBTTaskImportTransformer {

    private String taskErrorMessage;

    @Override
    public boolean transformTaskState(@NotNull Map<String, Object> state, @NotNull Map<String, String> projectNames) {
        return DBTTaskImportTransformer.remapProjectReference(state, projectNames);
    }

    @Override
    @NotNull
    public DBTTaskRunStatus executeTask(
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBTTask task,
        @NotNull Locale locale,
        @NotNull Log log,
        @NotNull PrintStream logStream,
        @NotNull DBTTaskExecutionListener listener
    ) throws DBException {
        SETTINGS settings = createTaskSettings(runnableContext, task);
        settings.setLogWriter(logStream);
        if (!validateTaskParameters(task, settings, log)) {
            listener.taskFinished(task, null, new InterruptedException("Task parameters validation failed"), settings);
            log.error("Task parameters validation failed");
            return new DBTTaskRunStatus();
        }
        try {
            runnableContext.run(true, true, monitor -> {
                monitor.beginTask(task.getType().getName(), 1);
                monitor.subTask(task.getType().getName());

                Log.setLogWriter(logStream);
                listener.taskStarted(task);
                Throwable error = null;
                try {
                    final boolean executionResult = doExecute(monitor, task, settings, log);
                    if (!executionResult) {
                        error = new DBCException("Task execution failed, reason: " + taskErrorMessage);
                    }
                } catch (Exception e) {
                    error = e;
                } finally {
                    listener.taskFinished(task, null, error, settings);
                    Log.setLogWriter(null);

                    monitor.worked(1);
                    monitor.done();
                }
            });
        } catch (InvocationTargetException e) {
            throw new DBException("Error executing native tool", e.getTargetException());
        } catch (InterruptedException e) {
            // ignore
        }
        return new DBTTaskRunStatus();
    }

    protected boolean isNativeClientHomeRequired() {
        return true;
    }

    protected boolean isMergeProcessStreams() {
        return false;
    }

    protected boolean needsModelRefresh() {
        return true;
    }

    private void validateClientHome(DBRProgressMonitor monitor, SETTINGS settings) throws DBCException {
        DBPDataSourceContainer dataSourceContainer = settings.getDataSourceContainer();
        if (isNativeClientHomeRequired()) {
            String clientHomeId = dataSourceContainer.getConnectionConfiguration().getClientHomeId();
            final DBPDriver driver = dataSourceContainer.getDriver();
            final List<DBPNativeClientLocation> clientLocations = driver.getNativeClientLocations();
            final DBPNativeClientLocationManager locationManager = driver.getNativeClientManager();
            if (locationManager != null) {
                clientLocations.addAll(locationManager.findLocalClientLocations());
            }
            if (clientHomeId == null) {
                if (!clientLocations.isEmpty()) {
                    settings.setClientHome(clientLocations.get(0));
                } else {
                    settings.setClientHome(null);
                }
                if (settings.getClientHome() == null) {
                    throw new DBCException("Client binaries location is not specified");
                }
            } else {
                DBPNativeClientLocation clientHome = DBUtils.findObject(clientLocations, clientHomeId);
                if (clientHome == null) {
                    clientHome = settings.findNativeClientHome(clientHomeId);
                }
                settings.setClientHome(clientHome);
            }
            if (settings.getClientHome() == null) {
                throw new DBCException("Local client home '" + clientHomeId + "' not found");
            }
        }

        DBPNativeClientLocation clientHome = settings.getClientHome();
        if (!isNativeClientHomeRequired() || clientHome == null) {
            return;
        }
        try {
            clientHome.validateFilesPresence(monitor);
        } catch (DBException e) {
            throw new DBCException("Error downloading client file(s)", e);
        } catch (InterruptedException e) {
            // ignore
            throw new DBCException("Client file download interrupted", e);
        }
    }

    public abstract Collection<PROCESS_ARG> getRunInfo(SETTINGS settings);

    public Collection<BASE_OBJECT> getUpdatedObjects(PROCESS_ARG settings) {
        return Collections.emptyList();
    }

    protected abstract SETTINGS createTaskSettings(DBRRunnableContext context, DBTTask task) throws DBException;

    protected boolean validateTaskParameters(DBTTask task, SETTINGS settings, Log log) {
        return true;
    }

    abstract protected java.util.List<String> getCommandLine(SETTINGS settings, PROCESS_ARG arg) throws IOException;

    public abstract void fillProcessParameters(SETTINGS settings, PROCESS_ARG arg, List<String> cmd) throws IOException;

    protected void setupProcessParameters(DBRProgressMonitor monitor, SETTINGS settings, PROCESS_ARG arg, ProcessBuilder process) {
    }

    protected boolean isLogInputStream() {
        return true;
    }

    protected void startProcessHandler(
        DBRProgressMonitor monitor,
        DBTTask task,
        SETTINGS settings,
        PROCESS_ARG arg,
        ProcessBuilder processBuilder,
        Process process,
        Log log
    ) throws IOException, DBException {
        LogReaderJob logReaderJob = new LogReaderJob(
            task,
            settings,
            processBuilder,
            process,
            isLogInputStream());
        logReaderJob.start();
    }

    public boolean executeProcess(
        DBRProgressMonitor monitor,
        DBTTask task,
        SETTINGS settings,
        PROCESS_ARG arg,
        Log log
    ) throws IOException, InterruptedException {
        monitor.beginTask(task.getType().getName(), 1);
        try {
            monitor.subTask("Start native tool " + getClass().getSimpleName());
            final List<String> commandLine = getCommandLine(settings, arg);
            if (!task.isTemporary()) {
                approveSavedTaskCommand(task, settings, commandLine);
            }
            final File execPath = new File(commandLine.get(0));

            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            processBuilder.directory(execPath.getParentFile());
            if (this.isMergeProcessStreams()) {
                processBuilder.redirectErrorStream(true);
            }
            setupProcessParameters(monitor, settings, arg, processBuilder);
            Process process = processBuilder.start();
            startProcessHandler(monitor, task, settings, arg, processBuilder, process, log);



            monitor.subTask("Executing");
            Thread.sleep(100);

            for (; ; ) {
                Thread.sleep(100);
                if (monitor.isCanceled()) {
                    process.destroy();
                }
                try {
                    final int exitCode = process.exitValue();
                    validateErrorCode(exitCode);
                } catch (IllegalThreadStateException e) {
                    // Still running
                    continue;
                }
                break;
            }
            //process.waitFor();
        } catch (IOException e) {
            log.error("IO error: " + e.getMessage());
            throw e;
        } catch (DBException e) {
            log.error("Process error: " + e.getMessage());
            throw new IOException(e);
        } finally {
            monitor.done();
        }
        return CommonUtils.isEmpty(taskErrorMessage);
    }

    /**
     * Asks the user to approve the exact command line of a saved task before the tool starts (the same approval as the
     * connection event commands). The password is not in the command line of the PostgreSQL and MySQL tools; anything
     * that ends with a password is masked as in the run log. A script run shows its script file as an input redirection.
     *
     * @throws InterruptedException the user declined: the run stops as when a task confirmation is declined
     */
    private void approveSavedTaskCommand(@NotNull DBTTask task, @NotNull SETTINGS settings, @NotNull List<String> commandLine)
        throws InterruptedException {
        StringBuilder text = new StringBuilder();
        for (String arg : commandLine) {
            if (NativeToolUtils.isSecureString(settings, arg)) {
                arg = "******"; //$NON-NLS-1$
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(arg.isEmpty() || arg.indexOf(' ') >= 0 ? "\"" + arg + "\"" : arg); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (settings instanceof AbstractScriptExecuteSettings<?> script && !CommonUtils.isEmpty(script.getInputFile())) {
            // A script run gets its script through the standard input, not in the arguments: which file runs is part of what is approved
            text.append(" < ").append(script.getInputFile()); //$NON-NLS-1$
        }
        DBRShellCommand command = new DBRShellCommand(text.toString());
        command.setWorkingDirectory(new File(commandLine.get(0)).getParent());
        Map<String, String> context = new LinkedHashMap<>();
        context.put(ModelMessages.model_shell_command_context_project, task.getProject().getDisplayName());
        context.put(ModelMessages.model_shell_command_context_task, task.getName() + " (" + task.getType().getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        DBPDataSourceContainer container = settings.getDataSourceContainer();
        if (container != null) {
            context.put(ModelMessages.model_shell_command_context_connection, container.getName());
        }
        context.put(ModelMessages.model_shell_command_context_event, ModelMessages.model_shell_command_event_saved_task);
        try {
            UIServiceShellCommands.validate(command, UIServiceShellCommands.getTaskScope(task), context);
        } catch (DBException e) {
            InterruptedException declined = new InterruptedException(e.getMessage());
            declined.initCause(e);
            throw declined;
        }
    }

    public void validateErrorCode(int exitCode) throws IOException {
        if (exitCode != 0) {
            throw new IOException("Process failed (exit code = " + exitCode + "). See error log.");
        }
    }

    protected void notifyToolFinish(String toolName, long workTime) {
        // Notify agent
        UIServiceSystemAgent serviceSystemAgent = DBWorkbench.getService(UIServiceSystemAgent.class);
        if (serviceSystemAgent != null && workTime > serviceSystemAgent.getLongOperationTimeout() * 1000) {
            serviceSystemAgent.notifyAgent(toolName, IStatus.INFO);
        }
    }

    protected boolean doExecute(DBRProgressMonitor monitor, DBTTask task, SETTINGS settings, Log log) throws DBException, InterruptedException {
        validateClientHome(monitor, settings);

        long startTime = System.currentTimeMillis();

        boolean isSuccess = true;
        try {
            for (PROCESS_ARG arg : getRunInfo(settings)) {
                if (monitor.isCanceled()) break;
                if (!executeProcess(monitor, task, settings, arg, log)) {
                    isSuccess = false;
                }
            }

            boolean refreshObjects = isSuccess && !monitor.isCanceled();
            var navigatorModel = task.getProject().getNavigatorModel();
            if (navigatorModel != null && refreshObjects && needsModelRefresh()) {
                // Refresh navigator node (script execution can change everything inside)
                for (BASE_OBJECT object : settings.getDatabaseObjects()) {
                    final DBNDatabaseNode node = navigatorModel.findNode(object);
                    if (node != null) {
                        node.refreshNode(monitor, this);
                    }
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.error(e);
            throw new DBException("Error executing process", e);
        }
        if (monitor.isCanceled()) {
            throw new InterruptedException();
        }

        long workTime = System.currentTimeMillis() - startTime;
        notifyToolFinish(task.getType().getName() + " - " + task.getName() + " has finished", workTime);
        return isSuccess;
    }

    public static abstract class DumpJob extends Thread {
        protected DBRProgressMonitor monitor;
        protected InputStream input;
        protected Path outFile;
        protected Log log;

        protected DumpJob(String name, DBRProgressMonitor monitor, InputStream stream, Path outFile, Log log) {
            super(name);
            this.monitor = monitor;
            this.input = stream;
            this.outFile = outFile;
            this.log = log;
        }

        @Override
        public final void run() {
            try {
                runDump();
            } catch (IOException e) {
                log.error(e);
            }
        }

        protected abstract void runDump()
            throws IOException;
    }

    public static class DumpCopierJob extends DumpJob {
        public DumpCopierJob(DBRProgressMonitor monitor, String name, InputStream stream, Path outFile, Log log) {
            super(name, monitor, stream, outFile, log);
        }

        @Override
        public void runDump() throws IOException {
            monitor.beginTask(getName(), 100);
            long totalBytesDumped = 0;
            long prevStatusUpdateTime = 0;
            byte[] buffer = new byte[10000];
            try {
                NumberFormat numberFormat = NumberFormat.getInstance();

                try (OutputStream output = Files.newOutputStream(outFile)) {
                    for (; ; ) {
                        int count = input.read(buffer);
                        if (count <= 0) {
                            break;
                        }
                        totalBytesDumped += count;
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - prevStatusUpdateTime > 300) {
                            if (!DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
                                monitor.subTask(numberFormat.format(totalBytesDumped) + " bytes");
                            }
                            prevStatusUpdateTime = currentTime;
                        }
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                deleteIfEmpty(outFile, totalBytesDumped, log);
            } finally {
                monitor.done();
            }
        }
    }

    /**
     * A tool that fails before writing anything (bad login, bad option) leaves no empty file behind: it looks like a
     * backup, and the MySQL backup refuses to write into an existing file, so the retry failed too.
     */
    protected static void deleteIfEmpty(@NotNull Path outFile, long bytesWritten, @NotNull Log log) {
        if (bytesWritten > 0) {
            return;
        }
        try {
            Files.deleteIfExists(outFile);
        } catch (IOException e) {
            log.debug("Can't delete empty output file " + outFile, e);
        }
    }

    public static class TextFileTransformerJob extends Thread {
        private final DBRProgressMonitor monitor;
        private final DBTTask task;
        private final OutputStream output;
        private final Path inputFile;
        private final String inputCharset;
        private final String outputCharset;
        private final Log log;

        public TextFileTransformerJob(DBRProgressMonitor monitor, DBTTask task, Path inputFile, OutputStream stream, String inputCharset, String outputCharset, Log log) {
            super(task.getName());
            this.monitor = monitor;
            this.task = task;
            this.output = stream;
            this.inputFile = inputFile;
            this.inputCharset = inputCharset;
            this.outputCharset = outputCharset;
            this.log = log;
        }

        @Override
        public void run() {
            try {
                try (InputStream scriptStream = new ProgressStreamReader(
                    monitor,
                    task.getName(),
                    Files.newInputStream(inputFile),
                    Files.size(inputFile))
                ) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(scriptStream, inputCharset));
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, outputCharset));
                    while (!monitor.isCanceled()) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        writer.println(line);
                        writer.flush();
                    }
                    output.flush();
                } finally {
                    IOUtils.close(output);
                }
            } catch (IOException e) {
                log.error(e);
            } finally {
                monitor.done();
            }
        }
    }

    public static class BinaryFileTransformerJob extends Thread {
        private final DBRProgressMonitor monitor;
        private final DBTTask task;
        private final OutputStream output;
        private final Path inputFile;
        private final Log log;

        public BinaryFileTransformerJob(DBRProgressMonitor monitor, DBTTask task, Path inputFile, OutputStream stream, Log log) {
            super(task.getName());
            this.monitor = monitor;
            this.task = task;
            this.output = stream;
            this.inputFile = inputFile;
            this.log = log;
        }

        @Override
        public void run() {
            try (InputStream scriptStream = new ProgressStreamReader(
                monitor,
                task.getName(),
                Files.newInputStream(inputFile),
                Files.size(inputFile))
            ) {
                byte[] buffer = new byte[100000];
                while (!monitor.isCanceled()) {
                    int readSize = scriptStream.read(buffer);
                    if (readSize < 0) {
                        break;
                    }
                    output.write(buffer, 0, readSize);
                    output.flush();
                }
                output.flush();
            } catch (IOException e) {
                log.error(e);
            } finally {
                try {
                    output.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
    }

    private class LogReaderJob extends Thread {
        private final DBTTask task;
        private final SETTINGS settings;
        private final PrintStream logWriter;
        private final ProcessBuilder processBuilder;
        private final Process input;
        private final boolean isLogInputStream;

        protected LogReaderJob(DBTTask task, SETTINGS settings, ProcessBuilder processBuilder, Process stream, boolean isLogInputStream) {
            super("Log reader for " + task.getName());
            this.task = task;
            this.settings = settings;
            this.logWriter = settings.getLogWriter();
            this.processBuilder = processBuilder;
            this.input = stream;
            this.isLogInputStream = isLogInputStream;
        }

        @Override
        public void run() {
            String lf = GeneralUtils.getDefaultLineSeparator();
            List<String> command = processBuilder.command();

            // Dump command line
            StringBuilder cmdString = new StringBuilder();
            for (String cmd : command) {
                if (NativeToolUtils.isSecureString(settings, cmd)) {
                    cmd = "******";
                }
                if (cmdString.length() > 0) cmdString.append(' ');
                cmdString.append(cmd);
            }
            cmdString.append(lf);

            try {
                logWriter.print(cmdString);

                logWriter.print(
                    NLS.bind(NativeToolMessages.native_tool_handler_log_task, task.getName(), new Date() + lf));
                logWriter.flush();


                if (isLogInputStream) {
                    Thread readInputThread = new Thread("Reading process input stream") {
                        @Override
                        public void run() {
                            try {
                                readStream(input.getInputStream());
                            } catch (IOException e) {
                                logWriter.println(e.getMessage() + lf);
                            }
                        }
                    };
                    readInputThread.start();
                    String errorMessage = readStream(input.getErrorStream());
                    if (!CommonUtils.isEmpty(errorMessage)) {
                        taskErrorMessage = errorMessage;
                    }
                    try {
                        readInputThread.join();
                    } catch (InterruptedException ignore) {
                        // ignore
                    }
                } else {
                    readStream(input.getErrorStream());
                }
            } catch (IOException e) {
                // just skip
                logWriter.println(e.getMessage() + lf);
            } finally {
                logWriter.print(NLS.bind(NativeToolMessages.native_tool_handler_log_finished_task, task.getName(),
                    new Date() + lf));
                logWriter.flush();
            }
        }
        
        private String readStream(@NotNull InputStream inputStream) throws IOException {
            StringBuilder message = new StringBuilder();
            try (Reader reader = new InputStreamReader(inputStream, GeneralUtils.getDefaultConsoleEncoding())) {
                StringBuilder buf = new StringBuilder();
                for (; ; ) {
                    int b = reader.read();
                    if (b == -1) {
                        break;
                    }
                    buf.append((char) b);
                    if (b == '\n') {
                        message.append(buf);
                        logWriter.println(buf);
                        logWriter.flush();
                        buf.setLength(0);
                    }
                    //int avail = input.available();
                }
            }
            return message.toString();
        }
    }

    private class NullReaderJob extends Thread {
        private InputStream input;

        protected NullReaderJob(DBTTask task, InputStream stream) {
            super("Task " + task.getName() + " log reader");
            this.input = stream;
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1000];
                for (; ; ) {
                    int count = input.read(buffer);
                    if (count <= 0) {
                        break;
                    }
                }
            } catch (IOException e) {
                // just skip
            }
        }
    }

    protected String getInputCharset() {
        return GeneralUtils.UTF8_ENCODING;
    }

    protected String getOutputCharset() {
        return GeneralUtils.UTF8_ENCODING;
    }

    protected String getDataSourcePassword(DBRProgressMonitor monitor, SETTINGS settings) {
        // Try to obtain password through auth model (makes sense for IAM-like models)
        String userPassword = null;
        DBPDataSourceContainer dataSourceContainer = settings.getDataSourceContainer();
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration(dataSourceContainer.getActualConnectionConfiguration());
        DBAAuthModel authModel = cfg.getAuthModel();
        if (authModel != AuthModelDatabaseNative.INSTANCE) {
            DBAAuthCredentials credentials = authModel.loadCredentials(dataSourceContainer, cfg);
            try {
                Properties connProperties = new Properties();
                authModel.initAuthentication(monitor, dataSourceContainer.getDataSource(), credentials, cfg, connProperties);
                Object authPassword = connProperties.get(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD);
                if (authPassword != null) {
                    userPassword = CommonUtils.toString(authPassword);
                }
            } catch (DBException e) {
                // ignore
            }
        }
        if (CommonUtils.isEmpty(userPassword)) {
            userPassword = dataSourceContainer.getActualConnectionConfiguration().getUserPassword();
        }
        return userPassword;
    }

}
