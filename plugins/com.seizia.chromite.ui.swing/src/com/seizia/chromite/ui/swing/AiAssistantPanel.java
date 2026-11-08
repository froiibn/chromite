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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.AIUsage;
import com.seizia.chromite.model.ai.registry.AISettingsEventListener;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.ai.tools.chat.AIAgentChat;
import com.seizia.chromite.model.ai.tools.chat.AIAgentChatRunner;
import com.seizia.chromite.model.ai.tools.chat.AIAgentChatStore;
import com.seizia.chromite.model.ai.tools.chat.AIAgentProviders;
import com.seizia.chromite.model.ai.tools.mcp.AIAgentPlaybooks;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "Chromite Agent" tool window: conversations with an AI model that works on the user's connections through the agent
 * tools (docs/chromite/ai-agent.md "2단계").
 *
 * 설계:
 * <ul>
 *     <li>대화 실행은 모델 번들의 {@link AIAgentChatRunner}가 작업({@link AbstractJob}) 안에서 한다. 이 창은 EDT에서 화면만 고친다(모델 호출을 EDT에서
 *     하지 않는다). 작업의 진행은 {@link AIAgentChatRunner.Listener}로 받아 {@link SwingUtilities#invokeLater}로 넘긴다. 글 조각은 짧은 타이머로 모아
 *     다시 그린다(조각마다 Markdown을 다시 해석하지 않게). 그릴 항목은 하나가 아니라 모음({@code pendingRender})이다. 도구가 타이머 간격보다
 *     빨리 끝나면 다음 응답의 글이 타이머 전에 오는데, 항목 하나만 기억하면 앞 글의 남은 조각이 그려지지 않아 첫 조각에서 멈춘다.</li>
 *     <li>제공자·모델·연결·데이터베이스는 대화마다 저장한다. 새 대화는 활성 쿼리 창의 연결과 현재 데이터베이스(쿼리 창이 쓰는 기본 실행 문맥의
 *     기본 카탈로그·스키마)로 시작한다. 모델은 이 연결을 도구의 기본 {@code connection} 인수로 쓴다.</li>
 *     <li>제공자와 모델은 입력란 아래 줄 왼쪽의 콤보 하나({@code modelPicker})로 고른다. 목록은 제공자 이름(고를 수 없는 제목) 아래에 그 제공자의
 *     모델을 묶고, 설정되지 않은 제공자는 "설정 필요" 항목(고르면 설정)을, 끝에는 "모델 이름 직접 입력"을 둔다. 제목과 동작 항목은 대화의 모델이
 *     아니므로 고르는 순간이 아니라 목록이 닫힐 때 처리하고 선택을 대화의 모델로 되돌린다. 키보드로 목록을 지나가는 것만으로 동작이 실행되지 않게
 *     하기 위해서다.</li>
 *     <li>대화 기록은 도구 막대의 단추가 여는 팝업 목록(거르기 입력란, 최근 순)이다. 대화를 늘 보이는 콤보로 두지 않고 필요할 때 여는 목록으로 두어
 *     맨 위 줄을 도구 막대만 남겼다. 빈 대화는 목록에 넣지 않는다(빈 대화는 새 대화로 다시 쓴다).</li>
 *     <li>플레이북 단추는 MCP 프롬프트와 같은 글({@link AIAgentPlaybooks#getText})을 채워 보낸다. 대화에는 짧은 이름만 보인다.</li>
 *     <li>쓰기 도구의 확인과 트랜잭션 질문은 {@link AiConfirmationDialog}가 한다(실행기가 부름). 이 창은 그 결과를 도구 카드로 보여 줄 뿐이다.</li>
 *     <li>실행 중에는 대화를 바꾸거나 지울 수 없다. 작업이 쓰는 대화 객체를 화면이 바꾸지 않게 하기 위해서다. 중지는 작업을 취소하고(모니터 취소),
 *     실행기가 HTTP 스트림을 닫고 실행 중인 도구와 열린 확인 대화상자를 끝낸다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 화면 이름을 Chromite Agent로 바꾼 데 맞춰 화면 문구를 인용한 주석을 고침(docs/chromite/ai-agent.md "명칭")
 * - 2026-11-07 새 대화와 "이 쿼리에 대해 묻기"의 현재 데이터베이스를 쿼리 창의 실행 문맥에서 읽음({@link #getCurrentDatabase(SqlEditorPanel)},
 *   쿼리 창마다 별도 연결, docs/chromite/transactions.md)
 * - 2026-11-08 대화 삭제 확인은 대화 삭제 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 다시 그릴 글 항목을 모음으로 둠: 도구 뒤 글이 첫 조각에서 멈추던 문제(docs/chromite/ai-agent.md "글 조각 그리기")
 * - 2026-11-08 실행 중인 도구의 진행 글로 도구 카드를 고침({@code toolProgress}, 백업·복원 도구)
 * - 2026-11-08 설정 단추(도구 막대, 공급자 미설정 안내)가 Options를 Chromite Agent 페이지(Providers 탭)로 엶
 *   (docs/chromite/ai-agent.md "설정 열기")
 * - 2026-11-08 위쪽 제공자·모델 줄을 없애고 제공자를 함께 고르는 모델 선택 하나를 보내기와 같은 줄 왼쪽에 둠, 맨 위 대화 콤보를 없애고
 *   대화 기록 팝업(거르기, 이동, 삭제)을 둠, 차례가 끝나면 토큰 사용량을 상태 줄에 보임 (docs/chromite/ai-agent.md "채팅 도구 창")
 */
final class AiAssistantPanel extends JPanel {

    private static final Log log = Log.getLog(AiAssistantPanel.class);

    private static final String PREF_ENGINE = "ai.chat.engine"; //$NON-NLS-1$
    private static final String PREF_MODEL_PREFIX = "ai.chat.model."; //$NON-NLS-1$
    private static final String PREF_MODELS_PREFIX = "ai.chat.models."; //$NON-NLS-1$
    private static final int RENDER_DELAY_MS = 80;
    private static final int TITLE_CHARS = 48;

    /**
     * What the tool window needs from the main window.
     */
    interface Host {
        /** The active query window, or null. */
        @Nullable
        SqlEditorPanel getActiveEditor();

        /** A new query window with the text (not executed). */
        void openSqlEditor(@Nullable DBPDataSourceContainer connection, @NotNull String sql, @Nullable String title);

        /** Shows the Chromite Agent tool window. */
        void showAssistant();

        /** Tools &gt; Options at a page ({@link OptionsDialog#PAGE_AGENT}). */
        void openOptions(@NotNull String pageId);
    }

    private record Playbook(@NotNull String name, @NotNull String label, boolean needsSql) {
    }

    /** A provider's model in the model picker. */
    private record ModelChoice(@NotNull String engineId, @Nullable String model) {
    }

    /** A provider heading in the model picker; not a selection. */
    private record ProviderHeading(@NotNull String engineId) {
    }

    /** "Setup required" under a provider that is not configured: opens the settings. */
    private record SetupItem(@NotNull String engineId) {
    }

    /** "Enter Model Name" at the end of the model picker. */
    private enum PickerAction {
        ENTER_MODEL
    }

    private final Host host;
    private final List<AIAgentChat> chats = new ArrayList<>();
    @Nullable
    private AIAgentChat chat;
    @Nullable
    private AbstractJob runningJob;

    private final JComboBox<Object> modelPicker = new JComboBox<>() {
        // As wide as the longest "provider · model" in the Send row, not wider (BoxLayout grows a component up to its maximum)
        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    };
    private final JComboBox<Object> connectionCombo = new JComboBox<>();
    private final JComboBox<String> databaseCombo = new JComboBox<>();
    private final JPanel transcript = new TranscriptPanel();
    private final JScrollPane transcriptScroll;
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton sendButton = new JButton(AiMessages.ai_send);
    private final JButton stopButton = new JButton(AiMessages.ai_stop);
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JPanel setupNotice = new JPanel(new BorderLayout(8, 0));
    private final Action newChatAction;
    private final Action deleteChatAction;
    private final Action historyAction;
    private final Action playbooksAction;
    private final Map<String, JComponent> entryViews = new HashMap<>();
    private final Timer renderTimer;
    private final AISettingsEventListener settingsListener = registry -> SwingUtilities.invokeLater(this::settingsChanged);
    // Text entries with appended text not drawn yet (see the class comment)
    private final Set<AIAgentChat.Entry> pendingRender = new LinkedHashSet<>();
    private boolean updating;
    // The model picker's list was closed with Escape or a click outside: the selection goes back to the conversation's model
    private boolean pickCanceled;
    // Tokens of the last turn, shown in the status line when the turn ends
    @Nullable
    private AIUsage turnUsage;

    AiAssistantPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);

        newChatAction = createAction(AiMessages.ai_new_chat, this::newChat);
        deleteChatAction = createAction(AiMessages.ai_delete_chat, this::deleteChat);
        historyAction = createAction(AiMessages.ai_history, null);
        playbooksAction = createAction(AiMessages.ai_playbooks, null);

        modelPicker.setRenderer(new ModelPickerRenderer());
        modelPicker.addActionListener(e -> {
            // A pick in the open list is handled when the list closes (see the class comment)
            if (!updating && !modelPicker.isPopupVisible()) {
                modelPicked();
            }
        });
        modelPicker.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                pickCanceled = false;
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // After the list has set the picked item
                SwingUtilities.invokeLater(() -> {
                    if (!updating) {
                        modelPicked();
                    }
                });
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                pickCanceled = true;
            }
        });
        connectionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBPDataSourceContainer container) {
                    setText(container.getName());
                    setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
                } else {
                    setText(AiMessages.ai_no_connection);
                    setIcon(null);
                }
                return this;
            }
        });
        connectionCombo.setToolTipText(AiMessages.ai_connection_tip);
        connectionCombo.addActionListener(e -> {
            if (!updating) {
                connectionChanged(connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer c ? getCurrentDatabase(c) : null);
            }
        });
        databaseCombo.setEditable(true);
        databaseCombo.setToolTipText(AiMessages.ai_database_tip);
        databaseCombo.addActionListener(e -> {
            if (!updating) {
                contextChanged();
            }
        });

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(createToolBar());
        top.add(createRow(connectionCombo, databaseCombo));
        add(top, BorderLayout.NORTH);

        transcriptScroll = new JScrollPane(transcript, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        transcriptScroll.getViewport().setOpaque(false);
        transcriptScroll.setOpaque(false);
        transcriptScroll.getVerticalScrollBar().setUnitIncrement(16);
        add(transcriptScroll, BorderLayout.CENTER);
        add(createInput(), BorderLayout.SOUTH);

        renderTimer = new Timer(RENDER_DELAY_MS, e -> renderPending());
        renderTimer.setRepeats(false);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refreshConnections();
                updateSetupNotice();
                if (chat != null && chat.isEmpty() && runningJob == null) {
                    applyActiveEditorContext();
                }
            }
        });
        AISettingsManager.getInstance().addChangedListener(settingsListener);
        loadChats();
    }

    //////////////////////////////////////////////////////////
    // Layout

    /**
     * The top row: the tool bar alone, at the left like the other tool windows' tool bars.
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(newChatAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(deleteChatAction, "close")); //$NON-NLS-1$
        JButton historyButton = UiIcons.createActionButton(historyAction, "history"); //$NON-NLS-1$
        historyButton.addActionListener(e -> showHistory(historyButton, "")); //$NON-NLS-1$
        toolBar.add(historyButton);
        toolBar.addSeparator();
        JButton playbooksButton = UiIcons.createActionButton(playbooksAction, "library"); //$NON-NLS-1$
        playbooksButton.addActionListener(e -> showPlaybooks(playbooksButton));
        toolBar.add(playbooksButton);
        toolBar.add(UiIcons.createActionButton(createAction(AiMessages.ai_settings, () -> host.openOptions(OptionsDialog.PAGE_AGENT)), "settings")); //$NON-NLS-1$
        return toolBar;
    }

    @NotNull
    private static JComponent createRow(@NotNull JComponent left, @NotNull JComponent right) {
        JPanel row = new JPanel(new GridLayout(1, 2, UIScale.scale(4), 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        left.setPreferredSize(new Dimension(UIScale.scale(60), left.getPreferredSize().height));
        right.setPreferredSize(new Dimension(UIScale.scale(60), right.getPreferredSize().height));
        row.add(left);
        row.add(right);
        return row;
    }

    @NotNull
    private JComponent createInput() {
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AiMessages.ai_input_placeholder);
        input.setToolTipText(AiMessages.ai_input_tip);
        // Enter sends, Shift+Enter starts a new line
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.ai.send"); //$NON-NLS-1$
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break"); //$NON-NLS-1$
        input.getActionMap().put("chromite.ai.send", createAction(AiMessages.ai_send, this::sendInput)); //$NON-NLS-1$
        sendButton.addActionListener(e -> sendInput());
        sendButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        sendButton.setIcon(UiIcons.get("run")); //$NON-NLS-1$
        stopButton.addActionListener(e -> stop());
        stopButton.setIcon(UiIcons.get("stop")); //$NON-NLS-1$
        stopButton.setVisible(false);

        JScrollPane inputScroll = new JScrollPane(input);
        // The model picker at the left of the Send row: as wide as its text, narrower (with "...") when the window is narrow
        modelPicker.setMinimumSize(new Dimension(UIScale.scale(60), modelPicker.getPreferredSize().height));
        JPanel actionRow = new JPanel();
        actionRow.setOpaque(false);
        actionRow.setLayout(new BoxLayout(actionRow, BoxLayout.X_AXIS));
        actionRow.add(modelPicker);
        actionRow.add(Box.createHorizontalGlue());
        actionRow.add(Box.createHorizontalStrut(UIScale.scale(4)));
        actionRow.add(sendButton);
        actionRow.add(stopButton);
        statusLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel statusRow = new JPanel(new BorderLayout(0, UIScale.scale(2)));
        statusRow.setOpaque(false);
        statusRow.add(actionRow, BorderLayout.CENTER);
        statusRow.add(statusLabel, BorderLayout.SOUTH);

        // Wraps in a narrow tool window
        JLabel noticeLabel = new JLabel("<html>" + CommonUtils.escapeHtml(AiMessages.ai_setup_notice) + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$
        noticeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.warning"); //$NON-NLS-1$
        JButton noticeButton = new JButton(AiMessages.ai_settings);
        noticeButton.addActionListener(e -> host.openOptions(OptionsDialog.PAGE_AGENT));
        setupNotice.setOpaque(false);
        setupNotice.add(noticeLabel, BorderLayout.CENTER);
        setupNotice.add(noticeButton, BorderLayout.EAST);
        setupNotice.setVisible(false);

        JPanel panel = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(6), UIScale.scale(8)));
        panel.add(setupNotice, BorderLayout.NORTH);
        panel.add(inputScroll, BorderLayout.CENTER);
        panel.add(statusRow, BorderLayout.SOUTH);
        return panel;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (task != null) {
                    task.run();
                }
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, name);
        return action;
    }

    //////////////////////////////////////////////////////////
    // Conversations (EDT)

    private void loadChats() {
        new AbstractJob("Read AI conversations") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<AIAgentChat> loaded = AIAgentChatStore.loadAll();
                SwingUtilities.invokeLater(() -> {
                    chats.clear();
                    chats.addAll(loaded);
                    if (chats.isEmpty()) {
                        newChat();
                    } else {
                        showChat(chats.getFirst());
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void newChat() {
        if (runningJob != null) {
            return;
        }
        // An empty conversation is reused instead of piling up empty ones
        AIAgentChat empty = chats.stream().filter(AIAgentChat::isEmpty).findFirst().orElse(null);
        AIAgentChat created = empty != null ? empty : AIAgentChat.create(""); //$NON-NLS-1$
        if (empty == null) {
            chats.addFirst(created);
        }
        DBPPreferenceStore preferences = getPreferences();
        String engineId = CommonUtils.toString(preferences.getString(PREF_ENGINE), AIAgentProviders.ANTHROPIC);
        if (!AIAgentProviders.PROVIDERS.contains(engineId)) {
            engineId = AIAgentProviders.ANTHROPIC;
        }
        created.setEngineId(engineId);
        created.setModel(CommonUtils.nullIfEmpty(preferences.getString(PREF_MODEL_PREFIX + engineId)));
        showChat(created);
        applyActiveEditorContext();
        input.requestFocusInWindow();
    }

    private void deleteChat() {
        if (chat != null) {
            deleteChat(chat);
        }
    }

    /**
     * Deletes a conversation (the current one or one picked in the conversation history) after asking, Cancel being the
     * default; an empty conversation goes without asking. Deleting the current conversation shows the most recent one.
     */
    private void deleteChat(@NotNull AIAgentChat target) {
        if (runningJob != null) {
            return;
        }
        if (!target.isEmpty() && !SwingPlatformUI.confirmChange(this,
            NLS.bind(AiMessages.ai_delete_chat_question, target.getTitle()), AiMessages.ai_delete_chat,
            AiMessages.ai_delete_chat, JOptionPane.QUESTION_MESSAGE)) {
            return;
        }
        chats.remove(target);
        new AbstractJob("Delete AI conversation") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                AIAgentChatStore.delete(target);
                return Status.OK_STATUS;
            }
        }.schedule();
        if (target != chat) {
            return;
        }
        chat = null;
        if (chats.isEmpty()) {
            newChat();
        } else {
            showChat(chats.getFirst());
        }
    }

    private void showChat(@NotNull AIAgentChat selected) {
        chat = selected;
        // The last turn's tokens belong to the conversation that was shown
        turnUsage = null;
        statusLabel.setText(" "); //$NON-NLS-1$
        fillModelPicker();
        refreshConnections();
        transcript.removeAll();
        entryViews.clear();
        for (AIAgentChat.Entry entry : selected.getEntries()) {
            addEntryView(entry);
        }
        if (selected.isEmpty()) {
            transcript.add(createEmptyState());
        }
        transcript.revalidate();
        transcript.repaint();
        updateActions();
        scrollToEnd();
    }

    //////////////////////////////////////////////////////////
    // Model picker (EDT)

    /**
     * The conversation's provider and model as the picker shows it: the provider's default model when the conversation
     * has none yet.
     */
    @NotNull
    private static ModelChoice getChoice(@NotNull AIAgentChat current) {
        String engineId = current.getEngineId() != null ? current.getEngineId() : AIAgentProviders.ANTHROPIC;
        String model = current.getModel() != null ? current.getModel() : AIAgentProviders.getDefaultModel(engineId);
        return new ModelChoice(engineId, model);
    }

    /**
     * Rebuilds the picker's list: each provider's heading and models (the known names, the list loaded with Test, the last
     * used model), "Setup required" for a provider that is not configured, and "Enter Model Name" at the end. The
     * conversation's model is in the list even when its provider is not configured, so the closed picker can show it.
     */
    private void fillModelPicker() {
        ModelChoice selected = chat == null ? null : getChoice(chat);
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        for (String engineId : AIAgentProviders.PROVIDERS) {
            model.addElement(new ProviderHeading(engineId));
            boolean configured = AIAgentProviders.isConfigured(engineId);
            List<String> names = configured ? getModels(engineId) : new ArrayList<>();
            if (selected != null && selected.engineId().equals(engineId) && !names.contains(selected.model())) {
                names.addFirst(selected.model());
            }
            for (String name : names) {
                model.addElement(new ModelChoice(engineId, name));
            }
            if (!configured) {
                model.addElement(new SetupItem(engineId));
            }
        }
        model.addElement(PickerAction.ENTER_MODEL);
        updating = true;
        try {
            modelPicker.setModel(model);
            modelPicker.setSelectedItem(selected);
        } finally {
            updating = false;
        }
        updatePickerToolTip();
    }

    @NotNull
    private static List<String> getModels(@NotNull String engineId) {
        List<String> models = new ArrayList<>(AIAgentProviders.getSuggestedModels(engineId));
        for (String loaded : CommonUtils.notEmpty(getPreferences().getString(PREF_MODELS_PREFIX + engineId)).split(",")) { //$NON-NLS-1$
            if (!loaded.isBlank() && !models.contains(loaded.trim())) {
                models.add(loaded.trim());
            }
        }
        // A model name entered by hand stays in the list
        String last = CommonUtils.nullIfEmpty(getPreferences().getString(PREF_MODEL_PREFIX + engineId));
        if (last != null && !models.contains(last)) {
            models.add(last);
        }
        return models;
    }

    /**
     * The picker's item when its list closed (or its selection changed while closed). A model becomes the conversation's;
     * a heading or an action is not a selection: the picker goes back to the conversation's model and the action runs,
     * unless the list was canceled.
     */
    private void modelPicked() {
        Object item = modelPicker.getSelectedItem();
        boolean canceled = pickCanceled;
        pickCanceled = false;
        if (!canceled && item instanceof ModelChoice) {
            contextChanged();
            updateSetupNotice();
            updatePickerToolTip();
            return;
        }
        if (chat != null) {
            updating = true;
            try {
                modelPicker.setSelectedItem(getChoice(chat));
            } finally {
                updating = false;
            }
        }
        if (canceled) {
            return;
        }
        if (item instanceof SetupItem) {
            host.openOptions(OptionsDialog.PAGE_AGENT);
        } else if (item == PickerAction.ENTER_MODEL) {
            enterModel();
        }
    }

    /**
     * "Enter Model Name": a provider and a model name the lists do not know (as the editable model combo allowed before).
     */
    private void enterModel() {
        AIAgentChat current = chat;
        if (current == null || runningJob != null) {
            return;
        }
        ModelChoice choice = getChoice(current);
        JComboBox<String> providers = new JComboBox<>(AIAgentProviders.PROVIDERS.toArray(String[]::new));
        providers.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String engineId) {
                    setText(AIAgentProviders.getLabel(engineId));
                }
                return this;
            }
        });
        providers.setSelectedItem(choice.engineId());
        JTextField name = new JTextField(CommonUtils.notEmpty(choice.model()), 24);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, UIScale.scale(8));
        panel.add(new JLabel(AiMessages.ai_model_provider), c);
        c.gridy = 1;
        panel.add(new JLabel(AiMessages.ai_model_name), c);
        c.gridx = 1;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(providers, c);
        c.gridy = 1;
        panel.add(name, c);
        name.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && name.isShowing()) {
                name.selectAll();
                name.requestFocusInWindow();
            }
        });
        if (JOptionPane.showConfirmDialog(this, panel, AiMessages.ai_model_enter_title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || CommonUtils.isEmptyTrimmed(name.getText())
            || !(providers.getSelectedItem() instanceof String engineId)) {
            return;
        }
        current.setEngineId(engineId);
        current.setModel(name.getText().trim());
        fillModelPicker();
        contextChanged();
        updateSetupNotice();
    }

    // The closed picker may cut the name: the tool tip has all of it
    private void updatePickerToolTip() {
        modelPicker.setToolTipText(modelPicker.getSelectedItem() instanceof ModelChoice choice
            ? NLS.bind(AiMessages.ai_model_tip, describe(choice)) : null);
    }

    /**
     * "Provider · model", as the closed picker shows it.
     */
    @NotNull
    private static String describe(@NotNull ModelChoice choice) {
        String provider = AIAgentProviders.getLabel(choice.engineId());
        return choice.model() == null ? provider : provider + " · " + choice.model(); //$NON-NLS-1$
    }

    private void settingsChanged() {
        // A provider was set up or removed, or Test loaded its models
        fillModelPicker();
        updateSetupNotice();
    }

    /**
     * Picker items: the closed picker shows "Provider · model"; the list shows provider headings in bold (never highlighted)
     * with their models and actions indented under them.
     */
    private static final class ModelPickerRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            boolean heading = value instanceof ProviderHeading;
            super.getListCellRendererComponent(list, value, index, isSelected && !heading, cellHasFocus && !heading);
            int indent = index < 0 || heading ? 0 : UIScale.scale(12);
            setBorder(BorderFactory.createCompoundBorder(getBorder(), BorderFactory.createEmptyBorder(0, indent, 0, 0)));
            setFont(list.getFont().deriveFont(heading ? Font.BOLD : Font.PLAIN));
            if (value instanceof ModelChoice choice) {
                setText(index < 0 ? describe(choice) : CommonUtils.notEmpty(choice.model()));
            } else if (value instanceof ProviderHeading providerHeading) {
                setText(AIAgentProviders.getLabel(providerHeading.engineId()));
            } else if (value instanceof SetupItem) {
                setText(AiMessages.ai_model_setup);
                if (!isSelected) {
                    setForeground(UIManager.getColor("Chromite.status.warning")); //$NON-NLS-1$
                }
            } else if (value == PickerAction.ENTER_MODEL) {
                setText(AiMessages.ai_model_enter);
            } else {
                setText(" "); //$NON-NLS-1$
            }
            return this;
        }
    }

    private void refreshConnections() {
        AIAgentChat current = chat;
        updating = true;
        try {
            DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
            model.addElement(AiMessages.ai_no_connection);
            DBPDataSourceContainer selected = null;
            for (DBPDataSourceContainer container : QueryLibraryEditor.getConnections()) {
                model.addElement(container);
                if (current != null && container.getId().equals(current.getConnectionId())) {
                    selected = container;
                }
            }
            model.setSelectedItem(selected != null ? selected : AiMessages.ai_no_connection);
            connectionCombo.setModel(model);
            databaseCombo.setModel(new DefaultComboBoxModel<>());
            databaseCombo.setSelectedItem(current == null ? null : current.getDatabase());
        } finally {
            updating = false;
        }
        if (connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container) {
            loadDatabases(container, current == null ? null : current.getDatabase());
        }
    }

    /**
     * New conversations start with the active query window's connection and current database.
     */
    private void applyActiveEditorContext() {
        SqlEditorPanel editor = host.getActiveEditor();
        DBPDataSourceContainer container = editor == null ? null : editor.getDataSourceContainer();
        if (container == null || chat == null) {
            return;
        }
        updating = true;
        try {
            connectionCombo.setSelectedItem(container);
        } finally {
            updating = false;
        }
        connectionChanged(getCurrentDatabase(editor));
    }

    /**
     * The default catalog or schema of the connection's default (main) execution context: the database of the query
     * windows that share it, and the one a window's own session starts with.
     */
    @Nullable
    static String getCurrentDatabase(@NotNull DBPDataSourceContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null || !container.isConnected()) {
            return null;
        }
        return getDefaultObjectName(DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false));
    }

    /**
     * The default catalog or schema of the query window's own execution context (its own session, or the main context it
     * shares); the connection's main context when the window has not used the connection yet.
     */
    @Nullable
    private static String getCurrentDatabase(@NotNull SqlEditorPanel editor) {
        DBCExecutionContext context = editor.getExecutionContext();
        if (context != null) {
            return getDefaultObjectName(context);
        }
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        return container == null ? null : getCurrentDatabase(container);
    }

    @Nullable
    private static String getDefaultObjectName(@Nullable DBCExecutionContext context) {
        DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
        if (defaults == null) {
            return null;
        }
        if (defaults.getDefaultCatalog() != null) {
            return defaults.getDefaultCatalog().getName();
        }
        return defaults.getDefaultSchema() != null ? defaults.getDefaultSchema().getName() : null;
    }

    private void connectionChanged(@Nullable String database) {
        if (chat == null) {
            return;
        }
        updating = true;
        try {
            databaseCombo.setModel(new DefaultComboBoxModel<>());
            databaseCombo.setSelectedItem(database);
        } finally {
            updating = false;
        }
        contextChanged();
        if (connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container) {
            loadDatabases(container, database);
        }
    }

    /**
     * Fills the database list of a connected connection in a job (the names of its catalogs, else schemas).
     */
    private void loadDatabases(@NotNull DBPDataSourceContainer container, @Nullable String selected) {
        if (!container.isConnected()) {
            return;
        }
        new AbstractJob("Read databases for the AI assistant") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> names = new ArrayList<>();
                try {
                    DBPDataSource dataSource = container.getDataSource();
                    if (dataSource instanceof DBSObjectContainer objectContainer) {
                        for (DBSObject child : objectContainer.getChildren(monitor)) {
                            if (child instanceof DBSCatalog || child instanceof DBSSchema) {
                                names.add(child.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Can't read the databases of " + container.getName(), e);
                }
                SwingUtilities.invokeLater(() -> {
                    if (connectionCombo.getSelectedItem() != container) {
                        return;
                    }
                    updating = true;
                    try {
                        Object current = databaseCombo.getEditor().getItem();
                        databaseCombo.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
                        // Nothing chosen yet: the database of the connection's main context
                        Object shown = selected != null ? selected : CommonUtils.isEmpty(CommonUtils.toString(current, null))
                            ? getCurrentDatabase(container) : current;
                        databaseCombo.setSelectedItem(shown);
                        if (chat != null && chat.getDatabase() == null && shown != null && runningJob == null) {
                            contextChanged();
                        }
                    } finally {
                        updating = false;
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Copies the pickers into the conversation and remembers the provider and model for new conversations.
     */
    private void contextChanged() {
        AIAgentChat current = chat;
        if (current == null || runningJob != null) {
            return;
        }
        ModelChoice choice = modelPicker.getSelectedItem() instanceof ModelChoice c ? c : getChoice(current);
        String engineId = choice.engineId();
        String model = CommonUtils.nullIfEmpty(choice.model());
        current.setEngineId(engineId);
        current.setModel(model == null ? null : model.trim());
        current.setConnectionId(connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer c ? c.getId() : null);
        current.setDatabase(CommonUtils.nullIfEmpty(CommonUtils.toString(databaseCombo.getEditor().getItem(), "").trim())); //$NON-NLS-1$
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_ENGINE, engineId);
        if (model != null) {
            preferences.setValue(PREF_MODEL_PREFIX + engineId, model.trim());
        }
        PrefUtils.savePreferenceStore(preferences);
    }

    private void updateSetupNotice() {
        String engineId = modelPicker.getSelectedItem() instanceof ModelChoice choice ? choice.engineId() : null;
        // Reading the profile resolves secrets from the secure storage: cheap, but not on every repaint
        setupNotice.setVisible(engineId != null && !AIAgentProviders.isConfigured(engineId));
    }

    //////////////////////////////////////////////////////////
    // Sending (EDT) and the running turn

    private void sendInput() {
        String text = input.getText().strip();
        if (text.isEmpty() || runningJob != null) {
            return;
        }
        if (send(text, null)) {
            input.setText(""); //$NON-NLS-1$
        }
    }

    /**
     * Starts a turn with the prompt.
     *
     * @param label short text shown in the conversation instead of the prompt (playbooks), or null
     * @return false if nothing was sent (running, not configured)
     */
    private boolean send(@NotNull String prompt, @Nullable String label) {
        AIAgentChat current = chat;
        if (current == null || runningJob != null) {
            return false;
        }
        contextChanged();
        String engineId = current.getEngineId();
        if (engineId == null || !AIAgentProviders.isConfigured(engineId)) {
            updateSetupNotice();
            JOptionPane.showMessageDialog(this, AiMessages.ai_setup_notice, AiMessages.ai_title, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        if (current.getTitle().isEmpty()) {
            String title = (label != null ? label : prompt).replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
            current.setTitle(title.length() > TITLE_CHARS ? title.substring(0, TITLE_CHARS) + "..." : title); //$NON-NLS-1$
        }
        if (current.isEmpty()) {
            transcript.removeAll();
        }
        addEntryView(current.addUser(prompt, label));
        // The most recent conversation first
        chats.remove(current);
        chats.addFirst(current);
        turnUsage = null;

        AiAssistantPanel panel = this;
        AIAgentChatRunner.Listener listener = new AIAgentChatRunner.Listener() {
            @Override
            public void entryAdded(@NotNull AIAgentChat.Entry entry) {
                SwingUtilities.invokeLater(() -> {
                    panel.addEntryView(entry);
                    if (entry.getKind() == AIAgentChat.Kind.TOOL) {
                        statusLabel.setText(NLS.bind(AiMessages.ai_status_tool, AiToolCard.getToolId(entry)));
                    }
                });
            }

            @Override
            public void textAppended(@NotNull AIAgentChat.Entry entry, @NotNull String delta) {
                SwingUtilities.invokeLater(() -> {
                    pendingRender.add(entry);
                    if (!renderTimer.isRunning()) {
                        renderTimer.start();
                    }
                });
            }

            @Override
            public void entryUpdated(@NotNull AIAgentChat.Entry entry) {
                SwingUtilities.invokeLater(() -> {
                    if (entryViews.get(entry.getId()) instanceof AiToolCard card) {
                        card.update();
                    }
                    statusLabel.setText(AiMessages.ai_status_thinking);
                });
            }

            @Override
            public void toolProgress(@NotNull AIAgentChat.Entry entry) {
                SwingUtilities.invokeLater(() -> {
                    if (entry.isRunning() && entryViews.get(entry.getId()) instanceof AiToolCard card) {
                        card.update();
                    }
                });
            }

            @Override
            public void usage(@NotNull AIUsage usage) {
                // Before turnFinished, which the job posts after the runner returns
                SwingUtilities.invokeLater(() -> turnUsage = usage);
            }
        };
        AbstractJob job = new AbstractJob("AI assistant") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                AIAgentChatRunner.run(monitor, current, listener);
                SwingUtilities.invokeLater(() -> turnFinished(this));
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        runningJob = job;
        statusLabel.setText(AiMessages.ai_status_thinking);
        updateActions();
        job.schedule();
        return true;
    }

    private void turnFinished(@NotNull AbstractJob job) {
        if (runningJob != job) {
            return;
        }
        boolean canceled = job.isCanceled();
        runningJob = null;
        renderPending();
        statusLabel.setText(canceled ? AiMessages.ai_status_stopped : turnUsage != null ? describeUsage(turnUsage) : " "); //$NON-NLS-1$
        // Tool calls still shown as running were canceled with the turn
        for (JComponent view : entryViews.values()) {
            if (view instanceof AiToolCard card) {
                card.update();
            }
        }
        updateActions();
        if (databaseCombo.getItemCount() == 0 && connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container) {
            loadDatabases(container, chat == null ? null : chat.getDatabase());
        }
    }

    /**
     * The tokens of a turn as the provider counted them (every request of the turn sends the conversation again, so the
     * input is the sum over the requests).
     */
    @NotNull
    private static String describeUsage(@NotNull AIUsage usage) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        String input = format.format(usage.totalInputTokens());
        String output = format.format(usage.totalOutputTokens());
        return usage.cachedTokens() > 0
            ? NLS.bind(AiMessages.ai_status_usage_cached, new Object[]{input, output, format.format(usage.cachedTokens())})
            : NLS.bind(AiMessages.ai_status_usage, input, output);
    }

    private void stop() {
        AbstractJob job = runningJob;
        if (job != null) {
            job.cancel();
            statusLabel.setText(AiMessages.ai_status_stopping);
        }
    }

    private void updateActions() {
        boolean running = runningJob != null;
        sendButton.setVisible(!running);
        stopButton.setVisible(running);
        modelPicker.setEnabled(!running);
        connectionCombo.setEnabled(!running);
        databaseCombo.setEnabled(!running);
        newChatAction.setEnabled(!running);
        deleteChatAction.setEnabled(!running && chat != null);
        historyAction.setEnabled(!running);
        playbooksAction.setEnabled(!running);
    }

    //////////////////////////////////////////////////////////
    // Conversation history (EDT)

    /**
     * The conversation history: a popup list under the button, most recent first, with a filter field. Enter or a click
     * switches to the conversation; Delete or the row's × deletes it (asking first) and opens the list again.
     *
     * @param filterText the filter to start with (the list opens again after a delete)
     */
    private void showHistory(@NotNull JComponent anchor, @NotNull String filterText) {
        if (runningJob != null) {
            return;
        }
        Map<String, String> connectionNames = new HashMap<>();
        for (DBPDataSourceContainer container : QueryLibraryEditor.getConnections()) {
            connectionNames.put(container.getId(), container.getName());
        }
        JPopupMenu popup = new JPopupMenu();
        JTextField filter = new JTextField(filterText);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AiMessages.ai_history_filter);
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        DefaultListModel<AIAgentChat> model = new DefaultListModel<>();
        JList<AIAgentChat> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new HistoryRenderer(connectionNames));
        list.setFocusable(false);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JLabel emptyLabel = new JLabel();
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(8), UIScale.scale(8), UIScale.scale(8)));
        emptyLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel panel = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4)));
        panel.add(filter, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        popup.add(panel);

        Runnable refill = () -> {
            String text = filter.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            for (AIAgentChat c : chats) {
                if (!c.isEmpty() && (text.isEmpty() || describeForFilter(c, connectionNames).contains(text))) {
                    model.addElement(c);
                }
            }
            int current = chat == null ? -1 : model.indexOf(chat);
            list.setSelectedIndex(model.isEmpty() ? -1 : Math.max(0, current));
            list.setVisibleRowCount(Math.max(1, Math.min(8, model.size())));
            boolean empty = model.isEmpty();
            emptyLabel.setText(chats.stream().anyMatch(c -> !c.isEmpty()) ? AiMessages.ai_history_no_match : AiMessages.ai_history_empty);
            panel.remove(empty ? scroll : emptyLabel);
            panel.add(empty ? emptyLabel : scroll, BorderLayout.CENTER);
            scroll.setPreferredSize(null);
            Dimension size = scroll.getPreferredSize();
            scroll.setPreferredSize(new Dimension(Math.max(size.width, UIScale.scale(320)), size.height));
            if (popup.isVisible()) {
                popup.pack();
            }
            panel.revalidate();
            panel.repaint();
        };
        Runnable open = () -> {
            AIAgentChat selected = list.getSelectedValue();
            popup.setVisible(false);
            if (selected != null && selected != chat && runningJob == null) {
                showChat(selected);
            }
        };
        Runnable delete = () -> {
            AIAgentChat selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            String text = filter.getText();
            popup.setVisible(false);
            // Open again after the question, deleted or not, to go on in the list
            deleteChat(selected);
            showHistory(anchor, text);
        };
        refill.run();
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refill.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refill.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refill.run();
            }
        });
        // The filter keeps the focus: the keys move in the list (bound on the field, before the popup's own menu keys)
        bindKey(filter, KeyEvent.VK_DOWN, 0, () -> moveSelection(list, 1));
        bindKey(filter, KeyEvent.VK_UP, 0, () -> moveSelection(list, -1));
        bindKey(filter, KeyEvent.VK_ENTER, 0, open);
        bindKey(filter, KeyEvent.VK_ESCAPE, 0, () -> popup.setVisible(false));
        bindKey(filter, KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK, delete);
        bindKey(filter, KeyEvent.VK_DELETE, 0, () -> {
            // Delete edits the filter while it has text; on an empty filter it deletes the selected conversation
            if (filter.getText().isEmpty()) {
                delete.run();
            } else {
                int caret = filter.getCaretPosition();
                if (filter.getSelectionStart() != filter.getSelectionEnd()) {
                    filter.replaceSelection(""); //$NON-NLS-1$
                } else if (caret < filter.getText().length()) {
                    filter.select(caret, caret + 1);
                    filter.replaceSelection(""); //$NON-NLS-1$
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(e.getPoint()) || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                list.setSelectedIndex(index);
                if (e.getX() >= bounds.x + bounds.width - HistoryRenderer.getDeleteWidth()) {
                    delete.run();
                } else {
                    open.run();
                }
            }
        });
        popup.show(anchor, 0, anchor.getHeight());
        // Later: when the list opens again after the delete question, the closed dialog gives the focus back first
        SwingUtilities.invokeLater(filter::requestFocusInWindow);
    }

    @NotNull
    private static String describeForFilter(@NotNull AIAgentChat c, @NotNull Map<String, String> connectionNames) {
        String connection = c.getConnectionId() == null ? "" : CommonUtils.notEmpty(connectionNames.get(c.getConnectionId())); //$NON-NLS-1$
        return (c.getTitle() + " " + connection).toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static void moveSelection(@NotNull JList<?> list, int step) {
        int size = list.getModel().getSize();
        if (size == 0) {
            return;
        }
        int index = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + step));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private static void bindKey(@NotNull JComponent component, int keyCode, int modifiers, @NotNull Runnable task) {
        String name = "chromite.ai.history." + keyCode + "." + modifiers; //$NON-NLS-1$ //$NON-NLS-2$
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, modifiers), name);
        component.getActionMap().put(name, createAction(name, task));
    }

    /**
     * A history row: the title (bold with a check mark for the current conversation), the last time and the connection
     * below it, and × at the right end.
     */
    private final class HistoryRenderer extends JPanel implements ListCellRenderer<AIAgentChat> {
        private final Map<String, String> connectionNames;
        private final JLabel title = new JLabel();
        private final JLabel details = new JLabel();
        private final JLabel deleteLabel = new JLabel(UiIcons.get("close")); //$NON-NLS-1$
        private final DateFormat timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        HistoryRenderer(@NotNull Map<String, String> connectionNames) {
            super(new BorderLayout(UIScale.scale(6), 0));
            this.connectionNames = connectionNames;
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            text.add(title);
            text.add(details);
            details.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            deleteLabel.setToolTipText(AiMessages.ai_history_delete_tip);
            add(text, BorderLayout.CENTER);
            add(deleteLabel, BorderLayout.EAST);
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(6), UIScale.scale(3), UIScale.scale(6)));
        }

        // The × area at the right end of a row
        static int getDeleteWidth() {
            return UIScale.scale(UiIcons.SIZE + 12);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends AIAgentChat> list, AIAgentChat value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            boolean current = value == chat;
            title.setText(value.getTitle().isEmpty() ? AiMessages.ai_chat_untitled : value.getTitle());
            title.setIcon(current ? UiIcons.get("check") : null); //$NON-NLS-1$
            title.setFont(list.getFont().deriveFont(current ? Font.BOLD : Font.PLAIN));
            String connection = value.getConnectionId() == null ? null : connectionNames.get(value.getConnectionId());
            String time = timeFormat.format(new Date(value.getUpdated()));
            details.setText(connection == null ? time : time + "  ·  " + connection); //$NON-NLS-1$
            Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
            setBackground(background);
            title.setForeground(foreground);
            details.setForeground(isSelected ? foreground : UIManager.getColor("Chromite.secondaryForeground")); //$NON-NLS-1$
            deleteLabel.setVisible(isSelected);
            return this;
        }
    }

    //////////////////////////////////////////////////////////
    // Transcript (EDT)

    private void addEntryView(@NotNull AIAgentChat.Entry entry) {
        JComponent view = switch (entry.getKind()) {
            case USER -> createUserView(entry);
            case ASSISTANT -> {
                AiMarkdownView markdown = new AiMarkdownView(this::openInEditor);
                markdown.setMarkdown(entry.getText());
                yield markdown;
            }
            case TOOL -> new AiToolCard(entry);
            case ERROR -> createErrorView(entry);
        };
        view.setAlignmentX(LEFT_ALIGNMENT);
        entryViews.put(entry.getId(), view);
        if (transcript.getComponentCount() > 0) {
            transcript.add(Box.createVerticalStrut(UIScale.scale(6)));
        }
        transcript.add(view);
        transcript.revalidate();
        transcript.repaint();
        scrollToEnd();
    }

    private void renderPending() {
        if (pendingRender.isEmpty()) {
            return;
        }
        for (AIAgentChat.Entry entry : pendingRender) {
            if (entryViews.get(entry.getId()) instanceof AiMarkdownView markdown) {
                markdown.setMarkdown(entry.getText());
            }
        }
        pendingRender.clear();
        scrollToEnd();
    }

    @NotNull
    private JComponent createUserView(@NotNull AIAgentChat.Entry entry) {
        JComponent text;
        if (entry.getLabel() != null) {
            // A playbook: the label; the full prompt is in the tool tip
            JLabel label = new JLabel(entry.getLabel());
            label.setToolTipText("<html><pre>" + CommonUtils.escapeHtml(entry.getText()) + "</pre></html>"); //$NON-NLS-1$ //$NON-NLS-2$
            text = label;
        } else {
            // The prompt may hold SQL in a code block ("Ask Chromite Agent"): shown like the answers, with Copy and Open in Query Window
            AiMarkdownView markdown = new AiMarkdownView(this::openInEditor);
            markdown.setMarkdown(entry.getText());
            text = markdown;
        }
        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        bubble.putClientProperty(FlatClientProperties.STYLE,
            "background: $Chromite.island.tabSelectedBackground; border: 6,8,6,8,$Chromite.island.tabSelectedBackground,1,10"); //$NON-NLS-1$
        bubble.add(text, BorderLayout.CENTER);
        return bubble;
    }

    @NotNull
    private static JComponent createErrorView(@NotNull AIAgentChat.Entry entry) {
        JTextArea text = new JTextArea(entry.getText());
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        text.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return text;
    }

    @NotNull
    private JComponent createEmptyState() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JTextArea hint = new JTextArea(AiMessages.ai_empty_hint);
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        hint.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(UIScale.scale(8)));
        for (Playbook playbook : getPlaybooks()) {
            JButton button = new JButton(playbook.label());
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setAlignmentX(LEFT_ALIGNMENT);
            button.addActionListener(e -> runPlaybook(playbook));
            panel.add(button);
        }
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private void scrollToEnd() {
        SwingUtilities.invokeLater(() -> {
            transcript.revalidate();
            JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void openInEditor(@NotNull String sql) {
        DBPDataSourceContainer container = connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer c ? c : null;
        host.openSqlEditor(container, sql, null);
    }

    /**
     * The {@code openInEditor} agent tool ({@code AIEditorBridge}, called on the EDT): a new query window with the text.
     */
    void openInEditor(@Nullable DBPDataSourceContainer connection, @NotNull String sql, @Nullable String title) {
        host.openSqlEditor(connection, sql, title);
    }

    //////////////////////////////////////////////////////////
    // Playbooks and the editor's "Ask Chromite Agent"

    @NotNull
    private static List<Playbook> getPlaybooks() {
        return List.of(
            new Playbook("tune-slow-query", AiMessages.ai_playbook_tune, true), //$NON-NLS-1$
            new Playbook("database-health", AiMessages.ai_playbook_health, false), //$NON-NLS-1$
            new Playbook("slow-query-root-cause", AiMessages.ai_playbook_why_slow, true), //$NON-NLS-1$
            new Playbook("library-monitoring", AiMessages.ai_playbook_system_check, false), //$NON-NLS-1$
            new Playbook("blocking-root-cause", AiMessages.ai_playbook_blocking, false), //$NON-NLS-1$
            new Playbook("routine-review", AiMessages.ai_playbook_routine, false)); //$NON-NLS-1$
    }

    private void showPlaybooks(@NotNull JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (Playbook playbook : getPlaybooks()) {
            JMenuItem item = new JMenuItem(playbook.label());
            item.addActionListener(e -> runPlaybook(playbook));
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void runPlaybook(@NotNull Playbook playbook) {
        if (runningJob != null || chat == null) {
            return;
        }
        if (!(connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container)) {
            JOptionPane.showMessageDialog(this, AiMessages.ai_playbook_no_connection, playbook.label(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put("connection", container.getName()); //$NON-NLS-1$
        if (playbook.needsSql()) {
            String sql = getActiveStatement();
            if (sql == null) {
                JOptionPane.showMessageDialog(this, AiMessages.ai_playbook_no_sql, playbook.label(), JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            values.put("sql", sql); //$NON-NLS-1$
        }
        if (playbook.name().equals("library-monitoring")) { //$NON-NLS-1$
            String folder = (String) JOptionPane.showInputDialog(this, AiMessages.ai_playbook_folder, playbook.label(),
                JOptionPane.QUESTION_MESSAGE, null, null, "Monitoring"); //$NON-NLS-1$
            if (folder == null) {
                return;
            }
            values.put("folder", folder.strip()); //$NON-NLS-1$
        } else if (playbook.name().equals("routine-review")) { //$NON-NLS-1$
            String object = JOptionPane.showInputDialog(this, AiMessages.ai_playbook_object, playbook.label(), JOptionPane.QUESTION_MESSAGE);
            if (CommonUtils.isEmptyTrimmed(object)) {
                return;
            }
            values.put("object", object.strip()); //$NON-NLS-1$
        }
        String text;
        try {
            text = AIAgentPlaybooks.getText(playbook.name());
        } catch (IOException e) {
            ErrorDialog.show(this, playbook.label(), null, e);
            return;
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("{" + value.getKey() + "}", value.getValue()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String label = playbook.label() + (values.containsKey("object") ? ": " + values.get("object") : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        send(text, label);
    }

    @Nullable
    private String getActiveStatement() {
        SqlEditorPanel editor = host.getActiveEditor();
        return editor == null ? null : getStatement(editor);
    }

    /**
     * The selected text of a query window, else the statement at the caret (the parser of the connection's dialect, as
     * code completion finds it), else the whole text.
     */
    @Nullable
    static String getStatement(@NotNull SqlEditorPanel editor) {
        SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
        String selected = textArea.getSelectedText();
        if (!CommonUtils.isEmptyTrimmed(selected)) {
            return selected.strip();
        }
        String text = textArea.getText();
        if (CommonUtils.isEmptyTrimmed(text)) {
            return null;
        }
        SQLScriptElement element = findStatementAtCaret(editor);
        return element != null ? element.getText().strip() : text.strip();
    }

    /**
     * The statement at the caret of a query window as the parser of its connection's dialect finds it (as code completion
     * does); null when the window has no connected connection, no text or no statement there. Also used by the
     * {@code getQueryWindowText} agent tool ({@link AiQueryWindowBridge}).
     */
    @Nullable
    static SQLScriptElement findStatementAtCaret(@NotNull SqlEditorPanel editor) {
        SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
        String text = textArea.getText();
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (CommonUtils.isEmptyTrimmed(text) || dataSource == null || !container.isConnected()) {
            return null;
        }
        try {
            SQLParserContext parserContext = SQLScriptBatches.createParserContext(dataSource, text);
            SQLScriptElement element = SQLScriptParser.extractQueryAtPos(parserContext, textArea.getCaretPosition(), false);
            return element == null || CommonUtils.isEmptyTrimmed(element.getText()) ? null : element;
        } catch (RuntimeException e) {
            log.debug("Can't find the statement at the caret", e);
            return null;
        }
    }

    /**
     * Adds "Ask Chromite Agent About This Query" to the context menu of a query window's editor.
     */
    void installEditorActions(@NotNull SqlEditorPanel editor) {
        JPopupMenu menu = editor.getTextArea().getPopupMenu();
        if (menu == null) {
            return;
        }
        JMenuItem item = new JMenuItem(AiMessages.ai_ask_about_query, UiIcons.get("ai-assistant")); //$NON-NLS-1$
        item.addActionListener(e -> askAbout(editor));
        menu.addSeparator();
        menu.add(item);
    }

    private void askAbout(@NotNull SqlEditorPanel editor) {
        String sql = getStatement(editor);
        if (sql == null) {
            UIManager.getLookAndFeel().provideErrorFeedback(editor);
            return;
        }
        host.showAssistant();
        if (runningJob != null) {
            JOptionPane.showMessageDialog(this, AiMessages.ai_busy, AiMessages.ai_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (chat != null && !chat.isEmpty()) {
            chat = null;
            newChat();
        }
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        if (container != null) {
            updating = true;
            try {
                connectionCombo.setSelectedItem(container);
            } finally {
                updating = false;
            }
            connectionChanged(getCurrentDatabase(editor));
        }
        send(NLS.bind(AiMessages.ai_ask_prompt, sql), null);
    }

    //////////////////////////////////////////////////////////

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Remembers the model names loaded from a provider (Options &gt; Chromite Agent &gt; Test), for the model picker.
     */
    static void rememberModels(@NotNull String engineId, @NotNull List<String> models) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_MODELS_PREFIX + engineId, String.join(",", models)); //$NON-NLS-1$
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Stops a running turn (the main window closes).
     */
    void dispose() {
        AbstractJob job = runningJob;
        if (job != null) {
            job.cancel();
        }
        renderTimer.stop();
        AISettingsManager.getInstance().removeChangedListener(settingsListener);
    }

    /**
     * The conversation column: entries stacked from the top, as wide as the viewport (text wraps), as tall as needed.
     */
    private static final class TranscriptPanel extends JPanel implements Scrollable {
        TranscriptPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(8), UIScale.scale(6), UIScale.scale(8)));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UIScale.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
