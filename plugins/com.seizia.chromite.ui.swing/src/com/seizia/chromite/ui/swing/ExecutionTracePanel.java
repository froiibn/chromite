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
import com.seizia.chromite.ext.mssql.model.SQLServerProcedure;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace.Module;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace.ModuleKey;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace.StatementKind;
import com.seizia.chromite.model.admin.mssql.SQLServerExecutionTrace.TracedStatement;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.TraceMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * SQL Server 실행 추적 문서 탭: 왼쪽은 모듈 원본(실행된 줄 바탕, 고른 문장의 줄), 오른쪽은 타임라인·줄별·메시지·결과 탭.
 *
 * 설계 (docs/chromite/sqlserver-execution-trace.md):
 * <ul>
 *     <li>모델은 {@link SQLServerExecutionTrace}(model.admin)다. 실행은 진행 대화상자에서 한다: 호출이 끝날 때까지 기다려야 결과를
 *     보일 수 있고, 모달이라 실행 중에 탭 닫기·앱 끝내기가 끼어들지 않아 XE 세션 정리(모델의 finally)가 늘 끝난다. 취소하면 실행 중인
 *     문장이 취소되고 그때까지의 기록을 보인다.</li>
 *     <li>디버거 탭({@link ProcedureDebuggerPanel})처럼 원본은 읽기 전용 {@code SqlTextArea}다. XE의 줄 번호는 모듈 정의
 *     ({@code sys.sql_modules.definition}) 기준이라 원본도 그 정의를 보인다(Modify 창의 글이 아니라). 실행된 줄에는 옅은 바탕과 여백
 *     표시(툴팁: 횟수·시간·행·읽기), 타임라인이나 줄별 표에서 고른 문장의 줄에는 디버거의 현재 줄과 같은 노란 바탕을 둔다. 다른
 *     모듈(호출된 프로시저·함수·트리거)의 문장을 고르면 그 모듈의 원본으로 바뀐다. 모듈 목록에서 직접 고를 수도 있다.</li>
 *     <li>타임라인은 문장이 끝난 순서(XE 버퍼 순서)이고 모듈 이름을 중첩 수준만큼 들여 쓴다. 마지막 열은 호출 전체에서 그 문장이
 *     차지한 구간 막대다. 줄별 표는 같은 모듈·줄(동적 SQL은 같은 글)을 모아 합계 시간이 큰 순서로 보인다(어느 줄이 느린지).</li>
 *     <li>트랜잭션 안전(사용자 규칙 2026-11-07): 호출이 끝나면 실행 세션의 트랜잭션을 서버에 묻고, 열려 있으면 커밋/롤백/취소를,
 *     커밋할 수 없는 트랜잭션이면 롤백/취소를 묻는다(취소가 기본). 취소하면 세션을 두고 상태 줄에 알리며, 트랜잭션 끝내기·다시 실행·
 *     탭 닫기·앱 끝내기({@link #confirmClose})에서 다시 묻는다. 상태를 읽을 수 없으면(연결이 끊김) 세션을 닫을지 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sqlserver-execution-trace.md)
 */
final class ExecutionTracePanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(ExecutionTracePanel.class);

    private static final int TIMELINE_COLUMN = 10;
    private static final int MESSAGES_TAB = 2;

    private final SQLServerProcedure routine;
    private final SQLServerExecutionTrace.Target target;
    private final String routineName;
    /**
     * Modules by key: the traced procedure (read when the tab opened) and those the last run executed.
     */
    private final Map<ModuleKey, Module> modules = new LinkedHashMap<>();

    /**
     * The session of the last run while its transaction is not ended (the user canceled the question).
     */
    @Nullable
    private SQLServerExecutionTrace pendingTrace;
    @Nullable
    private List<String> lastValues;
    private List<TracedStatement> statements = List.of();
    private List<LineSummary> lines = List.of();
    private long callMicros;
    /**
     * Status line text of the last run (shown again once its transaction is ended).
     */
    private String resultStatus = TraceMessages.trace_status_idle;

    private final SqlEditorPanel.SqlTextArea sourceArea = new SqlEditorPanel.SqlTextArea();
    private final Gutter gutter;
    private final List<Object> executedHighlights = new ArrayList<>();
    private final List<GutterIconInfo> executedIcons = new ArrayList<>();
    @Nullable
    private Object selectedHighlight;
    @Nullable
    private ModuleKey shownModule;
    private final DefaultComboBoxModel<Module> moduleListModel = new DefaultComboBoxModel<>();
    private final JComboBox<Module> moduleCombo = new JComboBox<>(moduleListModel);
    private boolean updatingModules;

    private final TimelineModel timelineModel = new TimelineModel();
    private final JTable timelineTable = new JTable(timelineModel);
    private final LinesModel linesModel = new LinesModel();
    private final JTable linesTable = new JTable(linesModel);
    private final DefaultTableModel resultsModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTextArea messagesArea = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel statusLabel = new JLabel(TraceMessages.trace_status_idle);

    private final Action runAction;
    private final Action endTransactionAction;

    ExecutionTracePanel(@NotNull SQLServerProcedure routine, @NotNull SQLServerExecutionTrace.Target target) {
        super(new BorderLayout());
        this.routine = routine;
        this.target = target;
        this.routineName = DBUtils.getObjectFullName(routine, DBPEvaluationContext.UI);
        modules.put(target.routine().key(), target.routine());

        runAction = createAction(TraceMessages.trace_run, TraceMessages.trace_run_tip, this::run);
        endTransactionAction = createAction(TraceMessages.trace_end_txn, TraceMessages.trace_end_txn_tip, this::finishTransaction);

        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createToolButton(runAction, "run")); //$NON-NLS-1$
        toolBar.addSeparator();
        JButton endButton = new JButton(endTransactionAction);
        endButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        endButton.setFocusable(false);
        toolBar.add(endButton);
        add(toolBar, BorderLayout.NORTH);

        sourceArea.setDataSourceContainer(routine.getDataSource().getContainer());
        sourceArea.setEditable(false);
        sourceArea.setHighlightCurrentLine(false);
        RTextScrollPane sourceScroll = new RTextScrollPane(sourceArea, true);
        sourceScroll.setIconRowHeaderEnabled(true);
        sourceScroll.setBorder(BorderFactory.createEmptyBorder());
        gutter = sourceScroll.getGutter();
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        sourceArea.applyTheme();

        moduleCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof Module module ? module.name() : value, index, isSelected,
                    cellHasFocus);
            }
        });
        moduleCombo.addActionListener(e -> {
            if (!updatingModules && moduleCombo.getSelectedItem() instanceof Module module) {
                showModule(module.key(), 0);
            }
        });
        JPanel moduleBar = new JPanel(new BorderLayout(UIScale.scale(6), 0));
        moduleBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        moduleBar.add(new JLabel(TraceMessages.trace_module), BorderLayout.WEST);
        moduleBar.add(moduleCombo, BorderLayout.CENTER);
        JPanel sourcePanel = new JPanel(new BorderLayout());
        sourcePanel.add(moduleBar, BorderLayout.NORTH);
        sourcePanel.add(sourceScroll, BorderLayout.CENTER);

        configureTimelineTable();
        configureLinesTable();
        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        JTable resultsTable = new JTable(resultsModel);

        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        tabs.addTab(TraceMessages.trace_tab_timeline, createScroll(timelineTable));
        tabs.addTab(TraceMessages.trace_tab_lines, createScroll(linesTable));
        tabs.addTab(TraceMessages.trace_tab_messages, createScroll(messagesArea));
        tabs.addTab(TraceMessages.trace_tab_results, createScroll(resultsTable));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, tabs);
        splitPane.setResizeWeight(0.45);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12)));
        add(statusLabel, BorderLayout.SOUTH);

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "chromite.trace.run"); //$NON-NLS-1$
        getActionMap().put("chromite.trace.run", runAction); //$NON-NLS-1$

        refreshModuleList();
        showModule(target.routine().key(), 0);
        updateState();
    }

    //////////////////////////////////////////////////////////
    // ToolDocument

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(TraceMessages.trace_title, routine.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(TraceMessages.trace_title, routineName);
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("procedure"); //$NON-NLS-1$
    }

    /**
     * Ends the traced session first (asking about its open transaction); false keeps the tab open.
     */
    @Override
    public boolean confirmClose() {
        return finishTransaction();
    }

    @Override
    public void dispose() {
        // confirmClose ended the session as the user said; a session left here was never asked about (should not happen)
        if (pendingTrace != null) {
            log.warn("Execution trace closed with an open session: " + routineName); //$NON-NLS-1$
            pendingTrace.close();
            pendingTrace = null;
        }
    }

    //////////////////////////////////////////////////////////
    // Run

    /**
     * Asks for the arguments, runs the procedure with the trace and shows what it recorded. EDT.
     */
    void run() {
        if (!finishTransaction()) {
            return;
        }
        List<String> values = ExecutionTraceStartDialog.show(this, routineName, target.parameters(), lastValues);
        if (values == null) {
            return;
        }
        lastValues = values;
        SQLServerExecutionTrace trace = new SQLServerExecutionTrace(routine);
        SQLServerExecutionTrace.Result result;
        try {
            result = ProgressDialog.runTask(TraceMessages.trace_progress_run, monitor -> trace.run(monitor, target.parameters(), values));
        } catch (DBException e) {
            if (!(e instanceof DBInterruptedException)) {
                ErrorDialog.show(this, NLS.bind(TraceMessages.trace_error_run, routineName), ExecutionTraceCommands.describeError(e), e);
            }
            if (trace.isOpen()) {
                // The call ran but its trace could not be read: its transaction is still the user's to end
                pendingTrace = trace;
                finishTransaction();
            }
            return;
        }
        pendingTrace = trace;
        showResult(result);
        finishTransaction();
    }

    /**
     * Ends the traced session: asks what to do with its open transaction (Commit / Roll back / Cancel), then closes it.
     *
     * @return true when no session is left; false when the user canceled (the session and its transaction stay)
     */
    private boolean finishTransaction() {
        SQLServerExecutionTrace trace = pendingTrace;
        if (trace == null) {
            return true;
        }
        try {
            SQLServerExecutionTrace.TransactionState transaction = ProgressDialog.runTask(TraceMessages.trace_progress_end,
                trace::getTransactionState);
            if (transaction != SQLServerExecutionTrace.TransactionState.NONE && !endTransaction(trace, transaction)) {
                updateState();
                return false;
            }
        } catch (DBInterruptedException e) {
            updateState();
            return false;
        } catch (DBException e) {
            // The session cannot be asked (its connection broke): closing it is the user's decision
            if (!SwingPlatformUI.confirmChange(this, NLS.bind(TraceMessages.trace_txn_unknown, routineName, e.getMessage()),
                TraceMessages.trace_txn_title, TraceMessages.trace_txn_close, JOptionPane.WARNING_MESSAGE)) {
                updateState();
                return false;
            }
        }
        trace.close();
        pendingTrace = null;
        updateState();
        return true;
    }

    /**
     * Asks Commit / Roll back / Cancel (Roll back / Cancel for a failed transaction) and ends the transaction as answered.
     *
     * @return false when the user canceled
     */
    private boolean endTransaction(@NotNull SQLServerExecutionTrace trace, @NotNull SQLServerExecutionTrace.TransactionState transaction)
        throws DBException {
        boolean failed = transaction == SQLServerExecutionTrace.TransactionState.FAILED;
        String[] options = failed
            ? new String[]{UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel}
            : new String[]{UpdateMessages.exit_txn_commit, UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel};
        String message = NLS.bind(failed ? TraceMessages.trace_txn_failed : TraceMessages.trace_txn_open, routineName);
        int answer = JOptionPane.showOptionDialog(this, message, TraceMessages.trace_txn_title, JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE, null, options, options[options.length - 1]);
        boolean commit;
        if (failed) {
            if (answer != 0) {
                return false;
            }
            commit = false;
        } else {
            if (answer != 0 && answer != 1) {
                return false;
            }
            commit = answer == 0;
        }
        ProgressDialog.runTask(UpdateMessages.exit_txn_progress, monitor -> {
            trace.endTransaction(monitor, commit);
            return null;
        });
        return true;
    }

    //////////////////////////////////////////////////////////
    // Result

    private void showResult(@NotNull SQLServerExecutionTrace.Result result) {
        statements = result.statements();
        modules.putAll(result.modules());
        callMicros = 0;
        for (TracedStatement statement : statements) {
            callMicros = Math.max(callMicros, statement.kind() == StatementKind.CALL ? statement.durationMicros() : statement.endMicros());
        }
        lines = summarizeLines(statements);
        timelineModel.fireTableDataChanged();
        linesModel.fireTableDataChanged();
        refreshModuleList();

        List<String> messages = new ArrayList<>(result.messages());
        for (int i = 0; i < result.resultSetRows().size(); i++) {
            messages.add(NLS.bind(TraceMessages.trace_msg_result_set, i + 1, result.resultSetRows().get(i)));
        }
        if (result.canceled()) {
            messages.add(TraceMessages.trace_msg_canceled);
        } else if (result.error() != null) {
            messages.add(NLS.bind(TraceMessages.trace_msg_error, result.error()));
        }
        if (result.droppedEvents() > 0) {
            messages.add(NLS.bind(TraceMessages.trace_msg_dropped, new Object[]{result.droppedEvents(), result.totalEvents(),
                SQLServerExecutionTrace.MAX_EVENTS}));
        }
        if (!result.complete()) {
            messages.add(TraceMessages.trace_msg_incomplete);
        }
        if (statements.isEmpty()) {
            messages.add(TraceMessages.trace_msg_none);
        }
        messagesArea.setText(String.join("\n", messages)); //$NON-NLS-1$
        messagesArea.setCaretPosition(0);

        List<String> columns = new ArrayList<>(result.outputColumns());
        if (!columns.isEmpty()) {
            // The call batch returns the return value first
            columns.set(0, TraceMessages.trace_return_value);
        }
        resultsModel.setDataVector(columns.isEmpty() ? new Object[0][] : new Object[][]{result.outputValues().toArray()}, columns.toArray());

        // Shown again even if it is the shown module: its executed lines are new
        shownModule = null;
        showModule(target.routine().key(), 0);
        if (result.canceled()) {
            tabs.setSelectedIndex(MESSAGES_TAB);
            resultStatus = NLS.bind(TraceMessages.trace_status_canceled, statements.size());
        } else if (result.error() != null) {
            tabs.setSelectedIndex(MESSAGES_TAB);
            resultStatus = NLS.bind(TraceMessages.trace_status_failed, statements.size());
        } else {
            tabs.setSelectedIndex(statements.isEmpty() ? MESSAGES_TAB : 0);
            resultStatus = NLS.bind(TraceMessages.trace_status_done, statements.size(), formatMillis(callMicros));
        }
        statusLabel.setText(resultStatus);
    }

    private record LineSummary(
        @NotNull TracedStatement first,
        int executions,
        long totalMicros,
        long maxMicros,
        long rows,
        long logicalReads
    ) {
    }

    /**
     * Statements of the same module line (or the same dynamic SQL text and line), slowest first. The call itself is not a line.
     */
    @NotNull
    private static List<LineSummary> summarizeLines(@NotNull List<TracedStatement> statements) {
        Map<String, List<TracedStatement>> groups = new LinkedHashMap<>();
        for (TracedStatement statement : statements) {
            if (statement.kind() == StatementKind.CALL) {
                continue;
            }
            String key = statement.kind() == StatementKind.MODULE
                ? statement.module() + ":" + statement.line() //$NON-NLS-1$
                : "dynamic:" + statement.line() + ":" + statement.text(); //$NON-NLS-1$ //$NON-NLS-2$
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(statement);
        }
        List<LineSummary> result = new ArrayList<>();
        for (List<TracedStatement> group : groups.values()) {
            long total = 0;
            long max = 0;
            long rows = 0;
            long reads = 0;
            for (TracedStatement statement : group) {
                total += statement.durationMicros();
                max = Math.max(max, statement.durationMicros());
                rows += statement.rows();
                reads += statement.logicalReads();
            }
            result.add(new LineSummary(group.getFirst(), group.size(), total, max, rows, reads));
        }
        result.sort(Comparator.comparingLong(LineSummary::totalMicros).reversed());
        return result;
    }

    //////////////////////////////////////////////////////////
    // Source and line highlighting

    private void refreshModuleList() {
        updatingModules = true;
        try {
            moduleListModel.removeAllElements();
            for (Module module : modules.values()) {
                moduleListModel.addElement(module);
            }
            if (shownModule != null) {
                moduleListModel.setSelectedItem(modules.get(shownModule));
            }
        } finally {
            updatingModules = false;
        }
    }

    /**
     * A statement chosen in Timeline or By Line: its module's source with its line marked.
     */
    private void showStatement(@NotNull TracedStatement statement) {
        switch (statement.kind()) {
            case MODULE -> showModule(Objects.requireNonNull(statement.module()), statement.line());
            case CALL -> showModule(target.routine().key(), 0);
            case DYNAMIC -> clearSelectedLine();
        }
    }

    /**
     * Shows a module's source with its executed lines, and marks a line.
     *
     * @param line line to mark (from 1), or 0
     */
    private void showModule(@NotNull ModuleKey key, int line) {
        Module module = modules.get(key);
        if (module == null) {
            return;
        }
        if (!key.equals(shownModule)) {
            shownModule = key;
            clearExecutedLines();
            clearSelectedLine();
            sourceArea.setText(module.source() != null ? module.source() : NLS.bind(TraceMessages.trace_source_unavailable, module.name()));
            sourceArea.setCaretPosition(0);
            sourceArea.discardAllEdits();
            paintExecutedLines(key);
            updatingModules = true;
            moduleListModel.setSelectedItem(module);
            updatingModules = false;
        }
        clearSelectedLine();
        if (line >= 1 && line <= sourceArea.getLineCount()) {
            try {
                selectedHighlight = sourceArea.addLineHighlight(line - 1, getSelectedLineColor());
                sourceArea.setCaretPosition(sourceArea.getLineStartOffset(line - 1));
            } catch (BadLocationException e) {
                log.debug("Traced line out of the source: " + line); //$NON-NLS-1$
            }
        }
    }

    private void paintExecutedLines(@NotNull ModuleKey key) {
        Color color = getExecutedLineColor();
        for (LineSummary summary : lines) {
            TracedStatement first = summary.first();
            int line = first.line();
            if (first.kind() != StatementKind.MODULE || !key.equals(first.module()) || line < 1 || line > sourceArea.getLineCount()) {
                continue;
            }
            try {
                executedHighlights.add(sourceArea.addLineHighlight(line - 1, color));
                executedIcons.add(gutter.addLineTrackingIcon(line - 1, ExecutedLineIcon.INSTANCE, NLS.bind(TraceMessages.trace_line_tip,
                    new Object[]{line, summary.executions(), formatMillis(summary.totalMicros()), summary.rows(), summary.logicalReads()})));
            } catch (BadLocationException e) {
                log.debug("Traced line out of the source: " + line); //$NON-NLS-1$
            }
        }
    }

    private void clearExecutedLines() {
        for (Object highlight : executedHighlights) {
            sourceArea.removeLineHighlight(highlight);
        }
        executedHighlights.clear();
        for (GutterIconInfo icon : executedIcons) {
            gutter.removeTrackingIcon(icon);
        }
        executedIcons.clear();
    }

    private void clearSelectedLine() {
        if (selectedHighlight != null) {
            sourceArea.removeLineHighlight(selectedHighlight);
            selectedHighlight = null;
        }
    }

    @NotNull
    private static Color getSelectedLineColor() {
        Color yellow = UIManager.getColor("Actions.Yellow"); //$NON-NLS-1$
        Color base = yellow != null ? yellow : new Color(0xE6A700);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 90);
    }

    @NotNull
    private static Color getExecutedLineColor() {
        Color green = UIManager.getColor("Actions.Green"); //$NON-NLS-1$
        Color base = green != null ? green : new Color(0x59A869);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 35);
    }

    //////////////////////////////////////////////////////////
    // Tables

    private void configureTimelineTable() {
        TableRowSorter<TimelineModel> sorter = new TableRowSorter<>(timelineModel);
        // The bar column sorts by start time
        sorter.setComparator(TIMELINE_COLUMN, Comparator.comparingLong(TracedStatement::startMicros));
        timelineTable.setRowSorter(sorter);
        timelineTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        timelineTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        timelineTable.setDefaultRenderer(Double.class, new MillisRenderer());
        timelineTable.getColumnModel().getColumn(TIMELINE_COLUMN).setCellRenderer(new TimelineRenderer());
        timelineTable.getColumnModel().getColumn(3).setCellRenderer(new StatementRenderer(row -> statements.get(row).text()));
        int[] widths = {40, 180, 50, 320, 90, 70, 60, 90, 90, 60, 160};
        for (int i = 0; i < widths.length; i++) {
            timelineTable.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(widths[i]));
        }
        timelineTable.getSelectionModel().addListSelectionListener(e -> {
            int row = timelineTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                showStatement(statements.get(timelineTable.convertRowIndexToModel(row)));
            }
        });
    }

    private void configureLinesTable() {
        linesTable.setAutoCreateRowSorter(true);
        linesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        linesTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        linesTable.setDefaultRenderer(Double.class, new MillisRenderer());
        linesTable.getColumnModel().getColumn(7).setCellRenderer(new StatementRenderer(row -> lines.get(row).first().text()));
        int[] widths = {180, 50, 80, 90, 90, 70, 90, 320};
        for (int i = 0; i < widths.length; i++) {
            linesTable.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(widths[i]));
        }
        linesTable.getSelectionModel().addListSelectionListener(e -> {
            int row = linesTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                showStatement(lines.get(linesTable.convertRowIndexToModel(row)).first());
            }
        });
    }

    @NotNull
    private String getModuleLabel(@NotNull TracedStatement statement) {
        return switch (statement.kind()) {
            case CALL -> TraceMessages.trace_call;
            case DYNAMIC -> TraceMessages.trace_dynamic;
            case MODULE -> {
                Module module = modules.get(statement.module());
                if (module == null) {
                    yield String.valueOf(statement.module());
                }
                // Modules of the procedure's own database without the database name (the column is narrow)
                String prefix = target.routine().name().substring(0, target.routine().name().indexOf('.') + 1);
                yield module.key().databaseId() == target.routine().key().databaseId() && module.name().startsWith(prefix)
                    ? module.name().substring(prefix.length()) : module.name();
            }
        };
    }

    /**
     * One line of a statement's text (the full text is the cell's tool tip).
     */
    @NotNull
    private static String toOneLine(@NotNull String text) {
        return text.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static double toMillis(long micros) {
        return micros / 1000.0;
    }

    @NotNull
    private static String formatMillis(long micros) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMinimumFractionDigits(3);
        format.setMaximumFractionDigits(3);
        return format.format(toMillis(micros));
    }

    /**
     * Completed statements in completion order.
     */
    private final class TimelineModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return statements.size();
        }

        @Override
        public int getColumnCount() {
            return 11;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> TraceMessages.trace_col_sequence;
                case 1 -> TraceMessages.trace_col_module;
                case 2 -> TraceMessages.trace_col_line;
                case 3 -> TraceMessages.trace_col_statement;
                case 4 -> TraceMessages.trace_col_duration;
                case 5 -> TraceMessages.trace_col_cpu;
                case 6 -> TraceMessages.trace_col_rows;
                case 7 -> TraceMessages.trace_col_logical_reads;
                case 8 -> TraceMessages.trace_col_physical_reads;
                case 9 -> TraceMessages.trace_col_writes;
                default -> TraceMessages.trace_col_timeline;
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0, 2 -> Integer.class;
                case 1, 3 -> String.class;
                case 4, 5 -> Double.class;
                case 6, 7, 8, 9 -> Long.class;
                default -> TracedStatement.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            TracedStatement statement = statements.get(row);
            return switch (column) {
                case 0 -> statement.sequence();
                // Nested statements are indented by their nesting level
                case 1 -> "  ".repeat(Math.max(0, statement.nestLevel() - 1)) + getModuleLabel(statement); //$NON-NLS-1$
                // The call's line is a line of the generated call batch: not shown
                case 2 -> statement.kind() == StatementKind.CALL ? null : statement.line();
                case 3 -> toOneLine(statement.text());
                case 4 -> toMillis(statement.durationMicros());
                case 5 -> toMillis(statement.cpuMicros());
                case 6 -> statement.rows();
                case 7 -> statement.logicalReads();
                case 8 -> statement.physicalReads();
                case 9 -> statement.writes();
                default -> statement;
            };
        }
    }

    /**
     * Statements grouped by line.
     */
    private final class LinesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return 8;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> TraceMessages.trace_col_module;
                case 1 -> TraceMessages.trace_col_line;
                case 2 -> TraceMessages.trace_col_executions;
                case 3 -> TraceMessages.trace_col_total;
                case 4 -> TraceMessages.trace_col_max;
                case 5 -> TraceMessages.trace_col_rows;
                case 6 -> TraceMessages.trace_col_logical_reads;
                default -> TraceMessages.trace_col_statement;
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 1, 2 -> Integer.class;
                case 3, 4 -> Double.class;
                case 5, 6 -> Long.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            LineSummary summary = lines.get(row);
            return switch (column) {
                case 0 -> getModuleLabel(summary.first());
                case 1 -> summary.first().line();
                case 2 -> summary.executions();
                case 3 -> toMillis(summary.totalMicros());
                case 4 -> toMillis(summary.maxMicros());
                case 5 -> summary.rows();
                case 6 -> summary.logicalReads();
                default -> toOneLine(summary.first().text());
            };
        }
    }

    /**
     * Milliseconds with three decimals (XE measures microseconds).
     */
    private static final class MillisRenderer extends DefaultTableCellRenderer {
        MillisRenderer() {
            setHorizontalAlignment(RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Double millis ? formatMillis(Math.round(millis * 1000)) : CommonUtils.toString(value));
        }
    }

    /**
     * A statement on one line; its whole text is the tool tip.
     */
    private static final class StatementRenderer extends DefaultTableCellRenderer {
        private final IntFunction<String> fullText;

        StatementRenderer(@NotNull IntFunction<String> fullText) {
            this.fullText = fullText;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String text = fullText.apply(table.convertRowIndexToModel(row));
            setToolTipText(text.isEmpty() ? null : "<html><pre>" + escapeHtml(text) + "</pre></html>"); //$NON-NLS-1$ //$NON-NLS-2$
            return this;
        }

        @NotNull
        private static String escapeHtml(@NotNull String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
    }

    /**
     * The part of the call a statement took, as a bar from its start to its end.
     */
    private final class TimelineRenderer extends DefaultTableCellRenderer {
        private TracedStatement statement;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column); //$NON-NLS-1$
            statement = value instanceof TracedStatement traced ? traced : null;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (statement == null || callMicros <= 0) {
                return;
            }
            int width = getWidth() - UIScale.scale(4);
            int x = UIScale.scale(2) + (int) (width * Math.min(1.0, (double) statement.startMicros() / callMicros));
            int end = UIScale.scale(2) + (int) (width * Math.min(1.0, (double) statement.endMicros() / callMicros));
            Color blue = UIManager.getColor("Actions.Blue"); //$NON-NLS-1$
            g.setColor(blue != null ? blue : new Color(0x3592C4));
            g.fillRect(x, UIScale.scale(4), Math.max(UIScale.scale(2), end - x), getHeight() - UIScale.scale(8));
        }
    }

    /**
     * Mark of an executed line in the gutter (its tool tip tells how often and how long).
     */
    private static final class ExecutedLineIcon implements Icon {
        static final ExecutedLineIcon INSTANCE = new ExecutedLineIcon();

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Color green = UIManager.getColor("Actions.Green"); //$NON-NLS-1$
                g2.setColor(green != null ? green : new Color(0x59A869));
                g2.fillRect(x + getIconWidth() / 3, y + UIScale.scale(1), getIconWidth() / 3, getIconHeight() - UIScale.scale(2));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return UIScale.scale(12);
        }

        @Override
        public int getIconHeight() {
            return UIScale.scale(12);
        }
    }

    //////////////////////////////////////////////////////////
    // State

    private void updateState() {
        runAction.setEnabled(true);
        endTransactionAction.setEnabled(pendingTrace != null);
        if (pendingTrace != null) {
            statusLabel.setText(TraceMessages.trace_status_txn_open);
        } else {
            statusLabel.setText(resultStatus);
        }
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String toolTip, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, toolTip);
        return action;
    }

    @NotNull
    private static JScrollPane createScroll(@NotNull JComponent component) {
        JScrollPane scrollPane = new JScrollPane(component);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }
}
