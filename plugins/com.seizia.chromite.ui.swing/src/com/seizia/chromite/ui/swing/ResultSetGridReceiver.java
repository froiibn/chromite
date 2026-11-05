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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.DBDValue;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCResultSetMetaData;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;

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
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 F단계: XLSX 내보내기용으로 숫자·불리언·날짜 원래 값과 열 바인딩을 함께 넘김 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 */
final class ResultSetGridReceiver implements DBDDataReceiver {

    private static final Log log = Log.getLog(ResultSetGridReceiver.class);

    private final DBSDataContainer dataContainer;
    private final Consumer<ResultSetGrid.Data> consumer;
    private final List<Object[]> rows = new ArrayList<>();
    private final List<Object[]> values = new ArrayList<>();
    private final Set<Integer> failedColumns = new HashSet<>();
    private DBDAttributeBinding[] bindings;
    private long maxRows;
    private boolean delivered;

    /**
     * @param dataContainer data container of the query (column metadata context)
     * @param consumer      receives the fetched data in the job thread
     */
    ResultSetGridReceiver(@NotNull DBSDataContainer dataContainer, @NotNull Consumer<ResultSetGrid.Data> consumer) {
        this.dataContainer = dataContainer;
        this.consumer = consumer;
    }

    @Override
    public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) throws DBException {
        DBCResultSetMetaData metaData = resultSet.getMeta();
        if (metaData == null) {
            throw new DBException("Null result set metadata");
        }
        this.bindings = DBUtils.getAttributeBindings(session, dataContainer, metaData);
        this.maxRows = maxRows;
        rows.clear();
        values.clear();
    }

    @Override
    public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        Object[] row = new Object[bindings.length];
        Object[] kept = null;
        for (int i = 0; i < bindings.length; i++) {
            DBDAttributeBinding binding = bindings[i];
            DBSAttributeBase attribute = binding.getAttribute();
            if (attribute == null) {
                row[i] = ResultSetGrid.NULL_VALUE;
                continue;
            }
            Object value = null;
            try {
                value = binding.getValueHandler().fetchValueObject(session, resultSet, attribute, binding.getOrdinalPosition());
                row[i] = DBUtils.isNullValue(value)
                    ? ResultSetGrid.NULL_VALUE
                    : binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
                if (isKeptValue(value)) {
                    if (kept == null) {
                        kept = new Object[bindings.length];
                    }
                    kept[i] = value;
                }
            } catch (Throwable e) {
                row[i] = "[error: " + e.getMessage() + "]";
                if (failedColumns.add(i)) {
                    log.warn("Can't read column '" + binding.getName() + "' value", e);
                }
            } finally {
                DBUtils.releaseValue(value);
            }
        }
        rows.add(row);
        values.add(kept);
    }

    /**
     * Values kept next to the display strings: plain value objects (fetched anew for each row), independent of the
     * result set and the session, and typed cells in a spreadsheet. Times of day ({@link java.sql.Time}) are not kept: as a date they would show 1970-01-01.
     */
    private static boolean isKeptValue(@Nullable Object value) {
        if (value instanceof DBDValue) {
            // Released after reading; its content is in the display string
            return false;
        }
        return value instanceof Number || value instanceof Boolean || (value instanceof Date && !(value instanceof Time));
    }

    @Override
    public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        deliver();
    }

    @Override
    public void close() {
        // fetchEnd is not called when the statement fails after fetchStart; show what was fetched
        deliver();
    }

    private void deliver() {
        if (delivered || bindings == null) {
            return;
        }
        delivered = true;
        String[] columns = new String[bindings.length];
        boolean[] numeric = new boolean[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            columns[i] = bindings[i].getName();
            numeric[i] = bindings[i].getDataKind() == DBPDataKind.NUMERIC;
        }
        boolean truncated = maxRows > 0 && rows.size() >= maxRows;
        // List.copyOf rejects null elements; rows without kept values have none
        consumer.accept(new ResultSetGrid.Data(columns, numeric, List.copyOf(rows), Collections.unmodifiableList(new ArrayList<>(values)),
            bindings, truncated));
    }
}
