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
package com.seizia.chromite.tools.transfer.processor;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProcessDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.ui.UIServiceShellCommands;
import com.seizia.chromite.tools.transfer.IDataTransferEventProcessor;
import com.seizia.chromite.tools.transfer.internal.DTMessages;
import com.seizia.chromite.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data transfer "execute process" step: runs a shell command when the transfer finishes.
 *
 * 변경 이력:
 * - 2026-11-07 명령 승인: 승인 서비스가 없으면 승인 없이 실행하던 것을 {@link UIServiceShellCommands#validate}로 바꿔
 *   실행하지 않게 함(fail closed). 연결에 묶이지 않는 단계라 승인을 기억하지 않고 매번 묻는다. 대화상자 문구를 NLS로 옮김
 *   (docs/chromite/shell-command-approval.md)
 */
public class ExecuteCommandEventProcessor implements IDataTransferEventProcessor<StreamTransferConsumer> {
    public static final String ID = "executeCommand";
    public static final String PROP_COMMAND = "command";
    public static final String PROP_WORKING_DIRECTORY = "workingDirectory";

    @Override
    public void processEvent(@NotNull DBRProgressMonitor monitor, @NotNull Event event, @NotNull StreamTransferConsumer consumer, @Nullable DBTTask task, @NotNull Map<String, Object> processorSettings) throws DBException {
        final String commandLine = consumer.translatePattern(CommonUtils.toString(processorSettings.get(PROP_COMMAND)));
        final String workingDirectory = CommonUtils.toString(processorSettings.get(PROP_WORKING_DIRECTORY));

        final DBRShellCommand command = new DBRShellCommand(commandLine);
        command.setWorkingDirectory(workingDirectory);

        Map<String, String> approvalContext = new LinkedHashMap<>();
        if (task != null) {
            approvalContext.put(DTMessages.data_transfer_shell_command_context_task, task.getName());
        }
        approvalContext.put(ModelMessages.model_shell_command_context_event, DTMessages.data_transfer_shell_command_event_finished);
        // No approval scope: the step is not tied to a connection, so the user is asked on every run
        UIServiceShellCommands.validate(command, null, approvalContext);

        final DBRProcessDescriptor processDescriptor = new DBRProcessDescriptor(command);
        processDescriptor.execute();
    }
}
