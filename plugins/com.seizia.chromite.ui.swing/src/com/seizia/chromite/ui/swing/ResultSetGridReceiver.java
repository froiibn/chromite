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

import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDValue;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCResultSetMetaData;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 결과 집합 하나를 받아 {@link ResultSetGrid.Data}로 만드는 수신기. 실행 엔진이 Job 스레드에서 부른다.
 *
 * 설계: SWT의 {@code ResultSetDataReceiver}와 같은 방식으로 열 정보({@link DBUtils#getAttributeBindings})와 값
 * ({@code DBDValueHandler.fetchValueObject})을 읽되, 값은 바로 표시 문자열({@link DBDDisplayFormat#UI})로 바꾸고 놓아 준다
 * ({@link DBUtils#releaseValue}). 그래서 LOB 같은 값도 결과 집합이 열려 있는 동안 읽히고, EDT는 문자열만 다룬다(조회 전용 그리드).
 * 행은 모아 두었다가 {@link #fetchEnd}에서 한 번에 넘긴다(swing-frontend.md "Swing 편집기가 호출할 API"). 넘기는 곳({@code consumer})은
 * EDT로 옮기는 일을 맡는다. 최대 행 수는 엔진이 {@code fetchStart}의 {@code maxRows}로 알려 주고 그만큼만 읽으므로,
 * 받은 행 수가 그 값에 닿으면 잘렸다고 표시한다.
 * 값 하나를 읽다 난 오류는 그 칸에 오류 문구를 넣고 열마다 한 번만 기록한다(SWT 수신기와 같음).
 * 숫자, 불리언, 날짜·시각 값은 표시 문자열과 함께 원래 값도 남긴다({@link #isKeptValue}). XLSX 내보내기가 숫자·날짜 칸을 그 형식으로 쓰기 위해서다
 * (표시 문자열은 지역 설정의 소수점과 최대 소수 자릿수로 바뀌어 다시 읽으면 값이 달라질 수 있다). 이 값들은 결과 집합·세션에 묶이지 않은 단순 값 객체라(수신기가 행마다 새로 받음)
 * 결과 집합을 닫은 뒤에도 안전하고, LOB·배열 같은 값은 지금처럼 놓아 준다. 원래 값이 없는 행은 배열을 만들지 않는다.
 * 결과 편집(G단계): 값 객체({@code DBDValue})가 아닌 원래 값은 모두 {@code fetchEnd}까지 남기고, {@code fetchEnd}에서 SWT 수신기처럼
 * {@link DBExecUtils#bindAttributes}로 열을 테이블 열·행 식별자(기본 키)에 묶는다. 묶으면서 값 처리기가 바뀐 열은 표시 문자열을 다시 만든다.
 * 편집할 수 있는지는 {@link ResultSetEditModel#getReadOnlyReason}이 정하고, 편집할 수 있으면 원래 값을 {@link ResultSetEditModel}에 넘긴다.
 * 읽기 전용이면 원래 값은 위의 XLSX용 값만 남긴다. 값 객체로 온 열과 값을 읽지 못한 열은 편집하지 않는다.
 * 값 패널·정렬·필터·다음 페이지(결과 뷰어): 값 패널이 칸의 원래 값을 보여 주도록 행마다 칸 값을 함께 넘긴다({@link ResultSetSource#cellValues()}).
 * LOB({@link DBDContent})는 {@link ResultSetCellContent}로 바꿔 넘기고, 메모리에 없는 LOB는 패널이 나중에 읽도록 값 객체를 놓아 주지 않는다.
 * 공간 값({@link DBGeometry}, {@code data.gis})은 결과 공간 보기가 그리도록 칸 값에 그대로 남긴다(DB 자원을 잡지 않는 값). 열은 지금처럼 편집하지 않는다.
 * 다음 페이지를 읽을 때는 첫 결과의 열 바인딩을 그대로 쓰는 수신기({@link #forNextSegment})를 만든다(SWT {@code ResultSetDataReceiver}의
 * {@code nextSegmentRead}처럼 열 정보를 다시 만들지 않는다). 이 수신기는 테이블 메타데이터에 다시 묶지 않고 편집 모델도 만들지 않는다
 * (첫 결과의 편집 모델에 행을 붙이는 일은 {@link ResultSetViewerPanel}이 한다).
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 F단계: XLSX 내보내기용으로 숫자·불리언·날짜 원래 값과 열 바인딩을 함께 넘김 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 열을 테이블 메타데이터에 묶고(bindAttributes) 편집 모델을 함께 넘김 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 결과 뷰어: 값 패널용 칸 값과 LOB 내용, 다시 읽기 정보({@link ResultSetSource}), 다음 페이지 수신기
 *   (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 Query Library와 합침: 쿼리로는 다시 읽을 수 있어 보여도 다시 읽으면 안 되는 결과(이번 실행에만 바인딩한 매개변수 값)의 이유를
 *   받는 생성자 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 공간 값({@link DBGeometry})을 칸 값에 남김(결과 공간 보기, docs/chromite/swing-result-charts-spatial.md)
 * - 2026-11-08 다시 읽으면 안 되는 이유를 받는 생성자를 지움: 값을 바인딩한 결과도 바인딩의 데이터 컨테이너로 다시 읽음
 *   (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 열 머리글을 열의 레이블(별칭)로: 이름({@code getColumnName})을 써서 MySQL·MariaDB처럼 드라이버가 원래 열 이름을 주는 곳에서
 *   {@code AS} 별칭이 보이지 않았다. SWT 표처럼 레이블, 없으면 이름. "레이블 대신 열 이름 표시" 설정({@code resultset.column.label.ignore})이
 *   레이블을 이름으로 바꾼다 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class ResultSetGridReceiver implements DBDDataReceiver {

    private static final Log log = Log.getLog(ResultSetGridReceiver.class);

    private final DBSDataContainer dataContainer;
    private final Consumer<ResultSetGrid.Fetched> consumer;
    private final List<Object[]> rows = new ArrayList<>();
    private final List<Object[]> rawValues = new ArrayList<>();
    private final List<Object[]> cellValues = new ArrayList<>();
    private final Set<Integer> failedColumns = new HashSet<>();
    @Nullable
    private final DBDAttributeBinding[] segmentBindings;
    private DBDAttributeBinding[] bindings;
    private DBDValueHandler[] fetchHandlers;
    private boolean[] unreadableColumns;
    private long maxRows;
    private boolean delivered;

    /**
     * @param dataContainer data container of the query (column metadata context, row refresh after a save)
     * @param consumer      receives the fetched data in the job thread
     */
    ResultSetGridReceiver(@NotNull DBSDataContainer dataContainer, @NotNull Consumer<ResultSetGrid.Fetched> consumer) {
        this(dataContainer, null, consumer);
    }

    private ResultSetGridReceiver(
        @NotNull DBSDataContainer dataContainer,
        @Nullable DBDAttributeBinding[] segmentBindings,
        @NotNull Consumer<ResultSetGrid.Fetched> consumer
    ) {
        this.dataContainer = dataContainer;
        this.segmentBindings = segmentBindings;
        this.consumer = consumer;
    }

    /**
     * A receiver of the next rows of a result: it reads them with the result's bound columns and delivers them
     * without an edit model.
     */
    @NotNull
    static ResultSetGridReceiver forNextSegment(
        @NotNull DBSDataContainer dataContainer,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull Consumer<ResultSetGrid.Fetched> consumer
    ) {
        return new ResultSetGridReceiver(dataContainer, bindings, consumer);
    }

    @Override
    public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) throws DBException {
        DBCResultSetMetaData metaData = resultSet.getMeta();
        if (metaData == null) {
            throw new DBException("Null result set metadata");
        }
        if (segmentBindings != null) {
            if (metaData.getAttributes().size() != segmentBindings.length) {
                throw new DBException(EditorMessages.editor_grid_columns_changed);
            }
            this.bindings = segmentBindings;
        } else {
            this.bindings = DBUtils.getAttributeBindings(session, dataContainer, metaData);
        }
        this.fetchHandlers = new DBDValueHandler[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            fetchHandlers[i] = bindings[i].getValueHandler();
        }
        this.unreadableColumns = new boolean[bindings.length];
        this.maxRows = maxRows;
        rows.clear();
        rawValues.clear();
        cellValues.clear();
    }

    @Override
    public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        Object[] row = new Object[bindings.length];
        Object[] raw = new Object[bindings.length];
        Object[] cells = new Object[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            DBDAttributeBinding binding = bindings[i];
            DBSAttributeBase attribute = binding.getAttribute();
            if (attribute == null) {
                row[i] = ResultSetGrid.NULL_VALUE;
                unreadableColumns[i] = true;
                continue;
            }
            Object value = null;
            boolean keepValue = false;
            try {
                value = binding.getValueHandler().fetchValueObject(session, resultSet, attribute, binding.getOrdinalPosition());
                boolean isNull = DBUtils.isNullValue(value);
                row[i] = isNull
                    ? ResultSetGrid.NULL_VALUE
                    : binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
                if (value instanceof DBDValue) {
                    // Released below unless the value panel reads it later; the grid has its display string only
                    unreadableColumns[i] = true;
                    if (value instanceof DBDContent content && !isNull) {
                        ResultSetCellContent cellContent = ResultSetCellContent.capture(content);
                        cells[i] = cellContent;
                        keepValue = cellContent.isLive();
                    } else if (value instanceof DBGeometry geometry && !isNull) {
                        // For the spatial view; a geometry is a plain value (its release does nothing), so it is kept as it is
                        cells[i] = geometry;
                        keepValue = true;
                    }
                } else {
                    raw[i] = value;
                    cells[i] = value;
                }
            } catch (Throwable e) {
                row[i] = NLS.bind(EditorMessages.editor_grid_cell_error, ErrorText.describeLine(e));
                unreadableColumns[i] = true;
                if (failedColumns.add(i)) {
                    log.warn("Can't read column '" + binding.getName() + "' value", e);
                }
            } finally {
                if (!keepValue) {
                    DBUtils.releaseValue(value);
                }
            }
        }
        rows.add(row);
        rawValues.add(raw);
        cellValues.add(cells);
    }

    /**
     * Values kept next to the display strings: plain value objects (fetched anew for each row), independent of the
     * result set and the session, and typed cells in a spreadsheet. Times of day ({@link java.sql.Time}) are not kept: as a date they would show 1970-01-01.
     */
    static boolean isKeptValue(@Nullable Object value) {
        if (value instanceof DBDValue) {
            // Released after reading; its content is in the display string
            return false;
        }
        return value instanceof Number || value instanceof Boolean || (value instanceof Date && !(value instanceof Time));
    }

    @Override
    public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        if (delivered || bindings == null) {
            return;
        }
        if (segmentBindings != null) {
            // The next rows of a result: its columns are bound already, and its viewer keeps the edit model
            deliver(session.getExecutionContext(), null, null);
            return;
        }
        Throwable bindError = null;
        try {
            // Table columns, unique keys and value handlers of table columns, as SWT ResultSetDataReceiver.fetchEnd
            DBExecUtils.bindAttributes(session, dataContainer instanceof DBSEntity entity ? entity : null, resultSet, bindings, rawValues);
        } catch (Throwable e) {
            bindError = e;
            log.debug("Can't bind result set columns to table metadata", e);
        }
        refreshDisplayOfRebound();
        DBCExecutionContext context = session.getExecutionContext();
        String readOnlyReason = ResultSetEditModel.getReadOnlyReason(context, bindings, unreadableColumns, bindError);
        deliver(context, readOnlyReason == null ? context : null, readOnlyReason);
    }

    @Override
    public void close() {
        // fetchEnd is not called when the statement fails after fetchStart; show what was fetched
        deliver(null, null, EditorMessages.editor_grid_fetch_incomplete);
    }

    /**
     * Binding converts the values of a column whose table column has another value handler (e.g. strings that are
     * LOBs or enums); their display strings are made again with that handler.
     */
    private void refreshDisplayOfRebound() {
        for (int i = 0; i < bindings.length; i++) {
            DBDAttributeBinding binding = bindings[i];
            if (binding.getValueHandler() == fetchHandlers[i] || unreadableColumns[i]) {
                continue;
            }
            for (int r = 0; r < rows.size(); r++) {
                Object value = rawValues.get(r)[i];
                rows.get(r)[i] = DBUtils.isNullValue(value)
                    ? ResultSetGrid.NULL_VALUE
                    : binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
            }
        }
    }

    /**
     * @param context        context of the fetch, or null if it did not complete
     * @param editContext    context of an editable result, or null
     * @param readOnlyReason why the result is read-only, or null if it is editable
     */
    private void deliver(
        @Nullable DBCExecutionContext context,
        @Nullable DBCExecutionContext editContext,
        @Nullable String readOnlyReason
    ) {
        if (delivered || bindings == null) {
            return;
        }
        delivered = true;
        String[] columns = new String[bindings.length];
        boolean[] numeric = new boolean[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            // The label (alias) as the SWT grid shows it; resultset.column.label.ignore makes the label the column name
            columns[i] = CommonUtils.isEmpty(bindings[i].getLabel()) ? bindings[i].getName() : bindings[i].getLabel();
            numeric[i] = bindings[i].getDataKind() == DBPDataKind.NUMERIC;
        }
        List<Object[]> values = new ArrayList<>(rawValues.size());
        for (Object[] raw : rawValues) {
            values.add(keepValues(raw));
        }
        boolean truncated = maxRows > 0 && rows.size() >= maxRows;
        // List.copyOf rejects null elements; rows without kept values have none
        ResultSetGrid.Data data = new ResultSetGrid.Data(columns, numeric, List.copyOf(rows), Collections.unmodifiableList(values),
            bindings, truncated);
        ResultSetEditModel editModel = editContext == null ? null
            : new ResultSetEditModel(editContext, dataContainer, bindings, unreadableColumns, rawValues, rows);
        rawValues.clear();
        String blocker = context == null ? EditorMessages.editor_grid_fetch_incomplete : ResultSetSource.getRequeryBlocker(dataContainer, context);
        ResultSetSource source = new ResultSetSource(dataContainer, context, Math.max(maxRows, 0), blocker,
            unreadableColumns.clone(), List.copyOf(cellValues));
        cellValues.clear();
        consumer.accept(new ResultSetGrid.Fetched(data, editModel, readOnlyReason, source));
    }

    /**
     * The values of a row kept for the XLSX export ({@link #isKeptValue}), or null if it has none.
     */
    @Nullable
    static Object[] keepValues(@NotNull Object[] raw) {
        Object[] kept = null;
        for (int i = 0; i < raw.length; i++) {
            if (isKeptValue(raw[i])) {
                if (kept == null) {
                    kept = new Object[raw.length];
                }
                kept[i] = raw[i];
            }
        }
        return kept;
    }
}
