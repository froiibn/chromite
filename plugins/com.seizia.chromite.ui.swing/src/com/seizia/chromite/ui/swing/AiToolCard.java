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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.agent.AIToolExecutor;
import com.seizia.chromite.model.ai.tools.chat.AIAgentChat;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool call in the assistant's conversation: a one-line header (name, class badge, state, duration, result summary) that
 * expands to the arguments and the result.
 *
 * 설계: 도구 호출은 대화 흐름 안에 접힌 카드로 둔다. 한 질문에 도구가 수십 번 불릴 수 있어 펼친 채로 두면 답이 묻힌다. 실패·거부·막힘은
 * 접힌 상태에서도 머리말의 상태 글과 색으로 보인다. 결과는 도구가 이미 크기를 제한해 돌려주지만(AgentLimits) 화면에는 앞부분만 보이고
 * 요약 줄(배열 항목 수, 잘림 여부)을 머리말에 단다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 트랜잭션 질문에 Commit/Roll back으로 답한 호출은 머리말 상태 앞에 "열린 트랜잭션 n개 롤백 →"을 보임(전에는 "완료"만 보여
 *   롤백으로 답한 commitTransaction이 커밋처럼 보였음)
 */
final class AiToolCard extends JPanel {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final int MAX_SHOWN_CHARS = 6000;
    private static final String DB_PREFIX = "db_"; //$NON-NLS-1$

    private final AIAgentChat.Entry entry;
    private final JLabel chevron = new JLabel();
    private final JLabel nameLabel = new JLabel();
    private final JPanel badgeHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel stateLabel = new JLabel();
    private final JPanel details = new JPanel(new BorderLayout(0, 4));
    private boolean expanded;

