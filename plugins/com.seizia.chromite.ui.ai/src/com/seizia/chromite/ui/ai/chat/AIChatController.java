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
package com.seizia.chromite.ui.ai.chat;

import org.eclipse.ui.IWorkbenchPartSite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.ai.AIChatConversation;
import com.seizia.chromite.model.ai.AIContextSettings;
import com.seizia.chromite.model.exec.DBCExecutionContext;

/**
 * AI chat controller. Provides info about chat host environment.
 */
public interface AIChatController extends DBPContextProvider {

    String CMD_ATTACH = "com.seizia.chromite.ai.chat.attach";
    String CMD_SEND_PROMPT = "com.seizia.chromite.ai.chat.sendPrompt";
    String CMD_RECORD_AUDIO = "com.seizia.chromite.ai.chat.recordAudio";
    String CMD_FOCUS_PROMPT = "com.seizia.chromite.ai.chat.focusPrompt";
    String CMD_FOCUS_CHAT = "com.seizia.chromite.ai.chat.focusChat";
    String CMD_NEW_CONVERSATION = "com.seizia.chromite.ai.chat.newConversation";
    String CMD_DELETE_CONVERSATION = "com.seizia.chromite.ai.chat.deleteConversation";
    String CMD_OPEN_SETTINGS = "com.seizia.chromite.ai.chat.openSettings";
    String CMD_OPEN_FILTERS = "com.seizia.chromite.ai.chat.openFilters";
    String CMD_DESCRIBE_OBJECT = "com.seizia.chromite.ai.describeObject";
    String CONTEXT_CHAT_CONTROL = "com.seizia.chromite.ui.ai.chat.control";

    int FEATURE_CONTEXT_VIEW            = 1 << 1;
    int FEATURE_PROMPT_VIEW             = 1 << 2;
    int FEATURE_AUDIO_TRANSCRIPT        = 1 << 3;

    int getChatFeatures();

    void executeInEditor(@NotNull String text);

    void openInEditor(@NotNull String text, @Nullable AIChatConversation conversation);

    @Nullable
    DBCExecutionContext getExecutionContext();

    @NotNull
    IWorkbenchPartSite getSite();

    @Nullable
    AIContextSettings getContextSettings();

}
