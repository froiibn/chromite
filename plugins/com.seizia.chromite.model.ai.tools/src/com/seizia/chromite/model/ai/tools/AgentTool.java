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

import com.google.gson.JsonElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunction;
import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;

import java.util.Map;

/**
 * Base class of agent tools: one class serves a group of tool IDs and dispatches on the called tool's ID.
 *
 * 설계: 확장점은 함수마다 {@code class}를 만들지만(설명자마다 인스턴스 하나), 도구 80여 개를 클래스 80여 개로 나누면 같은 도움 코드가 흩어진다.
 * 그래서 묶음(연결, 메타데이터, 쿼리 등)마다 클래스 하나가 {@link AIToolCallContext#getTool()}의 ID로 갈라 실행한다.
 * 도구는 실행기({@code AIToolExecutor})를 거쳐야만 정책·감사가 적용되므로, 실행기가 만든 {@link AIToolCallContext}가 아니면 거부한다.
 * 어느 DB 종류에서 쓸 수 있는지({@link #supports})는 도구 클래스가 안다(진단 SQL 도구는 SQL 자원의 DB 종류 블록). 채팅 요청에 보낼 도구를
 * 연결의 DB 종류로 고를 때 쓴다(docs/chromite/ai-agent.md "요청 크기"). plugin.xml 속성으로 따로 적으면 SQL 자원과 어긋날 수 있어 코드에 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md)
 * - 2026-11-08 {@link #supports}: 도구가 쓸 수 있는 DB 종류(요청 크기를 줄이는 도구 고르기)
 */
public abstract class AgentTool implements AIFunction {

    @NotNull
    @Override
    public final AIFunctionResult callFunction(
        @NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters
    ) throws DBException {
        if (!(context instanceof AIToolCallContext toolContext)) {
            throw new DBException("Agent tools run only through the agent tool executor");
        }
        JsonElement result = call(toolContext, toolContext.getTool().getId(), parameters);
        return new AIFunctionResult(toolContext.getTool().getType() == null ? AIFunctionType.INFORMATION :
            toolContext.getTool().getType(), AgentJson.toText(result));
    }

    /**
     * Runs the tool.
     */
    @NotNull
    protected abstract JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException;

    /**
     * Whether the tool works on connections of this database family. Tools that work on any connection (the model layer or
     * the driver's planner) keep the default.
     */
    public boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        return true;
    }

    @NotNull
    protected static DBPDataSourceContainer requireConnection(@NotNull AIToolCallContext context) throws AIToolArgumentException {
        DBPDataSourceContainer connection = context.getConnection();
        if (connection == null) {
            throw new AIToolArgumentException("Argument 'connection' is required (connection ID or name, see listConnections)");
        }
        return connection;
    }

    /**
     * The connected data source of the call's connection.
     */
    @NotNull
    protected static DBPDataSource requireDataSource(@NotNull AIToolCallContext context) throws DBException {
        return AgentSql.connect(context.getMonitor(), requireConnection(context));
    }

    @NotNull
    protected static DBException unknownTool(@NotNull String toolId) {
        return new DBException("Tool '" + toolId + "' is not implemented by this class");
    }
}
