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
package com.seizia.chromite.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAuditLog;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.agent.AIToolDecision;
import com.seizia.chromite.model.ai.agent.AIToolOutcome;
import com.seizia.chromite.model.ai.agent.AIToolPermission;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.ai.engine.AIEngineProperties;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.engine.anthropic.AnthropicProperties;
import com.seizia.chromite.model.ai.engine.chatcompletions.ChatCompletionsProperties;
import com.seizia.chromite.model.ai.engine.chatcompletions.VllmProperties;
import com.seizia.chromite.model.ai.engine.ollama.OllamaProperties;
import com.seizia.chromite.model.ai.engine.openai.OpenAIProperties;
import com.seizia.chromite.model.ai.tools.chat.AIAgentProviders;
import com.seizia.chromite.model.ai.tools.chat.AIAgentToolSets;
import com.seizia.chromite.model.ai.tools.mcp.AIMcpServer;
import com.seizia.chromite.model.ai.tools.mcp.AIMcpSettings;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBSecurityException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.View;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tools &gt; Options &gt; Chromite Agent: providers, the tool policy, the local MCP server and the audit log.
 *
 * 설계:
 * <ul>
 *     <li>Providers: 제공자별 API 키·주소·기본 모델. 키는 OS 보안 저장소에만 저장하고({@link AIAgentProviders#saveProperties}) 화면에 다시 보이지 않는다
 *     (입력란은 비어 있고 "저장됨" 안내만 보인다). 보안 저장소가 키를 저장하지 못하면 그 이유를 따로 알리고 아무것도 저장하지 않는다.
 *     Test는 저장하지 않은 값으로 모델 목록을 받아 본다(작업 스레드, 제공자에 요청 1번). vLLM은 이어서 그 모델로 도구 호출이 되는지 확인하고
 *     ({@link AIAgentProviders#checkToolCalls}, 요청 1번 더), 안 되면 Test 줄에 짧게 알리고 그 아래 입력란 폭으로 줄 바꾼 글에 서버를 띄우는 방법을 보인다
 *     (긴 글이 한 줄 상태 글에 들어가면 모든 양식이 그 폭으로 늘어난다). Test의 도구 호출 확인 결과는 채팅의 "도구 없음" 방식을 정한다
 *     ({@link AIAgentToolSets#setToolsOff}, 측정한 사실이라 [저장]을 기다리지 않음).
 *     제공자마다 채팅의 도구 묶음(자동/최소/핵심/전체, {@link AIAgentToolSets})을 고르는 값이 있고, Ollama·vLLM은 컨텍스트 크기(토큰)도 적는다
 *     (자동 묶음과 보내기 전 크기 확인에 씀, vLLM은 Test가 모델 목록의 크기로 빈 칸을 채움). 둘 다 값이라 [저장]에서 저장한다.</li>
 *     <li>Policy: {@link AIToolPolicy}의 규칙 사본을 고치고 페이지의 [저장]에서 한 번에 저장한다. "실제 권한" 열은 저장 전 사본으로 실행기와 같은 판정
 *     ({@link AIToolPolicy#resolve(AIToolPolicy.Settings, DBPDataSourceContainer, AIFunctionDescriptor, AIToolAccess)})을 해서 보인다.
 *     운영 연결 규칙과 읽기 전용 연결 규칙은 맨 위에 늘 보인다.</li>
 *     <li>MCP server: 켜기는 토글이라 누를 때 {@link AIMcpSettings}로 저장하고(서버가 바로 시작·중지), 포트는 [저장]에서 저장한다. 토큰 다시
 *     만들기는 즉시 하고(되돌릴 수 없으므로 묻는다), 토큰은 기본으로 가려 보인다.</li>
 *     <li>저장 표준(Options): 제공자마다, 권한 정책, MCP 포트가 칸({@link OptionsSaving.Part})이다. 키를 입력하거나 [키 지우기]를 누르면 값이
 *     바뀐 것이고 [저장]에서 저장한다. Test는 저장하지 않은 화면 값을 쓴다.</li>
 *     <li>Audit log: {@link AIToolAuditLog#readRecent} 최근 기록을 작업 스레드에서 읽어 표로 보이고 클라이언트·결과·글자로 거른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 OS 보안 저장소 실패 안내({@code ai_options_save_failed_secure_storage})
 * - 2026-11-07 탭 이름을 Chromite Agent로 바꾼 데 맞춰 주석을 고침(docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 Tool Policy 탭에 "도구를 모두 보냄" 설정(docs/chromite/ai-agent.md "요청 크기")
 * - 2026-11-08 감사 기록의 고정 문구·형식 열(시각·클라이언트·분류·정책·결과)을 가장 긴 문구에 맞춤: "Chromite Agent"가 "Chro…"로 잘렸다
 *   (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 * - 2026-11-08 MCP 토큰 다시 만들기 확인은 다시 만들기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 Providers 양식은 자기 폭을 지킴: 넓어진 Options 창에서 입력란이 오른쪽 끝까지 늘어나던 것(app round 1 #8,
 *   docs/chromite/swing-options.md "목록, 찾기, 페이지 열기")
 * - 2026-11-11 Google Gemini·vLLM 양식(vLLM의 키는 선택), Test의 도구 호출 확인(app round 2 #34, #35, docs/chromite/ai-agent.md "Chat Completions 엔진")
 * - 2026-11-12 OK 대신 Options 저장 표준: 칸마다 바뀐 수·저장·되돌리기, 토글은 바로 저장, 저장 실패는 페이지 아래 줄에(앱 변경 #59,
 *   docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 * - 2026-11-12 경고 글자는 아이콘 색 대신 글자용 경고 색 {@code Chromite.status.warningForeground}(앱 변경 #55: 밝은 테마의 경고 색 #FFAF0F는
 *   흰 바탕에서 1.8:1) (docs/chromite/design-spec.md "적용 현황 — 글자 선명도와 대비")
 * - 2026-11-12 Providers 양식에 도구 묶음·컨텍스트 크기, Test 결과로 도구 없음 방식을 정함. Tool Policy의 "도구를 모두 보냄"을 지움(묶음의
 *   "전체"가 대신함) (앱 변경 #64, docs/chromite/ai-agent.md "도구 묶음")
 * - 2026-11-12 MCP 서버 설명을 줄 바꾸는 글({@link WrappingNote})로: 한 줄 라벨이 영어(모든 배율)·한국어 100%에서 잘렸다(앱 변경 #71 b,
 *   docs/chromite/swing-options.md "잘리던 한 줄 안내")
 * - 2026-11-12 Tool Policy 표의 실제 권한: 늘 확인하는 도구({@link AIFunctionDescriptor#isAlwaysConfirmed()})는 막히지 않으면 "늘 확인".
 *   rollbackTransaction이 "읽기 / 자동 실행"으로 보였다(앱 변경 #71 g, docs/chromite/ai-agent.md "권한 정책")
 * - 2026-11-13 권한 정책의 규칙 안내를 HTML 라벨 대신 아이콘과 {@link WrappingNote}로({@link WrappingNote#withIcon}): 옵션 창 최소 폭에서
 *   Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다(앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
 * - 2026-11-15 감사 기록: 도구 열에 합친 도구의 작업을 붙이고, 글자 거르기가 옛 ID의 기록을 지금 ID로도 찾음(옛 기록은 옛 ID 그대로)
 *   (도구 정리, docs/chromite/ai-agent.md "감사 기록")
 */
final class AiOptionsPage extends JPanel {

    private static final Log log = Log.getLog(AiOptionsPage.class);
    private static final int AUDIT_LIMIT = 2000;

    private final List<ProviderForm> providerForms = new ArrayList<>();
    private final PolicyTab policyTab = new PolicyTab();
    private final McpTab mcpTab = new McpTab();

    AiOptionsPage() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(AiMessages.ai_options_providers, createProvidersTab());
        tabs.addTab(AiMessages.ai_options_policy, policyTab);
        tabs.addTab(AiMessages.ai_options_mcp, mcpTab);
        tabs.addTab(AiMessages.ai_options_audit, new AuditTab());
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * The blocks the page's Save writes: each provider, the tool policy, the MCP port.
     */
    @NotNull
    List<OptionsSaving.Part> getParts() {
        List<OptionsSaving.Part> parts = new ArrayList<>(providerForms);
        parts.add(policyTab);
        parts.add(mcpTab);
        return parts;
    }

    //////////////////////////////////////////////////////////
    // Providers

    @NotNull
    private JComponent createProvidersTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel note = new JLabel(AiMessages.ai_options_providers_note);
        note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        note.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(note);
        for (String engineId : AIAgentProviders.PROVIDERS) {
            ProviderForm form = new ProviderForm(engineId);
            providerForms.add(form);
            panel.add(Box.createVerticalStrut(12));
            form.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(form);
        }
        // One label column for all forms, so their fields line up
        int labelWidth = 0;
        for (ProviderForm form : providerForms) {
            for (JLabel label : form.labels) {
                labelWidth = Math.max(labelWidth, label.getPreferredSize().width);
            }
        }
        for (ProviderForm form : providerForms) {
            for (JLabel label : form.labels) {
                label.setPreferredSize(new Dimension(labelWidth, label.getPreferredSize().height));
            }
        }
        // The forms keep their preferred width (fields of 28 columns) instead of stretching across a wide Options page
        JPanel column = new JPanel(new BorderLayout());
        column.add(panel, BorderLayout.WEST);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(column, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(wrapper);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    /**
     * The settings of one provider: key (not for Ollama; optional for vLLM), address, default model, Test.
     */
    private static final class ProviderForm extends JPanel implements OptionsSaving.Part {
        private final String engineId;
        // The saved address and model, to count and revert the changes
        private String savedUrl = ""; //$NON-NLS-1$
        private String savedModel = ""; //$NON-NLS-1$
        private String savedContext = ""; //$NON-NLS-1$
        private AIAgentToolSets.ToolSet savedToolSet;
        private final JComboBox<AIAgentToolSets.ToolSet> toolSetCombo = new JComboBox<>(AIAgentToolSets.ToolSet.values());
        // Ollama and vLLM only: the size the Automatic tool set and the size check use
        private final JTextField contextField = new JTextField(8);
        private final JPasswordField keyField = new JPasswordField(28);
        private final JButton removeKeyButton = new JButton(AiMessages.ai_options_remove_key);
        private final JTextField urlField = new JTextField(28);
        private final JComboBox<String> modelCombo = new JComboBox<>();
        private final JButton testButton = new JButton(AiMessages.ai_options_test);
        private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
        // Why tool calls do not work, wrapped at the width of the fields; hidden otherwise
        private final JTextArea warningArea = new JTextArea() {
            @Override
            public Dimension getPreferredSize() {
                int width = urlField.getPreferredSize().width;
                View view = getUI().getRootView(this);
                view.setSize(width, Integer.MAX_VALUE);
                Insets insets = getInsets();
                return new Dimension(width, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
            }
        };
        private final List<JLabel> labels = new ArrayList<>();
        private boolean hasKey;
        private boolean removeKey;

        ProviderForm(@NotNull String engineId) {
            super(new GridBagLayout());
            this.engineId = engineId;
            setBorder(BorderFactory.createTitledBorder(AIAgentProviders.getLabel(engineId)));
            modelCombo.setEditable(true);
            load();

            int row = 0;
            if (!engineId.equals(AIAgentProviders.OLLAMA)) {
                JPanel keyRow = new JPanel(new BorderLayout(6, 0));
                keyRow.add(keyField, BorderLayout.CENTER);
                keyRow.add(removeKeyButton, BorderLayout.EAST);
                row = addRow(row, engineId.equals(AIAgentProviders.VLLM) ? AiMessages.ai_options_api_key_optional
                    : AiMessages.ai_options_api_key, keyRow);
            }
            row = addRow(row, engineId.equals(AIAgentProviders.OLLAMA) ? AiMessages.ai_options_host
                : engineId.equals(AIAgentProviders.OPENAI) ? AiMessages.ai_options_base_url_openai : AiMessages.ai_options_base_url, urlField);
            row = addRow(row, AiMessages.ai_options_model, modelCombo);
            if (hasContextSize()) {
                JPanel contextRow = new JPanel(new BorderLayout());
                contextRow.add(contextField, BorderLayout.WEST);
                row = addRow(row, AiMessages.ai_options_context_size, contextRow);
            }
            toolSetCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof AIAgentToolSets.ToolSet set) {
                        setText(describeToolSet(set));
                    }
                    return this;
                }
            });
            toolSetCombo.setToolTipText(AiMessages.ai_options_tool_set_tip);
            row = addRow(row, AiMessages.ai_options_tool_set, toolSetCombo);
            JPanel testRow = new JPanel(new BorderLayout(8, 0));
            testRow.add(testButton, BorderLayout.WEST);
            statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            testRow.add(statusLabel, BorderLayout.CENTER);
            row = addRow(row, null, testRow);
            warningArea.setEditable(false);
            warningArea.setLineWrap(true);
            warningArea.setWrapStyleWord(true);
            warningArea.setOpaque(false);
            warningArea.setBorder(BorderFactory.createEmptyBorder());
            warningArea.setFont(statusLabel.getFont());
            warningArea.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.warningForeground"); //$NON-NLS-1$
            warningArea.setVisible(false);
            // No label cell, so the hidden row takes no space
            GridBagConstraints warning = new GridBagConstraints();
            warning.gridx = 1;
            warning.gridy = row;
            warning.anchor = GridBagConstraints.WEST;
            warning.insets = new Insets(0, 6, 3, 8);
            add(warningArea, warning);

            // Remove key changes the value (the saved key goes with Save), as a typed key does
            removeKeyButton.addActionListener(e -> {
                removeKey = true;
                hasKey = false;
                keyField.setText(""); //$NON-NLS-1$
                updateKeyPlaceholder();
            });
            testButton.addActionListener(e -> test());
        }

        @NotNull
        @Override
        public String getName() {
            return AIAgentProviders.getLabel(engineId);
        }

        /**
         * A typed key or a removed key, the address and the model.
         */
        @Override
        public int getChangeCount() {
            int count = keyField.getPassword().length > 0 || removeKey ? 1 : 0;
            count += urlField.getText().trim().equals(savedUrl) ? 0 : 1;
            count += contextField.getText().trim().equals(savedContext) ? 0 : 1;
            count += toolSetCombo.getSelectedItem() == savedToolSet ? 0 : 1;
            return count + (getModel().equals(savedModel) ? 0 : 1);
        }

        // Servers of one's own, whose context size Chromite can't know (hosted models have their own size or a large one)
        private boolean hasContextSize() {
            return engineId.equals(AIAgentProviders.OLLAMA) || engineId.equals(AIAgentProviders.VLLM);
        }

        @NotNull
        private static String describeToolSet(@NotNull AIAgentToolSets.ToolSet set) {
            return switch (set) {
                case AUTOMATIC -> AiMessages.ai_options_tool_set_automatic;
                case MINIMAL -> NLS.bind(AiMessages.ai_options_tool_set_minimal, countTools(set));
                case CORE -> NLS.bind(AiMessages.ai_options_tool_set_core, countTools(set));
                case ALL -> NLS.bind(AiMessages.ai_options_tool_set_all, countTools(set));
            };
        }

        private static int countTools(@NotNull AIAgentToolSets.ToolSet set) {
            return (int) AIToolCatalog.getTools(AIFunctionPurpose.TOOL).stream().filter(t -> AIAgentToolSets.isMember(t, set)).count();
        }

        @NotNull
        private String getModel() {
            return CommonUtils.toString(modelCombo.getEditor().getItem(), "").trim(); //$NON-NLS-1$
        }

        private int addRow(int row, @Nullable String label, @NotNull JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 6, 3, 8);
            c.gridx = 0;
            JLabel labelComponent = new JLabel(label == null ? "" : label); //$NON-NLS-1$
            labels.add(labelComponent);
            add(labelComponent, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            add(field, c);
            return row + 1;
        }

        private void load() {
            try {
                AIEngineProperties properties = AIAgentProviders.copyProperties(engineId);
                switch (properties) {
                    case AnthropicProperties anthropic -> {
                        hasKey = !CommonUtils.isEmpty(anthropic.getApiKey());
                        urlField.setText(anthropic.getBaseUrl());
                    }
                    case OpenAIProperties openAI -> {
                        hasKey = !CommonUtils.isEmpty(openAI.getToken());
                        urlField.setText(openAI.getBaseUrl());
                    }
                    case OllamaProperties ollama -> urlField.setText(CommonUtils.notEmpty(ollama.getHostname()));
                    case ChatCompletionsProperties chat -> {
                        hasKey = !CommonUtils.isEmpty(chat.getApiKey());
                        urlField.setText(CommonUtils.notEmpty(chat.getEffectiveBaseUrl()));
                    }
                    default -> {
                    }
                }
                Integer contextSize = hasContextSize() ? properties.getContextWindowSize() : null;
                contextField.setText(contextSize == null ? "" : contextSize.toString()); //$NON-NLS-1$
                List<String> models = new ArrayList<>(AIAgentProviders.getSuggestedModels(engineId));
                modelCombo.setModel(new DefaultComboBoxModel<>(models.toArray(String[]::new)));
                modelCombo.setSelectedItem(properties.getModel());
            } catch (Exception e) {
                log.warn("Can't read the settings of " + engineId, e);
            }
            if (engineId.equals(AIAgentProviders.OLLAMA)) {
                urlField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "http://localhost:11434"); //$NON-NLS-1$
            } else if (engineId.equals(AIAgentProviders.VLLM)) {
                urlField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, VllmProperties.EXAMPLE_BASE_URL);
                contextField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                    NLS.bind(AiMessages.ai_options_context_size_assumed, AIAgentProviders.getContextTokens(engineId)));
            }
            toolSetCombo.setSelectedItem(AIAgentToolSets.getToolSet(engineId));
            keyField.setText(""); //$NON-NLS-1$
            removeKey = false;
            savedUrl = urlField.getText().trim();
            savedModel = getModel();
            savedContext = contextField.getText().trim();
            savedToolSet = (AIAgentToolSets.ToolSet) toolSetCombo.getSelectedItem();
            updateKeyPlaceholder();
        }

        private void updateKeyPlaceholder() {
            keyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                hasKey ? AiMessages.ai_options_key_saved : AiMessages.ai_options_key_none);
            removeKeyButton.setEnabled(hasKey);
            keyField.repaint();
        }

        /**
         * The saved settings with the form's values (the key only if one was typed).
         */
        @NotNull
        private AIEngineProperties createProperties() throws Exception {
            AIEngineProperties properties = AIAgentProviders.copyProperties(engineId);
            String key = new String(keyField.getPassword()).trim();
            String url = urlField.getText().trim();
            String model = CommonUtils.toString(modelCombo.getEditor().getItem(), "").trim(); //$NON-NLS-1$
            switch (properties) {
                case AnthropicProperties anthropic -> {
                    if (!key.isEmpty() || removeKey) {
                        anthropic.setApiKey(key.isEmpty() ? null : key);
                    }
                    anthropic.setBaseUrl(url.isEmpty() || url.equals(AnthropicProperties.DEFAULT_BASE_URL) ? null : url);
                }
                case OpenAIProperties openAI -> {
                    if (!key.isEmpty() || removeKey) {
                        openAI.setToken(key.isEmpty() ? null : key);
                    }
                    openAI.setBaseUrl(url.isEmpty() ? null : url);
                }
                case OllamaProperties ollama -> {
                    ollama.setHostname(url.isEmpty() ? null : url);
                    // Ollama needs a size (it is sent as num_ctx): an empty field keeps the saved one
                    Integer contextSize = parseContextSize();
                    if (contextSize != null) {
                        ollama.setContextWindowSize(contextSize);
                    }
                }
                case ChatCompletionsProperties chat -> {
                    if (!key.isEmpty() || removeKey) {
                        chat.setApiKey(key.isEmpty() ? null : key);
                    }
                    chat.setBaseUrl(url.isEmpty() || url.equals(chat.getDefaultBaseUrl()) ? null : url);
                    if (hasContextSize()) {
                        chat.setContextWindowSize(parseContextSize());
                    }
                }
                default -> {
                }
            }
            if (!model.isEmpty()) {
                properties.setModel(model);
            }
            return properties;
        }

        // The typed context size; null when empty. A value that is not a positive number can't be saved
        @Nullable
        private Integer parseContextSize() {
            String text = contextField.getText().trim().replace(",", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (text.isEmpty()) {
                return null;
            }
            int size = CommonUtils.toInt(text, -1);
            if (size <= 0) {
                throw new IllegalArgumentException(NLS.bind(AiMessages.ai_options_context_size_invalid, text));
            }
            return size;
        }

        private void test() {
            AIEngineProperties properties;
            try {
                properties = createProperties();
            } catch (Exception e) {
                statusLabel.setText(NLS.bind(AiMessages.ai_options_test_failed, e.getMessage()));
                return;
            }
            testButton.setEnabled(false);
            statusLabel.setText(AiMessages.ai_options_testing);
            new AbstractJob("Test AI provider") { //$NON-NLS-1$
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    List<AIModel> models = null;
                    String error = null;
                    String noTools = null;
                    try {
                        models = AIAgentProviders.loadModels(monitor, engineId, properties);
                        noTools = AIAgentProviders.checkToolCalls(monitor, engineId, properties);
                        // A measured fact, not a value of the form: the chat sends no tools to this model until a Test finds tool calls
                        AIAgentToolSets.setToolsOff(engineId, properties.getModel(), noTools != null);
                    } catch (Exception e) {
                        error = CommonUtils.notEmpty(e.getMessage());
                    }
                    List<String> result = models == null ? null : models.stream().map(AIModel::name).toList();
                    Integer modelContext = models == null ? null : models.stream()
                        .filter(m -> m.name().equals(properties.getModel()) && m.contextWindowSize() != null)
                        .map(AIModel::contextWindowSize).findFirst().orElse(null);
                    String failure = error;
                    String toolsFailure = noTools;
                    SwingUtilities.invokeLater(() -> {
                        if (modelContext != null && hasContextSize() && contextField.getText().isBlank()) {
                            // vLLM tells the model's max-model-len: the field the user would otherwise have to look up
                            contextField.setText(modelContext.toString());
                        }
                        testButton.setEnabled(true);
                        warningArea.setText(CommonUtils.notEmpty(toolsFailure));
                        warningArea.setVisible(failure == null && toolsFailure != null);
                        revalidate();
                        if (failure != null) {
                            statusLabel.setText(NLS.bind(AiMessages.ai_options_test_failed, failure));
                            statusLabel.setToolTipText(failure);
                            if (result == null) {
                                return;
                            }
                        } else if (toolsFailure != null) {
                            statusLabel.setText(NLS.bind(AiMessages.ai_options_test_no_tools, result.size()));
                            statusLabel.setToolTipText(null);
                        } else {
                            statusLabel.setText(NLS.bind(AiMessages.ai_options_test_ok, result.size()));
                            statusLabel.setToolTipText(null);
                        }
                        Object selected = modelCombo.getEditor().getItem();
                        List<String> all = new ArrayList<>(AIAgentProviders.getSuggestedModels(engineId));
                        for (String model : result) {
                            if (!all.contains(model)) {
                                all.add(model);
                            }
                        }
                        modelCombo.setModel(new DefaultComboBoxModel<>(all.toArray(String[]::new)));
                        modelCombo.setSelectedItem(selected);
                        AiAssistantPanel.rememberModels(engineId, result);
                    });
                    return Status.OK_STATUS;
                }
            }.schedule();
        }

        /**
         * Saves the form; a secure storage failure keeps its own text (the details alone don't tell that nothing went to a file).
         * The values are read again afterwards: a saved key shows as saved, the field empty.
         */
        @Override
        public void save() throws Exception {
            try {
                AIAgentProviders.saveProperties(engineId, createProperties());
            } catch (DBSecurityException e) {
                throw new DBSecurityException(NLS.bind(AiMessages.ai_options_save_failed_secure_storage, getName()), e);
            }
            if (toolSetCombo.getSelectedItem() instanceof AIAgentToolSets.ToolSet set) {
                AIAgentToolSets.setToolSet(engineId, set);
            }
            load();
        }

        @Override
        public void revert() {
            load();
        }
    }

    //////////////////////////////////////////////////////////
    // Policy

    /**
     * Rule choices of the policy table and the class combos; INHERIT removes the rule.
     */
    private enum Choice {
        INHERIT(null), AUTO(AIToolPermission.AUTO), CONFIRM(AIToolPermission.CONFIRM), BLOCKED(AIToolPermission.BLOCKED);

        @Nullable
        private final AIToolPermission permission;

        Choice(@Nullable AIToolPermission permission) {
            this.permission = permission;
        }

        @NotNull
        static Choice of(@Nullable String value) {
            for (Choice choice : values()) {
                if (choice.permission != null && choice.permission.name().equalsIgnoreCase(CommonUtils.notEmpty(value).trim())) {
                    return choice;
                }
            }
            return INHERIT;
        }

        @Override
        public String toString() {
            return permission == null ? AiMessages.ai_policy_inherit : describePermission(permission);
        }
    }

    @NotNull
    static String describePermission(@NotNull AIToolPermission permission) {
        return switch (permission) {
            case AUTO -> AiMessages.ai_policy_auto;
            case CONFIRM -> AiMessages.ai_policy_confirm;
            case BLOCKED -> AiMessages.ai_policy_blocked;
        };
    }

    private static final class PolicyTab extends JPanel implements OptionsSaving.Part {
        // The edited copy of the rules, and the saved rules to count and revert the changes
        private AIToolPolicy.Settings settings = AIToolPolicy.getInstance().getSettings();
        private AIToolPolicy.Settings saved = AIToolPolicy.getInstance().getSettings();
        private final List<AIFunctionDescriptor> tools = new ArrayList<>(AIToolCatalog.getTools(AIFunctionPurpose.ALL));
        private final JComboBox<Object> scopeCombo = new JComboBox<>();
        private final Map<AIToolAccess, JComboBox<Choice>> classCombos = new java.util.EnumMap<>(AIToolAccess.class);
        private final JTextField filterField = new JTextField(16);
        private final JLabel scopeNote = new JLabel(" "); //$NON-NLS-1$
        private final ToolTableModel tableModel = new ToolTableModel();
        private final JTable table = new JTable(tableModel);
        private final TableRowSorter<ToolTableModel> sorter = new TableRowSorter<>(tableModel);
        private boolean updating;

        PolicyTab() {
            super(new BorderLayout(0, 8));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            tools.sort(Comparator.comparing((AIFunctionDescriptor t) -> CommonUtils.notEmpty(t.getCategoryId()))
                .thenComparing(AIFunctionDescriptor::getId));

            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            for (String rule : List.of(AiMessages.ai_policy_rule_production, AiMessages.ai_policy_rule_read_only,
                AiMessages.ai_policy_rule_dangerous)) {
                // Wraps at spaces only (an HTML label broke Korean words when the Options window is narrow)
                JComponent note = WrappingNote.withIcon(UiIcons.get("info"), rule, 0); //$NON-NLS-1$
                note.setAlignmentX(LEFT_ALIGNMENT);
                note.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
                top.add(note);
            }
            top.add(Box.createVerticalStrut(4));

            scopeCombo.addItem(AiMessages.ai_policy_all_connections);
            for (DBPDataSourceContainer container : QueryLibraryEditor.getConnections()) {
                scopeCombo.addItem(container);
            }
            scopeCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DBPDataSourceContainer container) {
                        setText(AIToolPolicy.isProduction(container)
                            ? NLS.bind(AiMessages.ai_policy_connection_production, container.getName()) : container.getName());
                        setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
                    } else {
                        setIcon(null);
                    }
                    return this;
                }
            });
            scopeCombo.addActionListener(e -> scopeChanged());
            JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            scopeRow.add(new JLabel(AiMessages.ai_policy_scope));
            scopeRow.add(scopeCombo);
            for (AIToolAccess access : AIToolAccess.values()) {
                JComboBox<Choice> combo = new JComboBox<>(Choice.values());
                combo.addActionListener(e -> {
                    if (!updating && combo.getSelectedItem() instanceof Choice choice) {
                        setRule(AIToolPolicy.CLASS_KEY_PREFIX + access.name(), choice);
                        tableModel.fireTableDataChanged();
                    }
                });
                classCombos.put(access, combo);
                scopeRow.add(Box.createHorizontalStrut(6));
                scopeRow.add(new JLabel(NLS.bind(AiMessages.ai_policy_class_rule, describeAccess(access))));
                scopeRow.add(combo);
            }
            scopeRow.setAlignmentX(LEFT_ALIGNMENT);
            top.add(Box.createVerticalStrut(4));
            top.add(scopeRow);
            scopeNote.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.warningForeground"); //$NON-NLS-1$
            scopeNote.setAlignmentX(LEFT_ALIGNMENT);
            top.add(scopeNote);
            filterField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AiMessages.ai_policy_filter);
            filterField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
            filterField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    applyFilter();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    applyFilter();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            JPanel filterRow = new JPanel(new BorderLayout());
            filterRow.add(filterField, BorderLayout.WEST);
            filterRow.setAlignmentX(LEFT_ALIGNMENT);
            top.add(Box.createVerticalStrut(4));
            top.add(filterRow);
            add(top, BorderLayout.NORTH);

            table.setRowSorter(sorter);
            table.setDefaultEditor(Choice.class, new DefaultCellEditor(new JComboBox<>(Choice.values())));
            table.getColumnModel().getColumn(0).setPreferredWidth(220);
            table.getColumnModel().getColumn(1).setPreferredWidth(120);
            table.getColumnModel().getColumn(2).setPreferredWidth(80);
            table.getColumnModel().getColumn(3).setPreferredWidth(100);
            table.getColumnModel().getColumn(4).setPreferredWidth(140);
            table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                    if (value instanceof AIToolAccess access) {
                        setText(describeAccess(access));
                        if (!isSelected) {
                            setForeground(UIManager.getColor(access == AIToolAccess.DANGEROUS ? "Chromite.status.error" //$NON-NLS-1$
                                : access == AIToolAccess.WRITE ? "Chromite.status.warningForeground" : "Label.foreground")); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                    return this;
                }
            });
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(UIScale.scale(640), UIScale.scale(300)));
            add(scrollPane, BorderLayout.CENTER);
            scopeChanged();
        }

        @Nullable
        private DBPDataSourceContainer getScope() {
            return scopeCombo.getSelectedItem() instanceof DBPDataSourceContainer container ? container : null;
        }

        @NotNull
        private Map<String, String> getRules() {
            DBPDataSourceContainer scope = getScope();
            return scope == null ? settings.getDefaults() : settings.getConnections().computeIfAbsent(scope.getId(), id -> new java.util.LinkedHashMap<>());
        }

        private void setRule(@NotNull String key, @NotNull Choice choice) {
            Map<String, String> rules = getRules();
            if (choice.permission == null) {
                rules.remove(key);
            } else {
                rules.put(key, choice.permission.name());
            }
        }

        private void scopeChanged() {
            updating = true;
            try {
                Map<String, String> rules = getRules();
                for (Map.Entry<AIToolAccess, JComboBox<Choice>> e : classCombos.entrySet()) {
                    e.getValue().setSelectedItem(Choice.of(rules.get(AIToolPolicy.CLASS_KEY_PREFIX + e.getKey().name())));
                }
            } finally {
                updating = false;
            }
            DBPDataSourceContainer scope = getScope();
            if (scope != null && scope.isConnectionReadOnly()) {
                scopeNote.setText(AiMessages.ai_policy_scope_read_only);
            } else if (scope != null && AIToolPolicy.isProduction(scope)) {
                scopeNote.setText(AiMessages.ai_policy_scope_production);
            } else {
                scopeNote.setText(" "); //$NON-NLS-1$
            }
            tableModel.fireTableDataChanged();
        }

        private void applyFilter() {
            String text = filterField.getText().trim().toLowerCase(Locale.ROOT);
            sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends ToolTableModel, ? extends Integer> entry) {
                    AIFunctionDescriptor tool = tools.get(entry.getIdentifier());
                    return tool.getId().toLowerCase(Locale.ROOT).contains(text)
                        || tool.getName().toLowerCase(Locale.ROOT).contains(text)
                        || describeCategory(tool.getCategoryId()).toLowerCase(Locale.ROOT).contains(text);
                }
            });
        }

        @NotNull
        @Override
        public String getName() {
            return AiMessages.ai_options_policy;
        }

        /**
         * The rules (defaults and per connection) that differ from the saved ones.
         */
        @Override
        public int getChangeCount() {
            int count = countDifferences(settings.getDefaults(), saved.getDefaults());
            Set<String> connections = new HashSet<>(settings.getConnections().keySet());
            connections.addAll(saved.getConnections().keySet());
            for (String id : connections) {
                count += countDifferences(settings.getConnections().getOrDefault(id, Map.of()), saved.getConnections().getOrDefault(id, Map.of()));
            }
            return count;
        }

        private static int countDifferences(@NotNull Map<String, String> rules, @NotNull Map<String, String> savedRules) {
            Set<String> keys = new HashSet<>(rules.keySet());
            keys.addAll(savedRules.keySet());
            int count = 0;
            for (String key : keys) {
                if (!Objects.equals(rules.get(key), savedRules.get(key))) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void save() throws Exception {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            AIToolPolicy.getInstance().setSettings(settings);
            saved = AIToolPolicy.getInstance().getSettings();
        }

        @Override
        public void revert() {
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            settings = AIToolPolicy.getInstance().getSettings();
            saved = AIToolPolicy.getInstance().getSettings();
            scopeChanged();
        }

        private final class ToolTableModel extends AbstractTableModel {
            @Override
            public int getRowCount() {
                return tools.size();
            }

            @Override
            public int getColumnCount() {
                return 5;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> AiMessages.ai_policy_column_tool;
                    case 1 -> AiMessages.ai_policy_column_group;
                    case 2 -> AiMessages.ai_policy_column_class;
                    case 3 -> AiMessages.ai_policy_column_rule;
                    default -> AiMessages.ai_policy_column_effective;
                };
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case 2 -> AIToolAccess.class;
                    case 3 -> Choice.class;
                    default -> String.class;
                };
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3;
            }

            @Override
            public Object getValueAt(int row, int column) {
                AIFunctionDescriptor tool = tools.get(row);
                return switch (column) {
                    case 0 -> tool.getName() + " (" + tool.getId() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    case 1 -> describeCategory(tool.getCategoryId());
                    case 2 -> tool.getAccess();
                    case 3 -> Choice.of(getRules().get(tool.getId()));
                    default -> describeEffective(tool);
                };
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                if (column == 3 && value instanceof Choice choice) {
                    setRule(tools.get(row).getId(), choice);
                    fireTableRowsUpdated(row, row);
                }
            }
        }

        @NotNull
        private String describeEffective(@NotNull AIFunctionDescriptor tool) {
            DBPDataSourceContainer scope = getScope();
            AIToolAccess access = tool.getAccess();
            if (scope != null && access.isWrite() && tool.getWrites() == AIToolWrites.DATABASE && scope.isConnectionReadOnly()) {
                return AiMessages.ai_policy_effective_read_only;
            }
            AIToolDecision decision = AIToolPolicy.resolve(settings, scope, tool, access);
            if (tool.isAlwaysConfirmed() && decision.permission() != AIToolPermission.BLOCKED) {
                // The transaction guard asks whatever the rule allows (rollbackTransaction is READ, AUTO by default)
                return AiMessages.ai_policy_always_confirm;
            }
            return describePermission(decision.permission());
        }
    }

    @NotNull
    static String describeAccess(@NotNull AIToolAccess access) {
        return switch (access) {
            case READ -> AiMessages.ai_access_read;
            case WRITE -> AiMessages.ai_access_write;
            case DANGEROUS -> AiMessages.ai_access_dangerous;
        };
    }

    @NotNull
    static String describeCategory(@Nullable String categoryId) {
        return switch (CommonUtils.notEmpty(categoryId)) {
            case "agent.connections" -> AiMessages.ai_group_connections; //$NON-NLS-1$
            case "agent.metadata" -> AiMessages.ai_group_metadata; //$NON-NLS-1$
            case "agent.query" -> AiMessages.ai_group_query; //$NON-NLS-1$
            case "agent.data" -> AiMessages.ai_group_data; //$NON-NLS-1$
            case "agent.ddl" -> AiMessages.ai_group_ddl; //$NON-NLS-1$
            case "agent.transfer" -> AiMessages.ai_group_transfer; //$NON-NLS-1$
            case "agent.health" -> AiMessages.ai_group_health; //$NON-NLS-1$
            case "agent.performance" -> AiMessages.ai_group_performance; //$NON-NLS-1$
            case "agent.library" -> AiMessages.ai_group_library; //$NON-NLS-1$
            case "agent.editor" -> AiMessages.ai_group_editor; //$NON-NLS-1$
            default -> CommonUtils.notEmpty(categoryId);
        };
    }

    //////////////////////////////////////////////////////////
    // MCP server

    private static final class McpTab extends JPanel implements OptionsSaving.Part {
        private final JCheckBox enabledBox = new JCheckBox(AiMessages.ai_mcp_enable);
        private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(AIMcpSettings.getPort(), 1024, 65535, 1));
        private final JLabel statusLabel = new JLabel();
        private final JLabel urlLabel = new JLabel();
        private final JPasswordField tokenField = new JPasswordField(36);
        private final JToggleButton showTokenButton = new JToggleButton(AiMessages.ai_mcp_show);
        private final JTextArea commandArea = new JTextArea(3, 60);
        private final char echoChar;

        McpTab() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            enabledBox.setSelected(AIMcpSettings.isEnabled());
            // A toggle: saved when it is clicked, the server starts or stops at once (Options save standard)
            enabledBox.addActionListener(e -> {
                AIMcpSettings.setEnabled(enabledBox.isSelected());
                refresh();
            });
            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = addRow(form, row, null, enabledBox);
            JTextArea note = new WrappingNote(AiMessages.ai_mcp_note);
            note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            row = addRow(form, row, null, note);
            JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            portRow.add(portSpinner);
            portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#")); //$NON-NLS-1$
            row = addRow(form, row, AiMessages.ai_mcp_port, portRow);
            row = addRow(form, row, AiMessages.ai_mcp_status, statusLabel);
            row = addRow(form, row, AiMessages.ai_mcp_url, urlLabel);

            tokenField.setEditable(false);
            echoChar = tokenField.getEchoChar();
            showTokenButton.addActionListener(e -> tokenField.setEchoChar(showTokenButton.isSelected() ? (char) 0 : echoChar));
            JButton copyTokenButton = new JButton(AiMessages.ai_mcp_copy);
            copyTokenButton.addActionListener(e ->
                DBWorkbench.getPlatformUI().copyTextToClipboard(new String(tokenField.getPassword()), false));
            JButton regenerateButton = new JButton(AiMessages.ai_mcp_regenerate);
            regenerateButton.addActionListener(e -> regenerate());
            JPanel tokenRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            tokenRow.add(tokenField);
            for (JComponent button : List.of(showTokenButton, copyTokenButton, regenerateButton)) {
                tokenRow.add(Box.createHorizontalStrut(6));
                tokenRow.add(button);
            }
            row = addRow(form, row, AiMessages.ai_mcp_token, tokenRow);

            commandArea.setEditable(false);
            commandArea.setLineWrap(true);
            commandArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            JButton copyCommandButton = new JButton(AiMessages.ai_mcp_copy_command);
            copyCommandButton.addActionListener(e -> DBWorkbench.getPlatformUI().copyTextToClipboard(commandArea.getText(), false));
            JPanel commandPanel = new JPanel(new BorderLayout(0, 4));
            JLabel commandNote = new JLabel(AiMessages.ai_mcp_command_note);
            commandNote.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            commandPanel.add(commandNote, BorderLayout.NORTH);
            commandPanel.add(new JScrollPane(commandArea), BorderLayout.CENTER);
            JPanel commandButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            commandButtons.add(copyCommandButton);
            commandPanel.add(commandButtons, BorderLayout.SOUTH);
            addRow(form, row, AiMessages.ai_mcp_command, commandPanel);
            add(form, BorderLayout.NORTH);
            portSpinner.addChangeListener(e -> updateCommand());
            refresh();
        }

        private static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.insets = new Insets(4, 0, 4, 10);
            if (label != null) {
                c.gridx = 0;
                form.add(new JLabel(label), c);
            }
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(field, c);
            return row + 1;
        }

        private void refresh() {
            AIMcpServer server = AIMcpServer.getInstance();
            if (server.isRunning()) {
                statusLabel.setText(NLS.bind(AiMessages.ai_mcp_running, server.getPort()));
                statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.success"); //$NON-NLS-1$
            } else if (server.getLastError() != null) {
                statusLabel.setText(NLS.bind(AiMessages.ai_mcp_failed, server.getLastError()));
                statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
            } else {
                statusLabel.setText(AiMessages.ai_mcp_stopped);
                statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            }
            try {
                tokenField.setText(AIMcpSettings.getToken());
            } catch (Exception e) {
                tokenField.setText(""); //$NON-NLS-1$
                log.warn("Can't read the MCP token", e);
            }
            updateCommand();
        }

        private void updateCommand() {
            String url = "http://127.0.0.1:" + portSpinner.getValue() + AIMcpServer.PATH; //$NON-NLS-1$
            urlLabel.setText(url);
            commandArea.setText(AIMcpSettings.getClaudeCodeCommand().replace(AIMcpSettings.getUrl(), url));
        }

        private void regenerate() {
            if (!SwingPlatformUI.confirmChange(this, AiMessages.ai_mcp_regenerate_question, AiMessages.ai_mcp_regenerate,
                AiMessages.ai_mcp_regenerate, JOptionPane.WARNING_MESSAGE)) {
                return;
            }
            try {
                tokenField.setText(AIMcpSettings.regenerateToken());
            } catch (Exception e) {
                ErrorDialog.show(this, AiMessages.ai_mcp_regenerate, null, e);
            }
        }

        @NotNull
        @Override
        public String getName() {
            return AiMessages.ai_mcp_port;
        }

        @Override
        public int getChangeCount() {
            return ((Number) portSpinner.getValue()).intValue() == AIMcpSettings.getPort() ? 0 : 1;
        }

        @Override
        public void save() {
            AIMcpSettings.setPort(((Number) portSpinner.getValue()).intValue());
            refresh();
        }

        @Override
        public void revert() {
            portSpinner.setValue(AIMcpSettings.getPort());
        }
    }

    //////////////////////////////////////////////////////////
    // Audit log

    private static final class AuditTab extends JPanel {
        private static final String ALL = "*"; //$NON-NLS-1$
        // Table.cellMargins 8 + 8, and the header's sort arrow
        private static final int LABEL_COLUMN_PADDING = 28;
        private final JTextField filterField = new JTextField(18);
        private final JComboBox<String> clientCombo = new JComboBox<>(new String[]{ALL, "CHAT", "MCP"}); //$NON-NLS-1$ //$NON-NLS-2$
        private final JComboBox<String> outcomeCombo = new JComboBox<>(new String[]{ALL, "SUCCESS", "ERROR", "DENIED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "BLOCKED", "READ_ONLY", "CANCELED", "INVALID"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        private final JLabel countLabel = new JLabel(" "); //$NON-NLS-1$
        private final AuditTableModel model = new AuditTableModel();
        private final JTable table = new JTable(model);
        private final JTextArea detailArea = new JTextArea(6, 60);
        private List<JsonObject> all = List.of();

        AuditTab() {
            super(new BorderLayout(0, 6));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            filterField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AiMessages.ai_audit_filter);
            filterField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
            DefaultListCellRenderer allRenderer = new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (ALL.equals(value)) {
                        setText(AiMessages.ai_audit_all);
                    } else if (value instanceof String s && list.getModel() == outcomeCombo.getModel()) {
                        setText(AiToolCard.describeOutcome(s));
                    } else if ("CHAT".equals(value)) { //$NON-NLS-1$
                        setText(AiMessages.ai_audit_client_chat);
                    } else if ("MCP".equals(value)) { //$NON-NLS-1$
                        setText(AiMessages.ai_audit_client_mcp);
                    }
                    return this;
                }
            };
            clientCombo.setRenderer(allRenderer);
            outcomeCombo.setRenderer(allRenderer);
            filterField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    applyFilter();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    applyFilter();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            clientCombo.addActionListener(e -> applyFilter());
            outcomeCombo.addActionListener(e -> applyFilter());
            JButton refreshButton = new JButton(AiMessages.ai_audit_refresh, UiIcons.get("refresh")); //$NON-NLS-1$
            refreshButton.addActionListener(e -> load());
            JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            filters.add(filterField);
            filters.add(new JLabel(AiMessages.ai_audit_client));
            filters.add(clientCombo);
            filters.add(new JLabel(AiMessages.ai_audit_outcome));
            filters.add(outcomeCombo);
            filters.add(refreshButton);
            filters.add(countLabel);
            add(filters, BorderLayout.NORTH);

            table.setAutoCreateRowSorter(true);
            table.getSelectionModel().addListSelectionListener(e -> showDetail());
            // Free text: tool, connection, ms
            TableColumnModel columns = table.getColumnModel();
            columns.getColumn(2).setPreferredWidth(150);
            columns.getColumn(4).setPreferredWidth(140);
            columns.getColumn(7).setPreferredWidth(60);
            // Fixed format and fixed labels: time (0 is about the widest digit), client, class, policy, result
            fitLabels(0, List.of("0000-00-00 00:00:00")); //$NON-NLS-1$
            fitLabels(1, List.of(AiMessages.ai_audit_client_chat, AiMessages.ai_audit_client_mcp));
            fitLabels(3, Arrays.stream(AIToolAccess.values()).map(AiOptionsPage::describeAccess).toList());
            fitLabels(5, Arrays.stream(AIToolPermission.values()).map(AiOptionsPage::describePermission).toList());
            fitLabels(6, Arrays.stream(AIToolOutcome.values()).map(outcome -> AiToolCard.describeOutcome(outcome.name())).toList());
            JScrollPane tableScroll = new JScrollPane(table);
            tableScroll.setPreferredSize(new Dimension(UIScale.scale(640), UIScale.scale(240)));
            detailArea.setEditable(false);
            detailArea.setLineWrap(true);
            detailArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, new JScrollPane(detailArea));
            split.setResizeWeight(0.75);
            split.setBorder(BorderFactory.createEmptyBorder());
            add(split, BorderLayout.CENTER);
            JLabel fileLabel = new JLabel(NLS.bind(AiMessages.ai_audit_file, AIToolAuditLog.getInstance().getFile()));
            fileLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            fileLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            add(fileLabel, BorderLayout.SOUTH);
            load();
        }

        /**
         * Sizes a column whose values are fixed labels (or have a fixed format) to its longest label or its header,
         * with the cell margins and room for the sort arrow. Fixed numbers clipped "Chromite Agent" (client),
         * "Run automatically" (policy) and "Read-only connection" (result). The width is also the minimum, so fitting
         * the table to its width shrinks only the free text columns and never clips these.
         */
        private void fitLabels(int column, @NotNull List<String> labels) {
            FontMetrics metrics = table.getFontMetrics(table.getFont());
            int width = metrics.stringWidth(model.getColumnName(column));
            for (String label : labels) {
                width = Math.max(width, metrics.stringWidth(label));
            }
            width += UIScale.scale(LABEL_COLUMN_PADDING);
            table.getColumnModel().getColumn(column).setMinWidth(width);
            table.getColumnModel().getColumn(column).setPreferredWidth(width);
        }

        private void load() {
            countLabel.setText(AiMessages.ai_audit_loading);
            new AbstractJob("Read AI audit log") { //$NON-NLS-1$
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    List<JsonObject> records = AIToolAuditLog.getInstance().readRecent(AUDIT_LIMIT);
                    SwingUtilities.invokeLater(() -> {
                        all = records;
                        applyFilter();
                    });
                    return Status.OK_STATUS;
                }
            }.schedule();
        }

        private void applyFilter() {
            String text = filterField.getText().trim().toLowerCase(Locale.ROOT);
            String client = (String) clientCombo.getSelectedItem();
            String outcome = (String) outcomeCombo.getSelectedItem();
            List<JsonObject> rows = new ArrayList<>();
            for (JsonObject record : all) {
                if (!ALL.equals(client) && !client.equals(get(record, "client"))) { //$NON-NLS-1$
                    continue;
                }
                if (!ALL.equals(outcome) && !outcome.equals(get(record, "outcome"))) { //$NON-NLS-1$
                    continue;
                }
                if (!text.isEmpty() && !searchText(record).contains(text)) {
                    continue;
                }
                rows.add(record);
            }
            model.setRows(rows);
            countLabel.setText(NLS.bind(AiMessages.ai_audit_count, rows.size(), all.size()));
        }

        private void showDetail() {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                detailArea.setText(""); //$NON-NLS-1$
                return;
            }
            JsonObject record = model.rows.get(table.convertRowIndexToModel(viewRow));
            detailArea.setText(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(record));
            detailArea.setCaretPosition(0);
        }

        /**
         * What the text filter searches: the record and, for a record written under a former tool id, the tool it became, so
         * that the current id finds the old records too (the old id finds them by the record itself).
         */
        @NotNull
        private static String searchText(@NotNull JsonObject record) {
            String text = record.toString();
            String tool = get(record, "tool"); //$NON-NLS-1$
            if (!tool.isEmpty()) {
                String current = AIToolCatalog.canonicalId(tool);
                if (!current.equals(tool)) {
                    text += ' ' + current;
                }
            }
            return text.toLowerCase(Locale.ROOT);
        }

        @NotNull
        private static String get(@NotNull JsonObject record, @NotNull String name) {
            JsonElement value = record.get(name);
            return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString(); //$NON-NLS-1$
        }

        @NotNull
        private static <E extends Enum<E>> String describeEnum(
            @NotNull Class<E> type,
            @NotNull String value,
            @NotNull java.util.function.Function<E, String> describe
        ) {
            E constant = CommonUtils.valueOf(type, value, null);
            return constant == null ? value : describe.apply(constant);
        }

        private static final class AuditTableModel extends AbstractTableModel {
            private static final String[] FIELDS = {"time", "client", "tool", "access", "connectionName", "permission", "outcome", "durationMs"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            private List<JsonObject> rows = List.of();

            void setRows(@NotNull List<JsonObject> rows) {
                this.rows = rows;
                fireTableDataChanged();
            }

            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return FIELDS.length;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> AiMessages.ai_audit_column_time;
                    case 1 -> AiMessages.ai_audit_column_client;
                    case 2 -> AiMessages.ai_audit_column_tool;
                    case 3 -> AiMessages.ai_audit_column_class;
                    case 4 -> AiMessages.ai_audit_column_connection;
                    case 5 -> AiMessages.ai_audit_column_policy;
                    case 6 -> AiMessages.ai_audit_column_outcome;
                    default -> AiMessages.ai_audit_column_ms;
                };
            }

            @Override
            public Object getValueAt(int row, int column) {
                String value = get(rows.get(row), FIELDS[column]);
                return switch (column) {
                    // ISO time without the offset and fraction: 2026-11-07 10:02:03
                    case 0 -> value.length() >= 19 ? value.substring(0, 19).replace('T', ' ') : value;
                    case 1 -> value.equals("CHAT") ? AiMessages.ai_audit_client_chat : value; //$NON-NLS-1$
                    // A merged tool's operation (changeSchema (dropTable)); records from before keep their tool id
                    case 2 -> get(rows.get(row), "operation").isEmpty() ? value : value + " (" + get(rows.get(row), "operation") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    case 3 -> describeEnum(AIToolAccess.class, value, AiOptionsPage::describeAccess);
                    case 5 -> describeEnum(AIToolPermission.class, value, AiOptionsPage::describePermission);
                    case 6 -> value.isEmpty() ? value : AiToolCard.describeOutcome(value);
                    default -> value;
                };
            }
        }
    }
}
