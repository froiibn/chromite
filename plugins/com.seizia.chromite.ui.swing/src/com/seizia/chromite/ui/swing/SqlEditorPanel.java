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

import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteJob;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPErrorAssistant;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.impl.struct.ContextDefaultObjectsReader;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryListener;
import org.jkiss.dbeaver.model.sql.SQLQueryResult;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 편집기 탭(SSMS의 쿼리 창 하나): 편집기, 실행/취소, 결과({@link QueryResultsView}), 상태 줄.
 *
 * 설계:
 * <ul>
 *     <li>편집기는 RSyntaxTextArea(SQL 구문 강조)다. 색은 테마마다 XML 테마 파일({@code SqlEditorLight.xml},
 *     {@code SqlEditorDark.xml})로 정하고, Look and Feel이 바뀌면({@code updateUI}) 현재 테마의 파일을 다시 적용한다.</li>
 *     <li>F5(메인 창 Query 메뉴)와 Execute 단추는 선택이 1자 이상이면 선택한 부분만, 아니면 전체를 실행한다
 *     (swing-frontend.md "쿼리 실행 사양"). 범위는 전체 텍스트 안의 위치로 넘겨 쿼리 위치가 편집기 위치와 같게 한다(오류 줄 번호).</li>
 *     <li>실행은 두 Job으로 나눈다. 준비 Job이 연결(필요하면 {@link SwingConnectionService}), 실행 문맥, 문장 나누기
 *     ({@link SQLScriptBatches}: SQL Server 등은 {@code GO} 배치 단위)를 하고, 실행 엔진 {@link SQLQueryExecuteJob}을 예약한다.
 *     엔진을 직접 실행하지 않고 예약하는 것은 {@code DataSourceJob}이 실행 중 연결을 잡아 두는 동작({@code acquire/release})을 그대로 쓰기 위해서다.
 *     EDT는 기다리지 않는다. Cancel은 지금 돌고 있는 Job을 취소한다(엔진 Job은 실행 중인 문장을 취소한다).</li>
 *     <li>엔진의 콜백({@link SQLQueryExecuteHandler}, {@link SQLQueryListener})은 Job 스레드에서 불리므로, 화면 변경은 모두
 *     {@link SwingUtilities#invokeLater}로 EDT에 넘긴다. 각 실행은 {@link Execution} 객체로 구분해, 끝난 실행의 늦은 결과가 다음 실행 화면에 섞이지 않게 한다.</li>
 *     <li>오류는 대화상자 대신 Messages 탭에 쓰고(오류 위치 = 편집기의 줄·열), 엔진의 "계속할까요" 질문
 *     ({@link SQLQueryExecuteHandler#handleExecutionError})에는 묻지 않고 멈춤으로 답한다. 오류가 나면 SSMS처럼 Messages 탭을 보여 준다.</li>
 *     <li>커밋 방식, 오류 처리 방식, 최대 행 수는 엔진 기본값(SWT 편집기 설정 기본값, 연결의 최대 행 수 설정)을 쓴다.
 *     "Select Top N Rows"로 연 편집기는 N행까지 가져오도록 최대 행 수를 키운다({@link #setMaxRows}).
 *     실행 문맥은 연결의 기본(주) 문맥이다(편집기별 별도 연결은 아직 없음).</li>
 *     <li>연결: 편집기는 연결 없이 열 수 있다(파일 열기). 도구 막대의 연결 콤보(SSMS의 연결 바꾸기)로 연결을 고르거나 바꾸고,
 *     실행 중에는 바꿀 수 없다. 목록은 콤보를 열 때 활성 프로젝트의 연결로 다시 채운다(추가·삭제·이름 변경 반영).</li>
 *     <li>기본 DB/스키마 콤보(SSMS의 "Available Databases"): SWT의 활성 스키마 선택({@code SelectActiveSchemaHandler})과 같은 모델 API를 쓴다.
 *     목록과 현재 값은 {@link ContextDefaultObjectsReader}, 바꾸기는 {@link DBExecUtils#setExecutionContextDefaults}다(둘 다 작업 스레드).
 *     실행 문맥이 연결의 기본 문맥이라 같은 연결의 다른 편집기에도 적용된다. 연결되어 있지 않거나 문맥이 바꾸기를 지원하지 않으면 끈다.</li>
 *     <li>파일: 편집기는 텍스트만 다루고, 파일의 문자 집합·BOM·줄바꿈은 {@link SqlFileContent}로 기억해 저장할 때 되살린다.
 *     파일 대화상자와 최근 파일 목록은 메인 창이 맡는다. 문서가 바뀌면 고침 표시를 켜고 탭 제목 끝에 {@code *}를 붙인다.</li>
 *     <li>자동완성: {@link SqlCompletionPopup}(Ctrl+Space, {@code .}·글자 입력 뒤 자동)이 편집기의 현재 연결로 제안을 계산한다.</li>
 *     <li>찾기·바꾸기: 편집기가 Ctrl+F, Ctrl+H, F3, Shift+F3을 직접 받아 {@link FindReplaceDialog}를 연다(Edit 메뉴와 같은 동작).</li>
 *     <li>상태 줄({@link QueryStatusLine}): 편집기마다 하나이고 메인 창이 결과 아래에 보인다. 실행 상태, 연결의 서버·사용자, 기본 DB/스키마 콤보의 값, 시간, 행 수를 넣는다.</li>
 *     <li>결과 편집: 결과 표에 저장하지 않은 변경이 있으면 다시 실행하기 전에 버릴지 묻고(실행하면 결과 표를 새로 만든다), 저장 중에는 실행하지 않는다.
 *     닫을 때·끝낼 때 묻기는 메인 창이 {@link #hasPendingResultChanges}, {@link #saveResultChanges}로 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 E단계: 연결 없는 편집기, 연결·기본 스키마 콤보, 파일 내용과 고침 표시, 편집기별 최대 행 수
 *   (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: 자동완성 팝업 연결 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 결과 표 편집 상태(다시 실행 전 확인, 저장 중 실행 막기, 닫을 때 저장) (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: 도구 막대 아이콘(초록 ▶ Execute, ■ 중지), 문서 탭 아이콘, 편집기 글꼴 JetBrains Mono(합성 글꼴)
 *   (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 편집기의 찾기 키(Ctrl+F, Ctrl+H, F3, Shift+F3) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 상태 줄을 {@link QueryStatusLine}(SSMS 내용: 상태 아이콘, 서버, 사용자, DB/스키마, 시간, 행)으로 바꾸고 메인 창이 결과 아래에 둠
 *   (docs/chromite/swing-frontend.md "P1 디자인 적용")
 */
final class SqlEditorPanel extends JPanel {

    private static final Log log = Log.getLog(SqlEditorPanel.class);

    private static final DateTimeFormatter COMPLETION_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * The main window side of an editor.
     */
    interface Host {
        /**
         * The results of the editor changed; show them (if it is the active editor) on the Results or Messages tab.
         */
        void showResults(@NotNull SqlEditorPanel editor, boolean messages);

        /**
         * Execution started or finished (Query menu state).
         */
        void executionStateChanged(@NotNull SqlEditorPanel editor);

        /**
         * The tab title or tool tip changed (file name, connection, modified marker).
         */
        void titleChanged(@NotNull SqlEditorPanel editor);
    }

    private static final String NOT_AVAILABLE = "< N/A >"; //$NON-NLS-1$

    private final Host host;
    // EDT only
    @Nullable
    private DBPDataSourceContainer dataSourceContainer;
    private String fileName;
    @Nullable
    private Path file;
    /**
     * Format (charset, BOM, line separator) of the file; its text is not kept up to date.
     */
    private SqlFileContent fileFormat;
    private boolean dirty;
    private long maxRows;
    private final JComboBox<DBPDataSourceContainer> connectionCombo = new JComboBox<>();
    private final JComboBox<Object> schemaCombo = new JComboBox<>();
    private boolean updatingCombos;
    private boolean schemaChangeSupported;
    private int schemaSerial;
    @Nullable
    private SchemaState schemaState;
    private final SqlTextArea textArea;
    private final SqlCompletionPopup completion;
    private final QueryResultsView resultsView = new QueryResultsView(new QueryResultsView.Listener() {
        @Override
        public void showMessages() {
            host.showResults(SqlEditorPanel.this, true);
        }

        @Override
        public void resultsEditStateChanged() {
            updateActions();
        }
    });
    private final Action executeAction;
    private final Action cancelAction;
    private final QueryStatusLine statusLine = new QueryStatusLine();
    private final Timer elapsedTimer;
    private final Object jobLock = new Object();
    // EDT only
    @Nullable
    private Execution execution;

    /**
     * @param dataSourceContainer connection of the query window, or null to choose it later in the tool bar
     * @param fileName            name of the new query ({@code SQLQueryN.sql}) until it is saved to a file
     */
    SqlEditorPanel(@Nullable DBPDataSourceContainer dataSourceContainer, @NotNull String fileName, @NotNull Host host) {
        super(new BorderLayout());
        this.dataSourceContainer = dataSourceContainer;
        this.fileName = fileName;
        this.fileFormat = SqlFileContent.newFile("");
        this.host = host;

        executeAction = new AbstractAction("Execute") {
            @Override
            public void actionPerformed(ActionEvent e) {
                execute();
            }
        };
        executeAction.putValue(Action.SHORT_DESCRIPTION, "Execute the selection, or the whole script without a selection (F5)");
        cancelAction = new AbstractAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        };
        cancelAction.putValue(Action.SHORT_DESCRIPTION, "Cancel the executing query");
        cancelAction.setEnabled(false);

        textArea = new SqlTextArea();
        completion = new SqlCompletionPopup(textArea, () -> dataSourceContainer);
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();

        add(createToolBar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        elapsedTimer = new Timer(250, e -> {
            if (execution != null) {
                statusLine.setElapsed(formatElapsed(System.currentTimeMillis() - execution.startTime, false));
            }
        });
        statusLine.setConnection(dataSourceContainer);
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setDirty(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setDirty(true);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute changes do not modify the text
            }
        });
        reloadConnectionItems();
        refreshSchemas();
    }

    /**
     * SSMS-like tab title: {@code SQLQuery1.sql - connection*} ({@code *} while modified).
     */
    @NotNull
    String getTitle() {
        return fileName + " - " + (dataSourceContainer != null ? dataSourceContainer.getName() : "not connected") + (dirty ? "*" : "");
    }

    /**
     * Tab tool tip: the file path, or the name of a query that was never saved.
     */
    @NotNull
    String getToolTip() {
        return file != null ? file.toString() : fileName;
    }

    @Nullable
    DBPDataSourceContainer getDataSourceContainer() {
        return dataSourceContainer;
    }

    @Nullable
    Path getFile() {
        return file;
    }

    /**
     * File name for Save As: the file's name, or {@code SQLQueryN.sql} for a query that was never saved.
     */
    @NotNull
    String getFileName() {
        return fileName;
    }

    boolean isDirty() {
        return dirty;
    }

    /**
     * The editor text in the format of its file (a new query: UTF-8, platform line separator).
     */
    @NotNull
    SqlFileContent getContent() {
        return fileFormat.withText(textArea.getText());
    }

    /**
     * Replaces the text without marking the editor modified: a file that was opened, or a generated script.
     *
     * @param file the file the content was read from, or null for a script that is not a file yet
     */
    void setContent(@NotNull SqlFileContent content, @Nullable Path file) {
        textArea.setText(content.text());
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        fileFormat = content;
        if (file != null) {
            this.file = file;
            this.fileName = file.getFileName().toString();
        }
        dirty = false;
        host.titleChanged(this);
    }

    /**
     * The text was written to a file (Save, Save As).
     */
    void saved(@NotNull Path file, @NotNull SqlFileContent content) {
        this.file = file;
        this.fileName = file.getFileName().toString();
        this.fileFormat = content;
        dirty = false;
        host.titleChanged(this);
    }

    private void setDirty(boolean dirty) {
        if (this.dirty != dirty) {
            this.dirty = dirty;
            host.titleChanged(this);
        }
    }

    /**
     * Row limit of this query window ("Select Top N Rows"): it fetches at least this many rows, more if the
     * connection's maximum is higher. 0 uses the connection's maximum.
     */
    void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    /**
     * The connection was changed elsewhere (renamed, connected, disconnected): title and default schema.
     */
    void connectionChanged() {
        host.titleChanged(this);
        connectionCombo.repaint();
        statusLine.setConnection(dataSourceContainer);
        refreshSchemas();
    }

    @NotNull
    QueryResultsView getResultsView() {
        return resultsView;
    }

    /**
     * The query status line; the main window shows the active editor's line below the results (SSMS).
     */
    @NotNull
    QueryStatusLine getStatusLine() {
        return statusLine;
    }

    boolean isExecuting() {
        return execution != null;
    }

    /**
     * Unsaved changes in the result grids (closing the window asks to save them).
     */
    boolean hasPendingResultChanges() {
        return resultsView.getChangeCount() > 0;
    }

    int getPendingResultChangeCount() {
        return resultsView.getChangeCount();
    }

    boolean isSavingResults() {
        return resultsView.isSaving();
    }

    /**
     * Saves the result grids' changes, waiting in a progress dialog.
     *
     * @return false if a save failed (the error is in the Messages tab)
     */
    boolean saveResultChanges() {
        return resultsView.saveChanges();
    }

    void focusEditor() {
        textArea.requestFocusInWindow();
    }

    /**
     * The editor text area, for Edit &gt; Find / Replace.
     */
    @NotNull
    SqlTextArea getTextArea() {
        return textArea;
    }

    /**
     * Query > Complete (Ctrl+Space): code completion proposals at the caret.
     */
    void complete() {
        textArea.requestFocusInWindow();
        completion.complete();
    }

    /**
     * The tab is closing: stop a running query.
     */
    void dispose() {
        cancel();
        completion.cancel();
        elapsedTimer.stop();
    }

    /**
     * Document tab icon: an SQL file icon for a query saved to a file, otherwise the logo of the connection's driver
     * (DataGrip console tabs).
     */
    @NotNull
    Icon getTabIcon() {
        if (file == null && dataSourceContainer != null) {
            return NodeIcons.getIcon(dataSourceContainer.getDriver().getIcon());
        }
        return UiIcons.get("file-sql"); //$NON-NLS-1$
    }

    /**
     * SSMS query tool bar in DataGrip style: [▶ Execute] (green icon and text) [■] (icon only) | [connection ▾] [database/schema ▾].
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(UiIcons.createToolButton(executeAction, "run")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(cancelAction, "stop")); //$NON-NLS-1$
        toolBar.addSeparator();

        connectionCombo.setToolTipText("Connection of this query window");
        connectionCombo.setRenderer(new ConnectionRenderer());
        setFixedWidth(connectionCombo, 260);
        connectionCombo.addActionListener(e -> {
            if (!updatingCombos && connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer selected && selected != dataSourceContainer) {
                dataSourceContainer = selected;
                statusLine.setState(QueryStatusLine.State.READY, "Ready");
                statusLine.setConnection(selected);
                host.titleChanged(this);
                refreshSchemas();
            }
        });
        connectionCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Connections may have been added, removed or renamed since
                reloadConnectionItems();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        toolBar.add(connectionCombo);
        toolBar.addSeparator();

        schemaCombo.setToolTipText("Default database/schema of the connection (shared by its query windows)");
        schemaCombo.setRenderer(new SchemaRenderer());
        setFixedWidth(schemaCombo, 200);
        schemaCombo.addActionListener(e -> {
            if (!updatingCombos && schemaCombo.getSelectedItem() instanceof DBSObject selected) {
                changeDefaultObject(selected);
            }
        });
        toolBar.add(schemaCombo);
        // size.toolbar.editor 36 with size.control 28 combos
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        return toolBar;
    }

    private static void setFixedWidth(@NotNull JComponent component, int width) {
        Dimension size = new Dimension(width, component.getPreferredSize().height);
        component.setPreferredSize(size);
        component.setMaximumSize(size);
    }

    //////////////////////////////////////////////////////////
    // Execution (EDT)

    /**
     * Executes the selection if at least one character is selected, otherwise the whole editor text.
     */
    void execute() {
        if (execution != null) {
            return;
        }
        if (resultsView.isSaving()) {
            statusLine.setState(QueryStatusLine.State.READY, "Saving result changes…");
            return;
        }
        String text = textArea.getText();
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        int offset = 0;
        int length = text.length();
        if (selectionEnd - selectionStart >= 1) {
            offset = selectionStart;
            length = selectionEnd - selectionStart;
        }
        if (text.substring(offset, offset + length).isBlank()) {
            statusLine.setState(QueryStatusLine.State.READY, "Nothing to execute");
            return;
        }
        DBPDataSourceContainer container = dataSourceContainer;
        if (container == null) {
            JOptionPane.showMessageDialog(this, "Choose a connection for this query window in its tool bar first.",
                "Execute", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int pendingChanges = resultsView.getChangeCount();
        if (pendingChanges > 0) {
            String[] options = {"Discard and Execute", "Cancel"};
            if (JOptionPane.showOptionDialog(this,
                "The results have " + pendingChanges + (pendingChanges == 1 ? " unsaved change" : " unsaved changes")
                    + ".\nExecuting the query replaces them. Discard the changes?",
                "Execute", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
                return;
            }
        }

        long connectionMaxRows = container.getPreferenceStore().getInt(ModelPreferences.RESULT_SET_MAX_ROWS);
        Execution run = new Execution(container, text, offset, length,
            maxRows > 0 ? Math.max(maxRows, connectionMaxRows) : connectionMaxRows, maxRows > 0);
        execution = run;
        resultsView.clear();
        statusLine.setState(QueryStatusLine.State.EXECUTING, container.isConnected() ? "Executing query…" : "Connecting…");
        statusLine.setElapsed(formatElapsed(0, false));
        statusLine.setRows(null);
        elapsedTimer.start();
        updateActions();
        host.showResults(this, false);

        AbstractJob prepareJob = new AbstractJob("Prepare query") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                prepareAndSchedule(run, monitor);
                return Status.OK_STATUS;
            }
        };
        synchronized (jobLock) {
            run.job = prepareJob;
        }
        prepareJob.schedule();
    }

    /**
     * Cancels the running job: the preparation (connecting) or the query execution.
     */
    void cancel() {
        Execution run = execution;
        if (run == null) {
            return;
        }
        synchronized (jobLock) {
            run.cancelRequested = true;
            if (run.job != null) {
                run.job.cancel();
            }
        }
        statusLine.setState(QueryStatusLine.State.EXECUTING, "Canceling…");
        cancelAction.setEnabled(false);
    }

    private void updateActions() {
        executeAction.setEnabled(execution == null && !resultsView.isSaving());
        cancelAction.setEnabled(execution != null && !execution.cancelRequested);
        // The connection and its defaults stay as they are while a query runs on them
        connectionCombo.setEnabled(execution == null);
        schemaCombo.setEnabled(execution == null && schemaChangeSupported);
        host.executionStateChanged(this);
    }

    /**
     * Called in the EDT when the run is over (the job finished, or preparation stopped early).
     */
    private void finishExecution(@NotNull Execution run, @NotNull IStatus status) {
        if (execution != run) {
            return;
        }
        execution = null;
        elapsedTimer.stop();
        long elapsed = System.currentTimeMillis() - run.startTime;

        if (status.getSeverity() == IStatus.ERROR && !run.hasErrors) {
            // Errors outside of a query (e.g. opening the session); query errors were already written by the listener
            run.hasErrors = true;
            resultsView.appendError(CommonUtils.notEmpty(status.getMessage()));
        }
        boolean canceled = run.cancelRequested || status.getSeverity() == IStatus.CANCEL;
        if (canceled) {
            resultsView.appendMessage("Query was canceled by user.");
        }
        resultsView.appendMessage("");
        resultsView.appendMessage("Completion time: " + OffsetDateTime.now().format(COMPLETION_TIME_FORMAT));

        if (run.hasErrors) {
            statusLine.setState(QueryStatusLine.State.ERROR, "Query completed with errors.");
        } else if (canceled) {
            statusLine.setState(QueryStatusLine.State.CANCELED, "Query was canceled.");
        } else {
            statusLine.setState(QueryStatusLine.State.SUCCESS, "Query executed successfully.");
        }
        // The run may have connected: server version and the connected icon
        statusLine.setConnection(run.dataSourceContainer);
        statusLine.setElapsed(formatElapsed(elapsed, true));
        long rows = run.rowsFetched.get();
        statusLine.setRows(rows + (rows == 1 ? " row" : " rows"));
        updateActions();
        // As in SSMS: errors (or no result grids at all) bring the Messages tab to the front
        host.showResults(this, run.hasErrors || resultsView.getGridCount() == 0);
        // The run may have connected, or changed the default database (USE)
        refreshSchemas();
    }

    //////////////////////////////////////////////////////////
    // Execution (job threads)

    private void prepareAndSchedule(@NotNull Execution run, @NotNull DBRProgressMonitor monitor) {
        DBPDataSourceContainer container = run.dataSourceContainer;
        try {
            if (!container.isConnected()) {
                // Reports connection errors itself (SwingConnectionService)
                DBUtils.initDataSource(monitor, container, null);
            }
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null || !container.isConnected()) {
                stopPreparation(run, run.cancelRequested ? Status.CANCEL_STATUS
                    : errorStatus("Not connected to '" + container.getName() + "'"));
                return;
            }
            DBCExecutionContext context = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
            if (context == null) {
                stopPreparation(run, errorStatus("No execution context for '" + container.getName() + "'"));
                return;
            }
            List<SQLScriptElement> elements = SQLScriptBatches.extractQueries(dataSource, run.text, run.offset, run.length);
            if (elements.isEmpty()) {
                stopPreparation(run, Status.OK_STATUS);
                return;
            }
            SQLScriptContext scriptContext = new SQLScriptContext(null, () -> context, null, new MessagesWriter(run), null);
            SQLQueryExecuteJob job = new SQLQueryExecuteJob(
                "Execute query", context, null, elements, scriptContext,
                new QueryListener(run), new QueryHandler(run, context, scriptContext));
            if (run.customMaxRows) {
                job.setResultSetLimit(0, run.maxRows);
            }
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    IStatus result = event.getResult();
                    SwingUtilities.invokeLater(() -> finishExecution(run, result != null ? result : Status.OK_STATUS));
                }
            });
            synchronized (jobLock) {
                if (run.cancelRequested) {
                    stopPreparation(run, Status.CANCEL_STATUS);
                    return;
                }
                run.job = job;
                job.schedule();
            }
            post(run, () -> statusLine.setState(QueryStatusLine.State.EXECUTING, "Executing query…"));
        } catch (Throwable e) {
            log.debug("Query preparation failed", e);
            stopPreparation(run, errorStatus(CommonUtils.getAllExceptionMessages(e)));
        }
    }

    private void stopPreparation(@NotNull Execution run, @NotNull IStatus status) {
        SwingUtilities.invokeLater(() -> finishExecution(run, status));
    }

    @NotNull
    private static IStatus errorStatus(@NotNull String message) {
        return new Status(IStatus.ERROR, ChromiteSwingActivator.PLUGIN_ID, message);
    }

    /**
     * Runs a UI update in the EDT if the run is still the current one.
     */
    private void post(@NotNull Execution run, @NotNull Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (execution == run) {
                update.run();
            }
        });
    }

    /**
     * Messages of one finished query: warnings and PRINT output, rows affected, time, or the error with its position
     * in the editor.
     */
    private void reportQueryResult(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQueryResult result) {
        List<String> messages = new ArrayList<>();
        for (Throwable warning : CommonUtils.safeCollection(result.getWarnings())) {
            // SQL Server PRINT and RAISERROR (low severity), PostgreSQL NOTICE etc. arrive as SQLWarning
            messages.add(CommonUtils.notEmpty(warning.getMessage()));
        }
        Throwable error = result.getError();
        String errorText = null;
        if (error == null) {
            boolean anyCount = false;
            for (SQLQueryResult.ExecuteResult executeResult : result.getExecuteResults()) {
                Long count = executeResult.isResultSet() ? executeResult.getRowCount() : executeResult.getUpdateCount();
                if (count == null) {
                    continue;
                }
                anyCount = true;
                String line = "(" + count + (count == 1 ? " row affected)" : " rows affected)");
                if (executeResult.isResultSet() && run.maxRows > 0 && count >= run.maxRows) {
                    line += " - fetching stopped at the maximum of " + run.maxRows + " rows";
                }
                messages.add(line);
            }
            if (!anyCount && !run.cancelRequested) {
                // A canceled statement ends without an error and without counts; it did not complete
                messages.add("Commands completed successfully.");
            }
            messages.add("Execution time: " + result.getQueryTime() + " ms");
        } else {
            run.hasErrors = true;
            errorText = describeError(run, session, result.getStatement(), error);
            run.reportedErrors.add(error);
        }
        String finalErrorText = errorText;
        post(run, () -> {
            messages.forEach(resultsView::appendMessage);
            if (finalErrorText != null) {
                resultsView.appendError(finalErrorText);
            }
        });
    }

    /**
     * SSMS-like error header ({@code Msg 208, State 42S02, Line 3, Column 15}) followed by the error messages.
     * The position comes from the dialect's error assistant when it can locate the error, else it is the query start.
     */
    @NotNull
    private static String describeError(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQuery query, @NotNull Throwable error) {
        int errorOffset = -1;
        int line = -1;
        int column = -1;
        try {
            DBPErrorAssistant errorAssistant = DBUtils.getAdapter(DBPErrorAssistant.class, session.getDataSource());
            DBPErrorAssistant.ErrorPosition[] positions = errorAssistant == null ? null :
                errorAssistant.getErrorPosition(session.getProgressMonitor(), session.getExecutionContext(), query.getText(), error);
            if (positions != null && positions.length > 0) {
                DBPErrorAssistant.ErrorPosition position = positions[0];
                if (position.line < 0) {
                    if (position.position >= 0) {
                        errorOffset = query.getOffset() + position.position;
                    }
                } else {
                    // Line within the query, position within that line (both zero-based)
                    line = lineOf(run.text, query.getOffset()) + position.line;
                    column = position.position >= 0 ? position.position + 1 : -1;
                }
            }
        } catch (Throwable e) {
            log.debug("Can't get error position", e);
        }
        if (errorOffset >= 0 && errorOffset <= run.text.length()) {
            line = lineOf(run.text, errorOffset);
            column = errorOffset - run.text.lastIndexOf('\n', errorOffset - 1);
        } else if (line < 0) {
            line = lineOf(run.text, query.getOffset());
        }

        StringBuilder header = new StringBuilder();
        SQLException sqlException = findSqlException(error);
        if (sqlException != null) {
            header.append("Msg ").append(sqlException.getErrorCode());
            if (!CommonUtils.isEmpty(sqlException.getSQLState())) {
                header.append(", State ").append(sqlException.getSQLState());
            }
            header.append(", ");
        } else {
            header.append("Error, ");
        }
        header.append("Line ").append(line);
        if (column > 0) {
            header.append(", Column ").append(column);
        }
        return header + "\n" + CommonUtils.getAllExceptionMessages(error);
    }

    @Nullable
    private static SQLException findSqlException(@NotNull Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * One-based line number of an offset.
     */
    private static int lineOf(@NotNull String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    @NotNull
    private static String formatElapsed(long millis, boolean withMillis) {
        long seconds = millis / 1000;
        String text = String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60); //$NON-NLS-1$
        return withMillis ? text + String.format(".%03d", millis % 1000) : text; //$NON-NLS-1$
    }

    /**
     * State of one F5 run. Fields written in job threads are only read in the EDT after the job is done
     * (the done event is posted after all their updates), except the cancel flag which is guarded by {@code jobLock}.
     */
    private static final class Execution {
        final DBPDataSourceContainer dataSourceContainer;
        final String text;
        final int offset;
        final int length;
        final long maxRows;
        final boolean customMaxRows;
        final long startTime = System.currentTimeMillis();
        final AtomicLong rowsFetched = new AtomicLong();
        final List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();
        volatile boolean hasErrors;
        volatile boolean cancelRequested;
        @Nullable
        Job job;

        Execution(
            @NotNull DBPDataSourceContainer dataSourceContainer,
            @NotNull String text,
            int offset,
            int length,
            long maxRows,
            boolean customMaxRows
        ) {
            this.dataSourceContainer = dataSourceContainer;
            this.text = text;
            this.offset = offset;
            this.length = length;
            this.maxRows = maxRows;
            this.customMaxRows = customMaxRows;
        }
    }

    /**
     * Result receivers and user interaction of the execution engine (called in the job thread).
     */
    private final class QueryHandler implements SQLQueryExecuteHandler {
        private final Execution run;
        private final DBCExecutionContext context;
        private final SQLScriptContext scriptContext;

        QueryHandler(@NotNull Execution run, @NotNull DBCExecutionContext context, @NotNull SQLScriptContext scriptContext) {
            this.run = run;
            this.context = context;
            this.scriptContext = scriptContext;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            SQLQueryDataContainer dataContainer = new SQLQueryDataContainer(() -> context, query, scriptContext, log);
            return new ResultSetGridReceiver(dataContainer, fetched -> {
                run.rowsFetched.addAndGet(fetched.data().rows().size());
                post(run, () -> {
                    resultsView.addGrid(fetched);
                    statusLine.setRows(run.rowsFetched.get() + " rows");
                    host.showResults(SqlEditorPanel.this, false);
                });
            });
        }

        @NotNull
        @Override
        public DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
            // The error is shown in the Messages tab instead of a Stop/Retry/Ignore dialog; the script stops (decision: see class doc)
            if (!run.reportedErrors.contains(error)) {
                // Failed before the query was sent (parameters, pragmas, control commands)
                run.hasErrors = true;
                post(run, () -> resultsView.appendError(CommonUtils.getAllExceptionMessages(error)));
            }
            return DBPPlatformUI.UserResponse.STOP;
        }
    }

    /**
     * Query events of the execution engine (called in the job thread).
     */
    private final class QueryListener implements SQLQueryListener {
        private final Execution run;

        QueryListener(@NotNull Execution run) {
            this.run = run;
        }

        @Override
        public void onEndQuery(@NotNull DBCSession session, @NotNull SQLQueryResult result, @NotNull DBCStatistics statistics) {
            reportQueryResult(run, session, result);
        }
    }

    /**
     * Output of script commands ({@code @echo} etc.) goes to the Messages tab.
     */
    private final class MessagesWriter extends Writer {
        private final Execution run;

        MessagesWriter(@NotNull Execution run) {
            this.run = run;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            String text = new String(buffer, offset, length);
            if (!text.isBlank()) {
                post(run, () -> resultsView.appendMessage(text.stripTrailing()));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    //////////////////////////////////////////////////////////
    // Connection and default schema (EDT)

    /**
     * Fills the connection combo with the connections of the active project (and the current one, if it is elsewhere).
     */
    private void reloadConnectionItems() {
        List<DBPDataSourceContainer> containers = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (!container.isHidden() && !container.isTemporary()) {
                    containers.add(container);
                }
            }
        }
        if (dataSourceContainer != null && !containers.contains(dataSourceContainer)) {
            containers.add(dataSourceContainer);
        }
        containers.sort(Comparator.comparing(DBPDataSourceContainer::getName, String.CASE_INSENSITIVE_ORDER));
        updatingCombos = true;
        try {
            DefaultComboBoxModel<DBPDataSourceContainer> model = new DefaultComboBoxModel<>();
            containers.forEach(model::addElement);
            model.setSelectedItem(dataSourceContainer);
            connectionCombo.setModel(model);
        } finally {
            updatingCombos = false;
        }
    }

    /**
     * Reads the default catalog/schema of the connection and the ones it can change to, in a job.
     */
    private void refreshSchemas() {
        int serial = ++schemaSerial;
        DBPDataSourceContainer container = dataSourceContainer;
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        if (dataSource == null) {
            showSchemas(serial, null);
            return;
        }
        new AbstractJob("Read default schema of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                SchemaState state = null;
                DBCExecutionContext context = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
                if (context != null && context.getContextDefaults() != null) {
                    ContextDefaultObjectsReader reader = new ContextDefaultObjectsReader(dataSource, context);
                    try {
                        reader.run(monitor);
                        state = new SchemaState(dataSource, context, reader);
                    } catch (InvocationTargetException e) {
                        log.debug("Can't read the default schema of '" + container.getName() + "'", e.getTargetException());
                    } catch (InterruptedException e) {
                        // Canceled
                    }
                }
                SchemaState finalState = state;
                SwingUtilities.invokeLater(() -> showSchemas(serial, finalState));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void showSchemas(int serial, @Nullable SchemaState state) {
        if (serial != schemaSerial) {
            return;
        }
        schemaState = state;
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        Object selected = NOT_AVAILABLE;
        boolean supported = false;
        DBCExecutionContextDefaults<?, ?> contextDefaults = state == null ? null : state.context().getContextDefaults();
        Collection<? extends DBSObject> objects = state == null ? null : state.reader().getObjectList();
        if (contextDefaults != null && objects != null) {
            DBSObject defaultObject = state.reader().getDefaultObject();
            for (DBSObject object : objects) {
                if (object == null || DBUtils.getAdapter(DBSObjectContainer.class, object) == null) {
                    continue;
                }
                model.addElement(object);
                // A default schema selects its catalog when only catalogs are listed (as SelectActiveSchemaHandler)
                if (defaultObject != null && (object == defaultObject || object == defaultObject.getParentObject())) {
                    selected = object;
                }
            }
            supported = model.getSize() > 0 && (contextDefaults.supportsCatalogChange() || contextDefaults.supportsSchemaChange());
        }
        if (model.getSize() == 0) {
            model.addElement(NOT_AVAILABLE);
        } else if (selected == NOT_AVAILABLE) {
            model.insertElementAt(NOT_AVAILABLE, 0);
        }
        updatingCombos = true;
        try {
            model.setSelectedItem(selected);
            schemaCombo.setModel(model);
        } finally {
            updatingCombos = false;
        }
        schemaChangeSupported = supported;
        schemaCombo.setEnabled(execution == null && supported);
        statusLine.setDatabase(selected instanceof DBSObject object ? object.getName() : null);
    }

    /**
     * Makes a catalog or schema the default of the connection's context, with the same arguments as the SWT
     * active schema selector ({@code SelectActiveSchemaHandler}).
     */
    private void changeDefaultObject(@NotNull DBSObject object) {
        SchemaState state = schemaState;
        if (state == null || object == state.reader().getDefaultObject()) {
            return;
        }
        String currentCatalogName = state.reader().getDefaultCatalogName();
        String newCatalogName = object instanceof DBSCatalog ? object.getName() : currentCatalogName;
        String newSchemaName = object instanceof DBSSchema ? object.getName() : null;
        // Reads that are still running show the old default; drop them
        schemaSerial++;
        schemaCombo.setEnabled(false);
        new AbstractJob("Change default schema") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBExecUtils.setExecutionContextDefaults(monitor, state.dataSource(), state.context(),
                        newCatalogName, currentCatalogName, newSchemaName);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError("Change default schema",
                        "Can't make '" + object.getName() + "' the default", e);
                }
                SwingUtilities.invokeLater(SqlEditorPanel.this::refreshSchemas);
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Default catalog/schema read for the combo.
     */
    private record SchemaState(
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context,
        @NotNull ContextDefaultObjectsReader reader
    ) {
    }

    /**
     * Connection items: driver icon and connection name.
     */
    private static final class ConnectionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDataSourceContainer container) {
                setText(container.getName());
                setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
            } else {
                setText("<Select connection>");
                setIcon(null);
            }
            return this;
        }
    }

    /**
     * Catalog/schema items with the navigator icons; the "not available" text has none.
     */
    private static final class SchemaRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBSObject object) {
                setText(object.getName());
                setIcon(NodeIcons.getIcon(object instanceof DBSSchema ? DBIcon.TREE_SCHEMA : DBIcon.TREE_DATABASE));
            } else {
                setIcon(null);
            }
            return this;
        }
    }

    /**
     * SQL text area with the Chromite colors of the current theme (also used read-only by {@link DdlViewerPanel}).
     */
    static final class SqlTextArea extends RSyntaxTextArea {
        SqlTextArea() {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            setCodeFoldingEnabled(false);
            setTabSize(4);
            // SSMS find keys (Edit menu); bound here so they win over the text area's own keys (Ctrl+H deletes backwards)
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "chromite.find", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, false));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "chromite.replace", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "chromite.findNext", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "chromite.findPrevious", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, false));
        }

        private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Runnable task) {
            getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
            getActionMap().put(actionKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    task.run();
                }
            });
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getParent() != null) {
                // Look and feel switch: the tree update resets UI defaults first, then the theme colors are applied again
                SwingUtilities.invokeLater(this::applyTheme);
            }
        }

        void applyTheme() {
            String resource = ChromiteTheme.getCurrent() == ChromiteTheme.DARK ? "SqlEditorDark.xml" : "SqlEditorLight.xml"; //$NON-NLS-1$ //$NON-NLS-2$
            try (InputStream in = SqlEditorPanel.class.getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("SQL editor theme " + resource + " not found");
                    return;
                }
                Theme.load(in).apply(this);
            } catch (IOException e) {
                log.warn("Can't load SQL editor theme " + resource, e);
            }
            // font.code: the theme files name no font (a font family there would not be a composite font, and Hangul
            // would show as boxes), so the look and feel's JetBrains Mono composite font is set after the theme
            Font codeFont = UIManager.getFont("monospaced.font"); //$NON-NLS-1$
            if (codeFont != null) {
                setFont(codeFont);
                Gutter gutter = RSyntaxUtilities.getGutter(this);
                if (gutter != null) {
                    gutter.setLineNumberFont(codeFont);
                }
            }
        }
    }
}
