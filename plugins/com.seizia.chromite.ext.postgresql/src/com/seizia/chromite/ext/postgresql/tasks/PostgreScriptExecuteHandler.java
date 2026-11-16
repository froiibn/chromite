/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.postgresql.tasks;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.fs.DBFUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.registry.task.TaskPreferenceStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PostgreScriptExecuteHandler extends PostgreNativeToolHandler<PostgreScriptExecuteSettings, DBSObject, PostgreDatabase> {

    @Override
    public Collection<PostgreDatabase> getRunInfo(PostgreScriptExecuteSettings settings) {
        return Collections.singletonList(settings.getDatabase());
    }

    @Override
    protected PostgreScriptExecuteSettings createTaskSettings(DBRRunnableContext context, DBTTask task) throws DBException {
        PostgreScriptExecuteSettings settings = new PostgreScriptExecuteSettings(task.getProject());
        settings.loadSettings(context, new TaskPreferenceStore(task));

        return settings;
    }

    @Override
    protected boolean needsModelRefresh() {
        return true;
    }

    @Override
    public boolean isVerbose() {
        return false;
    }

    @Override
    public void fillProcessParameters(PostgreScriptExecuteSettings settings, PostgreDatabase arg, List<String> cmd) throws IOException {
        super.fillProcessParameters(settings, arg, cmd);

        if (arg.getDataSource().isServerVersionAtLeast(9, 5)) {
            cmd.add("--echo-errors"); //$NON-NLS-1$
        }
    }

    @Override
    protected boolean isExportWizard() {
        return false;
    }

    @Override
    protected List<String> getCommandLine(PostgreScriptExecuteSettings settings, PostgreDatabase arg) throws IOException {
        List<String> cmd = new ArrayList<>();
        fillProcessParameters(settings, arg, cmd);

        cmd.add(arg.getName());

        return cmd;
    }

    @Override
    protected boolean isLogInputStream() {
        return true;
    }

    @Override
    protected void startProcessHandler(
        DBRProgressMonitor monitor,
        DBTTask task,
        PostgreScriptExecuteSettings settings,
        PostgreDatabase arg,
        ProcessBuilder processBuilder,
        Process process,
        Log log
    ) throws IOException, DBException {
        final Path inputFile = DBFUtils.resolvePathFromString(monitor, task.getProject(), settings.getInputFile());
        if (!Files.exists(inputFile)) {
            throw new IOException("File '" + inputFile + "' doesn't exist");
        }
        super.startProcessHandler(monitor, task, settings, arg, processBuilder, process, log);
        new BinaryFileTransformerJob(monitor, task, inputFile, process.getOutputStream(), log).start();
    }

}
