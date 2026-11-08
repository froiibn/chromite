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

import com.seizia.chromite.ext.postgresql.model.data.PostgreStructValueHandler;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDResultSetModel;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.data.DBDValueDefaultGenerator;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.data.ResultSetValuePath;
import com.seizia.chromite.model.data.hints.DBDValueHintContext;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.impl.data.ResultSetHintContext;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.DBSEntity;
import org.jkiss.utils.CommonUtils;

import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *     <li>칸 안 편집은 글자(또는 불리언·ENUM 콤보, 날짜 선택)로 하므로 문자열·숫자·불리언·날짜 열과, SWT {@code StringValueManager}처럼
 *     글자로 고치는 {@code OBJECT} 열(PostgreSQL uuid·inet 등: 값 처리기가 글자로 값을 만듦)을 칸에서 편집한다({@link #isInlineEditable}).
 *     배열·구조체({@code ARRAY}·{@code STRUCT})는 수신기가 남긴 값 객체로 편집한다(글자는 값 처리기의 형식, 대화상자는
 *     {@link ResultSetValueDialogs}). 구조체는 PostgreSQL 복합 형식만이다: 일반 JDBC 구조체 처리기는 읽어 온 구조체를 그대로 바인딩해
 *     바꾼 속성이 저장되지 않는다. 그 밖의 값 객체({@code DBDValue})로 오는 열과 값을 읽지 못한 열은 읽기 전용이다.</li>
 *     <li>MySQL/MariaDB {@code SET} 열은 허용 값 여러 개를 확인란으로 고른다({@link #isMultiValueColumn}). ENUM 콤보와 같은 값 목록을 쓴다.</li>
 *     <li>LOB·이진 열(자료형 {@code CONTENT}: JSON, XML, CLOB, BLOB, {@code BINARY}: bytea 등, 그리고 드라이버가 LOB 값으로 준 문자열 열:
 *     MariaDB {@code LONGTEXT}·{@code JSON})은 값 전체를 바꾸는 방식으로만 편집한다(값 패널의 JSON·XML·글자 편집기, 파일에서 불러오기).
 *     수신기는 이 값 객체를 표시 문자열로 바꾸고 놓아 주므로 행에는 값이 없다(읽지 않은 값, {@link #hasValue}). 읽지 않은 칸은 원래 값을 모르므로
 *     "처음 값으로 돌아감" 판단을 하지 않고, 되돌리면 다시 읽지 않은 상태가 된다. 저장 엔진은 바뀐 열의 지금 값과 키 열만 쓰므로 읽지 않은 값이 문장에 들어가지 않는다.</li>
 *     <li>글자 → 값 변환은 열의 값 처리기({@code DBDValueHandler.getValueFromObject})로 한다(SWT {@code StringInlineEditor}와 같은 호출).</li>
 *     <li>실행 취소: SWT {@code ResultSetUndoRedoManager}처럼 칸 값 변경만 되돌린다(행 추가·삭제는 아님). 한 동작(붙여넣기, 위 행 값 복사 등)의 변경은
 *     한 묶음이다. 저장·취소하면 기록을 지운다.</li>
 *     <li>편집 메서드는 EDT에서 부른다. 저장 중({@link #isSaving})에는 화면이 편집을 막으므로 저장 Job이 행을 읽는 동안 바뀌지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 G단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 LOB 열 편집(읽지 않은 값), ENUM 값, 실행 취소·다시 실행, 행 복제, 위·아래 행 값 복사, 칸 되돌리기, 기본값으로
 *   (docs/chromite/swing-grid-editing.md)
 * - 2026-11-08 {@code OBJECT}·배열·구조체 열 편집, SET 열(여러 값) (docs/chromite/swing-value-editors.md)
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
        /**
         * LOB cells whose value the row holds (set in the grid, possibly saved since); other LOB cells were not read.
         */
        private final BitSet knownContent = new BitSet();
        /**
         * Changed LOB cells whose value before the change was not read.
         */
        private final BitSet unknownOriginal = new BitSet();

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
            for (Integer column : new ArrayList<>(originalValues.keySet())) {
                revertCell(column);
            }
        }

        /**
         * Restores the first value of a changed cell; a LOB cell that was not read is not read again.
         *
         * @return whether the cell was changed
         */
        boolean revertCell(int column) {
            if (!originalValues.containsKey(column)) {
                return false;
            }
            values[column] = originalValues.remove(column);
            display[column] = originalDisplay.remove(column);
            if (unknownOriginal.get(column)) {
                unknownOriginal.clear(column);
                knownContent.clear(column);
            }
            return true;
        }

        void clearChanges() {
            originalValues.clear();
            originalDisplay.clear();
            unknownOriginal.clear();
        }
    }

    /**
     * One cell change for undo and redo: the cell's state before and after it.
     *
     * @param changedBefore whether the cell was changed before (otherwise undo restores the read value)
     * @param changedAfter  whether the cell is changed after (false: the change was a reset to the read value)
     */
    private record CellChange(
        @NotNull Row row,
        int column,
        boolean changedBefore,
        @Nullable Object before,
        boolean changedAfter,
        @Nullable Object after
    ) {
    }

    /**
     * Undo steps kept (SWT {@code ResultSetPreferences.DEFAULT_EDIT_UNDO_LEVEL}).
     */
    private static final int UNDO_LIMIT = 200;

    private final DBCExecutionContext executionContext;
    private final DBSDataContainer dataContainer;
    private final DBDAttributeBinding[] bindings;
    private final List<DBDAttributeBinding> visibleBindings;
    private final DBSEntity singleSource;
    private final boolean[] editableColumns;
    private final boolean[] contentColumns;
    /**
     * Columns whose fetched values the rows don't hold (value objects such as LOBs, released by the receiver, or read
     * errors).
     */
    private final boolean[] unreadColumns;
    private final boolean[] lobColumns;
    private final String[][] enumValues;
    private final boolean[] multiValueColumns;
    private final boolean insertSupported;
    private final boolean deleteSupported;
    private final List<Row> rows = new ArrayList<>();
    private final ResultSetHintContext hintContext;
    private final Deque<List<CellChange>> undoSteps = new ArrayDeque<>();
    private final Deque<List<CellChange>> redoSteps = new ArrayDeque<>();
    @Nullable
    private List<CellChange> openStep;
    private boolean undoing;
    private int nextRowNumber;
    private volatile boolean saving;

    /**
     * @param unreadableColumns columns whose values are not kept: value objects (LOBs etc.) or read errors
     * @param values            original values of the fetched rows (null where the column is unreadable)
     * @param display           display strings of the fetched rows
     * @param lobColumns        columns whose fetched values were LOB value objects ({@code DBDContent}), even when the
     *                          column's table handler is a string handler (MySQL/MariaDB {@code TEXT}, {@code JSON})
     * @param enumValues        allowed values of ENUM columns, by column ({@link ResultSetEnumValues}), or null
     */
    ResultSetEditModel(
        @NotNull DBCExecutionContext executionContext,
        @NotNull DBSDataContainer dataContainer,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull boolean[] unreadableColumns,
        @NotNull boolean[] lobColumns,
        @NotNull List<Object[]> values,
        @NotNull List<Object[]> display,
        @Nullable String[][] enumValues
    ) {
        this.executionContext = executionContext;
        this.dataContainer = dataContainer;
        this.bindings = bindings;
        this.visibleBindings = List.of(bindings);
        this.singleSource = DBExecUtils.detectSingleSourceTable(bindings);
        this.editableColumns = new boolean[bindings.length];
        this.contentColumns = new boolean[bindings.length];
        this.unreadColumns = new boolean[bindings.length];
        this.lobColumns = lobColumns.clone();
        this.enumValues = enumValues != null && enumValues.length == bindings.length ? enumValues : new String[bindings.length][];
        this.multiValueColumns = new boolean[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            multiValueColumns[i] = this.enumValues[i] != null && ResultSetEnumValues.isMultiple(bindings[i]);
            editableColumns[i] = isEditableColumn(bindings[i], singleSource, unreadableColumns[i], lobColumns[i]);
            contentColumns[i] = editableColumns[i] && isWholeValueColumn(bindings[i], lobColumns[i]);
            unreadColumns[i] = unreadableColumns[i];
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
     * @param lobColumns columns whose fetched values were LOB value objects
     * @param bindError  error of binding the columns to table metadata, if any
     * @return null if it can be edited, otherwise a short reason shown in the grid ("read-only: …")
     */
    @Nullable
    static String getReadOnlyReason(
        @NotNull DBCExecutionContext executionContext,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull boolean[] unreadableColumns,
        @NotNull boolean[] lobColumns,
        @Nullable Throwable bindError
    ) {
        DBPDataSourceContainer container = executionContext.getDataSource().getContainer();
        String containerStatus = DBExecUtils.getResultSetReadOnlyStatus(container);
        if (containerStatus != null) {
            return containerStatus;
        }
        if (DBExecUtils.isResultSetReadOnly(executionContext)) {
            return EditorMessages.editor_grid_reason_no_connection;
        }
        if (bindError != null) {
            return NLS.bind(EditorMessages.editor_grid_reason_metadata, ErrorText.describeLine(bindError));
        }
        DBSEntity source = DBExecUtils.detectSingleSourceTable(bindings);
        if (source == null) {
            for (DBDAttributeBinding binding : bindings) {
                if (binding.getRowIdentifier() != null) {
                    return EditorMessages.editor_grid_reason_several_tables;
                }
            }
            return EditorMessages.editor_grid_reason_no_table_columns;
        }
        DBDRowIdentifier identifier = findDefaultRowIdentifier(bindings);
        if (identifier == null || identifier.isIncomplete() || !identifier.isValidIdentifier()) {
            return EditorMessages.editor_grid_reason_no_unique_key;
        }
        for (DBDAttributeBinding keyAttribute : identifier.getAttributes()) {
            int position = keyAttribute.getOrdinalPosition();
            if (position < 0 || position >= unreadableColumns.length || unreadableColumns[position]) {
                return EditorMessages.editor_grid_reason_key_unreadable;
            }
        }
        if (!isFeatureSupported(source, DBSDataManipulator.FEATURE_DATA_UPDATE)) {
            return EditorMessages.editor_grid_reason_no_data_changes;
        }
        for (int i = 0; i < bindings.length; i++) {
            if (isEditableColumn(bindings[i], source, unreadableColumns[i], lobColumns[i])) {
                return null;
            }
        }
        return EditorMessages.editor_grid_reason_no_editable_columns;
    }

    /**
     * A column of the source table that the model can update ({@link DBExecUtils#isAttributeReadOnly}) and that is
     * edited as text (also {@code OBJECT} columns, as SWT's string editor, and arrays and composites whose values the
     * receiver kept), or a LOB or binary column, edited as a whole value (LOB values are not kept, so it may be
     * "unreadable").
     */
    private static boolean isEditableColumn(@NotNull DBDAttributeBinding binding, @Nullable DBSEntity source, boolean unreadable, boolean lob) {
        if (source == null || binding.isPseudoAttribute() || DBExecUtils.isAttributeReadOnly(binding, true)) {
            return false;
        }
        DBDRowIdentifier identifier = binding.getRowIdentifier();
        if (identifier == null || identifier.getEntity() != source) {
            return false;
        }
        if (isWholeValueColumn(binding, lob)) {
            return true;
        }
        DBPDataKind dataKind = binding.getDataKind();
        return !unreadable && switch (dataKind) {
            case STRING, NUMERIC, BOOLEAN, DATETIME, OBJECT, ARRAY -> true;
            // The generic JDBC composite binds the struct it was fetched as (JDBCComposite.getStructValue), so changed
            // attributes would not be saved; PostgreSQL's handler writes the attribute values
            case STRUCT -> binding.getValueHandler() instanceof PostgreStructValueHandler;
            default -> false;
        };
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
            return EditorMessages.editor_grid_update_in_progress;
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
     * An editable cell that is edited in the grid (text, boolean or ENUM combo, date picker); LOB cells are edited in
     * the value panel or from a file.
     */
    /**
     * LOBs ({@link DBPDataKind#CONTENT}: JSON, XML, CLOB, BLOB), binary values ({@link DBPDataKind#BINARY}: bytea,
     * varbinary) and string columns that the driver delivered as LOB values (MySQL/MariaDB {@code LONGTEXT}, which is
     * also MariaDB's {@code JSON}; after binding, the table column's handler may be a string handler): edited as a whole
     * value, not as text in the grid.
     *
     * @param lob the fetched values of the column were LOB value objects
     */
    private static boolean isWholeValueColumn(@NotNull DBDAttributeBinding binding, boolean lob) {
        Class<?> valueType;
        try {
            valueType = binding.getValueHandler().getValueObjectType(binding);
        } catch (Exception e) {
            valueType = Object.class;
        }
        if (DBGeometry.class.isAssignableFrom(valueType)) {
            // Geometries (some drivers report binary columns) stay read-only: the spatial view shows them
            return false;
        }
        DBPDataKind dataKind = binding.getDataKind();
        return lob || dataKind == DBPDataKind.CONTENT || dataKind == DBPDataKind.BINARY || DBDContent.class.isAssignableFrom(valueType);
    }

    boolean isInlineEditable(int rowIndex, int column) {
        return isCellEditable(rowIndex, column) && !contentColumns[column];
    }

    /**
     * An editable LOB or binary column ({@link #isWholeValueColumn}).
     */
    boolean isContentColumn(int column) {
        return contentColumns[column];
    }

    /**
     * Whether a LOB column holds text (JSON, XML, CLOB…) rather than bytes, by its type: values are made from strings
     * then, and files are read and written as UTF-8.
     */
    boolean isTextContentColumn(int column) {
        DBDAttributeBinding binding = bindings[column];
        switch (binding.getTypeID()) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML -> {
                return true;
            }
            default -> {
                String typeName = CommonUtils.notEmpty(binding.getTypeName()).toLowerCase(Locale.ROOT);
                return typeName.contains("json") || typeName.contains("xml") || typeName.contains("text") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || typeName.contains("clob"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Whether the row holds the cell's value: false for a LOB cell that was not read and not set since (the result
     * viewer keeps its content for the value panel).
     */
    boolean hasValue(int rowIndex, int column) {
        return !isUnread(rows.get(rowIndex), column);
    }

    private boolean isUnread(@NotNull Row row, int column) {
        return unreadColumns[column] && row.state != RowState.ADDED && !row.knownContent.get(column);
    }

    /**
     * The allowed values of an ENUM column, or null.
     */
    @Nullable
    String[] getEnumValues(int column) {
        return enumValues[column];
    }

    @NotNull
    String[][] getEnumValues() {
        return enumValues;
    }

    /**
     * A column that holds several of its allowed values ({@link #getEnumValues}) at once: MySQL/MariaDB {@code SET}.
     */
    boolean isMultiValueColumn(int column) {
        return multiValueColumns[column];
    }

    @NotNull
    boolean[] getLobColumns() {
        return lobColumns.clone();
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
        return parseObject(column, text);
    }

    /**
     * Converts an editor's value (text, a boolean, a date of the date picker, the bytes or text of a file) to a value
     * of the column with its handler.
     *
     * @throws DBCException not a valid value of the column
     */
    @Nullable
    Object parseObject(int column, @Nullable Object object) throws DBCException {
        DBDAttributeBinding binding = bindings[column];
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Make cell value from editor")) {
            return binding.getValueHandler().getValueFromObject(session, binding, object, false, true);
        }
    }

    /**
     * Sets a cell value. The first value of the cell is kept until the change is saved or canceled; setting it back
     * removes the change. The change is an undo step, or part of the open one ({@link #beginStep}).
     */
    void setValue(int rowIndex, int column, @Nullable Object value) {
        Row row = rows.get(rowIndex);
        boolean changedBefore = row.isCellChanged(column);
        Object before = row.values[column];
        if (applyValue(row, column, value)) {
            record(new CellChange(row, column, changedBefore, before, row.isCellChanged(column), value));
        }
    }

    /**
     * @return whether the cell changed
     */
    private boolean applyValue(@NotNull Row row, int column, @Nullable Object value) {
        Object current = row.values[column];
        boolean unread = isUnread(row, column);
        if (!unread && Objects.equals(current, value) && (row.state != RowState.ADDED || row.isCellChanged(column))) {
            return false;
        }
        if (!row.originalValues.containsKey(column)) {
            row.originalValues.put(column, current);
            row.originalDisplay.put(column, row.display[column]);
            if (unread) {
                row.unknownOriginal.set(column);
            }
        } else if (row.state != RowState.ADDED && !row.unknownOriginal.get(column) && Objects.equals(row.originalValues.get(column), value)) {
            // Back to the value read from the database
            row.revertCell(column);
            return true;
        }
        if (contentColumns[column]) {
            row.knownContent.set(column);
        }
        row.values[column] = value;
        row.display[column] = toDisplay(column, value);
        return true;
    }

    /**
     * Restores the value read from the database (SWT "Reset cell value").
     */
    void resetCell(int rowIndex, int column) {
        Row row = rows.get(rowIndex);
        if (row.state == RowState.REMOVED) {
            return;
        }
        Object before = row.values[column];
        if (row.revertCell(column)) {
            record(new CellChange(row, column, true, before, false, row.values[column]));
        }
    }

    //////////////////////////////////////////////////////////
    // Undo and redo (cell values, as SWT ResultSetUndoRedoManager)

    /**
     * Starts an undo step for the changes of one action (paste, copy from the row above…); {@link #endStep} ends it.
     */
    void beginStep() {
        openStep = new ArrayList<>();
    }

    void endStep() {
        List<CellChange> step = openStep;
        openStep = null;
        if (step != null && !step.isEmpty()) {
            pushUndo(step);
        }
    }

    private void record(@NotNull CellChange change) {
        if (undoing) {
            return;
        }
        if (openStep != null) {
            openStep.add(change);
        } else {
            pushUndo(new ArrayList<>(List.of(change)));
        }
    }

    private void pushUndo(@NotNull List<CellChange> step) {
        undoSteps.push(step);
        while (undoSteps.size() > UNDO_LIMIT) {
            undoSteps.removeLast();
        }
        redoSteps.clear();
    }

    boolean canUndo() {
        return !undoSteps.isEmpty();
    }

    boolean canRedo() {
        return !redoSteps.isEmpty();
    }

    /**
     * Undoes the last step. Changes of rows that were deleted since are skipped.
     *
     * @return whether there was a step
     */
    boolean undo() {
        List<CellChange> step = undoSteps.poll();
        if (step == null) {
            return false;
        }
        undoing = true;
        try {
            for (int i = step.size() - 1; i >= 0; i--) {
                CellChange change = step.get(i);
                restore(change.row(), change.column(), change.changedBefore(), change.before());
            }
        } finally {
            undoing = false;
        }
        redoSteps.push(step);
        return true;
    }

    boolean redo() {
        List<CellChange> step = redoSteps.poll();
        if (step == null) {
            return false;
        }
        undoing = true;
        try {
            for (CellChange change : step) {
                restore(change.row(), change.column(), change.changedAfter(), change.after());
            }
        } finally {
            undoing = false;
        }
        undoSteps.push(step);
        return true;
    }

    private void restore(@NotNull Row row, int column, boolean changed, @Nullable Object value) {
        if (!rows.contains(row) || row.state == RowState.REMOVED) {
            return;
        }
        if (changed) {
            applyValue(row, column, value);
        } else {
            row.revertCell(column);
        }
    }

    private void clearUndo() {
        undoSteps.clear();
        redoSteps.clear();
        openStep = null;
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
     * Adds copies of rows (SWT "Duplicate Row", {@code addNewRow} with copy): every value is copied through its handler
     * except pseudo and auto-generated columns, which get new-row values as in {@link #addRow}. A LOB cell that was not
     * read can't be copied: it starts empty.
     *
     * @param sourceIndexes rows to copy, in order
     * @param index         where the copies go
     * @return the number of LOB cells left empty
     */
    int duplicateRows(@NotNull int[] sourceIndexes, int index) throws DBException {
        List<Row> copies = new ArrayList<>();
        int skipped = 0;
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Duplicate rows")) {
            for (int sourceIndex : sourceIndexes) {
                Row source = rows.get(sourceIndex);
                Object[] values = new Object[bindings.length];
                for (int i = 0; i < bindings.length; i++) {
                    DBDAttributeBinding binding = bindings[i];
                    if (binding.isPseudoAttribute() || binding.isAutoGenerated() || binding.getAttribute() == null) {
                        continue;
                    }
                    if (isUnread(source, i)) {
                        if (!ResultSetGrid.NULL_VALUE.equals(source.display[i])) {
                            skipped++;
                        }
                        values[i] = binding.getValueHandler().createNewValueObject(session, binding.getAttribute());
                    } else {
                        values[i] = DBUtils.isNullValue(source.values[i]) ? source.values[i]
                            : binding.getValueHandler().getValueFromObject(session, binding.getAttribute(), source.values[i], true, false);
                    }
                }
                Object[] display = new Object[bindings.length];
                for (int i = 0; i < bindings.length; i++) {
                    display[i] = toDisplay(i, values[i]);
                }
                copies.add(new Row(nextRowNumber++, values, display, RowState.ADDED));
            }
        }
        rows.addAll(Math.clamp(index, 0, rows.size()), copies);
        return skipped;
    }

    /**
     * Copies the values of the given columns from the row above or below (SWT {@code copyRowValues}): a single
     * selected row takes the values of its neighbor; a block of selected rows takes the values of its first (above) or
     * last (below) row. Cells that can't be edited, and LOB cells that were not read, are skipped.
     *
     * @return the number of cells set
     */
    int copyFromAdjacent(@NotNull int[] rowIndexes, @NotNull int[] columns, boolean fromAbove) throws DBException {
        int[] sorted = rowIndexes.clone();
        Arrays.sort(sorted);
        int count = 0;
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Copy row values")) {
            int start = 0;
            while (start < sorted.length) {
                int end = start;
                while (end + 1 < sorted.length && sorted[end + 1] == sorted[end] + 1) {
                    end++;
                }
                int first = sorted[start];
                int last = sorted[end];
                int sourceIndex = first == last ? (fromAbove ? first - 1 : first + 1) : (fromAbove ? first : last);
                if (sourceIndex >= 0 && sourceIndex < rows.size()) {
                    Row source = rows.get(sourceIndex);
                    for (int target = first; target <= last; target++) {
                        if (target == sourceIndex) {
                            continue;
                        }
                        for (int column : columns) {
                            DBDAttributeBinding binding = bindings[column];
                            if (!isCellEditable(target, column) || isUnread(source, column) || binding.isPseudoAttribute()
                                || binding.isAutoGenerated()) {
                                continue;
                            }
                            Object value = source.values[column];
                            if (!DBUtils.isNullValue(value)) {
                                value = binding.getValueHandler().getValueFromObject(session, binding, value, true, false);
                            }
                            setValue(target, column, value);
                            count++;
                        }
                    }
                }
                start = end + 1;
            }
        }
        return count;
    }

    /**
     * Sets cells to their handler's default value (SWT "Set to default": {@link DBDValueDefaultGenerator}, e.g. the
     * current time of a date column). Columns whose handler has none are skipped.
     *
     * @return the number of cells set
     */
    int setDefaults(@NotNull int[] rowIndexes, @NotNull int[] columns) {
        int count = 0;
        try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Generate default value")) {
            for (int row : rowIndexes) {
                for (int column : columns) {
                    DBDAttributeBinding binding = bindings[column];
                    if (isInlineEditable(row, column) && binding.getValueHandler() instanceof DBDValueDefaultGenerator generator) {
                        setValue(row, column, generator.generateDefaultValue(session, binding));
                        count++;
                    }
                }
            }
        }
        return count;
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
        clearUndo();
    }

    /**
     * After a successful save: deleted rows are gone, the rest keep their values as the new read values (a saved
     * LOB value stays known, so the value panel shows it rather than the content read first).
     *
     * @param executedRows rows whose statements ran (others keep their changes)
     */
    void acceptSaved(@NotNull List<Row> executedRows) {
        for (Row row : executedRows) {
            if (row.state == RowState.REMOVED) {
                rows.remove(row);
            } else {
                if (row.state == RowState.ADDED) {
                    for (int i = 0; i < contentColumns.length; i++) {
                        if (contentColumns[i]) {
                            row.knownContent.set(i);
                        }
                    }
                }
                row.state = RowState.NORMAL;
                row.clearChanges();
            }
        }
        clearUndo();
    }

    /**
     * Replaces a row's values with values read again from the database (after a save). The refresh releases LOB
     * values; a LOB value the row knows is kept.
     */
    void replaceValues(@NotNull Row row, @NotNull Object[] values, @NotNull Object[] display) {
        if (rows.contains(row) && !row.isChanged() && row.state == RowState.NORMAL) {
            for (int i = 0; i < values.length && i < row.values.length; i++) {
                if (contentColumns[i] && row.knownContent.get(i)) {
                    values[i] = row.values[i];
                }
            }
            row.values = values;
            row.display = display;
        }
    }

    int indexOf(@NotNull Row row) {
        return rows.indexOf(row);
    }
}
