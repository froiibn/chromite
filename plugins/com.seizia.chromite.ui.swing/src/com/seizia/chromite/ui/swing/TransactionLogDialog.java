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
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import com.seizia.chromite.model.qm.meta.QMMTransactionInfo;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.utils.RuntimeUtils;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transaction Log: the statements of the current transaction of an execution context (Query &gt; Transaction Log, the
 * query status line's open transaction menu, the open transactions list).
 *
 * 설계 (SWT {@code TransactionLogDialog}의 Swing판, SWT 코드를 옮기지 않고 동작만 맞춤):
 * <ul>
 *     <li>내용은 쿼리 관리자(QM)가 메모리에 기록한 그 문맥의 현재 트랜잭션 문장이다({@link QMUtils#getTransactionStatements}).
 *     상태 줄의 "문장 N개"와 유휴 트랜잭션 감시가 세는 문장과 같은 기준이다(메타데이터 읽기·내부 작업 제외, SELECT 포함).
 *     SWT는 기본으로 데이터를 바꾸는 문장만 보였지만 Chromite는 수동 커밋에서 SELECT도 트랜잭션을 연 것으로 보므로(잠금·스냅숏) 모두 보인다.</li>
 *     <li>열: 시각(문장 시작), 종류(SQL의 첫 키워드), SQL(한 줄로, 전체는 아래 미리 보기), 걸린 시간, 행(바꾼 행 또는 읽은 행), 결과(성공·오류·실행 중).</li>
 *     <li>모달이 아니고 문맥마다 하나다. 쿼리 관리자 기록은 메모리에 있어 1초마다 다시 읽어도 부담이 없다. 그래서 다른 쿼리 창에서
 *     실행하거나 커밋·롤백해도 창이 따라온다. 고른 문장은 같은 문장이면 그대로 둔다.</li>
 *     <li>커밋·롤백 단추는 두지 않는다(SWT와 같음). 끝내기는 Query 메뉴, 상태 줄, 열린 트랜잭션 목록이 같은 경로로 한다.</li>
 *     <li>지난 트랜잭션 보이기(SWT "Show previous transactions"): 켜면 그 문맥의 최근 트랜잭션 {@value #MAX_PREVIOUS_TRANSACTIONS}개의 문장을 같은 기준으로
 *     보이고({@link QMUtils#getRecentTransactionStatements}), 트랜잭션 열에 각 문장의 트랜잭션이 어떻게 끝났는지(커밋·롤백과 그 시각, 아직 열림)를 적는다.
 *     자동 커밋으로 실행한 문장은 트랜잭션이 없어 넣지 않는다. 쿼리 관리자가 메모리에 둔 기록만이라 다시 연결하거나 다시 시작하면 그 전 기록은 없다.
 *     모든 문장(메타데이터 포함) 보이기는 옮기지 않았다(docs/chromite/transactions.md "남은 일").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/transactions.md)
 * - 2026-11-08 지난 트랜잭션 보이기와 트랜잭션 열 (docs/chromite/connection-lifecycle.md "트랜잭션 로그의 지난 트랜잭션")
 */
final class TransactionLogDialog extends JDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final DateTimeFormatter START_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    private static final int MAX_SQL_COLUMN_TEXT = 300;
    private static final int MAX_PREVIOUS_TRANSACTIONS = 20;
    // How far back the session's statements are looked through for previous transactions
    private static final int MAX_PREVIOUS_STATEMENTS = 5000;
    // Time, type, statement, duration, rows, result, and the transaction when previous ones are shown
    private static final int[] COLUMN_WIDTHS = {100, 80, 520, 90, 110, 160, 170};

    // EDT only: the open log of each context
    private static final Map<DBCExecutionContext, TransactionLogDialog> dialogs = new IdentityHashMap<>();

    private final DBCExecutionContext context;
    private final JLabel summaryLabel = new JLabel();
    private final JCheckBox previousBox = new JCheckBox(LifecycleMessages.txn_log_show_previous);
    private final LogModel model = new LogModel();
    private final JTable table = new JTable(model);
    private final SqlEditorPanel.SqlTextArea preview = new SqlEditorPanel.SqlTextArea();
    private final Timer timer = new Timer(1000, e -> reload());

    private TransactionLogDialog(@Nullable Window owner, @NotNull DBCExecutionContext context) {
        super(owner, NLS.bind(EditorMessages.editor_txn_log_title, context.getDataSource().getContainer().getName()), ModalityType.MODELESS);
        this.context = context;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        String windows = SwingIdleTransactionService.describeQueryWindows(context);
        JLabel sessionLabel = new JLabel(NLS.bind(EditorMessages.editor_txn_log_session, windows != null ? windows : context.getContextName()));
        previousBox.setToolTipText(LifecycleMessages.txn_log_show_previous_tip);
        previousBox.addActionListener(e -> {
            model.setShowTransaction(previousBox.isSelected());
            applyColumnWidths();
            reload();
        });
        JPanel top = new JPanel(new GridLayout(3, 1, 0, 4));
        top.add(sessionLabel);
        top.add(summaryLabel);
        top.add(previousBox);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        applyColumnWidths();
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        preview.setEditable(false);
        preview.setHighlightCurrentLine(false);
        // Highlighting in the connection's dialect
        preview.setDataSourceContainer(context.getDataSource().getContainer());
        JScrollPane previewScroll = new JScrollPane(preview);
        preview.applyTheme();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), previewScroll);
        split.setResizeWeight(0.7);
        split.setContinuousLayout(true);

        JButton closeButton = new JButton(EditorMessages.editor_common_close);
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload();
        timer.start();
        setSize(UIScale.scale(1000), UIScale.scale(520));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the log of the context (one window per context; an open one comes to the front).
     */
    static void open(@NotNull Component parent, @NotNull DBCExecutionContext context) {
        TransactionLogDialog dialog = dialogs.get(context);
        if (dialog == null || !dialog.isDisplayable()) {
            dialog = new TransactionLogDialog(parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent), context);
            dialogs.put(context, dialog);
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    @Override
    public void dispose() {
        timer.stop();
        dialogs.remove(context, this);
        super.dispose();
    }

    /**
     * Time, type, duration, rows, result and transaction keep room for their text; the statement takes the rest.
     */
    private void applyColumnWidths() {
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(COLUMN_WIDTHS[i]));
        }
    }

    private void reload() {
        QMTransactionState state = QMUtils.getTransactionState(context);
        if (!state.isTransactionMode()) {
            summaryLabel.setText(EditorMessages.editor_txn_log_auto_commit);
        } else if (state.isOpen()) {
            summaryLabel.setText(NLS.bind(EditorMessages.editor_txn_log_open, new Object[]{
                START_FORMAT.format(Instant.ofEpochMilli(state.getTransactionStartTime()).atZone(ZoneId.systemDefault())),
                state.getExecuteCount(), state.getUpdateCount()}));
        } else {
            summaryLabel.setText(EditorMessages.editor_txn_log_none);
        }
        QMMStatementExecuteInfo selected = getSelected();
        model.setStatements(previousBox.isSelected()
            ? QMUtils.getRecentTransactionStatements(context, MAX_PREVIOUS_TRANSACTIONS, MAX_PREVIOUS_STATEMENTS)
            : QMUtils.getTransactionStatements(context));
        int index = selected == null ? -1 : model.indexOf(selected);
        if (index >= 0) {
            table.getSelectionModel().setSelectionInterval(index, index);
        } else {
            showSelected();
        }
    }

    @Nullable
    private QMMStatementExecuteInfo getSelected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.statements.get(row);
    }

    private void showSelected() {
        QMMStatementExecuteInfo selected = getSelected();
        String text = selected == null ? "" : CommonUtils.notEmpty(selected.getQueryString());
        if (!text.equals(preview.getText())) {
            preview.setText(text);
            preview.setCaretPosition(0);
        }
    }

    private final class LogModel extends AbstractTableModel {
        private final String[] columns = {
            EditorMessages.editor_txn_log_col_time, EditorMessages.editor_txn_log_col_type, EditorMessages.editor_txn_log_col_sql,
            EditorMessages.editor_txn_log_col_duration, EditorMessages.editor_txn_log_col_rows, EditorMessages.editor_txn_log_col_result,
            LifecycleMessages.txn_log_col_transaction};
        private List<QMMStatementExecuteInfo> statements = new ArrayList<>();
        private boolean showTransaction;

        void setShowTransaction(boolean showTransaction) {
            this.showTransaction = showTransaction;
            fireTableStructureChanged();
        }

        void setStatements(@NotNull List<QMMStatementExecuteInfo> statements) {
            this.statements = statements;
            // Rows of running statements change (duration, rows, result), so the whole table is redrawn
            fireTableDataChanged();
        }

        int indexOf(@NotNull QMMStatementExecuteInfo statement) {
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) == statement) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return statements.size();
        }

        @Override
        public int getColumnCount() {
            // The transaction column only with the previous transactions
            return showTransaction ? columns.length : columns.length - 1;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            QMMStatementExecuteInfo exec = statements.get(rowIndex);
            String query = CommonUtils.notEmpty(exec.getQueryString());
            return switch (columnIndex) {
                case 0 -> TIME_FORMAT.format(Instant.ofEpochMilli(exec.getOpenTime()).atZone(ZoneId.systemDefault()));
                case 1 -> SQLUtils.getFirstKeyword(context.getDataSource().getSQLDialect(), query).toUpperCase(Locale.ROOT);
                case 2 -> CommonUtils.truncateString(query.replaceAll("\\s+", " ").trim(), MAX_SQL_COLUMN_TEXT); //$NON-NLS-1$ //$NON-NLS-2$
                case 3 -> exec.isClosed() ? RuntimeUtils.formatExecutionTime(exec.getDuration()) : ""; //$NON-NLS-1$
                case 4 -> describeRows(exec);
                case 6 -> describeTransaction(exec);
                default -> !exec.isClosed() ? EditorMessages.editor_txn_log_running
                    : exec.hasError() ? CommonUtils.notEmpty(exec.getErrorMessage()) : EditorMessages.editor_txn_log_ok;
            };
        }

        /**
         * How the statement's transaction ended: committed or rolled back and when, or still open.
         */
        @NotNull
        private static String describeTransaction(@NotNull QMMStatementExecuteInfo exec) {
            QMMTransactionInfo transaction = exec.getSavepoint() == null ? null : exec.getSavepoint().getTransaction();
            if (transaction == null || !transaction.isClosed()) {
                return LifecycleMessages.txn_log_open;
            }
            String time = START_FORMAT.format(Instant.ofEpochMilli(transaction.getCloseTime()).atZone(ZoneId.systemDefault()));
            return NLS.bind(transaction.isCommitted() ? LifecycleMessages.txn_log_committed : LifecycleMessages.txn_log_rolled_back, time);
        }

        @NotNull
        private static String describeRows(@NotNull QMMStatementExecuteInfo exec) {
            if (exec.getUpdateRowCount() >= 0) {
                return NLS.bind(EditorMessages.editor_txn_log_rows_updated, exec.getUpdateRowCount());
            }
            if (exec.getFetchEndTime() > 0) {
                return NLS.bind(EditorMessages.editor_txn_log_rows_fetched, exec.getFetchRowCount());
            }
            return ""; //$NON-NLS-1$
        }
    }
}
