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
package com.seizia.chromite.model.ai.commands;

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.*;
import com.seizia.chromite.model.ai.engine.AIDatabaseContext;
import com.seizia.chromite.model.ai.impl.MessageChunk;
import com.seizia.chromite.model.ai.internal.AIMessages;
import com.seizia.chromite.model.ai.prompt.AIPromptGenerateSql;
import com.seizia.chromite.model.ai.registry.AIAssistantRegistry;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCMessageException;
import com.seizia.chromite.model.exec.output.DBCOutputSeverity;
import com.seizia.chromite.model.logical.DBSLogicalDataSource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Set;

/**
 * Control command handler
 */
public class SQLCommandAI implements SQLControlCommandHandler {
    private static final DBCOutputSeverity AI_OUTPUT_SEVERITY = new DBCOutputSeverity() {
        @NotNull
        @Override
        public String getName() {
            return "AI";
        }

        @Override
        public boolean isForced() {
            return true;
        }
    };

    @NotNull
    @Override
    public SQLControlResult handleCommand(@NotNull DBRProgressMonitor monitor, @NotNull SQLControlCommand command, @NotNull SQLScriptContext scriptContext) throws DBException {
        DBPDataSource dataSource = command.getDataSource();
        if (dataSource == null) {
            throw new DBException(AIMessages.ai_command_not_connected);
        }

        String prompt = command.getParameter();
        if (CommonUtils.isEmptyTrimmed(prompt)) {
            throw new DBException(AIMessages.ai_command_empty_prompt);
        }

        AIBaseFeatures.SQL_AI_COMMAND.use();

        final DBSLogicalDataSource lDataSource = new DBSLogicalDataSource(
            command.getDataSourceContainer(), "AI logical wrapper", null);

        DBPDataSourceContainer dataSourceContainer = lDataSource.getDataSourceContainer();
        AIContextSettingsDataSource completionSettings = new AIContextSettingsDataSource(dataSourceContainer);
        if (!completionSettings.isMetaTransferConfirmed()) {
            if (DBWorkbench.getPlatformUI().confirmAction(
                AIMessages.ai_command_confirm_usage_title,
                NLS.bind(AIMessages.ai_command_confirm_usage_message, dataSourceContainer.getName())
            )) {
                completionSettings.setMetaTransferConfirmed(true);
                completionSettings.saveSettings();
            } else {
                throw new DBException(NLS.bind(AIMessages.ai_command_services_restricted, dataSourceContainer.getName()));
            }
        }
        DBCExecutionContext executionContext = scriptContext.getExecutionContext();
        AIUtils.updateScopeSettingsIfNeeded(completionSettings, dataSourceContainer, executionContext);

        AIDatabaseContext.Builder contextBuilder = new AIDatabaseContext.Builder(lDataSource);
        if (executionContext != null) {
            contextBuilder.setExecutionContext(executionContext);
        }
        AIDatabaseScope scope = completionSettings.getScope();
        if (scope != null) {
            contextBuilder.setScope(scope);
        }

        if (scope == AIDatabaseScope.CUSTOM && completionSettings.getCustomObjectIds() != null) {
            contextBuilder.setCustomEntities(
                AITextUtils.loadCustomEntities(
                    monitor,
                    dataSource,
                    Set.of(completionSettings.getCustomObjectIds()))
            );
        }
        AIDatabaseContext dbContext = contextBuilder.build();
        AIPromptGenerateSql promptGenerateSql = new AIPromptGenerateSql();
        promptGenerateSql.setSqlQueriesOnly(true);
        AIFunctionContext fc = new AIFunctionContext(monitor, dbContext, promptGenerateSql);

        monitor.subTask(AIMessages.ai_command_generate_sql);

        AIAssistant assistant = AIAssistantRegistry.getInstance()
            .getAssistant(dataSourceContainer.getProject().getWorkspace());

        AIAssistantResponse result = assistant.generateText(
            monitor,
            AISettingsManager.getStaticSettings().getDefaultConfiguration(),
            fc,
            List.of(AIMessage.userMessage(prompt))
        );
        if (!result.isText()) {
            return SQLControlResult.success();
        }

        monitor.subTask(AIMessages.ai_command_process_generated_sql);

        AISqlFormatter sqlFormatter = AIAssistantRegistry.getInstance().getDescriptor().createSqlFormatter();
        MessageChunk[] messageChunks = AITextUtils.processAndSplitCompletion(
            monitor,
            dbContext,
            sqlFormatter,
            result.getText()
        );

        String script = null;
        StringBuilder messages = new StringBuilder();
        for (MessageChunk chunk : messageChunks) {
            if (chunk instanceof MessageChunk.Code code) {
                script = code.text();
            } else if (chunk instanceof MessageChunk.Text textChunk) {
                messages.append(textChunk.text());
            }
        }

        if (script == null) {
            if (!messages.isEmpty()) {
                throw new DBCMessageException(messages.toString());
            }
            throw new DBCMessageException(NLS.bind(AIMessages.ai_command_empty_response, prompt));
        }

        SQLDialect dialect = SQLUtils.getDialectFromObject(dataSource);
        if (!script.contains("\n") && SQLUtils.isCommentLine(dialect, script)) {
            throw new DBCMessageException(script);
        }

        List<SQLScriptElement> scriptElements = SQLScriptParser.parseScript(dataSource, script);
        if (!AIUtils.confirmExecutionIfNeeded(dataSource, scriptElements, true)) {
            return SQLControlResult.failure();
        }
        AIUtils.disableAutoCommitIfNeeded(
            monitor,
            scriptElements,
            scriptContext.getExecutionContext()
        );

        scriptContext.getOutputWriter().println(AI_OUTPUT_SEVERITY, prompt + " ==> " + script + "\n");

        if (scriptElements.size() == 1) {
            return SQLControlResult.transform(scriptElements.getFirst());
        } else {
            return SQLControlResult.transform(new SQLScript(dataSource, script, scriptElements));
        }
    }
}
