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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.ai.AIFunction;
import com.seizia.chromite.model.ai.AIFunctionAllowMode;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.AIFunctionParameterTransformer;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.AIToolbox;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.ai.engine.anthropic.AnthropicClient;
import com.seizia.chromite.model.ai.tools.mcp.AIMcpSettings;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tool sets of Chromite Agent chat requests: which tool definitions a request carries, chosen per provider.
 *
 * 설계(docs/chromite/ai-agent.md "도구 묶음", 앱 변경 #64):
 * <ul>
 *     <li>도구 101개의 정의는 약 69 KB(≈17k 토큰)다. 사내 소형 모델(컨텍스트 8k~32k)에는 정의만으로 넘친다. 그래서 묶음을 셋 둔다: 최소(작은 모델용,
 *     짧은 설명), 핵심(호스팅 모델의 기본), 전체. 구성원은 {@code plugin.xml}의 {@code toolSet} 속성으로 적는다(최소 ⊂ 핵심 ⊂ 전체). 코드 목록으로 두면
 *     도구를 더할 때 두 곳을 고쳐야 한다.</li>
 *     <li>묶음 밖의 도구는 메타 도구 {@code findTools}로 찾는다. 찾은 도구와 모델이 이름으로 부른 도구는 그 대화에 저장되어 다음 요청부터 함께
 *     간다({@link AIAgentChat#getFoundTools}). 정책·확인·감사는 모두 실행기({@code AIToolExecutor})가 하므로 이렇게 들어온 도구에도 그대로 적용된다.</li>
 *     <li>설정은 제공자마다 자동/최소/핵심/전체다. 기본값은 vLLM·Ollama = 최소, 나머지 = 핵심. 자동은 정의 크기가 컨텍스트의
 *     {@value #AUTOMATIC_SHARE_PERCENT}%를 넘지 않는 가장 큰 묶음이다(전체 → 핵심 → 최소). 최소보다 아래는 없다.</li>
 *     <li>전체는 예전 "도구를 모두 보냄"과 같다: 연결의 DB 종류·정책으로 거르지 않는다(한 대화에서 여러 DB 종류를 다룰 때). 그 확인란의 저장값
 *     (true)은 처음 읽을 때 모든 제공자의 "전체"로 옮기고 지운다. 최소·핵심은 전처럼 연결로 거른다({@link AIAgentToolSelection#select}).</li>
 *     <li>도구 호출을 못 하는 제공자·모델(vLLM을 도구 호출 옵션 없이 띄운 경우)은 제공자·모델별로 기억한다({@link #isToolsOff}). Providers의 Test와
 *     채팅 요청의 400 응답이 정하고, 도구 호출이 되는 Test가 지운다. 그동안 요청에는 도구를 싣지 않는다.</li>
 *     <li>토큰 수는 요청 전에 알 수 없어 어림한다({@link #estimateTokens}): ASCII 4글자 = 1토큰, 그 밖의 글자(한국어 등) 1글자 = 1토큰. 한국어를
 *     넉넉하게 세어 작은 컨텍스트를 넘기지 않는 쪽을 골랐다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (앱 변경 #64: 세 단계 도구 묶음, findTools, 제공자별 설정, 도구 없음 방식)
 */
public final class AIAgentToolSets {

    /**
     * Tool set setting of a provider.
     */
    public enum ToolSet {
        /** The largest set whose definitions fit the provider's context share. */
        AUTOMATIC,
        /** About a dozen tools with short descriptions, for small models. */
        MINIMAL,
        /** The tools of common questions. */
        CORE,
        /** Every tool, not filtered by the connection. */
        ALL
    }

    /**
     * ID of the meta tool that finds tools outside the request's set.
     */
    public static final String FIND_TOOLS = "findTools"; //$NON-NLS-1$

    /**
     * Share of the context window (percent) the tool definitions may take when the setting is Automatic.
     */
    public static final int AUTOMATIC_SHARE_PERCENT = 25;

    private static final String MEMBER_MINIMAL = "minimal"; //$NON-NLS-1$
    private static final String MEMBER_CORE = "core"; //$NON-NLS-1$
    private static final String PREF_TOOL_SET = "chromite.ai.chat.toolSet."; //$NON-NLS-1$
    private static final String PREF_TOOLS_OFF = "chromite.ai.chat.toolsOff."; //$NON-NLS-1$
    // The "send all tools" checkbox of the Tool Policy tab until 2026-11-12; read once to move it to the All set
    private static final String PREF_LEGACY_SEND_ALL_TOOLS = "chromite.ai.chat.sendAllTools"; //$NON-NLS-1$
    private static final Gson GSON = new Gson();

    // Tool IDs the running chat request offers, for findTools on the same thread; not set outside the chat (MCP)
    private static final ThreadLocal<Set<String>> OFFERED = new ThreadLocal<>();

    private static DBPPreferenceStore store;

    /**
     * The tools of one request.
     *
     * @param set              the set the tools come from (the resolved one when the setting is Automatic); null when tools are off
     * @param tools            the definitions to send (minimal tools with short descriptions in the minimal set)
     * @param otherDatabases   tools of the set that do not support the connection's database type
     * @param blocked          tools of the set the policy blocks on the connection
     * @param definitionTokens estimated tokens of the definitions
     * @param toolsOff         the provider's model can't call tools: nothing is sent
     */
    public record Plan(
        @Nullable ToolSet set,
        @NotNull List<AIFunctionDescriptor> tools,
        @NotNull List<String> otherDatabases,
        @NotNull List<String> blocked,
        int definitionTokens,
        boolean toolsOff
    ) {
        @NotNull
        public Set<String> toolIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AIFunctionDescriptor tool : tools) {
                ids.add(tool.getId());
            }
            return ids;
        }
    }

    private AIAgentToolSets() {
    }

    //////////////////////////////////////////////////////////
    // Settings

    @NotNull
    private static synchronized DBPPreferenceStore getStore() {
        if (store == null) {
            store = new BundlePreferenceStore(AIMcpSettings.BUNDLE_ID);
            if (store.contains(PREF_LEGACY_SEND_ALL_TOOLS)) {
                if (store.getBoolean(PREF_LEGACY_SEND_ALL_TOOLS)) {
                    for (String engineId : AIAgentProviders.PROVIDERS) {
                        if (CommonUtils.isEmpty(store.getString(PREF_TOOL_SET + engineId))) {
                            store.setValue(PREF_TOOL_SET + engineId, ToolSet.ALL.name());
                        }
                    }
                }
                store.setToDefault(PREF_LEGACY_SEND_ALL_TOOLS);
                PrefUtils.savePreferenceStore(store);
            }
        }
        return store;
    }

    /**
     * The set a provider uses when the user did not choose one: small models of one's own server get the minimal set.
     */
    @NotNull
    public static ToolSet getDefaultToolSet(@NotNull String engineId) {
        return engineId.equals(AIAgentProviders.VLLM) || engineId.equals(AIAgentProviders.OLLAMA) ? ToolSet.MINIMAL : ToolSet.CORE;
    }

    @NotNull
    public static ToolSet getToolSet(@NotNull String engineId) {
        return CommonUtils.valueOf(ToolSet.class, getStore().getString(PREF_TOOL_SET + engineId), getDefaultToolSet(engineId));
    }

    public static void setToolSet(@NotNull String engineId, @NotNull ToolSet set) {
        if (set == getToolSet(engineId)) {
            return;
        }
        getStore().setValue(PREF_TOOL_SET + engineId, set.name());
        PrefUtils.savePreferenceStore(getStore());
    }

    /**
     * Whether the provider's server rejected tool calls for this model (the Test or a chat request found it).
     */
    public static boolean isToolsOff(@NotNull String engineId, @Nullable String model) {
        return readToolsOff(engineId).contains(CommonUtils.notEmpty(model).trim());
    }

    public static void setToolsOff(@NotNull String engineId, @Nullable String model, boolean off) {
        List<String> models = readToolsOff(engineId);
        String name = CommonUtils.notEmpty(model).trim();
        if (off == models.contains(name)) {
            return;
        }
        if (off) {
            models.add(name);
        } else {
            models.remove(name);
        }
        getStore().setValue(PREF_TOOLS_OFF + engineId, String.join("\n", models)); //$NON-NLS-1$
        PrefUtils.savePreferenceStore(getStore());
    }

    @NotNull
    private static List<String> readToolsOff(@NotNull String engineId) {
        String value = getStore().getString(PREF_TOOLS_OFF + engineId);
        return CommonUtils.isEmpty(value) ? new ArrayList<>() : new ArrayList<>(List.of(value.split("\n", -1))); //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Sets

    /**
     * Whether a tool belongs to a set ({@code toolSet} attribute: minimal tools are also in core). findTools is not in the full
     * set: there is nothing outside it to find.
     */
    public static boolean isMember(@NotNull AIFunctionDescriptor tool, @NotNull ToolSet set) {
        String member = tool.getToolSet();
        return switch (set) {
            case MINIMAL -> MEMBER_MINIMAL.equals(member);
            case CORE -> MEMBER_MINIMAL.equals(member) || MEMBER_CORE.equals(member);
            case ALL, AUTOMATIC -> !tool.getId().equals(FIND_TOOLS);
        };
    }

    /**
     * The tools of a request of a conversation.
     *
     * @param setting       the provider's setting
     * @param connection    the conversation's connection, or null
     * @param catalog       the chat tools
     * @param found         tools the conversation found or called outside its set (sent with the minimal and core sets)
     * @param toolsOff      the provider's model can't call tools
     * @param contextTokens the model's context window, for Automatic
     */
    @NotNull
    public static Plan plan(
        @NotNull ToolSet setting,
        @Nullable DBPDataSourceContainer connection,
        @NotNull List<AIFunctionDescriptor> catalog,
        @NotNull Collection<String> found,
        boolean toolsOff,
        int contextTokens
    ) {
        if (toolsOff) {
            return new Plan(null, List.of(), List.of(), List.of(), 0, true);
        }
        if (setting != ToolSet.AUTOMATIC) {
            return build(setting, connection, catalog, found);
        }
        Plan plan = null;
        for (ToolSet candidate : List.of(ToolSet.ALL, ToolSet.CORE, ToolSet.MINIMAL)) {
            plan = build(candidate, connection, catalog, found);
            if ((long) plan.definitionTokens() * 100 <= (long) contextTokens * AUTOMATIC_SHARE_PERCENT) {
                return plan;
            }
        }
        return plan;
    }

    @NotNull
    private static Plan build(
        @NotNull ToolSet set,
        @Nullable DBPDataSourceContainer connection,
        @NotNull List<AIFunctionDescriptor> catalog,
        @NotNull Collection<String> found
    ) {
        List<AIFunctionDescriptor> members = new ArrayList<>();
        for (AIFunctionDescriptor tool : catalog) {
            if (isMember(tool, set) || (set != ToolSet.ALL && found.contains(tool.getId()))) {
                members.add(tool);
            }
        }
        AIAgentToolSelection.Selection selection = set == ToolSet.ALL
            ? new AIAgentToolSelection.Selection(members, List.of(), List.of())
            : AIAgentToolSelection.select(connection, members);
        List<AIFunctionDescriptor> tools = selection.tools();
        if (set == ToolSet.MINIMAL) {
            tools = tools.stream().map(AIAgentToolSets::shortView).toList();
        }
        return new Plan(set, tools, selection.otherDatabases(), selection.blocked(), estimateDefinitionTokens(tools), false);
    }

    //////////////////////////////////////////////////////////
    // Sizes

    /**
     * Estimated tokens of a text: four ASCII characters or one other character (Korean, ...) per token. Tokenizers differ by
     * model; this errs on the large side for Korean so that small contexts are not overrun.
     */
    public static int estimateTokens(@Nullable CharSequence text) {
        if (text == null) {
            return 0;
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x80) {
                ascii++;
            } else {
                other++;
            }
        }
        return (ascii + 3) / 4 + other;
    }

    /**
     * The definitions as a request carries them (the Messages API shape; Chat Completions adds a small wrapper per tool).
     */
    @NotNull
    public static String toDefinitionJson(@NotNull List<AIFunctionDescriptor> tools) {
        JsonArray array = new JsonArray();
        for (AIFunctionDescriptor tool : tools) {
            array.add(AnthropicClient.toTool(tool));
        }
        return GSON.toJson(array);
    }

    public static int estimateDefinitionTokens(@NotNull List<AIFunctionDescriptor> tools) {
        return tools.isEmpty() ? 0 : estimateTokens(toDefinitionJson(tools));
    }

    //////////////////////////////////////////////////////////
    // The running request (findTools)

    /**
     * Runs a tool call of a chat request; findTools reads the request's tools on the same thread ({@link #getOffered}).
     */
    static void runWithOffered(@NotNull Set<String> offered, @NotNull Runnable call) {
        OFFERED.set(offered);
        try {
            call.run();
        } finally {
            OFFERED.remove();
        }
    }

    /**
     * Tool IDs the running chat request offers; empty outside a chat request (findTools then searches every tool).
     */
    @NotNull
    public static Set<String> getOffered() {
        Set<String> offered = OFFERED.get();
        return offered == null ? Set.of() : offered;
    }

    //////////////////////////////////////////////////////////
    // Short descriptions

    /**
     * The tool with its short descriptions (plugin.xml {@code shortDescription}); the tool itself when it has none.
     */
    @NotNull
    static AIFunctionDescriptor shortView(@NotNull AIFunctionDescriptor tool) {
        boolean shortParameter = false;
        for (AIFunctionParameter parameter : tool.getParameters()) {
            shortParameter |= parameter.getShortDescription() != null;
        }
        return tool.getShortAiDescription() == null && !shortParameter ? tool : new ShortTool(tool);
    }

    /**
     * A tool definition that sends the short descriptions. Only the definition sent to the model changes: calls are matched by
     * name and run through the catalog's descriptor.
     */
    private static final class ShortTool implements AIFunctionDescriptor {
        private final AIFunctionDescriptor tool;
        private final AIFunctionParameter[] parameters;

        ShortTool(@NotNull AIFunctionDescriptor tool) {
            this.tool = tool;
            AIFunctionParameter[] source = tool.getParameters();
            parameters = new AIFunctionParameter[source.length];
            for (int i = 0; i < source.length; i++) {
                parameters[i] = source[i].getShortDescription() == null ? source[i] : new ShortParameter(source[i]);
            }
        }

        @NotNull
        @Override
        public AIToolbox getToolbox() {
            return tool.getToolbox();
        }

        @NotNull
        @Override
        public String getId() {
            return tool.getId();
        }

        @Nullable
        @Override
        public String getLegacyId() {
            return tool.getLegacyId();
        }

        @NotNull
        @Override
        public String getName() {
            return tool.getName();
        }

        @Nullable
        @Override
        public DBPImage getIcon() {
            return tool.getIcon();
        }

        @NotNull
        @Override
        public AIFunctionType getType() {
            return tool.getType();
        }

        @NotNull
        @Override
        public AIFunctionPurpose getPurpose() {
            return tool.getPurpose();
        }

        @NotNull
        @Override
        public AIToolAccess getAccess() {
            return tool.getAccess();
        }

        @NotNull
        @Override
        public AIToolWrites getWrites() {
            return tool.getWrites();
        }

        @Nullable
        @Override
        public String getCategoryId() {
            return tool.getCategoryId();
        }

        @Nullable
        @Override
        public String getAiDescription() {
            String description = tool.getShortAiDescription();
            return description != null ? description : tool.getAiDescription();
        }

        @Nullable
        @Override
        public String getToolSet() {
            return tool.getToolSet();
        }

        @Nullable
        @Override
        public String getShortAiDescription() {
            return tool.getShortAiDescription();
        }

        @Override
        public boolean isUI() {
            return tool.isUI();
        }

        @Override
        public boolean isSystem() {
            return tool.isSystem();
        }

        @Override
        public boolean isEnabledByDefault() {
            return tool.isEnabledByDefault();
        }

        @Override
        public boolean isOmitConfirmation() {
            return tool.isOmitConfirmation();
        }

        @NotNull
        @Override
        public AIFunctionAllowMode getDefaultAllowMode() {
            return tool.getDefaultAllowMode();
        }

        @NotNull
        @Override
        public AIFunctionParameter[] getParameters() {
            return parameters;
        }

        @Nullable
        @Override
        public AIFunctionParameter getParameter(@NotNull String name) {
            for (AIFunctionParameter parameter : parameters) {
                if (parameter.getName().equalsIgnoreCase(name)) {
                    return parameter;
                }
            }
            return null;
        }

        @NotNull
        @Override
        public String[] getDependsOn() {
            return tool.getDependsOn();
        }

        @NotNull
        @Override
        public AIFunction getInstance() {
            return tool.getInstance();
        }
    }

    private record ShortParameter(@NotNull AIFunctionParameter parameter) implements AIFunctionParameter {
        @NotNull
        @Override
        public String getName() {
            return parameter.getName();
        }

        @NotNull
        @Override
        public String getType() {
            return parameter.getType();
        }

        @Nullable
        @Override
        public String getDescription() {
            return parameter.getShortDescription();
        }

        @Nullable
        @Override
        public String getShortDescription() {
            return parameter.getShortDescription();
        }

        @Override
        public boolean isRequired() {
            return parameter.isRequired();
        }

        @Nullable
        @Override
        public String getDefaultValue() {
            return parameter.getDefaultValue();
        }

        @Nullable
        @Override
        public String[] getValidValues() {
            return parameter.getValidValues();
        }

        @Nullable
        @Override
        public AIFunctionParameterTransformer getTransformer() {
            return parameter.getTransformer();
        }

        @Nullable
        @Override
        public String getTransformerSuffix() {
            return parameter.getTransformerSuffix();
        }
    }
}
