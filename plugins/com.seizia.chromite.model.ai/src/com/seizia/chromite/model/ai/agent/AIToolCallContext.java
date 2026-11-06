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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIAssistant;
import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIPromptGenerator;
import com.seizia.chromite.model.ai.engine.AIDatabaseContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Function context of an agent tool call: the tool, the caller and the resolved connection.
 *
 * 설계: 기존 {@code AIFunction}의 호출 형식(문맥 + 인수)을 그대로 쓰면서, 도구가 자기 설명자(진단 SQL 자원 이름)와
 * 실행기가 찾아 둔 연결을 받을 수 있게 {@link AIFunctionContext}를 넓혔다. 채팅에서 오면 채팅의 데이터베이스 문맥과 프롬프트를 그대로 넘긴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md)
 *
 * @see com.seizia.chromite.model.ai.AIFunction
 */
public class AIToolCallContext extends AIFunctionContext {

    private static final AIPromptGenerator NO_PROMPT = new AIPromptGenerator() {
        @NotNull
        @Override
        public String generatorId() {
            return "agent-tool"; //$NON-NLS-1$
        }

        @NotNull
        @Override
        public String build(@NotNull AIAssistant assistant, @Nullable AIDatabaseContext context) {
            return "";
        }
    };

    @NotNull
    private final AIFunctionDescriptor tool;
    @NotNull
    private final AIToolClient client;
    @Nullable
    private final DBPDataSourceContainer connection;

    public AIToolCallContext(
        @NotNull DBRProgressMonitor monitor,
        @Nullable AIFunctionContext chatContext,
        @NotNull AIFunctionDescriptor tool,
        @NotNull AIToolClient client,
        @Nullable DBPDataSourceContainer connection
    ) {
        super(
            monitor,
            chatContext == null ? null : chatContext.getContext(),
            chatContext == null ? NO_PROMPT : chatContext.getPrompt()
        );
        this.tool = tool;
        this.client = client;
        this.connection = connection;
    }

    @NotNull
    public AIFunctionDescriptor getTool() {
        return tool;
    }

    @NotNull
    public AIToolClient getClient() {
        return client;
    }

    /**
     * The connection named by the {@code connection} argument (or the chat's current connection), or null.
     */
    @Nullable
    public DBPDataSourceContainer getConnection() {
        return connection;
    }
}
