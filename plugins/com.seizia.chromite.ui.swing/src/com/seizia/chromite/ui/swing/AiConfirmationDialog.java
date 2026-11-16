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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import com.seizia.chromite.model.ai.agent.AIPendingTransaction;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolClient;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationAnswer;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationHandler;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationRequest;
import com.seizia.chromite.model.ai.agent.AIToolPermission;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Asks the user before an AI tool call runs: Allow / Deny, or Commit / Roll back / Cancel when the call would end an open
 * transaction. Serves the in-app assistant and the local MCP server (the same {@link AIToolConfirmationHandler}).
 *
 * 설계:
 * <ul>
 *     <li>계약은 docs/chromite/ai-agent.md "2단계(채팅 화면) 인터페이스"다. 실행기는 작업 스레드(채팅 작업, MCP HTTP 스레드)에서
 *     {@link Handler#confirm}을 부르고 답이 올 때까지 기다린다. 처리기는 EDT에 모달 대화상자를 띄우고, 기다리는 동안 모니터 취소(채팅의 중지,
 *     MCP {@code notifications/cancelled})를 보면 대화상자를 닫고 DENY를 돌려준다.</li>
 *     <li>대화상자는 한 번에 하나다(공정 잠금). MCP 클라이언트가 여러 쓰기 도구를 한꺼번에 불러도 질문이 겹쳐 쌓이지 않고 차례로 나온다.</li>
 *     <li>보이는 것: 요청한 쪽(이 앱의 Chromite Agent / 외부 에이전트 MCP), 도구 이름과 ID, 실제 분류(WRITE/DANGEROUS), 연결(운영 표시 포함), 실행할
 *     SQL·DDL·명령 줄 전체 또는 쿼리 창에서 바뀌는 글(미리 보기, 읽기 전용 SQL 편집기. 제목은 미리 보기 종류를 따른다), 비밀을 지운 인수(실행기가
 *     이미 지움), 정책 이유.</li>
 *     <li>"이 연결에서 이 도구 항상 허용"은 WRITE에만 둔다. DANGEROUS는 설정 화면의 연결별·도구별 규칙으로만 자동 실행할 수 있다(사용자 결정).
 *     트랜잭션 질문에도 두지 않는다: 열린 트랜잭션을 어떻게 끝낼지는 매번 묻는다(사용자 규칙 "트랜잭션 안전").</li>
 *     <li>기본 단추는 Deny / Cancel이다. Enter를 잘못 눌러 쓰기가 실행되지 않게 하기 위해서다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 화면 이름을 Chromite Agent로 바꾼 데 맞춰 요청한 쪽 설명을 고침(docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 미리 보기 종류에 맞는 제목과 첫 줄: 쿼리 창 글 고치기는 "바뀌는 글"(지운 줄·넣은 줄 색 표시)이고 DB를 바꾸지 않는다고 알림
 *   (docs/chromite/ai-agent.md "편집기 도구")
 * - 2026-11-12 항상 허용·위험 도구 안내 옆에 Options의 Chromite Agent 페이지를 여는 링크(B12, docs/chromite/swing-options.md "특정 페이지로 여는 곳")
 */
final class AiConfirmationDialog extends JDialog {

    private static final Log log = Log.getLog(AiConfirmationDialog.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_VALUE_CHARS = 400;
    private static final long WAIT_STEP_MS = 100;

    /**
     * The confirmation handler registered in {@link AIAgentServices}.
     */
    static final class Handler implements AIToolConfirmationHandler {
        private final Supplier<Window> owner;
        private final ReentrantLock oneAtATime = new ReentrantLock(true);

        Handler(@NotNull Supplier<Window> owner) {
            this.owner = owner;
        }

        @NotNull
        @Override
        public AIToolConfirmationAnswer confirm(@NotNull DBRProgressMonitor monitor, @NotNull AIToolConfirmationRequest request) {
            if (SwingUtilities.isEventDispatchThread()) {
                // Not expected (tools run in jobs), but a modal dialog on the EDT is safe
                return new AiConfirmationDialog(owner.get(), request).open();
            }
            try {
                while (!oneAtATime.tryLock(WAIT_STEP_MS, TimeUnit.MILLISECONDS)) {
                    if (monitor.isCanceled()) {
                        return AIToolConfirmationAnswer.DENY;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AIToolConfirmationAnswer.DENY;
            }
            try {
                return ask(monitor, request);
            } finally {
                oneAtATime.unlock();
            }
        }

        @NotNull
        private AIToolConfirmationAnswer ask(@NotNull DBRProgressMonitor monitor, @NotNull AIToolConfirmationRequest request) {
            CompletableFuture<AIToolConfirmationAnswer> answer = new CompletableFuture<>();
            AtomicReference<AiConfirmationDialog> shown = new AtomicReference<>();
            SwingUtilities.invokeLater(() -> {
                try {
                    AiConfirmationDialog dialog = new AiConfirmationDialog(owner.get(), request);
                    shown.set(dialog);
                    answer.complete(dialog.open());
                } catch (Throwable e) {
                    log.error("Can't show the AI tool confirmation", e);
                    answer.complete(AIToolConfirmationAnswer.DENY);
                }
            });
            while (true) {
                try {
                    return answer.get(WAIT_STEP_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (monitor.isCanceled()) {
                        SwingUtilities.invokeLater(() -> {
                            AiConfirmationDialog dialog = shown.get();
                            if (dialog != null) {
                                dialog.close(AIToolConfirmationAnswer.DENY);
                            }
                        });
                        return AIToolConfirmationAnswer.DENY;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AIToolConfirmationAnswer.DENY;
                } catch (ExecutionException e) {
                    return AIToolConfirmationAnswer.DENY;
                }
            }
        }
    }

    private final AIToolConfirmationRequest request;
    private AIToolConfirmationAnswer answer = AIToolConfirmationAnswer.DENY;
    @Nullable
    private JCheckBox alwaysAllowBox;

    private AiConfirmationDialog(@Nullable Window owner, @NotNull AIToolConfirmationRequest request) {
        super(owner, request.pendingTransactions().isEmpty() ? AiMessages.ai_confirm_title : AiMessages.ai_confirm_tx_title,
            ModalityType.APPLICATION_MODAL);
        this.request = request;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        boolean transaction = !request.pendingTransactions().isEmpty();

        JPanel content = new JPanel(new BorderLayout(0, 10));
        // Dialog insets 10/12 (design-spec space tokens)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel headline = new JLabel(getHeadline(transaction));
        headline.setIcon(UiIcons.get(request.access() == AIToolAccess.DANGEROUS ? "error" : "warning")); //$NON-NLS-1$ //$NON-NLS-2$
        headline.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4"); //$NON-NLS-1$
        content.add(headline, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(createFacts());
        if (transaction) {
            body.add(Box.createVerticalStrut(10));
            body.add(createTransactions());
        }
        if (!CommonUtils.isEmptyTrimmed(request.preview())) {
            boolean textChange = request.previewKind() == AIToolPreviewProvider.Kind.TEXT_CHANGE;
            body.add(Box.createVerticalStrut(10));
            body.add(createSection(textChange ? AiMessages.ai_confirm_preview_change : AiMessages.ai_confirm_preview,
                createPreview(request.preview(), textChange)));
        }
        if (!request.arguments().isEmpty()) {
            body.add(Box.createVerticalStrut(10));
            body.add(createSection(AiMessages.ai_confirm_arguments, createArguments(request.arguments())));
        }
        content.add(body, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        // The rules live in the settings (Chromite Agent > Policy): a link opens that page
        JPanel rule = new JPanel(new BorderLayout(6, 0));
        if (!transaction && request.access() == AIToolAccess.WRITE && request.connection() != null) {
            alwaysAllowBox = new JCheckBox(NLS.bind(AiMessages.ai_confirm_always_allow, request.toolName(), request.connection().getName()));
            alwaysAllowBox.setToolTipText(AiMessages.ai_confirm_always_allow_tip);
            rule.add(alwaysAllowBox, BorderLayout.CENTER);
        } else if (!transaction && request.access() == AIToolAccess.DANGEROUS) {
            JLabel note = new JLabel(AiMessages.ai_confirm_dangerous_note);
            note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            rule.add(note, BorderLayout.CENTER);
        }
        if (rule.getComponentCount() > 0) {
            rule.add(OptionsLinks.createLink(OptionsDialog.PAGE_AGENT), BorderLayout.EAST);
            bottom.add(rule, BorderLayout.NORTH);
        }
        bottom.add(createButtons(transaction), BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().registerKeyboardAction(e -> close(AIToolConfirmationAnswer.DENY),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                answer = AIToolConfirmationAnswer.DENY;
            }
        });
        pack();
        Dimension size = getSize();
        setSize(Math.max(size.width, 640), Math.min(size.height, 640));
        setLocationRelativeTo(owner);
    }

    @NotNull
    private String getHeadline(boolean transaction) {
        if (transaction) {
            return AiMessages.ai_confirm_tx_headline;
        }
        boolean mcp = request.client() == AIToolClient.MCP;
        if (request.previewKind() == AIToolPreviewProvider.Kind.TEXT_CHANGE) {
            // A query window edit changes the user's text, not the database
            return mcp ? AiMessages.ai_confirm_headline_edit_mcp : AiMessages.ai_confirm_headline_edit;
        }
        if (request.access() == AIToolAccess.DANGEROUS) {
            return mcp ? AiMessages.ai_confirm_headline_dangerous_mcp : AiMessages.ai_confirm_headline_dangerous;
        }
        return mcp ? AiMessages.ai_confirm_headline_mcp : AiMessages.ai_confirm_headline;
    }

    @NotNull
    private JComponent createFacts() {
        JPanel facts = new JPanel(new GridBagLayout());
        facts.setAlignmentX(LEFT_ALIGNMENT);
        int row = 0;
        JPanel tool = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tool.add(new JLabel(request.toolName() + "  (" + request.toolId() + ")  ")); //$NON-NLS-1$ //$NON-NLS-2$
        tool.add(AiToolCard.createAccessBadge(request.access().name()));
        row = addFact(facts, row, AiMessages.ai_confirm_tool, tool);
        DBPDataSourceContainer connection = request.connection();
        if (connection != null) {
            JPanel target = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            target.add(new JLabel(connection.getName() + "  ")); //$NON-NLS-1$
            if (AIToolPolicy.isProduction(connection)) {
                target.add(AiToolCard.createBadge(AiMessages.ai_badge_production, "Chromite.status.error")); //$NON-NLS-1$
            }
            row = addFact(facts, row, AiMessages.ai_confirm_connection, target);
        }
        row = addFact(facts, row, AiMessages.ai_confirm_requested_by, new JLabel(
            request.client() == AIToolClient.MCP ? AiMessages.ai_confirm_client_mcp : AiMessages.ai_confirm_client_chat));
        JLabel reason = new JLabel(describeReason(request.reason(), request.previewKind()));
        reason.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        addFact(facts, row, AiMessages.ai_confirm_reason, reason);
        return facts;
    }

    private static int addFact(@NotNull JPanel panel, int row, @NotNull String label, @NotNull JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 12);
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(value, c);
        return row + 1;
    }

    /**
     * The policy reason in the user's language (the model writes it in English for the audit log). A query window edit
     * changes no data: its default reason names the text and where to let it run without asking.
     */
    @NotNull
    private static String describeReason(@NotNull String reason, @NotNull AIToolPreviewProvider.Kind previewKind) {
        if (reason.startsWith("connection rule")) { //$NON-NLS-1$
            return AiMessages.ai_reason_connection_rule;
        } else if (reason.startsWith("global rule")) { //$NON-NLS-1$
            return AiMessages.ai_reason_global_rule;
        } else if (reason.startsWith("default for")) { //$NON-NLS-1$
            return previewKind == AIToolPreviewProvider.Kind.TEXT_CHANGE ? AiMessages.ai_reason_default_edit : AiMessages.ai_reason_default;
        } else if (reason.contains("uncommitted transaction")) { //$NON-NLS-1$
            return AiMessages.ai_reason_transaction;
        }
        return reason;
    }

    @NotNull
    private JComponent createTransactions() {
        DefaultListModel<String> model = new DefaultListModel<>();
        for (AIPendingTransaction pending : request.pendingTransactions()) {
            model.addElement(pending.owner() + " - " + pending.description() //$NON-NLS-1$
                + (pending.canCommit() ? "" : "  " + AiMessages.ai_confirm_tx_no_commit)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JList<String> list = new JList<>(model);
        list.setVisibleRowCount(Math.min(4, model.size()));
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(AiMessages.ai_confirm_tx_question), BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    @NotNull
    private static JComponent createSection(@NotNull String title, @NotNull JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    /**
     * The preview in a read-only SQL editor. A text change marks its removed ("- ") and added ("+ ") lines with the
     * result grid's removed and added row colors.
     */
    @NotNull
    private static JComponent createPreview(@NotNull String sql, boolean textChange) {
        SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();
        textArea.setText(sql);
        textArea.setCaretPosition(0);
        textArea.setEditable(false);
        textArea.discardAllEdits();
        String[] lines = sql.split("\n", -1); //$NON-NLS-1$
        textArea.setRows(Math.min(12, Math.max(3, lines.length)));
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        textArea.applyTheme();
        if (textChange) {
            Color removed = UIManager.getColor("Chromite.Table.removedBackground"); //$NON-NLS-1$
            Color added = UIManager.getColor("Chromite.Table.addedBackground"); //$NON-NLS-1$
            for (int line = 0; line < lines.length; line++) {
                Color color = lines[line].startsWith("- ") ? removed : lines[line].startsWith("+ ") ? added : null; //$NON-NLS-1$ //$NON-NLS-2$
                if (color != null) {
                    try {
                        textArea.addLineHighlight(line, color);
                    } catch (BadLocationException e) {
                        log.debug(e);
                    }
                }
            }
        }
        return scrollPane;
    }

    @NotNull
    private static JComponent createArguments(@NotNull Map<String, Object> arguments) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{AiMessages.ai_confirm_argument_name, AiMessages.ai_confirm_argument_value}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            Object value = e.getValue();
            String text = value instanceof String s ? s : GSON.toJson(value);
            if (text.length() > MAX_VALUE_CHARS) {
                text = text.substring(0, MAX_VALUE_CHARS) + "..."; //$NON-NLS-1$
            }
            model.addRow(new Object[]{e.getKey(), text.replace('\n', ' ')});
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(480);
        table.setPreferredScrollableViewportSize(new Dimension(600, table.getRowHeight() * Math.min(6, model.getRowCount())));
        return new JScrollPane(table);
    }

    @NotNull
    private JComponent createButtons(boolean transaction) {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton defaultButton;
        if (transaction) {
            JButton commitButton = new JButton(AiMessages.ai_confirm_commit);
            commitButton.setEnabled(request.pendingTransactions().stream().allMatch(AIPendingTransaction::canCommit));
            commitButton.addActionListener(e -> close(AIToolConfirmationAnswer.COMMIT));
            JButton rollbackButton = new JButton(AiMessages.ai_confirm_rollback);
            rollbackButton.addActionListener(e -> close(AIToolConfirmationAnswer.ROLLBACK));
            JButton cancelButton = new JButton(AiMessages.ai_confirm_cancel);
            cancelButton.addActionListener(e -> close(AIToolConfirmationAnswer.DENY));
            buttons.add(commitButton);
            buttons.add(rollbackButton);
            buttons.add(cancelButton);
            defaultButton = cancelButton;
        } else {
            JButton allowButton = new JButton(AiMessages.ai_confirm_allow);
            allowButton.addActionListener(e -> allow());
            JButton denyButton = new JButton(AiMessages.ai_confirm_deny);
            denyButton.addActionListener(e -> close(AIToolConfirmationAnswer.DENY));
            buttons.add(allowButton);
            buttons.add(denyButton);
            defaultButton = denyButton;
        }
        getRootPane().setDefaultButton(defaultButton);
        SwingUtilities.invokeLater(defaultButton::requestFocusInWindow);
        return buttons;
    }

    private void allow() {
        DBPDataSourceContainer connection = request.connection();
        if (alwaysAllowBox != null && alwaysAllowBox.isSelected() && connection != null) {
            try {
                AIToolPolicy.getInstance().setPermission(connection.getId(), request.toolId(), AIToolPermission.AUTO);
            } catch (Exception e) {
                // The call itself is still allowed; only the rule was not saved
                ErrorDialog.show(this, AiMessages.ai_confirm_title, AiMessages.ai_confirm_rule_failed, e);
            }
        }
        close(AIToolConfirmationAnswer.ALLOW);
    }

    private void close(@NotNull AIToolConfirmationAnswer answer) {
        this.answer = answer;
        dispose();
    }

    @NotNull
    private AIToolConfirmationAnswer open() {
        setVisible(true);
        return answer;
    }
}
