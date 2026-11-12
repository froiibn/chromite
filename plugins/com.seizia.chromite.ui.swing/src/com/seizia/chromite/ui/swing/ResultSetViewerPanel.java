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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import com.seizia.chromite.ui.swing.internal.GridFilterMessages;
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import com.seizia.chromite.ui.swing.internal.GridSqlMessages;
import com.seizia.chromite.ui.swing.internal.GridVirtualMessages;
import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeBindingCustom;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDReferenceNavigation;
import com.seizia.chromite.model.data.DBDResultSetModel;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.data.order.OrderingStrategy;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.parser.SQLSemanticProcessor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.virtual.DBVEntityAttribute;
import com.seizia.chromite.model.virtual.DBVEntityForeignKey;
import com.seizia.chromite.model.virtual.DBVUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.DataSourceJob;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.AdjustmentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 결과 집합 하나의 뷰어(SWT {@code ResultSetViewer}에 해당): 결과 표({@link ResultSetGrid})를 감싸 열 필터·정렬 머리글, 값 패널, 레코드 보기, 상태 줄을 붙인다.
 *
 * 설계:
 * <ul>
 *     <li>표 자체(그리기, 셀 편집, 저장)는 {@link ResultSetGrid}가 맡고, 이 클래스는 표 밖의 기능만 가진다. 정렬·다음 페이지처럼 행이 바뀌면
 *     표를 고치지 않고 바뀐 행으로 새 {@link ResultSetGrid}를 만들어 바꿔 끼운다(열 너비, 스크롤, 선택은 이어받는다).
 *     표가 값을 표시 문자열로 미리 갖는 구조(C단계)와 편집 모델(G단계)을 그대로 두고 기능을 더하기 위해서다.</li>
 *     <li><b>머리글</b>({@link ResultSetHeaderRenderer}): 열 아이콘, 이름, 오른쪽 끝에 필터(깔때기)·정렬 아이콘. 깔때기는 열 필터 팝업을 열고,
 *     정렬 아이콘은 정렬을 바꾼다. 이름 자리를 누르면 엑셀처럼 그 열 전체를 선택한다(Ctrl+클릭은 열을 더하고, Shift+클릭은 기준 열부터 범위).
 *     정렬과 필터는 아이콘에서만 바뀌게 해 열을 고르려다 쿼리를 다시 읽는 일이 없게 했다.</li>
 *     <li><b>정렬</b>(SWT {@code ResultSetViewer.toggleSortOrder}): 머리글의 정렬 아이콘을 누르면 오름차순 → 내림차순 → 정렬 없음 순서로 바뀐다.
 *     Ctrl을 누르고 누르면 다른 열의 정렬을 두고 이 열을 더한다. 서버에서 정렬할지는 SWT 설정 {@code resultset.order.mode}
 *     ({@link OrderingStrategy}: SMART면 아직 읽지 않은 행이 있을 때만 서버, CLIENT_SIDE는 항상 표 안에서, SERVER_SIDE는 항상 서버)와
 *     DB가 결과 정렬을 지원하는지로 정하고, 쿼리를 다시 읽을 수 없는 결과({@link ResultSetSource#requeryBlocker()})는 표 안에서 정렬한다.
 *     표 안 정렬은 SWT {@code ResultSetModel.resetOrderingImpl}처럼 먼저 읽은 순서로 되돌린 뒤 열의 값 처리기 비교기, 문자열은 대소문자 무시,
 *     그 밖에는 {@link DBUtils#compareDataValues}로 비교한다. 서버 정렬은 쿼리의 ORDER BY를 바꾼다(모델 생성기, 행을 고르는 ORDER BY는 그대로
 *     두고 바깥에서 정렬). 정렬이 있으면 상태 줄에 "정렬: 열 ↑ ✕"를 보이고, 쿼리의 ORDER BY를 바꿨으면 그렇다고 쓴다. ✕는 정렬을 없애
 *     쿼리 순서로 되돌린다.</li>
 *     <li><b>필터</b>: 열마다 머리글의 깔때기로 여는 팝업({@link ResultSetColumnFilterPopup})이 그 열의 조건({@link DBDAttributeConstraint})을
 *     정하고, 뷰어가 바뀐 {@link DBDDataFilter}로 쿼리를 서버에서 다시 읽는다. 표의 오른쪽 클릭 "Filter by Value"는 SWT
 *     {@code FilterByAttributeAction}처럼 열 조건(연산자와 값)을 더한다. 조건이 있으면 상태 줄에 "필터: 열1, 열2 ✕" 요약을 보인다
 *     (줄을 새로 두지 않고, 도구 설명은 {@link SQLUtils#appendConditionString}으로 만든 SQL 조건, ✕는 모든 필터 지우기).</li>
 *     <li><b>다음 페이지</b>(SWT {@code readNextSegment}, {@code readAllData}): 표를 끝까지 내리면(SWT 설정 {@code resultset.autofetch.next.segment},
 *     기본 켬) 또는 상태 줄의 "Fetch next"를 누르면 같은 쿼리를 지금 행 수부터 한 번에 읽는 행 수만큼 더 읽어 붙인다. "Fetch all"은 확인한 뒤 나머지를 모두 읽는다.</li>
 *     <li>다시 읽기는 모두 백그라운드 Job({@link DataSourceJob})에서 {@link DBSDataContainer#readData}로 한다. 저장하지 않은 변경이 있으면
 *     SWT {@code checkForChanges}처럼 먼저 저장하거나 취소해야 한다(자동 다음 페이지는 묻지 않고 건너뛴다).</li>
 *     <li><b>값 패널</b>({@link ResultSetValuePanel}, F7)과 <b>레코드 보기</b>({@link ResultSetRecordView})는 지금 선택한 칸·행을 보여 준다.
 *     값 패널을 연 상태는 다음 결과에도 이어진다(이 실행 동안).</li>
 *     <li><b>차트 보기</b>({@link ResultSetChartView})와 <b>공간 보기</b>({@link ResultSetSpatialView})는 레코드 보기처럼 표 자리에 바꿔 보인다
 *     (한 번에 하나, 같은 단추를 다시 누르면 표). 둘 다 표에 읽혀 있는 행을 그리고, 행이 바뀌면 다시 그린다. Spatial 단추는 기하 열이 있을 때만 보인다.</li>
 *     <li><b>상태 줄</b>: 읽은 행 수("200 rows fetched — more available"), 다음 페이지 링크, 선택한 칸의 집계(SSMS 상태 줄처럼 개수·합계·평균·최솟값·최댓값,
 *     모델 계층의 {@code com.seizia.chromite.model.data.aggregate} 함수 사용, {@link ResultSetAggregates}).</li>
 *     <li><b>외래 키 따라가기</b>(SWT {@code navigateAssociation}/{@code navigateReference}): 칸의 외래 키(Ctrl+B)나 "Navigate" 메뉴의 참조 테이블·참조하는 테이블로
 *     고른 행의 관련 행을 이 뷰어에서 읽는다(대상 테이블을 데이터 컨테이너로, 모델이 만든 필터로). 떠난 결과는 그대로 기록에 남겨({@code HistoryState})
 *     뒤로·앞으로(Alt+←/→, 상태 줄 ←/→)가 다시 읽지 않고 돌려놓는다. 관계 목록은 처음 쓸 때 한 번 읽는다({@link ResultSetReferences}).</li>
 *     <li><b>옆 패널</b>: 참조 패널({@link ResultSetReferencesPanel})과 집계 패널({@link ResultSetAggregatePanel})을 탭으로 결과 오른쪽에 둔다(결과 창은 넓고 낮아서).
 *     켠 패널이 없으면 숨는다.</li>
 *     <li><b>열 배치와 이동</b>({@link ResultSetColumnLayout}): 열 숨기기·빈 열 숨기기·머리글 끌어 순서 바꾸기, 행·열로 가기, 확대·축소.
 *     표를 새로 만들 때 같은 열이면 배치를 옮긴다. 찾기 줄({@link ResultSetFindBar}, Ctrl+F)과 텍스트 보기({@link ResultSetTextView})도 있다.</li>
 *     <li><b>전체 행 수</b>(SWT {@code resultset.count}): 더 읽을 행이 있으면 상태 줄 "행 수 세기"가 모델의 {@code countData}(SELECT COUNT)로 센다.</li>
 *     <li><b>가상 키</b>({@link ResultSetVirtualKeys}): 키가 없어 읽기 전용인 결과의 가상 고유 키, 가상 외래 키. 정하면 결과를 다시 읽는다.</li>
 *     <li><b>저장한 필터·최근 필터</b>({@link ResultSetSavedFilters}): 테이블 하나에서 온 결과의 열 필터와 정렬을 이름으로 저장해 고르고,
 *     조건으로 다시 읽을 때마다 그 조건을 테이블의 최근 필터에 남긴다. 둘 다 연결의 가상 모델에 저장한다.</li>
 *     <li><b>가상(계산) 열</b>({@link ResultSetVirtualColumns}): 테이블 하나에서 온 결과에 식으로 계산하는 열을 더한다. DB에 없는 열이라
 *     머리글 필터와 "Filter by Value"를 끄고, 정렬은 표 안에서만 하며 서버에서 다시 읽는 필터에서는 뺀다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 값 패널, 정렬·필터, 다음 페이지, 레코드 보기, 복사 형식, 열 너비 맞춤, 선택 집계 (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 데이터 전송과 합침: 다시 읽은 결과(정렬·필터·다음 페이지)도 Export Data…의 쿼리({@link ResultSetGrid.Fetched#exportQuery()})를 이어받음
 *   (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 Export Data…에 서버에서 읽은 필터·정렬({@code serverFilter})을 넘기고, 표 안에서만 한 정렬은 마법사에 알림
 *   (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 다시 읽기·다음 페이지 뒤 행 수를 알림({@link ResultSetGrid.Host#rowsChanged}): 쿼리 상태 줄이 실행 때 행 수에 머물던 문제
 *   (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 WHERE 필터 줄을 없애고 열 머리글의 필터·정렬 아이콘과 열 필터 팝업으로 바꿈(사용자 요청). 값 패널·레코드 단추는 상태 줄 오른쪽으로,
 *   필터 요약은 상태 줄에 (docs/chromite/swing-grid-column-filters.md)
 * - 2026-11-08 LOB 칸 편집: F2·두 번 누르기로 값 패널을 열고, 글자 LOB는 패널에서 고치며, 파일에서 불러오기·파일로 저장(메뉴와 값 패널 단추).
 *   그리드가 모르는(읽지 않은) LOB 값만 받은 내용으로 보이고, 고치거나 저장한 값은 편집 모델의 값을 보임 (docs/chromite/swing-grid-editing.md)
 * - 2026-11-08 상태 줄의 정렬 요약: 정렬한 열, 쿼리의 ORDER BY를 바꿨는지, 쿼리 순서로 되돌리기(✕). 상태 줄 왼쪽이 넘치면 두 줄로 늘어남
 *   ({@link WrapFlowLayout}: 전에는 넘친 요약이 상태 줄 밖으로 밀려 가려짐) (docs/chromite/swing-grid-column-filters.md "정렬과 쿼리의 ORDER BY")
 * - 2026-11-08 차트 보기·공간 보기와 상태 줄 Chart / Spatial 단추. 레코드 보기 여부(boolean)를 보기 방식({@code ViewMode})으로 바꿈.
 *   기하 값은 값 패널에도 보이고 "Filter by Value"에는 넣지 않음 (docs/chromite/swing-result-charts-spatial.md)
 * - 2026-11-08 끝까지 내리면 더 읽기({@link #isAutoFetchNextSegment}): 기본값을 전역 설정에 두고 연결의 저장소로 읽음. 전에는 연결 자신의 값만
 *   보아 Options의 값이 효과가 없었다 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 * - 2026-11-08 결과 탐색(SWT 대조 B4): 외래 키 따라가기와 뒤로·앞으로, 참조·집계 패널, 가상 키, 열 숨기기·순서·빈 열, 행·열로 가기, 확대·축소,
 *   찾기, 텍스트 보기, 전체 행 수. 레코드 보기의 줄은 모델 열 번호라 표의 열을 숨기거나 옮겨도 같은 칸을 가리키게 바꿈
 *   (docs/chromite/swing-grid-navigation.md)
 * - 2026-11-08 16진수로 편집…({@link ResultSetHexEditor}), 외부 편집기로 열기({@link ResultSetExternalEditor}, 저장하면 셀에 넣음,
 *   결과를 닫으면 멈춤). 배열·구조체 값은 "Filter by Value"에 넣지 않음 (docs/chromite/swing-value-editors.md)
 * - 2026-11-08 결과 행에서 SQL 만들기: 오른쪽 클릭 메뉴의 "SQL 생성"(Copy As 다음, {@link ResultSetGenerateSql}). 고른 행의 칸 값을
 *   저장하지 않은 변경까지 넘김 (docs/chromite/swing-result-generate-sql.md)
 * - 2026-11-08 저장한 필터·최근 필터(오른쪽 클릭 "저장한 필터")와 가상(계산) 열(오른쪽 클릭 "가상 열"). 가상 열의 머리글 필터·Filter by Value를
 *   끄고, 가상 열 정렬은 표 안에서 하며, 서버에서 다시 읽는 필터에서 가상 열의 정렬을 뺀다. 가상 키·가상 열을 바꾼 뒤 다시 읽는 메서드를 하나로
 *   ({@link #readAgainForVirtualModel}) (docs/chromite/swing-saved-filters-virtual-columns.md)
 * - 2026-11-08 머리글 이름 자리를 누르면 정렬 대신 열 전체 선택(Ctrl+클릭 더하기, Shift+클릭 범위). 정렬은 정렬 아이콘으로만
 *   (사용자 요청, docs/chromite/swing-grid-column-filters.md "머리글 누르기")
 * - 2026-11-09 단축키를 플랫폼 명령 키로({@link UiKeys}: macOS Command, 앱 변경 #24) (docs/chromite/shortcuts.md), 열 머리글 Ctrl+클릭도 명령 키, 텍스트 보기는 macOS에서도 Control+`
 * - 2026-11-12 결과 영역의 Text 탭이 결과마다의 글을 얻는 {@link #formatText}(텍스트 보기와 같은 글, 앱 변경 #54)
 *   (docs/chromite/swing-frontend.md "결과 영역 두 단계 탭")
 * - 2026-11-12 아래 줄의 보기 토글(값 패널·레코드·텍스트·차트·공간·참조·집계)의 좌우 여백을 {@link UiSizes#TEXT_TOOL_BUTTON_STYLE}로: 아이콘 단추 여백(4)이라 눌린 바탕과 글자 사이가 거의 없었다
 *   (앱 변경 #71 e, docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-12 아이콘 단추의 도구 설명을 설명문 대신 기능 이름(동작·메뉴의 이름)과 단축키로 (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class ResultSetViewerPanel extends JPanel {

    private static final Log log = Log.getLog(ResultSetViewerPanel.class);

    /**
     * SWT {@code ResultSetPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT} (UI bundle, default on).
     */
    static final String PREF_AUTO_FETCH_NEXT_SEGMENT = "resultset.autofetch.next.segment"; //$NON-NLS-1$
    private static final String CARD_GRID = "grid"; //$NON-NLS-1$
    private static final String CARD_RECORD = "record"; //$NON-NLS-1$
    private static final String CARD_CHART = "chart"; //$NON-NLS-1$
    private static final String CARD_SPATIAL = "spatial"; //$NON-NLS-1$
    private static final String CARD_TEXT = "text"; //$NON-NLS-1$
    private static final int HEADER_CLICK_BORDER = 4;
    /**
     * Widest first column width made for the header icons (the grid's own first widths stop at the same width).
     */
    private static final int HEADER_FIT_MAX_WIDTH = 320;

    /**
     * Whether new result viewers open with the value panel (the state of the last toggle in this session).
     */
    private static boolean valuePanelDefault;

    /**
     * What the viewer shows in place of the grid: the grid itself, the record view, the chart view, the spatial view
     * or the text view.
     */
    private enum ViewMode {
        GRID(CARD_GRID), RECORD(CARD_RECORD), CHART(CARD_CHART), SPATIAL(CARD_SPATIAL), TEXT(CARD_TEXT);

        private final String card;

        ViewMode(@NotNull String card) {
            this.card = card;
        }
    }

    /**
     * One row kept across rebuilds of the grid: its fetch position, display strings, export values, cell values
     * and, for editable results, its values.
     */
    private record ViewerRow(
        int ordinal,
        @NotNull Object[] display,
        @Nullable Object[] kept,
        @NotNull Object[] cells,
        @Nullable Object[] values
    ) {
    }

    /**
     * A result the viewer left by following a foreign key, kept as it was to go back to it.
     *
     * @param title what the status line shows for it (the table and key it was reached by), or null for the query's
     *              own result
     */
    private record HistoryState(
        @NotNull ResultSetGrid.Fetched fetched,
        @NotNull int[] ordinals,
        @NotNull DBDDataFilter dataFilter,
        @NotNull DBDDataFilter serverFilter,
        boolean hasMore,
        boolean clientOrdered,
        @Nullable String title,
        @Nullable Long totalRowCount
    ) {
    }

    private final ResultSetGrid.Host host;
    private final ResultSetGrid.Host gridHost;
    private final ResultSetValuePanel valuePanel;
    private final ResultSetRecordView recordView;
    private final ResultSetChartView chartView;
    private final ResultSetSpatialView spatialView;
    private final JLabel rowsLabel = new JLabel();
    private final JButton fetchNextButton;
    private final JButton fetchAllButton;
    private final JPanel filterSummary = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JLabel filterSummaryLabel = new JLabel();
    private final JPanel sortSummary = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JLabel sortSummaryLabel = new JLabel();
    private final JLabel aggregateLabel = new JLabel();
    private final JToggleButton valuePanelToggle;
    private final JToggleButton recordToggle;
    private final Action loadFileAction;
    private final Action saveFileAction;
    private final Action hexAction;
    private final Action externalAction;
    private final ResultSetExternalEditor externalEditor = new ResultSetExternalEditor();
    private final JToggleButton chartToggle;
    private final JToggleButton spatialToggle;
    private final JToggleButton textToggle;
    private final JToggleButton referencesToggle;
    private final JToggleButton aggregatesToggle;
    private final ResultSetTextView textView;
    private final ResultSetFindBar findBar;
    private final ResultSetReferencesPanel referencesPanel;
    private final ResultSetAggregatePanel aggregatePanel;
    // The references and aggregates panels beside the result (the results area is wide and low), as tabs; hidden while neither is on
    private final JTabbedPane sidePanels = new JTabbedPane();
    private final JSplitPane panelSplit;
    private final Action backAction;
    private final Action forwardAction;
    private final JButton backButton;
    private final JButton forwardButton;
    private final JLabel navigationLabel = new JLabel();
    private final JButton countButton;
    private final JPanel cards = new JPanel(new CardLayout());
    private final JSplitPane split;
    private final Timer selectionTimer;

    private ResultSetGrid.Fetched fetched;
    @Nullable
    private ResultSetGrid grid;
    /**
     * Fetch position of each row of the current grid, by the row number of its edit model row or by its index.
     */
    private int[] ordinals;
    private DBDDataFilter dataFilter;
    /**
     * The filter the shown rows were read with on the server (conditions and server-side sort; not a sort done in the
     * grid), for "Export Data…".
     */
    private DBDDataFilter serverFilter;
    private boolean hasMore;
    private boolean clientOrdered;
    private boolean reading;
    @Nullable
    private String statusMessage;
    private int lastScrollValue;
    private boolean valuePanelShown;
    // Kept apart from the toggle buttons: a click selects the button before its action runs
    private ViewMode viewMode = ViewMode.GRID;
    private int valuePanelWidth = -1;
    /**
     * Results left by following foreign keys and the ones gone back from; the entry at {@link #historyPosition} is
     * null, as the current result lives in the viewer's fields.
     */
    private final List<HistoryState> history = new ArrayList<>();
    private int historyPosition = -1;
    @Nullable
    private String navigationTitle;
    /**
     * The relations of the result's tables, read once ({@link #requestLinks}), and the tables they were read for.
     */
    @Nullable
    private List<ResultSetReferences.Link> links;
    @Nullable
    private List<DBSEntity> linkTables;
    private final List<Consumer<List<ResultSetReferences.Link>>> linkCallbacks = new ArrayList<>();
    private int zoom;
    // The grid's font and row height before zooming
    private Font gridFont;
    private int gridRowHeight;
    @Nullable
    private Long totalRowCount;
    private boolean counting;
    // The rows the references panel last read for
    @Nullable
    private int[] referenceRows;
    @Nullable
    private ResultSetGrid.Fetched referenceFetched;

    ResultSetViewerPanel(@NotNull ResultSetGrid.Fetched fetched, @NotNull ResultSetGrid.Host host) {
        super(new BorderLayout());
        this.host = host;
        this.gridHost = new ResultSetGrid.Host() {
            @Override
            public void appendMessage(@NotNull String text) {
                host.appendMessage(text);
            }

            @Override
            public void appendError(@NotNull String text) {
                host.appendError(text);
            }

            @Override
            public void showMessages() {
                host.showMessages();
            }

            @Override
            public void editStateChanged() {
                host.editStateChanged();
                if (grid != null) {
                    statusMessage = null;
                    updateStatus();
                    scheduleSelectionUpdate();
                }
            }

            @Override
            public void editInValuePanel() {
                setValuePanelShown(true);
                scheduleSelectionUpdate();
            }

            @Override
            public void exportData(@NotNull Component parent, @NotNull DBSDataContainer query) {
                // The rows as the server read them (SWT ResultSetHandlerMain passes the model's data filter); a sort done in
                // the grid only can't be passed on
                String orderNote = clientOrdered && dataFilter.hasOrdering()
                    ? EditorMessages.editor_viewer_order_note
                    : null;
                DataExportWizard.openForQuery(parent, query, serverFilter, orderNote);
            }

            @Nullable
            @Override
            public Runnable defineVirtualKey() {
                // The grid's link only for a table the virtual key applies to (no real key)
                DBSEntity entity = getSingleSource();
                return entity != null && ResultSetVirtualKeys.usesVirtualKey(entity, ResultSetViewerPanel.this.fetched.data().bindings())
                    ? () -> editVirtualKey(entity) : null;
            }
        };
        this.fetched = fetched;
        this.ordinals = sequence(fetched.data().rows().size());
        this.dataFilter = createDataFilter(fetched.data().bindings());
        this.serverFilter = new DBDDataFilter(dataFilter);
        this.hasMore = fetched.data().truncated() && fetched.source().segmentSize() > 0;

        valuePanel = new ResultSetValuePanel();
        recordView = new ResultSetRecordView(new RecordSource());
        loadFileAction = createAction(GridEditMessages.gridedit_lob_load, null, this::loadCellFromFile);
        loadFileAction.putValue(Action.SHORT_DESCRIPTION, GridEditMessages.gridedit_lob_load_tip);
        saveFileAction = createAction(GridEditMessages.gridedit_lob_save, null, this::saveCellToFile);
        saveFileAction.putValue(Action.SHORT_DESCRIPTION, GridEditMessages.gridedit_lob_save_tip);
        loadFileAction.setEnabled(false);
        saveFileAction.setEnabled(false);
        valuePanel.setFileActions(loadFileAction, saveFileAction);
        hexAction = createAction(ValueEditMessages.valueedit_hex_menu, null, this::editCellAsHex);
        externalAction = createAction(ValueEditMessages.valueedit_external_menu, null, this::openCellExternally);
        hexAction.setEnabled(false);
        externalAction.setEnabled(false);
        chartView = new ResultSetChartView(new ChartSource());
        spatialView = new ResultSetSpatialView(new SpatialSource());

        Action toggleValuePanel = createAction(EditorMessages.editor_viewer_value_panel, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), () -> setValuePanelShown(!valuePanelShown));
        toggleValuePanel.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_value_panel_tip);
        valuePanelToggle = createToggle(toggleValuePanel);
        Action toggleRecord = createAction(EditorMessages.editor_viewer_record, null, () -> toggleViewMode(ViewMode.RECORD));
        toggleRecord.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_record_tip);
        recordToggle = createToggle(toggleRecord);
        Action toggleChart = createAction(EditorMessages.editor_viewer_chart, null, () -> toggleViewMode(ViewMode.CHART));
        toggleChart.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_chart_tip);
        chartToggle = createToggle(toggleChart);
        Action toggleSpatial = createAction(EditorMessages.editor_viewer_spatial, null, () -> toggleViewMode(ViewMode.SPATIAL));
        toggleSpatial.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_spatial_tip);
        spatialToggle = createToggle(toggleSpatial);
        Action toggleText = createAction(GridNavMessages.gridnav_text,
            UiKeys.command(KeyEvent.VK_BACK_QUOTE), () -> toggleViewMode(ViewMode.TEXT));
        toggleText.putValue(Action.SHORT_DESCRIPTION, UiKeys.format(GridNavMessages.gridnav_text_tip, toggleText));
        textToggle = createToggle(toggleText);
        textView = new ResultSetTextView(new TextSource());
        findBar = new ResultSetFindBar(() -> grid == null ? null : grid.getTable());
        referencesPanel = new ResultSetReferencesPanel(new ReferencesSource());
        aggregatePanel = new ResultSetAggregatePanel(new AggregateSource());
        Action toggleReferences = createAction(GridNavMessages.gridnav_refs, null,
            () -> toggleSidePanel(referencesPanel, GridNavMessages.gridnav_refs));
        toggleReferences.putValue(Action.SHORT_DESCRIPTION, GridNavMessages.gridnav_refs_tip);
        referencesToggle = createToggle(toggleReferences);
        Action toggleAggregates = createAction(GridNavMessages.gridnav_agg, null,
            () -> toggleSidePanel(aggregatePanel, GridNavMessages.gridnav_agg));
        toggleAggregates.putValue(Action.SHORT_DESCRIPTION, GridNavMessages.gridnav_agg_tip);
        aggregatesToggle = createToggle(toggleAggregates);
        backAction = createAction("←", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), //$NON-NLS-1$
            () -> navigateHistory(historyPosition - 1));
        // The arrows are the buttons' text: the tool tip is the Navigate menu's name with the shortcut
        backAction.putValue(Action.SHORT_DESCRIPTION, UiIcons.withShortcut(GridNavMessages.gridnav_back, (KeyStroke) backAction.getValue(Action.ACCELERATOR_KEY)));
        forwardAction = createAction("→", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), //$NON-NLS-1$
            () -> navigateHistory(historyPosition + 1));
        forwardAction.putValue(Action.SHORT_DESCRIPTION, UiIcons.withShortcut(GridNavMessages.gridnav_forward, (KeyStroke) forwardAction.getValue(Action.ACCELERATOR_KEY)));
        backButton = createLinkButton(backAction);
        forwardButton = createLinkButton(forwardAction);
        Action count = createAction(GridNavMessages.gridnav_count, null, this::countRows);
        count.putValue(Action.SHORT_DESCRIPTION, GridNavMessages.gridnav_count_tip);
        countButton = createLinkButton(count);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put((KeyStroke) toggleValuePanel.getValue(Action.ACCELERATOR_KEY), "chromite.toggleValuePanel"); //$NON-NLS-1$
        getActionMap().put("chromite.toggleValuePanel", toggleValuePanel); //$NON-NLS-1$
        // Result navigation keys (SWT result set commands at 263b8ddb45; Ctrl+B for the referenced row as DataGrip, since
        // SWT's Alt+Space opens the window menu on Windows)
        bindViewerKey(toggleText);
        bindViewerKey(backAction);
        bindViewerKey(forwardAction);
        bindViewerKey(createAction("find", UiKeys.command(KeyEvent.VK_F), findBar::open)); //$NON-NLS-1$
        bindViewerKey(createAction("gotoRow", UiKeys.command(KeyEvent.VK_G), this::goToRow)); //$NON-NLS-1$
        bindViewerKey(createAction("gotoColumn", UiKeys.command(KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK), //$NON-NLS-1$
            this::goToColumn));
        bindViewerKey(createAction("navigateLink", UiKeys.command(KeyEvent.VK_B), this::navigateCellLink)); //$NON-NLS-1$
        bindViewerKey(createAction("referencesMenu", UiKeys.command(KeyEvent.VK_1, InputEvent.SHIFT_DOWN_MASK), //$NON-NLS-1$
            this::showReferencesMenu));
        bindViewerKey(createAction("zoomIn", KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.ALT_DOWN_MASK), () -> setZoom(zoom + 1))); //$NON-NLS-1$
        bindViewerKey(createAction("zoomOut", KeyStroke.getKeyStroke(KeyEvent.VK_9, InputEvent.ALT_DOWN_MASK), () -> setZoom(zoom - 1))); //$NON-NLS-1$

        Action fetchNext = createAction(EditorMessages.editor_viewer_fetch_next, null, () -> readNextSegment(false));
        fetchNextButton = createLinkButton(fetchNext);
        Action fetchAll = createAction(EditorMessages.editor_viewer_fetch_all, null, this::readAllData);
        fetchAll.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_fetch_all_tip);
        fetchAllButton = createLinkButton(fetchAll);

        cards.add(new JPanel(), CARD_GRID);
        cards.add(recordView, CARD_RECORD);
        cards.add(chartView, CARD_CHART);
        cards.add(spatialView, CARD_SPATIAL);
        cards.add(textView, CARD_TEXT);
        // The split stays in place and the hidden value panel is invisible: moving the grid to another parent would
        // take the keyboard focus away from it
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cards, valuePanel);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        valuePanel.setVisible(false);
        // The same for the panels beside the result
        panelSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, split, sidePanels);
        panelSplit.setResizeWeight(1.0);
        panelSplit.setContinuousLayout(true);
        panelSplit.setBorder(BorderFactory.createEmptyBorder());
        sidePanels.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        sidePanels.setVisible(false);
        sidePanels.addChangeListener(e -> refreshSidePanel());

        add(findBar, BorderLayout.NORTH);
        add(panelSplit, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        selectionTimer = new Timer(100, e -> selectionChanged());
        selectionTimer.setRepeats(false);
        valuePanel.setEditor(this::applyValueText);

        installGrid(new ResultSetGrid(fetched, gridHost), false);
        if (valuePanelDefault) {
            setValuePanelShown(true);
        }
    }

    //////////////////////////////////////////////////////////
    // Access for the results view

    @NotNull
    ResultSetGrid getGrid() {
        return grid;
    }

    /**
     * The grid's rows as the text view shows them, for the results view's Text tab.
     */
    @NotNull
    String formatText() {
        ResultSetTextView.Source source = new TextSource();
        JTable table = source.getTable();
        return table == null ? "" : ResultSetTextView.format(table, source::isNumeric); //$NON-NLS-1$
    }

    /**
     * Releases the LOB values kept for the value panel and stops the external editor's watches, deleting their
     * files (the results are cleared).
     */
    void dispose() {
        selectionTimer.stop();
        externalEditor.stopAll();
        valuePanel.dispose();
        referencesPanel.dispose();
        releaseContents(fetched.source().cellValues());
        // The results kept to go back to
        for (HistoryState state : history) {
            if (state != null) {
                releaseContents(state.fetched().source().cellValues());
            }
        }
        history.clear();
    }

    //////////////////////////////////////////////////////////
    // Layout

    @NotNull
    private JComponent createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(UiSizes.createPadding(UiSizes.VIEWER_STATUS_PADDING));
        // Wraps to a second line when the row count, filter and sort summaries don't fit: a plain FlowLayout wrapped
        // them below the one-line status bar, where they were cut off (sort summary next to filters in a narrow result)
        JPanel left = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        rowsLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        // Foreign key navigation: back, forward and the table it reached, only after a navigation
        left.add(backButton);
        left.add(forwardButton);
        left.add(navigationLabel);
        left.add(rowsLabel);
        left.add(fetchNextButton);
        left.add(fetchAllButton);
        left.add(countButton);
        // Active filters: only when there are any, so the status line keeps its height and no row is added
        filterSummary.setOpaque(false);
        filterSummary.add(new JLabel(ResultSetHeaderRenderer.createActiveFilterIcon()));
        filterSummary.add(filterSummaryLabel);
        Action clearFilters = createAction(GridFilterMessages.gridfilter_clear_all, null, this::clearAllFilters);
        JButton clearFiltersButton = UiIcons.createActionButton(clearFilters, "close"); //$NON-NLS-1$
        // As high as the status line's text (an icon button's 24 would make the line grow while there are filters)
        clearFiltersButton.setMargin(new Insets(1, 1, 1, 1));
        filterSummary.add(clearFiltersButton);
        filterSummary.setVisible(false);
        left.add(filterSummary);
        // Active sort: the columns, whether the sort replaced the query's ORDER BY, and the way back to the query's order
        sortSummary.setOpaque(false);
        sortSummary.add(sortSummaryLabel);
        Action restoreOrder = createAction(GridFilterMessages.gridfilter_sort_restore, null, this::clearSorting);
        JButton restoreOrderButton = UiIcons.createActionButton(restoreOrder, "close"); //$NON-NLS-1$
        restoreOrderButton.setMargin(new Insets(1, 1, 1, 1));
        sortSummary.add(restoreOrderButton);
        sortSummary.setVisible(false);
        left.add(sortSummary);
        aggregateLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.setOpaque(false);
        right.add(aggregateLabel);
        right.add(valuePanelToggle);
        right.add(recordToggle);
        right.add(textToggle);
        right.add(chartToggle);
        right.add(spatialToggle);
        right.add(referencesToggle);
        right.add(aggregatesToggle);
        bar.add(left, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    @NotNull
    private static JButton createLinkButton(@NotNull Action action) {
        JButton button = new JButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor; margin: 0,2,0,2"); //$NON-NLS-1$
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    @NotNull
    private static JToggleButton createToggle(@NotNull Action action) {
        JToggleButton button = new JToggleButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, UiSizes.TEXT_TOOL_BUTTON_STYLE);
        button.setFocusable(false);
        return button;
    }

    private boolean isRecordMode() {
        return viewMode == ViewMode.RECORD;
    }

    /**
     * Shows a view in place of the grid, or the grid again when that view is shown already.
     */
    private void toggleViewMode(@NotNull ViewMode mode) {
        setViewMode(viewMode == mode ? ViewMode.GRID : mode);
    }

    private void setViewMode(@NotNull ViewMode mode) {
        this.viewMode = mode;
        recordToggle.setSelected(mode == ViewMode.RECORD);
        chartToggle.setSelected(mode == ViewMode.CHART);
        spatialToggle.setSelected(mode == ViewMode.SPATIAL);
        textToggle.setSelected(mode == ViewMode.TEXT);
        ((CardLayout) cards.getLayout()).show(cards, mode.card);
        switch (mode) {
            case RECORD -> {
                recordView.refresh();
                // The record line (a model column) of the grid's selected column, so the value panel keeps showing the same cell
                int viewColumn = grid != null ? grid.getTable().getColumnModel().getSelectionModel().getLeadSelectionIndex() : -1;
                recordView.focusTable(grid != null && viewColumn >= 0 && viewColumn < grid.getTable().getColumnCount()
                    ? grid.getTable().convertColumnIndexToModel(viewColumn) : -1);
            }
            // The chart starts from the cells selected in the grid
            case CHART -> chartView.configureFromSelection();
            case SPATIAL -> spatialView.refresh();
            case TEXT -> textView.refresh();
            case GRID -> {
                if (grid != null) {
                    grid.getTable().requestFocusInWindow();
                }
            }
        }
        scheduleSelectionUpdate();
    }

    /**
     * Whether a column has geometry values the spatial view can draw (the Spatial button is shown only then).
     */
    private boolean hasSpatialColumns() {
        for (DBDAttributeBinding binding : fetched.data().bindings()) {
            if (ResultSetSpatialView.isSpatialColumn(binding)) {
                return true;
            }
        }
        return false;
    }

    private void setValuePanelShown(boolean shown) {
        if (shown == valuePanelShown) {
            valuePanelToggle.setSelected(shown);
            return;
        }
        valuePanelShown = shown;
        valuePanelDefault = shown;
        valuePanelToggle.setSelected(shown);
        if (shown) {
            valuePanel.setVisible(true);
            placeDivider();
        } else {
            valuePanelWidth = valuePanel.getWidth();
            valuePanel.setVisible(false);
        }
        split.revalidate();
        split.repaint();
        scheduleSelectionUpdate();
    }

    /**
     * Puts the value panel at its last width, or at about a third of the viewer. A new viewer has no size yet (the
     * panel opens with it): then the divider is placed at the first layout.
     */
    private void placeDivider() {
        if (split.getWidth() <= 0) {
            split.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (split.getWidth() > 0) {
                        split.removeComponentListener(this);
                        placeDivider();
                    }
                }
            });
            return;
        }
        int width = valuePanelWidth > 0 ? valuePanelWidth : Math.max(260, split.getWidth() * 35 / 100);
        split.setDividerLocation(Math.max(120, split.getWidth() - width - split.getDividerSize()));
    }

    //////////////////////////////////////////////////////////
    // Grid installation

    /**
     * Puts a new grid in place of the current one.
     *
     * @param keepView keep the column widths, scroll position and selection of the current grid (same columns and rows)
     */
    private void installGrid(@NotNull ResultSetGrid newGrid, boolean keepView) {
        ResultSetGrid oldGrid = grid;
        Point viewPosition = null;
        int selectedRow = -1;
        int selectedColumn = -1;
        ResultSetColumnLayout.State layout = null;
        if (oldGrid != null) {
            JTable oldTable = oldGrid.getTable();
            layout = ResultSetColumnLayout.capture(oldTable);
            if (keepView) {
                JViewport viewport = getViewport(oldTable);
                viewPosition = viewport != null ? viewport.getViewPosition() : null;
                selectedRow = oldTable.getSelectedRow();
                selectedColumn = oldTable.getSelectedColumn();
            }
            cards.remove(oldGrid);
        } else {
            cards.remove(0);
        }
        grid = newGrid;
        cards.add(newGrid, CARD_GRID, 0);
        JTable table = newGrid.getTable();
        // The same rows in another order or with more rows: the user's column layout and widths stay. A new read of the
        // same columns keeps the hidden columns and their order, its widths are fitted again. Other columns start anew.
        boolean restored = layout != null && (keepView || !ResultSetColumnLayout.isNatural(layout))
            && ResultSetColumnLayout.restore(table, layout, keepView);
        installHeader(table);
        if (!(restored && keepView)) {
            fitHeaderWidths(table);
        }
        gridFont = table.getFont();
        gridRowHeight = table.getRowHeight();
        if (zoom != 0) {
            ResultSetColumnLayout.applyZoom(table, gridFont, gridRowHeight, zoom);
        }
        installMenus(table);
        ListSelectionListener selectionListener = e -> scheduleSelectionUpdate();
        table.getSelectionModel().addListSelectionListener(selectionListener);
        table.getColumnModel().getSelectionModel().addListSelectionListener(selectionListener);
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        if (scrollPane != null) {
            lastScrollValue = 0;
            scrollPane.getVerticalScrollBar().addAdjustmentListener(this::verticalScrolled);
        }
        // The same columns in a new grid of the same viewer, but checked again so the button can't outlive its columns
        spatialToggle.setVisible(hasSpatialColumns());
        if (viewMode == ViewMode.SPATIAL && !spatialToggle.isVisible()) {
            viewMode = ViewMode.GRID;
            spatialToggle.setSelected(false);
        }
        ((CardLayout) cards.getLayout()).show(cards, viewMode.card);
        cards.revalidate();
        cards.repaint();
        if (selectedRow >= 0 && selectedColumn >= 0 && selectedRow < table.getRowCount()) {
            boolean autoscrolls = table.getAutoscrolls();
            table.setAutoscrolls(false);
            table.changeSelection(selectedRow, selectedColumn, false, false);
            table.setAutoscrolls(autoscrolls);
        }
        if (viewPosition != null) {
            Point position = viewPosition;
            SwingUtilities.invokeLater(() -> {
                JViewport viewport = getViewport(table);
                if (viewport != null) {
                    viewport.setViewPosition(position);
                }
            });
        }
        updateStatus();
        recordView.refresh();
        // Sorted, filtered or more rows: the chart, the map and the text show the grid's rows
        if (viewMode == ViewMode.CHART) {
            chartView.refresh();
        } else if (viewMode == ViewMode.SPATIAL) {
            spatialView.refresh();
        } else if (viewMode == ViewMode.TEXT) {
            textView.refresh();
        }
        checkLinkTables();
        scheduleSelectionUpdate();
    }

    @Nullable
    private static JViewport getViewport(@NotNull JTable table) {
        return table.getParent() instanceof JViewport viewport ? viewport : null;
    }

    /**
     * Column headers with the column icon, the filter and sort icons ({@link ResultSetHeaderRenderer}); a click on the
     * funnel opens the column's filter popup, on the sort icon it sorts (Ctrl adds the column to the sort), on the name
     * it selects the whole column ({@link #selectColumns}).
     */
    private void installHeader(@NotNull JTable table) {
        JTableHeader header = table.getTableHeader();
        TableCellRenderer delegate = header.getDefaultRenderer();
        // The wrapper is not a component, so a look and feel switch would not reach the wrapped renderer
        header.addPropertyChangeListener("UI", e -> { //$NON-NLS-1$
            if (delegate instanceof Component component) {
                SwingUtilities.updateComponentTreeUI(component);
            }
        });
        ResultSetHeaderRenderer renderer = new ResultSetHeaderRenderer(delegate, new ResultSetHeaderRenderer.Columns() {
            @NotNull
            @Override
            public DBDAttributeBinding getBinding(int modelColumn) {
                return fetched.data().bindings()[modelColumn];
            }

            @Override
            public boolean isFiltered(int modelColumn) {
                DBDAttributeConstraint constraint = getConstraint(modelColumn);
                return constraint != null && constraint.hasCondition();
            }

            @Override
            public int getSortDirection(int modelColumn) {
                DBDAttributeConstraint constraint = getConstraint(modelColumn);
                if (constraint == null || constraint.getOrderPosition() <= 0) {
                    return 0;
                }
                return constraint.isOrderDescending() ? -1 : 1;
            }

            @Override
            public boolean isFilterEnabled(int modelColumn) {
                // A virtual column is calculated in Chromite: the database can't filter it
                return fetched.source().canRequery() && !reading && !fetched.data().bindings()[modelColumn].isCustom();
            }
        });
        header.setDefaultRenderer(renderer);
        // Columns are moved by dragging their header (SWT moveColumnLeft/Right); a drag is not a header click
        header.setReorderingAllowed(true);
        boolean[] dragged = {false};
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragged[0] = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1 || dragged[0]
                    || header.getCursor().getType() == Cursor.E_RESIZE_CURSOR) {
                    return;
                }
                int column = header.columnAtPoint(e.getPoint());
                if (column < 0 || isNearColumnBorder(header, column, e.getX())) {
                    return;
                }
                // Ctrl+click on Windows, Command-click on macOS (where Ctrl+click is the right click)
                boolean ctrl = UiKeys.isCommandDown(e);
                switch (renderer.zoneAt(header, column, e.getPoint())) {
                    case FILTER -> openFilterPopup(header, column);
                    case SORT -> toggleSort(table.convertColumnIndexToModel(column), null, ctrl);
                    case NAME -> selectColumns(table, column, ctrl, (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (renderer.setHover(-1, ResultSetHeaderRenderer.Zone.NAME)) {
                    header.repaint();
                }
            }
        });
        header.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                dragged[0] = true;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int column = header.columnAtPoint(e.getPoint());
                ResultSetHeaderRenderer.Zone zone = column < 0 ? ResultSetHeaderRenderer.Zone.NAME : renderer.zoneAt(header, column, e.getPoint());
                if (renderer.setHover(column, zone)) {
                    header.repaint();
                }
                header.setToolTipText(column < 0 ? null : getHeaderToolTip(table.convertColumnIndexToModel(column), zone));
            }
        });
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showHeaderMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showHeaderMenu(e);
            }

            private void showHeaderMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int column = header.columnAtPoint(e.getPoint());
                // A new menu each time: a kept one would miss look and feel switches (it is not in the component tree)
                JPopupMenu headerMenu = new JPopupMenu();
                if (column >= 0) {
                    int modelColumn = table.convertColumnIndexToModel(column);
                    headerMenu.add(createAction(EditorMessages.editor_viewer_sort_ascending, null, () -> toggleSort(modelColumn, Boolean.FALSE, false)));
                    headerMenu.add(createAction(EditorMessages.editor_viewer_sort_descending, null, () -> toggleSort(modelColumn, Boolean.TRUE, false)));
                }
                Action clearSort = createAction(EditorMessages.editor_viewer_clear_sorting, null, this::clearSorting);
                clearSort.setEnabled(dataFilter.hasOrdering());
                headerMenu.add(clearSort);
                headerMenu.addSeparator();
                if (column >= 0) {
                    headerMenu.add(createAction(EditorMessages.editor_viewer_fit_column, null, () -> ResultSetColumnFit.fitColumn(table, column)));
                }
                headerMenu.add(createAction(EditorMessages.editor_viewer_fit_all_columns, null, () -> ResultSetColumnFit.fitAllColumns(table)));
                headerMenu.addSeparator();
                addColumnItems(headerMenu, table, column);
                headerMenu.show(header, e.getX(), e.getY());
            }

            private void clearSorting() {
                ResultSetViewerPanel.this.clearSorting();
            }
        });
        // Double click on a column border fits the column, like the header of DataGrip and Excel
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    int column = header.columnAtPoint(e.getPoint());
                    if (column >= 0 && isNearColumnBorder(header, column, e.getX())) {
                        Rectangle rect = header.getHeaderRect(column);
                        int target = e.getX() - rect.x < HEADER_CLICK_BORDER && column > 0 ? column - 1 : column;
                        ResultSetColumnFit.fitColumn(table, target);
                    }
                }
            }
        });
    }

    private static boolean isNearColumnBorder(@NotNull JTableHeader header, int column, int x) {
        Rectangle rect = header.getHeaderRect(column);
        return x - rect.x < HEADER_CLICK_BORDER || rect.x + rect.width - x <= HEADER_CLICK_BORDER;
    }

    /**
     * Selects whole columns from a click on a header name, like Excel: one column, {@code add} (Ctrl) adds the column to
     * the selected ones, {@code range} (Shift) selects the columns from the anchor column to this one.
     * <p>
     * The selection models are set directly instead of {@code changeSelection}: that scrolls to the cell it selects (the
     * grid would jump to the last row) and toggles a selected cell on Ctrl. The lead and anchor go to the first row, so
     * the focus cell stays at the top of the column (Excel's active cell) and arrow keys start from there.
     */
    private static void selectColumns(@NotNull JTable table, int column, boolean add, boolean range) {
        int rowCount = table.getRowCount();
        if (rowCount == 0) {
            return;
        }
        ListSelectionModel columns = table.getColumnModel().getSelectionModel();
        int anchor = columns.getAnchorSelectionIndex();
        if (range && anchor >= 0 && anchor < table.getColumnCount()) {
            if (add) {
                columns.addSelectionInterval(anchor, column);
            } else {
                columns.setSelectionInterval(anchor, column);
            }
        } else if (add) {
            columns.addSelectionInterval(column, column);
        } else {
            columns.setSelectionInterval(column, column);
        }
        ListSelectionModel rows = table.getSelectionModel();
        rows.setSelectionInterval(0, rowCount - 1);
        if (rows instanceof DefaultListSelectionModel model) {
            model.moveLeadSelectionIndex(0);
        }
        table.requestFocusInWindow();
    }

    /**
     * Widens the columns whose first width (set by the grid from the plain header) leaves no room for the header icons.
     */
    private static void fitHeaderWidths(@NotNull JTable table) {
        TableCellRenderer renderer = table.getTableHeader().getDefaultRenderer();
        int maxWidth = UIScale.scale(HEADER_FIT_MAX_WIDTH);
        for (int column = 0; column < table.getColumnCount(); column++) {
            TableColumn tableColumn = table.getColumnModel().getColumn(column);
            int width = renderer.getTableCellRendererComponent(table, tableColumn.getHeaderValue(), false, false, -1, column)
                .getPreferredSize().width + table.getIntercellSpacing().width;
            if (width > tableColumn.getPreferredWidth()) {
                tableColumn.setPreferredWidth(Math.min(width, Math.max(maxWidth, tableColumn.getPreferredWidth())));
            }
        }
    }

    @Nullable
    private String getHeaderToolTip(int column, @NotNull ResultSetHeaderRenderer.Zone zone) {
        DBDAttributeBinding binding = fetched.data().bindings()[column];
        DBDAttributeConstraint constraint = getConstraint(column);
        return switch (zone) {
            case NAME -> {
                String tip = NLS.bind(GridFilterMessages.gridfilter_header_column_tip, binding.getLabel(), binding.getFullTypeName(),
                    UiKeys.getCommandText());
                yield binding instanceof DBDAttributeBindingCustom custom
                    ? tip + "  —  " + NLS.bind(GridVirtualMessages.gridvirt_column_tip, custom.getEntityAttribute().getExpression()) //$NON-NLS-1$
                    : tip;
            }
            case FILTER -> {
                if (binding.isCustom()) {
                    yield GridVirtualMessages.gridvirt_column_no_filter;
                }
                if (!fetched.source().canRequery()) {
                    yield NLS.bind(EditorMessages.editor_viewer_filters_need, fetched.source().requeryBlocker());
                }
                String condition = constraint != null && constraint.hasCondition() ? getConditionText(constraint) : null;
                yield CommonUtils.isEmpty(condition) ? GridFilterMessages.gridfilter_header_filter_tip
                    : NLS.bind(GridFilterMessages.gridfilter_header_filtered_tip, condition);
            }
            case SORT -> {
                if (constraint == null || constraint.getOrderPosition() <= 0) {
                    yield NLS.bind(GridFilterMessages.gridfilter_header_sort_ascending_tip, UiKeys.getCommandText());
                }
                yield constraint.isOrderDescending() ? GridFilterMessages.gridfilter_header_sort_clear_tip
                    : GridFilterMessages.gridfilter_header_sort_descending_tip;
            }
        };
    }

    /**
     * Opens the filter popup of a column (view index) below its header cell.
     */
    private void openFilterPopup(@NotNull JTableHeader header, int column) {
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        DBDAttributeBinding binding = bindings[header.getTable().convertColumnIndexToModel(column)];
        if (reading || !fetched.source().canRequery() || binding.isCustom()) {
            return;
        }
        // The popup reads the values with the other columns' conditions: without a sort of virtual columns (not in the database)
        ResultSetColumnFilterPopup.show(header, header.getHeaderRect(column), binding,
            bindings, ResultSetVirtualColumns.withoutVirtualColumns(dataFilter), fetched.source(), new ResultSetColumnFilterPopup.Host() {
                @Override
                public void applyFilter(@NotNull DBDDataFilter filter) {
                    ResultSetViewerPanel.this.applyFilter(filter);
                }

                @Override
                public void clearAllFilters() {
                    ResultSetViewerPanel.this.clearAllFilters();
                }
            });
    }

    /**
     * Adds the viewer's items to the grid's context menu: filters, copy formats, Generate SQL, panels.
     */
    private void installMenus(@NotNull JTable table) {
        JPopupMenu menu = table.getComponentPopupMenu();
        if (menu == null) {
            return;
        }
        JMenu filterMenu = new JMenu(EditorMessages.editor_viewer_filter_by_value);
        JMenu copyAsMenu = ResultSetCopyFormats.createMenu(table, () -> grid.getCurrentData(), this::getDataSource);
        JMenu generateSqlMenu = new JMenu(GridSqlMessages.gridsql_menu);
        ResultSetGenerateSql.Source generateSqlSource = new GenerateSqlSource(table);
        Action clearFilter = createAction(EditorMessages.editor_common_clear_filter, null, this::clearAllFilters);
        Action fitColumns = createAction(EditorMessages.editor_viewer_fit_all_columns, null, () -> ResultSetColumnFit.fitAllColumns(table));
        JCheckBoxMenuItem valuePanelItem = new JCheckBoxMenuItem(valuePanelToggle.getAction());
        JCheckBoxMenuItem recordItem = new JCheckBoxMenuItem(recordToggle.getAction());
        JMenuItem loadFileItem = new JMenuItem(loadFileAction);
        JMenuItem saveFileItem = new JMenuItem(saveFileAction);
        JCheckBoxMenuItem chartItem = new JCheckBoxMenuItem(chartToggle.getAction());
        JCheckBoxMenuItem spatialItem = new JCheckBoxMenuItem(spatialToggle.getAction());
        JCheckBoxMenuItem textItem = new JCheckBoxMenuItem(textToggle.getAction());
        JCheckBoxMenuItem referencesItem = new JCheckBoxMenuItem(referencesToggle.getAction());
        JCheckBoxMenuItem aggregatesItem = new JCheckBoxMenuItem(aggregatesToggle.getAction());
        JMenu navigateMenu = new JMenu(GridNavMessages.gridnav_menu_navigate);
        JMenu columnsMenu = new JMenu(GridNavMessages.gridnav_menu_columns);
        JMenu virtualMenu = new JMenu(GridNavMessages.gridnav_menu_virtual);
        JMenu savedFiltersMenu = new JMenu(GridVirtualMessages.gridvirt_filters_menu);
        JMenu virtualColumnsMenu = new JMenu(GridVirtualMessages.gridvirt_columns_menu);
        JMenuItem findItem = new JMenuItem(createAction(GridNavMessages.gridnav_find,
            UiKeys.command(KeyEvent.VK_F), this::openFindBar));

        int copyIndex = -1;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JMenuItem item && EditorMessages.editor_grid_copy_with_headers.equals(item.getText())) {
                copyIndex = i;
            }
        }
        if (copyIndex >= 0) {
            menu.insert(copyAsMenu, copyIndex + 1);
            menu.insert(generateSqlMenu, copyIndex + 2);
        } else {
            menu.add(copyAsMenu);
            menu.add(generateSqlMenu);
        }
        menu.addSeparator();
        menu.add(loadFileItem);
        menu.add(saveFileItem);
        menu.add(new JMenuItem(hexAction));
        menu.add(new JMenuItem(externalAction));
        menu.addSeparator();
        menu.add(filterMenu);
        menu.add(clearFilter);
        menu.add(savedFiltersMenu);
        menu.add(navigateMenu);
        menu.addSeparator();
        menu.add(fitColumns);
        menu.add(columnsMenu);
        menu.add(virtualMenu);
        menu.add(virtualColumnsMenu);
        menu.add(findItem);
        menu.addSeparator();
        menu.add(valuePanelItem);
        menu.add(recordItem);
        menu.add(textItem);
        menu.add(chartItem);
        menu.add(spatialItem);
        menu.add(referencesItem);
        menu.add(aggregatesItem);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                fillFilterMenu(filterMenu, table);
                ResultSetGenerateSql.fillMenu(generateSqlMenu, generateSqlSource);
                clearFilter.setEnabled(fetched.source().canRequery() && dataFilter.hasConditions() && !reading);
                valuePanelItem.setSelected(valuePanelShown);
                recordItem.setSelected(isRecordMode());
                updateFileActions();
                chartItem.setSelected(viewMode == ViewMode.CHART);
                spatialItem.setVisible(spatialToggle.isVisible());
                spatialItem.setSelected(viewMode == ViewMode.SPATIAL);
                textItem.setSelected(viewMode == ViewMode.TEXT);
                referencesItem.setSelected(isSidePanelShown(referencesPanel));
                aggregatesItem.setSelected(isSidePanelShown(aggregatePanel));
                fillNavigateMenu(navigateMenu);
                columnsMenu.removeAll();
                int lead = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
                addColumnItems(columnsMenu, table, lead >= 0 && lead < table.getColumnCount() ? lead : -1);
                fillVirtualMenu(virtualMenu);
                fillSavedFiltersMenu(savedFiltersMenu);
                fillVirtualColumnsMenu(virtualColumnsMenu, table);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
    }

    /**
     * The value filters of the selected cell (SWT filter menu: operators with the cell value, and without arguments).
     */
    private void fillFilterMenu(@NotNull JMenu filterMenu, @NotNull JTable table) {
        filterMenu.removeAll();
        int row = table.getSelectedRow();
        int column = table.getSelectedColumn();
        boolean enabled = fetched.source().canRequery() && !reading && row >= 0 && column >= 0;
        filterMenu.setEnabled(enabled);
        if (!enabled) {
            filterMenu.setToolTipText(fetched.source().canRequery() ? null : NLS.bind(EditorMessages.editor_viewer_filters_need, fetched.source().requeryBlocker()));
            return;
        }
        filterMenu.setToolTipText(null);
        int modelColumn = table.convertColumnIndexToModel(column);
        DBDAttributeBinding binding = fetched.data().bindings()[modelColumn];
        if (binding.isCustom()) {
            filterMenu.setEnabled(false);
            filterMenu.setToolTipText(GridVirtualMessages.gridvirt_column_no_filter);
            return;
        }
        Object value = getCellValue(row, modelColumn);
        String name = binding.getName();
        // Geometries are kept for the spatial view only, arrays and structures for their editors; they are not offered as
        // filter values
        if (value != null && !(value instanceof ResultSetCellContent) && !(value instanceof DBGeometry) && !ResultSetGridReceiver.isComplexValue(value)) {
            String shown = CommonUtils.truncateString(String.valueOf(getDisplayValue(row, modelColumn)).replaceAll("\\s+", " "), 60); //$NON-NLS-1$ //$NON-NLS-2$
            for (DBCLogicalOperator operator : new DBCLogicalOperator[]{
                DBCLogicalOperator.EQUALS, DBCLogicalOperator.NOT_EQUALS, DBCLogicalOperator.GREATER, DBCLogicalOperator.LESS}) {
                if (supportsOperator(binding, operator)) {
                    filterMenu.add(createAction(name + " " + operator.getExpression() + " '" + shown + "'", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        () -> filterByValue(binding, operator, value)));
                }
            }
            filterMenu.addSeparator();
        }
        for (DBCLogicalOperator operator : new DBCLogicalOperator[]{DBCLogicalOperator.IS_NULL, DBCLogicalOperator.IS_NOT_NULL}) {
            if (supportsOperator(binding, operator)) {
                filterMenu.add(createAction(name + " " + operator.getExpression(), null, () -> filterByValue(binding, operator, null))); //$NON-NLS-1$
            }
        }
        DBDAttributeConstraint constraint = dataFilter.getConstraint(binding);
        if (constraint != null && constraint.hasCondition()) {
            filterMenu.addSeparator();
            filterMenu.add(createAction(NLS.bind(EditorMessages.editor_viewer_remove_filter_of, name), null, () -> {
                DBDDataFilter filter = new DBDDataFilter(dataFilter);
                DBDAttributeConstraint copy = filter.getConstraint(binding);
                if (copy != null) {
                    ResultSetColumnFilterPopup.clearCondition(copy);
                }
                applyFilter(filter);
            }));
        }
    }

    private static boolean supportsOperator(@NotNull DBDAttributeBinding binding, @NotNull DBCLogicalOperator operator) {
        for (DBCLogicalOperator supported : binding.getValueHandler().getSupportedOperators(binding)) {
            if (supported == operator) {
                return true;
            }
        }
        return false;
    }

    //////////////////////////////////////////////////////////
    // Cell access

    /**
     * The original value of a cell: the edited value of an editable result, the kept value, the LOB content or the
     * geometry, or null for NULL and values that are not kept.
     */
    @Nullable
    private Object getCellValue(int row, int column) {
        ResultSetEditModel editModel = fetched.editModel();
        List<Object[]> cells = fetched.source().cellValues();
        if (editModel != null) {
            if (row >= editModel.getRowCount()) {
                return null;
            }
            ResultSetEditModel.Row editRow = editModel.getRow(row);
            Object value = editRow.values[column];
            // Value objects the grid did not read (LOB content, geometries): the model has null there, or the empty value
            // object that binding the column to its table made of that null (MariaDB geometry: an empty DBGeometry).
            // A LOB set in the grid (panel, file) is the model's value
            if (DBUtils.isNullValue(value) && !editModel.hasValue(row, column)) {
                int number = editRow.getRowNumber();
                Object cell = number < cells.size() ? cells.get(number)[column] : null;
                if (cell instanceof ResultSetCellContent || cell instanceof DBGeometry) {
                    return cell;
                }
            }
            return value;
        }
        return row < cells.size() ? cells.get(row)[column] : null;
    }

    @Nullable
    private Object getDisplayValue(int row, int column) {
        ResultSetEditModel editModel = fetched.editModel();
        if (editModel != null) {
            return row < editModel.getRowCount() ? editModel.getRow(row).display[column] : null;
        }
        List<Object[]> rows = fetched.data().rows();
        return row < rows.size() ? rows.get(row)[column] : null;
    }

    private boolean isCellEditable(int row, int column) {
        ResultSetEditModel editModel = fetched.editModel();
        return editModel != null && row < editModel.getRowCount() && editModel.isCellEditable(row, column);
    }

    /**
     * Whether the value panel edits the cell as text: an editable cell, except a binary LOB (loaded from a file).
     */
    private boolean isTextEditable(int row, int column) {
        ResultSetEditModel editModel = fetched.editModel();
        return isCellEditable(row, column) && (!editModel.isContentColumn(column) || editModel.isTextContentColumn(column));
    }

    /**
     * The selected cell (model column), in the grid or the record view, or null.
     */
    @Nullable
    private int[] getSelectedCell() {
        if (grid == null) {
            return null;
        }
        JTable table = grid.getTable();
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int column = getSelectedModelColumn(table);
        if (row < 0 || row >= table.getRowCount() || column < 0) {
            return null;
        }
        return new int[]{row, column};
    }

    /**
     * The model column of the selected cell: the record view's line (its lines are the model columns) or the grid's
     * column (a view column: columns may be hidden or moved), or -1.
     */
    private int getSelectedModelColumn(@NotNull JTable table) {
        if (isRecordMode() && recordView.getSelectedColumn() >= 0) {
            return recordView.getSelectedColumn();
        }
        int viewColumn = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        return viewColumn >= 0 && viewColumn < table.getColumnCount() ? table.convertColumnIndexToModel(viewColumn) : -1;
    }

    /**
     * Load from File and Save to File apply to the selected LOB cell (load: an editable one).
     */
    private void updateFileActions() {
        int[] cell = getSelectedCell();
        ResultSetEditModel editModel = fetched.editModel();
        boolean load = cell != null && editModel != null && editModel.isContentColumn(cell[1]) && isCellEditable(cell[0], cell[1]);
        Object value = cell == null ? null : getCellValue(cell[0], cell[1]);
        boolean save = value instanceof ResultSetCellContent || value instanceof byte[]
            || value instanceof DBDContent content && !DBUtils.isNullValue(content);
        loadFileAction.setEnabled(load && !reading);
        saveFileAction.setEnabled(save);
        // Hex: a binary value, or an editable binary LOB cell (also NULL: new bytes are typed)
        boolean binary = value instanceof byte[] || (value instanceof ResultSetCellContent content && !content.isText())
            || (value instanceof DBDContent content && !DBUtils.isNullValue(content) && !ResultSetLobFiles.isText(content));
        hexAction.setEnabled(cell != null && (binary || load && !editModel.isTextContentColumn(cell[1])) && !reading);
        // External editor: a value to open, or an editable cell to fill
        externalAction.setEnabled(cell != null && (save || isTextEditable(cell[0], cell[1]) || load
            || getDisplayValue(cell[0], cell[1]) instanceof String) && !reading);
    }

    /**
     * Edit as Hex… (SWT hex editor of binary values): the whole value, up to what the value panel reads, in the hex
     * dialog; an editable binary LOB cell gets the edited bytes through the column's handler (as Load from File).
     */
    private void editCellAsHex() {
        int[] cell = getSelectedCell();
        if (cell == null || grid == null) {
            return;
        }
        ResultSetEditModel editModel = fetched.editModel();
        boolean editable = editModel != null && editModel.isContentColumn(cell[1]) && isCellEditable(cell[0], cell[1]);
        Object value = getCellValue(cell[0], cell[1]);
        String columnName = fetched.data().bindings()[cell[1]].getName();
        ResultSetCellContent.Loaded loaded;
        try {
            loaded = ProgressDialog.runTask(ValueEditMessages.valueedit_hex_read_title, monitor -> loadBytes(monitor, value));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ValueEditMessages.valueedit_hex_read_title, ErrorText.describeLine(e), e);
            return;
        }
        if (loaded.truncated()) {
            JOptionPane.showMessageDialog(this, NLS.bind(ValueEditMessages.valueedit_hex_too_large,
                    String.format("%,d", loaded.length()), String.format("%,d", ResultSetCellContent.MAX_LOAD_SIZE)), //$NON-NLS-1$ //$NON-NLS-2$
                ValueEditMessages.valueedit_hex_read_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        byte[] bytes = loaded.bytes() != null ? loaded.bytes() : CommonUtils.notEmpty(loaded.text()).getBytes(StandardCharsets.UTF_8);
        byte[] edited = ResultSetHexEditor.edit(this, columnName, bytes, editable);
        if (edited == null || grid == null) {
            return;
        }
        String error = grid.setCellObject(cell[0], cell[1], edited);
        if (error != null) {
            DBWorkbench.getPlatformUI().showError(ValueEditMessages.valueedit_hex_read_title, error);
        }
    }

    /**
     * The content of a cell for the hex editor (job thread): read as the value panel reads it, so a value larger than
     * {@link ResultSetCellContent#MAX_LOAD_SIZE} comes back truncated and is not opened. NULL is no bytes.
     */
    @NotNull
    private static ResultSetCellContent.Loaded loadBytes(@NotNull DBRProgressMonitor monitor, @Nullable Object value) throws DBException {
        if (value instanceof byte[] bytes) {
            return new ResultSetCellContent.Loaded(bytes, null, bytes.length, false);
        }
        ResultSetCellContent content = value instanceof ResultSetCellContent cellContent ? cellContent
            : value instanceof DBDContent dbContent && !DBUtils.isNullValue(dbContent) ? ResultSetCellContent.capture(dbContent) : null;
        return content == null ? new ResultSetCellContent.Loaded(new byte[0], null, 0, false) : content.load(monitor);
    }

    /**
     * Open in External Editor: the cell's value in a file opened by the default application of its type; for an
     * editable cell, each save of the file is put in the cell ({@link ResultSetExternalEditor}).
     */
    private void openCellExternally() {
        int[] cell = getSelectedCell();
        ResultSetGrid ownerGrid = grid;
        if (cell == null || ownerGrid == null) {
            return;
        }
        int column = cell[1];
        DBDAttributeBinding binding = fetched.data().bindings()[column];
        ResultSetEditModel editModel = fetched.editModel();
        boolean contentColumn = editModel != null && editModel.isContentColumn(column);
        boolean editable = isCellEditable(cell[0], column) && (contentColumn || isTextEditable(cell[0], column));
        Object value = getCellValue(cell[0], column);
        boolean text;
        ResultSetExternalEditor.ContentWriter writer;
        if (value instanceof ResultSetCellContent content) {
            text = content.isText();
            writer = content::writeTo;
        } else if (value instanceof byte[] bytes) {
            text = false;
            writer = (monitor, file) -> ResultSetLobFiles.writeLoaded(null, bytes, file);
        } else if (value instanceof DBDContent content && !DBUtils.isNullValue(content)) {
            text = ResultSetLobFiles.isText(content);
            writer = (monitor, file) -> ResultSetLobFiles.writeContent(monitor, content, file, text);
        } else if (contentColumn) {
            // A NULL LOB cell: an empty file to fill
            text = editModel.isTextContentColumn(column);
            writer = (monitor, file) -> ResultSetLobFiles.writeLoaded(text ? "" : null, text ? null : new byte[0], file); //$NON-NLS-1$
        } else {
            text = true;
            Object display = getDisplayValue(cell[0], column);
            String cellText = editable ? editModel.getEditText(cell[0], column)
                : display == null || display == ResultSetGrid.NULL_VALUE ? "" : display.toString(); //$NON-NLS-1$
            writer = (monitor, file) -> ResultSetLobFiles.writeLoaded(cellText, null, file);
        }
        ResultSetExternalEditor.Target target = null;
        if (editable) {
            ResultSetEditModel.Row row = editModel.getRow(cell[0]);
            target = new ResultSetExternalEditor.Target() {
                @Nullable
                @Override
                public String apply(@NotNull Object input) {
                    return isAlive() ? ownerGrid.setCellObject(editModel.indexOf(row), column, input) : EditorMessages.editor_grid_cell_read_only;
                }

                @Override
                public boolean isAlive() {
                    return grid == ownerGrid && editModel.indexOf(row) >= 0;
                }

                @Override
                public void showStatus(@NotNull String text) {
                    if (grid == ownerGrid) {
                        ownerGrid.showStatus(text);
                    }
                }
            };
        }
        externalEditor.open(this, binding.getName(), binding.getTypeName(), text, writer, target);
    }

    /**
     * Load from File (SWT {@code ContentValueManager.loadFromFile}): the file's bytes, or its text for a text LOB,
     * become the cell's value.
     */
    private void loadCellFromFile() {
        int[] cell = getSelectedCell();
        ResultSetEditModel editModel = fetched.editModel();
        if (cell == null || grid == null || editModel == null || !editModel.isContentColumn(cell[1]) || !isCellEditable(cell[0], cell[1])) {
            return;
        }
        Path file = ResultSetLobFiles.chooseOpen(this);
        if (file == null) {
            return;
        }
        boolean text = editModel.isTextContentColumn(cell[1]);
        Object input;
        try {
            input = ProgressDialog.runTask(GridEditMessages.gridedit_lob_load_title, monitor -> ResultSetLobFiles.read(monitor, file, text));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridEditMessages.gridedit_lob_load_title, NLS.bind(GridEditMessages.gridedit_lob_cant_load, file), e);
            return;
        }
        String error = grid.setCellObject(cell[0], cell[1], input);
        if (error != null) {
            DBWorkbench.getPlatformUI().showError(GridEditMessages.gridedit_lob_load_title, NLS.bind(GridEditMessages.gridedit_lob_cant_load, file),
                new DBException(error));
        }
    }

    /**
     * Save to File (SWT {@code ContentValueManager.saveToFile}): the cell's whole value, text as UTF-8.
     */
    private void saveCellToFile() {
        int[] cell = getSelectedCell();
        if (cell == null) {
            return;
        }
        Object value = getCellValue(cell[0], cell[1]);
        boolean text;
        if (value instanceof ResultSetCellContent content) {
            text = content.isText();
        } else if (value instanceof byte[]) {
            text = false;
        } else if (value instanceof DBDContent content && !DBUtils.isNullValue(content)) {
            text = ResultSetLobFiles.isText(content);
        } else {
            return;
        }
        DBDAttributeBinding binding = fetched.data().bindings()[cell[1]];
        Path file = ResultSetLobFiles.chooseSave(this, ResultSetLobFiles.suggestFileName(binding.getName(), binding.getTypeName(), text));
        if (file == null) {
            return;
        }
        try {
            ProgressDialog.runTask(GridEditMessages.gridedit_lob_save_title, monitor -> {
                if (value instanceof ResultSetCellContent content) {
                    content.writeTo(monitor, file);
                } else if (value instanceof byte[] bytes) {
                    ResultSetLobFiles.writeLoaded(null, bytes, file);
                } else {
                    ResultSetLobFiles.writeContent(monitor, (DBDContent) value, file, text);
                }
                return null;
            });
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridEditMessages.gridedit_lob_save_title, NLS.bind(GridEditMessages.gridedit_lob_cant_save, file), e);
        }
    }

    @Nullable
    private DBPDataSource getDataSource() {
        DBCExecutionContext context = fetched.source().context();
        return context != null ? context.getDataSource() : null;
    }

    //////////////////////////////////////////////////////////
    // Selection: value panel, record view, aggregates

    private void scheduleSelectionUpdate() {
        selectionTimer.restart();
    }

    private void selectionChanged() {
        if (grid == null) {
            return;
        }
        JTable table = grid.getTable();
        int row = table.getSelectionModel().getLeadSelectionIndex();
        if (isRecordMode()) {
            recordView.refresh();
        }
        int column = getSelectedModelColumn(table);
        if (valuePanelShown) {
            if (row >= 0 && row < table.getRowCount() && column >= 0) {
                DBDAttributeBinding binding = fetched.data().bindings()[column];
                valuePanel.showCell(new ResultSetValuePanel.Cell(
                    System.identityHashCode(grid) + ":" + row + ":" + column, //$NON-NLS-1$ //$NON-NLS-2$
                    binding,
                    getDisplayValue(row, column),
                    getCellValue(row, column),
                    isTextEditable(row, column)));
            } else {
                valuePanel.showCell(null);
            }
        }
        if (viewMode == ViewMode.SPATIAL) {
            spatialView.selectionChanged();
        }
        // The aggregates describe the grid's selection: only while the grid is shown
        aggregateLabel.setText(viewMode != ViewMode.GRID ? "" : ResultSetAggregates.describeSelection(table, this::getAggregateValue));
        updateFileActions();
        Component sidePanel = sidePanels.isVisible() ? sidePanels.getSelectedComponent() : null;
        if (sidePanel == aggregatePanel) {
            aggregatePanel.refresh();
        } else if (sidePanel == referencesPanel) {
            // The related rows are read again only when other rows are selected (not for another column of the same rows)
            int[] rows = table.getSelectedRows();
            if (!Arrays.equals(rows, referenceRows) || referenceFetched != fetched) {
                referenceRows = rows;
                referenceFetched = fetched;
                referencesPanel.scheduleRefresh();
            }
        }
    }

    @Nullable
    private Object getAggregateValue(int row, int column) {
        Object value = getCellValue(row, column);
        if (value instanceof ResultSetCellContent) {
            return getDisplayValue(row, column);
        }
        return value;
    }

    /**
     * Applies the text of the value panel to the selected cell.
     *
     * @return null if it was set, otherwise why not
     */
    @Nullable
    private String applyValueText(@NotNull String text) {
        if (grid == null) {
            return EditorMessages.editor_viewer_no_grid;
        }
        JTable table = grid.getTable();
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int column = getSelectedModelColumn(table);
        if (row < 0 || column < 0) {
            return EditorMessages.editor_viewer_no_cell;
        }
        return grid.setCellText(row, column, text);
    }

    //////////////////////////////////////////////////////////
    // Status

    /**
     * Rows of the grid now (with inserted and deleted rows of an editable result).
     */
    int getRowCount() {
        ResultSetEditModel editModel = fetched.editModel();
        return editModel != null ? editModel.getRowCount() : fetched.data().rows().size();
    }

    private void updateStatus() {
        int rows = getRowCount();
        String text;
        if (reading) {
            text = EditorMessages.editor_common_reading;
        } else if (statusMessage != null) {
            text = statusMessage;
        } else {
            String rowsText = String.format("%,d", rows); //$NON-NLS-1$
            if (hasMore && totalRowCount != null) {
                // Counted on the server (Count Rows)
                text = NLS.bind(GridNavMessages.gridnav_rows_of_total, rowsText, String.format("%,d", totalRowCount)); //$NON-NLS-1$
            } else {
                text = NLS.bind(hasMore
                    ? rows == 1 ? EditorMessages.editor_viewer_rows_more_one : EditorMessages.editor_viewer_rows_more_many
                    : rows == 1 ? EditorMessages.editor_viewer_rows_one : EditorMessages.editor_viewer_rows_many, rowsText);
            }
            if (dataFilter.hasConditions()) {
                text = NLS.bind(EditorMessages.editor_viewer_rows_filtered, text);
            }
            String hidden = grid == null ? null : ResultSetColumnLayout.describeHidden(grid.getTable());
            if (hidden != null) {
                text = text + "  ·  " + hidden; //$NON-NLS-1$
            }
        }
        rowsLabel.setText(text);
        boolean canRead = hasMore && fetched.source().canRequery() && !reading;
        fetchNextButton.setVisible(canRead);
        fetchAllButton.setVisible(canRead);
        countButton.setVisible(canRead && totalRowCount == null
            && fetched.source().dataContainer().isFeatureSupported(DBSDataContainer.FEATURE_DATA_COUNT));
        countButton.setEnabled(!counting);
        countButton.setText(counting ? GridNavMessages.gridnav_counting : GridNavMessages.gridnav_count);
        // Back and forward appear with the first foreign key navigation
        boolean navigated = history.size() > 1;
        backButton.setVisible(navigated);
        forwardButton.setVisible(navigated);
        backAction.setEnabled(!reading && historyPosition > 0);
        forwardAction.setEnabled(!reading && historyPosition >= 0 && historyPosition < history.size() - 1);
        navigationLabel.setVisible(navigationTitle != null);
        navigationLabel.setText(navigationTitle);
        navigationLabel.setToolTipText(navigationTitle == null ? null : NLS.bind(GridNavMessages.gridnav_navigated_tip,
            DBUtils.getObjectFullName(fetched.source().dataContainer(), DBPEvaluationContext.UI)));
        fetchNextButton.setText(NLS.bind(EditorMessages.editor_viewer_fetch_next_count, String.format("%,d", fetched.source().segmentSize()))); //$NON-NLS-1$
        if (!fetched.source().canRequery() && hasMore) {
            rowsLabel.setToolTipText(NLS.bind(EditorMessages.editor_viewer_more_rows_blocked, fetched.source().requeryBlocker()));
        } else {
            rowsLabel.setToolTipText(null);
        }
        updateFilterSummary();
        updateSortSummary();
    }

    /**
     * "Sort: col1 ↑, col2 ↓ ✕" while the result is sorted. A sort read from the server replaces the query's own
     * ORDER BY (the model generator, {@link SQLSemanticProcessor#getReplaceableOrderBy}): then the summary says so, and
     * ✕ (no sort) reads the query in its own order again.
     */
    private void updateSortSummary() {
        List<DBDAttributeConstraint> orders = dataFilter.getOrderConstraints();
        sortSummary.setVisible(!orders.isEmpty());
        if (orders.isEmpty()) {
            return;
        }
        List<String> columns = new ArrayList<>();
        for (DBDAttributeConstraint order : orders) {
            String label = CommonUtils.isEmpty(order.getAttributeLabel()) ? order.getAttributeName() : order.getAttributeLabel();
            columns.add(label + (order.isOrderDescending() ? " ↓" : " ↑")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String columnsText = String.join(", ", columns); //$NON-NLS-1$
        String replaced = clientOrdered ? null : getReplaceableOrderBy();
        sortSummaryLabel.setIcon(ResultSetHeaderRenderer.createActiveSortIcon(orders.getFirst().isOrderDescending() ? -1 : 1));
        sortSummaryLabel.setText(NLS.bind(replaced != null
            ? GridFilterMessages.gridfilter_sort_summary_replaces : GridFilterMessages.gridfilter_sort_summary, columnsText));
        sortSummaryLabel.setToolTipText(replaced != null ? NLS.bind(GridFilterMessages.gridfilter_sort_replaces_tip, replaced)
            : clientOrdered ? GridFilterMessages.gridfilter_sort_grid_tip : GridFilterMessages.gridfilter_sort_server_tip);
        for (Component component : sortSummary.getComponents()) {
            if (component instanceof AbstractButton button) {
                button.setEnabled(!reading);
            }
        }
    }

    /**
     * The ORDER BY of the result's query that a server sort replaces, or null (the query is parsed already: the
     * re-read check parses it in the job thread).
     */
    @Nullable
    private String getReplaceableOrderBy() {
        if (fetched.source().dataContainer() instanceof SQLQueryContainer container && container.getQuery() instanceof SQLQuery query) {
            return SQLSemanticProcessor.getReplaceableOrderBy(query);
        }
        return null;
    }

    /**
     * "Filters: col1, col2 ✕" with the SQL condition as tool tip, only while there are filters.
     */
    private void updateFilterSummary() {
        List<String> names = new ArrayList<>();
        for (DBDAttributeBinding binding : fetched.data().bindings()) {
            DBDAttributeConstraint constraint = dataFilter.getConstraint(binding);
            if (constraint != null && constraint.hasCondition()) {
                names.add(binding.getLabel());
            }
        }
        filterSummary.setVisible(!names.isEmpty());
        filterSummaryLabel.setText(NLS.bind(GridFilterMessages.gridfilter_summary, String.join(", ", names))); //$NON-NLS-1$
        String condition = getConditionText(dataFilter);
        filterSummaryLabel.setToolTipText(condition.isEmpty() ? null : condition);
        for (Component component : filterSummary.getComponents()) {
            if (component instanceof AbstractButton button) {
                button.setEnabled(!reading);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Sorting

    @Nullable
    private DBDAttributeConstraint getConstraint(int column) {
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        return column >= 0 && column < bindings.length ? dataFilter.getConstraint(bindings[column]) : null;
    }

    /**
     * Toggles the sort of a column (SWT {@code ResultSetViewer.toggleSortOrder}).
     *
     * @param descending forced order, or null to cycle ascending → descending → none
     * @param add        keep the sort of the other columns
     */
    private void toggleSort(int column, @Nullable Boolean descending, boolean add) {
        if (reading || !checkNoChanges(EditorMessages.editor_viewer_action_sort)) {
            return;
        }
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        DBDAttributeBinding binding = fetched.data().bindings()[column];
        DBDAttributeConstraint constraint = filter.getConstraint(binding);
        if (constraint == null) {
            return;
        }
        if (!add) {
            for (DBDAttributeConstraint other : filter.getConstraints()) {
                if (other != constraint) {
                    other.setOrderPosition(0);
                    other.setOrderDescending(false);
                }
            }
            if (constraint.getOrderPosition() > 0) {
                constraint.setOrderPosition(1);
            }
        }
        if (descending != null) {
            if (constraint.getOrderPosition() == 0) {
                constraint.setOrderPosition(filter.getMaxOrderingPosition() + 1);
            }
            constraint.setOrderDescending(descending);
        } else if (constraint.getOrderPosition() == 0) {
            constraint.setOrderPosition(filter.getMaxOrderingPosition() + 1);
            constraint.setOrderDescending(false);
        } else if (!constraint.isOrderDescending()) {
            constraint.setOrderDescending(true);
        } else {
            for (DBDAttributeConstraint other : filter.getConstraints()) {
                if (other.getOrderPosition() > constraint.getOrderPosition()) {
                    other.setOrderPosition(other.getOrderPosition() - 1);
                }
            }
            constraint.setOrderPosition(0);
            constraint.setOrderDescending(false);
        }
        // Custom ORDER BY and column ordering don't mix (SWT)
        filter.setOrder(null);
        applyOrdering(filter);
    }

    private void clearSorting() {
        if (reading || !dataFilter.hasOrdering() || !checkNoChanges(EditorMessages.editor_viewer_action_sort)) {
            return;
        }
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        filter.resetOrderBy();
        applyOrdering(filter);
    }

    private void applyOrdering(@NotNull DBDDataFilter filter) {
        // A virtual column is calculated in Chromite: sorted in the grid, by the rows read
        if (isServerSideOrdering() && !ResultSetVirtualColumns.hasVirtualOrdering(filter)) {
            dataFilter = filter;
            requery(filter);
        } else {
            dataFilter = filter;
            clientOrdered = true;
            List<ViewerRow> rows = currentRows();
            sortRows(rows);
            rebuild(rows, fetched.source().unreadableColumns(), true);
        }
    }

    /**
     * Where to sort: the SWT ordering strategy, the database's support and whether the query can be read again.
     */
    private boolean isServerSideOrdering() {
        ResultSetSource source = fetched.source();
        DBCExecutionContext context = source.context();
        if (!source.canRequery() || context == null || !context.getDataSource().getInfo().supportsResultSetOrdering()) {
            return false;
        }
        DBPPreferenceStore store = context.getDataSource().getContainer().getPreferenceStore();
        return switch (OrderingStrategy.get(store)) {
            case CLIENT_SIDE -> false;
            case SERVER_SIDE -> true;
            case SMART -> hasMore;
        };
    }

    /**
     * Sorts in the fetch order and then by the ordered columns (SWT {@code ResultSetModel.resetOrderingImpl}).
     */
    private void sortRows(@NotNull List<ViewerRow> rows) {
        rows.sort(Comparator.comparingInt(ViewerRow::ordinal));
        List<DBDAttributeConstraint> orders = dataFilter.getOrderConstraints();
        if (orders.isEmpty()) {
            return;
        }
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        List<int[]> keys = new ArrayList<>();
        for (DBDAttributeConstraint order : orders) {
            for (int i = 0; i < bindings.length; i++) {
                if (bindings[i].matches(order.getAttribute(), false)) {
                    keys.add(new int[]{i, order.isOrderDescending() ? -1 : 1});
                    break;
                }
            }
        }
        rows.sort((row1, row2) -> {
            for (int[] key : keys) {
                int column = key[0];
                Object cell1 = getSortValue(row1, column);
                Object cell2 = getSortValue(row2, column);
                // A virtual column's value may not be of its type (an expression error is a text): compared as plain values
                Comparator<Object> comparator = bindings[column].isCustom() ? null : bindings[column].getValueHandler().getComparator();
                int result;
                if (comparator != null) {
                    result = comparator.compare(cell1, cell2);
                } else if (cell1 instanceof String text1 && cell2 instanceof String text2) {
                    result = text1.compareToIgnoreCase(text2);
                } else {
                    result = DBUtils.compareDataValues(cell1, cell2);
                }
                if (result != 0) {
                    return result * key[1];
                }
            }
            return 0;
        });
    }

    @Nullable
    private static Object getSortValue(@NotNull ViewerRow row, int column) {
        Object value = row.values() != null ? row.values()[column] : row.cells()[column];
        if (value == null || value instanceof ResultSetCellContent) {
            // NULL, or a value known by its display string only
            Object display = row.display()[column];
            return display == ResultSetGrid.NULL_VALUE || display == null ? null : display.toString();
        }
        return value;
    }

    //////////////////////////////////////////////////////////
    // Filters

    @NotNull
    private static DBDDataFilter createDataFilter(@NotNull DBDAttributeBinding[] bindings) {
        // As SWT ResultSetModel.createDataFilter (the grid has no nested attributes)
        List<DBDAttributeConstraint> constraints = new ArrayList<>(bindings.length);
        for (DBDAttributeBinding binding : bindings) {
            constraints.add(new DBDAttributeConstraint(binding, constraints.size(), constraints.size()));
        }
        return new DBDDataFilter(constraints);
    }

    /**
     * The filter for the columns of a new fetch, with the conditions and orders of the old one (by column name).
     */
    @NotNull
    private static DBDDataFilter rebindFilter(@NotNull DBDDataFilter old, @NotNull DBDAttributeBinding[] bindings) {
        DBDDataFilter filter = createDataFilter(bindings);
        for (DBDAttributeConstraint constraint : filter.getConstraints()) {
            DBDAttributeConstraint previous = old.getConstraint(constraint.getAttributeName());
            if (previous != null) {
                constraint.setOrderPosition(previous.getOrderPosition());
                constraint.setOrderDescending(previous.isOrderDescending());
                if (previous.getOperator() != null) {
                    constraint.setOperator(previous.getOperator());
                    constraint.setReverseOperator(previous.isReverseOperator());
                    constraint.setValue(previous.getValue());
                } else if (previous.getCriteria() != null) {
                    constraint.setCriteria(previous.getCriteria());
                }
                // What the column filter popup set (its operator and texts)
                constraint.setOptions(previous.getOptions());
            }
        }
        filter.setOrder(old.getOrder());
        // A foreign key of several columns from several rows: (a = 1 AND b = 2) OR (…), not a IN (…) AND b IN (…)
        filter.setUseDisjunctiveNormalForm(old.isUseDisjunctiveNormalForm());
        return filter;
    }

    /**
     * The conditions of a filter as SQL, as the SWT filter panel shows them.
     */
    @NotNull
    private String getConditionText(@NotNull DBDDataFilter filter) {
        DBPDataSource dataSource = getDataSource();
        if (dataSource == null || !filter.hasConditions()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try {
            SQLUtils.appendConditionString(filter, dataSource, null, text, true);
        } catch (Exception e) {
            log.debug("Can't make the filter text", e);
            return "";
        }
        return text.toString();
    }

    /**
     * The condition of one column as SQL.
     */
    @NotNull
    private String getConditionText(@NotNull DBDAttributeConstraint constraint) {
        return getConditionText(new DBDDataFilter(List.of(new DBDAttributeConstraint(constraint))));
    }

    /**
     * Removes the conditions of all columns (the sort stays) and reads the result again.
     */
    private void clearAllFilters() {
        if (!dataFilter.hasConditions()) {
            return;
        }
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        for (DBDAttributeConstraint constraint : filter.getConstraints()) {
            ResultSetColumnFilterPopup.clearCondition(constraint);
        }
        applyFilter(filter);
    }

    private void filterByValue(@NotNull DBDAttributeBinding binding, @NotNull DBCLogicalOperator operator, @Nullable Object value) {
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        DBDAttributeConstraint constraint = filter.getConstraint(binding);
        if (constraint == null) {
            return;
        }
        // The popup's operator and texts no longer describe the column's condition
        ResultSetColumnFilterPopup.clearCondition(constraint);
        constraint.setOperator(operator);
        constraint.setValue(value);
        applyFilter(filter);
    }

    private void applyFilter(@NotNull DBDDataFilter filter) {
        if (reading || !fetched.source().canRequery()) {
            return;
        }
        if (!checkNoChanges(EditorMessages.editor_viewer_action_filter)) {
            return;
        }
        requery(filter);
    }

    //////////////////////////////////////////////////////////
    // Reading again

    /**
     * Whether the grid has no unsaved changes; otherwise asks to discard them (SWT {@code checkForChanges}).
     */
    private boolean checkNoChanges(@NotNull String what) {
        if (grid == null) {
            return false;
        }
        if (grid.isSaving()) {
            statusMessage = NLS.bind(EditorMessages.editor_viewer_wait_saved, what);
            updateStatus();
            return false;
        }
        int changes = grid.getChangeCount();
        if (changes == 0) {
            return true;
        }
        return DBWorkbench.getPlatformUI().confirmAction(EditorMessages.editor_viewer_unsaved_title,
            NLS.bind(changes == 1 ? EditorMessages.editor_viewer_unsaved_one : EditorMessages.editor_viewer_unsaved_many, changes, what), true);
    }

    /**
     * Reads the query again with a filter from its first row (sorting on the server, filters).
     */
    private void requery(@NotNull DBDDataFilter filter) {
        ResultSetSource source = fetched.source();
        DBCExecutionContext context = source.context();
        if (context == null) {
            return;
        }
        // A sort by a virtual column was done in the grid; the database has no such column, and the new rows come
        // in the server's order
        DBDDataFilter readFilter = ResultSetVirtualColumns.withoutVirtualColumns(filter);
        DBDDataFilter viewFilter = new DBDDataFilter(readFilter);
        long segmentSize = source.segmentSize();
        startRead("Read results", context, (monitor, done) -> {
            ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
            ResultSetGridReceiver receiver = new ResultSetGridReceiver(source.dataContainer(), fetchedResult -> result[0] = fetchedResult);
            readData(monitor, context, source.dataContainer(), receiver, readFilter, 0, segmentSize > 0 ? segmentSize : -1, this);
            done.accept(() -> {
                if (result[0] != null) {
                    applyRequery(result[0], viewFilter);
                }
            });
        });
    }

    private void applyRequery(@NotNull ResultSetGrid.Fetched requeried, @NotNull DBDDataFilter filter) {
        // The re-read keeps the query of the first result, so "Export Data…" stays available (with this filter, serverFilter)
        DBSDataContainer exportQuery = fetched.exportQuery();
        ResultSetGrid.Fetched newFetched = exportQuery == null ? requeried : requeried.withExportQuery(exportQuery);
        releaseContents(fetched.source().cellValues());
        fetched = newFetched;
        ordinals = sequence(newFetched.data().rows().size());
        hasMore = newFetched.data().truncated() && newFetched.source().segmentSize() > 0;
        dataFilter = rebindFilter(filter, newFetched.data().bindings());
        serverFilter = new DBDDataFilter(dataFilter);
        clientOrdered = false;
        // Other conditions: the counted total no longer applies
        totalRowCount = null;
        installGrid(new ResultSetGrid(newFetched, gridHost), false);
        host.editStateChanged();
        host.rowsChanged();
        rememberFilter();
    }

    private void verticalScrolled(@NotNull AdjustmentEvent e) {
        JScrollBar bar = (JScrollBar) e.getAdjustable();
        int value = bar.getValue();
        boolean scrolledDown = value > lastScrollValue;
        lastScrollValue = value;
        if (!scrolledDown || bar.getMaximum() <= bar.getVisibleAmount() || value + bar.getVisibleAmount() < bar.getMaximum() - 2) {
            return;
        }
        DBCExecutionContext context = fetched.source().context();
        if (context != null && !isAutoFetchNextSegment(context.getDataSource().getContainer().getPreferenceStore())) {
            return;
        }
        SwingUtilities.invokeLater(() -> readNextSegment(true));
    }

    /**
     * Whether scrolling to the end reads the next rows. The default (on) is registered in the global settings, so that a
     * connection without a value of its own follows Tools &gt; Options; checking only the connection's own value made
     * the global setting ineffective.
     */
    static boolean isAutoFetchNextSegment(@NotNull DBPPreferenceStore store) {
        DBWorkbench.getPlatform().getPreferenceStore().setDefault(PREF_AUTO_FETCH_NEXT_SEGMENT, true);
        return store.getBoolean(PREF_AUTO_FETCH_NEXT_SEGMENT);
    }

    /**
     * Reads the next rows (SWT {@code readNextSegment}).
     *
     * @param automatic started by scrolling to the end: skipped if there are unsaved changes instead of asking
     */
    private void readNextSegment(boolean automatic) {
        if (!hasMore || reading || !fetched.source().canRequery() || grid == null) {
            return;
        }
        if (automatic && (grid.isDirty() || grid.isSaving())) {
            statusMessage = EditorMessages.editor_viewer_save_to_fetch;
            updateStatus();
            return;
        }
        if (!automatic && !checkSavedForAppend()) {
            return;
        }
        readMore(fetched.source().segmentSize());
    }

    /**
     * Reads all remaining rows after a confirmation (SWT {@code readAllData}).
     */
    private void readAllData() {
        if (!hasMore || reading || !fetched.source().canRequery()) {
            return;
        }
        if (!DBWorkbench.getPlatformUI().confirmAction(EditorMessages.editor_viewer_fetch_all_title,
            EditorMessages.editor_viewer_fetch_all_confirm)) {
            return;
        }
        if (!checkSavedForAppend()) {
            return;
        }
        readMore(-1);
    }

    /**
     * Next rows are added to the rows as they are now: pending changes must be saved or canceled first.
     */
    private boolean checkSavedForAppend() {
        if (grid == null || grid.isSaving()) {
            return false;
        }
        if (grid.isDirty()) {
            DBWorkbench.getPlatformUI().showMessageBox(EditorMessages.editor_viewer_fetch_more_title,
                EditorMessages.editor_viewer_fetch_more_pending, false);
            return false;
        }
        return true;
    }

    private void readMore(long maxRows) {
        ResultSetSource source = fetched.source();
        DBCExecutionContext context = source.context();
        if (context == null) {
            return;
        }
        // Without a sort of virtual columns: the rows are read in the server's order and sorted in the grid again
        DBDDataFilter readFilter = ResultSetVirtualColumns.withoutVirtualColumns(dataFilter);
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        int offset = fetched.editModel() != null ? fetched.editModel().getRowCount() : fetched.data().rows().size();
        startRead("Read next rows", context, (monitor, done) -> {
            ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
            ResultSetGridReceiver receiver = ResultSetGridReceiver.forNextSegment(source.dataContainer(), bindings,
                fetchedResult -> result[0] = fetchedResult);
            readData(monitor, context, source.dataContainer(), receiver, readFilter, offset, maxRows, this);
            done.accept(() -> {
                if (result[0] != null) {
                    applySegment(result[0]);
                }
            });
        });
    }

    private void applySegment(@NotNull ResultSetGrid.Fetched segment) {
        if (grid == null || grid.isDirty() || grid.isSaving()) {
            // Changed while reading: the new rows would not match the edit model
            releaseContents(segment.source().cellValues());
            statusMessage = EditorMessages.editor_viewer_save_to_fetch;
            updateStatus();
            return;
        }
        List<ViewerRow> rows = currentRows();
        int nextOrdinal = 0;
        for (ViewerRow row : rows) {
            nextOrdinal = Math.max(nextOrdinal, row.ordinal() + 1);
        }
        boolean editable = fetched.editModel() != null;
        ResultSetGrid.Data data = segment.data();
        List<Object[]> cells = segment.source().cellValues();
        for (int i = 0; i < data.rows().size(); i++) {
            Object[] rowCells = cells.get(i);
            Object[] values = null;
            if (editable) {
                values = new Object[rowCells.length];
                for (int c = 0; c < rowCells.length; c++) {
                    values[c] = rowCells[c] instanceof ResultSetCellContent ? null : rowCells[c];
                }
            }
            rows.add(new ViewerRow(nextOrdinal++, data.rows().get(i), data.values().get(i), rowCells, values));
        }
        boolean[] unreadable = fetched.source().unreadableColumns().clone();
        boolean[] segmentUnreadable = segment.source().unreadableColumns();
        for (int i = 0; i < unreadable.length && i < segmentUnreadable.length; i++) {
            unreadable[i] |= segmentUnreadable[i];
        }
        hasMore = data.truncated() && fetched.source().segmentSize() > 0;
        if (clientOrdered) {
            sortRows(rows);
        }
        rebuild(rows, unreadable, true);
    }

    /**
     * The rows of the current grid (no pending changes: they were saved or canceled).
     */
    @NotNull
    private List<ViewerRow> currentRows() {
        List<ViewerRow> rows = new ArrayList<>();
        List<Object[]> cells = fetched.source().cellValues();
        ResultSetEditModel editModel = fetched.editModel();
        if (editModel == null) {
            ResultSetGrid.Data data = fetched.data();
            for (int i = 0; i < data.rows().size(); i++) {
                rows.add(new ViewerRow(ordinals[i], data.rows().get(i), data.values().get(i),
                    i < cells.size() ? cells.get(i) : new Object[data.columns().length], null));
            }
            return rows;
        }
        for (int i = 0; i < editModel.getRowCount(); i++) {
            ResultSetEditModel.Row row = editModel.getRow(i);
            int number = row.getRowNumber();
            Object[] fetchedCells = number < cells.size() ? cells.get(number) : null;
            Object[] rowCells = row.values.clone();
            Object[] values = row.values.clone();
            for (int c = 0; c < rowCells.length; c++) {
                if (!editModel.hasValue(i, c)) {
                    // Value objects the grid did not read: the LOB content or geometry kept for the panel and the spatial view
                    Object fetchedCell = fetchedCells != null ? fetchedCells[c] : null;
                    rowCells[c] = fetchedCell instanceof ResultSetCellContent || fetchedCell instanceof DBGeometry ? fetchedCell : null;
                } else if (editModel.isContentColumn(c) && row.values[c] instanceof DBDContent content) {
                    // A LOB value set and saved in the grid: the new grid's panel content (its edit model reads LOB
                    // columns as not read)
                    rowCells[c] = DBUtils.isNullValue(content) ? null : ResultSetCellContent.capture(content);
                    values[c] = null;
                }
            }
            int ordinal = number < ordinals.length ? ordinals[number] : ordinals.length + number;
            rows.add(new ViewerRow(ordinal, row.display.clone(), ResultSetGridReceiver.keepValues(row.values), rowCells, values));
        }
        return rows;
    }

    /**
     * Makes a new grid of the given rows (client-side sorting, next rows).
     */
    private void rebuild(@NotNull List<ViewerRow> rows, @NotNull boolean[] unreadable, boolean keepView) {
        ResultSetGrid.Data old = fetched.data();
        ResultSetSource source = fetched.source();
        List<Object[]> display = new ArrayList<>(rows.size());
        List<Object[]> kept = new ArrayList<>(rows.size());
        List<Object[]> cells = new ArrayList<>(rows.size());
        List<Object[]> values = new ArrayList<>(rows.size());
        int[] newOrdinals = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            ViewerRow row = rows.get(i);
            display.add(row.display());
            kept.add(row.kept());
            cells.add(row.cells());
            values.add(row.values() != null ? row.values() : new Object[old.columns().length]);
            newOrdinals[i] = row.ordinal();
        }
        ResultSetGrid.Data data = new ResultSetGrid.Data(old.columns(), old.numeric(), List.copyOf(display),
            Collections.unmodifiableList(kept), old.bindings(), hasMore);
        ResultSetEditModel oldModel = fetched.editModel();
        ResultSetEditModel editModel = oldModel == null ? null
            : new ResultSetEditModel(oldModel.getExecutionContext(), oldModel.getDataContainer(), old.bindings(), unreadable,
                oldModel.getLobColumns(), values, display, oldModel.getEnumValues());
        ResultSetSource newSource = new ResultSetSource(source.dataContainer(), source.context(), source.segmentSize(),
            source.requeryBlocker(), unreadable, List.copyOf(cells));
        fetched = new ResultSetGrid.Fetched(data, editModel, fetched.readOnlyReason(), newSource, fetched.exportQuery());
        ordinals = newOrdinals;
        installGrid(new ResultSetGrid(fetched, gridHost), keepView);
        host.editStateChanged();
        // More rows, or the same rows in another order (sorted in the grid)
        host.rowsChanged();
    }

    /**
     * A read job body: reads in the job thread, then hands the EDT work to {@code done}.
     */
    private interface ReadTask {
        void run(@NotNull DBRProgressMonitor monitor, @NotNull Consumer<Runnable> done) throws Exception;
    }

    private void startRead(@NotNull String name, @NotNull DBCExecutionContext context, @NotNull ReadTask task) {
        reading = true;
        statusMessage = null;
        updateStatus();
        repaintHeader();
        new DataSourceJob(name, context) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Runnable[] edtWork = new Runnable[1];
                Throwable error = null;
                try {
                    task.run(monitor, work -> edtWork[0] = work);
                } catch (Throwable e) {
                    error = e;
                }
                Throwable readError = error;
                SwingUtilities.invokeLater(() -> finishRead(edtWork[0], readError));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void finishRead(@Nullable Runnable work, @Nullable Throwable error) {
        reading = false;
        if (!isDisplayable()) {
            return;
        }
        repaintHeader();
        if (error != null) {
            String message = ErrorText.describe(error);
            statusMessage = NLS.bind(EditorMessages.editor_viewer_read_failed, CommonUtils.truncateString(message.replace('\n', ' '), 200));
            host.appendError(NLS.bind(EditorMessages.editor_viewer_cant_read_again, message));
            log.debug("Result read failed", error);
            // The data filter stays what the grid shows: the failed filter was only a copy
            updateStatus();
            return;
        }
        if (work != null) {
            work.run();
        }
        updateStatus();
    }

    /**
     * The header's filter icons are off while reading.
     */
    private void repaintHeader() {
        if (grid != null) {
            grid.getTable().getTableHeader().repaint();
        }
    }

    /**
     * Reads a data container's rows into a receiver (job thread): the result's query again, or the table a foreign key
     * leads to (navigation, references panel).
     *
     * @param controller the execution source's controller (the viewer or panel that reads)
     */
    static void readData(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull DBSDataContainer dataContainer,
        @NotNull ResultSetGridReceiver receiver,
        @NotNull DBDDataFilter filter,
        long offset,
        long maxRows,
        @NotNull Object controller
    ) throws Exception {
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.USER, "Read result rows")) {
            // The query is the source descriptor, as in the engine's first run: binding the columns to the table
            // (editing, keys) reads the table from it, because drivers like SQL Server don't name the column tables
            Object descriptor = dataContainer instanceof SQLQueryContainer container ? container.getQuery() : null;
            AbstractExecutionSource source = new AbstractExecutionSource(dataContainer, context, controller, descriptor);
            // FLAG_REFRESH: query parameters keep their values instead of being asked again
            dataContainer.readData(source, session, receiver, filter.hasFilters() ? filter : null, offset, maxRows,
                DBSDataContainer.FLAG_REFRESH, 0);
        } finally {
            // Delivers what was read if fetchEnd was not reached
            receiver.close();
        }
    }

    //////////////////////////////////////////////////////////
    // Foreign key navigation (SWT navigateAssociation, navigateReference, navigateHistory)

    /**
     * The table of a single-table result, or null.
     */
    @Nullable
    private DBSEntity getSingleSource() {
        return DBExecUtils.detectSingleSourceTable(fetched.data().bindings());
    }

    /**
     * The tables whose columns the result shows: the relations are read for them.
     */
    @NotNull
    private List<DBSEntity> getLinkTables() {
        List<DBSEntity> tables = new ArrayList<>();
        for (DBDAttributeBinding binding : fetched.data().bindings()) {
            DBSEntityAttribute attribute = binding.getEntityAttribute();
            if (attribute != null && !tables.contains(attribute.getParentObject())) {
                tables.add(attribute.getParentObject());
            }
        }
        return tables;
    }

    /**
     * A new grid of other tables (navigation, another query): the relations read before don't apply.
     */
    private void checkLinkTables() {
        if (!Objects.equals(getLinkTables(), linkTables)) {
            links = null;
            linkTables = null;
            referencesPanel.resultChanged();
        }
    }

    /**
     * Gives the relations of the result's tables to the callback in the EDT, reading them first in a job (once per
     * result tables).
     */
    private void requestLinks(@NotNull Consumer<List<ResultSetReferences.Link>> callback) {
        if (links != null) {
            callback.accept(links);
            return;
        }
        linkCallbacks.add(callback);
        if (linkCallbacks.size() > 1) {
            // Being read
            return;
        }
        DBCExecutionContext context = fetched.source().context();
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        List<DBSEntity> tables = getLinkTables();
        if (context == null || tables.isEmpty()) {
            deliverLinks(List.of(), tables);
            return;
        }
        new DataSourceJob("Read table relations", context) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<ResultSetReferences.Link> result;
                try {
                    result = ResultSetReferences.readLinks(monitor, bindings);
                } catch (Throwable e) {
                    // As SWT: relations may be unsupported by the database; the menus show none
                    log.debug("Can't read the table relations", e);
                    result = List.of();
                }
                List<ResultSetReferences.Link> read = result;
                SwingUtilities.invokeLater(() -> deliverLinks(read, tables));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void deliverLinks(@NotNull List<ResultSetReferences.Link> read, @NotNull List<DBSEntity> tables) {
        if (tables.equals(getLinkTables())) {
            links = read;
            linkTables = tables;
        }
        List<Consumer<List<ResultSetReferences.Link>>> callbacks = new ArrayList<>(linkCallbacks);
        linkCallbacks.clear();
        callbacks.forEach(callback -> callback.accept(read));
    }

    /**
     * "table (key)" of a foreign key of a cell's column (no database access for real keys).
     */
    @NotNull
    private static String describeAssociation(@NotNull DBSEntityAssociation association) {
        DBSEntity target = association.getAssociatedEntity();
        return target == null ? association.getName() : ResultSetReferences.describe(target, association);
    }

    /**
     * The Navigate submenu: the referenced rows of the cell's foreign keys, the referenced and referencing tables,
     * back and forward, go to row or column, count rows.
     */
    private void fillNavigateMenu(@NotNull JMenu menu) {
        menu.removeAll();
        boolean canRead = !reading && fetched.source().context() != null && grid != null && grid.getTable().getSelectedRowCount() > 0;
        int[] cell = getSelectedCell();
        List<DBSEntityAssociation> associations = cell == null ? List.of()
            : ResultSetReferences.getCellAssociations(fetched.data().bindings()[cell[1]]);
        if (associations.isEmpty()) {
            JMenuItem none = new JMenuItem(GridNavMessages.gridnav_no_cell_link);
            none.setEnabled(false);
            menu.add(none);
        }
        for (int i = 0; i < associations.size(); i++) {
            DBSEntityAssociation association = associations.get(i);
            String label = describeAssociation(association);
            Action action = createAction(NLS.bind(GridNavMessages.gridnav_go_referenced, label),
                i == 0 ? UiKeys.command(KeyEvent.VK_B) : null,
                () -> navigate(association, false, label));
            action.setEnabled(canRead);
            menu.add(action);
        }
        menu.addSeparator();
        JMenu referenced = new JMenu(GridNavMessages.gridnav_referenced_tables);
        JMenu referencing = new JMenu(GridNavMessages.gridnav_referencing_tables);
        menu.add(referenced);
        menu.add(referencing);
        fillLinkMenus(referenced, referencing, canRead);
        menu.addSeparator();
        menu.add(createMenuAction(GridNavMessages.gridnav_back, backAction));
        menu.add(createMenuAction(GridNavMessages.gridnav_forward, forwardAction));
        menu.addSeparator();
        menu.add(createAction(GridNavMessages.gridnav_goto_row, UiKeys.command(KeyEvent.VK_G), this::goToRow));
        menu.add(createAction(GridNavMessages.gridnav_goto_column,
            UiKeys.command(KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK), this::goToColumn));
        Action count = createAction(GridNavMessages.gridnav_count, null, this::countRows);
        count.setEnabled(countButton.isVisible() && !counting);
        menu.add(count);
    }

    /**
     * A menu item for an action of the status line (its accelerator and enabled state) under a menu name.
     */
    @NotNull
    private static Action createMenuAction(@NotNull String name, @NotNull Action target) {
        Action action = createAction(name, (KeyStroke) target.getValue(Action.ACCELERATOR_KEY),
            () -> target.actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, name)));
        action.setEnabled(target.isEnabled());
        return action;
    }

    /**
     * Fills the referenced and referencing tables menus, after reading the relations if needed.
     */
    private void fillLinkMenus(@NotNull JMenu referenced, @NotNull JMenu referencing, boolean canRead) {
        if (links != null) {
            fillLinkMenus(referenced, referencing, canRead, links);
            return;
        }
        for (JMenu menu : new JMenu[]{referenced, referencing}) {
            JMenuItem reading = new JMenuItem(GridNavMessages.gridnav_reading_links);
            reading.setEnabled(false);
            menu.add(reading);
        }
        requestLinks(read -> fillLinkMenus(referenced, referencing, canRead, read));
    }

    private void fillLinkMenus(@NotNull JMenu referenced, @NotNull JMenu referencing, boolean canRead,
                               @NotNull List<ResultSetReferences.Link> read) {
        referenced.removeAll();
        referencing.removeAll();
        for (ResultSetReferences.Link link : read) {
            Action action = createAction(link.label(), null, () -> navigate(link.association(), link.referencing(), link.label()));
            action.setEnabled(canRead);
            (link.referencing() ? referencing : referenced).add(action);
        }
        for (JMenu menu : new JMenu[]{referenced, referencing}) {
            if (menu.getMenuComponentCount() == 0) {
                JMenuItem none = new JMenuItem(menu == referenced ? GridNavMessages.gridnav_no_referenced : GridNavMessages.gridnav_no_referencing);
                none.setEnabled(false);
                menu.add(none);
            }
            // Read while the submenu was open: it takes its new size
            if (menu.isPopupMenuVisible()) {
                menu.getPopupMenu().pack();
            }
        }
    }

    /**
     * Ctrl+B (DataGrip "Go to referenced data"; SWT navigateLink): the referenced rows of the cell's foreign key, a
     * choice if the column has several.
     */
    private void navigateCellLink() {
        int[] cell = getSelectedCell();
        if (cell == null || grid == null) {
            return;
        }
        List<DBSEntityAssociation> associations = ResultSetReferences.getCellAssociations(fetched.data().bindings()[cell[1]]);
        if (associations.isEmpty()) {
            statusMessage = GridNavMessages.gridnav_no_cell_link;
            updateStatus();
            return;
        }
        if (associations.size() == 1) {
            navigate(associations.getFirst(), false, describeAssociation(associations.getFirst()));
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        for (DBSEntityAssociation association : associations) {
            String label = describeAssociation(association);
            popup.add(createAction(NLS.bind(GridNavMessages.gridnav_go_referenced, label), null, () -> navigate(association, false, label)));
        }
        showAtSelectedCell(popup);
    }

    /**
     * Ctrl+Shift+1 (SWT referencesMenu): the referenced and referencing tables at the selected cell.
     */
    private void showReferencesMenu() {
        if (grid == null) {
            return;
        }
        JTable table = grid.getTable();
        requestLinks(read -> {
            if (grid == null || grid.getTable() != table || !table.isShowing()) {
                return;
            }
            JPopupMenu popup = new JPopupMenu();
            JMenu referenced = new JMenu(GridNavMessages.gridnav_referenced_tables);
            JMenu referencing = new JMenu(GridNavMessages.gridnav_referencing_tables);
            fillLinkMenus(referenced, referencing, !reading && table.getSelectedRowCount() > 0, read);
            popup.add(referenced);
            popup.add(referencing);
            showAtSelectedCell(popup);
        });
    }

    private void showAtSelectedCell(@NotNull JPopupMenu popup) {
        if (grid == null) {
            return;
        }
        JTable table = grid.getTable();
        int row = Math.max(table.getSelectionModel().getLeadSelectionIndex(), 0);
        int column = Math.max(table.getColumnModel().getSelectionModel().getLeadSelectionIndex(), 0);
        Rectangle cell = table.getCellRect(row, column, true);
        popup.show(table, cell.x, cell.y + cell.height);
    }

    /**
     * The selected rows (at most {@link ResultSetReferencesPanel#MAX_ROWS}) with the model that gives their key
     * values: the edit model of an editable result, the kept values otherwise. Null when no row is selected.
     */
    @Nullable
    private ResultSetReferencesPanel.SelectedRows getSelectedRowsForLinks() {
        if (grid == null) {
            return null;
        }
        JTable table = grid.getTable();
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return null;
        }
        if (rows.length > ResultSetReferencesPanel.MAX_ROWS) {
            rows = Arrays.copyOf(rows, ResultSetReferencesPanel.MAX_ROWS);
        }
        ResultSetEditModel editModel = fetched.editModel();
        if (editModel != null) {
            List<DBDValueRow> selected = new ArrayList<>();
            for (int row : rows) {
                if (row < editModel.getRowCount()) {
                    selected.add(editModel.getRow(row));
                }
            }
            return selected.isEmpty() ? null : new ResultSetReferencesPanel.SelectedRows(editModel, selected);
        }
        List<Object[]> cells = fetched.source().cellValues();
        List<Object[]> values = new ArrayList<>();
        for (int row : rows) {
            if (row < cells.size()) {
                Object[] rowValues = cells.get(row).clone();
                for (int i = 0; i < rowValues.length; i++) {
                    // LOB content and geometries are not key values
                    if (rowValues[i] instanceof ResultSetCellContent || rowValues[i] instanceof DBGeometry) {
                        rowValues[i] = null;
                    }
                }
                values.add(rowValues);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        DBDResultSetModel model = new ResultSetReferences.RowsModel(fetched.source().dataContainer(), fetched.data().bindings(), values);
        return new ResultSetReferencesPanel.SelectedRows(model, model.getAllRows());
    }

    /**
     * Reads the rows a foreign key leads to from the selected rows and shows them in this viewer; the result left is
     * kept for Back.
     *
     * @param referencing the other table references this result (its rows point to the selected rows)
     */
    private void navigate(@NotNull DBSEntityAssociation association, boolean referencing, @NotNull String label) {
        DBCExecutionContext context = fetched.source().context();
        if (reading || context == null) {
            return;
        }
        ResultSetReferencesPanel.SelectedRows selected = getSelectedRowsForLinks();
        if (selected == null) {
            statusMessage = GridNavMessages.gridnav_select_rows;
            updateStatus();
            return;
        }
        if (!checkNoChanges(GridNavMessages.gridnav_action_navigate)) {
            return;
        }
        String title = NLS.bind(referencing ? GridNavMessages.gridnav_title_referencing : GridNavMessages.gridnav_title_referenced, label);
        long segmentSize = fetched.source().segmentSize();
        startRead("Navigate foreign key", context, (monitor, done) -> {
            DBDReferenceNavigation navigation = ResultSetReferences.resolve(monitor, association, referencing, selected.model(), selected.rows());
            DBSDataContainer target = (DBSDataContainer) navigation.getTargetEntity();
            ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
            ResultSetGridReceiver receiver = new ResultSetGridReceiver(target, fetchedResult -> result[0] = fetchedResult);
            readData(monitor, context, target, receiver, navigation.getTargetFilter(), 0, segmentSize > 0 ? segmentSize : -1, this);
            done.accept(() -> {
                if (result[0] != null) {
                    showNavigated(result[0], navigation.getTargetFilter(), title);
                }
            });
        });
    }

    private void showNavigated(@NotNull ResultSetGrid.Fetched navigated, @NotNull DBDDataFilter filter, @NotNull String title) {
        // The result left stays as it is for Back; results gone back from are dropped (as browser history)
        while (history.size() > historyPosition + 1) {
            HistoryState dropped = history.removeLast();
            if (dropped != null) {
                releaseContents(dropped.fetched().source().cellValues());
            }
        }
        if (historyPosition < 0) {
            history.add(captureState());
        } else {
            history.set(historyPosition, captureState());
        }
        history.add(null);
        historyPosition = history.size() - 1;
        // The navigation's conditions become the column filters of the new result (shown in the status line, removable).
        // A table's rows have no query: "Export Data…" runs queries only, so it stays off here
        DBDDataFilter viewFilter = rebindFilter(filter, navigated.data().bindings());
        restoreState(new HistoryState(navigated, sequence(navigated.data().rows().size()), viewFilter, new DBDDataFilter(viewFilter),
            navigated.data().truncated() && navigated.source().segmentSize() > 0, false, title, null));
    }

    /**
     * The current result as a history entry. Its pending changes were confirmed to be discarded ({@link #checkNoChanges}),
     * so they are reverted: going back shows the rows as read or saved.
     */
    @NotNull
    private HistoryState captureState() {
        if (grid != null && grid.isDirty()) {
            grid.cancelChanges();
        }
        return new HistoryState(fetched, ordinals, dataFilter, serverFilter, hasMore, clientOrdered, navigationTitle, totalRowCount);
    }

    private void restoreState(@NotNull HistoryState state) {
        fetched = state.fetched();
        ordinals = state.ordinals();
        dataFilter = state.dataFilter();
        serverFilter = state.serverFilter();
        hasMore = state.hasMore();
        clientOrdered = state.clientOrdered();
        navigationTitle = state.title();
        totalRowCount = state.totalRowCount();
        statusMessage = null;
        installGrid(new ResultSetGrid(fetched, gridHost), false);
        host.editStateChanged();
        host.rowsChanged();
    }

    /**
     * Back and forward (Alt+←/→): shows a kept result again as it was (not read again).
     */
    private void navigateHistory(int position) {
        if (reading || position < 0 || position >= history.size() || position == historyPosition || history.get(position) == null) {
            return;
        }
        if (!checkNoChanges(GridNavMessages.gridnav_action_navigate)) {
            return;
        }
        HistoryState target = history.get(position);
        history.set(historyPosition, captureState());
        history.set(position, null);
        historyPosition = position;
        restoreState(target);
    }

    //////////////////////////////////////////////////////////
    // Panels beside the result, columns, find, zoom, count, virtual keys

    private boolean isSidePanelShown(@NotNull JComponent panel) {
        return sidePanels.indexOfComponent(panel) >= 0;
    }

    /**
     * Shows a panel as a tab beside the result, or removes it; the panel area is hidden without tabs.
     */
    private void toggleSidePanel(@NotNull JComponent panel, @NotNull String title) {
        if (isSidePanelShown(panel)) {
            sidePanels.remove(panel);
        } else {
            sidePanels.addTab(title, panel);
            sidePanels.setSelectedComponent(panel);
        }
        boolean shown = sidePanels.getTabCount() > 0;
        if (shown != sidePanels.isVisible()) {
            sidePanels.setVisible(shown);
            if (shown) {
                placePanelDivider();
            }
        }
        referencesToggle.setSelected(isSidePanelShown(referencesPanel));
        aggregatesToggle.setSelected(isSidePanelShown(aggregatePanel));
        panelSplit.revalidate();
        panelSplit.repaint();
        refreshSidePanel();
    }

    private void placePanelDivider() {
        if (panelSplit.getWidth() <= 0) {
            panelSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (panelSplit.getWidth() > 0) {
                        panelSplit.removeComponentListener(this);
                        placePanelDivider();
                    }
                }
            });
            return;
        }
        panelSplit.setDividerLocation(Math.max(200, panelSplit.getWidth() * 55 / 100));
    }

    private void refreshSidePanel() {
        Component panel = sidePanels.getSelectedComponent();
        if (panel == referencesPanel) {
            referenceRows = null;
            referencesPanel.scheduleRefresh();
        } else if (panel == aggregatePanel) {
            aggregatePanel.refresh();
        }
    }

    /**
     * Hide, show and hide empty columns, go to column, and zoom, for the header menu and the grid's Columns menu.
     *
     * @param viewColumn the clicked or selected column, or -1
     */
    private void addColumnItems(@NotNull JComponent menu, @NotNull JTable table, int viewColumn) {
        Action hide = createAction(GridNavMessages.gridnav_hide_column, null, () -> {
            // The selected columns when the clicked one is among them
            int[] columns = viewColumn >= 0 && !table.isColumnSelected(viewColumn) ? new int[]{viewColumn} : table.getSelectedColumns();
            ResultSetColumnLayout.hideColumns(table, columns);
            columnsChanged();
        });
        hide.setEnabled(viewColumn >= 0 && table.getColumnCount() > 1);
        int hidden = ResultSetColumnLayout.getHiddenCount(table);
        Action showAll = createAction(hidden > 0 ? NLS.bind(GridNavMessages.gridnav_show_all_columns_count, hidden)
            : GridNavMessages.gridnav_show_all_columns, null, () -> {
            ResultSetColumnLayout.showAllColumns(table);
            ResultSetColumnFit.fitAllColumns(table);
            columnsChanged();
        });
        showAll.setEnabled(hidden > 0);
        Action hideEmpty = createAction(GridNavMessages.gridnav_hide_empty_columns, null, () -> {
            int count = ResultSetColumnLayout.hideEmptyColumns(table);
            statusMessage = count == 0 ? GridNavMessages.gridnav_no_empty_columns : null;
            columnsChanged();
        });
        menu.add(new JMenuItem(hide));
        menu.add(new JMenuItem(showAll));
        menu.add(new JMenuItem(hideEmpty));
        menu.add(new JMenuItem(createAction(GridNavMessages.gridnav_goto_column,
            UiKeys.command(KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK), this::goToColumn)));
        menu.add(new JSeparator());
        Action zoomIn = createAction(GridNavMessages.gridnav_zoom_in, KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.ALT_DOWN_MASK), () -> setZoom(zoom + 1));
        zoomIn.setEnabled(zoom < ResultSetColumnLayout.MAX_ZOOM);
        Action zoomOut = createAction(GridNavMessages.gridnav_zoom_out, KeyStroke.getKeyStroke(KeyEvent.VK_9, InputEvent.ALT_DOWN_MASK), () -> setZoom(zoom - 1));
        zoomOut.setEnabled(zoom > ResultSetColumnLayout.MIN_ZOOM);
        Action zoomReset = createAction(GridNavMessages.gridnav_zoom_reset, null, () -> setZoom(0));
        zoomReset.setEnabled(zoom != 0);
        menu.add(new JMenuItem(zoomIn));
        menu.add(new JMenuItem(zoomOut));
        menu.add(new JMenuItem(zoomReset));
    }

    /**
     * The grid's columns were hidden, shown or moved.
     */
    private void columnsChanged() {
        updateStatus();
        if (viewMode == ViewMode.TEXT) {
            textView.refresh();
        }
        scheduleSelectionUpdate();
    }

    private void setZoom(int step) {
        int newZoom = Math.clamp(step, ResultSetColumnLayout.MIN_ZOOM, ResultSetColumnLayout.MAX_ZOOM);
        if (newZoom == zoom || grid == null) {
            return;
        }
        zoom = newZoom;
        ResultSetColumnLayout.applyZoom(grid.getTable(), gridFont, gridRowHeight, zoom);
    }

    /**
     * Go to row and column work in the grid: another view shows the grid first.
     */
    private void showGrid() {
        if (viewMode != ViewMode.GRID) {
            setViewMode(ViewMode.GRID);
        }
    }

    private void goToRow() {
        if (grid != null) {
            showGrid();
            ResultSetColumnLayout.goToRow(this, grid.getTable());
        }
    }

    private void goToColumn() {
        if (grid != null) {
            showGrid();
            ResultSetColumnLayout.goToColumn(this, grid.getTable());
            columnsChanged();
        }
    }

    private void openFindBar() {
        showGrid();
        findBar.open();
    }

    /**
     * Count Rows (SWT {@code resultset.count}): the rows of the result's query or table with the filters the rows were
     * read with, counted on the server ({@code countData}, SELECT COUNT).
     */
    private void countRows() {
        ResultSetSource source = fetched.source();
        DBCExecutionContext context = source.context();
        if (counting || context == null || !source.canRequery()) {
            return;
        }
        counting = true;
        updateStatus();
        DBDDataFilter countedFilter = serverFilter;
        DBDDataFilter filter = new DBDDataFilter(serverFilter);
        DBSDataContainer container = source.dataContainer();
        new DataSourceJob("Count rows", context) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                long count = -1;
                Throwable error = null;
                try {
                    count = DBUtils.readRowCount(monitor, context, container, filter.hasConditions() ? filter : null, ResultSetViewerPanel.this);
                } catch (Throwable e) {
                    error = e;
                }
                long counted = count;
                Throwable countError = error;
                SwingUtilities.invokeLater(() -> {
                    counting = false;
                    if (countError != null || counted < 0) {
                        // A container may not know its count (-1)
                        statusMessage = GridNavMessages.gridnav_count_failed;
                        if (countError != null) {
                            host.appendError(NLS.bind(GridNavMessages.gridnav_count_failed_detail, ErrorText.describe(countError)));
                            log.debug("Row count failed", countError);
                        }
                    } else if (serverFilter == countedFilter) {
                        // The same rows as when the count started (no new filter or navigation since)
                        totalRowCount = counted;
                    }
                    updateStatus();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * The Virtual Keys submenu: the virtual unique key and virtual foreign keys of the result's table.
     */
    private void fillVirtualMenu(@NotNull JMenu menu) {
        menu.removeAll();
        DBSEntity entity = getSingleSource();
        if (entity == null) {
            JMenuItem none = new JMenuItem(GridNavMessages.gridnav_vkey_no_table);
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        Action key = createAction(GridNavMessages.gridnav_vkey_define, null, () -> editVirtualKey(entity));
        key.setEnabled(!reading && ResultSetVirtualKeys.usesVirtualKey(entity, fetched.data().bindings()));
        JMenuItem keyItem = new JMenuItem(key);
        if (!key.isEnabled()) {
            keyItem.setToolTipText(GridNavMessages.gridnav_vkey_has_key);
        }
        menu.add(keyItem);
        Action foreignKey = createAction(GridNavMessages.gridnav_vfk_add, null, () -> addVirtualForeignKey(entity));
        foreignKey.setEnabled(!reading);
        menu.add(foreignKey);
        List<DBVEntityForeignKey> foreignKeys = ResultSetVirtualKeys.getForeignKeys(entity);
        if (!foreignKeys.isEmpty()) {
            JMenu remove = new JMenu(GridNavMessages.gridnav_vfk_remove);
            for (DBVEntityForeignKey existing : foreignKeys) {
                remove.add(createAction(ResultSetVirtualKeys.describe(existing), null, () -> {
                    if (ResultSetVirtualKeys.removeForeignKey(this, entity, existing)) {
                        readAgainForVirtualModel(GridNavMessages.gridnav_vkey_run_again);
                    }
                }));
            }
            remove.setEnabled(!reading);
            menu.add(remove);
        }
    }

    private void editVirtualKey(@NotNull DBSEntity entity) {
        if (!reading && ResultSetVirtualKeys.editUniqueKey(this, entity, fetched.data().bindings())) {
            readAgainForVirtualModel(GridNavMessages.gridnav_vkey_run_again);
        }
    }

    private void addVirtualForeignKey(@NotNull DBSEntity entity) {
        if (!reading && ResultSetVirtualKeys.addForeignKey(this, entity, fetched.data().bindings())) {
            readAgainForVirtualModel(GridNavMessages.gridnav_vkey_run_again);
        }
    }

    /**
     * A virtual key or column changed: the columns are bound to the keys and the virtual columns are added when the
     * result is read, so it is read again (its pending changes are discarded after asking). A result that can't be
     * read again tells to run the query again.
     *
     * @param runAgain the status message for a result that can't be read again
     */
    private void readAgainForVirtualModel(@NotNull String runAgain) {
        links = null;
        linkTables = null;
        referencesPanel.resultChanged();
        if (!fetched.source().canRequery() || reading || !checkNoChanges(GridNavMessages.gridnav_action_read_again)) {
            statusMessage = runAgain;
            updateStatus();
            return;
        }
        requery(new DBDDataFilter(dataFilter));
    }

    //////////////////////////////////////////////////////////
    // Saved filters and recent filters (ResultSetSavedFilters)

    /**
     * The Saved Filters submenu: save the current filter, the table's saved filters, its recent filters and deleting.
     */
    private void fillSavedFiltersMenu(@NotNull JMenu menu) {
        menu.removeAll();
        DBSEntity entity = getSingleSource();
        if (entity == null) {
            JMenuItem none = new JMenuItem(GridNavMessages.gridnav_vkey_no_table);
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        boolean canApply = fetched.source().canRequery() && !reading;
        String applyBlocked = fetched.source().canRequery() ? null : NLS.bind(EditorMessages.editor_viewer_filters_need, fetched.source().requeryBlocker());
        Action save = createAction(GridVirtualMessages.gridvirt_filters_save, null, () -> saveCurrentFilter(entity));
        save.setEnabled(!reading && ResultSetSavedFilters.hasSavable(dataFilter));
        JMenuItem saveItem = new JMenuItem(save);
        if (!ResultSetSavedFilters.hasSavable(dataFilter)) {
            saveItem.setToolTipText(GridVirtualMessages.gridvirt_filters_nothing);
        }
        menu.add(saveItem);
        List<ResultSetSavedFilters.Entry> saved = ResultSetSavedFilters.getSaved(entity);
        if (!saved.isEmpty()) {
            menu.addSeparator();
            for (ResultSetSavedFilters.Entry entry : saved) {
                Action apply = createAction(entry.name(), null, () -> applySavedFilter(entry, true));
                apply.setEnabled(canApply);
                JMenuItem item = new JMenuItem(apply);
                item.setToolTipText(applyBlocked != null ? applyBlocked : entry.text());
                menu.add(item);
            }
        }
        menu.addSeparator();
        JMenu recentMenu = new JMenu(GridVirtualMessages.gridvirt_filters_history);
        List<ResultSetSavedFilters.Entry> recent = ResultSetSavedFilters.getRecent(entity);
        if (recent.isEmpty()) {
            JMenuItem empty = new JMenuItem(GridVirtualMessages.gridvirt_filters_history_empty);
            empty.setEnabled(false);
            recentMenu.add(empty);
        } else {
            for (ResultSetSavedFilters.Entry entry : recent) {
                Action apply = createAction(ResultSetSavedFilters.shorten(entry.text(), 80), null, () -> applySavedFilter(entry, false));
                apply.setEnabled(canApply);
                JMenuItem item = new JMenuItem(apply);
                item.setToolTipText(applyBlocked != null ? applyBlocked : entry.text());
                recentMenu.add(item);
            }
            recentMenu.addSeparator();
            recentMenu.add(createAction(GridVirtualMessages.gridvirt_filters_history_clear, null,
                () -> ResultSetSavedFilters.clearRecent(this, entity)));
        }
        menu.add(recentMenu);
        if (!saved.isEmpty()) {
            JMenu deleteMenu = new JMenu(GridVirtualMessages.gridvirt_filters_delete);
            for (ResultSetSavedFilters.Entry entry : saved) {
                deleteMenu.add(createAction(entry.name(), null, () -> ResultSetSavedFilters.delete(this, entity, entry)));
            }
            menu.add(deleteMenu);
        }
    }

    private void saveCurrentFilter(@NotNull DBSEntity entity) {
        if (reading) {
            return;
        }
        String name = ResultSetSavedFilters.save(this, entity, dataFilter, getFilterText(dataFilter));
        if (name != null) {
            statusMessage = NLS.bind(GridVirtualMessages.gridvirt_filters_saved, name);
            updateStatus();
        }
    }

    /**
     * Applies a saved filter (its conditions and sort) or a recent filter (its conditions; the sort stays) and reads
     * the result again. Conditions of columns the result does not have are left out and named in the messages.
     */
    private void applySavedFilter(@NotNull ResultSetSavedFilters.Entry entry, boolean withOrder) {
        DBCExecutionContext context = fetched.source().context();
        if (reading || context == null || !fetched.source().canRequery()) {
            return;
        }
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        DBDDataFilter target = ResultSetVirtualColumns.withoutVirtualColumns(dataFilter);
        for (DBDAttributeConstraint constraint : target.getConstraints()) {
            ResultSetColumnFilterPopup.clearCondition(constraint);
            if (withOrder) {
                constraint.setOrderPosition(0);
                constraint.setOrderDescending(false);
            }
        }
        ResultSetSavedFilters.Restored restored = ResultSetSavedFilters.restore(entry, target, bindings, context, withOrder);
        if (!restored.missingColumns().isEmpty()) {
            host.appendMessage(NLS.bind(GridVirtualMessages.gridvirt_filters_skipped,
                entry.name().isEmpty() ? ResultSetSavedFilters.shorten(entry.text(), 80) : entry.name(), String.join(", ", restored.missingColumns()))); //$NON-NLS-1$
        }
        applyFilter(restored.filter());
    }

    /**
     * The conditions and the sort of a filter as SQL, for the saved filter menu.
     */
    @NotNull
    private String getFilterText(@NotNull DBDDataFilter filter) {
        String conditions = getConditionText(filter);
        List<String> orders = new ArrayList<>();
        for (DBDAttributeConstraint order : filter.getOrderConstraints()) {
            if (!ResultSetVirtualColumns.isVirtual(order)) {
                orders.add(order.getAttributeName() + (order.isOrderDescending() ? " DESC" : "")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String orderText = orders.isEmpty() ? "" : NLS.bind(GridVirtualMessages.gridvirt_filters_order, String.join(", ", orders)); //$NON-NLS-1$ //$NON-NLS-2$
        return conditions.isEmpty() ? orderText : orderText.isEmpty() ? conditions : conditions + "  " + orderText; //$NON-NLS-1$
    }

    /**
     * Keeps the conditions the result was just read with in the recent filters of its table.
     */
    private void rememberFilter() {
        DBSEntity entity = getSingleSource();
        if (entity != null && dataFilter.hasConditions()) {
            ResultSetSavedFilters.remember(entity, dataFilter, getConditionText(dataFilter));
        }
    }

    //////////////////////////////////////////////////////////
    // Virtual columns (ResultSetVirtualColumns)

    /**
     * The Virtual Columns submenu: add, edit and remove the calculated columns of the result's table.
     */
    private void fillVirtualColumnsMenu(@NotNull JMenu menu, @NotNull JTable table) {
        menu.removeAll();
        DBSEntity entity = getSingleSource();
        if (entity == null) {
            JMenuItem none = new JMenuItem(GridNavMessages.gridnav_vkey_no_table);
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        Action add = createAction(GridVirtualMessages.gridvirt_column_add, null, () -> editVirtualColumn(entity, null, table));
        add.setEnabled(!reading);
        menu.add(add);
        List<DBVEntityAttribute> columns = DBVUtils.getCustomAttributes(entity);
        if (!columns.isEmpty()) {
            JMenu edit = new JMenu(GridVirtualMessages.gridvirt_column_edit);
            JMenu remove = new JMenu(GridVirtualMessages.gridvirt_column_remove);
            for (DBVEntityAttribute column : columns) {
                String label = column.getName() + " = " + ResultSetSavedFilters.shorten(CommonUtils.notEmpty(column.getExpression()), 60); //$NON-NLS-1$
                edit.add(createAction(label, null, () -> editVirtualColumn(entity, column, table)));
                remove.add(createAction(label, null, () -> {
                    if (!reading && ResultSetVirtualColumns.remove(this, entity, column)) {
                        readAgainForVirtualModel(GridVirtualMessages.gridvirt_column_run_again);
                    }
                }));
            }
            edit.setEnabled(!reading);
            remove.setEnabled(!reading);
            menu.add(edit);
            menu.add(remove);
        }
    }

    private void editVirtualColumn(@NotNull DBSEntity entity, @Nullable DBVEntityAttribute column, @NotNull JTable table) {
        if (!reading && ResultSetVirtualColumns.edit(this, entity, fetched.data().bindings(), column, getPreviewRow(table))) {
            readAgainForVirtualModel(GridVirtualMessages.gridvirt_column_run_again);
        }
    }

    /**
     * The values of the selected row (else the first), by binding, for the expression preview; LOB contents are
     * not values the expression sees (NULL, as when the column is calculated).
     */
    @Nullable
    private Object[] getPreviewRow(@NotNull JTable table) {
        if (table.getRowCount() == 0) {
            return null;
        }
        int row = Math.max(table.getSelectionModel().getLeadSelectionIndex(), 0);
        if (row >= table.getRowCount()) {
            row = 0;
        }
        Object[] values = new Object[fetched.data().bindings().length];
        for (int column = 0; column < values.length; column++) {
            Object value = getCellValue(row, column);
            values[column] = value instanceof ResultSetCellContent ? null : value;
        }
        return values;
    }

    //////////////////////////////////////////////////////////
    // Helpers

    static void releaseContents(@NotNull List<Object[]> cells) {
        for (Object[] row : cells) {
            for (Object cell : row) {
                if (cell instanceof ResultSetCellContent content) {
                    content.release();
                }
            }
        }
    }

    @NotNull
    private static int[] sequence(int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    @NotNull
    static Action createAction(@NotNull String name, @Nullable KeyStroke accelerator, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, accelerator);
        return action;
    }

    /**
     * The chart view's access to the rows and the selection of the grid.
     */
    private final class ChartSource implements ResultSetChartView.Source {
        @NotNull
        @Override
        public DBDAttributeBinding[] getBindings() {
            return fetched.data().bindings();
        }

        @NotNull
        @Override
        public String[] getColumnNames() {
            return fetched.data().columns();
        }

        @NotNull
        @Override
        public boolean[] getNumericColumns() {
            return fetched.data().numeric();
        }

        @Override
        public int getRowCount() {
            return ResultSetViewerPanel.this.getRowCount();
        }

        @Nullable
        @Override
        public Object getValue(int row, int column) {
            return getCellValue(row, column);
        }

        @Nullable
        @Override
        public Object getDisplayValue(int row, int column) {
            return ResultSetViewerPanel.this.getDisplayValue(row, column);
        }

        @NotNull
        @Override
        public int[] getSelectedRows() {
            return grid == null ? new int[0] : grid.getTable().getSelectedRows();
        }

        @NotNull
        @Override
        public int[] getSelectedColumns() {
            if (grid == null) {
                return new int[0];
            }
            JTable table = grid.getTable();
            int[] columns = table.getSelectedColumns();
            for (int i = 0; i < columns.length; i++) {
                columns[i] = table.convertColumnIndexToModel(columns[i]);
            }
            return columns;
        }

        @Override
        public boolean hasMoreRows() {
            return hasMore;
        }
    }

    /**
     * The spatial view's access to the geometry values and the selected row of the grid.
     */
    private final class SpatialSource implements ResultSetSpatialView.Source {
        @NotNull
        @Override
        public DBDAttributeBinding[] getBindings() {
            return fetched.data().bindings();
        }

        @NotNull
        @Override
        public String[] getColumnNames() {
            return fetched.data().columns();
        }

        @Override
        public int getRowCount() {
            return ResultSetViewerPanel.this.getRowCount();
        }

        @Nullable
        @Override
        public Object getSpatialValue(int row, int column) {
            return getCellValue(row, column);
        }

        @Override
        public int getCurrentRow() {
            return grid == null ? -1 : grid.getTable().getSelectionModel().getLeadSelectionIndex();
        }

        @Override
        public void setCurrentRow(int row) {
            if (grid != null && row >= 0 && row < grid.getTable().getRowCount()) {
                JTable table = grid.getTable();
                table.changeSelection(row, Math.max(table.getSelectedColumn(), 0), false, false);
            }
        }
    }

    /**
     * The record view's access to the selected row of the grid.
     */
    private final class RecordSource implements ResultSetRecordView.Source {
        @Override
        public int getRowCount() {
            return grid == null ? 0 : grid.getTable().getRowCount();
        }

        @Override
        public int getCurrentRow() {
            return grid == null ? -1 : grid.getTable().getSelectionModel().getLeadSelectionIndex();
        }

        @Override
        public void setCurrentRow(int row) {
            if (grid != null && row >= 0 && row < grid.getTable().getRowCount()) {
                JTable table = grid.getTable();
                int column = Math.max(table.getSelectedColumn(), 0);
                table.changeSelection(row, column, false, false);
            }
        }

        @NotNull
        @Override
        public String[] getColumnNames() {
            return fetched.data().columns();
        }

        @NotNull
        @Override
        public DBDAttributeBinding[] getBindings() {
            return fetched.data().bindings();
        }

        @Nullable
        @Override
        public Object getDisplayValue(int row, int column) {
            return ResultSetViewerPanel.this.getDisplayValue(row, column);
        }

        @Override
        public boolean isEditable(int row, int column) {
            // LOB cells are edited in the value panel, as in the grid
            ResultSetEditModel editModel = fetched.editModel();
            return editModel != null && row < editModel.getRowCount() && editModel.isInlineEditable(row, column);
        }

        @NotNull
        @Override
        public String getEditText(int row, int column) {
            ResultSetEditModel editModel = fetched.editModel();
            return editModel == null ? "" : editModel.getEditText(row, column);
        }

        @Nullable
        @Override
        public String setText(int row, int column, @NotNull String text) {
            return grid == null ? EditorMessages.editor_viewer_no_grid : grid.setCellText(row, column, text);
        }

        @Override
        public boolean isChanged(int row, int column) {
            ResultSetEditModel editModel = fetched.editModel();
            return editModel != null && row < editModel.getRowCount() && editModel.getRow(row).isCellChanged(column);
        }

        @Override
        public void save() {
            if (grid != null) {
                grid.save(false);
            }
        }

        @Override
        public void selectionChanged() {
            scheduleSelectionUpdate();
        }
    }

    /**
     * The text view's access to the grid.
     */
    private final class TextSource implements ResultSetTextView.Source {
        @Nullable
        @Override
        public JTable getTable() {
            return grid == null ? null : grid.getTable();
        }

        @Override
        public boolean isNumeric(int column) {
            boolean[] numeric = fetched.data().numeric();
            return column >= 0 && column < numeric.length && numeric[column];
        }
    }

    /**
     * The aggregates panel's access to the grid's selection and values.
     */
    private final class AggregateSource implements ResultSetAggregatePanel.Source {
        @Nullable
        @Override
        public JTable getTable() {
            return grid == null ? null : grid.getTable();
        }

        @Nullable
        @Override
        public Object getValue(int row, int column) {
            return getAggregateValue(row, column);
        }

        @NotNull
        @Override
        public String getDisplayText(int row, int column) {
            Object display = getDisplayValue(row, column);
            return display == null ? "" : display.toString();
        }

        @NotNull
        @Override
        public String getColumnName(int column) {
            return fetched.data().columns()[column];
        }
    }

    /**
     * The references panel's access to the result: its relations, the selected rows and the viewer's navigation.
     */
    private final class ReferencesSource implements ResultSetReferencesPanel.Source {
        @Nullable
        @Override
        public DBCExecutionContext getContext() {
            return fetched.source().context();
        }

        @Override
        public void requestLinks(@NotNull Consumer<List<ResultSetReferences.Link>> callback) {
            ResultSetViewerPanel.this.requestLinks(callback);
        }

        @Nullable
        @Override
        public ResultSetReferencesPanel.SelectedRows getSelectedRows() {
            return getSelectedRowsForLinks();
        }

        @Override
        public long getSegmentSize() {
            return fetched.source().segmentSize();
        }

        @NotNull
        @Override
        public ResultSetGrid.Host getHost() {
            return host;
        }

        @Override
        public void open(@NotNull ResultSetReferences.Link link) {
            navigate(link.association(), link.referencing(), link.label());
        }
    }

    /**
     * Generate SQL's access to the result: its columns, the selected rows and their cell values (with pending changes).
     */
    private final class GenerateSqlSource implements ResultSetGenerateSql.Source {
        private final JTable table;

        GenerateSqlSource(@NotNull JTable table) {
            this.table = table;
        }

        @NotNull
        @Override
        public Component getOwner() {
            return table;
        }

        @NotNull
        @Override
        public DBDAttributeBinding[] getBindings() {
            return fetched.data().bindings();
        }

        @NotNull
        @Override
        public DBSDataContainer getDataContainer() {
            return fetched.source().dataContainer();
        }

        @Nullable
        @Override
        public DBCExecutionContext getContext() {
            return fetched.source().context();
        }

        @NotNull
        @Override
        public int[] getSelectedRows() {
            return table.getSelectedRows();
        }

        @Nullable
        @Override
        public Object getCellValue(int row, int column) {
            return ResultSetViewerPanel.this.getCellValue(row, column);
        }

        @NotNull
        @Override
        public ResultSetGrid.Host getHost() {
            return host;
        }
    }

    /**
     * Binds an action's accelerator in the viewer (while the focus is in it: grid, record view, panels).
     */
    private void bindViewerKey(@NotNull Action action) {
        String key = "chromite.viewer." + action.getValue(Action.NAME); //$NON-NLS-1$
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put((KeyStroke) action.getValue(Action.ACCELERATOR_KEY), key);
        getActionMap().put(key, action);
    }

    /**
     * A flow layout whose preferred height counts the lines it wraps to at the container's width (FlowLayout reports one
     * line, so the wrapped components fell outside the status bar). When the line count changes on a resize, the
     * container is validated again so the status bar takes the new height.
     */
    private static final class WrapFlowLayout extends FlowLayout {
        private int lastHeight = -1;

        WrapFlowLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }

        @Override
        public void layoutContainer(Container target) {
            super.layoutContainer(target);
            int height = preferredLayoutSize(target).height;
            if (height != lastHeight) {
                lastHeight = height;
                SwingUtilities.invokeLater(target::revalidate);
            }
        }

        @NotNull
        private Dimension layoutSize(@NotNull Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                // Before the first layout the width is 0: one line, as FlowLayout
                int width = target.getWidth();
                int maxWidth = width > 0 ? width - insets.left - insets.right - 2 * getHgap() : Integer.MAX_VALUE;
                int totalWidth = 0;
                int totalHeight = 0;
                int rowWidth = 0;
                int rowHeight = 0;
                for (Component component : target.getComponents()) {
                    if (!component.isVisible()) {
                        continue;
                    }
                    Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                    // The same test as FlowLayout.layoutContainer, so both break the lines at the same components
                    if (rowWidth > 0 && rowWidth + size.width > maxWidth) {
                        totalWidth = Math.max(totalWidth, rowWidth);
                        totalHeight += rowHeight + getVgap();
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    rowWidth += (rowWidth > 0 ? getHgap() : 0) + size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                totalWidth = Math.max(totalWidth, rowWidth);
                totalHeight += rowHeight;
                return new Dimension(totalWidth + insets.left + insets.right + 2 * getHgap(),
                    totalHeight + insets.top + insets.bottom + 2 * getVgap());
            }
        }
    }
}
