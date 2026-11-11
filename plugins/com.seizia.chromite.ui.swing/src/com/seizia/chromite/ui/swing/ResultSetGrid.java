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
import com.seizia.chromite.ext.postgresql.model.data.PostgreBitStringValueHandler;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.resultset.DataUpdaterJob;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 결과 집합 하나를 보여 주는 표(SSMS 결과 그리드 하나). 단일 테이블 결과는 편집하고 저장할 수 있다(SSMS "Edit Top 200 Rows").
 *
 * 설계: 값은 Job 스레드의 {@link ResultSetGridReceiver}가 결과 집합이 열려 있는 동안 표시 문자열로 바꿔 넘긴다
 * ({@link Data}). 그래서 EDT에서는 모델 계층(값 처리기, LOB 읽기)을 부르지 않고, 결과 집합을 닫은 뒤에도 값을 그대로 보여 줄 수 있다.
 * NULL은 표시 문자열과 구분되는 표지({@link #NULL_VALUE})로 두어 "NULL"이라는 문자열 값과 다르게 그린다(SSMS처럼 "NULL", 보조 글자색 text.secondary).
 * 숫자 열은 오른쪽 정렬한다. 열 너비는 머리글과 앞쪽 행들의 글자 너비로 정하고, 표는 자동 맞춤을 끄고 가로 스크롤한다.
 * 왼쪽에 SSMS처럼 행 번호 열을 둔다(행 머리글, 누르면 그 행 선택, 새 행은 {@code *}). 행 번호 칸은 선택·포커스와 관계없이 늘 그리드 바탕이다:
 * 선택한 행의 번호를 칠하면 셀 선택이 번호 열까지 이어졌다가 끊긴 것처럼 보였다.
 * 복사(Ctrl+C)는 {@link JTable}의 기본 전송 처리기가 선택한 칸의 표시 문자열을 탭으로 나눠 복사한다.
 * 오른쪽 클릭 메뉴(SSMS 결과 그리드와 같은 항목): Copy, Copy with Headers(Ctrl+Shift+C), Export to CSV…, Export to Excel (XLSX)…({@link ResultSetExport}).
 * Export Data…는 쿼리를 다시 실행해 모든 행을 모델의 모든 형식으로 내보내는 마법사({@link DataExportWizard})다. 다시 실행할 수 있는 결과
 * (그 결과를 만든 문장이 바꾸지 않는 SELECT 하나인 결과: 배치의 결과는 그 문장만 다시 실행한다, {@link Fetched#exportQuery()}, {@link DataExportWizard#canExportQuery})에서만 켜진다. CSV·XLSX 항목은 그대로 표에 보이는 행(편집 중인 값 포함)을 쓴다.
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
 *     <li>저장 전 스크립트 미리 보기({@link #confirmSave}): SWT {@code ResultSetViewer.applyChanges}처럼 설정 {@value #PREF_CONFIRM_BEFORE_SAVE}가
 *     켜졌거나 연결 유형의 "데이터 변경 확인"(기본: Test, Production)이 켜졌으면 저장할 문장을 먼저 보여 주고 Save를 눌러야 저장한다.
 *     Cancel이면 아무것도 실행하지 않고 바뀐 내용은 표에 남는다. 설정은 SWT처럼 연결의 설정 저장소에서 읽는다(연결별 값이 없으면 전체 설정).</li>
 *     <li>복사와 내보내기는 지금 표에 보이는 내용({@link #getCurrentData})을 쓴다.</li>
 *     <li>저장하고 커밋(SWT {@code applyAndCommitChanges}): 수동 커밋 연결에서만 보인다. 그 연결에서 Save는 변경을 열린 트랜잭션에 두고(사용자 규칙:
 *     묻지 않고 커밋하지 않음) 이 명령만 저장 뒤 커밋한다. 트랜잭션에 그 전 변경이 있으면 함께 커밋된다고 먼저 묻는다(기본 단추 Cancel).
 *     변경 미리 보기(SWT "Generate Script")는 설정과 관계없이 저장할 문장을 보여 주고 Save를 누르면 저장한다.</li>
 *     <li>형식별 칸 편집기: 불리언·ENUM 열은 값 목록({@link ChoiceCellEditor}), 날짜·시각 열은 글자 편집기에 달력 단추({@link ResultSetDateTimePicker}).
 *     LOB 열은 칸에서 고치지 않고 F2·두 번 누르기로 값 패널을 연다({@link Host#editInValuePanel}).</li>
 *     <li>행 복제, 위·아래 행 값 복사, 기본값으로, 칸 되돌리기, 실행 취소·다시 실행(칸 값), 붙여넣기(Ctrl+V, 여러 칸)와 특수 붙여넣기(새 행, NULL 표지)는
 *     SWT 결과 명령과 같은 단축키다. 한 동작의 변경은 한 번에 되돌린다.</li>
 *     <li>값 편집…(Shift+Enter, SWT "Open value editor")은 외래 키·SET·배열·구조체·hstore 칸의 대화상자({@link ResultSetValueDialogs})를 연다.
 *     글자 편집기 끝의 단추도 같다. UUID 만들기(Ctrl+Shift+Alt+U)는 고른 글자·UUID 칸에 새 UUID를 넣는다. 둘 다 칸 값만 바꾸고
 *     DB는 저장할 때 바뀐다(Save와 저장하고 커밋 규칙 그대로).</li>
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
 * - 2026-11-06 결과 뷰어: {@link Fetched}에 다시 읽기 정보와 칸 값({@link ResultSetSource}), 결과 뷰어가 쓰는 표 접근과 글자로 칸 값 바꾸기
 *   ({@link #getTable}, {@link #setCellText}). 정렬·필터·값 패널 등은 이 표를 감싸는 {@link ResultSetViewerPanel}에 있다
 *   (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 Export Data…를 {@link Host#exportData}로 열어 결과 뷰어가 필터·정렬을 넘김 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 {@link Host#rowsChanged}: 결과 뷰어가 다시 읽은 행 수를 쿼리 상태 줄에 알림 (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 꺼진 Export Data…의 도구 설명에 수신기가 아는 실제 이유(예: 이번 실행에만 바인딩한 값)를 보임 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 칸과 행 번호를 고정폭 글꼴 대신 UI 글꼴(Pretendard 12)로(사용자 요청). 숫자 폭이 글자마다 달라 행 번호 열 폭을
 *   가장 넓은 숫자로 계산 (docs/chromite/design-spec.md "적용 현황 — 그리드 글꼴 Pretendard")
 * - 2026-11-07 저장 전 스크립트 미리 보기: 설정과 연결 유형의 "데이터 변경 확인"이 다시 동작함 (docs/chromite/swing-frontend.md "저장 전 스크립트 미리 보기")
 * - 2026-11-08 촘촘한 모드: 행 번호의 행 높이가 그리드 행 높이를 따라가고, 열·행 번호 폭이 칸 여백의 차이만큼 바뀜(실행 중 밀도 전환)
 *   (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 Export Data… 도구 설명의 예를 고침: 값을 바인딩한 실행도 이제 다시 읽음 (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 저장하고 커밋, 변경 미리 보기, 불리언·ENUM·날짜 편집기, LOB 칸은 값 패널로, 행 복제·위아래 값 복사·기본값·칸 되돌리기,
 *   실행 취소, 붙여넣기·특수 붙여넣기 (docs/chromite/swing-grid-editing.md)
 * - 2026-11-08 결과 탐색: 결과 뷰어가 외래 키로 떠나며 남기는 결과의 변경을 버리도록 {@link #cancelChanges}를 열고, 읽기 전용 줄에
 *   "키가 없음"이면 가상 키 정하기 링크({@link Host#defineVirtualKey}) (docs/chromite/swing-grid-navigation.md)
 * - 2026-11-08 값 편집…(참조 값·SET·배열·구조체·hstore 대화상자), UUID 만들기, 비트 문자열 칸은 0·1만, 결과 뷰어의 값 명령이 쓰는
 *   상태 알림({@link #showStatus}) (docs/chromite/swing-value-editors.md)
 * - 2026-11-08 결과 행에서 SQL 만들기: 만든 문장을 쿼리 창에 여는 {@link Host#openScript}·{@link Host#canOpenScript}
 *   (docs/chromite/swing-result-generate-sql.md)
 * - 2026-11-08 Export Data 쿼리를 첫 결과 집합만이 아니라 쿼리 창의 모든 결과가 가짐: 배치 결과도 그 결과를 만든 문장으로 다시 실행
 *   (docs/chromite/sql-batch-reread.md)
 * - 2026-11-08 행 번호 칸에 선택·포커스 바탕을 칠하지 않음, 그 칠하기만을 위한 선택·포커스 리스너 삭제(사용자 요청)
 *   (docs/chromite/swing-result-viewer.md "표 편의 기능")
 * - 2026-11-09 단축키를 플랫폼 명령 키로({@link UiKeys}: macOS Command, 앱 변경 #24) (docs/chromite/shortcuts.md), macOS 다시 실행 ⇧⌘Z·아래 행 값 복사 ⇧⌘D
 * - 2026-11-12 행 번호 폭 주석: UI 글꼴이 운영체제 글꼴이 되어 비례 숫자를 Pretendard로 단정하지 않음 (앱 변경 #67)
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
     * Show the script before every save (SWT {@code ResultSetPreferences.RESULT_SET_CONFIRM_BEFORE_SAVE}, off by default).
     */
    static final String PREF_CONFIRM_BEFORE_SAVE = "resultset.confirm.beforeSave"; //$NON-NLS-1$

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
     * What a receiver delivers: the data, either the edit model of an editable result or why it is read-only, and
     * where the result was read from (with the cell values of the value panel).
     *
     * @param exportQuery the query container that reads this result again on its own (the statement of the query
     *                    window's run that produced it; "Export Data…" runs it again for all rows), or null
     */
    record Fetched(
        @NotNull Data data,
        @Nullable ResultSetEditModel editModel,
        @Nullable String readOnlyReason,
        @NotNull ResultSetSource source,
        @Nullable DBSDataContainer exportQuery
    ) {
        Fetched(@NotNull Data data, @Nullable ResultSetEditModel editModel, @Nullable String readOnlyReason, @NotNull ResultSetSource source) {
            this(data, editModel, readOnlyReason, source, null);
        }

        @NotNull
        Fetched withExportQuery(@NotNull DBSDataContainer query) {
            return new Fetched(data, editModel, readOnlyReason, source, query);
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

        /**
         * The rows of a result changed after its query ran: the result viewer read it again (filter, server-side sort) or
         * read more rows.
         */
        default void rowsChanged() {
        }

        /**
         * "Export Data…": runs the result's query again to export all rows. The result viewer overrides it to apply
         * its filter and sort.
         */
        default void exportData(@NotNull Component parent, @NotNull DBSDataContainer query) {
            DataExportWizard.openForQuery(parent, query, null, null);
        }

        /**
         * Edit Cell (F2, double click) on a LOB cell: its value is edited in the value panel, which the result viewer
         * opens. A grid without a viewer does nothing.
         */
        default void editInValuePanel() {
        }

        /**
         * "Define Virtual Key…" on a result that is read-only for want of a unique key, or null when the host can't
         * define one (a grid without a result viewer). The result viewer defines it and reads the result again.
         */
        @Nullable
        default Runnable defineVirtualKey() {
            return null;
        }

        /**
         * Whether {@link #openScript} opens a query window (a grid of a query window); otherwise statements are only
         * copied.
         */
        default boolean canOpenScript() {
            return false;
        }

        /**
         * Opens statements made from a result (Generate SQL) in a new query window of the connection, without running
         * them.
         */
        default void openScript(@NotNull DBPDataSourceContainer container, @NotNull String script) {
        }
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
    private Action saveCommitAction;
    private JButton saveCommitButton;
    private Action cancelChangesAction;
    private Action previewChangesAction;
    private Action setNullAction;
    private Action setDefaultAction;
    private Action resetCellAction;
    private Action insertRowAction;
    private Action duplicateRowAction;
    private Action copyFromAboveAction;
    private Action copyFromBelowAction;
    private Action deleteRowsAction;
    private Action editCellAction;
    private Action editValueAction;
    private Action generateUuidAction;
    private Action undoAction;
    private Action redoAction;
    private Action pasteAction;
    private Action pasteSpecialAction;
    private Action copyAction;
    private Action copyWithHeadersAction;
    @Nullable
    private TextCellEditor textEditor;
    @Nullable
    private ChoiceCellEditor choiceEditor;
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

            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                // Boolean and ENUM columns pick from a list; the others are typed (dates with a picker)
                if (choiceEditor != null && ChoiceCellEditor.hasChoices(editModel, convertColumnIndexToModel(column))) {
                    return choiceEditor;
                }
                return super.getCellEditor(row, column);
            }
        };
        // DataGrip grid: vertical lines in grid.line, horizontal lines in the lighter grid.lineSubtle (one JTable grid
        // color can't do both), column names left-aligned. The cells keep the look and feel's Table.font, the UI font
        // like the column header (user decision 2026-11-07, design-spec "font.grid")
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
            textEditor = new TextCellEditor();
            choiceEditor = new ChoiceCellEditor();
            table.setDefaultEditor(Object.class, textEditor);
        }

        JScrollPane scrollPane = new JScrollPane(table);
        rowHeader = createRowHeader();
        scrollPane.setRowHeaderView(rowHeader);
        add(createStatusStrip(fetched.readOnlyReason()), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        fitColumnWidths(table, data);
        followCellPadding();
        installContextMenu(fetched.source().requeryBlocker());
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
     * The table of the grid, for the result viewer around it (selection, header, menu).
     */
    @NotNull
    JTable getTable() {
        return table;
    }

    /**
     * Sets a cell from text as the cell editor does: the column's value handler makes the value (value panel,
     * record view).
     *
     * @param row    row index of the table
     * @param column model column index
     * @return null if the value was set, otherwise why it was not
     */
    @Nullable
    String setCellText(int row, int column, @NotNull String text) {
        return setCellObject(row, column, text);
    }

    /**
     * Sets a cell from an editor's value (text, or the bytes or text of a file loaded into a LOB cell): the column's
     * value handler makes the value.
     *
     * @return null if the value was set, otherwise why it was not
     */
    @Nullable
    String setCellObject(int row, int column, @NotNull Object input) {
        if (editModel == null || !editModel.isCellEditable(row, column)) {
            return EditorMessages.editor_grid_cell_read_only;
        }
        if (!stopEditing()) {
            return EditorMessages.editor_grid_invalid_editor;
        }
        try {
            editModel.setValue(row, column, editModel.parseObject(column, input));
        } catch (DBCException e) {
            return NLS.bind(EditorMessages.editor_grid_invalid_value, data.columns()[column], ErrorText.describeLine(e));
        }
        repaintKeepingSelection();
        return null;
    }

    /**
     * The edit model of an editable result, or null (the result viewer's access for LOB files and the value panel).
     */
    @Nullable
    ResultSetEditModel getEditModel() {
        return editModel;
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
            saveAction = createAction(EditorMessages.editor_common_save, UiKeys.command(KeyEvent.VK_S), () -> save(false));
            saveAction.putValue(Action.SHORT_DESCRIPTION, UiKeys.format(EditorMessages.editor_grid_save_tip, saveAction));
            saveCommitAction = createAction(GridEditMessages.gridedit_save_commit, null, () -> save(false, true, false));
            saveCommitAction.putValue(Action.SHORT_DESCRIPTION, GridEditMessages.gridedit_save_commit_tip);
            cancelChangesAction = createAction(EditorMessages.editor_common_cancel, null, this::cancelChanges);
            cancelChangesAction.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_grid_cancel_tip);
            strip.add(createStripButton(saveAction));
            // Only in manual commit mode, where Save leaves the changes in the open transaction
            saveCommitButton = createStripButton(saveCommitAction);
            strip.add(saveCommitButton);
            strip.add(createStripButton(cancelChangesAction));
        } else {
            statusLabel.setText(NLS.bind(EditorMessages.editor_grid_read_only, CommonUtils.notEmpty(readOnlyReason)));
        }
        strip.add(statusLabel);
        // No unique key: a virtual key makes the table editable (SWT ValidateUniqueKeyUsageDialog)
        Runnable defineKey = editModel == null && EditorMessages.editor_grid_reason_no_unique_key.equals(readOnlyReason)
            ? host.defineVirtualKey() : null;
        if (defineKey != null) {
            Action defineKeyAction = createAction(GridNavMessages.gridnav_vkey_define, null, defineKey);
            defineKeyAction.putValue(Action.SHORT_DESCRIPTION, GridNavMessages.gridnav_vkey_define_tip);
            strip.add(createStripButton(defineKeyAction));
        }
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
        boolean manualCommit = isManualCommit();
        saveAction.setEnabled(dirty && !saving);
        saveCommitAction.setEnabled(dirty && !saving && manualCommit);
        saveCommitButton.setVisible(manualCommit);
        cancelChangesAction.setEnabled(dirty && !saving);
        previewChangesAction.setEnabled(dirty && !saving);
        editCellAction.setEnabled(!saving);
        editValueAction.setEnabled(!saving && hasRows);
        setNullAction.setEnabled(!saving);
        setDefaultAction.setEnabled(!saving && hasRows);
        generateUuidAction.setEnabled(!saving && hasRows);
        resetCellAction.setEnabled(!saving && dirty);
        insertRowAction.setEnabled(!saving && editModel.isInsertSupported());
        duplicateRowAction.setEnabled(!saving && hasRows && editModel.isInsertSupported());
        copyFromAboveAction.setEnabled(!saving && hasRows);
        copyFromBelowAction.setEnabled(!saving && hasRows);
        deleteRowsAction.setEnabled(!saving && hasRows && editModel.isDeleteSupported());
        undoAction.setEnabled(!saving && editModel.canUndo());
        redoAction.setEnabled(!saving && editModel.canRedo());
        pasteAction.setEnabled(!saving && hasRows);
        pasteSpecialAction.setEnabled(!saving);
        String text;
        if (saving) {
            text = EditorMessages.editor_grid_saving;
        } else if (transientStatus != null) {
            text = transientStatus;
        } else {
            int changes = editModel.getChangeCount();
            String table = editModel.getSingleSource() != null ? editModel.getSingleSource().getName() : "";
            text = changes == 0 ? NLS.bind(EditorMessages.editor_grid_editable_none, table)
                : NLS.bind(changes == 1 ? EditorMessages.editor_grid_editable_one : EditorMessages.editor_grid_editable_many, table, changes);
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
        if (row < 0 || column < 0) {
            return;
        }
        if (isPanelEditedCell(row, column)) {
            host.editInValuePanel();
            return;
        }
        if (table.editCellAt(row, column)) {
            Component editor = table.getEditorComponent();
            if (editor != null) {
                editor.requestFocusInWindow();
            }
        }
    }

    /**
     * An editable LOB cell: edited in the value panel, not in the grid.
     */
    private boolean isPanelEditedCell(int row, int viewColumn) {
        int column = table.convertColumnIndexToModel(viewColumn);
        return editModel != null && editModel.isContentColumn(column) && editModel.isCellEditable(row, column);
    }

    /**
     * Model column indexes of the selected columns.
     */
    @NotNull
    private int[] getSelectedModelColumns() {
        int[] columns = table.getSelectedColumns();
        for (int i = 0; i < columns.length; i++) {
            columns[i] = table.convertColumnIndexToModel(columns[i]);
        }
        return columns;
    }

    private void setSelectedToNull() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        boolean changed = false;
        editModel.beginStep();
        try {
            for (int row : table.getSelectedRows()) {
                for (int modelColumn : getSelectedModelColumns()) {
                    if (editModel.isCellEditable(row, modelColumn)) {
                        editModel.setValue(row, modelColumn, null);
                        changed = true;
                    }
                }
            }
        } finally {
            editModel.endStep();
        }
        if (changed) {
            repaintKeepingSelection();
        }
    }

    /**
     * Set to Default (SWT {@code cell.setDefault}): the handler's default value, e.g. the current time.
     */
    private void setSelectedToDefault() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int count;
        editModel.beginStep();
        try {
            count = editModel.setDefaults(table.getSelectedRows(), getSelectedModelColumns());
        } finally {
            editModel.endStep();
        }
        repaintKeepingSelection();
        if (count == 0) {
            setTransientStatus(GridEditMessages.gridedit_no_default);
        }
    }

    /**
     * Edit Value… (SWT "Open value editor", Shift+Enter): the value dialog of the selected cell
     * ({@link ResultSetValueDialogs}); a LOB cell opens the value panel as Edit Cell does.
     */
    private void editValueInDialog() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int row = table.getSelectedRow();
        int viewColumn = table.getSelectedColumn();
        if (row < 0 || viewColumn < 0) {
            return;
        }
        if (isPanelEditedCell(row, viewColumn)) {
            host.editInValuePanel();
            return;
        }
        int column = table.convertColumnIndexToModel(viewColumn);
        if (!editModel.isInlineEditable(row, column)) {
            setTransientStatus(EditorMessages.editor_grid_cell_read_only);
            return;
        }
        if (ResultSetValueDialogs.getKind(editModel, column) == null) {
            setTransientStatus(ValueEditMessages.valueedit_no_dialog);
            return;
        }
        ResultSetValueDialogs.Picked picked = ResultSetValueDialogs.edit(table, editModel, row, column);
        if (picked == null) {
            return;
        }
        try {
            editModel.setValue(row, column, picked.input() == null ? null : editModel.parseObject(column, picked.input()));
        } catch (DBCException e) {
            setTransientStatus(NLS.bind(EditorMessages.editor_grid_invalid_value_status, data.columns()[column], ErrorText.describeLine(e)));
            return;
        }
        repaintKeepingSelection();
    }

    /**
     * Generate UUID (SWT {@code generate.uuid}): a new random UUID in each selected text or UUID cell, made by the
     * column's handler (a UUID object, or its text). Other cells are skipped and counted. One undo step.
     */
    private void generateUuids() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int set = 0;
        int skipped = 0;
        editModel.beginStep();
        try {
            for (int row : table.getSelectedRows()) {
                for (int column : getSelectedModelColumns()) {
                    if (!editModel.isInlineEditable(row, column) || !isUuidColumn(data.bindings()[column])) {
                        skipped++;
                        continue;
                    }
                    try {
                        editModel.setValue(row, column, editModel.parseObject(column, UUID.randomUUID().toString()));
                        set++;
                    } catch (DBCException e) {
                        skipped++;
                    }
                }
            }
        } finally {
            editModel.endStep();
        }
        repaintKeepingSelection();
        if (skipped > 0) {
            setTransientStatus(NLS.bind(ValueEditMessages.valueedit_uuid_skipped, set, skipped));
        }
    }

    /**
     * A column that can hold a UUID: text (SQL Server {@code uniqueidentifier}, MariaDB {@code UUID}, character
     * columns), an {@code OBJECT} column edited as text (PostgreSQL {@code uuid}), or a UUID value handler.
     */
    private static boolean isUuidColumn(@NotNull DBDAttributeBinding binding) {
        if (binding.getDataKind() == DBPDataKind.STRING || binding.getDataKind() == DBPDataKind.OBJECT) {
            return true;
        }
        try {
            return UUID.class.isAssignableFrom(binding.getValueHandler().getValueObjectType(binding));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reset Cell (SWT {@code cell.reset}): the selected cells get the values read from the database back.
     */
    private void resetSelectedCells() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        editModel.beginStep();
        try {
            for (int row : table.getSelectedRows()) {
                for (int modelColumn : getSelectedModelColumns()) {
                    editModel.resetCell(row, modelColumn);
                }
            }
        } finally {
            editModel.endStep();
        }
        repaintKeepingSelection();
    }

    /**
     * Duplicate Row (SWT {@code row.copy}): copies of the selected rows below them.
     */
    private void duplicateSelectedRows() {
        if (editModel == null || !stopEditing()) {
            return;
        }
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) {
            return;
        }
        int position = table.getSelectionModel().getMaxSelectionIndex() + 1;
        int skipped;
        try {
            skipped = editModel.duplicateRows(selected, position);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridEditMessages.gridedit_duplicate_row, EditorMessages.editor_grid_cant_add_row, e);
            return;
        }
        int column = Math.max(table.getSelectedColumn(), 0);
        dataChanged();
        table.changeSelection(position, column, false, false);
        table.changeSelection(position + selected.length - 1, column, false, true);
        table.requestFocusInWindow();
        if (skipped > 0) {
            setTransientStatus(NLS.bind(GridEditMessages.gridedit_duplicate_lob_skipped, skipped));
        }
    }

    /**
     * Copy from the Row Above / Below (SWT {@code row.copy.from.above/below}).
     */
    private void copyFromAdjacentRow(boolean fromAbove) {
        if (editModel == null || !stopEditing()) {
            return;
        }
        editModel.beginStep();
        try {
            editModel.copyFromAdjacent(table.getSelectedRows(), getSelectedModelColumns(), fromAbove);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(fromAbove ? GridEditMessages.gridedit_copy_from_above : GridEditMessages.gridedit_copy_from_below,
                GridEditMessages.gridedit_cant_copy_values, e);
        } finally {
            editModel.endStep();
        }
        repaintKeepingSelection();
    }

    private void undo() {
        if (editModel != null && stopEditing() && editModel.undo()) {
            repaintKeepingSelection();
        }
    }

    private void redo() {
        if (editModel != null && stopEditing() && editModel.redo()) {
            repaintKeepingSelection();
        }
    }

    //////////////////////////////////////////////////////////
    // Paste (EDT)

    /**
     * Pastes the clipboard text into the cells from the selected one on (SWT paste and Paste Special): lines are
     * rows and tabs separate the values, as a spreadsheet copies them. One value with several selected cells fills
     * them all. Values go through the columns' handlers; cells that can't be edited and values a column rejects are
     * skipped and counted. All changes are one undo step.
     *
     * @param options null for a plain paste (no new rows, no NULL marker)
     */
    private void paste(@Nullable ResultSetPaste.Options options) {
        if (editModel == null || !stopEditing()) {
            return;
        }
        String text = ResultSetPaste.readClipboard();
        if (text == null) {
            return;
        }
        ResultSetPaste.Options effective = options != null ? options : ResultSetPaste.Options.PLAIN;
        List<String[]> lines = ResultSetPaste.parse(text, effective.ignoreQuotes());
        if (lines.isEmpty()) {
            return;
        }
        int startRow = table.getSelectionModel().getLeadSelectionIndex();
        int startColumn = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (startRow < 0 || startColumn < 0) {
            if (!effective.insertNewRows() || table.getColumnCount() == 0) {
                return;
            }
            startRow = gridModel.getRowCount();
            startColumn = 0;
        }
        int[] counts = new int[2];
        String[] firstError = new String[1];
        editModel.beginStep();
        try {
            if (lines.size() == 1 && lines.getFirst().length == 1 && table.getSelectedRowCount() * table.getSelectedColumnCount() > 1) {
                // One value into every selected cell
                for (int row : table.getSelectedRows()) {
                    for (int column : getSelectedModelColumns()) {
                        pasteValue(row, column, lines.getFirst()[0], effective, counts, firstError);
                    }
                }
            } else {
                for (int i = 0; i < lines.size(); i++) {
                    int row = startRow + i;
                    if (row >= gridModel.getRowCount()) {
                        if (!effective.insertNewRows() || !editModel.isInsertSupported()) {
                            counts[1] += lines.size() - i;
                            break;
                        }
                        editModel.addRow(gridModel.getRowCount());
                    }
                    String[] values = lines.get(i);
                    for (int j = 0; j < values.length; j++) {
                        int viewColumn = startColumn + j;
                        if (viewColumn >= table.getColumnCount()) {
                            counts[1] += values.length - j;
                            break;
                        }
                        pasteValue(row, table.convertColumnIndexToModel(viewColumn), values[j], effective, counts, firstError);
                    }
                }
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridEditMessages.gridedit_paste, EditorMessages.editor_grid_cant_add_row, e);
        } finally {
            editModel.endStep();
        }
        dataChanged();
        if (startRow < gridModel.getRowCount()) {
            table.changeSelection(startRow, startColumn, false, false);
        }
        table.requestFocusInWindow();
        if (counts[1] > 0) {
            setTransientStatus(NLS.bind(GridEditMessages.gridedit_paste_skipped, new Object[]{counts[0], counts[1],
                firstError[0] != null ? firstError[0] : GridEditMessages.gridedit_paste_skipped_readonly}));
        }
    }

    private void pasteValue(
        int row,
        int column,
        @NotNull String text,
        @NotNull ResultSetPaste.Options options,
        @NotNull int[] counts,
        @NotNull String[] firstError
    ) {
        // LOB cells take text only when their column holds text (JSON, XML, CLOB)
        if (!editModel.isCellEditable(row, column) || (editModel.isContentColumn(column) && !editModel.isTextContentColumn(column))) {
            counts[1]++;
            return;
        }
        try {
            Object value = options.isNull(text) ? null : editModel.parseValue(column, text);
            editModel.setValue(row, column, value);
            counts[0]++;
        } catch (DBCException e) {
            counts[1]++;
            if (firstError[0] == null) {
                firstError[0] = NLS.bind(EditorMessages.editor_grid_invalid_value, data.columns()[column], ErrorText.describeLine(e));
            }
        }
    }

    private void pasteSpecial() {
        ResultSetPaste.Options options = ResultSetPaste.askOptions(this);
        if (options != null) {
            paste(options);
        }
    }

    private void setTransientStatus(@NotNull String text) {
        transientStatus = text;
        updateState();
    }

    /**
     * Shows a notice in the status strip of an editable result until the next change (the result viewer's external
     * editor). A read-only result keeps its reason there.
     */
    void showStatus(@NotNull String text) {
        setTransientStatus(text);
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
            DBWorkbench.getPlatformUI().showError(EditorMessages.editor_grid_insert_row, EditorMessages.editor_grid_cant_add_row, e);
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

    /**
     * Reverts all pending changes: Cancel, and the result viewer when it keeps the result for going back after a
     * foreign key navigation whose "discard the changes?" the user confirmed.
     */
    void cancelChanges() {
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
        return save(wait, false, false);
    }

    /**
     * @param commit  Save and Commit: in manual commit mode, commit the open transaction after the statements (SWT
     *                {@code applyAndCommitChanges}); if the transaction already holds earlier changes, ask first
     *                (Cancel is the default). In auto-commit mode the save commits anyway.
     * @param preview show the statements first even when the option and the connection type don't ask for it
     *                (SWT "Generate Script")
     */
    private boolean save(boolean wait, boolean commit, boolean preview) {
        if (editModel == null || editModel.isSaving() || !stopEditing()) {
            return false;
        }
        if (!editModel.isDirty()) {
            return true;
        }
        boolean commitAfter = commit && isManualCommit();
        if (commitAfter && !confirmCommitEarlierChanges()) {
            return false;
        }
        ResultSetEditPersister persister = new ResultSetEditPersister(editModel);
        String tableName = editModel.getSingleSource() != null ? editModel.getSingleSource().getName() : EditorMessages.editor_grid_results;
        try {
            persister.checkEntityIdentifiers();
        } catch (DBException e) {
            reportSaveError(tableName, e, ResultSetEditPersister.TransactionMode.NONE, false);
            return false;
        }
        if ((preview || isSavePreviewRequired()) && !confirmSave(persister, tableName)) {
            return false;
        }
        long startTime = System.currentTimeMillis();
        editModel.setSaving(true);
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        updateState();
        DataUpdaterJob job = persister.createSaveJob(commitAfter, outcome -> finishSave(tableName, outcome, startTime));
        if (wait) {
            ProgressDialog.waitForJob(EditorMessages.editor_grid_save_job, job);
            return job.getError() == null;
        }
        job.schedule();
        return true;
    }

    /**
     * The result's session is in manual commit mode (the query manager's record, no driver call).
     */
    private boolean isManualCommit() {
        return editModel != null && QMUtils.getTransactionState(editModel.getExecutionContext()).isTransactionMode();
    }

    /**
     * Save and Commit commits the whole open transaction. When it already holds changes made before (other
     * statements of the query window, an earlier save), the user confirms that they are committed too.
     *
     * @return true to go on
     */
    private boolean confirmCommitEarlierChanges() {
        QMTransactionState state = QMUtils.getTransactionState(editModel.getExecutionContext());
        if (!state.isOpen() || state.getUpdateCount() == 0) {
            return true;
        }
        return SwingPlatformUI.confirmChange(this, NLS.bind(GridEditMessages.gridedit_commit_earlier, state.getUpdateCount()),
            GridEditMessages.gridedit_save_commit, GridEditMessages.gridedit_save_commit, JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Whether the save shows its statements first, as SWT {@code ResultSetViewer.applyChanges}: the option or the
     * connection type's "Confirm data changes".
     */
    private boolean isSavePreviewRequired() {
        DBPDataSourceContainer container = editModel.getExecutionContext().getDataSource().getContainer();
        return container.getPreferenceStore().getBoolean(PREF_CONFIRM_BEFORE_SAVE)
            || container.getConnectionConfiguration().getConnectionType().isConfirmDataChange();
    }

    /**
     * Shows the statements of the save and asks whether to run them (SWT {@code SavePreviewDialog}).
     *
     * @return true to save; false if the user canceled or the statements could not be made (the changes stay pending)
     */
    private boolean confirmSave(@NotNull ResultSetEditPersister persister, @NotNull String tableName) {
        DataUpdaterJob job = persister.createScriptJob();
        ProgressDialog.waitForJob(EditorMessages.editor_grid_preview_job, job);
        if (job.getError() != null) {
            reportSaveError(tableName, job.getError(), ResultSetEditPersister.TransactionMode.NONE, false);
            return false;
        }
        String header = NLS.bind(EditorMessages.editor_grid_preview_header, new Object[]{
            editModel.getExecutionContext().getDataSource().getContainer().getName(),
            persister.getInsertCount(), persister.getUpdateCount(), persister.getDeleteCount()});
        if (ScriptPreviewDialog.confirmSave(this, EditorMessages.editor_grid_preview_title, header,
            persister.getScript(EditorMessages.editor_grid_preview_comment), EditorMessages.editor_common_save)) {
            return true;
        }
        transientStatus = NLS.bind(EditorMessages.editor_grid_save_canceled, getChangeCount());
        updateState();
        return false;
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
            host.appendMessage(NLS.bind(EditorMessages.editor_grid_saved,
                new Object[]{tableName, outcome.inserted(), outcome.updated(), outcome.deleted()}));
            if (outcome.zeroRowChanges() > 0) {
                host.appendMessage(NLS.bind(EditorMessages.editor_grid_zero_rows, outcome.zeroRowChanges()));
            }
            if (outcome.committed()) {
                host.appendMessage(GridEditMessages.gridedit_committed);
            } else if (outcome.transaction() == ResultSetEditPersister.TransactionMode.OPEN_TRANSACTION) {
                host.appendMessage(EditorMessages.editor_grid_manual_commit);
            }
            if (outcome.commitError() != null) {
                host.appendError(GridEditMessages.gridedit_commit_failed + "\n" + ErrorText.describe(outcome.commitError())); //$NON-NLS-1$
                host.showMessages();
            }
            host.appendMessage(NLS.bind(EditorMessages.editor_grid_save_time, System.currentTimeMillis() - startTime));
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
            case SAVE_TRANSACTION -> EditorMessages.editor_grid_rollback_save;
            case OPEN_TRANSACTION -> EditorMessages.editor_grid_rollback_open;
            case NONE -> partlyApplied
                ? EditorMessages.editor_grid_partly_applied
                : EditorMessages.editor_grid_nothing_saved;
        };
        host.appendError(NLS.bind(EditorMessages.editor_grid_cant_save, tableName, applied) + "\n" + ErrorText.describe(error)); //$NON-NLS-1$
        host.showMessages();
        transientStatus = NLS.bind(EditorMessages.editor_grid_save_failed, getChangeCount());
        updateState();
    }

    //////////////////////////////////////////////////////////
    // Actions and menus

    /**
     * @param requeryBlocker why the result's query can't be read again, as the receiver knows it (e.g. the connection
     *                       was closed, or the query is not a single SELECT), or null
     */
    private void installContextMenu(@Nullable String requeryBlocker) {
        copyAction = createAction(EditorMessages.editor_grid_copy, UiKeys.command(KeyEvent.VK_C),
            () -> TransferHandler.getCopyAction().actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"))); //$NON-NLS-1$
        copyWithHeadersAction = createAction(EditorMessages.editor_grid_copy_with_headers,
            UiKeys.command(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK),
            () -> DBWorkbench.getPlatformUI().copyTextToClipboard(ResultSetExport.copySelection(table, getCurrentData(), true), false));
        Action exportCsvAction = createAction(EditorMessages.editor_grid_export_csv, null, () -> ResultSetExport.exportCsv(table, getCurrentData()));
        Action exportXlsxAction = createAction(EditorMessages.editor_grid_export_xlsx, null, () -> ResultSetExport.exportXlsx(table, getCurrentData()));
        Action exportDataAction = createAction(EditorMessages.editor_grid_export_data, null, () -> {
            if (exportQuery != null) {
                host.exportData(table, exportQuery);
            }
        });
        boolean queryExportable = DataExportWizard.canExportQuery(exportQuery);
        exportDataAction.setEnabled(queryExportable);
        // Disabled: the known reason first (e.g. the connection was closed: the general rule would mislead)
        exportDataAction.putValue(Action.SHORT_DESCRIPTION, queryExportable
            ? EditorMessages.editor_grid_export_data_tip
            : requeryBlocker != null
            ? NLS.bind(EditorMessages.editor_grid_export_data_blocked, requeryBlocker)
            : EditorMessages.editor_grid_export_data_single);
        // Ctrl+C stays with the table's transfer handler; the menu only shows it
        bindKey(table, JComponent.WHEN_FOCUSED, copyWithHeadersAction, "chromite.copyWithHeaders"); //$NON-NLS-1$

        JPopupMenu popupMenu = new JPopupMenu();
        if (editModel != null) {
            editCellAction = createAction(EditorMessages.editor_grid_edit_cell, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), this::editSelectedCell);
            setNullAction = createAction(EditorMessages.editor_grid_set_null, UiKeys.command(KeyEvent.VK_0), this::setSelectedToNull);
            // Shortcuts of SWT's result set commands (ui.editors.data plugin.xml at 263b8ddb45); Reset Cell's Esc is
            // left out: in the grid Esc cancels a cell edit
            setDefaultAction = createAction(GridEditMessages.gridedit_set_default,
                UiKeys.command(KeyEvent.VK_BACK_SPACE), this::setSelectedToDefault);
            resetCellAction = createAction(GridEditMessages.gridedit_reset_cell, null, this::resetSelectedCells);
            // SWT "Open value editor" (Shift+Enter) and "Generate UUID" (Ctrl+Shift+Alt+U)
            editValueAction = createAction(ValueEditMessages.valueedit_edit_value,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), this::editValueInDialog);
            editValueAction.putValue(Action.SHORT_DESCRIPTION, ValueEditMessages.valueedit_edit_value_tip);
            generateUuidAction = createAction(ValueEditMessages.valueedit_generate_uuid,
                UiKeys.command(KeyEvent.VK_U, InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                this::generateUuids);
            insertRowAction = createAction(EditorMessages.editor_grid_insert_row, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.ALT_DOWN_MASK), this::insertRow);
            duplicateRowAction = createAction(GridEditMessages.gridedit_duplicate_row,
                UiKeys.command(KeyEvent.VK_INSERT, InputEvent.ALT_DOWN_MASK), this::duplicateSelectedRows);
            copyFromAboveAction = createAction(GridEditMessages.gridedit_copy_from_above,
                UiKeys.command(KeyEvent.VK_D), () -> copyFromAdjacentRow(true));
            copyFromBelowAction = createAction(GridEditMessages.gridedit_copy_from_below,
                UiKeys.command(KeyEvent.VK_D, InputEvent.ALT_DOWN_MASK), () -> copyFromAdjacentRow(false));
            deleteRowsAction = createAction(EditorMessages.editor_grid_delete_row, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelectedRows);
            undoAction = createAction(GridEditMessages.gridedit_undo, UiKeys.command(KeyEvent.VK_Z), this::undo);
            redoAction = createAction(GridEditMessages.gridedit_redo, UiKeys.command(KeyEvent.VK_Y), this::redo);
            pasteAction = createAction(GridEditMessages.gridedit_paste, UiKeys.command(KeyEvent.VK_V), () -> paste(null));
            pasteSpecialAction = createAction(GridEditMessages.gridedit_paste_special,
                UiKeys.command(KeyEvent.VK_V, InputEvent.SHIFT_DOWN_MASK), this::pasteSpecial);
            previewChangesAction = createAction(GridEditMessages.gridedit_preview_changes, null, () -> save(false, false, true));
            previewChangesAction.putValue(Action.SHORT_DESCRIPTION, GridEditMessages.gridedit_preview_changes_tip);
            Action saveMenuAction = createAction(EditorMessages.editor_grid_save_changes, (KeyStroke) saveAction.getValue(Action.ACCELERATOR_KEY), () -> save(false));
            Action cancelMenuAction = createAction(EditorMessages.editor_grid_cancel_changes, null, this::cancelChanges);
            // F2 opens the grid's editor, or the value panel for a LOB cell
            for (Action action : new Action[]{editCellAction, editValueAction, setNullAction, setDefaultAction, generateUuidAction,
                insertRowAction, duplicateRowAction, copyFromAboveAction, copyFromBelowAction, deleteRowsAction, undoAction, redoAction,
                pasteAction, pasteSpecialAction}) {
                bindKey(table, JComponent.WHEN_FOCUSED, action, "chromite." + action.getValue(Action.NAME)); //$NON-NLS-1$
            }
            // Ctrl+S in the grid or its cell editor saves the grid, even without changes, instead of reaching File > Save
            Action saveKeyAction = createAction(EditorMessages.editor_common_save, null, () -> save(false));
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                (KeyStroke) saveAction.getValue(Action.ACCELERATOR_KEY), "chromite.saveGrid"); //$NON-NLS-1$
            getActionMap().put("chromite.saveGrid", saveKeyAction); //$NON-NLS-1$
            // A double click on a LOB cell opens the value panel (the grid doesn't edit it)
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int row = table.rowAtPoint(e.getPoint());
                    int column = table.columnAtPoint(e.getPoint());
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && row >= 0 && column >= 0
                        && isPanelEditedCell(row, column)) {
                        host.editInValuePanel();
                    }
                }
            });
            popupMenu.add(editCellAction);
            popupMenu.add(editValueAction);
            popupMenu.add(setNullAction);
            popupMenu.add(setDefaultAction);
            popupMenu.add(generateUuidAction);
            popupMenu.add(resetCellAction);
            popupMenu.addSeparator();
            popupMenu.add(insertRowAction);
            popupMenu.add(duplicateRowAction);
            popupMenu.add(copyFromAboveAction);
            popupMenu.add(copyFromBelowAction);
            popupMenu.add(deleteRowsAction);
            popupMenu.addSeparator();
            popupMenu.add(undoAction);
            popupMenu.add(redoAction);
            popupMenu.add(pasteAction);
            popupMenu.add(pasteSpecialAction);
            popupMenu.addSeparator();
            JMenuItem saveCommitItem = new JMenuItem(saveCommitAction);
            popupMenu.add(saveMenuAction);
            popupMenu.add(saveCommitItem);
            popupMenu.add(previewChangesAction);
            popupMenu.add(cancelMenuAction);
            popupMenu.addSeparator();
            saveMenuAction.setEnabled(false);
            cancelMenuAction.setEnabled(false);
            saveAction.addPropertyChangeListener(e -> saveMenuAction.setEnabled(saveAction.isEnabled()));
            cancelChangesAction.addPropertyChangeListener(e -> cancelMenuAction.setEnabled(cancelChangesAction.isEnabled()));
            // Save and Commit only means something in manual commit mode
            popupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    saveCommitItem.setVisible(isManualCommit());
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                }
            });
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
     * Width of the row numbers up to {@code rowCount} (at least two digits). The digits of the UI font (the operating
     * system's) may be proportional (Pretendard, the earlier UI font: "1" is narrower than "0"), so a number's own width is not enough for the numbers
     * below it ("111" is narrower than "108"): the width is the digit count times the widest digit.
     */
    private static int getRowNumberWidth(@NotNull JTable header, int rowCount) {
        FontMetrics metrics = header.getFontMetrics(header.getFont());
        int digitWidth = 0;
        for (char digit = '0'; digit <= '9'; digit++) {
            digitWidth = Math.max(digitWidth, metrics.charWidth(digit));
        }
        return String.valueOf(Math.max(rowCount, 99)).length() * digitWidth;
    }

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
     * The column widths (fitted, or changed by the user) and the row number width include the cell padding of the time
     * they were set. A density change at runtime ({@link UiDensity}) changes the cell and header margins by the same
     * amount, so every width changes by the padding difference: the texts keep their room (switching back from compact
     * mode would otherwise cut the header names).
     */
    private void followCellPadding() {
        int[] padding = {getCellPadding(table)};
        table.addPropertyChangeListener("UI", e -> { //$NON-NLS-1$
            int newPadding = getCellPadding(table);
            int delta = newPadding - padding[0];
            padding[0] = newPadding;
            if (delta == 0) {
                return;
            }
            for (int column = 0; column < table.getColumnCount(); column++) {
                TableColumn tableColumn = table.getColumnModel().getColumn(column);
                tableColumn.setPreferredWidth(tableColumn.getPreferredWidth() + delta);
            }
            TableColumn numberColumn = rowHeader.getColumnModel().getColumn(0);
            int width = numberColumn.getPreferredWidth() + delta;
            numberColumn.setPreferredWidth(width);
            rowHeader.setPreferredScrollableViewportSize(new Dimension(width, 0));
        });
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
        header.setFocusable(false);
        header.setRowSelectionAllowed(false);
        header.setShowGrid(false);
        header.setRowHeight(table.getRowHeight());
        // The grid's row height comes from the theme (size.row.grid) and changes with the density (UiDensity)
        table.addPropertyChangeListener("rowHeight", e -> header.setRowHeight(table.getRowHeight())); //$NON-NLS-1$
        // DataGrip row numbers: grid background, text.secondary, left-aligned. A number never takes the selection or
        // focus look: a selected cell would seem to run on into the number column and then break off (user, 2026-11-08)
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable rowTable, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(rowTable, value, false, false, row, column);
                setBackground(rowTable.getBackground());
                setForeground(UIManager.getColor("Chromite.secondaryForeground")); //$NON-NLS-1$
                return this;
            }
        };
        renderer.setHorizontalAlignment(SwingConstants.LEFT);
        header.setDefaultRenderer(Object.class, renderer);
        int rowCount = gridModel.getRowCount();
        int width = getRowNumberWidth(header, rowCount) + getCellPadding(table);
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
            return editModel != null && editModel.isInlineEditable(rowIndex, columnIndex);
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
     * handler when editing stops ({@link ResultSetEditModel#parseValue}). A button at its end opens the date picker,
     * or the value dialog of a foreign key, SET, array, structure or hstore cell ({@link ResultSetValueDialogs}, also
     * Shift+Enter). A PostgreSQL bit string cell takes only 0 and 1 (SWT {@code BitStringInlineEditor}).
     */
    private final class TextCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final JButton pickerButton;
        private final JButton dialogButton;
        private final JButton referenceButton;
        private final DocumentFilter bitFilter = new BitStringFilter();
        private final Border normalBorder;
        private String initialText = "";
        private int modelColumn;
        private int modelRow;
        @Nullable
        private EditedValue value;
        /**
         * The value picked in the date picker; it ends the edit without parsing the text.
         */
        @Nullable
        private EditedValue picked;

        TextCellEditor() {
            normalBorder = field.getBorder();
            field.addActionListener(e -> stopCellEditing());
            // Date and time cells: a calendar button in the field (SWT DateTimeInlineEditor "Calendar"), also Alt+Down
            Action pickAction = createAction(GridEditMessages.gridedit_pick_date, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK),
                this::pickDate);
            pickerButton = UiIcons.createActionButton(pickAction, "schedule"); //$NON-NLS-1$
            pickerButton.setToolTipText(GridEditMessages.gridedit_pick_date_tip);
            pickerButton.setFocusable(false);
            pickerButton.setMargin(new Insets(0, 2, 0, 2));
            field.getInputMap().put((KeyStroke) pickAction.getValue(Action.ACCELERATOR_KEY), "chromite.pickDate"); //$NON-NLS-1$
            field.getActionMap().put("chromite.pickDate", pickAction); //$NON-NLS-1$
            // Value dialogs (SWT "Open value editor", Shift+Enter): a search button for foreign keys, "more" for the others
            Action dialogAction = createAction(ValueEditMessages.valueedit_edit_value,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), this::pickFromDialog);
            dialogButton = UiIcons.createActionButton(dialogAction, "more"); //$NON-NLS-1$
            referenceButton = UiIcons.createActionButton(dialogAction, "search"); //$NON-NLS-1$
            for (JButton button : new JButton[]{dialogButton, referenceButton}) {
                button.setToolTipText(ValueEditMessages.valueedit_edit_value_tip);
                button.setFocusable(false);
                button.setMargin(new Insets(0, 2, 0, 2));
            }
            field.getInputMap().put((KeyStroke) dialogAction.getValue(Action.ACCELERATOR_KEY), "chromite.editValue"); //$NON-NLS-1$
            field.getActionMap().put("chromite.editValue", dialogAction); //$NON-NLS-1$
        }

        /**
         * Opens the value dialog of the cell; its value ends the edit without parsing the text.
         */
        private void pickFromDialog() {
            if (ResultSetValueDialogs.getKind(editModel, modelColumn) == null) {
                return;
            }
            ResultSetValueDialogs.Picked result = ResultSetValueDialogs.edit(table, editModel, modelRow, modelColumn);
            if (result == null) {
                field.requestFocusInWindow();
                return;
            }
            try {
                picked = new EditedValue(result.input() == null ? null : editModel.parseObject(modelColumn, result.input()));
            } catch (DBCException e) {
                showInvalid(e);
                return;
            }
            stopCellEditing();
        }

        private boolean hasPicker() {
            return ResultSetDateTimePicker.supports(data.bindings()[modelColumn], editModel.getRow(modelRow).values[modelColumn]);
        }

        private void pickDate() {
            if (!hasPicker()) {
                return;
            }
            DBDAttributeBinding binding = data.bindings()[modelColumn];
            // Start from the typed text when it is a date, else from the cell's value
            Object start = editModel.getRow(modelRow).values[modelColumn];
            if (!field.getText().equals(initialText)) {
                try {
                    Object typed = editModel.parseValue(modelColumn, field.getText());
                    if (typed instanceof java.util.Date date) {
                        start = date;
                    }
                } catch (DBCException e) {
                    // Not a date: the picker starts from the cell's value
                }
            }
            java.util.Date result = ResultSetDateTimePicker.pick(table, ResultSetDateTimePicker.getKind(binding),
                start instanceof java.util.Date date ? date : null);
            if (result == null) {
                field.requestFocusInWindow();
                return;
            }
            try {
                picked = new EditedValue(editModel.parseObject(modelColumn, result));
            } catch (DBCException e) {
                showInvalid(e);
                return;
            }
            stopCellEditing();
        }

        private void showInvalid(@NotNull DBCException e) {
            Color errorColor = UIManager.getColor("Component.error.focusedBorderColor"); //$NON-NLS-1$
            field.setBorder(BorderFactory.createLineBorder(errorColor != null ? errorColor : Color.RED, 2));
            transientStatus = NLS.bind(EditorMessages.editor_grid_invalid_value_status, data.columns()[modelColumn], ErrorText.describeLine(e));
            updateState();
            UIManager.getLookAndFeel().provideErrorFeedback(field);
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            // Double click, F2, or the Edit Cell menu
            return !(event instanceof MouseEvent mouseEvent) || mouseEvent.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object cellValue, boolean isSelected, int row, int column) {
            modelColumn = table.convertColumnIndexToModel(column);
            modelRow = table.convertRowIndexToModel(row);
            initialText = editModel.getEditText(modelRow, modelColumn);
            value = null;
            picked = null;
            AbstractDocument document = (AbstractDocument) field.getDocument();
            document.setDocumentFilter(null);
            field.setText(initialText);
            if (data.bindings()[modelColumn].getValueHandler() instanceof PostgreBitStringValueHandler) {
                document.setDocumentFilter(bitFilter);
            }
            field.setBorder(normalBorder);
            field.setFont(table.getFont());
            ResultSetValueDialogs.Kind dialogKind = ResultSetValueDialogs.getKind(editModel, modelColumn);
            field.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, hasPicker() ? pickerButton
                : dialogKind == ResultSetValueDialogs.Kind.REFERENCE ? referenceButton
                : dialogKind != null ? dialogButton : null);
            SwingUtilities.invokeLater(field::selectAll);
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public boolean stopCellEditing() {
            if (picked != null) {
                value = picked;
                picked = null;
                return super.stopCellEditing();
            }
            String text = field.getText();
            if (text.equals(initialText)) {
                // Unchanged text keeps the value (an empty editor of a NULL cell must not become an empty string)
                value = null;
                return super.stopCellEditing();
            }
            try {
                value = new EditedValue(editModel.parseValue(modelColumn, text));
            } catch (DBCException e) {
                showInvalid(e);
                return false;
            }
            return super.stopCellEditing();
        }

        @Override
        public void cancelCellEditing() {
            value = null;
            picked = null;
            super.cancelCellEditing();
        }
    }

    /**
     * Lets only 0 and 1 into a bit string cell (SWT {@code BitStringInlineEditor}); the length is checked by the
     * database when the change is saved.
     */
    private static final class BitStringFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            if (isBits(string)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (isBits(text)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        private static boolean isBits(@Nullable String text) {
            if (text == null) {
                return true;
            }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '0' && text.charAt(i) != '1') {
                    UIManager.getLookAndFeel().provideErrorFeedback(null);
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Editor of a boolean or ENUM cell (SWT {@code BooleanInlineEditor}, {@code EnumValueManager}): a list of the
     * allowed values, with NULL when the column takes it. The list opens with the editor; picking a value ends the
     * edit. A SET column holds several values: it is typed, or picked in the value dialog ({@link ResultSetValueDialogs}).
     */
    private final class ChoiceCellEditor extends AbstractCellEditor implements TableCellEditor {

        /**
         * One entry: its label and the value given to the column's handler (null for NULL).
         */
        private record Choice(@NotNull String label, @Nullable Object value) {
            @Override
            public String toString() {
                return label;
            }
        }

        private final JComboBox<Choice> combo = new JComboBox<>();
        private int modelColumn;
        @Nullable
        private EditedValue value;
        private boolean filling;

        ChoiceCellEditor() {
            // Arrow keys move in the list without selecting (no action event); Enter or a click selects and ends the edit
            combo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE); //$NON-NLS-1$
            combo.addActionListener(e -> {
                if (!filling) {
                    stopCellEditing();
                }
            });
        }

        static boolean hasChoices(@Nullable ResultSetEditModel model, int column) {
            return model != null && !model.isContentColumn(column)
                && ((model.getEnumValues(column) != null && !model.isMultiValueColumn(column))
                || model.getAttributes()[column].getDataKind() == DBPDataKind.BOOLEAN);
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            // Double click, F2, or the Edit Cell menu
            return !(event instanceof MouseEvent mouseEvent) || mouseEvent.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object cellValue, boolean isSelected, int row, int column) {
            modelColumn = table.convertColumnIndexToModel(column);
            DBDAttributeBinding binding = data.bindings()[modelColumn];
            Object current = editModel.getRow(table.convertRowIndexToModel(row)).values[modelColumn];
            value = null;
            filling = true;
            try {
                combo.removeAllItems();
                Choice selected = null;
                String[] enumValues = editModel.getEnumValues(modelColumn);
                if (enumValues != null) {
                    String currentText = DBUtils.isNullValue(current) ? null : current.toString();
                    for (String enumValue : enumValues) {
                        Choice choice = new Choice(enumValue, enumValue);
                        combo.addItem(choice);
                        if (enumValue.equals(currentText)) {
                            selected = choice;
                        }
                    }
                } else {
                    for (Boolean flag : new Boolean[]{Boolean.TRUE, Boolean.FALSE}) {
                        Choice choice = new Choice(binding.getValueHandler().getValueDisplayString(binding, flag, DBDDisplayFormat.EDIT), flag);
                        combo.addItem(choice);
                        if (flag.equals(current)) {
                            selected = choice;
                        }
                    }
                }
                if (binding.getAttribute() == null || !binding.getAttribute().isRequired()) {
                    Choice nullChoice = new Choice(ResultSetGrid.NULL_VALUE.toString(), null);
                    combo.addItem(nullChoice);
                    if (DBUtils.isNullValue(current)) {
                        selected = nullChoice;
                    }
                }
                combo.setSelectedItem(selected);
            } finally {
                filling = false;
            }
            combo.setFont(table.getFont());
            SwingUtilities.invokeLater(() -> {
                if (combo.isShowing()) {
                    combo.requestFocusInWindow();
                    combo.showPopup();
                }
            });
            return combo;
        }

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public boolean stopCellEditing() {
            if (combo.getSelectedItem() instanceof Choice choice) {
                try {
                    value = new EditedValue(choice.value() == null ? null : editModel.parseObject(modelColumn, choice.value()));
                } catch (DBCException e) {
                    transientStatus = NLS.bind(EditorMessages.editor_grid_invalid_value_status, data.columns()[modelColumn], ErrorText.describeLine(e));
                    updateState();
                    UIManager.getLookAndFeel().provideErrorFeedback(combo);
                    return false;
                }
            } else {
                value = null;
            }
            if (combo.isPopupVisible()) {
                combo.hidePopup();
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
