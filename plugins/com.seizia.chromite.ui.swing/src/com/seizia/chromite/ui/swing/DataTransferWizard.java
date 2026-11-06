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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.MonitorRunnableContext;
import com.seizia.chromite.model.runtime.ProxyProgressMonitor;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskExecutionListener;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.tools.transfer.DataTransferPipe;
import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.DataTransferState;
import com.seizia.chromite.tools.transfer.IDataTransferConsumer;
import com.seizia.chromite.tools.transfer.IDataTransferProducer;
import com.seizia.chromite.tools.transfer.task.DTTaskHandlerTransfer;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * SSMS "SQL Server Import and Export Wizard"처럼 단계를 따라 데이터 내보내기·가져오기를 설정하고 실행하는 대화상자의 공통 틀.
 *
 * 설계:
 * <ul>
 *     <li>단계(페이지)는 하위 클래스({@link DataExportWizard}, {@link DataImportWizard})가 만들고, 이 클래스는 SSMS 마법사와 같은
 *     왼쪽 단계 목록, 머리글(제목·설명), Back / Next / Finish / Cancel, 마지막 진행 페이지를 맡는다.</li>
 *     <li>실행은 SWT 마법사({@code DataTransferWizard.performFinish} → {@code DataTransferWizardExecutor.runTask})와 같이 모델의
 *     {@link DTTaskHandlerTransfer#executeWithSettings}에 {@link DataTransferSettings}를 그대로 넘긴다. 그래서 파이프 초기화, 대상 테이블 만들기,
 *     참조 무결성 끄기·되돌리기, 작업 Job({@code DataTransferJob}), 이벤트 처리기(폴더 열기, 실패한 파일 지우기)가 SWT와 같다.
 *     SWT처럼 임시 작업({@code createTemporaryTask})을 만들어 넘기고, 직렬화하지 않으므로 질의 결과 같은 저장할 수 없는 원본도 쓸 수 있다.</li>
 *     <li>EDT를 막지 않도록 실행은 {@link AbstractJob}에서 하고, 모델이 부르는 {@code DBRRunnableContext}는 그 Job의 모니터로 바로 실행하는
 *     {@link MonitorRunnableContext}다. Cancel은 Job을 취소하고, {@link AbstractJob}이 실행 중인 문장을 취소한다.</li>
 *     <li>진행 페이지는 파이프(원본 하나 → 대상 하나)마다 한 행이다. 모델의 작업 Job은 {@link DataTransferSettings#acquireDataPipe}로 파이프를
 *     차례로 가져가므로, 이 메서드를 재정의해 지금 처리 중인 파이프를 안다({@link #createSettings}). 모니터의 작업 이름("N rows fetched",
 *     "Insert rows (N)")은 그 행의 메시지 칸에 보인다. 끝나면 원본·대상 통계로 행 수를 쓴다.</li>
 *     <li>오류는 모델처럼 {@link DBTTaskExecutionListener#taskFinished}의 오류로 받는다. 행을 넣다 난 오류는 모델이
 *     {@code showErrorStopRetryIgnore}(Stop / Retry / Skip / Skip All)로 묻는다({@link SwingPlatformUI}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 데이터 전송: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 테이블 복사: 성공 뒤 처리({@link #transferSucceeded}), 선택에 따라 단계 목록 다시 그리기({@link #refreshSteps})
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만들고, 오류 영역에 스택 추적을 여는 "자세히…" 단추를 더함 (docs/chromite/swing-frontend.md "오류 문구")
 */
abstract class DataTransferWizard extends JDialog {

    private static final Log log = Log.getLog(DataTransferWizard.class);

    /**
     * One step of the wizard.
     */
    interface Page {
        @NotNull
        String getTitle();

        @NotNull
        String getDescription();

        @NotNull
        JComponent getComponent();

        /**
         * Called each time the page is shown.
         */
        default void activate() {
        }

        /**
         * Called before leaving the page forward (Next or Finish).
         *
         * @return why the page can't be left (shown to the user), or null
         */
        @Nullable
        default String leave() {
            return null;
        }

        /**
         * Pages that don't apply to the current choices are skipped.
         */
        default boolean isApplicable() {
            return true;
        }
    }

    private enum PipeState {
        WAITING(ToolsMessages.transfer_state_waiting),
        RUNNING(ToolsMessages.transfer_state_running),
        SUCCESS(ToolsMessages.transfer_state_success),
        ERROR(ToolsMessages.transfer_state_error),
        CANCELED(ToolsMessages.transfer_state_canceled),
        NOT_RUN(ToolsMessages.transfer_state_not_run);

        private final String label;

        PipeState(@NotNull String label) {
            this.label = label;
        }
    }

    private static final class PipeRow {
        final DataTransferPipe pipe;
        final String source;
        String target;
        PipeState state = PipeState.WAITING;
        String rows = "";
        String message = "";

        PipeRow(@NotNull DataTransferPipe pipe, @NotNull String source, @NotNull String target) {
            this.pipe = pipe;
            this.source = source;
            this.target = target;
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private final JPanel stepsPanel = new JPanel(new GridBagLayout());
    private final JLabel titleLabel = new JLabel();
    private final JLabel descriptionLabel = new JLabel();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JButton backButton = new JButton(ToolsMessages.wizard_button_back);
    private final JButton nextButton = new JButton(ToolsMessages.wizard_button_next);
    private final JButton finishButton = new JButton(ToolsMessages.wizard_button_finish);
    private final JButton cancelButton = new JButton(ToolsMessages.button_cancel);
    private final JLabel progressLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea errorArea = new JTextArea(4, 40);
    private final JScrollPane errorScroll = new JScrollPane(errorArea);
    private final ErrorDialog.DetailsButton errorDetailsButton = new ErrorDialog.DetailsButton(this::getTitle);
    private final JPanel errorPanel = new JPanel(new BorderLayout(0, 2));
    private final List<PipeRow> pipeRows = new ArrayList<>();
    private final PipeTableModel pipeTableModel = new PipeTableModel();
    private int currentPage = -1;
    @Nullable
    private AbstractJob runningJob;
    private boolean finished;

    DataTransferWizard(@Nullable Window owner, @NotNull String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
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
     * Adds the pages and lays the dialog out; called by the subclass constructor once its fields are set.
     */
    protected final void initWizard(@NotNull List<Page> wizardPages) {
        pages.addAll(wizardPages);
        pages.add(new ProgressPage());

        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3"); //$NON-NLS-1$
        descriptionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(descriptionLabel, BorderLayout.CENTER);

        for (int i = 0; i < pages.size(); i++) {
            cardPanel.add(pages.get(i).getComponent(), String.valueOf(i));
        }
        cardPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        JPanel right = new JPanel(new BorderLayout());
        right.add(header, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.add(new JSeparator(), BorderLayout.NORTH);
        body.add(cardPanel, BorderLayout.CENTER);
        right.add(body, BorderLayout.CENTER);

        stepsPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 20));
        JPanel left = new JPanel(new BorderLayout());
        left.add(stepsPanel, BorderLayout.NORTH);
        // Fixed width: the semibold current step would otherwise change the width from page to page
        left.setPreferredSize(new Dimension(240, 0));
        JPanel leftWithLine = new JPanel(new BorderLayout());
        leftWithLine.add(left, BorderLayout.CENTER);
        leftWithLine.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.EAST);

        backButton.addActionListener(e -> showPage(findPage(currentPage, -1)));
        nextButton.addActionListener(e -> goNext());
        finishButton.addActionListener(e -> finish());
        cancelButton.addActionListener(e -> cancelOrClose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(backButton);
        buttons.add(nextButton);
        buttons.add(finishButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        south.add(buttons, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.add(leftWithLine, BorderLayout.WEST);
        content.add(right, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(nextButton);

        setSize(860, 600);
        setMinimumSize(new Dimension(720, 480));
        setLocationRelativeTo(getOwner());
        showPage(findPage(-1, 1));
    }

    /**
     * Shows the dialog (blocks until it is closed).
     */
    final void open() {
        setVisible(true);
    }

    /**
     * Builds the transfer from the pages, on the EDT after the last settings page was left.
     *
     * @return the settings to run, or null if the transfer can't start (the reason was shown)
     */
    @Nullable
    protected abstract DataTransferSettings prepareTransfer();

    /**
     * Name of a pipe's source in the progress table.
     */
    @NotNull
    protected abstract String describeSource(@NotNull DataTransferPipe pipe);

    /**
     * Name of a pipe's target in the progress table before the transfer.
     */
    @NotNull
    protected abstract String describeTarget(@NotNull DataTransferPipe pipe);

    /**
     * Name of a pipe's target after it was transferred (e.g. the file actually written), or null to keep it.
     */
    @Nullable
    protected String describeResult(@NotNull DataTransferPipe pipe) {
        return null;
    }

    /**
     * Called on the EDT when the whole transfer succeeded (e.g. to open the copied tables).
     */
    protected void transferSucceeded() {
    }

    /**
     * Updates the step list and the buttons after a choice that makes other pages applicable (e.g. the destination type).
     */
    protected final void refreshSteps() {
        updateSteps();
        updateButtons();
    }

    /**
     * Data transfer settings whose pipe hand-out is reported to the progress page.
     */
    @NotNull
    protected final DataTransferSettings createSettings(
        @Nullable Collection<? extends IDataTransferProducer<?>> producers,
        @Nullable Collection<? extends IDataTransferConsumer<?, ?>> consumers,
        boolean export
    ) {
        return new DataTransferSettings(producers, consumers, new HashMap<>(), new DataTransferState(), true, export, false, false) {
            @Nullable
            @Override
            public synchronized DataTransferPipe acquireDataPipe(@NotNull DBRProgressMonitor monitor, @Nullable DBTTask task) {
                DataTransferPipe pipe = super.acquireDataPipe(monitor, task);
                SwingUtilities.invokeLater(() -> pipeStarted(pipe));
                return pipe;
            }
        };
    }

    //////////////////////////////////////////////////////////
    // Navigation

    private int findPage(int from, int direction) {
        for (int i = from + direction; i >= 0 && i < pages.size() - 1; i += direction) {
            if (pages.get(i).isApplicable()) {
                return i;
            }
        }
        return -1;
    }

    private void showPage(int index) {
        if (index < 0) {
            return;
        }
        currentPage = index;
        Page page = pages.get(index);
        titleLabel.setText(page.getTitle());
        descriptionLabel.setText(page.getDescription());
        page.activate();
        cards.show(cardPanel, String.valueOf(index));
        updateSteps();
        updateButtons();
    }

    private void goNext() {
        Page page = pages.get(currentPage);
        String error = page.leave();
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        showPage(findPage(currentPage, 1));
    }

    private void updateButtons() {
        boolean progress = isProgressPage();
        backButton.setEnabled(!progress && findPage(currentPage, -1) >= 0);
        nextButton.setEnabled(!progress && findPage(currentPage, 1) >= 0);
        finishButton.setEnabled(!progress);
        cancelButton.setText(finished ? ToolsMessages.button_close : ToolsMessages.button_cancel);
        cancelButton.setEnabled(!progress || runningJob != null || finished);
        getRootPane().setDefaultButton(progress ? cancelButton : nextButton.isEnabled() ? nextButton : finishButton);
    }

    private boolean isProgressPage() {
        return currentPage == pages.size() - 1;
    }

    /**
     * Step list on the left (SSMS wizards): the current step in semibold, the others in the secondary color.
     */
    private void updateSteps() {
        stepsPanel.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 8, 0);
        int row = 0;
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (!page.isApplicable() && i != pages.size() - 1) {
                continue;
            }
            JLabel label = new JLabel(page.getTitle());
            if (i == currentPage) {
                label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            } else {
                label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            }
            c.gridy = row++;
            stepsPanel.add(label, c);
        }
        stepsPanel.revalidate();
        stepsPanel.repaint();
    }

    private void finish() {
        // Leave the current page and every following applicable page, as Next would
        for (int i = currentPage; i >= 0 && i < pages.size() - 1; i = findPage(i, 1)) {
            Page page = pages.get(i);
            if (i != currentPage) {
                page.activate();
            }
            String error = page.leave();
            if (error != null) {
                showPage(i);
                JOptionPane.showMessageDialog(this, error, getTitle(), JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        DataTransferSettings settings = prepareTransfer();
        if (settings == null) {
            return;
        }
        startTransfer(settings);
    }

    private void cancelOrClose() {
        if (runningJob != null) {
            cancelButton.setEnabled(false);
            progressLabel.setText(ToolsMessages.transfer_progress_canceling);
            runningJob.cancel();
            return;
        }
        dispose();
    }

    //////////////////////////////////////////////////////////
    // Transfer

    private void startTransfer(@NotNull DataTransferSettings settings) {
        pipeRows.clear();
        for (DataTransferPipe pipe : settings.getDataPipes()) {
            pipeRows.add(new PipeRow(pipe, describeSource(pipe), describeTarget(pipe)));
        }
        pipeTableModel.fireTableDataChanged();
        progressBar.setMaximum(Math.max(1, pipeRows.size()));
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        errorArea.setText("");
        errorDetailsButton.setError(null, null);
        errorPanel.setVisible(false);

        Throwable[] result = new Throwable[1];
        String title = getTitle();
        DBPProject project = settings.getProject();
        AbstractJob job = new AbstractJob(title) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                DBRProgressMonitor reporting = new ProxyProgressMonitor(monitor) {
                    @Override
                    public void beginTask(@NotNull String name, int totalWork) {
                        super.beginTask(name, totalWork);
                        reportTask(name);
                    }

                    @Override
                    public void subTask(@NotNull String name) {
                        super.subTask(name);
                        reportTask(name);
                    }
                };
                DBTTaskExecutionListener listener = new DBTTaskExecutionListener() {
                    @Override
                    public void taskStarted(@Nullable DBTTask task) {
                    }

                    @Override
                    public void taskFinished(@Nullable DBTTask task, @Nullable Object res, @Nullable Throwable error, @Nullable Object s) {
                        result[0] = error;
                    }

                    @Override
                    public void subTaskFinished(@Nullable DBTTask task, @Nullable Throwable error, @Nullable Object s) {
                    }
                };
                try {
                    new DTTaskHandlerTransfer().executeWithSettings(
                        new MonitorRunnableContext(reporting),
                        createTemporaryTask(project, title, settings.isProducerOptional()),
                        Locale.getDefault(),
                        log,
                        null,
                        listener,
                        settings);
                } catch (Throwable e) {
                    if (result[0] == null) {
                        result[0] = e;
                    }
                }
                if (result[0] == null && monitor.isCanceled()) {
                    result[0] = new DBInterruptedException("Data transfer was canceled");
                }
                return Status.OK_STATUS;
            }
        };
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                SwingUtilities.invokeLater(() -> transferFinished(result[0]));
            }
        });
        runningJob = job;
        showPage(pages.size() - 1);
        progressLabel.setText(ToolsMessages.transfer_progress_starting);
        job.schedule();
    }

    /**
     * A temporary task as the SWT wizard creates for a transfer that is not saved as a task
     * ({@code DataTransferWizard.DataTransferWizardExecutor}); null if the task type is not registered.
     */
    @Nullable
    private static DBTTask createTemporaryTask(@Nullable DBPProject project, @NotNull String title, boolean importTask) {
        if (project == null) {
            return null;
        }
        DBTTaskType taskType = project.getTaskManager().getRegistry().getTaskType(
            importTask ? DTConstants.TASK_IMPORT : DTConstants.TASK_EXPORT);
        if (taskType == null) {
            return null;
        }
        DBTTask task = project.getTaskManager().createTemporaryTask(taskType, title);
        // A temporary task has no state; the SWT wizard fills it before running, and the transfer nodes read it
        // (DatabaseTransferProducer → DBTaskUtils.initFromContext → task.getProperties())
        task.setProperties(new LinkedHashMap<>());
        return task;
    }

    private void reportTask(@Nullable String name) {
        if (CommonUtils.isEmpty(name)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (runningJob == null || !cancelButton.isEnabled()) {
                return;
            }
            progressLabel.setText(name);
            for (int i = 0; i < pipeRows.size(); i++) {
                PipeRow row = pipeRows.get(i);
                if (row.state == PipeState.RUNNING) {
                    row.message = name;
                    pipeTableModel.fireTableRowsUpdated(i, i);
                }
            }
        });
    }

    private void pipeStarted(@Nullable DataTransferPipe pipe) {
        int done = 0;
        for (int i = 0; i < pipeRows.size(); i++) {
            PipeRow row = pipeRows.get(i);
            if (row.state == PipeState.RUNNING) {
                completeRow(row, PipeState.SUCCESS);
            }
            if (pipe != null && row.pipe == pipe) {
                row.state = PipeState.RUNNING;
                row.message = "";
            }
            if (row.state == PipeState.SUCCESS) {
                done++;
            }
        }
        progressBar.setIndeterminate(false);
        progressBar.setValue(done);
        pipeTableModel.fireTableDataChanged();
    }

    private void completeRow(@NotNull PipeRow row, @NotNull PipeState state) {
        row.state = state;
        long rows = getRowCount(row.pipe);
        // No statistics after a successful transfer means no rows (an empty table)
        row.rows = rows >= 0 ? String.valueOf(rows) : state == PipeState.SUCCESS ? "0" : "";
        if (state == PipeState.SUCCESS) {
            row.message = "";
            try {
                String result = describeResult(row.pipe);
                if (result != null) {
                    row.target = result;
                }
            } catch (Exception e) {
                log.debug("Can't describe the transfer result", e);
            }
        }
    }

    /**
     * Rows transferred by a pipe: fetched by a database producer, or inserted by a database consumer; -1 if there
     * are no statistics.
     */
    private static long getRowCount(@NotNull DataTransferPipe pipe) {
        long rows = -1;
        IDataTransferProducer<?> producer = pipe.getProducer();
        if (producer != null) {
            DBCStatistics statistics = producer.getStatistics();
            if (statistics.getRowsFetched() > 0) {
                rows = statistics.getRowsFetched();
            }
        }
        IDataTransferConsumer<?, ?> consumer = pipe.getConsumer();
        if (consumer != null) {
            DBCStatistics statistics = consumer.getStatistics();
            if (statistics.getRowsUpdated() > 0) {
                rows = Math.max(rows, statistics.getRowsUpdated());
            }
        }
        return rows;
    }

    private void transferFinished(@Nullable Throwable error) {
        runningJob = null;
        finished = true;
        boolean canceled = error instanceof DBInterruptedException;
        boolean failed = false;
        for (PipeRow row : pipeRows) {
            switch (row.state) {
                case RUNNING -> {
                    if (error == null) {
                        completeRow(row, PipeState.SUCCESS);
                    } else {
                        completeRow(row, canceled ? PipeState.CANCELED : PipeState.ERROR);
                        row.message = canceled ? "" : ErrorText.summary(error);
                        failed = !canceled;
                    }
                }
                case WAITING -> {
                    if (error == null) {
                        completeRow(row, PipeState.SUCCESS);
                    } else if (canceled) {
                        row.state = PipeState.CANCELED;
                    } else if (!failed) {
                        // Failed before the job took a pipe (e.g. opening the target or creating tables)
                        row.state = PipeState.ERROR;
                        row.message = ErrorText.summary(error);
                        failed = true;
                    } else {
                        row.state = PipeState.NOT_RUN;
                    }
                }
                default -> {
                }
            }
        }
        pipeTableModel.fireTableDataChanged();
        progressBar.setIndeterminate(false);
        long succeeded = pipeRows.stream().filter(r -> r.state == PipeState.SUCCESS).count();
        progressBar.setValue((int) succeeded);
        if (error == null) {
            progressLabel.setText(NLS.bind(pipeRows.size() == 1 ? ToolsMessages.transfer_progress_success_one : ToolsMessages.transfer_progress_success,
                succeeded, pipeRows.size()));
            updateButtons();
            transferSucceeded();
        } else if (canceled) {
            progressLabel.setText(NLS.bind(ToolsMessages.transfer_progress_canceled, succeeded, pipeRows.size()));
        } else {
            progressLabel.setText(NLS.bind(ToolsMessages.transfer_progress_failed, succeeded, pipeRows.size()));
            String errorText = ErrorText.describe(error);
            errorArea.setText(errorText);
            errorArea.setCaretPosition(0);
            errorDetailsButton.setError(errorText, error);
            errorPanel.setVisible(true);
            errorPanel.getParent().revalidate();
            log.debug("Data transfer failed", error);
        }
        updateButtons();
    }

    //////////////////////////////////////////////////////////
    // Progress page

    /**
     * Last page (SSMS "Performing Operation"): one row per source object, overall progress and the error.
     */
    private final class ProgressPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 8));

        ProgressPage() {
            JTable table = new JTable(pipeTableModel);
            table.setFillsViewportHeight(true);
            table.setRowSelectionAllowed(false);
            table.getTableHeader().setReorderingAllowed(false);
            table.getColumnModel().getColumn(0).setPreferredWidth(180);
            table.getColumnModel().getColumn(1).setPreferredWidth(260);
            table.getColumnModel().getColumn(2).setMinWidth(80);
            table.getColumnModel().getColumn(2).setPreferredWidth(80);
            table.getColumnModel().getColumn(3).setMinWidth(60);
            table.getColumnModel().getColumn(3).setPreferredWidth(60);
            table.getColumnModel().getColumn(4).setPreferredWidth(200);
            DefaultTableCellRenderer rowsRenderer = new DefaultTableCellRenderer();
            rowsRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(3).setCellRenderer(rowsRenderer);
            table.setDefaultRenderer(Object.class, new StateRenderer());

            JPanel top = new JPanel(new BorderLayout(0, 6));
            top.add(progressLabel, BorderLayout.NORTH);
            top.add(progressBar, BorderLayout.CENTER);
            errorArea.setEditable(false);
            errorArea.setLineWrap(true);
            errorArea.setWrapStyleWord(true);
            errorArea.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.error.focusedBorderColor"); //$NON-NLS-1$
            // The stack trace stays behind Details: the visible text has no class names
            JPanel detailsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            detailsRow.add(errorDetailsButton);
            errorPanel.add(errorScroll, BorderLayout.CENTER);
            errorPanel.add(detailsRow, BorderLayout.SOUTH);
            errorPanel.setVisible(false);
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            panel.add(errorPanel, BorderLayout.SOUTH);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.transfer_page_progress_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return ToolsMessages.transfer_page_progress_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }
    }

    private final class PipeTableModel extends AbstractTableModel {
        private final String[] columns = {ToolsMessages.transfer_column_source, ToolsMessages.transfer_column_destination,
            ToolsMessages.transfer_column_status, ToolsMessages.transfer_column_rows, ToolsMessages.transfer_column_message};

        @Override
        public int getRowCount() {
            return pipeRows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PipeRow row = pipeRows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.source;
                case 1 -> row.target;
                case 2 -> row.state;
                case 3 -> row.rows;
                default -> row.message;
            };
        }
    }

    /**
     * Status in words, errors in the error color; long texts show in full as tool tips.
     */
    private static final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Object shown = value instanceof PipeState state ? state.label : value;
            super.getTableCellRendererComponent(table, shown, isSelected, hasFocus, row, column);
            setForeground(value == PipeState.ERROR ? UIManager.getColor("Component.error.focusedBorderColor") : table.getForeground()); //$NON-NLS-1$
            setToolTipText(shown == null || shown.toString().isEmpty() ? null : shown.toString());
            return this;
        }
    }
}
