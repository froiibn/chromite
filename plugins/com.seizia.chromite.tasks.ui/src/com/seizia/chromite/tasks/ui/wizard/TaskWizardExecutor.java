/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.tasks.ui.wizard;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskHandler;
import com.seizia.chromite.model.task.DBTaskUtils;
import com.seizia.chromite.tasks.ui.TaskFeatures;
import org.jkiss.utils.CommonUtils;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Map;

public class TaskWizardExecutor extends TaskProcessorUI {

    private final Log log;
    private PrintStream logWriter;

    public TaskWizardExecutor(@NotNull DBRRunnableContext staticContext, @NotNull DBTTask task, @NotNull Log log, @NotNull PrintStream logWriter) {
        super(staticContext, task);
        this.log = log;
        this.logWriter = logWriter;
    }

    @Override
    protected boolean isShowFinalMessage() {
        return CommonUtils.getBoolean(getTask().getProperties().get("showFinalMessage"), true);
    }

    @Override
    protected void runTask() throws DBException {
        if (DBTaskUtils.confirmTask(getTask(), log, logWriter)) {
            TaskFeatures.TASKS_EXECUTE.use(Map.of("type", getTask().getType().getId()));

            executeTaskWithHistory(() -> {
                DBTTaskHandler handlerTransfer = getTask().getType().createHandler();
                handlerTransfer.executeTask(this, getTask(), Locale.getDefault(), log, logWriter, this);
            });
        }
    }

}
