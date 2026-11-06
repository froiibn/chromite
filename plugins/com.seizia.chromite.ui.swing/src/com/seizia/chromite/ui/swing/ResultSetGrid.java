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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.resultset.DataUpdaterJob;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Map;

/**
 * 결과 집합 하나를 보여 주는 표(SSMS 결과 그리드 하나). 단일 테이블 결과는 편집하고 저장할 수 있다(SSMS "Edit Top 200 Rows").
 *
 * 설계: 값은 Job 스레드의 {@link ResultSetGridReceiver}가 결과 집합이 열려 있는 동안 표시 문자열로 바꿔 넘긴다
 * ({@link Data}). 그래서 EDT에서는 모델 계층(값 처리기, LOB 읽기)을 부르지 않고, 결과 집합을 닫은 뒤에도 값을 그대로 보여 줄 수 있다.
 * NULL은 표시 문자열과 구분되는 표지({@link #NULL_VALUE})로 두어 "NULL"이라는 문자열 값과 다르게 그린다(SSMS처럼 "NULL", 보조 글자색 text.secondary).
 * 숫자 열은 오른쪽 정렬한다. 열 너비는 머리글과 앞쪽 행들의 글자 너비로 정하고, 표는 자동 맞춤을 끄고 가로 스크롤한다.
 * 왼쪽에 SSMS처럼 행 번호 열을 둔다(행 머리글, 누르면 그 행 선택, 새 행은 {@code *}).
 * 복사(Ctrl+C)는 {@link JTable}의 기본 전송 처리기가 선택한 칸의 표시 문자열을 탭으로 나눠 복사한다.
 * 오른쪽 클릭 메뉴(SSMS 결과 그리드와 같은 항목): Copy, Copy with Headers(Ctrl+Shift+C), Export to CSV…, Export to Excel (XLSX)…({@link ResultSetExport}).
 * Export Data…는 쿼리를 다시 실행해 모든 행을 모델의 모든 형식으로 내보내는 마법사({@link DataExportWizard})다. 다시 실행할 수 있는 결과
 * (쿼리의 첫 결과 집합이면서 문장 하나뿐인, 바꾸지 않는 SELECT, {@link Fetched#exportQuery()}, {@link DataExportWizard#canExportQuery})에서만 켜진다. CSV·XLSX 항목은 그대로 표에 보이는 행(편집 중인 값 포함)을 쓴다.
 * 오른쪽 클릭한 칸이 선택 밖이면 그 칸을 선택한 뒤 메뉴를 연다.
 * <p>
 * 편집(G단계): 수신기가 편집할 수 있다고 정한 결과({@link Fetched#editModel()}, 판단은 {@link ResultSetEditModel#getReadOnlyReason})는
 * 표 위 줄에 Save / Cancel 단추와 상태(바뀐 수, "Saving…")를 두고, 읽기 전용 결과는 같은 줄에 이유("read-only: no unique key")를 쓴다.
 * <ul>
 *     <li>셀 편집은 두 번 누르기나 F2로 시작한다. 글자 입력으로는 시작하지 않는다({@code JTable.autoStartsEdit} 끔): 그러지 않으면 표가 처리하지 않은
 *     단축키(F5, Ctrl+N 등)가 메뉴로 가기 전에 셀 편집을 시작한다. 편집기 글자는 값 처리기의 편집 형식이고, 끝낼 때 값 처리기로 값을 만든다.
 *     값이 맞지 않으면 편집기에 빨간 테두리를 두르고 이유를 상태 줄에 쓴 뒤 편집을 계속한다. 글자를 바꾸지 않았으면 값도 바꾸지 않는다(NULL이 빈 문자열이 되지 않게).</li>
 *     <li>NULL은 Ctrl+0 또는 메뉴 "Set NULL", 새 행은 Alt+Insert 또는 메뉴 "Insert Row"(선택한 행 아래), 행 삭제는 Delete 또는 메뉴 "Delete Row"
 *     (저장 전까지 취소선으로 남는다). 바뀐 셀·새 행·지울 행은 배경색으로 구분한다.</li>
 *     <li>저장은 Ctrl+S(포커스가 이 표나 셀 편집기에 있을 때. 편집할 수 있는 표에서는 바뀐 것이 없어도 SQL 파일 저장으로 넘기지 않는다), Save 단추, 메뉴 "Save Changes"다.
 *     {@link ResultSetEditPersister}가 백그라운드 Job에서 한 트랜잭션으로 저장하고, 실패하면 오류를 Messages에 쓰고 바뀐 내용은 그대로 둔다.
 *     성공하면 저장한 행을 키로 다시 읽는다. Cancel은 바뀐 내용을 모두 되돌린다. 저장 중에는 편집을 막는다.</li>
 *     <li>복사와 내보내기는 지금 표에 보이는 내용({@link #getCurrentData})을 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 E단계: 오른쪽 클릭 메뉴(복사, 머리글 포함 복사, CSV 내보내기) (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: XLSX 내보내기 메뉴, {@link Data}에 원래 값(숫자·불리언·날짜)과 열 바인딩 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 셀 편집·NULL·행 추가·삭제, 저장·취소, 편집 상태 줄 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: DataGrip 그리드 모양(편집기 글꼴, 세로·가로 격자선, 왼쪽 정렬 머리글, 행 번호 열, NULL 보조 글자색,
 *   편집 색을 테마 키로) (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 열 너비의 여백을 고정값(18) 대신 Look and Feel의 머리글 렌더러·셀 테두리 여백으로 계산, 행 번호를 눌러 행을 고를 때
 *   표가 가로로 스크롤되지 않게 함 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 데이터 전송: "Export Data…" 메뉴, {@link Fetched#exportQuery()} (docs/chromite/swing-data-transfer.md)
 */
