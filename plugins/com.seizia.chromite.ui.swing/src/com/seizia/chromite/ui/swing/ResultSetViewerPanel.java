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
import com.seizia.chromite.ui.swing.internal.GridFilterMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.order.OrderingStrategy;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.parser.SQLSemanticProcessor;
import com.seizia.chromite.model.struct.DBSDataContainer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
 *     정렬 아이콘과 이름 자리는 똑같이 정렬을 바꾼다(예전 머리글 클릭 동작을 그대로 둠).</li>
 *     <li><b>정렬</b>(SWT {@code ResultSetViewer.toggleSortOrder}): 열 머리글을 누르면 오름차순 → 내림차순 → 정렬 없음 순서로 바뀐다.
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
 * - 2026-11-08 상태 줄의 정렬 요약: 정렬한 열, 쿼리의 ORDER BY를 바꿨는지, 쿼리 순서로 되돌리기(✕). 상태 줄 왼쪽이 넘치면 두 줄로 늘어남
 *   ({@link WrapFlowLayout}: 전에는 넘친 요약이 상태 줄 밖으로 밀려 가려짐) (docs/chromite/swing-grid-column-filters.md "정렬과 쿼리의 ORDER BY")
 * - 2026-11-08 차트 보기·공간 보기와 상태 줄 Chart / Spatial 단추. 레코드 보기 여부(boolean)를 보기 방식({@code ViewMode})으로 바꿈.
 *   기하 값은 값 패널에도 보이고 "Filter by Value"에는 넣지 않음 (docs/chromite/swing-result-charts-spatial.md)
 */
final class ResultSetViewerPanel extends JPanel {

    private static final Log log = Log.getLog(ResultSetViewerPanel.class);

    /**
     * SWT {@code ResultSetPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT} (UI bundle, default on).
     */
    private static final String PREF_AUTO_FETCH_NEXT_SEGMENT = "resultset.autofetch.next.segment"; //$NON-NLS-1$
    private static final String CARD_GRID = "grid"; //$NON-NLS-1$
    private static final String CARD_RECORD = "record"; //$NON-NLS-1$
    private static final String CARD_CHART = "chart"; //$NON-NLS-1$
    private static final String CARD_SPATIAL = "spatial"; //$NON-NLS-1$
    private static final int SORT_CLICK_BORDER = 4;
    /**
     * Widest first column width made for the header icons (the grid's own first widths stop at the same width).
     */
    private static final int HEADER_FIT_MAX_WIDTH = 320;

    /**
     * Whether new result viewers open with the value panel (the state of the last toggle in this session).
     */
    private static boolean valuePanelDefault;

    /**
     * What the viewer shows in place of the grid: the grid itself, the record view, the chart view or the spatial view.
     */
    private enum ViewMode {
        GRID(CARD_GRID), RECORD(CARD_RECORD), CHART(CARD_CHART), SPATIAL(CARD_SPATIAL);

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
    private final JToggleButton chartToggle;
    private final JToggleButton spatialToggle;
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
            public void exportData(@NotNull Component parent, @NotNull DBSDataContainer query) {
                // The rows as the server read them (SWT ResultSetHandlerMain passes the model's data filter); a sort done in
                // the grid only can't be passed on
                String orderNote = clientOrdered && dataFilter.hasOrdering()
                    ? EditorMessages.editor_viewer_order_note
                    : null;
                DataExportWizard.openForQuery(parent, query, serverFilter, orderNote);
            }
        };
        this.fetched = fetched;
        this.ordinals = sequence(fetched.data().rows().size());
        this.dataFilter = createDataFilter(fetched.data().bindings());
        this.serverFilter = new DBDDataFilter(dataFilter);
        this.hasMore = fetched.data().truncated() && fetched.source().segmentSize() > 0;

        valuePanel = new ResultSetValuePanel();
        recordView = new ResultSetRecordView(new RecordSource());
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
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put((KeyStroke) toggleValuePanel.getValue(Action.ACCELERATOR_KEY), "chromite.toggleValuePanel"); //$NON-NLS-1$
        getActionMap().put("chromite.toggleValuePanel", toggleValuePanel); //$NON-NLS-1$

