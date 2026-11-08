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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLQueryType;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.struct.DBSDataContainer;

import java.util.List;

/**
 * 결과 하나를 어디서 읽었는지와 값 패널용 칸 값. 수신기가 만들어 {@link ResultSetGrid.Fetched}에 담는다.
 *
 * 설계: 정렬·필터·다음 페이지는 같은 쿼리를 {@link DBSDataContainer#readData}로 다시 읽는다(SWT {@code ResultSetViewer.runDataPump}와 같음).
 * 그래서 결과마다 데이터 컨테이너, 실행 문맥, 한 번에 읽는 행 수를 남긴다. 다시 읽어도 되는지는 SWT처럼 컨테이너의
 * {@link DBSDataContainer#FEATURE_DATA_FILTER}와, 쿼리를 다시 실행해도 데이터가 바뀌지 않는지(SWT {@code verifyQuerySafety}가 묻는 것)로 정한다.
 * Swing은 묻는 대신 SELECT 하나({@link SQLQuery#getType()}이 SELECT이고 {@link SQLQuery#isModifying()}이 거짓)일 때만 다시 읽는다:
 * 프로시저 호출은 다시 실행하면 다른 문장까지 실행되고 두 번째 이후 결과 집합은 {@code readData}로 다시 얻을 수 없다.
 * GO 배치나 선택한 여러 문장의 결과는 컨테이너가 그 결과를 만든 문장만 가지므로 그 문장으로 판단하고, 문장을 정하지 못한 결과
 * ({@link SQLQueryDataContainer#isWholeBatchOnly()})는 배치 전체를 다시 실행해야 하므로 다시 읽지 않는다. 그 검사가 먼저다:
 * 배치 글자의 {@link SQLQuery#getType()}은 첫 문장의 종류라 {@code SELECT …; UPDATE …}도 SELECT로 보인다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 값 패널, 정렬·필터, 다음 페이지 (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 결과를 만든 문장을 정하지 못한 배치 결과는 다시 읽지 않고 그 이유를 보임 (docs/chromite/sql-batch-reread.md)
 *
 * @param dataContainer     the query of the result (re-read with filters, next pages)
 * @param context           execution context of the fetch, or null if unknown
 * @param segmentSize       rows read at once (the engine's maximum row count), 0 if all rows were read
 * @param requeryBlocker    why the query can't be read again (sorting on the server, filters, next pages), or null
 * @param unreadableColumns columns whose values were not kept: value objects other than LOBs, or read errors
 * @param cellValues        per row: the original values, {@link ResultSetCellContent} for LOBs, null where not kept
 */
record ResultSetSource(
    @NotNull DBSDataContainer dataContainer,
    @Nullable DBCExecutionContext context,
    long segmentSize,
    @Nullable String requeryBlocker,
    @NotNull boolean[] unreadableColumns,
    @NotNull List<Object[]> cellValues
) {

    /**
     * Why a query can't be read again with filters and row offsets, or null if it can (job thread: parses the query).
     */
    @Nullable
    static String getRequeryBlocker(@NotNull DBSDataContainer dataContainer, @Nullable DBCExecutionContext context) {
        if (context == null || !context.isConnected()) {
            return EditorMessages.editor_common_not_connected;
        }
        if (!dataContainer.isFeatureSupported(DBSDataContainer.FEATURE_DATA_FILTER)) {
            return EditorMessages.editor_source_no_filters;
        }
        if (dataContainer instanceof SQLQueryDataContainer resultContainer && resultContainer.isWholeBatchOnly()) {
            return EditorMessages.editor_source_whole_batch;
        }
        if (dataContainer instanceof SQLQueryContainer queryContainer) {
            SQLScriptElement element = queryContainer.getQuery();
            if (!(element instanceof SQLQuery query) || query.getType() != SQLQueryType.SELECT || query.isModifying()) {
                return EditorMessages.editor_source_not_single_select;
            }
        }
        return null;
    }

    boolean canRequery() {
        return requeryBlocker == null && context != null;
    }
}
