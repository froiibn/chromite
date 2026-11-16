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
import com.seizia.chromite.utils.GeneralUtils;

import java.nio.file.Path;

/**
 * UI services the agent tools need, registered by the frontend at startup; and the agent's workspace folder.
 *
 * 설계: 확장점 대신 정적 등록을 쓴다. 화면은 앱에 하나뿐이고(Swing 프런트엔드), 등록하는 쪽이 시작할 때 한 번 부르면 되며, 헤드리스 실행에서는
 * 등록이 없다는 것 자체가 "화면 없음 → 확인 거부"를 뜻하기 때문이다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계(채팅 화면) 인터페이스")
 */
public final class AIAgentServices {

    private static final String AGENT_FOLDER = "chromite-ai"; //$NON-NLS-1$

    @Nullable
    private static volatile AIToolConfirmationHandler confirmationHandler;
    @Nullable
    private static volatile AIEditorBridge editorBridge;

    private AIAgentServices() {
    }

    @Nullable
    public static AIToolConfirmationHandler getConfirmationHandler() {
        return confirmationHandler;
    }

    /**
     * Registers (or with null removes) the confirmation UI.
     */
    public static void setConfirmationHandler(@Nullable AIToolConfirmationHandler handler) {
        confirmationHandler = handler;
    }

    @Nullable
    public static AIEditorBridge getEditorBridge() {
        return editorBridge;
    }

    public static void setEditorBridge(@Nullable AIEditorBridge bridge) {
        editorBridge = bridge;
    }

    /**
     * Workspace folder of the agent: policy, audit log, MCP token ({@code <workspace>/.metadata/chromite-ai}).
     */
    @NotNull
    public static Path getAgentFolder() {
        return GeneralUtils.getMetadataFolder().resolve(AGENT_FOLDER);
    }
}