    AiToolCard(@NotNull AIAgentChat.Entry entry) {
        super(new BorderLayout());
        this.entry = entry;
        setOpaque(false);
        putClientProperty(FlatClientProperties.STYLE, "border: 4,8,4,8,$Component.borderColor,1,8"); //$NON-NLS-1$

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(chevron);
        nameLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        left.add(nameLabel);
        badgeHolder.setOpaque(false);
        left.add(badgeHolder);
        header.add(left, BorderLayout.WEST);
        stateLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        header.add(stateLabel, BorderLayout.CENTER);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setExpanded(!expanded);
            }
        });
        add(header, BorderLayout.NORTH);
        details.setOpaque(false);
        details.setVisible(false);
        add(details, BorderLayout.CENTER);
        update();
    }

    /**
     * Shows the current state of the entry (running or finished).
     */
    void update() {
        String toolId = getToolId(entry);
        AIFunctionDescriptor tool = AIToolCatalog.getTool(toolId);
        nameLabel.setText(tool != null ? tool.getName() : toolId);
        nameLabel.setToolTipText(toolId);
        badgeHolder.removeAll();
        String access = entry.getAccess() != null ? entry.getAccess() : tool != null ? tool.getAccess().name() : null;
        if (access != null) {
            badgeHolder.add(createAccessBadge(access));
        }
        chevron.setText(expanded ? "▾" : "▸"); //$NON-NLS-1$ //$NON-NLS-2$
        String outcome = entry.getOutcome();
        if (outcome == null) {
            stateLabel.setText(AiMessages.ai_tool_running);
            stateLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        } else {
            String state = describeOutcome(outcome);
            // A Commit / Roll back answer to the transaction question was carried out before the tool ran: it comes first,
            // so that it stays visible when a narrow tool window cuts the end of the line
            String before = describeTransactionsBeforeCall(entry.getResult());
            if (before != null) {
                state = NLS.bind(AiMessages.ai_tool_state_after_transaction, before, state);
            }
            String summary = outcome.equals("SUCCESS") ? summarize(entry.getResult()) : errorText(entry.getResult()); //$NON-NLS-1$
            stateLabel.setText(NLS.bind(AiMessages.ai_tool_state, new Object[]{state, formatDuration(entry.getDurationMs()),
                summary == null ? "" : " - " + summary})); //$NON-NLS-1$ //$NON-NLS-2$
            stateLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: " + (outcome.equals("SUCCESS") //$NON-NLS-1$ //$NON-NLS-2$
                ? "$Chromite.secondaryForeground" : outcome.equals("ERROR") ? "$Chromite.status.error" : "$Chromite.status.warning")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        stateLabel.setToolTipText(stateLabel.getText());
        if (expanded) {
            fillDetails();
        }
        revalidate();
        repaint();
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        if (expanded) {
            fillDetails();
        }
        details.setVisible(expanded);
        update();
    }

    private void fillDetails() {
        details.removeAll();
        JTextArea arguments = createText(PRETTY.toJson(entry.getArguments()));
        details.add(createLabeled(AiMessages.ai_tool_arguments, arguments), BorderLayout.NORTH);
        String result = entry.getResult();
        if (result != null) {
            details.add(createLabeled(AiMessages.ai_tool_result, createText(pretty(result))), BorderLayout.CENTER);
        }
    }

    @NotNull
    private static JComponent createLabeled(@NotNull String title, @NotNull JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setOpaque(false);
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    @NotNull
    private static JTextArea createText(@NotNull String text) {
        JTextArea area = new JTextArea(text.length() > MAX_SHOWN_CHARS
            ? text.substring(0, MAX_SHOWN_CHARS) + "\n" + AiMessages.ai_tool_result_cut : text); //$NON-NLS-1$
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        area.putClientProperty(FlatClientProperties.STYLE, "background: $Chromite.editorBackground"); //$NON-NLS-1$
        return area;
    }

    /**
     * Tool ID without the toolbox prefix the engines use ({@code db_explainQuery} → {@code explainQuery}).
     */
    @NotNull
    static String getToolId(@NotNull AIAgentChat.Entry entry) {
        String function = entry.getFunction() == null ? "" : entry.getFunction(); //$NON-NLS-1$
        return function.startsWith(DB_PREFIX) ? function.substring(DB_PREFIX.length()) : function;
    }

    @NotNull
    static String describeOutcome(@NotNull String outcome) {
        return switch (outcome) {
            case "SUCCESS" -> AiMessages.ai_outcome_success; //$NON-NLS-1$
            case "ERROR" -> AiMessages.ai_outcome_error; //$NON-NLS-1$
            case "DENIED" -> AiMessages.ai_outcome_denied; //$NON-NLS-1$
            case "BLOCKED" -> AiMessages.ai_outcome_blocked; //$NON-NLS-1$
            case "READ_ONLY" -> AiMessages.ai_outcome_read_only; //$NON-NLS-1$
            case "CANCELED" -> AiMessages.ai_outcome_canceled; //$NON-NLS-1$
            case "INVALID" -> AiMessages.ai_outcome_invalid; //$NON-NLS-1$
            default -> outcome;
        };
    }

    @NotNull
    static String formatDuration(long ms) {
        return ms < 1000 ? NLS.bind(AiMessages.ai_duration_ms, ms) : NLS.bind(AiMessages.ai_duration_s, String.format("%.1f", ms / 1000.0)); //$NON-NLS-1$
    }

    /**
     * One line about a JSON result: the sizes of its arrays, truncation and an open transaction.
     */
    @Nullable
    static String summarize(@Nullable String result) {
        if (result == null) {
            return null;
        }
        try {
            JsonElement json = JsonParser.parseString(result);
            if (json.isJsonArray()) {
                return NLS.bind(AiMessages.ai_summary_items, json.getAsJsonArray().size());
            }
            if (!json.isJsonObject()) {
                return null;
            }
            JsonObject object = json.getAsJsonObject();
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                if (e.getValue().isJsonArray() && parts.size() < 3) {
                    JsonArray array = e.getValue().getAsJsonArray();
                    parts.add(e.getKey() + " " + array.size()); //$NON-NLS-1$
                }
            }
            if (object.has("truncated") || object.has("hasMore") && object.get("hasMore").isJsonPrimitive() && object.get("hasMore").getAsBoolean()) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                parts.add(AiMessages.ai_summary_truncated);
            }
            if (object.has("transactionOpen") && object.get("transactionOpen").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                && object.get("transactionOpen").getAsBoolean()) { //$NON-NLS-1$
                parts.add(AiMessages.ai_summary_transaction_open);
            }
            if (object.has("updateCount")) { //$NON-NLS-1$
                parts.add(NLS.bind(AiMessages.ai_summary_update_count, object.get("updateCount").toString())); //$NON-NLS-1$
            }
            return parts.isEmpty() ? null : String.join(", ", parts); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What the user's Commit / Roll back answer did before the call ({@link AIToolExecutor#RESULT_TRANSACTIONS_BEFORE_CALL}).
     */
    @Nullable
    static String describeTransactionsBeforeCall(@Nullable String result) {
        if (result == null) {
            return null;
        }
        try {
            JsonElement before = JsonParser.parseString(result).getAsJsonObject().get(AIToolExecutor.RESULT_TRANSACTIONS_BEFORE_CALL);
            if (before == null || !before.isJsonObject()) {
                return null;
            }
            JsonObject object = before.getAsJsonObject();
            String count = object.has("count") ? object.get("count").getAsString() : "1"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return NLS.bind("COMMITTED".equals(object.get("action").getAsString()) //$NON-NLS-1$ //$NON-NLS-2$
                ? AiMessages.ai_tool_transaction_committed : AiMessages.ai_tool_transaction_rolled_back, count);
        } catch (RuntimeException e) {
            // Not a JSON object
            return null;
        }
    }

    @Nullable
    private static String errorText(@Nullable String result) {
        if (result == null) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(result).getAsJsonObject();
            if (object.has("error")) { //$NON-NLS-1$
                String text = object.get("error").getAsString(); //$NON-NLS-1$
                return text.length() > 160 ? text.substring(0, 160) + "..." : text; //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            // Not JSON
        }
        return null;
    }

    @NotNull
    private static String pretty(@NotNull String json) {
        try {
            return PRETTY.toJson(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return json;
        }
    }

    /**
     * Class badge: READ neutral, WRITE warning, DANGEROUS error colors (theme keys, so light and dark work).
     */
    @NotNull
    static JComponent createAccessBadge(@NotNull String access) {
        return switch (access) {
            case "WRITE" -> createBadge(AiMessages.ai_access_write, "Chromite.status.warning"); //$NON-NLS-1$ //$NON-NLS-2$
            case "DANGEROUS" -> createBadge(AiMessages.ai_access_dangerous, "Chromite.status.error"); //$NON-NLS-1$ //$NON-NLS-2$
            default -> createBadge(AiMessages.ai_access_read, "Chromite.secondaryForeground"); //$NON-NLS-1$
        };
    }

    /**
     * A small rounded label with a colored outline and text.
     */
    @NotNull
    static JComponent createBadge(@NotNull String text, @NotNull String colorKey) {
        JLabel badge = new JLabel(text);
        badge.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        badge.putClientProperty(FlatClientProperties.STYLE, "foreground: $" + colorKey //$NON-NLS-1$
            + "; border: 0,5,0,5,$" + colorKey + ",1,8"); //$NON-NLS-1$ //$NON-NLS-2$
        return badge;
    }
}
