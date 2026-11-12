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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.maintenance.MaintenanceCommand;
import com.seizia.chromite.model.admin.maintenance.MaintenanceDialect;
import com.seizia.chromite.model.admin.maintenance.MaintenanceOperation;
import com.seizia.chromite.model.admin.maintenance.MaintenanceOption;
import com.seizia.chromite.model.admin.maintenance.MaintenancePlan;
import com.seizia.chromite.model.admin.maintenance.MaintenanceResult;
import com.seizia.chromite.model.admin.maintenance.MaintenanceRunner;
import com.seizia.chromite.model.admin.maintenance.MaintenanceTarget;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 유지 관리 대화상자: 대상 하나(테이블, 인덱스, 스키마, 데이터베이스, Oracle 무효 개체)에 할 작업과 옵션을 고르고, 명령을 미리 본 뒤 확인을 받아 실행한다.
 *
 * 설계: DB 관리 축 공통 규칙(권한 먼저, 미리 보기, EDT 밖 실행)과 트랜잭션 안전 규칙을 한 화면에서 지킨다.
 * <ul>
 *     <li>열 때 대상의 상태와 로그인 권한을 서버에서 읽는다({@link MaintenanceRunner#prepare}, EDT 밖). 권한이 없는 작업은 안내 줄에 이유를 보이고 실행을 끈다.</li>
 *     <li>작업이나 옵션을 바꿀 때마다 명령 미리 보기를 다시 만든다(서버를 읽지 않는다). 실행하는 문장은 미리 보기에 보인 것 그대로다.</li>
 *     <li>실행은 확인 창(대상 서버, 경고, 별도 연결이라는 것)을 거친 뒤 백그라운드 작업에서 하고, 도넛 스피너와 취소를 보인다. 취소는 작업을 취소하고,
 *     작업의 모니터가 실행 중인 JDBC 문장을 취소한다(PostgreSQL 취소 요청, SQL Server 주의 신호, Oracle 중단).</li>
 *     <li>명령은 사용자의 트랜잭션에 넣지 않는다: 작업마다 새 격리 연결을 자동 커밋으로 열고 닫는다({@link MaintenanceRunner}). 그래서 SQL 편집기의
 *     열린 트랜잭션에 커밋·롤백을 묻을 일이 없다.</li>
 *     <li>결과는 아래 탭에 보인다: 메시지(문장, 서버 메시지, 걸린 시간, 오류)와 서버가 돌려준 표(MySQL Msg_type/Msg_text, Oracle 컴파일 오류).</li>
 * </ul>
 * SWT에는 DBeaver의 도구 대화상자({@code ToolWizardDialog}, {@code SQLToolExecuteHandler})가 비슷한 일을 하지만 미리 보기가 따로 있고 권한 확인이 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 실행 확인은 실행 / 취소 단추(취소가 기본), 실행 중에 닫을 때의 취소 질문은 아니요가 기본 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-12 대상 읽기·실행 중 표시를 길이를 모르는 막대에서 도넛 스피너({@link SpinnerIcon})로 (앱 변경 #60,
 *   docs/chromite/swing-sql-editor-session-ui.md "실행 중 표시")
 * - 2026-11-12 실행 확인의 글이 낱말 사이에서만 줄을 바꿈({@link WrappingNote.MessageArea}): Swing 줄 바꿈이 한국어
 *   낱말 가운데서 끊었다(앱 변경 #82, docs/chromite/swing-options.md "잘리던 한 줄 안내")
 */
final class MaintenanceDialog extends JDialog {

    private static final Log log = Log.getLog(MaintenanceDialog.class);

    /**
     * Statements listed in the confirmation; the rest is counted.
     */
    private static final int CONFIRM_STATEMENTS = 8;
    /**
     * Client property of an option control: false when the server does not allow the option.
     */
    private static final String OPTION_ALLOWED = "chromite.maintenance.optionAllowed"; //$NON-NLS-1$

    private final DBSInstance instance;
    private final MaintenanceDialect dialect;
    private final MaintenanceTarget target;
    private final String serverName;
    @Nullable
    private final String requestedOperation;
    @Nullable
    private final Runnable onFinished;

    private final JComboBox<MaintenanceOperation> operationCombo = new JComboBox<>();
    private final JLabel descriptionLabel = new JLabel(" "); //$NON-NLS-1$
    private final JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JTextArea factsArea = new JTextArea(2, 40);
    private final AccessNoticeBar deniedBar = new AccessNoticeBar();
    private final AccessNoticeBar warningBar = new AccessNoticeBar();
    private final SqlEditorPanel.SqlTextArea previewArea = new SqlEditorPanel.SqlTextArea();
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final JTextArea messagesArea = new JTextArea();
    // The length of the reading and of the run is not known: a spinner, not an indeterminate bar (app change 60)
    private final JLabel spinner = SpinnerIcon.createLabel();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JButton runButton = new JButton(MaintenanceViewMessages.maintview_run);
    private final JButton cancelButton = new JButton(MaintenanceViewMessages.maintview_cancel);
    private final JButton closeButton = new JButton(MaintenanceViewMessages.maintview_close);
    private final JButton copyButton = new JButton(MaintenanceViewMessages.maintview_copy);
    private final Map<String, Object> values = new HashMap<>();

    @Nullable
    private MaintenancePlan plan;
    @Nullable
    private MaintenanceCommand command;
    @Nullable
    private AbstractJob runningJob;

    private MaintenanceDialog(@NotNull Component owner, @NotNull DBPDataSourceContainer container, @NotNull DBSInstance instance,
                              @NotNull MaintenanceDialect dialect, @NotNull MaintenanceTarget target, @Nullable String requestedOperation,
                              @Nullable Runnable onFinished) {
        super(SwingUtilities.getWindowAncestor(owner), NLS.bind(MaintenanceViewMessages.maintview_dialog_title, target.getDisplayName()),
            ModalityType.MODELESS);
        this.instance = instance;
        this.dialect = dialect;
        this.target = target;
        this.serverName = container.getName();
        this.requestedOperation = requestedOperation;
        this.onFinished = onFinished;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested();
            }
        });

        int gap = UIScale.scale(8);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, gap / 2, gap);
        addRow(form, c, MaintenanceViewMessages.maintview_server, new JLabel(serverName));
        JLabel targetLabel = new JLabel(target.getDisplayName());
        targetLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold"); //$NON-NLS-1$
        addRow(form, c, MaintenanceViewMessages.maintview_target, targetLabel);
        operationCombo.setEnabled(false);
        operationCombo.addActionListener(e -> operationChanged());
        addRow(form, c, MaintenanceViewMessages.maintview_operation, operationCombo);
        descriptionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        addRow(form, c, null, descriptionLabel);
        optionsPanel.setOpaque(false);
        addRow(form, c, MaintenanceViewMessages.maintview_options, optionsPanel);
        factsArea.setEditable(false);
        factsArea.setLineWrap(true);
        factsArea.setWrapStyleWord(true);
        factsArea.setOpaque(false);
        factsArea.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        factsArea.setText(MaintenanceViewMessages.maintview_reading_target);
        addRow(form, c, MaintenanceViewMessages.maintview_state, factsArea);

        previewArea.setEditable(false);
        previewArea.setRows(6);
        RTextScrollPane previewScroll = new RTextScrollPane(previewArea, true);
        previewArea.applyTheme();
        JLabel previewLabel = new JLabel(MaintenanceViewMessages.maintview_preview);
        previewLabel.setBorder(BorderFactory.createEmptyBorder(gap, 0, gap / 2, 0));
        JPanel previewPanel = new JPanel(new BorderLayout());
        JPanel previewHeader = new JPanel(new BorderLayout());
        previewHeader.add(warningBar, BorderLayout.NORTH);
        previewHeader.add(previewLabel, BorderLayout.SOUTH);
        previewPanel.add(previewHeader, BorderLayout.NORTH);
        previewPanel.add(previewScroll, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(gap, gap, 0, gap));
        top.add(form, BorderLayout.NORTH);
        top.add(previewPanel, BorderLayout.CENTER);

        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        messagesArea.setFont(previewArea.getFont());
        resultTabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        resultTabs.addTab(MaintenanceViewMessages.maintview_messages, new JScrollPane(messagesArea));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0, gap, 0, gap));
        bottom.add(resultTabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.6);
        split.setBorder(BorderFactory.createEmptyBorder());

        spinner.setVisible(false);
        runButton.setEnabled(false);
        runButton.addActionListener(e -> run());
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancel());
        closeButton.addActionListener(e -> closeRequested());
        copyButton.setEnabled(false);
        copyButton.addActionListener(e -> {
            if (command != null) {
                DBWorkbench.getPlatformUI().copyTextToClipboard(command.preview(), false);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        buttons.add(copyButton);
        buttons.add(runButton);
        buttons.add(cancelButton);
        buttons.add(closeButton);
        JPanel status = new JPanel(new BorderLayout(gap, 0));
        status.add(spinner, BorderLayout.WEST);
        status.add(statusLabel, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(gap, 0));
        south.setBorder(BorderFactory.createEmptyBorder(gap, gap, gap, gap));
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(deniedBar, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> closeRequested(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(UIScale.scale(820), UIScale.scale(680));
        setLocationRelativeTo(owner);
    }

    private static void addRow(@NotNull JPanel form, @NotNull GridBagConstraints c, @Nullable String label, @NotNull JComponent field) {
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST;
        if (label != null) {
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.gridy++;
    }

    /**
     * Opens the dialog for a target and reads its state.
     *
     * @param operationId operation to select first (a suggestion of the maintenance list), or null for the plan's suggestion
     * @param onFinished  called on the EDT after a run (e.g. to read the maintenance list again), or null
     */
    static void open(@NotNull Component owner, @NotNull DBPDataSourceContainer container, @NotNull DBSInstance instance,
                     @NotNull MaintenanceDialect dialect, @NotNull MaintenanceTarget target, @Nullable String operationId,
                     @Nullable Runnable onFinished) {
        MaintenanceDialog dialog = new MaintenanceDialog(owner, container, instance, dialect, target, operationId, onFinished);
        dialog.setVisible(true);
        dialog.readPlan();
    }

    private void readPlan() {
        statusLabel.setText(MaintenanceViewMessages.maintview_reading_target);
        spinner.setVisible(true);
        new AbstractJob("Read maintenance target") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    MaintenancePlan result = MaintenanceRunner.prepare(monitor, instance, dialect, target);
                    SwingUtilities.invokeLater(() -> showPlan(result));
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> {
                        spinner.setVisible(false);
                        factsArea.setText(NLS.bind(MaintenanceViewMessages.maintview_read_error, ErrorText.describeLine(e)));
                        statusLabel.setText(NLS.bind(MaintenanceViewMessages.maintview_read_error, ErrorText.describeLine(e)));
                    });
                    log.debug("Error reading the maintenance target", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void showPlan(@NotNull MaintenancePlan result) {
        plan = result;
        spinner.setVisible(false);
        statusLabel.setText(" "); //$NON-NLS-1$
        factsArea.setText(String.join("\n", result.getFacts())); //$NON-NLS-1$
        String select = requestedOperation != null && result.getOperation(requestedOperation) != null ? requestedOperation
            : result.getSuggestedOperation();
        operationCombo.removeAllItems();
        for (MaintenanceOperation operation : result.getOperations()) {
            operationCombo.addItem(operation);
        }
        operationCombo.setEnabled(operationCombo.getItemCount() > 1);
        MaintenanceOperation selected = select == null ? null : result.getOperation(select);
        if (selected != null) {
            operationCombo.setSelectedItem(selected);
        }
        operationChanged();
    }

    @Nullable
    private MaintenanceOperation getOperation() {
        return operationCombo.getSelectedItem() instanceof MaintenanceOperation operation ? operation : null;
    }

    /**
     * Builds the option controls of the selected operation.
     */
    private void operationChanged() {
        MaintenanceOperation operation = getOperation();
        values.clear();
        optionsPanel.removeAll();
        if (operation == null || plan == null) {
            updateCommand();
            return;
        }
        descriptionLabel.setText(operation.description() == null ? " " : operation.description()); //$NON-NLS-1$
        int gap = UIScale.scale(14);
        for (MaintenanceOption option : operation.options()) {
            JComponent control = switch (option.kind()) {
                case FLAG -> {
                    JCheckBox box = new JCheckBox(option.label(), Boolean.TRUE.equals(option.defaultValue()));
                    box.addActionListener(e -> {
                        values.put(option.id(), box.isSelected());
                        updateCommand();
                    });
                    yield box;
                }
                case CHOICE -> {
                    JComboBox<MaintenanceOption.Choice> combo = new JComboBox<>(option.choices().toArray(new MaintenanceOption.Choice[0]));
                    for (MaintenanceOption.Choice choice : option.choices()) {
                        if (choice.value().equals(option.defaultValue())) {
                            combo.setSelectedItem(choice);
                        }
                    }
                    combo.addActionListener(e -> {
                        if (combo.getSelectedItem() instanceof MaintenanceOption.Choice choice) {
                            values.put(option.id(), choice.value());
                            updateCommand();
                        }
                    });
                    yield labeled(option.label(), combo);
                }
            };
            control.setEnabled(option.enabled());
            control.putClientProperty(OPTION_ALLOWED, option.enabled());
            control.setToolTipText(option.toolTip());
            control.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, gap));
            optionsPanel.add(control);
        }
        if (operation.options().isEmpty()) {
            JLabel none = new JLabel(MaintenanceViewMessages.maintview_no_options);
            none.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
            optionsPanel.add(none);
        }
        optionsPanel.revalidate();
        optionsPanel.repaint();
        updateCommand();
    }

    @NotNull
    private static JComponent labeled(@NotNull String label, @NotNull JComponent control) {
        JPanel panel = new JPanel(new BorderLayout(UIScale.scale(4), 0)) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                control.setEnabled(enabled);
            }

            @Override
            public void setToolTipText(String text) {
                super.setToolTipText(text);
                control.setToolTipText(text);
            }
        };
        panel.setOpaque(false);
        panel.add(new JLabel(label), BorderLayout.WEST);
        panel.add(control, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Rebuilds the preview, warnings and permission notice from the selected operation and options.
     */
    private void updateCommand() {
        MaintenanceOperation operation = getOperation();
        if (operation == null || plan == null) {
            command = null;
            previewArea.setText(""); //$NON-NLS-1$
            warningBar.setNotice(null);
            deniedBar.setNotice(null);
            updateButtons();
            return;
        }
        command = dialect.buildCommand(plan, operation.id(), values);
        previewArea.setText(command.statements().isEmpty() ? "" : command.preview()); //$NON-NLS-1$
        previewArea.setCaretPosition(0);
        warningBar.setNotice(command.warnings().isEmpty() ? null : String.join("\n", command.warnings())); //$NON-NLS-1$
        String denied = plan.getDeniedReason(operation.id());
        deniedBar.setNotice(denied != null ? denied : command.statements().isEmpty() ? MaintenanceViewMessages.maintview_nothing_to_run : null);
        updateButtons();
    }

    private void updateButtons() {
        boolean running = runningJob != null;
        MaintenanceOperation operation = getOperation();
        boolean allowed = plan != null && operation != null && plan.getDeniedReason(operation.id()) == null
            && command != null && !command.statements().isEmpty();
        runButton.setEnabled(allowed && !running);
        copyButton.setEnabled(command != null && !command.statements().isEmpty());
        cancelButton.setEnabled(running);
        operationCombo.setEnabled(!running && operationCombo.getItemCount() > 1);
        setEnabledDeep(optionsPanel, !running);
    }

    private static void setEnabledDeep(@NotNull JPanel panel, boolean enabled) {
        for (Component component : panel.getComponents()) {
            // Options the server does not allow stay off (their tool tip says why)
            if (component instanceof JComponent control) {
                control.setEnabled(enabled && !Boolean.FALSE.equals(control.getClientProperty(OPTION_ALLOWED)));
            }
        }
    }

    /**
     * Asks for confirmation and runs the command in a background job.
     */
    private void run() {
        MaintenanceCommand runCommand = command;
        if (runCommand == null || runCommand.statements().isEmpty() || runningJob != null) {
            return;
        }
        if (!confirm(runCommand)) {
            return;
        }
        resultTabs.setSelectedIndex(0);
        while (resultTabs.getTabCount() > 1) {
            resultTabs.removeTabAt(1);
        }
        messagesArea.setText(""); //$NON-NLS-1$
        spinner.setVisible(true);
        statusLabel.setText(MaintenanceViewMessages.maintview_running);
        AbstractJob job = new AbstractJob("Run maintenance") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                long start = System.currentTimeMillis();
                try {
                    MaintenanceResult result = MaintenanceRunner.run(monitor, instance, dialect, runCommand);
                    SwingUtilities.invokeLater(() -> showResult(result));
                } catch (Throwable e) {
                    long elapsed = System.currentTimeMillis() - start;
                    SwingUtilities.invokeLater(() -> showResult(new MaintenanceResult(List.of(), List.of(new MaintenanceResult.Message(
                        MaintenanceResult.Level.ERROR, ErrorText.describe(e))), MaintenanceResult.Status.FAILED, elapsed)));
                    log.debug("Error running maintenance", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        };
        runningJob = job;
        updateButtons();
        job.schedule();
    }

    private boolean confirm(@NotNull MaintenanceCommand runCommand) {
        StringBuilder text = new StringBuilder(NLS.bind(MaintenanceViewMessages.maintview_confirm, serverName));
        text.append("\n\n"); //$NON-NLS-1$
        List<String> statements = runCommand.statements();
        for (int i = 0; i < statements.size() && i < CONFIRM_STATEMENTS; i++) {
            text.append(statements.get(i)).append('\n');
        }
        if (statements.size() > CONFIRM_STATEMENTS) {
            text.append(NLS.bind(MaintenanceViewMessages.maintview_confirm_more, statements.size() - CONFIRM_STATEMENTS)).append('\n');
        }
        for (String warning : runCommand.warnings()) {
            text.append('\n').append(warning).append('\n');
        }
        text.append('\n').append(MaintenanceViewMessages.maintview_confirm_connection);
        JTextArea message = new WrappingNote.MessageArea(text.toString());
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        message.setColumns(60);
        message.setSize(new Dimension(UIScale.scale(520), Short.MAX_VALUE));
        return SwingPlatformUI.confirmChange(this, message, MaintenanceViewMessages.maintview_confirm_title, MaintenanceViewMessages.maintview_run,
            runCommand.warnings().isEmpty() ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private void cancel() {
        AbstractJob job = runningJob;
        if (job != null) {
            cancelButton.setEnabled(false);
            statusLabel.setText(MaintenanceViewMessages.maintview_canceling);
            job.cancel();
        }
    }

    private void showResult(@NotNull MaintenanceResult result) {
        runningJob = null;
        spinner.setVisible(false);
        String seconds = String.format("%.1f", result.elapsedMillis() / 1000.0); //$NON-NLS-1$
        statusLabel.setText(NLS.bind(switch (result.status()) {
            case SUCCEEDED -> MaintenanceViewMessages.maintview_done;
            case FAILED -> MaintenanceViewMessages.maintview_failed;
            case CANCELED -> MaintenanceViewMessages.maintview_canceled;
        }, seconds));
        StringBuilder text = new StringBuilder();
        for (MaintenanceResult.Message message : result.messages()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(message.level() == MaintenanceResult.Level.ERROR
                ? NLS.bind(MaintenanceViewMessages.maintview_msg_error, message.text()) : message.text());
        }
        messagesArea.setText(text.toString());
        messagesArea.setCaretPosition(messagesArea.getDocument().getLength());
        for (AdminGrid grid : result.grids()) {
            AdminGridTable table = new AdminGridTable(false);
            table.setRows(grid.getColumns(), grid.getRows());
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            resultTabs.addTab(grid.getTitle(), scroll);
        }
        if (!result.grids().isEmpty() && !result.grids().get(0).getRows().isEmpty() && result.status() != MaintenanceResult.Status.FAILED) {
            // The server's own answer (Msg_type/Msg_text, compile errors) says more than the log; an empty one says less
            resultTabs.setSelectedIndex(1);
        }
        updateButtons();
        if (onFinished != null) {
            onFinished.run();
        }
    }

    private void closeRequested() {
        if (runningJob != null) {
            Object[] options = {UIManager.getString("OptionPane.yesButtonText"), UIManager.getString("OptionPane.noButtonText")}; //$NON-NLS-1$ //$NON-NLS-2$
            // No is the default button: Enter must not stop the running command
            int answer = JOptionPane.showOptionDialog(this, MaintenanceViewMessages.maintview_close_running,
                MaintenanceViewMessages.maintview_confirm_title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (answer == 0) {
                cancel();
            }
            return;
        }
        dispose();
    }
}
