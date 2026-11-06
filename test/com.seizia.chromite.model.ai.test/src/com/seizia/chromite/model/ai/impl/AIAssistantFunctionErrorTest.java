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
package com.seizia.chromite.model.ai.impl;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.AIChatConversation;
import com.seizia.chromite.model.ai.AIChatRequest;
import com.seizia.chromite.model.ai.AIChatResponseConsumer;
import com.seizia.chromite.model.ai.AIChatSession;
import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.AIPromptGenerator;
import com.seizia.chromite.model.ai.AIToolboxManager;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AIAssistantFunctionErrorTest extends ChromiteUnitTest {
    @Test
    public void failedFunctionStopsRequestAndFinishesConversation() throws Exception {
        AIAssistantImpl assistant = Mockito.spy(new AIAssistantImpl(Mockito.mock(DBPWorkspace.class)));
        AIToolboxManager toolboxManager = Mockito.mock(AIToolboxManager.class);
        Mockito.doReturn(toolboxManager).when(assistant).getToolboxManager();
        AIFunctionDescriptor function = Mockito.mock(AIFunctionDescriptor.class);
        Mockito.when(function.getType()).thenReturn(AIFunctionType.INFORMATION);
        AIFunctionCall call = new AIFunctionCall("test_function", Map.of());
        call.setFunction(function);
        DBException failure = new DBException("Function failed");
        Mockito.doReturn(new AIFunctionResult(AIFunctionType.INFORMATION, "Function failed", null, failure))
            .when(assistant).callFunction(Mockito.any(), Mockito.eq(call));

        AIChatSession session = Mockito.mock(AIChatSession.class);
        AIChatConversation conversation = Mockito.mock(AIChatConversation.class);
        Mockito.when(conversation.isActive()).thenReturn(true);
        AIChatResponseConsumer listener = Mockito.mock(AIChatResponseConsumer.class);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Mockito.doAnswer(invocation -> {
            finished.complete(null);
            return null;
        }).when(listener).complete(Mockito.anyList(), Mockito.eq(true), Mockito.eq(false));
        AIChatRequest request = new AIChatRequest(null, List.of(), null);

        Method processFunctionCalls = AIAssistantImpl.class.getDeclaredMethod("processFunctionCalls",
            AIChatSession.class, AIChatConversation.class, AIChatResponseConsumer.class,
            AIFunctionContext.class, AIChatRequest.class, List.class);
        processFunctionCalls.setAccessible(true);
        processFunctionCalls.invoke(assistant, session, conversation, listener,
            new AIFunctionContext(new VoidProgressMonitor(), null, Mockito.mock(AIPromptGenerator.class)),
            request, List.of(call));

        finished.get(5, TimeUnit.SECONDS);
        var events = Mockito.inOrder(listener);
        events.verify(listener).processFunctionCall(Mockito.argThat(message -> message.getError() == failure));
        events.verify(listener).error(failure);
        events.verify(listener).complete(Mockito.anyList(), Mockito.eq(true), Mockito.eq(false));
        Mockito.verify(assistant, Mockito.never()).generateTextStream(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any(), Mockito.any());
    }

    @Test
    public void failedFollowUpRequestFinishesConversation() throws Exception {
        AIAssistantImpl assistant = Mockito.spy(new AIAssistantImpl(Mockito.mock(DBPWorkspace.class)));
        Mockito.doReturn(Mockito.mock(AIToolboxManager.class)).when(assistant).getToolboxManager();
        AIFunctionDescriptor function = Mockito.mock(AIFunctionDescriptor.class);
        Mockito.when(function.getType()).thenReturn(AIFunctionType.INFORMATION);
        AIFunctionCall call = new AIFunctionCall("test_function", Map.of());
        call.setFunction(function);
        Mockito.doReturn(new AIFunctionResult(AIFunctionType.INFORMATION, "Function succeeded"))
            .when(assistant).callFunction(Mockito.any(), Mockito.eq(call));
        DBException failure = new DBException("Authentication expired");
        Mockito.doThrow(failure).when(assistant).generateTextStream(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any(), Mockito.any());

        AIChatSession session = Mockito.mock(AIChatSession.class);
        AIChatConversation conversation = Mockito.mock(AIChatConversation.class);
        Mockito.when(conversation.isActive()).thenReturn(true);
        AIChatResponseConsumer listener = Mockito.mock(AIChatResponseConsumer.class);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Mockito.doAnswer(invocation -> {
            finished.complete(null);
            return null;
        }).when(listener).complete(Mockito.anyList(), Mockito.eq(true), Mockito.eq(false));

        Method processFunctionCalls = AIAssistantImpl.class.getDeclaredMethod("processFunctionCalls",
            AIChatSession.class, AIChatConversation.class, AIChatResponseConsumer.class,
            AIFunctionContext.class, AIChatRequest.class, List.class);
        processFunctionCalls.setAccessible(true);
        processFunctionCalls.invoke(assistant, session, conversation, listener,
            new AIFunctionContext(new VoidProgressMonitor(), null, Mockito.mock(AIPromptGenerator.class)),
            new AIChatRequest(null, List.of(), null), List.of(call));

        finished.get(5, TimeUnit.SECONDS);
        Mockito.verify(listener).error(failure);
    }
}
