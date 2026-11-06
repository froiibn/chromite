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
import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 데이터베이스 작업 대화상자(백업·복원)의 공통 틀.
 *
 * 설계: SSMS의 데이터베이스 작업 대화상자(Back Up Database, Restore Database) 배치를 따른다. 왼쪽에 "페이지 선택" 목록,
 * 그 아래 연결 정보와 진행 상태(상태 글자와 진행 막대), 오른쪽에 고른 페이지, 아래에 스크립트 단추(메뉴)와 확인·취소다.
 * 실행하면 마지막 "메시지" 페이지로 넘어가 서버 메시지(STATS 진행률 등)나 도구 출력을 차례로 보여 준다.
 * <ul>
 *     <li>실행은 백그라운드 Job({@link #runTask})이고, 그동안 페이지 목록을 잠가 설정 페이지로 돌아가지 못하게 한다
 *     (설정은 시작할 때 이미 읽었으므로 고쳐도 소용이 없다). 취소 단추와 창 닫기는 Job을 취소한다.</li>
 *     <li>성공하면 창을 닫지 않고 메시지 페이지에 결과를 남긴다(확인 단추는 끄고 취소 단추는 닫기가 된다). 서버 메시지를
 *     다시 볼 수 있게 하려는 것이다. 실패하면 오류를 메시지에 남기고 다시 고쳐 실행할 수 있게 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
abstract class DatabaseTaskDialog extends JDialog {

    private static final Log log = Log.getLog(DatabaseTaskDialog.class);

    /**
     * One page of the dialog.
     */
    record Page(@NotNull String title, @NotNull JComponent component) {
    }

    private final DBPDataSourceContainer container;
    private final DefaultListModel<String> pageModel = new DefaultListModel<>();
    private final JList<String> pageList = new JList<>(pageModel);
    private final JLabel pageTitle = new JLabel();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JTextArea messagesArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(BackupRestoreMessages.task_status_ready);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton scriptButton = new JButton(BackupRestoreMessages.task_button_script);
    private final JButton okButton = new JButton(BackupRestoreMessages.task_button_ok);
    private final JButton cancelButton = new JButton(BackupRestoreMessages.task_button_cancel);
    private final List<Page> pages = new ArrayList<>();
    @Nullable
    private AbstractJob runningJob;
    private boolean succeeded;

    DatabaseTaskDialog(@NotNull Component owner, @NotNull String title, @NotNull DBPDataSourceContainer container) {
        super(SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
        this.container = container;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelOrClose();
            }
        });
        getRootPane().registerKeyboardAction(e -> cancelOrClose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Adds the pages (and the Messages page) and lays the dialog out; called by the subclass constructor.
     */
    protected final void initDialog(@NotNull List<Page> settingsPages) {
        pages.addAll(settingsPages);
        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        messagesArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        pages.add(new Page(BackupRestoreMessages.task_page_messages, new JScrollPane(messagesArea)));
        for (int i = 0; i < pages.size(); i++) {
            pageModel.addElement(pages.get(i).title());
            cardPanel.add(pages.get(i).component(), String.valueOf(i));
        }
        pageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pageList.addListSelectionListener(e -> {
            int index = pageList.getSelectedIndex();
            if (!e.getValueIsAdjusting() && index >= 0) {
                pageTitle.setText(pages.get(index).title());
                cards.show(cardPanel, String.valueOf(index));
            }
        });

        // Left: page list, connection, progress (as the database task dialogs this one follows)
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        addSection(left, BackupRestoreMessages.task_select_page);
        pageList.setAlignmentX(Component.LEFT_ALIGNMENT);
        pageList.setOpaque(false);
        left.add(pageList);
        left.add(Box.createVerticalGlue());
        addSection(left, BackupRestoreMessages.task_connection);
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        addInfo(left, BackupRestoreMessages.task_connection_server, CommonUtils.isEmpty(configuration.getHostName())
            ? CommonUtils.notEmpty(configuration.getUrl()) : configuration.getHostName()
            + (CommonUtils.isEmpty(configuration.getHostPort()) ? "" : ":" + configuration.getHostPort())); //$NON-NLS-1$ //$NON-NLS-2$
        addInfo(left, BackupRestoreMessages.task_connection_name, container.getName());
        addInfo(left, BackupRestoreMessages.task_connection_user, CommonUtils.notEmpty(configuration.getUserName()));
        left.add(Box.createVerticalStrut(12));
        addSection(left, BackupRestoreMessages.task_progress);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(statusLabel);
        left.add(Box.createVerticalStrut(6));
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressBar.getPreferredSize().height));
        left.add(progressBar);
        left.setPreferredSize(new Dimension(230, 0));
        JPanel leftWithLine = new JPanel(new BorderLayout());
        leftWithLine.add(left, BorderLayout.CENTER);
        leftWithLine.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.EAST);

        pageTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3"); //$NON-NLS-1$
        pageTitle.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        cardPanel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        JPanel right = new JPanel(new BorderLayout());
        right.add(pageTitle, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.add(new JSeparator(), BorderLayout.NORTH);
        body.add(cardPanel, BorderLayout.CENTER);
        right.add(body, BorderLayout.CENTER);

        scriptButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        scriptButton.setIcon(UIManager.getIcon("Tree.expandedIcon")); //$NON-NLS-1$
        scriptButton.setHorizontalTextPosition(SwingConstants.LEFT);
        scriptButton.addActionListener(e -> showScriptMenu());
        okButton.addActionListener(e -> start());
        cancelButton.addActionListener(e -> cancelOrClose());
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftButtons.add(scriptButton);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightButtons.add(okButton);
        rightButtons.add(cancelButton);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        buttons.add(leftButtons, BorderLayout.WEST);
        buttons.add(rightButtons, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.add(leftWithLine, BorderLayout.WEST);
        content.add(right, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        pageList.setSelectedIndex(0);

        setSize(900, 640);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(getOwner());
    }

    private static void addSection(@NotNull JPanel panel, @NotNull String title) {
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        panel.add(label);
    }

    private static void addInfo(@NotNull JPanel panel, @NotNull String name, @NotNull String value) {
        JLabel nameLabel = new JLabel(name);
        nameLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel valueLabel = new JLabel(value);
        valueLabel.setToolTipText(value);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        panel.add(nameLabel);
        panel.add(valueLabel);
    }

    @NotNull
    protected final DBPDataSourceContainer getContainer() {
        return container;
    }

    /**
     * Shows the dialog (blocks until it is closed).
     */
    final void open() {
        setVisible(true);
    }

    /**
     * Checks the input before running, on the EDT.
     *
     * @return why the task can't run (shown to the user), or null
     */
    @Nullable
    protected abstract String validateInput();

    /**
     * Prepares the run on the EDT (reads the controls); the returned runnable runs in the background job.
     */
    @NotNull
    protected abstract TaskRun prepareRun();

    /**
     * The work of a run, in a background job.
     */
    interface TaskRun {
        void run(@NotNull DBRProgressMonitor monitor) throws Exception;
    }

    /**
     * Message shown when the run succeeded.
     */
    @NotNull
    protected abstract String getSuccessText();

    /**
     * Called on the EDT after a successful run (e.g. to refresh the navigator).
     */
    protected void runSucceeded() {
    }

    /**
     * Items of the Script button menu.
     */
    @NotNull
    protected abstract List<Action> getScriptActions();

    /**
     * Builds a script on the EDT (may read the server with a progress dialog).
     */
    interface ScriptBuilder {
        @NotNull
        String build() throws Exception;
    }

    /**
     * Script menu of the SQL dialogs: the script to a new query window or to the clipboard. The input is checked first.
     */
    @NotNull
    protected final List<Action> createSqlScriptActions(
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @NotNull ScriptBuilder builder
    ) {
        Action toWindow = new AbstractAction(BackupRestoreMessages.task_script_new_query) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String script = buildScript(builder);
                if (script != null) {
                    queryWindows.openScript(container, script, 0, false);
                    dispose();
                }
            }
        };
        Action toClipboard = new AbstractAction(BackupRestoreMessages.task_script_clipboard) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String script = buildScript(builder);
                if (script != null) {
                    DBWorkbench.getPlatformUI().copyTextToClipboard(script, false);
                }
            }
        };
        return List.of(toWindow, toClipboard);
    }

    /**
     * Checks the input and builds the script; null (after telling the user why) if it can't be built.
     */
    @Nullable
    protected final String buildScript(@NotNull ScriptBuilder builder) {
        String problem = validateInput();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            return builder.build();
        } catch (Exception e) {
            log.debug("Can't build the script", e);
            JOptionPane.showMessageDialog(this, getErrorText(e), getTitle(), JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /**
     * Error text of a failed run for the Messages page.
     */
    @NotNull
    protected String getErrorText(@NotNull Throwable error) {
        return CommonUtils.getAllExceptionMessages(error);
    }

    private void showScriptMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Action action : getScriptActions()) {
            menu.add(action);
        }
        menu.show(scriptButton, 0, scriptButton.getHeight());
    }

    /**
     * Appends a line to the Messages page; may be called from any thread.
     */
    protected final void appendMessage(@NotNull String text) {
        SwingUtilities.invokeLater(() -> {
            if (messagesArea.getDocument().getLength() > 0) {
                messagesArea.append("\n"); //$NON-NLS-1$
            }
            messagesArea.append(text);
            messagesArea.setCaretPosition(messagesArea.getDocument().getLength());
        });
    }

    /**
     * Shows the progress of the run (0-100, or -1 for unknown); may be called from any thread.
     */
    protected final void reportProgress(int percent) {
        SwingUtilities.invokeLater(() -> {
            if (runningJob == null) {
                return;
            }
            if (percent < 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setValue(Math.min(100, percent));
                statusLabel.setText(NLS.bind(BackupRestoreMessages.task_status_percent, percent));
            }
        });
    }

    private void start() {
        if (runningJob != null || succeeded) {
            return;
        }
        String problem = validateInput();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        TaskRun run;
        try {
            run = prepareRun();
        } catch (Exception e) {
            log.debug("Can't prepare the task", e);
            JOptionPane.showMessageDialog(this, getErrorText(e), getTitle(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        messagesArea.setText(""); //$NON-NLS-1$
        pageList.setSelectedIndex(pages.size() - 1);
        pageList.setEnabled(false);
        okButton.setEnabled(false);
        scriptButton.setEnabled(false);
        statusLabel.setText(BackupRestoreMessages.task_status_executing);
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);

        Throwable[] error = new Throwable[1];
        boolean[] canceled = new boolean[1];
        AbstractJob job = new AbstractJob(getTitle()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    run.run(monitor);
                } catch (Throwable e) {
                    error[0] = e;
                }
                canceled[0] = monitor.isCanceled();
                return Status.OK_STATUS;
            }
        };
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                SwingUtilities.invokeLater(() -> finished(error[0], canceled[0]));
            }
        });
        runningJob = job;
        job.schedule();
    }

    private void finished(@Nullable Throwable error, boolean canceled) {
        runningJob = null;
        progressBar.setIndeterminate(false);
        if (error == null && !canceled) {
            succeeded = true;
            progressBar.setValue(100);
            statusLabel.setText(BackupRestoreMessages.task_status_completed);
            appendMessage(getSuccessText());
            cancelButton.setText(BackupRestoreMessages.task_button_close);
            runSucceeded();
        } else {
            progressBar.setValue(0);
            if (canceled) {
                statusLabel.setText(BackupRestoreMessages.task_status_canceled);
                appendMessage(BackupRestoreMessages.task_message_canceled);
            } else {
                log.debug("Database task failed", error);
                statusLabel.setText(BackupRestoreMessages.task_status_failed);
                appendMessage(getErrorText(error));
            }
            pageList.setEnabled(true);
            okButton.setEnabled(true);
            scriptButton.setEnabled(true);
            cancelButton.setEnabled(true);
        }
    }

    private void cancelOrClose() {
        if (runningJob != null) {
            cancelButton.setEnabled(false);
            statusLabel.setText(BackupRestoreMessages.task_status_canceling);
            runningJob.cancel();
            return;
        }
        dispose();
    }

    //////////////////////////////////////////////////////////
    // Layout helpers for the pages

    /**
     * A page body: rows of label + control from the top, the rest of the height empty.
     */
    static final class FormPanel extends JPanel {
        private final GridBagConstraints constraints = new GridBagConstraints();

        FormPanel() {
            super(new GridBagLayout());
            constraints.gridy = 0;
            constraints.insets = new Insets(0, 0, 8, 8);
            constraints.anchor = GridBagConstraints.WEST;
        }

        /**
         * A section title (with a gap above unless it is the first row).
         */
        void addSection(@NotNull String title) {
            JLabel label = new JLabel(title);
            label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            if (constraints.gridy > 0) {
                label.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            }
            addFull(label);
        }

        void addRow(@NotNull String label, @NotNull JComponent control) {
            constraints.gridx = 0;
            constraints.gridwidth = 1;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            JLabel labelComponent = new JLabel(label);
            labelComponent.setLabelFor(control);
            add(labelComponent, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            add(control, constraints);
            constraints.gridy++;
        }

        void addFull(@NotNull JComponent component) {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            add(component, constraints);
            constraints.gridy++;
        }

        /**
         * A component that takes the remaining height (a table).
         */
        void addFiller(@NotNull JComponent component) {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weightx = 1;
            constraints.weighty = 1;
            constraints.fill = GridBagConstraints.BOTH;
            add(component, constraints);
            constraints.weighty = 0;
            constraints.gridy++;
        }

        /**
         * Ends the form: the rest of the height stays empty.
         */
        void finish() {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weighty = 1;
            add(Box.createGlue(), constraints);
        }

        /**
         * A secondary-colored note that wraps at the page width (a label would cut long Korean sentences off).
         */
        @NotNull
        static JTextArea note(@NotNull String text) {
            JTextArea area = new JTextArea(text) {
                private int laidOutHeight = -1;

                @Override
                public Dimension getPreferredSize() {
                    // Width comes from the form column; the height follows the width it was given
                    return new Dimension(1, super.getPreferredSize().height);
                }

                @Override
                public void setBounds(int x, int y, int width, int height) {
                    super.setBounds(x, y, width, height);
                    // Wrapping at the new width changes the height: lay the form out again once
                    int wanted = super.getPreferredSize().height;
                    if (wanted != height && wanted != laidOutHeight) {
                        laidOutHeight = wanted;
                        SwingUtilities.invokeLater(this::revalidate);
                    }
                }
            };
            area.setEditable(false);
            area.setFocusable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setOpaque(false);
            area.setBorder(BorderFactory.createEmptyBorder());
            area.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
            area.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            return area;
        }
    }
}
