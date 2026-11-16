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

import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIMessage;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests of the request history budget of the Chromite Agent chat ({@link AIAgentChat#toModelMessages}).
 *
 * 설계: 예산을 넘으면 오래된 도구 결과를 미리 보기로 줄이고, 그래도 넘으면 오래된 차례를 통째로 빼고, 마지막으로 남은 차례의 오래된 결과도
 * 줄인다(최신 결과 몇 개는 그대로). 어느 경우에도 호출과 결과는 한 메시지로 함께 가고 기록은 사용자의 프롬프트로 시작해야 한다. 저장한
 * 대화(항목 목록)는 바뀌지 않아야 한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Chromite Agent")
 */
public class AIAgentChatHistoryTest {

    private static final String LONG_RESULT = "{\"rows\":[" + "\"0123456789\",".repeat(400) + "\"end\"]}";

    @Test
    public void smallConversationIsSentWholeWithoutErrorsAndWithCanceledCalls() {
        AIAgentChat chat = AIAgentChat.create("test");
        chat.addUser("How many sessions?", null);
        AIAgentChat.Entry tool = chat.addTool(call("db_listSessions"));
        tool.finishTool("SUCCESS", "READ", "{\"count\":3}", 5);
        chat.addError("provider timeout");
        chat.addAssistant().appendText("There are 3 sessions.");
        chat.addAssistant();
        chat.addUser("And locks?", null);
        // The app stopped while this call ran: it still needs a result
        chat.addTool(call("db_getLocks"));

        AIAgentChat.ModelHistory history = chat.toModelMessages(AIAgentChat.HISTORY_BUDGET_CHARS, AIAgentChat.KEEP_TURNS);

        Assertions.assertEquals(5, history.messages().size());
        Assertions.assertEquals(0, history.droppedTurns());
        Assertions.assertEquals(0, history.shortenedResults());
        Assertions.assertEquals(history.fullChars(), history.sentChars());
        AIMessage canceled = history.messages().get(4);
        Assertions.assertNotNull(canceled.getFunctionCall());
        Assertions.assertNotNull(canceled.getFunctionResult());
        Assertions.assertTrue(String.valueOf(canceled.getFunctionResult().getValue()).contains("CANCELED"));
        assertPairsWhole(history);
    }

    @Test
    public void oldToolResultsAreShortenedBeforeAnyTurnIsDropped() {
        AIAgentChat chat = conversation(4, 2);
        int full = chat.toModelMessages(Integer.MAX_VALUE, AIAgentChat.KEEP_TURNS).fullChars();

        AIAgentChat.ModelHistory history = chat.toModelMessages(full - 2 * LONG_RESULT.length() + 2_000, AIAgentChat.KEEP_TURNS);

        Assertions.assertEquals(0, history.droppedTurns());
        Assertions.assertTrue(history.shortenedResults() > 0);
        Assertions.assertTrue(history.sentChars() < history.fullChars());
        assertPairsWhole(history);
        // The kept turns (the last two) still carry their results in full
        List<AIMessage> messages = history.messages();
        Assertions.assertEquals(LONG_RESULT, resultOf(messages.get(messages.size() - 1)));
        // The saved conversation is unchanged
        Assertions.assertEquals(LONG_RESULT, chat.getEntries().get(1).getResult());
    }

    @Test
    public void oldTurnsAreDroppedWholeWhenShorteningIsNotEnough() {
        AIAgentChat chat = conversation(6, 3);

        AIAgentChat.ModelHistory history = chat.toModelMessages(4 * LONG_RESULT.length(), AIAgentChat.KEEP_TURNS);

        Assertions.assertTrue(history.droppedTurns() > 0);
        Assertions.assertTrue(history.sentChars() <= 4 * LONG_RESULT.length() || history.droppedTurns() == 6 - AIAgentChat.KEEP_TURNS);
        Assertions.assertNotNull(history.messages().getFirst().getContent());
        Assertions.assertNull(history.messages().getFirst().getFunctionCall(), "the history must start with a prompt");
        assertPairsWhole(history);
    }

    @Test
    public void oneLongTurnKeepsItsNewestResults() {
        AIAgentChat chat = conversation(1, 10);

        AIAgentChat.ModelHistory history = chat.toModelMessages(5 * LONG_RESULT.length(), AIAgentChat.KEEP_TURNS);

        Assertions.assertEquals(0, history.droppedTurns());
        List<AIMessage> messages = history.messages();
        // The four newest results are sent in full, older ones of the same turn are shortened
        for (int i = messages.size() - 4; i < messages.size(); i++) {
            Assertions.assertEquals(LONG_RESULT, resultOf(messages.get(i)), "result " + i);
        }
        Assertions.assertNotEquals(LONG_RESULT, resultOf(messages.get(1)));
        Assertions.assertEquals(6, history.shortenedResults());
        assertPairsWhole(history);
    }

    /**
     * Turns of a prompt followed by tool calls with long results.
     */
    @NotNull
    private static AIAgentChat conversation(int turns, int toolsPerTurn) {
        AIAgentChat chat = AIAgentChat.create("test");
        for (int t = 0; t < turns; t++) {
            chat.addUser("Question " + t, null);
            for (int i = 0; i < toolsPerTurn; i++) {
                chat.addTool(call("db_runSql")).finishTool("SUCCESS", "READ", LONG_RESULT, 10);
            }
        }
        return chat;
    }

    @NotNull
    private static AIFunctionCall call(@NotNull String function) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("connection", "postgres-1");
        return new AIFunctionCall(function, arguments);
    }

    private static String resultOf(@NotNull AIMessage message) {
        Assertions.assertNotNull(message.getFunctionResult());
        return String.valueOf(message.getFunctionResult().getValue());
    }

    /**
     * Every call is sent with its result, in the same message.
     */
    private static void assertPairsWhole(@NotNull AIAgentChat.ModelHistory history) {
        for (AIMessage message : history.messages()) {
            if (message.getFunctionCall() != null) {
                Assertions.assertNotNull(message.getFunctionResult(), "call " + message.getFunctionCall().getFunctionName() + " without its result");
            }
            if (message.getFunctionResult() != null) {
                Assertions.assertNotNull(message.getFunctionCall(), "result without its call");
            }
        }
    }
}
