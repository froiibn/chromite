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

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.ai.tools.AgentTool;
import com.seizia.chromite.model.ai.tools.DbFamily;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.connection.DBPDriver;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

/**
 * Regression tests of the tools one Chromite Agent request sends ({@link AIAgentToolSelection#select}): tools that
 * cannot work on the conversation's connection are left out and named in the system prompt instead.
 *
 * 설계: 빠지는 것은 연결의 DB 종류를 지원하지 않는 도구와 정책이 그 연결에서 막는 도구(읽기 전용 연결의 DB 쓰기, 운영 연결의 쓰기)다. 연결
 * 인수가 없는 도구와 연결이 없는 대화는 그대로 모두 보낸다. 정책은 시험 작업 공간의 기본 규칙(파일 없음)을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Chromite Agent")
 * - 2026-11-12 "도구를 모두 보냄" 설정이 없어져 그 가정을 지움 (앱 변경 #64)
 */
public class AIAgentToolSelectionTest extends ChromiteUnitTest {

    @Test
    public void conversationWithoutConnectionSendsEverything() {
        List<AIFunctionDescriptor> all = List.of(tool("listConnections", false, AIToolAccess.READ, true), tool("runSql", true, AIToolAccess.WRITE, true));
        AIAgentToolSelection.Selection selection = AIAgentToolSelection.select(null, all);
        Assertions.assertEquals(all, selection.tools());
        Assertions.assertTrue(selection.otherDatabases().isEmpty());
        Assertions.assertTrue(selection.blocked().isEmpty());
    }

    @Test
    public void toolsOfOtherDatabasesAndBlockedWritesAreLeftOut() {
        AIFunctionDescriptor noConnection = tool("formatSql", false, AIToolAccess.READ, false);
        AIFunctionDescriptor postgresRead = tool("getLocks", true, AIToolAccess.READ, true);
        AIFunctionDescriptor sqlServerOnly = tool("getAgentJobs", true, AIToolAccess.READ, false);
        AIFunctionDescriptor databaseWrite = tool("killSession", true, AIToolAccess.WRITE, true);
        AIFunctionDescriptor fileWrite = tool("saveScript", true, AIToolAccess.WRITE, true);
        Mockito.when(fileWrite.getWrites()).thenReturn(AIToolWrites.FILE);
        List<AIFunctionDescriptor> all = List.of(noConnection, postgresRead, sqlServerOnly, databaseWrite, fileWrite);

        AIAgentToolSelection.Selection development = AIAgentToolSelection.select(postgres("ai-select-dev", DBPConnectionType.DEV, false), all);
        Assertions.assertEquals(List.of(noConnection, postgresRead, databaseWrite, fileWrite), development.tools());
        Assertions.assertEquals(List.of("getAgentJobs"), development.otherDatabases());
        Assertions.assertTrue(development.blocked().isEmpty());

        // A read-only connection refuses database writes only
        AIAgentToolSelection.Selection readOnly = AIAgentToolSelection.select(postgres("ai-select-ro", DBPConnectionType.DEV, true), all);
        Assertions.assertEquals(List.of("killSession"), readOnly.blocked());
        Assertions.assertTrue(readOnly.tools().contains(fileWrite));

        // A production connection blocks write tools unless a rule for the connection allows them
        AIAgentToolSelection.Selection production = AIAgentToolSelection.select(postgres("ai-select-prod", DBPConnectionType.PROD, false), all);
        Assertions.assertEquals(List.of("killSession", "saveScript"), production.blocked());
        Assertions.assertEquals(List.of(noConnection, postgresRead), production.tools());
    }

    @NotNull
    private static AIFunctionDescriptor tool(@NotNull String id, boolean connectionArgument, @NotNull AIToolAccess access, boolean supportsPostgres) {
        AIFunctionDescriptor tool = Mockito.mock(AIFunctionDescriptor.class);
        Mockito.when(tool.getId()).thenReturn(id);
        Mockito.when(tool.getAccess()).thenReturn(access);
        Mockito.when(tool.getWrites()).thenReturn(AIToolWrites.DATABASE);
        if (connectionArgument) {
            Mockito.when(tool.getParameter(AIToolConnections.PARAM_CONNECTION)).thenReturn(Mockito.mock(AIFunctionParameter.class));
        }
        AgentTool instance = Mockito.mock(AgentTool.class);
        Mockito.when(instance.supports(Mockito.eq(tool), Mockito.any(DbFamily.class)))
            .thenAnswer(invocation -> supportsPostgres || invocation.getArgument(1) != DbFamily.POSTGRESQL);
        Mockito.when(tool.getInstance()).thenReturn(instance);
        return tool;
    }

    @NotNull
    private static DBPDataSourceContainer postgres(@NotNull String id, @NotNull DBPConnectionType type, boolean readOnly) {
        DBPDriver driver = Mockito.mock(DBPDriver.class);
        Mockito.when(driver.getProviderId()).thenReturn("postgresql");
        Mockito.when(driver.getId()).thenReturn("postgres-jdbc");
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setConnectionType(new DBPConnectionType(type));
        DBPDataSourceContainer container = Mockito.mock(DBPDataSourceContainer.class);
        Mockito.when(container.getId()).thenReturn(id);
        Mockito.when(container.getDriver()).thenReturn(driver);
        Mockito.when(container.getConnectionConfiguration()).thenReturn(configuration);
        Mockito.when(container.isConnectionReadOnly()).thenReturn(readOnly);
        return container;
    }
}
