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
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.preferences.DBPPreferenceListener;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMConstants;
import com.seizia.chromite.model.qm.QMEvent;
import com.seizia.chromite.model.qm.QMEventAction;
import com.seizia.chromite.model.qm.QMEventBrowser;
import com.seizia.chromite.model.qm.QMEventCursor;
import com.seizia.chromite.model.qm.QMMetaEvent;
import com.seizia.chromite.model.qm.QMMetaListener;
import com.seizia.chromite.model.qm.QMObjectType;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.qm.filters.QMCursorFilter;
import com.seizia.chromite.model.qm.filters.QMEventCriteria;
import com.seizia.chromite.model.qm.meta.QMMConnectionInfo;
import com.seizia.chromite.model.qm.meta.QMMObject;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import com.seizia.chromite.model.qm.meta.QMMTransactionInfo;
import com.seizia.chromite.model.qm.meta.QMMTransactionSavepointInfo;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.qm.DefaultEventFilter;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.ui.swing.internal.QueryManagerMessages;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Query Manager 도구 창: 쿼리 관리자(QM)가 기록한 모든 실행(쿼리 창의 문장만이 아니라 결과 필터·정렬, 스크립트, 유틸리티 쿼리,
 * 메타데이터 읽기)과 커밋·롤백, 연결·끊기를 한 표로 보인다(SWT {@code QueryManagerView}·{@code QueryLogViewer}의 Swing판, 코드를 옮기지 않고 동작만 맞춤).
 *
 * 설계:
 * <ul>
 *     <li>내용은 쿼리 관리자가 메모리에 둔 기록이다. 처음 보일 때 기록 전체를 한 번 읽고({@link QMUtils#getEventBrowser}, 메모리에 둔 마지막 1만 개
 *     이벤트), 그 뒤로는 쿼리 관리자의 알림({@link QMMetaListener}, 0.25초마다 모아서 옴)을 EDT에서 더한다. 이벤트는 객체마다 한 행으로 모은다
 *     (실행은 시작·가져오기·끝 이벤트가 같은 행, 커밋·롤백은 트랜잭션이 끝난 이벤트, 연결은 연결함·끊음 이벤트마다 한 행). 행은 모델 객체를 그대로
 *     들고 있어 실행 중인 문장이 끝나거나 트랜잭션이 커밋되면 다음 알림 때 그 행이 바뀐다.</li>
 *     <li>알림은 처음 보일 때부터 앱을 닫을 때({@link #dispose})까지 받는다. 숨기고 다시 보일 때 기록을 다시 읽으면 지우기({@link #clear})로 비운 행이
 *     돌아오기 때문이다. 숨긴 동안은 행만 고치고 표는 다시 보일 때 거른다. 한 번도 열지 않으면 아무것도 받지 않는다.</li>
 *     <li>보일 종류(연결, 트랜잭션, 쿼리와 쿼리 종류)는 따로 두지 않고 쿼리 관리자 설정 {@code qm.objectTypes}·{@code qm.queryTypes}를 쓴다
 *     (SWT와 같음, Options &gt; Logs의 "기록할 것"·"쿼리 종류"와 같은 키). 거르기는 SQL 로그 파일과 같은 {@link DefaultEventFilter}다.
 *     그래서 "보일 항목" 메뉴에서 바꾸면 SQL 로그 파일이 기록하는 것도 바뀌고, Options에서 바꾸면 이 창도 바로 따른다(설정 변경 알림).
 *     메타데이터 읽기를 숨겨도 기록은 그대로라 다시 켜면 지난 메타데이터 읽기도 보인다.</li>
 *     <li>연결 필터는 기록에 나온 연결이고, 찾기 글자는 문장·연결 이름·세션 이름(쿼리 창의 세션은 {@code SQLEditor <SQLQueryN.sql>})·오류에서
 *     대소문자 무시로 찾는다. 그래서 한 쿼리 창의 실행만 보려면 창 이름으로 찾는다(SWT 편집기의 실행 로그 대신).</li>
 *     <li>트랜잭션 보기: 고른 행이 속한 트랜잭션({@code QMMTransactionInfo})의 행만 보인다. 연결 필터 대신 쓰이고 찾기 글자와 보일 종류는 그대로
 *     적용한다. 자동 커밋으로 실행한 문장은 트랜잭션이 없어 켜지지 않는다. 트랜잭션 열은 각 문장의 트랜잭션이 아직 열렸는지, 언제 커밋·롤백했는지다
 *     (트랜잭션 로그와 같은 문구).</li>
 *     <li>열기와 복사는 실행한 순서(오래된 것 먼저)로 문장을 잇는다. 둘 이상이면 끝에 구분 기호가 없는 문장에 {@code ;}를 붙인다. 쿼리 창은 첫 문장의
 *     연결로 열고(지운 연결이면 연결 없이) 실행하지 않는다. 문장에 들어간 값은 기록된 그대로다(바인딩한 실행은 쿼리 관리자가 값을 넣어 적은 문장).</li>
 *     <li>지우기는 이 표만 비운다(쿼리 관리자의 기록, SQL 로그 파일, 쿼리 기록은 그대로). 지운 시각보다 먼저 시작한 문장은 끝나도 다시 나오지 않는다.</li>
 *     <li>행은 쿼리 관리자가 메모리에 두는 만큼({@value #MAX_ROWS}개)만 두고 오래된 것부터 버린다. 앱을 다시 시작하면 비어 있다(지난 실행은 SQL 로그 파일에).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-manager.md)
 */
final class QueryManagerPanel extends JPanel implements QMMetaListener, DBPPreferenceListener {

    private static final Log log = Log.getLog(QueryManagerPanel.class);

    /**
     * Opens statements in a query window (main window).
     */
    interface Host {
        /**
         * Opens a new query window with the text, on the connection (or without one). Never runs it.
         */
        void openInQueryWindow(@Nullable DBPDataSourceContainer connection, @NotNull String sql);
    }

    /**
     * As many entries as the query manager keeps in memory ({@code QMMCollectorImpl} history).
     */
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_STATEMENT_COLUMN_TEXT = 300;
    private static final int MAX_TOOLTIP_TEXT = 1000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final DateTimeFormatter FULL_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final DateTimeFormatter END_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    private static final int COL_TIME = 0;
    private static final int COL_CONNECTION = 1;
    private static final int COL_SESSION = 2;
    private static final int COL_TYPE = 3;
    private static final int COL_STATEMENT = 4;
    private static final int COL_DURATION = 5;
    private static final int COL_ROWS = 6;
    private static final int COL_RESULT = 7;
    private static final int COL_TRANSACTION = 8;
    // Time, connection, session, type, statement, duration, rows, result, transaction
    private static final int[] COLUMN_WIDTHS = {95, 140, 160, 130, 460, 80, 100, 180, 140};
    /**
     * Client property of a check box menu item that keeps its menu open when clicked (Swing's menu item UI).
     */
    private static final String KEEP_MENU_OPEN = "CheckBoxMenuItem.doNotCloseOnMouseClick"; //$NON-NLS-1$

    private final Host host;
    // EDT only: the rows, oldest first, and each row by its key
    private final List<Row> rows = new ArrayList<>();
    private final Map<RowKey, Row> rowsByKey = new HashMap<>();
    // EDT only: the connections of the rows (container id to name), for the connection filter
    private final Map<String, String> connections = new LinkedHashMap<>();
    // The same filter as the SQL log file (qm.objectTypes, qm.queryTypes)
    private final DefaultEventFilter typeFilter = new DefaultEventFilter();
    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final SqlEditorPanel.SqlTextArea preview = new SqlEditorPanel.SqlTextArea();
    private final JTextField searchField = new JTextField(18);
    private final JComboBox<ConnectionItem> connectionCombo = new JComboBox<>();
    private final JLabel countLabel = new JLabel();
    private final JPanel transactionBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel transactionLabel = new JLabel();
    private final Action openAction;
    private final Action copyAction;
    private final Action showTransactionAction;
    private final Action clearAction;
    private final Action showAction;
    // Events of the query manager's dispatcher thread, applied on the EDT
    private final List<QMEvent> pendingEvents = new ArrayList<>();
    private boolean applyScheduled;
    // EDT only
    @Nullable
    private QMMTransactionInfo transactionFilter;
    private long clearedBefore;
    private boolean started;
    private boolean disposed;
    private boolean dirty;
    private boolean updatingCombo;
    private boolean refiltering;

    QueryManagerPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;
        openAction = createAction(QueryManagerMessages.qm_open, QueryManagerMessages.qm_open_tip, this::openSelected);
        copyAction = createAction(QueryManagerMessages.qm_copy, QueryManagerMessages.qm_copy_tip, this::copySelected);
        showTransactionAction = createAction(QueryManagerMessages.qm_show_transaction, QueryManagerMessages.qm_show_transaction_tip,
            this::showSelectedTransaction);
        clearAction = createAction(QueryManagerMessages.qm_clear, QueryManagerMessages.qm_clear_tip, this::clear);
        showAction = new AbstractAction(QueryManagerMessages.qm_show) {
            @Override
            public void actionPerformed(ActionEvent e) {
                showTypeMenu(e.getSource() instanceof Component component ? component : QueryManagerPanel.this);
            }
        };
        showAction.putValue(Action.SHORT_DESCRIPTION, QueryManagerMessages.qm_show_tip);

        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.add(UiIcons.createActionButton(openAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(copyAction, "copy")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(showTransactionAction, "commit")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(showAction, "filter")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(clearAction, "close")); //$NON-NLS-1$

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, QueryManagerMessages.qm_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setToolTipText(QueryManagerMessages.qm_search);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        connectionCombo.addActionListener(e -> {
            if (!updatingCombo) {
                refilter();
            }
        });
        updateConnectionCombo();
        countLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel filters = new JPanel(new BorderLayout(6, 0));
        filters.setOpaque(false);
        filters.add(searchField, BorderLayout.CENTER);
        JPanel filtersEast = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filtersEast.setOpaque(false);
        filtersEast.add(connectionCombo);
        filtersEast.add(countLabel);
        filters.add(filtersEast, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        top.add(toolBar, BorderLayout.WEST);
        top.add(filters, BorderLayout.CENTER);

        JButton showAllButton = new JButton(QueryManagerMessages.qm_show_all);
        showAllButton.addActionListener(e -> setTransactionFilter(null));
        transactionBar.setOpaque(false);
        transactionBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        transactionBar.add(transactionLabel, BorderLayout.CENTER);
        transactionBar.add(showAllButton, BorderLayout.EAST);
        transactionBar.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        north.add(transactionBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(COLUMN_WIDTHS[i]));
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !refiltering) {
                selectionChanged();
            }
        });
        // Ctrl+C and Edit > Copy copy the statements, not the cells (EditMenu uses the transfer handler)
        table.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                String script = getSelectedScript();
                return script == null ? null : new StringSelection(script);
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) {
                    openSelected();
                }
            }
        });

        preview.setEditable(false);
        preview.setHighlightCurrentLine(false);
        JScrollPane previewScroll = new JScrollPane(preview);
        preview.applyTheme();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), previewScroll);
        split.setResizeWeight(0.7);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        updateActions();
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String description, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!started && !disposed) {
            start();
        } else if (dirty) {
            refilter();
        }
    }

    /**
     * Reads the query manager's history and follows it from now on (the first time the window is shown).
     */
    private void start() {
        started = true;
        QMUtils.registerMetaListener(this);
        DBWorkbench.getPlatform().getPreferenceStore().addPropertyChangeListener(this);
        QMEventBrowser browser = QMUtils.getEventBrowser(true);
        if (browser != null) {
            // Every kind: the view filters by itself, so changing the filter shows earlier entries too
            QMEventCriteria criteria = new QMEventCriteria();
            criteria.setObjectTypes(QMObjectType.values());
            criteria.setQueryTypes(DBCExecutionPurpose.values());
            List<QMEvent> events = new ArrayList<>();
            try (QMEventCursor cursor = browser.getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
                DBRProgressMonitor monitor = new VoidProgressMonitor();
                while (cursor.hasNextEvent(monitor)) {
                    events.add(cursor.nextEvent(monitor));
                }
            } catch (DBException e) {
                log.warn("Can't read the query manager history", e); //$NON-NLS-1$
            }
            // The cursor gives the newest first. Events that also arrive by the listener are merged into their rows
            Collections.reverse(events);
            events.forEach(this::applyEvent);
            trimRows();
        }
        refilter();
    }

    /**
     * Stops following the query manager (main window closed).
     */
    void dispose() {
        disposed = true;
        if (started) {
            started = false;
            QMUtils.unregisterMetaListener(this);
            DBWorkbench.getPlatform().getPreferenceStore().removePropertyChangeListener(this);
        }
    }

    @Override
    public void metaInfoChanged(@NotNull DBRProgressMonitor monitor, @NotNull List<QMMetaEvent> events) {
        if (DBWorkbench.getPlatform().isShuttingDown()) {
            return;
        }
        boolean schedule;
        synchronized (pendingEvents) {
            pendingEvents.addAll(events);
            schedule = !applyScheduled;
            applyScheduled = true;
        }
        if (schedule) {
            SwingUtilities.invokeLater(this::applyPendingEvents);
        }
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent event) {
        // From this window's Show menu or from Tools > Options > Logs, on any thread
        if (event.getProperty() != null && event.getProperty().startsWith(QMConstants.PROP_PREFIX)) {
            SwingUtilities.invokeLater(() -> {
                typeFilter.reloadPreferences();
                refilterIfShowing();
            });
        }
    }

    private void applyPendingEvents() {
        List<QMEvent> events;
        synchronized (pendingEvents) {
            events = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
            applyScheduled = false;
        }
        if (disposed) {
            return;
        }
        events.forEach(this::applyEvent);
        trimRows();
        refilterIfShowing();
    }

    private void refilterIfShowing() {
        if (isShowing()) {
            refilter();
        } else {
            dirty = true;
        }
    }

    /**
     * Puts the event into the row of its object: a new row for an execution, a transaction end or a connect/disconnect
     * not seen before, else the row's latest event.
     */
    private void applyEvent(@NotNull QMEvent event) {
        QMMObject object = event.getObject();
        RowKey key;
        long time;
        switch (object) {
            case QMMStatementExecuteInfo exec -> {
                if (CommonUtils.isEmpty(exec.getQueryString())) {
                    return;
                }
                key = new RowKey(exec.getObjectId(), null, 0);
                time = exec.getOpenTime();
            }
            case QMMTransactionInfo txn -> {
                // A commit or rollback ends the transaction record; its begin is no event of its own
                if (event.getAction() != QMEventAction.END) {
                    return;
                }
                key = new RowKey(txn.getObjectId(), null, 0);
                time = txn.getCloseTime();
            }
            case QMMTransactionSavepointInfo savepoint -> {
                if (event.getAction() != QMEventAction.END) {
                    return;
                }
                key = new RowKey(savepoint.getObjectId(), null, 0);
                time = savepoint.getCloseTime();
            }
            case QMMConnectionInfo connection -> {
                // The same object connects again after a reconnect: one row per connect and disconnect
                if (event.getAction() != QMEventAction.BEGIN && event.getAction() != QMEventAction.END) {
                    return;
                }
                time = QMUtils.getObjectEventTime(event);
                key = new RowKey(connection.getObjectId(), event.getAction(), time);
            }
            default -> {
                return;
            }
        }
        Row row = rowsByKey.get(key);
        if (row == null) {
            if (time < clearedBefore) {
                return;
            }
            row = new Row(key);
            rowsByKey.put(key, row);
            rows.add(row);
        }
        row.event = event;
        QMMConnectionInfo connection = row.getConnection();
        if (connection != null) {
            connections.putIfAbsent(connection.getContainerId(), CommonUtils.notEmpty(connection.getContainerName()));
        }
    }

    private void trimRows() {
        int excess = rows.size() - MAX_ROWS;
        if (excess > 0) {
            List<Row> removed = rows.subList(0, excess);
            removed.forEach(row -> rowsByKey.remove(row.key));
            removed.clear();
        }
    }

    /**
     * Shows the rows that pass the filters, newest first, keeping the selected rows.
     */
    private void refilter() {
        dirty = false;
        updateConnectionCombo();
        List<Row> selected = getSelectedRows();
        String search = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String connectionId = connectionCombo.getSelectedItem() instanceof ConnectionItem item ? item.id() : null;
        List<Row> visible = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row row = rows.get(i);
            if (!typeFilter.accept(row.event)) {
                continue;
            }
            if (transactionFilter != null) {
                // The transaction is of one connection: the connection filter does not apply
                if (row.getTransaction() != transactionFilter) {
                    continue;
                }
            } else if (connectionId != null) {
                QMMConnectionInfo connection = row.getConnection();
                if (connection == null || !connectionId.equals(connection.getContainerId())) {
                    continue;
                }
            }
            if (!search.isEmpty() && !row.matches(search)) {
                continue;
            }
            visible.add(row);
        }
        // The new rows clear the selection; the preview follows only the selection put back below
        refiltering = true;
        try {
            model.setRows(visible);
            for (Row row : selected) {
                int index = model.indexOf(row);
                if (index >= 0) {
                    table.getSelectionModel().addSelectionInterval(index, index);
                }
            }
        } finally {
            refiltering = false;
        }
        countLabel.setText(NLS.bind(QueryManagerMessages.qm_count, visible.size(), rows.size()));
        updateTransactionBar();
        selectionChanged();
    }

    /**
     * The connection filter's items: all connections, then the connections in the rows by name. Rebuilt only when they
     * change, keeping the chosen connection.
     */
    private void updateConnectionCombo() {
        List<ConnectionItem> items = new ArrayList<>();
        items.add(new ConnectionItem(null, QueryManagerMessages.qm_all_connections));
        TreeMap<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        connections.forEach((id, name) -> byName.put(name + '\u0000' + id, id));
        byName.forEach((nameAndId, id) -> items.add(new ConnectionItem(id, connections.get(id))));
        ComboBoxModel<ConnectionItem> current = connectionCombo.getModel();
        if (current.getSize() == items.size()) {
            boolean same = true;
            for (int i = 0; i < items.size() && same; i++) {
                same = items.get(i).equals(current.getElementAt(i));
            }
            if (same) {
                return;
            }
        }
        Object selected = connectionCombo.getSelectedItem();
        updatingCombo = true;
        try {
            DefaultComboBoxModel<ConnectionItem> comboModel = new DefaultComboBoxModel<>(items.toArray(new ConnectionItem[0]));
            comboModel.setSelectedItem(selected != null && items.contains(selected) ? selected : items.getFirst());
            connectionCombo.setModel(comboModel);
        } finally {
            updatingCombo = false;
        }
    }

    @NotNull
    private List<Row> getSelectedRows() {
        List<Row> selected = new ArrayList<>();
        for (int index : table.getSelectedRows()) {
            if (index < model.rows.size()) {
                selected.add(model.rows.get(index));
            }
        }
        return selected;
    }

    /**
     * The selected statements in the order they ran (oldest first).
     */
    @NotNull
    private List<QMMStatementExecuteInfo> getSelectedStatements() {
        List<QMMStatementExecuteInfo> statements = new ArrayList<>();
        List<Row> selected = getSelectedRows();
        // The table shows the newest first
        for (int i = selected.size() - 1; i >= 0; i--) {
            if (selected.get(i).event.getObject() instanceof QMMStatementExecuteInfo exec) {
                statements.add(exec);
            }
        }
        return statements;
    }

    /**
     * The selected statements as one text: a single statement as it ran, several ones each ended by a delimiter.
     */
    @Nullable
    private String getSelectedScript() {
        List<QMMStatementExecuteInfo> statements = getSelectedStatements();
        if (statements.isEmpty()) {
            return null;
        }
        if (statements.size() == 1) {
            return statements.getFirst().getQueryString().strip();
        }
        StringBuilder script = new StringBuilder();
        for (QMMStatementExecuteInfo exec : statements) {
            String query = exec.getQueryString().strip();
            if (!script.isEmpty()) {
                script.append('\n');
            }
            script.append(query);
            if (!query.endsWith(SQLConstants.DEFAULT_STATEMENT_DELIMITER)) {
                script.append(SQLConstants.DEFAULT_STATEMENT_DELIMITER);
            }
            script.append('\n');
        }
        return script.toString();
    }

    private void selectionChanged() {
        List<Row> selected = getSelectedRows();
        Row lead = selected.isEmpty() ? null : selected.getFirst();
        String text = lead == null ? "" : lead.getPreviewText(); //$NON-NLS-1$
        if (!text.equals(preview.getText())) {
            QMMConnectionInfo connection = lead == null ? null : lead.getConnection();
            // Highlighting in the connection's dialect
            preview.setDataSourceContainer(connection == null ? null : QueryHistoryDialog.findConnection(connection.getContainerId()));
            preview.setText(text);
            preview.setCaretPosition(0);
        }
        updateActions();
    }

    private void updateActions() {
        boolean statements = !getSelectedStatements().isEmpty();
        openAction.setEnabled(statements);
        copyAction.setEnabled(statements);
        List<Row> selected = getSelectedRows();
        showTransactionAction.setEnabled(selected.size() == 1 && selected.getFirst().getTransaction() != null
            && selected.getFirst().getTransaction() != transactionFilter);
        clearAction.setEnabled(!rows.isEmpty());
    }

    private void openSelected() {
        List<QMMStatementExecuteInfo> statements = getSelectedStatements();
        String script = getSelectedScript();
        if (statements.isEmpty() || script == null) {
            return;
        }
        // The connection of the first statement; null when it was deleted (the window opens without a connection)
        DBPDataSourceContainer connection = QueryHistoryDialog.findConnection(statements.getFirst().getConnection().getContainerId());
        host.openInQueryWindow(connection, script);
    }

    private void copySelected() {
        String script = getSelectedScript();
        if (script != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(script), null);
        }
    }

    private void showSelectedTransaction() {
        List<Row> selected = getSelectedRows();
        if (selected.size() == 1 && selected.getFirst().getTransaction() != null) {
            setTransactionFilter(selected.getFirst().getTransaction());
        }
    }

    private void setTransactionFilter(@Nullable QMMTransactionInfo transaction) {
        transactionFilter = transaction;
        refilter();
    }

    private void updateTransactionBar() {
        QMMTransactionInfo transaction = transactionFilter;
        transactionBar.setVisible(transaction != null);
        if (transaction != null) {
            QMMConnectionInfo connection = transaction.getConnection();
            transactionLabel.setText(NLS.bind(QueryManagerMessages.qm_transaction_filter, new Object[]{
                CommonUtils.notEmpty(connection.getContainerName()), CommonUtils.notEmpty(connection.getContextName()),
                describeTransaction(transaction)}));
        }
    }

    /**
     * Empties the list; the query manager's records, the SQL log file and the Query History stay.
     */
    private void clear() {
        clearedBefore = System.currentTimeMillis();
        rows.clear();
        rowsByKey.clear();
        connections.clear();
        transactionFilter = null;
        refilter();
    }

    private void showPopup(@NotNull MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem(openAction));
        menu.add(new JMenuItem(copyAction));
        menu.add(new JMenuItem(showTransactionAction));
        menu.addSeparator();
        menu.add(new JMenuItem(clearAction));
        menu.show(table, e.getX(), e.getY());
    }

    /**
     * The Show menu: the query manager's object types and query kinds ({@code qm.objectTypes}, {@code qm.queryTypes}).
     * The menu stays open while items are checked; each change is saved at once.
     */
    private void showTypeMenu(@NotNull Component invoker) {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        QMEventCriteria criteria = QMUtils.createDefaultCriteria(store);
        JPopupMenu menu = new JPopupMenu();
        Map<DBCExecutionPurpose, JCheckBoxMenuItem> purposeItems = new EnumMap<>(DBCExecutionPurpose.class);
        Map<QMObjectType, String> objectLabels = new EnumMap<>(QMObjectType.class);
        objectLabels.put(QMObjectType.session, SettingsMessages.logs_object_session);
        objectLabels.put(QMObjectType.txn, SettingsMessages.logs_object_txn);
        objectLabels.put(QMObjectType.query, SettingsMessages.logs_object_query);
        for (Map.Entry<QMObjectType, String> entry : objectLabels.entrySet()) {
            QMObjectType type = entry.getKey();
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(entry.getValue(), criteria.hasObjectType(type));
            item.putClientProperty(KEEP_MENU_OPEN, true);
            item.addActionListener(e -> {
                setObjectType(store, type, item.isSelected());
                if (type == QMObjectType.query) {
                    purposeItems.values().forEach(purposeItem -> purposeItem.setEnabled(item.isSelected()));
                }
            });
            menu.add(item);
        }
        menu.addSeparator();
        for (DBCExecutionPurpose purpose : DBCExecutionPurpose.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(getPurposeLabel(purpose), criteria.hasQueryType(purpose));
            item.putClientProperty(KEEP_MENU_OPEN, true);
            item.setEnabled(criteria.hasObjectType(QMObjectType.query));
            item.addActionListener(e -> setQueryType(store, purpose, item.isSelected()));
            purposeItems.put(purpose, item);
            menu.add(item);
        }
        menu.show(invoker, 0, invoker.getHeight());
    }

    private static void setObjectType(@NotNull DBPPreferenceStore store, @NotNull QMObjectType type, boolean shown) {
        Collection<QMObjectType> current = QMObjectType.fromString(store.getString(QMConstants.PROP_OBJECT_TYPES));
        Set<QMObjectType> types = current.isEmpty() ? EnumSet.noneOf(QMObjectType.class) : EnumSet.copyOf(current);
        if (shown) {
            types.add(type);
        } else {
            types.remove(type);
        }
        // The change listener (this window, the SQL log file) applies it
        store.setValue(QMConstants.PROP_OBJECT_TYPES, QMObjectType.toString(types));
        PrefUtils.savePreferenceStore(store);
    }

    private static void setQueryType(@NotNull DBPPreferenceStore store, @NotNull DBCExecutionPurpose purpose, boolean shown) {
        Set<DBCExecutionPurpose> purposes = EnumSet.noneOf(DBCExecutionPurpose.class);
        for (String name : CommonUtils.splitString(store.getString(QMConstants.PROP_QUERY_TYPES), ',')) {
            try {
                purposes.add(DBCExecutionPurpose.valueOf(name));
            } catch (IllegalArgumentException e) {
                // Not a query kind (as QMUtils.createDefaultCriteria)
            }
        }
        if (shown) {
            purposes.add(purpose);
        } else {
            purposes.remove(purpose);
        }
        List<String> names = new ArrayList<>();
        purposes.forEach(p -> names.add(p.name()));
        // Same format as the default (ModelPreferences) and Tools > Options > Logs: names separated by ','
        store.setValue(QMConstants.PROP_QUERY_TYPES, String.join(",", names)); //$NON-NLS-1$
        PrefUtils.savePreferenceStore(store);
    }

    /**
     * The query kind as Tools &gt; Options &gt; Logs names it.
     */
    @NotNull
    private static String getPurposeLabel(@NotNull DBCExecutionPurpose purpose) {
        return switch (purpose) {
            case USER -> SettingsMessages.logs_purpose_user;
            case USER_FILTERED -> SettingsMessages.logs_purpose_user_filtered;
            case USER_SCRIPT -> SettingsMessages.logs_purpose_user_script;
            case UTIL -> SettingsMessages.logs_purpose_util;
            case META -> SettingsMessages.logs_purpose_meta;
            case META_DDL -> SettingsMessages.logs_purpose_meta_ddl;
        };
    }

    /**
     * How the transaction ended: committed or rolled back and when, or still open; auto-commit without one.
     */
    @NotNull
    private static String describeTransaction(@Nullable QMMTransactionInfo transaction) {
        if (transaction == null) {
            return QueryManagerMessages.qm_auto_commit;
        }
        if (!transaction.isClosed()) {
            return LifecycleMessages.txn_log_open;
        }
        String time = END_TIME_FORMAT.format(Instant.ofEpochMilli(transaction.getCloseTime()).atZone(ZoneId.systemDefault()));
        return NLS.bind(transaction.isCommitted() ? LifecycleMessages.txn_log_committed : LifecycleMessages.txn_log_rolled_back, time);
    }

    @NotNull
    private static String formatTime(@NotNull DateTimeFormatter format, long time) {
        return format.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    /**
     * A row's identity: the object id, plus the action and time for connection rows (one row per connect and disconnect).
     */
    private record RowKey(long objectId, @Nullable QMEventAction action, long time) {
    }

    private record ConnectionItem(@Nullable String id, @NotNull String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final class Row {
        private final RowKey key;
        // The latest event of the object (the object itself is live and shows its current state)
        private QMEvent event;

        private Row(@NotNull RowKey key) {
            this.key = key;
        }

        @Nullable
        QMMConnectionInfo getConnection() {
            return event.getObject().getConnection();
        }

        /**
         * The transaction of the row: the statement's (none in auto-commit mode), or the ended transaction itself.
         */
        @Nullable
        QMMTransactionInfo getTransaction() {
            return switch (event.getObject()) {
                case QMMStatementExecuteInfo exec -> exec.getSavepoint() == null ? null : exec.getSavepoint().getTransaction();
                case QMMTransactionInfo txn -> txn;
                case QMMTransactionSavepointInfo savepoint -> savepoint.getTransaction();
                default -> null;
            };
        }

        long getTime() {
            return event.getObject() instanceof QMMStatementExecuteInfo exec ? exec.getOpenTime() : key.time() != 0 ? key.time()
                : event.getObject().getCloseTime();
        }

        @NotNull
        String getStatementText() {
            return switch (event.getObject()) {
                case QMMStatementExecuteInfo exec -> CommonUtils.notEmpty(exec.getQueryString());
                case QMMTransactionInfo txn -> txn.isCommitted() ? QueryManagerMessages.qm_commit : QueryManagerMessages.qm_rollback;
                case QMMTransactionSavepointInfo savepoint ->
                    savepoint.isCommitted() ? QueryManagerMessages.qm_commit : QueryManagerMessages.qm_rollback_savepoint;
                case QMMConnectionInfo ignored ->
                    event.getAction() == QMEventAction.BEGIN ? QueryManagerMessages.qm_connected : QueryManagerMessages.qm_disconnected;
                default -> ""; //$NON-NLS-1$
            };
        }

        @NotNull
        String getType() {
            return switch (event.getObject()) {
                case QMMStatementExecuteInfo exec -> getPurposeLabel(exec.getStatement().getPurpose());
                case QMMTransactionInfo ignored -> QueryManagerMessages.qm_type_transaction;
                case QMMTransactionSavepointInfo ignored -> QueryManagerMessages.qm_type_savepoint;
                case QMMConnectionInfo ignored -> QueryManagerMessages.qm_type_connection;
                default -> ""; //$NON-NLS-1$
            };
        }

        @NotNull
        String getDuration() {
            QMMObject object = event.getObject();
            if (object instanceof QMMConnectionInfo || !object.isClosed()) {
                return ""; //$NON-NLS-1$
            }
            if (object instanceof QMMStatementExecuteInfo exec && exec.isFetching()) {
                return ""; //$NON-NLS-1$
            }
            return RuntimeUtils.formatExecutionTime(object.getDuration());
        }

        @NotNull
        String getRows() {
            if (!(event.getObject() instanceof QMMStatementExecuteInfo exec) || !exec.isClosed() || exec.isFetching()) {
                return ""; //$NON-NLS-1$
            }
            if (exec.getUpdateRowCount() >= 0) {
                return NLS.bind(EditorMessages.editor_txn_log_rows_updated, exec.getUpdateRowCount());
            }
            if (exec.getFetchEndTime() > 0) {
                return NLS.bind(EditorMessages.editor_txn_log_rows_fetched, exec.getFetchRowCount());
            }
            return ""; //$NON-NLS-1$
        }

        boolean isRunning() {
            return event.getObject() instanceof QMMStatementExecuteInfo exec && (!exec.isClosed() || exec.isFetching());
        }

        boolean hasError() {
            return event.getObject() instanceof QMMStatementExecuteInfo exec && exec.isClosed() && exec.hasError();
        }

        @NotNull
        String getResult() {
            if (!(event.getObject() instanceof QMMStatementExecuteInfo exec)) {
                return ""; //$NON-NLS-1$
            }
            if (isRunning()) {
                return EditorMessages.editor_txn_log_running;
            }
            if (!exec.hasError()) {
                return EditorMessages.editor_txn_log_ok;
            }
            String message = CommonUtils.notEmpty(exec.getErrorMessage());
            return exec.getErrorCode() == 0 ? message : "[" + exec.getErrorCode() + "] " + message; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @NotNull
        String getTransactionText() {
            if (event.getObject() instanceof QMMConnectionInfo) {
                return ""; //$NON-NLS-1$
            }
            return describeTransaction(getTransaction());
        }

        /**
         * Whether the statement, the connection or session name or the error contains the (lower case) text.
         */
        boolean matches(@NotNull String search) {
            QMMConnectionInfo connection = getConnection();
            return contains(getStatementText(), search) || contains(getResult(), search)
                || connection != null && (contains(connection.getContainerName(), search) || contains(connection.getContextName(), search));
        }

        private static boolean contains(@Nullable String text, @NotNull String search) {
            return text != null && text.toLowerCase(Locale.ROOT).contains(search);
        }

        /**
         * The statement as it ran, with the error as comment lines below it.
         */
        @NotNull
        String getPreviewText() {
            StringBuilder text = new StringBuilder(getStatementText());
            if (hasError()) {
                text.append("\n\n-- ").append(getResult().replace("\n", "\n-- ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return text.toString();
        }
    }

    private static final class RowModel extends AbstractTableModel {
        private final String[] columns = {
            EditorMessages.editor_txn_log_col_time, QueryManagerMessages.qm_column_connection, QueryManagerMessages.qm_column_session,
            EditorMessages.editor_txn_log_col_type, EditorMessages.editor_txn_log_col_sql, EditorMessages.editor_txn_log_col_duration,
            EditorMessages.editor_txn_log_col_rows, EditorMessages.editor_txn_log_col_result, LifecycleMessages.txn_log_col_transaction};
        private List<Row> rows = List.of();

        void setRows(@NotNull List<Row> rows) {
            this.rows = rows;
            // Rows of running statements and open transactions change too, so the whole table is redrawn
            fireTableDataChanged();
        }

        int indexOf(@NotNull Row row) {
            return rows.indexOf(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            Row row = rows.get(rowIndex);
            QMMConnectionInfo connection = row.getConnection();
            return switch (columnIndex) {
                case COL_TIME -> formatTime(TIME_FORMAT, row.getTime());
                case COL_CONNECTION -> connection == null ? "" : CommonUtils.notEmpty(connection.getContainerName()); //$NON-NLS-1$
                case COL_SESSION -> connection == null ? "" : CommonUtils.notEmpty(connection.getContextName()); //$NON-NLS-1$
                case COL_TYPE -> row.getType();
                case COL_STATEMENT -> CommonUtils.truncateString(row.getStatementText().replaceAll("\\s+", " ").trim(), //$NON-NLS-1$ //$NON-NLS-2$
                    MAX_STATEMENT_COLUMN_TEXT);
                case COL_DURATION -> row.getDuration();
                case COL_ROWS -> row.getRows();
                case COL_RESULT -> row.getResult();
                case COL_TRANSACTION -> row.getTransactionText();
                default -> ""; //$NON-NLS-1$
            };
        }
    }

    /**
     * Errors in the error color, running statements in bold, internal work (utility, metadata) in the secondary color;
     * duration and rows right-aligned.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(column == COL_DURATION || column == COL_ROWS ? RIGHT : LEFT);
            Row entry = row < model.rows.size() ? model.rows.get(row) : null;
            if (entry == null) {
                return this;
            }
            setFont(entry.isRunning() ? table.getFont().deriveFont(Font.BOLD) : table.getFont());
            if (!isSelected) {
                Color foreground = null;
                if (entry.hasError()) {
                    foreground = UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
                } else if (entry.event.getObject() instanceof QMMStatementExecuteInfo exec && !exec.getStatement().getPurpose().isUser()) {
                    foreground = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
                }
                setForeground(foreground != null ? foreground : table.getForeground());
            }
            setToolTipText(switch (column) {
                case COL_TIME -> formatTime(FULL_TIME_FORMAT, entry.getTime());
                case COL_STATEMENT, COL_RESULT -> {
                    String text = column == COL_STATEMENT ? entry.getStatementText() : entry.getResult();
                    yield text.isEmpty() ? null : CommonUtils.truncateString(text, MAX_TOOLTIP_TEXT);
                }
                default -> null;
            });
            return this;
        }
    }
}
