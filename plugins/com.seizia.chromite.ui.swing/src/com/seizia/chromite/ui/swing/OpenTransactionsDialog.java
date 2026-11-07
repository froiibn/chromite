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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools &gt; Open Transactions: the execution contexts of all connections that hold an open transaction, with Commit,
 * Roll Back and the transaction log of the selected one.
 *
 * 설계 (SWT {@code PendingTransactionsDialog}의 Swing판, 동작만 맞춤):
 * <ul>
 *     <li>대상: 작업 공간의 모든 프로젝트에서 연결된 연결의 모든 실행 문맥 중 열린 트랜잭션이 있는 것({@link QMTransactionState#isOpen()}:
 *     수동 커밋에서 마지막 커밋·롤백 뒤 사용자 문장이 있음). 쿼리 창 상태 줄, 유휴 트랜잭션 감시와 같은 기준이라 세 곳이 같은 트랜잭션을 보인다.
 *     SWT는 데이터 변경 문장이 없는 문맥도 "Show all connections"로 보였지만 그 문맥은 끝낼 것이 없으므로 두지 않았다.</li>
 *     <li>열: 연결, 데이터베이스(문맥의 기본 카탈로그·스키마, 없으면 연결 설정의 DB), 세션(그 문맥을 쓰는 쿼리 창, 아니면 문맥 이름), 시작 시각,
 *     문장 수(데이터 변경 수), 유휴 시간(마지막 문장 끝부터, 실행 중이면 "실행 중").</li>
 *     <li>Commit / Roll Back은 고른 행이 보여 준 트랜잭션만 끝낸다({@link SwingIdleTransactionService#endTransaction}: 상태 줄·Query 메뉴·유휴 알림과 같은 경로).
 *     그 사이 다른 트랜잭션이 시작되었거나 문장이 실행 중이면 아무것도 하지 않고 알린다. 사용자가 행을 골라 누르는 동작이므로 따로 묻지 않는다
 *     (상태 줄 메뉴와 같은 확인 규칙). 실행 중인 행은 단추가 꺼진다.</li>
 *     <li>모달이 아니고 하나만 연다. 쿼리 관리자 기록만 읽으므로(드라이버 호출 없음) 1초마다 다시 채운다. 고른 행은 같은 문맥이면 그대로 둔다.</li>
 *     <li>도구 창이 아니라 대화상자다: 가끔 여는 점검 화면이고, 도킹 배치 저장(ToolWindowDocking)을 늘리지 않는다.</li>
 *     <li>Chromite Agent·MCP 도구의 격리 세션은 쿼리 관리자를 거치지 않아 보이지 않는다(설명 문구에 적음). 끝낼 때는 {@code ExitTransactionCheck}가 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/transactions.md)
 */
final class OpenTransactionsDialog extends JDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private record Entry(@NotNull DBCExecutionContext context, @NotNull QMTransactionState state, @NotNull String database,
                         @NotNull String session) {
    }

    @Nullable
    private static OpenTransactionsDialog instance;

    private final EntriesModel model = new EntriesModel();
    private final JTable table = new JTable(model);
    private final JLabel countLabel = new JLabel();
    private final JButton commitButton = new JButton(EditorMessages.editor_status_txn_commit, UiIcons.get("commit")); //$NON-NLS-1$
    private final JButton rollbackButton = new JButton(EditorMessages.editor_status_txn_rollback, UiIcons.get("rollback")); //$NON-NLS-1$
    private final JButton logButton = new JButton(EditorMessages.editor_status_txn_log);
    private final Timer timer = new Timer(1000, e -> reload());

    private OpenTransactionsDialog(@NotNull Frame owner) {
        super(owner, EditorMessages.editor_txn_list_title, false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JLabel description = new JLabel(EditorMessages.editor_txn_list_description);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {180, 150, 200, 80, 150, 90};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(widths[i]));
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });

        commitButton.addActionListener(e -> endSelected(true));
        rollbackButton.addActionListener(e -> endSelected(false));
        logButton.addActionListener(e -> {
            Entry entry = getSelected();
            if (entry != null) {
                TransactionLogDialog.open(this, entry.context());
            }
        });
        JButton closeButton = new JButton(EditorMessages.editor_common_close);
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(commitButton);
        buttons.add(rollbackButton);
        buttons.add(logButton);
        buttons.add(closeButton);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(countLabel, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(description, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload();
        timer.start();
        setSize(UIScale.scale(900), UIScale.scale(380));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the window (one per application).
     */
    static void open(@NotNull Frame owner) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new OpenTransactionsDialog(owner);
        }
        instance.setVisible(true);
        instance.toFront();
    }

    @Override
    public void dispose() {
        timer.stop();
        if (instance == this) {
            instance = null;
        }
        super.dispose();
    }

    private void reload() {
        Entry selected = getSelected();
        List<Entry> entries = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            if (project.getDataSourceRegistry() == null) {
                continue;
            }
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                DBPDataSource dataSource = container.isConnected() ? container.getDataSource() : null;
                if (dataSource == null) {
                    continue;
                }
                for (DBSInstance instance : dataSource.getAvailableInstances()) {
                    for (DBCExecutionContext context : instance.getAllContexts()) {
                        QMTransactionState state = QMUtils.getTransactionState(context);
                        if (state.isOpen()) {
                            String windows = SwingIdleTransactionService.describeQueryWindows(context);
                            entries.add(new Entry(context, state, describeDatabase(context), windows != null ? windows : context.getContextName()));
                        }
                    }
                }
            }
        }
        model.setEntries(entries);
        int index = -1;
        for (int i = 0; selected != null && i < entries.size(); i++) {
            if (entries.get(i).context() == selected.context()) {
                index = i;
            }
        }
        if (index >= 0) {
            table.getSelectionModel().setSelectionInterval(index, index);
        } else if (!entries.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
        countLabel.setText(entries.isEmpty() ? EditorMessages.editor_txn_list_empty : ""); //$NON-NLS-1$
        updateButtons();
    }

    /**
     * The context's current catalog and schema (cached by the context, no driver call), else the connection's database.
     */
    @NotNull
    private static String describeDatabase(@NotNull DBCExecutionContext context) {
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        DBSCatalog catalog = defaults == null ? null : defaults.getDefaultCatalog();
        DBSSchema schema = defaults == null ? null : defaults.getDefaultSchema();
        if (catalog != null || schema != null) {
            return catalog == null ? schema.getName() : schema == null ? catalog.getName() : catalog.getName() + "." + schema.getName(); //$NON-NLS-1$
        }
        return CommonUtils.notEmpty(context.getDataSource().getContainer().getConnectionConfiguration().getDatabaseName());
    }

    @Nullable
    private Entry getSelected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.entries.size() ? null : model.entries.get(row);
    }

    private void updateButtons() {
        Entry entry = getSelected();
        boolean canEnd = entry != null && !entry.state().isExecuting();
        commitButton.setEnabled(canEnd);
        rollbackButton.setEnabled(canEnd);
        logButton.setEnabled(entry != null);
    }

    /**
     * Commits or rolls back the transaction the selected row shows, as the status line's menu does.
     */
    private void endSelected(boolean commit) {
        Entry entry = getSelected();
        if (entry != null) {
            SwingIdleTransactionService.endTransaction(this, entry.context(), entry.state().getTransactionStartTime(), commit, this::reload);
        }
    }

    private static final class EntriesModel extends AbstractTableModel {
        private final String[] columns = {
            EditorMessages.editor_txn_list_col_connection, EditorMessages.editor_txn_list_col_database, EditorMessages.editor_txn_list_col_session,
            EditorMessages.editor_txn_list_col_started, EditorMessages.editor_txn_list_col_statements, EditorMessages.editor_txn_list_col_idle};
        private List<Entry> entries = new ArrayList<>();

        void setEntries(@NotNull List<Entry> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return entries.size();
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
            Entry entry = entries.get(rowIndex);
            QMTransactionState state = entry.state();
            return switch (columnIndex) {
                case 0 -> entry.context().getDataSource().getContainer().getName();
                case 1 -> entry.database();
                case 2 -> entry.session();
                case 3 -> TIME_FORMAT.format(Instant.ofEpochMilli(state.getTransactionStartTime()).atZone(ZoneId.systemDefault()));
                case 4 -> NLS.bind(EditorMessages.editor_txn_list_statements, state.getExecuteCount(), state.getUpdateCount());
                default -> state.isExecuting() ? EditorMessages.editor_txn_list_running
                    : SwingIdleTransactionService.formatDuration(System.currentTimeMillis() - state.getLastExecuteTime());
            };
        }
    }
}