        Action fetchNext = createAction(EditorMessages.editor_viewer_fetch_next, null, () -> readNextSegment(false));
        fetchNextButton = createLinkButton(fetchNext);
        Action fetchAll = createAction(EditorMessages.editor_viewer_fetch_all, null, this::readAllData);
        fetchAll.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_viewer_fetch_all_tip);
        fetchAllButton = createLinkButton(fetchAll);

        cards.add(new JPanel(), CARD_GRID);
        cards.add(recordView, CARD_RECORD);
        cards.add(chartView, CARD_CHART);
        cards.add(spatialView, CARD_SPATIAL);
        // The split stays in place and the hidden value panel is invisible: moving the grid to another parent would
        // take the keyboard focus away from it
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cards, valuePanel);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        valuePanel.setVisible(false);

        add(split, BorderLayout.CENTER);
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
     * Releases the LOB values kept for the value panel (the results are cleared).
     */
    void dispose() {
        selectionTimer.stop();
        valuePanel.dispose();
        releaseContents(fetched.source().cellValues());
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
        left.add(rowsLabel);
        left.add(fetchNextButton);
        left.add(fetchAllButton);
        // Active filters: only when there are any, so the status line keeps its height and no row is added
        filterSummary.setOpaque(false);
        filterSummary.add(new JLabel(ResultSetHeaderRenderer.createActiveFilterIcon()));
        filterSummary.add(filterSummaryLabel);
        Action clearFilters = createAction(GridFilterMessages.gridfilter_clear_all, null, this::clearAllFilters);
        clearFilters.putValue(Action.SHORT_DESCRIPTION, GridFilterMessages.gridfilter_clear_all);
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
        restoreOrder.putValue(Action.SHORT_DESCRIPTION, GridFilterMessages.gridfilter_sort_restore);
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
        right.add(chartToggle);
        right.add(spatialToggle);
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
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
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
        ((CardLayout) cards.getLayout()).show(cards, mode.card);
        switch (mode) {
            case RECORD -> {
                recordView.refresh();
                // The record line of the grid's selected column, so the value panel keeps showing the same cell
                recordView.focusTable(grid != null ? grid.getTable().getColumnModel().getSelectionModel().getLeadSelectionIndex() : -1);
            }
            // The chart starts from the cells selected in the grid
            case CHART -> chartView.configureFromSelection();
            case SPATIAL -> spatialView.refresh();
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
        int[] widths = null;
        if (oldGrid != null) {
            JTable oldTable = oldGrid.getTable();
            if (keepView) {
                // The same rows in another order or with more rows: the user's column widths stay; a new read fits them again
                widths = new int[oldTable.getColumnCount()];
                for (int i = 0; i < widths.length; i++) {
                    widths[i] = oldTable.getColumnModel().getColumn(i).getWidth();
                }
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
        if (widths != null && widths.length == table.getColumnCount()) {
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
        }
        installHeader(table);
        if (widths == null) {
            fitHeaderWidths(table);
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
        // Sorted, filtered or more rows: the chart and the map show the grid's rows
        if (viewMode == ViewMode.CHART) {
            chartView.refresh();
        } else if (viewMode == ViewMode.SPATIAL) {
            spatialView.refresh();
        }
        scheduleSelectionUpdate();
    }

    @Nullable
    private static JViewport getViewport(@NotNull JTable table) {
        return table.getParent() instanceof JViewport viewport ? viewport : null;
    }

    /**
     * Column headers with the column icon, the filter and sort icons ({@link ResultSetHeaderRenderer}); a click on the
     * funnel opens the column's filter popup, on the sort icon or the name it sorts (Ctrl adds the column to the sort).
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
            public boolean isFilterEnabled() {
                return fetched.source().canRequery() && !reading;
            }
        });
        header.setDefaultRenderer(renderer);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1 || header.getCursor().getType() == Cursor.E_RESIZE_CURSOR) {
                    return;
                }
                int column = header.columnAtPoint(e.getPoint());
                if (column < 0 || isNearColumnBorder(header, column, e.getX())) {
                    return;
                }
                if (renderer.zoneAt(header, column, e.getPoint()) == ResultSetHeaderRenderer.Zone.FILTER) {
                    openFilterPopup(header, column);
                    return;
                }
                toggleSort(table.convertColumnIndexToModel(column), null, (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0);
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
                        int target = e.getX() - rect.x < SORT_CLICK_BORDER && column > 0 ? column - 1 : column;
                        ResultSetColumnFit.fitColumn(table, target);
                    }
                }
            }
        });
    }

    private static boolean isNearColumnBorder(@NotNull JTableHeader header, int column, int x) {
        Rectangle rect = header.getHeaderRect(column);
        return x - rect.x < SORT_CLICK_BORDER || rect.x + rect.width - x <= SORT_CLICK_BORDER;
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
            case NAME -> NLS.bind(GridFilterMessages.gridfilter_header_column_tip, binding.getLabel(), binding.getFullTypeName());
            case FILTER -> {
                if (!fetched.source().canRequery()) {
                    yield NLS.bind(EditorMessages.editor_viewer_filters_need, fetched.source().requeryBlocker());
                }
                String condition = constraint != null && constraint.hasCondition() ? getConditionText(constraint) : null;
                yield CommonUtils.isEmpty(condition) ? GridFilterMessages.gridfilter_header_filter_tip
                    : NLS.bind(GridFilterMessages.gridfilter_header_filtered_tip, condition);
            }
            case SORT -> {
                if (constraint == null || constraint.getOrderPosition() <= 0) {
                    yield GridFilterMessages.gridfilter_header_sort_ascending_tip;
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
        if (reading || !fetched.source().canRequery()) {
            return;
        }
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        ResultSetColumnFilterPopup.show(header, header.getHeaderRect(column), bindings[header.getTable().convertColumnIndexToModel(column)],
            bindings, new DBDDataFilter(dataFilter), fetched.source(), new ResultSetColumnFilterPopup.Host() {
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
     * Adds the viewer's items to the grid's context menu: filters, copy formats, panels.
     */
    private void installMenus(@NotNull JTable table) {
        JPopupMenu menu = table.getComponentPopupMenu();
        if (menu == null) {
            return;
        }
        JMenu filterMenu = new JMenu(EditorMessages.editor_viewer_filter_by_value);
        JMenu copyAsMenu = ResultSetCopyFormats.createMenu(table, () -> grid.getCurrentData(), this::getDataSource);
        Action clearFilter = createAction(EditorMessages.editor_common_clear_filter, null, this::clearAllFilters);
        Action fitColumns = createAction(EditorMessages.editor_viewer_fit_all_columns, null, () -> ResultSetColumnFit.fitAllColumns(table));
        JCheckBoxMenuItem valuePanelItem = new JCheckBoxMenuItem(valuePanelToggle.getAction());
        JCheckBoxMenuItem recordItem = new JCheckBoxMenuItem(recordToggle.getAction());
        JCheckBoxMenuItem chartItem = new JCheckBoxMenuItem(chartToggle.getAction());
        JCheckBoxMenuItem spatialItem = new JCheckBoxMenuItem(spatialToggle.getAction());

        int copyIndex = -1;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JMenuItem item && EditorMessages.editor_grid_copy_with_headers.equals(item.getText())) {
                copyIndex = i;
            }
        }
        if (copyIndex >= 0) {
            menu.insert(copyAsMenu, copyIndex + 1);
        } else {
            menu.add(copyAsMenu);
        }
        menu.addSeparator();
        menu.add(filterMenu);
        menu.add(clearFilter);
        menu.addSeparator();
        menu.add(fitColumns);
        menu.add(valuePanelItem);
        menu.add(recordItem);
        menu.add(chartItem);
        menu.add(spatialItem);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                fillFilterMenu(filterMenu, table);
                clearFilter.setEnabled(fetched.source().canRequery() && dataFilter.hasConditions() && !reading);
                valuePanelItem.setSelected(valuePanelShown);
                recordItem.setSelected(isRecordMode());
                chartItem.setSelected(viewMode == ViewMode.CHART);
                spatialItem.setVisible(spatialToggle.isVisible());
                spatialItem.setSelected(viewMode == ViewMode.SPATIAL);
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
        Object value = getCellValue(row, modelColumn);
        String name = binding.getName();
        // Geometries are kept for the spatial view only; they are not offered as filter values
        if (value != null && !(value instanceof ResultSetCellContent) && !(value instanceof DBGeometry)) {
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
            // Value objects are not in the edit model (their columns are read-only): LOB content and geometries. The
            // model has null there, or the empty value object that binding the column to its table made of that null
            // (MariaDB geometry: an empty DBGeometry)
            if (DBUtils.isNullValue(value) && editRow.state != ResultSetEditModel.RowState.ADDED && !editRow.isCellChanged(column)) {
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
        int viewColumn = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (isRecordMode()) {
            recordView.refresh();
            int recordColumn = recordView.getSelectedColumn();
            if (recordColumn >= 0) {
                viewColumn = recordColumn;
            }
        }
        if (valuePanelShown) {
            if (row >= 0 && row < table.getRowCount() && viewColumn >= 0 && viewColumn < table.getColumnCount()) {
                int column = table.convertColumnIndexToModel(viewColumn);
                DBDAttributeBinding binding = fetched.data().bindings()[column];
                valuePanel.showCell(new ResultSetValuePanel.Cell(
                    System.identityHashCode(grid) + ":" + row + ":" + column, //$NON-NLS-1$ //$NON-NLS-2$
                    binding,
                    getDisplayValue(row, column),
                    getCellValue(row, column),
                    isCellEditable(row, column)));
            } else {
                valuePanel.showCell(null);
            }
        }
        if (viewMode == ViewMode.SPATIAL) {
            spatialView.selectionChanged();
        }
        // The aggregates describe the grid's selection: only while the grid is shown
        aggregateLabel.setText(viewMode != ViewMode.GRID ? "" : ResultSetAggregates.describeSelection(table, this::getAggregateValue));
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
        int viewColumn = isRecordMode() && recordView.getSelectedColumn() >= 0
            ? recordView.getSelectedColumn() : table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row < 0 || viewColumn < 0) {
            return EditorMessages.editor_viewer_no_cell;
        }
        return grid.setCellText(row, table.convertColumnIndexToModel(viewColumn), text);
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
            text = NLS.bind(hasMore
                ? rows == 1 ? EditorMessages.editor_viewer_rows_more_one : EditorMessages.editor_viewer_rows_more_many
                : rows == 1 ? EditorMessages.editor_viewer_rows_one : EditorMessages.editor_viewer_rows_many, rowsText);
            if (dataFilter.hasConditions()) {
                text = NLS.bind(EditorMessages.editor_viewer_rows_filtered, text);
            }
        }
        rowsLabel.setText(text);
        boolean canRead = hasMore && fetched.source().canRequery() && !reading;
        fetchNextButton.setVisible(canRead);
        fetchAllButton.setVisible(canRead);
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
        if (isServerSideOrdering()) {
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
                Comparator<Object> comparator = bindings[column].getValueHandler().getComparator();
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
        DBDDataFilter readFilter = new DBDDataFilter(filter);
        DBDDataFilter viewFilter = new DBDDataFilter(filter);
        long segmentSize = source.segmentSize();
        startRead("Read results", context, (monitor, done) -> {
            ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
            ResultSetGridReceiver receiver = new ResultSetGridReceiver(source.dataContainer(), fetchedResult -> result[0] = fetchedResult);
            readData(monitor, context, source.dataContainer(), receiver, readFilter, 0, segmentSize > 0 ? segmentSize : -1);
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
        installGrid(new ResultSetGrid(newFetched, gridHost), false);
        host.editStateChanged();
        host.rowsChanged();
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
        if (context != null && !context.getDataSource().getContainer().getPreferenceStore().getBoolean(PREF_AUTO_FETCH_NEXT_SEGMENT)
            && context.getDataSource().getContainer().getPreferenceStore().contains(PREF_AUTO_FETCH_NEXT_SEGMENT)) {
            return;
        }
        SwingUtilities.invokeLater(() -> readNextSegment(true));
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
        DBDDataFilter readFilter = new DBDDataFilter(dataFilter);
        DBDAttributeBinding[] bindings = fetched.data().bindings();
        int offset = fetched.editModel() != null ? fetched.editModel().getRowCount() : fetched.data().rows().size();
        startRead("Read next rows", context, (monitor, done) -> {
            ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
            ResultSetGridReceiver receiver = ResultSetGridReceiver.forNextSegment(source.dataContainer(), bindings,
                fetchedResult -> result[0] = fetchedResult);
            readData(monitor, context, source.dataContainer(), receiver, readFilter, offset, maxRows);
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
            if (fetchedCells != null) {
                for (int c = 0; c < rowCells.length; c++) {
                    if (fetchedCells[c] instanceof ResultSetCellContent content && !row.isCellChanged(c)) {
                        rowCells[c] = content;
                    }
                }
            }
            int ordinal = number < ordinals.length ? ordinals[number] : ordinals.length + number;
            rows.add(new ViewerRow(ordinal, row.display.clone(), ResultSetGridReceiver.keepValues(row.values), rowCells, row.values.clone()));
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
            : new ResultSetEditModel(oldModel.getExecutionContext(), oldModel.getDataContainer(), old.bindings(), unreadable, values, display);
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

    private void readData(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull DBSDataContainer dataContainer,
        @NotNull ResultSetGridReceiver receiver,
        @NotNull DBDDataFilter filter,
        long offset,
        long maxRows
    ) throws Exception {
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.USER, "Read result rows")) {
            // The query is the source descriptor, as in the engine's first run: binding the columns to the table
            // (editing, keys) reads the table from it, because drivers like SQL Server don't name the column tables
            Object descriptor = dataContainer instanceof SQLQueryContainer container ? container.getQuery() : null;
            AbstractExecutionSource source = new AbstractExecutionSource(dataContainer, context, this, descriptor);
            // FLAG_REFRESH: query parameters keep their values instead of being asked again
            dataContainer.readData(source, session, receiver, filter.hasFilters() ? filter : null, offset, maxRows,
                DBSDataContainer.FLAG_REFRESH, 0);
        } finally {
            // Delivers what was read if fetchEnd was not reached
            receiver.close();
        }
    }

    //////////////////////////////////////////////////////////
    // Helpers

    private static void releaseContents(@NotNull List<Object[]> cells) {
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
            return isCellEditable(row, column);
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
