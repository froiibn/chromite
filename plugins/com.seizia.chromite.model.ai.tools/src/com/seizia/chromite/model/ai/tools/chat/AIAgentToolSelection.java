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
package com.seizia.chromite.model.ai.tools.chat;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunction;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolPermission;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.ai.tools.AgentTool;
import com.seizia.chromite.model.ai.tools.DbFamily;
import com.seizia.chromite.model.ai.tools.mcp.AIMcpSettings;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the tool definitions one chat request sends to the model.
 *
 * 설계(docs/chromite/ai-agent.md "요청 크기"):
 * <ul>
 *     <li>요청마다 도구 정의 전체(90개가 넘음)를 보내면 정의만으로 문맥의 큰 몫을 쓴다. 대화의 연결로 쓸 수 없는 도구는 보내지 않는다:
 *     그 연결의 DB 종류를 지원하지 않는 도구({@link AgentTool#supports}, 진단 SQL 도구는 SQL 자원의 DB 종류 블록)와, 정책이 그 연결에서 막는 도구
 *     (실행기와 같은 판정: 읽기 전용 연결의 DB 쓰기 도구, {@link AIToolPolicy#resolve}가 BLOCKED). 막힘 판정은 도구의 정적 분류로 한다. 호출별로
 *     분류가 올라가는 도구(runSql 등)는 정적 분류가 낮아 보내지고, 실행기가 호출마다 다시 판정한다.</li>
 *     <li>연결 인수가 없는 도구(listConnections, formatSql 등)는 늘 보낸다. 대화에 연결이 없으면 모두 보낸다.</li>
 *     <li>보내지 않은 도구 ID는 시스템 프롬프트에 짧게 알린다. 모델이 사용자에게 "그 도구는 이 연결에서 막혀 있다"고 말하거나, 다른 연결의 일이면
 *     대화의 연결을 바꾸라고 할 수 있게 하기 위해서다.</li>
 *     <li>"도구를 모두 보냄" 설정({@link #PREF_SEND_ALL_TOOLS}, 기본 꺼짐)은 고르지 않고 모두 보낸다. 다른 연결의 도구를 한 대화에서 함께 쓸 때를 위한
 *     것이다. MCP 서버의 {@code tools/list}는 연결이 정해지지 않으므로 늘 전체다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (후속 과제 #76 요청 크기)
 */
public final class AIAgentToolSelection {

    private static final Log log = Log.getLog(AIAgentToolSelection.class);

    public static final String PREF_SEND_ALL_TOOLS = "chromite.ai.chat.sendAllTools"; //$NON-NLS-1$

    private static DBPPreferenceStore store;

    /**
     * Tools of one request and the IDs left out.
     *
     * @param otherDatabases tools that do not support the connection's database type
     * @param blocked        tools the policy blocks on the connection (or that write to a read-only connection)
     */
    public record Selection(
        @NotNull List<AIFunctionDescriptor> tools,
        @NotNull List<String> otherDatabases,
        @NotNull List<String> blocked
    ) {
    }

    private AIAgentToolSelection() {
    }

    @NotNull
    private static synchronized DBPPreferenceStore getStore() {
        if (store == null) {
            store = new BundlePreferenceStore(AIMcpSettings.BUNDLE_ID);
            store.setDefault(PREF_SEND_ALL_TOOLS, false);
        }
        return store;
    }

    public static boolean isSendAllTools() {
        return getStore().getBoolean(PREF_SEND_ALL_TOOLS);
    }

    public static void setSendAllTools(boolean sendAll) {
        getStore().setValue(PREF_SEND_ALL_TOOLS, sendAll);
        PrefUtils.savePreferenceStore(getStore());
    }

    /**
     * The tools for a request of a conversation on a connection.
     *
     * @param connection the conversation's connection, or null
     */
    @NotNull
    public static Selection select(@Nullable DBPDataSourceContainer connection, @NotNull List<AIFunctionDescriptor> all) {
        if (connection == null || isSendAllTools()) {
            return new Selection(all, List.of(), List.of());
        }
        DbFamily family = DbFamily.of(connection);
        AIToolPolicy policy = AIToolPolicy.getInstance();
        List<AIFunctionDescriptor> tools = new ArrayList<>(all.size());
        List<String> otherDatabases = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (AIFunctionDescriptor tool : all) {
            if (tool.getParameter(AIToolConnections.PARAM_CONNECTION) == null) {
                tools.add(tool);
                continue;
            }
            if (!supports(tool, family)) {
                otherDatabases.add(tool.getId());
                continue;
            }
            AIToolAccess access = tool.getAccess();
            boolean readOnlyWrite = access.isWrite() && tool.getWrites() == AIToolWrites.DATABASE && connection.isConnectionReadOnly();
            if (readOnlyWrite || policy.resolve(connection, tool, access).permission() == AIToolPermission.BLOCKED) {
                blocked.add(tool.getId());
                continue;
            }
            tools.add(tool);
        }
        return new Selection(tools, otherDatabases, blocked);
    }

    private static boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        try {
            AIFunction instance = tool.getInstance();
            return !(instance instanceof AgentTool agentTool) || agentTool.supports(tool, family);
        } catch (Exception e) {
            // A tool that can't be created fails when called; sending it costs nothing more than before
            log.debug("Can't check the database types of tool " + tool.getId(), e);
            return true;
        }
    }
}
