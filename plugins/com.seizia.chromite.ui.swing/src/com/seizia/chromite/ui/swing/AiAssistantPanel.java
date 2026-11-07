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
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Chromite Agent" tool window: conversations with an AI model that works on the user's connections through the agent
 * tools (docs/chromite/ai-agent.md "2단계").
 *
 * 설계:
 * <ul>
 *     <li>대화 실행은 모델 번들의 {@link AIAgentChatRunner}가 작업({@link AbstractJob}) 안에서 한다. 이 창은 EDT에서 화면만 고친다(모델 호출을 EDT에서
 *     하지 않는다). 작업의 진행은 {@link AIAgentChatRunner.Listener}로 받아 {@link SwingUtilities#invokeLater}로 넘긴다. 글 조각은 짧은 타이머로 모아
 *     다시 그린다(조각마다 Markdown을 다시 해석하지 않게).</li>
 *     <li>제공자·모델·연결·데이터베이스는 대화마다 저장한다. 새 대화는 활성 쿼리 창의 연결과 현재 데이터베이스(쿼리 창이 쓰는 기본 실행 문맥의
 *     기본 카탈로그·스키마)로 시작한다. 모델은 이 연결을 도구의 기본 {@code connection} 인수로 쓴다.</li>
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

        /** Tools &gt; Options. */
        void openOptions();
    }

    private record Playbook(@NotNull String name, @NotNull String label, boolean needsSql) {
    }

    private final Host host;
    private final List<AIAgentChat> chats = new ArrayList<>();
    @Nullable
    private AIAgentChat chat;
    @Nullable
    private AbstractJob runningJob;

    private final JComboBox<AIAgentChat> chatCombo = new JComboBox<>();
    private final JComboBox<String> providerCombo = new JComboBox<>(AIAgentProviders.PROVIDERS.toArray(String[]::new));
    private final JComboBox<String> modelCombo = new JComboBox<>();
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
    private final Action playbooksAction;
    private final Map<String, JComponent> entryViews = new HashMap<>();
    private final Timer renderTimer;
    private final AISettingsEventListener settingsListener = registry -> SwingUtilities.invokeLater(this::updateSetupNotice);
    @Nullable
    private AIAgentChat.Entry pendingRender;
    private boolean updating;

    AiAssistantPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);

        newChatAction = createAction(AiMessages.ai_new_chat, this::newChat);
        deleteChatAction = createAction(AiMessages.ai_delete_chat, this::deleteChat);
        playbooksAction = createAction(AiMessages.ai_playbooks, null);

        chatCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AIAgentChat c) {
                    setText(c.getTitle().isEmpty() ? AiMessages.ai_chat_untitled : c.getTitle());
                }
                return this;
            }
        });
        chatCombo.setToolTipText(AiMessages.ai_chats_tip);
        chatCombo.addActionListener(e -> {
            if (!updating && chatCombo.getSelectedItem() instanceof AIAgentChat selected && selected != chat) {
                showChat(selected);
            }
        });
        providerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String engineId) {
                    setText(AIAgentProviders.getLabel(engineId));
                }
                return this;
            }
        });
        providerCombo.setToolTipText(AiMessages.ai_provider_tip);
        providerCombo.addActionListener(e -> {
            if (!updating) {
                providerChanged();
            }
        });
        modelCombo.setEditable(true);
        modelCombo.setToolTipText(AiMessages.ai_model_tip);
        modelCombo.addActionListener(e -> {
            if (!updating) {
                contextChanged();
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
        top.add(createRow(providerCombo, modelCombo));
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

    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(newChatAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(deleteChatAction, "close")); //$NON-NLS-1$
        toolBar.addSeparator();
        JButton playbooksButton = UiIcons.createActionButton(playbooksAction, "library"); //$NON-NLS-1$
        playbooksButton.addActionListener(e -> showPlaybooks(playbooksButton));
        toolBar.add(playbooksButton);
        toolBar.add(UiIcons.createActionButton(createAction(AiMessages.ai_settings, host::openOptions), "settings")); //$NON-NLS-1$
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setOpaque(false);
        bar.add(toolBar, BorderLayout.EAST);
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setOpaque(false);
        chatPanel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), 0, 0));
        chatPanel.add(chatCombo, BorderLayout.CENTER);
        bar.add(chatPanel, BorderLayout.CENTER);
        return bar;
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
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(sendButton);
        buttons.add(stopButton);
        statusLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.add(statusLabel, BorderLayout.CENTER);
        statusRow.add(buttons, BorderLayout.EAST);

        // Wraps in a narrow tool window
        JLabel noticeLabel = new JLabel("<html>" + CommonUtils.escapeHtml(AiMessages.ai_setup_notice) + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$
        noticeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.warning"); //$NON-NLS-1$
        JButton noticeButton = new JButton(AiMessages.ai_settings);
        noticeButton.addActionListener(e -> host.openOptions());
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
                        updateChatCombo();
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
        updateChatCombo();
        showChat(created);
        applyActiveEditorContext();
        input.requestFocusInWindow();
    }

    private void deleteChat() {
        AIAgentChat current = chat;
        if (current == null || runningJob != null) {
            return;
        }
        if (!current.isEmpty() && !SwingPlatformUI.confirmChange(this,
            NLS.bind(AiMessages.ai_delete_chat_question, current.getTitle()), AiMessages.ai_delete_chat,
            AiMessages.ai_delete_chat, JOptionPane.QUESTION_MESSAGE)) {
            return;
        }
        chats.remove(current);
        new AbstractJob("Delete AI conversation") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                AIAgentChatStore.delete(current);
                return Status.OK_STATUS;
            }
        }.schedule();
        chat = null;
        if (chats.isEmpty()) {
            newChat();
        } else {
            updateChatCombo();
            showChat(chats.getFirst());
        }
    }

    private void updateChatCombo() {
        updating = true;
        try {
            chatCombo.setModel(new DefaultComboBoxModel<>(chats.toArray(AIAgentChat[]::new)));
            chatCombo.setSelectedItem(chat);
        } finally {
            updating = false;
        }
    }

    private void showChat(@NotNull AIAgentChat selected) {
        chat = selected;
        updating = true;
        try {
            chatCombo.setSelectedItem(selected);
            String engineId = selected.getEngineId() != null ? selected.getEngineId() : AIAgentProviders.ANTHROPIC;
            providerCombo.setSelectedItem(engineId);
            fillModels(engineId, selected.getModel());
        } finally {
            updating = false;
        }
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

    private void fillModels(@NotNull String engineId, @Nullable String selectedModel) {
        List<String> models = new ArrayList<>(AIAgentProviders.getSuggestedModels(engineId));
        for (String loaded : CommonUtils.notEmpty(getPreferences().getString(PREF_MODELS_PREFIX + engineId)).split(",")) { //$NON-NLS-1$
            if (!loaded.isBlank() && !models.contains(loaded.trim())) {
                models.add(loaded.trim());
            }
        }
        String model = selectedModel != null ? selectedModel : AIAgentProviders.getDefaultModel(engineId);
        if (model != null && !models.contains(model)) {
            models.addFirst(model);
        }
        modelCombo.setModel(new DefaultComboBoxModel<>(models.toArray(String[]::new)));
        modelCombo.setSelectedItem(model);
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

    private void providerChanged() {
        if (!(providerCombo.getSelectedItem() instanceof String engineId)) {
            return;
        }
        updating = true;
        try {
            fillModels(engineId, CommonUtils.nullIfEmpty(getPreferences().getString(PREF_MODEL_PREFIX + engineId)));
        } finally {
            updating = false;
        }
        contextChanged();
        updateSetupNotice();
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
        String engineId = providerCombo.getSelectedItem() instanceof String s ? s : null;
        String model = CommonUtils.nullIfEmpty(CommonUtils.toString(modelCombo.getEditor().getItem(), null));
        current.setEngineId(engineId);
        current.setModel(model == null ? null : model.trim());
        current.setConnectionId(connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer c ? c.getId() : null);
        current.setDatabase(CommonUtils.nullIfEmpty(CommonUtils.toString(databaseCombo.getEditor().getItem(), "").trim())); //$NON-NLS-1$
        if (engineId != null) {
            DBPPreferenceStore preferences = getPreferences();
            preferences.setValue(PREF_ENGINE, engineId);
            if (model != null) {
                preferences.setValue(PREF_MODEL_PREFIX + engineId, model.trim());
            }
            PrefUtils.savePreferenceStore(preferences);
        }
    }

    private void updateSetupNotice() {
        String engineId = providerCombo.getSelectedItem() instanceof String s ? s : null;
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
            chatCombo.repaint();
        }
        if (current.isEmpty()) {
            transcript.removeAll();
        }
        addEntryView(current.addUser(prompt, label));
        // The most recent conversation first
        chats.remove(current);
        chats.addFirst(current);
        updateChatCombo();

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
                    pendingRender = entry;
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
        statusLabel.setText(canceled ? AiMessages.ai_status_stopped : " "); //$NON-NLS-1$
        // Tool calls still shown as running were canceled with the turn
        for (JComponent view : entryViews.values()) {
            if (view instanceof AiToolCard card) {
                card.update();
            }
        }
        updateActions();
        chatCombo.repaint();
        if (databaseCombo.getItemCount() == 0 && connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container) {
            loadDatabases(container, chat == null ? null : chat.getDatabase());
        }
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
        chatCombo.setEnabled(!running);
        providerCombo.setEnabled(!running);
        modelCombo.setEnabled(!running);
        connectionCombo.setEnabled(!running);
        databaseCombo.setEnabled(!running);
        newChatAction.setEnabled(!running);
        deleteChatAction.setEnabled(!running && chat != null);
        playbooksAction.setEnabled(!running);
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
        AIAgentChat.Entry entry = pendingRender;
        pendingRender = null;
        if (entry != null && entryViews.get(entry.getId()) instanceof AiMarkdownView markdown) {
            markdown.setMarkdown(entry.getText());
            scrollToEnd();
        }
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
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (dataSource != null && container.isConnected()) {
            try {
                SQLParserContext parserContext = SQLScriptBatches.createParserContext(dataSource, text);
                SQLScriptElement element = SQLScriptParser.extractQueryAtPos(parserContext, textArea.getCaretPosition(), false);
                if (element != null && !CommonUtils.isEmptyTrimmed(element.getText())) {
                    return element.getText().strip();
                }
            } catch (RuntimeException e) {
                log.debug("Can't find the statement at the caret", e);
            }
        }
        return text.strip();
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
