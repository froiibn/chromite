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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.DBDResultSetModel;
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.data.ResultSetValuePath;
import org.jkiss.dbeaver.model.data.hints.DBDValueHintContext;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.impl.data.ResultSetHintContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 편집할 수 있는 결과 집합 하나의 데이터와 바뀐 내용(SSMS "Edit Top 200 Rows", DataGrip 그리드 편집).
 *
 * 설계:
 * <ul>
 *     <li>모델 계층의 저장 엔진({@code DBDResultSetDataUpdater}, {@link ResultSetEditPersister})이 읽는 {@link DBDResultSetModel}을 구현한다.
 *     SWT의 {@code ResultSetModel}(UI 번들, SWT 색상 의존)을 옮기지 않고, 저장에 필요한 계약(열 바인딩, 행, 셀 값, 기본 행 식별자, 단일 원본 테이블)만
 *     같은 규칙으로 구현했다(swing-frontend.md "구현 현황 — G단계").</li>
 *     <li>행은 SWT {@code ResultSetRow}처럼 지금 값({@link Row#values}, 원래 값 객체)과 바뀐 셀의 처음 값({@link Row#originalValues})을 갖는다.
 *     저장 엔진은 UPDATE의 SET에 지금 값을, WHERE에 키 열의 처음 값을 쓴다. 화면은 표시 문자열({@link Row#display})만 그린다.</li>
 *     <li>편집할 수 있는지는 모델이 정한다({@link #getReadOnlyReason}): 연결 상태({@link DBExecUtils#getResultSetReadOnlyStatus}),
 *     단일 원본 테이블({@link DBExecUtils#detectSingleSourceTable}, 조인·식만 있는 결과는 없음), 완전한 기본 행 식별자(기본 키·고유 키),
 *     테이블의 데이터 변경 기능({@link DBSDataManipulator}). SWT의 {@code ResultSetViewer.isReadOnly}/{@code ResultSetModel.getReadOnlyStatus}와 같은 판단이며,
 *     SWT가 키가 없을 때 띄우는 가상 키 정의 대화상자({@code ValidateUniqueKeyUsageDialog}) 대신 읽기 전용으로 둔다.</li>
 *     <li>셀 편집은 글자로 하므로 문자열·숫자·불리언·날짜 열만 편집한다. LOB·배열·구조체처럼 값 객체({@code DBDValue})로 오는 열과 값을 읽지 못한 열은 읽기 전용이다
 *     (수신기가 그 값을 결과 집합을 닫기 전에 표시 문자열로 바꾸고 놓아 준다, {@link ResultSetGridReceiver}).</li>
 *     <li>글자 → 값 변환은 열의 값 처리기({@code DBDValueHandler.getValueFromObject})로 한다(SWT {@code StringInlineEditor}와 같은 호출).</li>
 *     <li>편집 메서드는 EDT에서 부른다. 저장 중({@link #isSaving})에는 화면이 편집을 막으므로 저장 Job이 행을 읽는 동안 바뀌지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 G단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 */
final class ResultSetEditModel implements DBDResultSetModel {

    enum RowState {
        NORMAL,
        ADDED,
        REMOVED
    }

    /**
     * One row: its current values and display strings, and the first values of the cells changed since the last save.
     */
    static final class Row implements DBDValueRow {
        private final int rowNumber;
        @NotNull
        Object[] values;
        @NotNull
        Object[] display;
        @NotNull
        RowState state;
        /**
         * Column index to the value before the first change (existing rows), or to null for a cell set in a new row.
         */
        final Map<Integer, Object> originalValues = new LinkedHashMap<>();
        private final Map<Integer, Object> originalDisplay = new HashMap<>();

        Row(int rowNumber, @NotNull Object[] values, @NotNull Object[] display, @NotNull RowState state) {
            this.rowNumber = rowNumber;
            this.values = values;
            this.display = display;
            this.state = state;
        }

        @Override
        public int getRowNumber() {
            return rowNumber;
        }

        @NotNull
        @Override
        public Object[] getValues() {
            return values;
        }

        boolean isChanged() {
            return !originalValues.isEmpty();
        }

        boolean isCellChanged(int column) {
            return originalValues.containsKey(column);
        }

        /**
         * Restores the first values of the changed cells.
         */
        void revert() {
            for (Map.Entry<Integer, Object> entry : originalValues.entrySet()) {
                values[entry.getKey()] = entry.getValue();
                display[entry.getKey()] = originalDisplay.get(entry.getKey());
            }
            clearChanges();
        }

        void clearChanges() {
            originalValues.clear();
            originalDisplay.clear();
        }
    }

    private final DBCExecutionContext executionContext;
    private final DBSDataContainer dataContainer;
    private final DBDAttributeBinding[] bindings;
    private final List<DBDAttributeBinding> visibleBindings;
    private final DBSEntity singleSource;
    private final boolean[] editableColumns;
    private final boolean insertSupported;
    private final boolean deleteSupported;
    private final List<Row> rows = new ArrayList<>();
    private final ResultSetHintContext hintContext;
    private int nextRowNumber;
    private volatile boolean saving;

    /**
     * @param unreadableColumns columns whose values are not kept: value objects (LOBs etc.) or read errors
     * @param values            original values of the fetched rows (null where the column is unreadable)
     * @param display           display strings of the fetched rows
     */
    ResultSetEditModel(
        @NotNull DBCExecutionContext executionContext,
        @NotNull DBSDataContainer dataContainer,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull boolean[] unreadableColumns,
        @NotNull List<Object[]> values,
        @NotNull List<Object[]> display
    ) {
        this.executionContext = executionContext;
        this.dataContainer = dataContainer;
        this.bindings = bindings;
        this.visibleBindings = List.of(bindings);
        this.singleSource = DBExecUtils.detectSingleSourceTable(bindings);
        this.editableColumns = new boolean[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            editableColumns[i] = isEditableColumn(bindings[i], singleSource, unreadableColumns[i]);
        }
        this.insertSupported = isFeatureSupported(singleSource, DBSDataManipulator.FEATURE_DATA_INSERT);
        this.deleteSupported = isFeatureSupported(singleSource, DBSDataManipulator.FEATURE_DATA_DELETE);
        for (int i = 0; i < values.size(); i++) {
            // Own copies: the fetched display rows stay as they were (the grid snapshot for read-only use)
            rows.add(new Row(nextRowNumber++, values.get(i).clone(), display.get(i).clone(), RowState.NORMAL));
        }
        this.hintContext = new ResultSetHintContext(() -> dataContainer, this::getSingleSource);
    }

    /**
     * Decides whether a fetched result can be edited, by the rules of the SWT result set viewer.
     *
     * @param bindError error of binding the columns to table metadata, if any
     * @return null if it can be edited, otherwise a short reason shown in the grid ("read-only: …")
     */
    @Nullable
    static String getReadOnlyReason(
        @NotNull DBCExecutionContext executionContext,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull boolean[] unreadableColumns,
        @Nullable Throwable bindError
    ) {
        DBPDataSourceContainer container = executionContext.getDataSource().getContainer();
        String containerStatus = DBExecUtils.getResultSetReadOnlyStatus(container);
        if (containerStatus != null) {
            return containerStatus;
        }
        if (DBExecUtils.isResultSetReadOnly(executionContext)) {
            return "no connection to database";
        }
        if (bindError != null) {
            return "can't read table metadata (" + bindError.getMessage() + ")";
        }
        DBSEntity source = DBExecUtils.detectSingleSourceTable(bindings);
        if (source == null) {
            for (DBDAttributeBinding binding : bindings) {
                if (binding.getRowIdentifier() != null) {
                    return "columns of several tables";
                }
            }
            return "no table columns";
        }
        DBDRowIdentifier identifier = findDefaultRowIdentifier(bindings);
        if (identifier == null || identifier.isIncomplete() || !identifier.isValidIdentifier()) {
            return "no unique key";
        }
        for (DBDAttributeBinding keyAttribute : identifier.getAttributes()) {
            int position = keyAttribute.getOrdinalPosition();
            if (position < 0 || position >= unreadableColumns.length || unreadableColumns[position]) {
                return "key values can't be read";
            }
        }
        if (!isFeatureSupported(source, DBSDataManipulator.FEATURE_DATA_UPDATE)) {
            return "the table doesn't support data changes";
        }
        for (int i = 0; i < bindings.length; i++) {
            if (isEditableColumn(bindings[i], source, unreadableColumns[i])) {
                return null;
            }
        }
        return "no editable columns";
    }

    /**
     * A column of the source table that the model can update ({@link DBExecUtils#isAttributeReadOnly}) and that is
     * edited as text.
     */
    private static boolean isEditableColumn(@NotNull DBDAttributeBinding binding, @Nullable DBSEntity source, boolean unreadable) {
        if (unreadable || source == null || binding.isPseudoAttribute() || DBExecUtils.isAttributeReadOnly(binding, true)) {
            return false;
        }
        DBDRowIdentifier identifier = binding.getRowIdentifier();
        if (identifier == null || identifier.getEntity() != source) {
            return false;
        }
        DBPDataKind dataKind = binding.getDataKind();
        return dataKind == DBPDataKind.STRING || dataKind == DBPDataKind.NUMERIC
            || dataKind == DBPDataKind.BOOLEAN || dataKind == DBPDataKind.DATETIME;
    }

    private static boolean isFeatureSupported(@Nullable DBSEntity source, @NotNull String feature) {
        return source instanceof DBSDataManipulator manipulator && manipulator.isFeatureSupported(feature);
    }

    @Nullable
    private static DBDRowIdentifier findDefaultRowIdentifier(@NotNull DBDAttributeBinding[] bindings) {
        // The first identifier in column order, as ResultSetModel.getDefaultRowIdentifier
        for (DBDAttributeBinding binding : bindings) {
            DBDRowIdentifier identifier = binding.getRowIdentifier();
            if (identifier != null) {
                return identifier;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // DBDResultSetModel

    @NotNull
    @Override
    public DBDAttributeBinding[] getAttributes() {
        return bindings;
    }

    @NotNull
    @Override
    public List<DBDAttributeBinding> getVisibleAttributes() {
        return visibleBindings;
    }

    @NotNull
    @Override
    public List<Row> getAllRows() {
        return Collections.unmodifiableList(rows);
    }

    @Nullable
    @Override
    public DBDRowIdentifier getDefaultRowIdentifier() {
        return findDefaultRowIdentifier(bindings);
    }

    @Nullable
    @Override
    public Object getCellValue(
        @NotNull DBDAttributeBinding attribute,
        @NotNull DBDValueRow row,
        @Nullable int[] rowIndexes,
        @Nullable ResultSetValuePath valuePath,
        boolean retrieveDeepestCollectionElement
    ) {
        if (valuePath != null) {
            return DBUtils.getRowValueByPath(row, valuePath);
        }
        return DBUtils.getAttributeValue(attribute, bindings, row.getValues(), rowIndexes, retrieveDeepestCollectionElement);
    }

    @Nullable
    @Override
    public Object getCellValue(@NotNull DBDAttributeBinding attribute, @NotNull DBDValueRow row) {
        return getCellValue(attribute, row, null, null, false);
    }

    @NotNull
    @Override
    public DBDValueHintContext getHintContext() {
        return hintContext;
    }

    @Nullable
    @Override
    public String getReadOnlyStatus(@Nullable DBPDataSourceContainer dataSourceContainer) {
        if (saving) {
            return "Update in progress";
        }
        return DBExecUtils.getResultSetReadOnlyStatus(dataSourceContainer);
    }

    @Nullable
    @Override
    public DBSEntity getSingleSource() {
        return singleSource;
    }

    //////////////////////////////////////////////////////////
    // Grid access and editing (EDT)

    @NotNull
    DBCExecutionContext getExecutionContext() {
        return executionContext;
    }

    @NotNull
    DBSDataContainer getDataContainer() {
        return dataContainer;
    }

    int getRowCount() {
        return rows.size();
    }

    @NotNull
    Row getRow(int index) {
        return rows.get(index);
    }

    boolean isSaving() {
        return saving;
    }

    void setSaving(boolean saving) {
        this.saving = saving;
    }

    boolean isInsertSupported() {
        return insertSupported;
    }

    boolean isDeleteSupported() {
        return deleteSupported;
    }

    boolean isCellEditable(int rowIndex, int column) {
        return !saving && editableColumns[column] && rows.get(rowIndex).state != RowState.REMOVED;
    }

    /**
     * Pending changes, counted as SWT counts them: one per new or deleted row, one per changed cell of other rows.
     */
    int getChangeCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.state != RowState.NORMAL) {
                count++;
            } else {
                count += row.originalValues.size();
            }
        }
        return count;
    }

    boolean isDirty() {
        return getChangeCount() > 0;
    }

    /**
     * The text a cell editor starts with: the value in the editable format of its handler, empty for NULL.
     */
    @NotNull
    String getEditText(int rowIndex, int column) {
        Object value = rows.get(rowIndex).values[column];
        if (DBUtils.isNullValue(value)) {
            return "";
        }
        DBDAttributeBinding binding = bindings[column];
        return binding.getValueHandler().getValueDisplayString(binding, value, DBDDisplayFormat.EDIT);
    }

    /**
     * Converts the text of a cell editor to a value of the column ({@code DBDValueHandler.getValueFromObject}, as the
     * SWT inline text editor). The session is a utility session of the result's context, as in SWT.
     *
     * @throws DBCException the text is not a valid value of the column (e.g. not a number)
     */
    @Nullable
    Object parseValue(int column, @NotNull String text) throws DBCException {
        DBDAttributeBinding binding = bindings[column];
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Make cell value from editor")) {
            return binding.getValueHandler().getValueFromObject(session, binding, text, false, true);
        }
    }

    /**
     * Sets a cell value. The first value of the cell is kept until the change is saved or canceled; setting it back
     * removes the change.
     */
    void setValue(int rowIndex, int column, @Nullable Object value) {
        Row row = rows.get(rowIndex);
        Object current = row.values[column];
        if (Objects.equals(current, value) && (row.state != RowState.ADDED || row.isCellChanged(column))) {
            return;
        }
        if (!row.originalValues.containsKey(column)) {
            row.originalValues.put(column, current);
            row.originalDisplay.put(column, row.display[column]);
        } else if (row.state != RowState.ADDED && Objects.equals(row.originalValues.get(column), value)) {
            // Back to the value read from the database
            row.values[column] = value;
            row.display[column] = row.originalDisplay.get(column);
            row.originalValues.remove(column);
            row.originalDisplay.remove(column);
            return;
        }
        row.values[column] = value;
        row.display[column] = toDisplay(column, value);
    }

    @NotNull
    Object toDisplay(int column, @Nullable Object value) {
        if (DBUtils.isNullValue(value)) {
            return ResultSetGrid.NULL_VALUE;
        }
        DBDAttributeBinding binding = bindings[column];
        return binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
    }

    /**
     * Adds an empty row (the values a new row starts with in SWT: {@code createNewValueObject}, except pseudo and
     * auto-generated columns, which the database fills).
     *
     * @return index of the new row
     */
    int addRow(int index) throws DBException {
        Object[] values = new Object[bindings.length];
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Add new row")) {
            for (int i = 0; i < bindings.length; i++) {
                DBDAttributeBinding binding = bindings[i];
                if (!binding.isPseudoAttribute() && !binding.isAutoGenerated() && binding.getAttribute() != null) {
                    values[i] = binding.getValueHandler().createNewValueObject(session, binding.getAttribute());
                }
            }
        }
        Object[] display = new Object[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            display[i] = toDisplay(i, values[i]);
        }
        int position = Math.clamp(index, 0, rows.size());
        rows.add(position, new Row(nextRowNumber++, values, display, RowState.ADDED));
        return position;
    }

    /**
     * Deletes rows: new rows go away, other rows are marked deleted. A deleted row gets its read values back, so
     * the DELETE finds it by its stored key.
     */
    void deleteRows(@NotNull int[] rowIndexes) {
        int[] sorted = rowIndexes.clone();
        Arrays.sort(sorted);
        for (int i = sorted.length - 1; i >= 0; i--) {
            Row row = rows.get(sorted[i]);
            if (row.state == RowState.ADDED) {
                rows.remove(sorted[i]);
            } else {
                row.revert();
                row.state = RowState.REMOVED;
            }
        }
    }

    /**
     * Cancel: drops new rows, restores deleted rows and changed cells.
     */
    void revertAll() {
        rows.removeIf(row -> row.state == RowState.ADDED);
        for (Row row : rows) {
            row.revert();
            row.state = RowState.NORMAL;
        }
    }

    /**
     * After a successful save: deleted rows are gone, the rest keep their values as the new read values.
     *
     * @param executedRows rows whose statements ran (others keep their changes)
     */
    void acceptSaved(@NotNull List<Row> executedRows) {
        for (Row row : executedRows) {
            if (row.state == RowState.REMOVED) {
                rows.remove(row);
            } else {
                row.state = RowState.NORMAL;
                row.clearChanges();
            }
        }
    }

    /**
     * Replaces a row's values with values read again from the database (after a save).
     */
    void replaceValues(@NotNull Row row, @NotNull Object[] values, @NotNull Object[] display) {
        if (rows.contains(row) && !row.isChanged() && row.state == RowState.NORMAL) {
            row.values = values;
            row.display = display;
        }
    }

    int indexOf(@NotNull Row row) {
        return rows.indexOf(row);
    }
}
