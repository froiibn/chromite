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
import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import com.seizia.chromite.debug.DBGConstants;
import com.seizia.chromite.debug.DBGController;
import com.seizia.chromite.debug.DBGControllerFactory;
import com.seizia.chromite.debug.DBGEvent;
import com.seizia.chromite.debug.DBGEventHandler;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.debug.DBGSession;
import com.seizia.chromite.debug.DBGStackFrame;
import com.seizia.chromite.debug.DBGTargetResult;
import com.seizia.chromite.debug.DBGVariable;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DebugMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
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
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로시저 디버거 문서 탭: 루틴 원본과 중단점 여백, 단계 실행 도구 막대, 변수·호출 스택·메시지·결과 탭.
 *
 * 설계 (docs/chromite/procedure-debugger.md):
 * <ul>
 *     <li>모델은 DB 중립 디버그 모델({@code com.seizia.chromite.debug})만 쓴다. 루틴을 디버깅할 수 있는지와 컨트롤러는 루틴의 어댑터
 *     ({@link DBGControllerFactory})가 정한다(PostgreSQL: pldbgapi, Oracle: DBMS_DEBUG). SWT 버전의 Eclipse 디버그 모델·실행 구성·마커는 옮기지 않았다.</li>
 *     <li>SWT의 Debug 관점(여러 보기)을 SSMS 디버거처럼 한 문서 탭에 모았다: 위는 원본(읽기 전용 SQL 편집기, 여백을 눌러 중단점,
 *     현재 줄 화살표·바탕), 아래 탭은 Variables(이름·형식·값, 멈춘 동안 값 고치기), Call Stack(누르면 그 프레임의 원본·변수),
 *     Messages(서버 알림, 오류), Results(호출 결과). 다른 문서처럼 메인 창 탭이라 도구 창 배치를 바꾸지 않는다.</li>
 *     <li>중단점은 화면이 원하는 상태(루틴 원본 식별자 → 줄)로 들고 있다가 대상이 멈출 때마다 세션에 맞춘다({@link #syncBreakpoints}).
 *     실행 중에는 제어 연결이 단계 명령으로 바빠 바로 넣을 수 없고, attach 모드는 대상이 붙기 전에 넣을 수 없기 때문이다.
 *     시작 전에 놓을 수 있게 열 때 원본을 컨트롤러로 읽어 둔다.</li>
 *     <li>스레드: 모델 이벤트는 작업 스레드에서 오므로 EDT로 넘긴다. DB를 읽는 일(스택, 변수, 원본, 중단점 맞추기)은 작업에서 하고,
 *     그동안({@link #busy}) 단계 단추를 끈다. 시작·중지·트랜잭션 끝내기는 사용자가 기다려야 하므로 진행 대화상자({@link ProgressDialog}).</li>
 *     <li>트랜잭션 안전(사용자 규칙 2026-11-07): 디버그 세션이 끝나면(끝까지 실행, 중지, 탭 닫기, 앱 종료) 대상 세션의 트랜잭션 상태를
 *     서버에 묻고, 열려 있으면 커밋/롤백/취소를, 실패한 트랜잭션이면 롤백/취소를 묻는다({@link #finishSession}). 취소하면 세션과 연결을
 *     그대로 두고 상태 줄에 알린다. 조용히 커밋하거나 롤백하지 않는다.</li>
 *     <li>단축키는 Visual Studio·SSMS 디버거와 같다(F5, Shift+F5, F10, F11, Shift+F11). 이 탭 안에 초점이 있을 때만
 *     ({@code WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}) 메인 창 Query 메뉴의 F5(실행)보다 먼저 받는다. 프로시저 나가기(Shift+F11)는
 *     세션이 지원할 때({@link DBGSession#canStepReturn}, Oracle)만 켜진다.</li>
 *     <li>시작 대화상자의 attach 모드는 팩터리가 지원할 때({@link DBGControllerFactory#supportsAttach})만 고를 수 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 Oracle 디버거: attach 지원 여부를 시작 대화상자에 넘김, 프로시저 나가기 도움말을 세션에 따라 바꿈, 시작하는 동안 호출이
 *   끝난 경우(TERMINATE가 시작 중에 옴)를 끝내기로 처리, 권한 오류 안내
 */
final class ProcedureDebuggerPanel extends JPanel implements ToolDocument, DBGEventHandler {

    private static final Log log = Log.getLog(ProcedureDebuggerPanel.class);

    private enum State {
        /**
         * No session (not started, or ended).
         */
        IDLE,
        STARTING,
        /**
         * The target runs, or the session waits for another session's call (attach mode).
         */
        RUNNING,
        SUSPENDED,
        /**
         * The target ended; the session is being closed.
         */
        ENDING,
        /**
         * The target ended, but the user kept the debugged session's open transaction (Cancel).
         */
        TRANSACTION_PENDING
    }

    private final DBSProcedure routine;
    private final DBGControllerFactory factory;
    private final Object rootSourceId;
    private final List<DBSProcedureParameter> inputParameters;
    private final String routineName;
    /**
     * Sources by routine identifier: the debugged routine's (read when the tab opened) and those of called routines.
     */
    private final Map<Object, String> sources = new HashMap<>();
    /**
     * Breakpoints the user set: routine identifier → lines (as the debugger numbers them, from 1). EDT only.
     */
    private final Map<Object, TreeSet<Integer>> breakpoints = new HashMap<>();
    /**
     * Breakpoints put into the session, by {@link #breakpointKey}. Written by the breakpoint sync job.
     */
    private final Map<String, DBGBreakpointDescriptor> applied = new ConcurrentHashMap<>();

    @Nullable
    private DBGController controller;
    @Nullable
    private DBGSession session;
    private State state = State.IDLE;
    private boolean waitingForCall;
    private boolean busy;
    /**
     * The start dialog's last answers (the arguments are offered again).
     */
    @Nullable
    private Map<String, Object> lastConfiguration;

    private final SqlEditorPanel.SqlTextArea sourceArea = new SqlEditorPanel.SqlTextArea();
    private final Gutter gutter;
    private final List<GutterIconInfo> breakpointIcons = new ArrayList<>();
    @Nullable
    private GutterIconInfo currentLineIcon;
    @Nullable
    private Object currentLineHighlight;
    private Object shownSourceId;

    private List<? extends DBGStackFrame> frames = List.of();
    private List<? extends DBGVariable<?>> variables = List.of();
    @Nullable
    private DBGStackFrame shownFrame;
    private boolean updatingStack;

    private final VariablesModel variablesModel = new VariablesModel();
    private final StackModel stackModel = new StackModel();
    private final JTable stackTable = new JTable(stackModel);
    private final DefaultTableModel resultsModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTextArea messagesArea = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel statusLabel = new JLabel();

    private final Action continueAction;
    private final Action stopAction;
    private final Action stepOverAction;
    private final Action stepIntoAction;
    private final Action stepOutAction;

    /**
     * @param factory         debugger of the routine (its adapter)
     * @param rootSourceId    identifier of the routine's source ({@link DBGController#getSourceIdentifier})
     * @param source          the routine's source as the debugger numbers its lines
     * @param inputParameters the routine's input parameters, in order
     */
    ProcedureDebuggerPanel(
        @NotNull DBSProcedure routine,
        @NotNull DBGControllerFactory factory,
        @NotNull Object rootSourceId,
        @NotNull String source,
        @NotNull List<DBSProcedureParameter> inputParameters
    ) {
        super(new BorderLayout());
        this.routine = routine;
        this.factory = factory;
        this.rootSourceId = rootSourceId;
        this.inputParameters = inputParameters;
        this.routineName = DBUtils.getObjectFullName(routine, DBPEvaluationContext.UI);
        sources.put(rootSourceId, source);

        continueAction = createAction(DebugMessages.debug_continue, DebugMessages.debug_continue_tip, this::startOrContinue);
        stopAction = createAction(DebugMessages.debug_stop, DebugMessages.debug_stop_tip, this::stop);
        stepOverAction = createAction(DebugMessages.debug_step_over, DebugMessages.debug_step_over_tip, () -> step(DBGEvent.STEP_OVER));
        stepIntoAction = createAction(DebugMessages.debug_step_into, DebugMessages.debug_step_into_tip, () -> step(DBGEvent.STEP_INTO));
        stepOutAction = createAction(DebugMessages.debug_step_out, DebugMessages.debug_step_out_tip, () -> step(DBGEvent.STEP_RETURN));

        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createToolButton(continueAction, "run")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(stopAction, "stop")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createTextButton(stepOverAction));
        toolBar.add(createTextButton(stepIntoAction));
        toolBar.add(createTextButton(stepOutAction));
        add(toolBar, BorderLayout.NORTH);

        sourceArea.setDataSourceContainer(routine.getDataSource() == null ? null : routine.getDataSource().getContainer());
        sourceArea.setEditable(false);
        sourceArea.setHighlightCurrentLine(false);
        RTextScrollPane sourceScroll = new RTextScrollPane(sourceArea, true);
        sourceScroll.setIconRowHeaderEnabled(true);
        sourceScroll.setBorder(BorderFactory.createEmptyBorder());
        gutter = sourceScroll.getGutter();
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        sourceArea.applyTheme();
        installGutterClicks();

        JTable variablesTable = new JTable(variablesModel);
        variablesTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        stackTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stackTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingStack) {
                int row = stackTable.getSelectedRow();
                if (row >= 0 && row < frames.size()) {
                    showFrame(frames.get(row));
                }
            }
        });
        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        JTable resultsTable = new JTable(resultsModel);

        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        tabs.addTab(DebugMessages.debug_tab_variables, createScroll(variablesTable));
        tabs.addTab(DebugMessages.debug_tab_stack, createScroll(stackTable));
        tabs.addTab(DebugMessages.debug_tab_messages, createScroll(messagesArea));
        tabs.addTab(DebugMessages.debug_tab_results, createScroll(resultsTable));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sourceScroll, tabs);
        splitPane.setResizeWeight(0.6);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12)));
        add(statusLabel, BorderLayout.SOUTH);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "chromite.debug.continue", continueAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.SHIFT_DOWN_MASK), "chromite.debug.stop", stopAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), "chromite.debug.stepOver", stepOverAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "chromite.debug.stepInto", stepIntoAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F11, InputEvent.SHIFT_DOWN_MASK), "chromite.debug.stepOut", stepOutAction); //$NON-NLS-1$

        showSource(rootSourceId);
        updateState();
    }

    //////////////////////////////////////////////////////////
    // ToolDocument

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(DebugMessages.debug_title, routine.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(DebugMessages.debug_title, routineName);
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get(routine.getProcedureType() == DBSProcedureType.FUNCTION ? "function" : "procedure"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Ends the debug session first (asking about the debugged session's open transaction); false keeps the tab open.
     */
    @Override
    public boolean confirmClose() {
        return finishSession();
    }

    @Override
    public void dispose() {
        // confirmClose ended the session as the user said; a session left here was never asked about (should not happen)
        if (session != null) {
            log.warn("Debugger closed with an active session: " + routineName); //$NON-NLS-1$
        }
        if (controller != null) {
            controller.unregisterEventHandler(this);
            controller.dispose();
            controller = null;
        }
    }

    //////////////////////////////////////////////////////////
    // Commands

    private void startOrContinue() {
        if (busy) {
            return;
        }
        if (state == State.SUSPENDED && session != null) {
            runStep(session::execContinue);
        } else if (state == State.IDLE || state == State.TRANSACTION_PENDING) {
            start();
        }
    }

    private void start() {
        if (state == State.TRANSACTION_PENDING && !finishSession()) {
            return;
        }
        Map<String, Object> configuration = DebugStartDialog.show(this, routineName, inputParameters, lastConfiguration, factory.supportsAttach());
        if (configuration == null) {
            return;
        }
        lastConfiguration = configuration;
        messagesArea.setText(""); //$NON-NLS-1$
        resultsModel.setDataVector(new Object[0][], new Object[0]);
        frames = List.of();
        variables = List.of();
        stackModel.fireTableDataChanged();
        variablesModel.fireTableDataChanged();
        applied.clear();
        waitingForCall = DBGConstants.ATTACH_KIND_GLOBAL.equals(configuration.get(DBGConstants.ATTR_ATTACH_KIND));
        DBGController newController;
        try {
            newController = factory.createController(routine, configuration);
        } catch (DBGException e) {
            ErrorDialog.show(this, DebugMessages.debug_error_start, e.getMessage(), e);
            return;
        }
        controller = newController;
        newController.registerEventHandler(this);
        state = State.STARTING;
        updateState();
        try {
            DBGSession started = ProgressDialog.runTask(DebugMessages.debug_progress_start, newController::openSession);
            if (session == null) {
                session = started;
            }
            if (state == State.STARTING) {
                // Attach mode: the session waits for a call (its RESUME event may still be on its way)
                state = State.RUNNING;
                if (session != null && session.getTargetResult() != null) {
                    // The call ended before the debugger could stop in it (its TERMINATE came while starting)
                    state = State.ENDING;
                    showTargetEnd(session);
                    finishSession();
                    return;
                }
            }
            updateState();
        } catch (DBException e) {
            if (!(e instanceof DBInterruptedException)) {
                ErrorDialog.show(this, DebugMessages.debug_error_start, DebugCommands.describeError(e), e);
            }
            newController.unregisterEventHandler(this);
            newController.dispose();
            controller = null;
            session = null;
            state = State.IDLE;
            clearCurrentLine();
            updateState();
        }
    }

    private void step(int kind) {
        DBGSession current = session;
        if (state != State.SUSPENDED || busy || current == null) {
            return;
        }
        switch (kind) {
            case DBGEvent.STEP_OVER -> runStep(current::execStepOver);
            case DBGEvent.STEP_INTO -> runStep(current::execStepInto);
            case DBGEvent.STEP_RETURN -> {
                if (current.canStepReturn()) {
                    runStep(current::execStepReturn);
                }
            }
            default -> {
            }
        }
    }

    private interface StepCommand {
        void run() throws DBGException;
    }

    /**
     * Starts a step (the model runs it in a job); the target runs until its SUSPEND or TERMINATE event.
     */
    private void runStep(@NotNull StepCommand command) {
        state = State.RUNNING;
        waitingForCall = false;
        clearCurrentLine();
        updateState();
        try {
            command.run();
        } catch (DBGException e) {
            ErrorDialog.show(this, DebugMessages.debug_error_command, e.getMessage(), e);
            state = State.SUSPENDED;
            updateState();
        }
    }

    private void stop() {
        if (state == State.RUNNING || state == State.SUSPENDED || state == State.TRANSACTION_PENDING) {
            finishSession();
        }
    }

    /**
     * Ends the debug session: stops the target if it still runs, asks what to do with the debugged session's open
     * transaction (Commit / Roll back / Cancel), then closes the session's connections. EDT.
     *
     * @return true when no session is left; false when the user canceled (the session and its transaction stay)
     */
    private boolean finishSession() {
        DBGSession current = session;
        if (current == null) {
            return true;
        }
        try {
            if (state == State.RUNNING || state == State.SUSPENDED || state == State.STARTING) {
                state = State.ENDING;
                updateState();
                ProgressDialog.runTask(DebugMessages.debug_progress_stop, monitor -> {
                    current.abort(monitor);
                    return null;
                });
                showTargetEnd(current);
            }
            DBGSession.TransactionState transaction = ProgressDialog.runTask(DebugMessages.debug_progress_stop, current::getTransactionState);
            if (transaction != DBGSession.TransactionState.NONE && !endTransaction(current, transaction)) {
                state = State.TRANSACTION_PENDING;
                updateState();
                return false;
            }
        } catch (DBException e) {
            ErrorDialog.show(this, DebugMessages.debug_error_command, e.getMessage(), e);
            state = State.TRANSACTION_PENDING;
            updateState();
            return false;
        }
        try {
            ProgressDialog.runTask(DebugMessages.debug_progress_stop, monitor -> {
                current.closeSession(monitor);
                return null;
            });
        } catch (DBException e) {
            log.warn("Cannot close the debug session of " + routineName, e); //$NON-NLS-1$
        }
        if (controller != null) {
            controller.unregisterEventHandler(this);
            controller.dispose();
            controller = null;
        }
        session = null;
        state = State.IDLE;
        busy = false;
        clearCurrentLine();
        updateState();
        statusLabel.setText(DebugMessages.debug_status_ended);
        return true;
    }

    /**
     * Asks Commit / Roll back / Cancel (Roll back / Cancel for a failed transaction) and ends the transaction as answered.
     *
     * @return false when the user canceled
     */
    private boolean endTransaction(@NotNull DBGSession current, @NotNull DBGSession.TransactionState transaction) throws DBException {
        boolean failed = transaction == DBGSession.TransactionState.FAILED;
        String[] options = failed
            ? new String[]{UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel}
            : new String[]{UpdateMessages.exit_txn_commit, UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel};
        String message = NLS.bind(failed ? DebugMessages.debug_txn_failed : DebugMessages.debug_txn_open, routineName);
        int answer = JOptionPane.showOptionDialog(this, message, DebugMessages.debug_txn_title, JOptionPane.DEFAULT_OPTION,
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
            current.endTransaction(monitor, commit);
            return null;
        });
        return true;
    }

    //////////////////////////////////////////////////////////
    // Model events

    @Override
    public void handleDebugEvent(DBGEvent event) {
        SwingUtilities.invokeLater(() -> onDebugEvent(event));
    }

    private void onDebugEvent(@NotNull DBGEvent event) {
        if (session == null && state == State.STARTING && event.getSource() instanceof DBGSession eventSession) {
            // Local mode: the first stop comes while the start dialog still waits for openSession
            session = eventSession;
        }
        if (session == null || event.getSource() != session) {
            return;
        }
        switch (event.getKind()) {
            case DBGEvent.RESUME -> {
                if (state == State.STARTING || state == State.SUSPENDED || state == State.RUNNING) {
                    state = State.RUNNING;
                    waitingForCall = event.getDetails() == DBGEvent.MODEL_SPECIFIC;
                    updateState();
                }
            }
            case DBGEvent.SUSPEND -> {
                if (state == State.STARTING || state == State.RUNNING) {
                    state = State.SUSPENDED;
                    waitingForCall = false;
                    refreshSuspended();
                }
            }
            case DBGEvent.TERMINATE -> {
                if (state == State.RUNNING || state == State.SUSPENDED) {
                    // The target has ended by itself: nothing to stop, only its transaction to ask about
                    state = State.ENDING;
                    showTargetEnd(session);
                    finishSession();
                }
            }
            default -> {
            }
        }
    }

    /**
     * The target stopped: puts the breakpoints in, reads the stack, the top frame's variables and source, and the new
     * messages (in a job), then shows them.
     */
    private void refreshSuspended() {
        DBGSession current = session;
        DBGController currentController = controller;
        if (current == null || currentController == null) {
            return;
        }
        Map<Object, TreeSet<Integer>> wanted = copyBreakpoints();
        busy = true;
        updateState();
        new AbstractJob("Read debugger state") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> errors = syncBreakpoints(monitor, current, currentController, wanted);
                List<? extends DBGStackFrame> newFrames = List.of();
                List<? extends DBGVariable<?>> newVariables = List.of();
                String topSource = null;
                try {
                    newFrames = current.getStack();
                    newVariables = current.getVariables(null);
                    if (!newFrames.isEmpty() && !sources.containsKey(newFrames.getFirst().getSourceIdentifier())) {
                        topSource = current.getSource(newFrames.getFirst());
                    }
                } catch (DBGException e) {
                    errors.add(e.getMessage() + (e.getCause() != null ? ": " + e.getCause().getMessage() : "")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                List<String> messages = current.readMessages();
                List<? extends DBGStackFrame> stack = newFrames;
                List<? extends DBGVariable<?>> vars = newVariables;
                String source = topSource;
                SwingUtilities.invokeLater(() -> {
                    if (session != current) {
                        return;
                    }
                    busy = false;
                    appendMessages(messages);
                    appendMessages(errors);
                    if (!stack.isEmpty() && source != null) {
                        sources.put(stack.getFirst().getSourceIdentifier(), source);
                    }
                    frames = stack;
                    variables = vars;
                    updatingStack = true;
                    stackModel.fireTableDataChanged();
                    if (!stack.isEmpty()) {
                        stackTable.setRowSelectionInterval(0, 0);
                    }
                    updatingStack = false;
                    variablesModel.fireTableDataChanged();
                    shownFrame = stack.isEmpty() ? null : stack.getFirst();
                    showCurrentFrame();
                    updateState();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * A frame chosen in Call Stack: its variables (the server's debugger focus moves to it) and its source.
     */
    private void showFrame(@NotNull DBGStackFrame frame) {
        DBGSession current = session;
        if (current == null || state != State.SUSPENDED || busy || frame == shownFrame) {
            return;
        }
        busy = true;
        updateState();
        new AbstractJob("Read debugger frame") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<? extends DBGVariable<?>> newVariables = List.of();
                String source = null;
                String error = null;
                try {
                    newVariables = current.getVariables(frame);
                    if (!sources.containsKey(frame.getSourceIdentifier())) {
                        source = current.getSource(frame);
                    }
                } catch (DBGException e) {
                    error = e.getMessage();
                }
                List<? extends DBGVariable<?>> vars = newVariables;
                String frameSource = source;
                String frameError = error;
                SwingUtilities.invokeLater(() -> {
                    if (session != current) {
                        return;
                    }
                    busy = false;
                    if (frameError != null) {
                        appendMessages(List.of(frameError));
                    }
                    if (frameSource != null) {
                        sources.put(frame.getSourceIdentifier(), frameSource);
                    }
                    variables = vars;
                    variablesModel.fireTableDataChanged();
                    shownFrame = frame;
                    showCurrentFrame();
                    updateState();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Makes the session's breakpoints those the user set (adds the new ones, removes the cleared ones). Runs while the
     * target is suspended (the controller connection is free).
     *
     * @return error messages of breakpoints that could not be set or removed
     */
    @NotNull
    private List<String> syncBreakpoints(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBGSession current,
        @NotNull DBGController currentController,
        @NotNull Map<Object, TreeSet<Integer>> wanted
    ) {
        List<String> errors = new ArrayList<>();
        Set<String> wantedKeys = new HashSet<>();
        for (Map.Entry<Object, TreeSet<Integer>> entry : wanted.entrySet()) {
            for (Integer line : entry.getValue()) {
                String key = breakpointKey(entry.getKey(), line);
                wantedKeys.add(key);
                if (!applied.containsKey(key)) {
                    DBGBreakpointDescriptor descriptor = currentController.describeBreakpoint(entry.getKey(), line);
                    try {
                        current.addBreakpoint(monitor, descriptor);
                        applied.put(key, descriptor);
                    } catch (DBGException e) {
                        errors.add(e.getMessage() + (e.getCause() != null ? ": " + e.getCause().getMessage() : "")); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        }
        for (Map.Entry<String, DBGBreakpointDescriptor> entry : new ArrayList<>(applied.entrySet())) {
            if (!wantedKeys.contains(entry.getKey())) {
                try {
                    current.removeBreakpoint(monitor, entry.getValue());
                    applied.remove(entry.getKey());
                } catch (DBGException e) {
                    errors.add(e.getMessage() + (e.getCause() != null ? ": " + e.getCause().getMessage() : "")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return errors;
    }

    @NotNull
    private static String breakpointKey(@NotNull Object sourceId, int line) {
        return sourceId + ":" + line; //$NON-NLS-1$
    }

    @NotNull
    private Map<Object, TreeSet<Integer>> copyBreakpoints() {
        Map<Object, TreeSet<Integer>> copy = new HashMap<>();
        breakpoints.forEach((id, lines) -> copy.put(id, new TreeSet<>(lines)));
        return copy;
    }

    /**
     * The call ended: its messages, then its result or error in Messages and Results.
     */
    private void showTargetEnd(@NotNull DBGSession current) {
        appendMessages(current.readMessages());
        DBGTargetResult result = current.getTargetResult();
        if (result == null) {
            return;
        }
        if (result.canceled()) {
            appendMessages(List.of(DebugMessages.debug_result_canceled));
        } else if (result.errorMessage() != null) {
            appendMessages(List.of(NLS.bind(DebugMessages.debug_result_error, result.errorMessage())));
        } else if (result.columns().isEmpty()) {
            appendMessages(List.of(DebugMessages.debug_result_none));
        } else {
            Object[][] rows = new Object[result.rows().size()][];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = result.rows().get(i).toArray();
            }
            resultsModel.setDataVector(rows, result.columns().toArray());
            appendMessages(List.of(NLS.bind(DebugMessages.debug_result_rows, result.rows().size())));
            tabs.setSelectedIndex(tabs.getTabCount() - 1);
            return;
        }
        tabs.setSelectedIndex(2);
    }

    private void appendMessages(@NotNull List<String> messages) {
        for (String message : messages) {
            messagesArea.append(message);
            messagesArea.append("\n"); //$NON-NLS-1$
        }
    }

    //////////////////////////////////////////////////////////
    // Source, breakpoints and current line

    private void showCurrentFrame() {
        DBGStackFrame frame = shownFrame;
        if (frame == null) {
            clearCurrentLine();
            return;
        }
        showSource(frame.getSourceIdentifier());
        markCurrentLine(frame.getLineNumber());
        updateState();
    }

    /**
     * Shows a routine's source (if it is not shown already) with its breakpoints.
     */
    private void showSource(@NotNull Object sourceId) {
        if (sourceId.equals(shownSourceId)) {
            return;
        }
        clearCurrentLine();
        shownSourceId = sourceId;
        sourceArea.setText(CommonUtils.notEmpty(sources.get(sourceId)));
        sourceArea.setCaretPosition(0);
        sourceArea.discardAllEdits();
        paintBreakpoints();
    }

    private void paintBreakpoints() {
        for (GutterIconInfo icon : breakpointIcons) {
            gutter.removeTrackingIcon(icon);
        }
        breakpointIcons.clear();
        for (Integer line : breakpoints.getOrDefault(shownSourceId, new TreeSet<>())) {
            if (line - 1 < sourceArea.getLineCount()) {
                try {
                    breakpointIcons.add(gutter.addLineTrackingIcon(line - 1, BreakpointIcon.INSTANCE, DebugMessages.debug_breakpoint_tip));
                } catch (BadLocationException e) {
                    log.debug("Breakpoint line out of the source: " + line); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * @param line line as the debugger numbers it (from 1)
     */
    private void markCurrentLine(int line) {
        clearCurrentLine();
        if (line < 1 || line > sourceArea.getLineCount()) {
            return;
        }
        try {
            currentLineIcon = gutter.addLineTrackingIcon(line - 1, CurrentLineIcon.INSTANCE, DebugMessages.debug_current_line_tip);
            currentLineHighlight = sourceArea.addLineHighlight(line - 1, getCurrentLineColor());
            sourceArea.setCaretPosition(sourceArea.getLineStartOffset(line - 1));
        } catch (BadLocationException e) {
            log.debug("Current line out of the source: " + line); //$NON-NLS-1$
        }
    }

    private void clearCurrentLine() {
        if (currentLineIcon != null) {
            gutter.removeTrackingIcon(currentLineIcon);
            currentLineIcon = null;
        }
        if (currentLineHighlight != null) {
            sourceArea.removeLineHighlight(currentLineHighlight);
            currentLineHighlight = null;
        }
    }

    @NotNull
    private static Color getCurrentLineColor() {
        Color yellow = UIManager.getColor("Actions.Yellow"); //$NON-NLS-1$
        Color base = yellow != null ? yellow : new Color(0xE6A700);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 70);
    }

    /**
     * A click in the gutter (line numbers or icon row) toggles the breakpoint of that line.
     */
    private void installGutterClicks() {
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Point point = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), sourceArea);
                int offset = sourceArea.viewToModel2D(new Point(0, point.y));
                try {
                    toggleBreakpoint(sourceArea.getLineOfOffset(offset) + 1);
                } catch (BadLocationException ex) {
                    log.debug("No line at the gutter click"); //$NON-NLS-1$
                }
            }
        };
        for (Component child : gutter.getComponents()) {
            child.addMouseListener(listener);
        }
    }

    private void toggleBreakpoint(int line) {
        if (shownSourceId == null || CommonUtils.isEmptyTrimmed(getLineText(line))) {
            return;
        }
        TreeSet<Integer> lines = breakpoints.computeIfAbsent(shownSourceId, id -> new TreeSet<>());
        if (!lines.remove(line)) {
            lines.add(line);
        }
        paintBreakpoints();
        if (state == State.SUSPENDED && !busy) {
            // Put it into the session now; while the target runs it goes in at the next stop
            DBGSession current = session;
            DBGController currentController = controller;
            if (current != null && currentController != null) {
                Map<Object, TreeSet<Integer>> wanted = copyBreakpoints();
                busy = true;
                updateState();
                new AbstractJob("Set breakpoints") { //$NON-NLS-1$
                    @NotNull
                    @Override
                    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                        List<String> errors = syncBreakpoints(monitor, current, currentController, wanted);
                        SwingUtilities.invokeLater(() -> {
                            busy = false;
                            appendMessages(errors);
                            updateState();
                        });
                        return Status.OK_STATUS;
                    }
                }.schedule();
            }
        }
    }

    @NotNull
    private String getLineText(int line) {
        try {
            int start = sourceArea.getLineStartOffset(line - 1);
            int end = sourceArea.getLineEndOffset(line - 1);
            return sourceArea.getText(start, end - start);
        } catch (BadLocationException e) {
            return ""; //$NON-NLS-1$
        }
    }

    //////////////////////////////////////////////////////////
    // State

    private void updateState() {
        boolean suspended = state == State.SUSPENDED && !busy;
        DBGSession current = session;
        continueAction.setEnabled(!busy && (state == State.IDLE || state == State.TRANSACTION_PENDING || suspended));
        stopAction.setEnabled(state == State.RUNNING || state == State.SUSPENDED || state == State.TRANSACTION_PENDING);
        stepOverAction.setEnabled(suspended && current != null && current.canStepOver());
        stepIntoAction.setEnabled(suspended && current != null && current.canStepInto());
        stepOutAction.setEnabled(suspended && current != null && current.canStepReturn());
        stepOutAction.putValue(Action.SHORT_DESCRIPTION, current != null && !current.canStepReturn()
            ? DebugMessages.debug_step_out_unsupported_tip : DebugMessages.debug_step_out_tip);
        statusLabel.setText(switch (state) {
            case IDLE -> statusLabel.getText().equals(DebugMessages.debug_status_ended) ? DebugMessages.debug_status_ended : DebugMessages.debug_status_idle;
            case STARTING -> DebugMessages.debug_status_starting;
            case RUNNING -> waitingForCall ? NLS.bind(DebugMessages.debug_status_waiting, routineName) : DebugMessages.debug_status_running;
            case SUSPENDED -> shownFrame == null ? DebugMessages.debug_status_running
                : NLS.bind(DebugMessages.debug_status_suspended, shownFrame.getLineNumber(), shownFrame.getName());
            case ENDING -> DebugMessages.debug_status_ended;
            case TRANSACTION_PENDING -> DebugMessages.debug_status_txn_open;
        });
    }

    //////////////////////////////////////////////////////////
    // Helpers

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
    private static JButton createTextButton(@NotNull Action action) {
        JButton button = new JButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Action action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionKey);
        getActionMap().put(actionKey, action);
    }

    @NotNull
    private static JScrollPane createScroll(@NotNull JComponent component) {
        JScrollPane scrollPane = new JScrollPane(component);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    /**
     * Variables of the shown frame: name (arguments marked), type, value. The value can be changed while the target is
     * suspended; the server checks it ({@code pldbg_deposit_value}, {@code DBMS_DEBUG.SET_VALUE}).
     */
    private final class VariablesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return variables.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> DebugMessages.debug_col_name;
                case 1 -> DebugMessages.debug_col_type;
                default -> DebugMessages.debug_col_value;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBGVariable<?> variable = variables.get(row);
            return switch (column) {
                case 0 -> variable.isArgument() ? NLS.bind(DebugMessages.debug_argument, variable.getName()) : variable.getName();
                case 1 -> variable.getTypeName();
                default -> variable.getVal();
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 2 && state == State.SUSPENDED && !busy;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            DBGSession current = session;
            DBGStackFrame frame = shownFrame;
            if (current == null || row >= variables.size() || Objects.equals(value, getValueAt(row, column))) {
                return;
            }
            DBGVariable<?> variable = variables.get(row);
            busy = true;
            updateState();
            new AbstractJob("Set debugger variable") { //$NON-NLS-1$
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    String error = null;
                    List<? extends DBGVariable<?>> newVariables = null;
                    try {
                        current.setVariableVal(variable, CommonUtils.toString(value));
                        newVariables = current.getVariables(frame);
                    } catch (DBGException e) {
                        error = NLS.bind(DebugMessages.debug_value_error, variable.getName()) + ": " + e.getMessage() //$NON-NLS-1$
                            + (e.getCause() != null ? " (" + e.getCause().getMessage() + ")" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                    String valueError = error;
                    List<? extends DBGVariable<?>> vars = newVariables;
                    SwingUtilities.invokeLater(() -> {
                        busy = false;
                        if (valueError != null) {
                            appendMessages(List.of(valueError));
                            tabs.setSelectedIndex(2);
                        }
                        if (vars != null && session == current) {
                            variables = vars;
                        }
                        fireTableDataChanged();
                        updateState();
                    });
                    return Status.OK_STATUS;
                }
            }.schedule();
        }
    }

    /**
     * Call stack, innermost frame first.
     */
    private final class StackModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return frames.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? DebugMessages.debug_col_function : DebugMessages.debug_col_line;
        }

        @Override
        public Object getValueAt(int row, int column) {
            DBGStackFrame frame = frames.get(row);
            return column == 0 ? frame.getName() : frame.getLineNumber();
        }
    }

    /**
     * Red dot of a breakpoint in the gutter.
     */
    private static final class BreakpointIcon implements Icon {
        static final BreakpointIcon INSTANCE = new BreakpointIcon();

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color red = UIManager.getColor("Actions.Red"); //$NON-NLS-1$
                g2.setColor(red != null ? red : new Color(0xDB5860));
                int size = getIconWidth() - UIScale.scale(2);
                g2.fillOval(x + UIScale.scale(1), y + UIScale.scale(1), size, size);
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

    /**
     * Arrow of the line where the target is stopped.
     */
    private static final class CurrentLineIcon implements Icon {
        static final CurrentLineIcon INSTANCE = new CurrentLineIcon();

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color yellow = UIManager.getColor("Actions.Yellow"); //$NON-NLS-1$
                g2.setColor(yellow != null ? yellow : new Color(0xE6A700));
                int w = getIconWidth();
                int h = getIconHeight();
                Path2D arrow = new Path2D.Float();
                arrow.moveTo(x, y + h * 0.3);
                arrow.lineTo(x + w * 0.5, y + h * 0.3);
                arrow.lineTo(x + w * 0.5, y);
                arrow.lineTo(x + w, y + h * 0.5);
                arrow.lineTo(x + w * 0.5, y + h);
                arrow.lineTo(x + w * 0.5, y + h * 0.7);
                arrow.lineTo(x, y + h * 0.7);
                arrow.closePath();
                g2.fill(arrow);
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
}
