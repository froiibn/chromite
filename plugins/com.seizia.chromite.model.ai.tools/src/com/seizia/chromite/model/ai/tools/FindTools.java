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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.tools.chat.AIAgentToolSelection;
import com.seizia.chromite.model.ai.tools.chat.AIAgentToolSets;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The meta tool {@code findTools}: finds agent tools outside the chat request's tool set by keywords and area.
 *
 * 설계(docs/chromite/ai-agent.md "도구 묶음", 앱 변경 #64):
 * <ul>
 *     <li>채팅은 제공자의 도구 묶음(최소·핵심)만 보낸다. 묶음 밖의 일은 모델이 이 도구로 찾는다. 결과의 도구는 채팅 실행기가 그 대화에 더해 다음
 *     요청부터 정의를 함께 보낸다({@link #foundIds}). 이 도구는 이름·분야·분류·한 줄 설명만 돌려준다(정의 전체는 다음 요청에 실림).</li>
 *     <li>찾는 범위는 지금 요청에 없는 채팅 도구다. 지금 요청의 도구는 같은 스레드의 {@link AIAgentToolSets#getOffered}에서 읽는다. 연결이 있으면
 *     요청 고르기와 같은 거르기({@link AIAgentToolSelection#select})로 그 연결의 DB 종류에 맞지 않거나 정책이 막는 도구를 뺀다.</li>
 *     <li>고르기는 낱말 점수다: 도구 ID의 낱말(camelCase로 나눔)과 같으면 3, ID에 들어 있으면 2, 설명·분야에 들어 있으면 1. 끝의 s/es는 떼고 비교한다.
 *     설명이 영어이므로 모델에게 영어 낱말을 달라고 인수 설명에 적었다. 맞는 것이 없으면 분야별 도구 ID 목록을 돌려 다시 찾게 한다.</li>
 *     <li>MCP 목록에는 없다({@code purpose="TOOL"}): MCP 클라이언트는 이미 모든 도구를 받는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (앱 변경 #64)
 */
public class FindTools extends AgentTool {

    private static final String CATEGORY_PREFIX = "agent."; //$NON-NLS-1$
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        if (!toolId.equals(AIAgentToolSets.FIND_TOOLS)) {
            throw unknownTool(toolId);
        }
        String area = CommonUtils.notEmpty(AgentArgs.getString(args, "area")).trim().toLowerCase(Locale.ROOT);
        List<String> words = words(CommonUtils.notEmpty(AgentArgs.getString(args, "query")));
        int limit = Math.max(1, Math.min(MAX_LIMIT, AgentArgs.getInt(args, "limit", DEFAULT_LIMIT)));

        Set<String> offered = AIAgentToolSets.getOffered();
        List<AIFunctionDescriptor> candidates = new ArrayList<>();
        for (AIFunctionDescriptor tool : AIToolCatalog.getTools(AIFunctionPurpose.TOOL)) {
            if (!tool.getId().equals(AIAgentToolSets.FIND_TOOLS) && !offered.contains(tool.getId())) {
                candidates.add(tool);
            }
        }
        candidates = AIAgentToolSelection.select(context.getConnection(), candidates).tools();

        Map<AIFunctionDescriptor, Integer> scores = new LinkedHashMap<>();
        for (AIFunctionDescriptor tool : candidates) {
            if (!area.isEmpty() && !area.equals(areaOf(tool))) {
                continue;
            }
            int score = words.isEmpty() ? 1 : score(tool, words);
            if (score > 0) {
                scores.put(tool, score);
            }
        }
        List<AIFunctionDescriptor> found = scores.keySet().stream()
            .sorted(Comparator.comparing((AIFunctionDescriptor t) -> -scores.get(t)).thenComparing(AIFunctionDescriptor::getId))
            .limit(limit)
            .toList();

        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        for (AIFunctionDescriptor tool : found) {
            JsonObject item = new JsonObject();
            item.addProperty("id", tool.getId());
            item.addProperty("area", areaOf(tool));
            item.addProperty("access", tool.getAccess().name());
            item.addProperty("description", firstSentence(tool.getAiDescription()));
            tools.add(item);
        }
        result.add("tools", tools);
        if (found.isEmpty()) {
            // Nothing matched: the areas and their tools, so the model can search again with other words or an area
            JsonObject areas = new JsonObject();
            for (AIFunctionDescriptor tool : candidates) {
                String toolArea = areaOf(tool);
                if (!areas.has(toolArea)) {
                    areas.add(toolArea, new JsonArray());
                }
                areas.getAsJsonArray(toolArea).add(tool.getId());
            }
            result.add("areas", areas);
            result.addProperty("note", "No tool matched. Search again with other English keywords or an area, or use runReadOnlySql with the database's system views.");
        } else {
            result.addProperty("note", "These tools are available from your next step on; call them by id.");
        }
        return result;
    }

    /**
     * Tool IDs of a successful findTools result, which the chat adds to the conversation.
     */
    @NotNull
    public static List<String> foundIds(@Nullable String resultJson) {
        List<String> ids = new ArrayList<>();
        try {
            JsonElement json = JsonParser.parseString(CommonUtils.notEmpty(resultJson));
            JsonArray tools = json.isJsonObject() && json.getAsJsonObject().get("tools") instanceof JsonArray array ? array : null;
            if (tools != null) {
                for (JsonElement tool : tools) {
                    if (tool.isJsonObject() && tool.getAsJsonObject().get("id") != null) {
                        ids.add(tool.getAsJsonObject().get("id").getAsString());
                    }
                }
            }
        } catch (RuntimeException e) {
            // Not a findTools result (an error text): nothing found
        }
        return ids;
    }

    @NotNull
    private static String areaOf(@NotNull AIFunctionDescriptor tool) {
        String category = CommonUtils.notEmpty(tool.getCategoryId());
        return category.startsWith(CATEGORY_PREFIX) ? category.substring(CATEGORY_PREFIX.length()) : category;
    }

    private static int score(@NotNull AIFunctionDescriptor tool, @NotNull List<String> words) {
        String id = tool.getId().toLowerCase(Locale.ROOT);
        List<String> idWords = words(tool.getId().replaceAll("([a-z])([A-Z])", "$1 $2")); //$NON-NLS-1$ //$NON-NLS-2$
        String text = (CommonUtils.notEmpty(tool.getAiDescription()) + ' ' + areaOf(tool)).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String word : words) {
            if (idWords.contains(word)) {
                score += 3;
            } else if (id.contains(word)) {
                score += 2;
            } else if (text.contains(word)) {
                score += 1;
            }
        }
        return score;
    }

    // Lower-case words of two or more letters, with a plural ending removed ("indexes" finds "index")
    @NotNull
    private static List<String> words(@NotNull String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) { //$NON-NLS-1$
            if (word.length() < 2) {
                continue;
            }
            if (word.endsWith("es") && word.length() > 4) { //$NON-NLS-1$
                word = word.substring(0, word.length() - 2);
            } else if (word.endsWith("s") && word.length() > 3) { //$NON-NLS-1$
                word = word.substring(0, word.length() - 1);
            }
            if (!words.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    @NotNull
    private static String firstSentence(@Nullable String description) {
        String text = CommonUtils.notEmpty(description).strip();
        int end = text.indexOf(". "); //$NON-NLS-1$
        return end < 0 ? text : text.substring(0, end + 1);
    }
}