final class ResultSetGrid extends JPanel {

    /**
     * Marker of a NULL value in {@link Data#rows()}; its text is what Ctrl+C copies.
     */
    static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "NULL"; //$NON-NLS-1$
        }
    };

    private static final int MIN_COLUMN_WIDTH = 40;
    private static final int MAX_COLUMN_WIDTH = 320;
    private static final int MEASURED_ROWS = 200;

    /**
     * Fetched result set.
     *
     * @param columns   column labels
     * @param numeric   whether a column holds numbers (right-aligned)
     * @param rows      display strings or {@link #NULL_VALUE}
     * @param values    per row, the original numbers, booleans and dates by column (null elsewhere), or null when the
     *                  row has none; used by the XLSX export for typed cells
     * @param bindings  column bindings of the result set (labels, data kinds, value handlers for the export)
     * @param truncated fetching stopped at the maximum row count
     */
    record Data(
        @NotNull String[] columns,
        @NotNull boolean[] numeric,
        @NotNull List<Object[]> rows,
        @NotNull List<Object[]> values,
        @NotNull DBDAttributeBinding[] bindings,
        boolean truncated
    ) {
    }

    /**
     * What a receiver delivers: the data, and either the edit model of an editable result or why it is read-only.
     *
     * @param exportQuery the query container that reads this result again on its own (the first result set of a
     *                    query; "Export Data…" runs it again for all rows), or null
     */
    record Fetched(
        @NotNull Data data,
        @Nullable ResultSetEditModel editModel,
        @Nullable String readOnlyReason,
        @Nullable DBSDataContainer exportQuery
    ) {
        Fetched(@NotNull Data data, @Nullable ResultSetEditModel editModel, @Nullable String readOnlyReason) {
            this(data, editModel, readOnlyReason, null);
        }

        @NotNull
        Fetched withExportQuery(@NotNull DBSDataContainer query) {
            return new Fetched(data, editModel, readOnlyReason, query);
        }
    }

    /**
     * The query window side of a grid: where save results and errors go.
     */
    interface Host {
        void appendMessage(@NotNull String text);

        void appendError(@NotNull String text);

        /**
         * Brings the Messages tab to the front (a save failed).
         */
        void showMessages();

        /**
         * Pending changes or the saving state changed.
         */
        void editStateChanged();
    }

    /**
     * Value of a finished cell edit (the parsed value may be null).
     */
    private record EditedValue(@Nullable Object value) {
    }

    private final Data data;
    @Nullable
    private final DBSDataContainer exportQuery;
    @Nullable
    private final ResultSetEditModel editModel;
    private final Host host;
    private final JTable table;
    private final GridModel gridModel;
    private final JTable rowHeader;
    private final JLabel statusLabel = new JLabel();
    private final List<Action> editActions = new ArrayList<>();
    private Action saveAction;
    private Action cancelChangesAction;
    private Action setNullAction;
    private Action insertRowAction;
    private Action deleteRowsAction;
    private Action editCellAction;
    private Action copyAction;
    private Action copyWithHeadersAction;
    @Nullable
    private String transientStatus;

    ResultSetGrid(@NotNull Fetched fetched, @NotNull Host host) {
        super(new BorderLayout());
        this.data = fetched.data();
        this.exportQuery = fetched.exportQuery();
        this.editModel = fetched.editModel();
        this.host = host;
        gridModel = new GridModel();
        table = new JTable(gridModel) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintHorizontalLines(this, g);
            }
        };
        // DataGrip grid: font.code, vertical lines in grid.line, horizontal lines in the lighter grid.lineSubtle
        // (one JTable grid color can't do both), column names left-aligned
        table.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(1, 0));
        if (table.getTableHeader().getDefaultRenderer() instanceof JLabel headerRenderer) {
            headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CellRenderer(data.numeric()));
        // Typing does not start editing: unbound shortcuts (F5, Ctrl+N…) would otherwise open a cell editor first
        table.putClientProperty("JTable.autoStartsEdit", Boolean.FALSE); //$NON-NLS-1$
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        if (editModel != null) {
            table.setDefaultEditor(Object.class, new TextCellEditor());
        }

        JScrollPane scrollPane = new JScrollPane(table);
        rowHeader = createRowHeader();
        scrollPane.setRowHeaderView(rowHeader);
        add(createStatusStrip(fetched.readOnlyReason()), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        fitColumnWidths(table, data);
        installContextMenu();
        updateState();
    }

    //////////////////////////////////////////////////////////
    // Edit state

    boolean isDirty() {
        return editModel != null && editModel.isDirty();
    }

    boolean isSaving() {
        return editModel != null && editModel.isSaving();
    }

    int getChangeCount() {
        return editModel == null ? 0 : editModel.getChangeCount();
    }

    /**
     * The rows as the grid shows them now (with pending changes), for copying and exporting.
     */
    @NotNull
    Data getCurrentData() {
        if (editModel == null) {
            return data;
        }
        List<Object[]> rows = new ArrayList<>(editModel.getRowCount());
        List<Object[]> values = new ArrayList<>(editModel.getRowCount());
        for (int i = 0; i < editModel.getRowCount(); i++) {
            ResultSetEditModel.Row row = editModel.getRow(i);
            rows.add(row.display.clone());
            values.add(ResultSetGridReceiver.keepValues(row.values));
        }
        return new Data(data.columns(), data.numeric(), rows, values, data.bindings(), data.truncated());
    }

    /**
     * Status strip above the grid: Save / Cancel and the pending changes of an editable result, or why it is read-only.
     */
    @NotNull
    private JComponent createStatusStrip(@Nullable String readOnlyReason) {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        strip.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        if (editModel != null) {
            saveAction = createAction("Save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), () -> save(false));
            saveAction.putValue(Action.SHORT_DESCRIPTION, "Save the changes of this grid to the database (Ctrl+S)");
            cancelChangesAction = createAction("Cancel", null, this::cancelChanges);
            cancelChangesAction.putValue(Action.SHORT_DESCRIPTION, "Discard the changes of this grid");
            strip.add(createStripButton(saveAction));
            strip.add(createStripButton(cancelChangesAction));
        } else {
            statusLabel.setText("read-only: " + CommonUtils.notEmpty(readOnlyReason));
        }
        strip.add(statusLabel);
        return strip;
    }

    @NotNull
    private static JButton createStripButton(@NotNull Action action) {
        JButton button = new JButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    /**
     * Updates the actions and the status strip after a change of the data or of the saving state.
     */
    private void updateState() {
        boolean hasRows = gridModel.getRowCount() > 0;
        copyAction.setEnabled(hasRows);
        copyWithHeadersAction.setEnabled(hasRows);
        if (editModel == null) {
            return;
        }
        boolean saving = editModel.isSaving();
        boolean dirty = editModel.isDirty();
        saveAction.setEnabled(dirty && !saving);
        cancelChangesAction.setEnabled(dirty && !saving);
        editCellAction.setEnabled(!saving);
        setNullAction.setEnabled(!saving);
        insertRowAction.setEnabled(!saving && editModel.isInsertSupported());
        deleteRowsAction.setEnabled(!saving && hasRows && editModel.isDeleteSupported());
        String text;
        if (saving) {
            text = "Saving…";
        } else if (transientStatus != null) {
            text = transientStatus;
        } else {
            int changes = editModel.getChangeCount();
            String table = editModel.getSingleSource() != null ? editModel.getSingleSource().getName() : "";
            text = "Editable (" + table + ") · " + (changes == 0 ? "no pending changes"
                : changes + (changes == 1 ? " pending change" : " pending changes"));
        }
        statusLabel.setText(text);
        host.editStateChanged();
    }

    private void dataChanged() {
        transientStatus = null;
        gridModel.fireTableDataChanged();
        rowHeader.revalidate();
        rowHeader.repaint();
        updateState();
    }

    //////////////////////////////////////////////////////////
    // Editing (EDT)

    private void editSelectedCell() {
        int row = table.getSelectedRow();
        int column = table.getSelectedColumn();
        if (row >= 0 && column >= 0 && table.editCellAt(row, column)) {
            Component editor = table.getEditorComponent();
            if (editor != null) {
                editor.requestFocusInWindow();
            }
        }
    }

    private void setSelectedToNull() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        boolean changed = false;
        for (int row : table.getSelectedRows()) {
            for (int column : table.getSelectedColumns()) {
                int modelColumn = table.convertColumnIndexToModel(column);
                if (editModel.isCellEditable(row, modelColumn)) {
                    editModel.setValue(row, modelColumn, null);
                    changed = true;
                }
            }
        }
        if (changed) {
            repaintKeepingSelection();
        }
    }

    private void insertRow() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int selected = table.getSelectedRow();
        int index = selected < 0 ? editModel.getRowCount() : table.getSelectionModel().getMaxSelectionIndex() + 1;
        int position;
        try {
            position = editModel.addRow(index);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Insert Row", "Can't add a new row", e);
            return;
        }
        dataChanged();
        table.changeSelection(position, 0, false, false);
        table.requestFocusInWindow();
    }

    private void deleteSelectedRows() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        int lead = rows[0];
        int column = Math.max(table.getSelectedColumn(), 0);
        editModel.deleteRows(rows);
        dataChanged();
        if (gridModel.getRowCount() > 0) {
            table.changeSelection(Math.min(lead, gridModel.getRowCount() - 1), column, false, false);
        }
    }

    private void cancelChanges() {
        if (editModel == null || editModel.isSaving()) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        editModel.revertAll();
        dataChanged();
    }

    /**
     * Finishes a cell edit in progress.
     *
     * @return false if the editor holds an invalid value (it stays open)
     */
    private boolean stopEditing() {
        return !table.isEditing() || table.getCellEditor().stopCellEditing();
    }

    private void repaintKeepingSelection() {
        transientStatus = null;
        table.repaint();
        rowHeader.repaint();
        updateState();
    }

    //////////////////////////////////////////////////////////
    // Save (EDT)

    /**
     * Saves the pending changes in a background job.
     *
     * @param wait wait for the save in a progress dialog (closing a query window or the application)
     * @return whether the changes were saved; without waiting, whether the save started
     */
    boolean save(boolean wait) {
        if (editModel == null || editModel.isSaving() || !stopEditing()) {
            return false;
        }
        if (!editModel.isDirty()) {
            return true;
        }
        ResultSetEditPersister persister = new ResultSetEditPersister(editModel);
        String tableName = editModel.getSingleSource() != null ? editModel.getSingleSource().getName() : "results";
        try {
            persister.checkEntityIdentifiers();
        } catch (DBException e) {
            reportSaveError(tableName, e, ResultSetEditPersister.TransactionMode.NONE, false);
            return false;
        }
        long startTime = System.currentTimeMillis();
        editModel.setSaving(true);
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        updateState();
        DataUpdaterJob job = persister.createSaveJob(outcome -> finishSave(tableName, outcome, startTime));
        if (wait) {
            ProgressDialog.waitForJob("Save results", job);
            return job.getError() == null;
        }
        job.schedule();
        return true;
    }

    private void finishSave(@NotNull String tableName, @NotNull ResultSetEditPersister.Outcome outcome, long startTime) {
        editModel.setSaving(false);
        Throwable error = outcome.error();
        List<ResultSetEditModel.Row> refreshRows = new ArrayList<>();
        for (ResultSetEditModel.Row row : outcome.executedRows()) {
            if (row.state != ResultSetEditModel.RowState.REMOVED) {
                refreshRows.add(row);
            }
        }
        int selectedRow = table.getSelectedRow();
        int selectedColumn = table.getSelectedColumn();
        editModel.acceptSaved(outcome.executedRows());
        dataChanged();
        if (selectedRow >= 0 && selectedColumn >= 0 && gridModel.getRowCount() > 0) {
            // A data change clears the selection; keep the user's place
            table.changeSelection(Math.min(selectedRow, gridModel.getRowCount() - 1), selectedColumn, false, false);
        }
        if (error == null) {
            host.appendMessage("Saved changes to " + tableName + ": " + outcome.inserted() + " inserted, "
                + outcome.updated() + " updated, " + outcome.deleted() + " deleted.");
            if (outcome.zeroRowChanges() > 0) {
                host.appendMessage("Warning: " + outcome.zeroRowChanges() + " statement(s) changed no rows "
                    + "(the row may have been changed or deleted by someone else).");
            }
            if (outcome.transaction() == ResultSetEditPersister.TransactionMode.OPEN_TRANSACTION) {
                host.appendMessage("The connection is in manual commit mode: the changes are in the open transaction until you COMMIT.");
            }
            host.appendMessage("Save time: " + (System.currentTimeMillis() - startTime) + " ms");
            if (isDisplayable()) {
                ResultSetEditPersister.refreshRows(editModel, refreshRows, refreshed -> {
                    refreshed.forEach((row, values) -> editModel.replaceValues(row, values[0], values[1]));
                    table.repaint();
                });
            }
        } else {
            reportSaveError(tableName, error, outcome.transaction(), !outcome.executedRows().isEmpty());
        }
    }

    private void reportSaveError(
        @NotNull String tableName,
        @NotNull Throwable error,
        @NotNull ResultSetEditPersister.TransactionMode transaction,
        boolean partlyApplied
    ) {
        String applied = switch (transaction) {
            case SAVE_TRANSACTION -> "The save transaction was rolled back; nothing was saved. Your changes are kept in the grid.";
            case OPEN_TRANSACTION -> "The statements of this save were rolled back. Your changes are kept in the grid.";
            case NONE -> partlyApplied
                ? "Statements before the error were applied; the other changes are kept in the grid."
                : "Nothing was saved. Your changes are kept in the grid.";
        };
        host.appendError("Can't save changes to " + tableName + ". " + applied + "\n" + CommonUtils.getAllExceptionMessages(error));
        host.showMessages();
        transientStatus = "Save failed: " + getChangeCount() + " pending changes kept (see Messages)";
        updateState();
    }

    //////////////////////////////////////////////////////////
    // Actions and menus

    private void installContextMenu() {
        copyAction = createAction("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
            () -> TransferHandler.getCopyAction().actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"))); //$NON-NLS-1$
        copyWithHeadersAction = createAction("Copy with Headers",
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
            () -> DBWorkbench.getPlatformUI().copyTextToClipboard(ResultSetExport.copySelection(table, getCurrentData(), true), false));
        Action exportCsvAction = createAction("Export to CSV…", null, () -> ResultSetExport.exportCsv(table, getCurrentData()));
        Action exportXlsxAction = createAction("Export to Excel (XLSX)…", null, () -> ResultSetExport.exportXlsx(table, getCurrentData()));
        Action exportDataAction = createAction("Export Data…", null, () -> {
            if (exportQuery != null) {
                DataExportWizard.openForQuery(table, exportQuery);
            }
        });
        boolean queryExportable = DataExportWizard.canExportQuery(exportQuery);
        exportDataAction.setEnabled(queryExportable);
        exportDataAction.putValue(Action.SHORT_DESCRIPTION, queryExportable
            ? "Run the query again and export all its rows in any format"
            : "Only the result of a query with one SELECT statement can be run again for export; use Export to CSV or Excel for the rows shown");
        // Ctrl+C stays with the table's transfer handler; the menu only shows it
        bindKey(table, JComponent.WHEN_FOCUSED, copyWithHeadersAction, "chromite.copyWithHeaders"); //$NON-NLS-1$

        JPopupMenu popupMenu = new JPopupMenu();
        if (editModel != null) {
            editCellAction = createAction("Edit Cell", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), this::editSelectedCell);
            setNullAction = createAction("Set NULL", KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), this::setSelectedToNull);
            insertRowAction = createAction("Insert Row", KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.ALT_DOWN_MASK), this::insertRow);
            deleteRowsAction = createAction("Delete Row", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelectedRows);
            Action saveMenuAction = createAction("Save Changes", (KeyStroke) saveAction.getValue(Action.ACCELERATOR_KEY), () -> save(false));
            Action cancelMenuAction = createAction("Cancel Changes", null, this::cancelChanges);
            // F2 is the table's own "start editing" binding; the menu only shows it
            bindKey(table, JComponent.WHEN_FOCUSED, setNullAction, "chromite.setNull"); //$NON-NLS-1$
            bindKey(table, JComponent.WHEN_FOCUSED, insertRowAction, "chromite.insertRow"); //$NON-NLS-1$
            bindKey(table, JComponent.WHEN_FOCUSED, deleteRowsAction, "chromite.deleteRows"); //$NON-NLS-1$
            // Ctrl+S in the grid or its cell editor saves the grid, even without changes, instead of reaching File > Save
            Action saveKeyAction = createAction("Save", null, () -> save(false));
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                (KeyStroke) saveAction.getValue(Action.ACCELERATOR_KEY), "chromite.saveGrid"); //$NON-NLS-1$
            getActionMap().put("chromite.saveGrid", saveKeyAction); //$NON-NLS-1$
            popupMenu.add(editCellAction);
            popupMenu.add(setNullAction);
            popupMenu.addSeparator();
            popupMenu.add(insertRowAction);
            popupMenu.add(deleteRowsAction);
            popupMenu.addSeparator();
            popupMenu.add(saveMenuAction);
            popupMenu.add(cancelMenuAction);
            popupMenu.addSeparator();
            saveMenuAction.setEnabled(false);
            cancelMenuAction.setEnabled(false);
            saveAction.addPropertyChangeListener(e -> saveMenuAction.setEnabled(saveAction.isEnabled()));
            cancelChangesAction.addPropertyChangeListener(e -> cancelMenuAction.setEnabled(cancelChangesAction.isEnabled()));
        }
        popupMenu.add(copyAction);
        popupMenu.add(copyWithHeadersAction);
        popupMenu.addSeparator();
        popupMenu.add(exportDataAction);
        popupMenu.add(exportCsvAction);
        popupMenu.add(exportXlsxAction);
        table.setComponentPopupMenu(popupMenu);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    int column = table.columnAtPoint(e.getPoint());
                    if (row >= 0 && column >= 0 && !table.isCellSelected(row, column)) {
                        table.changeSelection(row, column, false, false);
                    }
                    table.requestFocusInWindow();
                }
            }
        });
    }

    private static void bindKey(@NotNull JComponent component, int condition, @NotNull Action action, @NotNull String key) {
        component.getInputMap(condition).put((KeyStroke) action.getValue(Action.ACCELERATOR_KEY), key);
        component.getActionMap().put(key, action);
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable KeyStroke accelerator, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, accelerator);
        return action;
    }

    //////////////////////////////////////////////////////////
    // Layout

    /**
     * Column widths from the header and the first rows. The padding is not a constant: the header width is the
     * preferred width of the look and feel's header renderer (its cell margins, scaled), and the cells add the
     * insets of the cell border ({@code Table.cellMargins}, scaled) and the vertical grid line.
     */
    private static void fitColumnWidths(@NotNull JTable table, @NotNull Data data) {
        FontMetrics cellMetrics = table.getFontMetrics(table.getFont());
        int cellPadding = getCellPadding(table);
        TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
        int measuredRows = Math.min(MEASURED_ROWS, data.rows().size());
        for (int column = 0; column < data.columns().length; column++) {
            int width = headerRenderer.getTableCellRendererComponent(table, data.columns()[column], false, false, -1, column)
                .getPreferredSize().width + table.getIntercellSpacing().width;
            for (int row = 0; row < measuredRows; row++) {
                width = Math.max(width, cellMetrics.stringWidth(toDisplayText(data.rows().get(row)[column])) + cellPadding);
            }
            TableColumn tableColumn = table.getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(Math.clamp(width, UIScale.scale(MIN_COLUMN_WIDTH), UIScale.scale(MAX_COLUMN_WIDTH)));
        }
    }

    /**
     * Left and right insets of a cell (the look and feel's cell border) plus the vertical grid line.
     */
    private static int getCellPadding(@NotNull JTable table) {
        Border border = UIManager.getBorder("Table.cellNoFocusBorder"); //$NON-NLS-1$
        Insets insets = border != null ? border.getBorderInsets(table) : new Insets(0, 0, 0, 0);
        return insets.left + insets.right + table.getIntercellSpacing().width;
    }

    /**
     * Horizontal grid lines (grid.lineSubtle) at the bottom of every visible row.
     */
    private static void paintHorizontalLines(@NotNull JTable table, @NotNull Graphics g) {
        Color color = UIManager.getColor("Chromite.Table.horizontalLineColor"); //$NON-NLS-1$
        Rectangle clip = g.getClipBounds();
        if (color == null || clip == null || table.getRowCount() == 0) {
            return;
        }
        int first = table.rowAtPoint(new Point(0, clip.y));
        int last = table.rowAtPoint(new Point(0, clip.y + clip.height - 1));
        if (first < 0) {
            return;
        }
        if (last < 0) {
            last = table.getRowCount() - 1;
        }
        g.setColor(color);
        for (int row = first; row <= last; row++) {
            Rectangle cell = table.getCellRect(row, 0, true);
            g.fillRect(clip.x, cell.y + cell.height - 1, clip.width, 1);
        }
    }

    /**
     * Row numbers like SSMS ({@code *} for a new row). A one-column table keeps the row heights of the grid; clicking
     * a number selects the row without scrolling the grid sideways.
     */
    @NotNull
    private JTable createRowHeader() {
        JTable header = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return gridModel.getRowCount();
            }

            @Override
            public int getColumnCount() {
                return 1;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                if (editModel != null && editModel.getRow(rowIndex).state == ResultSetEditModel.RowState.ADDED) {
                    return "*"; //$NON-NLS-1$
                }
                return rowIndex + 1;
            }
        }) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintHorizontalLines(this, g);
                // 1px grid.line between the row numbers and the grid
                Color lineColor = UIManager.getColor("Table.gridColor"); //$NON-NLS-1$
                if (lineColor != null) {
                    g.setColor(lineColor);
                    g.fillRect(getWidth() - 1, 0, 1, getHeight());
                }
            }
        };
        gridModel.addTableModelListener(e -> ((AbstractTableModel) header.getModel()).fireTableDataChanged());
        header.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        header.setFocusable(false);
        header.setRowSelectionAllowed(false);
        header.setShowGrid(false);
        header.setRowHeight(table.getRowHeight());
        // DataGrip row numbers: grid background, text.secondary, left-aligned; the numbers of selected rows get the
        // selection background of the grid
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable rowTable, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(rowTable, value, false, false, row, column);
                boolean rowSelected = row < table.getRowCount() && table.isRowSelected(row);
                setBackground(rowSelected
                    ? UIManager.getColor(table.isFocusOwner() ? "Table.selectionBackground" : "Table.selectionInactiveBackground") //$NON-NLS-1$ //$NON-NLS-2$
                    : rowTable.getBackground());
                setForeground(UIManager.getColor("Chromite.secondaryForeground")); //$NON-NLS-1$
                return this;
            }
        };
        renderer.setHorizontalAlignment(SwingConstants.LEFT);
        header.setDefaultRenderer(Object.class, renderer);
        table.getSelectionModel().addListSelectionListener(e -> header.repaint());
        table.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                header.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                header.repaint();
            }
        });
        int rowCount = gridModel.getRowCount();
        int width = header.getFontMetrics(header.getFont()).stringWidth(String.valueOf(Math.max(rowCount, 99))) + getCellPadding(table);
        header.getColumnModel().getColumn(0).setPreferredWidth(width);
        header.setPreferredScrollableViewportSize(new Dimension(width, 0));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = header.rowAtPoint(e.getPoint());
                if (row >= 0 && table.getColumnCount() > 0) {
                    // changeSelection scrolls to the cell it selects: to the first and then to the last column, so the
                    // grid would jump to its right end and cut off the first columns. The clicked row is visible already.
                    boolean autoscrolls = table.getAutoscrolls();
                    table.setAutoscrolls(false);
                    try {
                        table.changeSelection(row, 0, false, false);
                        table.changeSelection(row, table.getColumnCount() - 1, false, true);
                    } finally {
                        table.setAutoscrolls(autoscrolls);
                    }
                    table.requestFocusInWindow();
                }
            }
        });
        return header;
    }

    @NotNull
    private static String toDisplayText(Object value) {
        return value == null ? "" : value.toString();
    }

    private final class GridModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return editModel != null ? editModel.getRowCount() : data.rows().size();
        }

        @Override
        public int getColumnCount() {
            return data.columns().length;
        }

        @Override
        public String getColumnName(int column) {
            return data.columns()[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return editModel != null ? editModel.getRow(rowIndex).display[columnIndex] : data.rows().get(rowIndex)[columnIndex];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return editModel != null && editModel.isCellEditable(rowIndex, columnIndex);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (editModel != null && value instanceof EditedValue edited) {
                editModel.setValue(rowIndex, columnIndex, edited.value());
                fireTableRowsUpdated(rowIndex, rowIndex);
                transientStatus = null;
                updateState();
            }
        }
    }

    /**
     * Text editor of a cell: starts with the value in the handler's edit format and makes the value with the
     * handler when editing stops ({@link ResultSetEditModel#parseValue}).
     */
    private final class TextCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final Border normalBorder;
        private String initialText = "";
        private int modelColumn;
        @Nullable
        private EditedValue value;

        TextCellEditor() {
            normalBorder = field.getBorder();
            field.addActionListener(e -> stopCellEditing());
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            // Double click, F2, or the Edit Cell menu
            return !(event instanceof MouseEvent mouseEvent) || mouseEvent.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object cellValue, boolean isSelected, int row, int column) {
            modelColumn = table.convertColumnIndexToModel(column);
            initialText = editModel.getEditText(table.convertRowIndexToModel(row), modelColumn);
            value = null;
            field.setText(initialText);
            field.setBorder(normalBorder);
            field.setFont(table.getFont());
            SwingUtilities.invokeLater(field::selectAll);
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public boolean stopCellEditing() {
            String text = field.getText();
            if (text.equals(initialText)) {
                // Unchanged text keeps the value (an empty editor of a NULL cell must not become an empty string)
                value = null;
                return super.stopCellEditing();
            }
            try {
                value = new EditedValue(editModel.parseValue(modelColumn, text));
            } catch (DBCException e) {
                Color errorColor = UIManager.getColor("Component.error.focusedBorderColor"); //$NON-NLS-1$
                field.setBorder(BorderFactory.createLineBorder(errorColor != null ? errorColor : Color.RED, 2));
                transientStatus = "Invalid value for " + data.columns()[modelColumn] + ": " + CommonUtils.notEmpty(e.getMessage());
                updateState();
                UIManager.getLookAndFeel().provideErrorFeedback(field);
                return false;
            }
            return super.stopCellEditing();
        }

        @Override
        public void cancelCellEditing() {
            value = null;
            super.cancelCellEditing();
        }
    }

    private final class CellRenderer extends DefaultTableCellRenderer {
        private final boolean[] numeric;

        CellRenderer(@NotNull boolean[] numeric) {
            this.numeric = numeric;
        }

        @Override
        public Component getTableCellRendererComponent(
            JTable table,
            Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column
        ) {
            boolean isNull = value == NULL_VALUE;
            // A cell shows one line, as in SSMS; the copied value keeps its line breaks
            String text = isNull ? NULL_VALUE.toString() : toDisplayText(value).replace('\r', ' ').replace('\n', ' ');
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
            int modelColumn = table.convertColumnIndexToModel(column);
            setHorizontalAlignment(modelColumn < numeric.length && numeric[modelColumn] ? SwingConstants.RIGHT : SwingConstants.LEFT);
            Font font = table.getFont();
            ResultSetEditModel.Row editRow = editModel != null ? editModel.getRow(table.convertRowIndexToModel(row)) : null;
            if (editRow != null && editRow.state == ResultSetEditModel.RowState.REMOVED) {
                font = font.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
            }
            setFont(font);
            if (!isSelected) {
                // setForeground/setBackground also become the renderer's defaults, so every cell sets them
                setForeground(isNull ? UIManager.getColor("Chromite.secondaryForeground") : table.getForeground()); //$NON-NLS-1$
                Color changeColor = editRow == null ? null : getChangeColor(editRow, modelColumn);
                setBackground(changeColor != null ? changeColor : table.getBackground());
            }
            return this;
        }

        /**
         * Background of a new row, a deleted row or a changed cell (theme keys {@code Chromite.Table.*Background}).
         */
        @Nullable
        private static Color getChangeColor(@NotNull ResultSetEditModel.Row row, int column) {
            return switch (row.state) {
                case ADDED -> UIManager.getColor("Chromite.Table.addedBackground"); //$NON-NLS-1$
                case REMOVED -> UIManager.getColor("Chromite.Table.removedBackground"); //$NON-NLS-1$
                case NORMAL -> row.isCellChanged(column) ? UIManager.getColor("Chromite.Table.changedBackground") : null; //$NON-NLS-1$
            };
        }
    }
}
