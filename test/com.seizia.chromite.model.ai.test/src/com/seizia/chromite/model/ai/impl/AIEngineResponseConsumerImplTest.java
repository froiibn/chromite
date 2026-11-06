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
package com.seizia.chromite.model.ai.impl;

import com.seizia.chromite.model.ai.AIChatConversation;
import com.seizia.chromite.model.ai.AIChatResponseConsumer;
import com.seizia.chromite.model.ai.AIMessageMeta;
import com.seizia.chromite.model.ai.AIUsage;
import com.seizia.chromite.model.ai.engine.AIEngine;
import com.seizia.chromite.model.ai.engine.AIEngineResponseChunk;
import com.seizia.chromite.model.ai.registry.AIEngineDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

public class AIEngineResponseConsumerImplTest {
    @Test
    public void cancellationPreservesUnknownUsage() {
        Assertions.assertNull(cancelWithUsage(null).usage());
    }

    @Test
    public void cancellationPreservesReportedUsage() {
        AIUsage usage = new AIUsage(10, 2, 1, 0);
        Assertions.assertEquals(usage, cancelWithUsage(usage).usage());
    }

    private static AIMessageMeta cancelWithUsage(AIUsage usage) {
        AIChatResponseConsumer listener = Mockito.mock(AIChatResponseConsumer.class);
        AIChatConversation conversation = Mockito.mock(AIChatConversation.class);
        Mockito.when(conversation.getState()).thenReturn(AIChatConversation.State.CANCELED);
        AIEngine<?> engine = Mockito.mock(AIEngine.class, Mockito.RETURNS_DEEP_STUBS);
        AIEngineResponseConsumerImpl consumer = new AIEngineResponseConsumerImpl(
            listener,
            Mockito.mock(DBRProgressMonitor.class),
            engine,
            conversation,
            Mockito.mock(AIEngineDescriptor.class),
            calls -> { }
        );
        consumer.usage(usage);
        consumer.nextChunk(new AIEngineResponseChunk(List.of("text")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AIMessageMeta>> meta = ArgumentCaptor.forClass(List.class);
        Mockito.verify(listener).complete(meta.capture(), Mockito.eq(true), Mockito.eq(true));
        Mockito.verify(listener, Mockito.never()).nextMessageChunk(Mockito.anyString());
        return meta.getValue().getFirst();
    }
}
