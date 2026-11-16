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
 * - 2026-11-16 LOB 열({@link #lobColumns})과 다시 읽기가 결과의 종류로 해당하는지({@link #appliesRequery}): 오른쪽 클릭 메뉴가 해당하지 않는
 *   명령을 숨김 (앱 변경 #137, docs/chromite/context-menus.md "결과 그리드")
 * - 2026-11-16 막는 이유가 연결인지를 문구 비교 대신 {@link Blocker}로 넘김({@link #requeryBlockedByConnection})
 * - 2026-11-17 문장 접두어가 있는 SELECT({@link SQLQuery#getStatementPrefixLength()})는 다시 읽지 않음(전처럼). 이제 종류가 SELECT로
 *   정해지지만 필터·행 수·페이지의 다시 쓰기가 접두어를 잃는다(앱 변경 #139, docs/chromite/swing-sql-exec-safety.md "문장 접두어")
 *
 * @param dataContainer     the query of the result (re-read with filters, next pages)
 * @param context           execution context of the fetch, or null if unknown
 * @param segmentSize       rows read at once (the engine's maximum row count), 0 if all rows were read
 * @param requeryBlocker    why the query can't be read again (sorting on the server, filters, next pages), or null
 * @param requeryBlockedByConnection the blocker is that the connection was closed when the result was read (it can be
 *                          read again once connected); false for what the query is and for a fetch that did not complete
 * @param unreadableColumns columns whose values were not kept: value objects other than LOBs, or read errors
 * @param lobColumns        columns whose fetched values were LOB value objects (also string columns a driver delivers as
 *                          LOBs, such as MariaDB {@code LONGTEXT}), as the edit model has them
 * @param cellValues        per row: the original values, {@link ResultSetCellContent} for LOBs, null where not kept
 */
record ResultSetSource(
    @NotNull DBSDataContainer dataContainer,
    @Nullable DBCExecutionContext context,
    long segmentSize,
    @Nullable String requeryBlocker,
    boolean requeryBlockedByConnection,
    @NotNull boolean[] unreadableColumns,
    @NotNull boolean[] lobColumns,
    @NotNull List<Object[]> cellValues
) {

    /**
     * Why a query can't be read again, and whether that is the connection (closed now) rather than what the query is.
     *
     * @param reason     the reason shown to the user
     * @param connection the connection is not open; the query itself could be read again
     */
    record Blocker(@NotNull String reason, boolean connection) {
    }

    /**
     * Why a query can't be read again with filters and row offsets, or null if it can (job thread: parses the query).
     */
    @Nullable
    static Blocker getRequeryBlocker(@NotNull DBSDataContainer dataContainer, @Nullable DBCExecutionContext context) {
        if (context == null || !context.isConnected()) {
            return new Blocker(EditorMessages.editor_common_not_connected, true);
        }
        if (!dataContainer.isFeatureSupported(DBSDataContainer.FEATURE_DATA_FILTER)) {
            return new Blocker(EditorMessages.editor_source_no_filters, false);
        }
        if (dataContainer instanceof SQLQueryDataContainer resultContainer && resultContainer.isWholeBatchOnly()) {
            return new Blocker(EditorMessages.editor_source_whole_batch, false);
        }
        if (dataContainer instanceof SQLQueryContainer queryContainer) {
            SQLScriptElement element = queryContainer.getQuery();
            // A prefixed SELECT (SQLDialect.getStatementPrefixLength): the filter, count and paging rewrites would lose the prefix
            if (!(element instanceof SQLQuery query) || query.getType() != SQLQueryType.SELECT || query.isModifying()
                || query.getStatementPrefixLength() > 0) {
                return new Blocker(EditorMessages.editor_source_not_single_select, false);
            }
        }
        return null;
    }

    boolean canRequery() {
        return requeryBlocker == null && context != null;
    }

    /**
     * Whether the query is one that can be read again (filters, sort on the server, counting): {@link #canRequery}
     * without the connection, which may be closed now. The other blockers are what the query is (not one SELECT, a
     * container without filters, a batch statement that is not known) or a fetch that did not complete.
     */
    boolean appliesRequery() {
        return context != null && (requeryBlocker == null || requeryBlockedByConnection);
    }
}
