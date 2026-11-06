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
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import org.jkiss.code.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Saved conversations of the AI assistant: {@code <workspace>/.metadata/chromite-ai/chats/<id>.json}.
 *
 * 설계: 대화 하나 = 파일 하나. 대화마다 따로 고치고 지우므로 한 파일에 모으면 매번 전체를 다시 써야 한다. 정책·감사 기록과 같은 에이전트 폴더
 * ({@link AIAgentServices#getAgentFolder()})에 두어 작업 공간과 함께 옮겨진다. 대화에는 도구 결과(DB 값)가 들어 있으므로 작업 공간 밖에 두지 않는다.
 * 비밀은 도구 결과에 없고(도구가 내지 않음) 인수도 저장 전에 지우지 않는다: 모델이 보낸 그대로여야 다음 요청에 같은 짝을 보낼 수 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 */
public final class AIAgentChatStore {

    private static final Log log = Log.getLog(AIAgentChatStore.class);

    private static final String FOLDER = "chats"; //$NON-NLS-1$
    private static final String EXTENSION = ".json"; //$NON-NLS-1$

    private AIAgentChatStore() {
    }

    @NotNull
    public static Path getFolder() {
        return AIAgentServices.getAgentFolder().resolve(FOLDER);
    }

    /**
     * All saved conversations, the most recently updated first. Unreadable files are skipped (and logged).
     */
    @NotNull
    public static synchronized List<AIAgentChat> loadAll() {
        List<AIAgentChat> chats = new ArrayList<>();
        Path folder = getFolder();
        if (!Files.isDirectory(folder)) {
            return chats;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + EXTENSION)) { //$NON-NLS-1$
            for (Path file : files) {
                try {
                    AIAgentChat chat = AIAgentChat.fromJson(Files.readString(file, StandardCharsets.UTF_8));
                    if (chat != null) {
                        chats.add(chat);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Can't read the AI conversation " + file, e);
                }
            }
        } catch (IOException e) {
            log.warn("Can't list the AI conversations in " + folder, e);
        }
        chats.sort(Comparator.comparingLong(AIAgentChat::getUpdated).reversed());
        return chats;
    }

    /**
     * Writes a conversation (temporary file + move, so a crash never leaves half a file).
     */
    public static synchronized void save(@NotNull AIAgentChat chat) {
        Path file = getFolder().resolve(chat.getId() + EXTENSION);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(chat.getId() + ".tmp"); //$NON-NLS-1$
            Files.writeString(temp, chat.toJson(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Can't save the AI conversation " + file, e);
        }
    }

    public static synchronized void delete(@NotNull AIAgentChat chat) {
        try {
            Files.deleteIfExists(getFolder().resolve(chat.getId() + EXTENSION));
        } catch (IOException e) {
            log.warn("Can't delete the AI conversation " + chat.getId(), e);
        }
    }
}
