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
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.order.OrderingStrategy;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractExecutionSource;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQueryContainer;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.jobs.DataSourceJob;
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
 * 결과 집합 하나의 뷰어(SWT {@code ResultSetViewer}에 해당): 결과 표({@link ResultSetGrid})를 감싸 필터 줄, 값 패널, 레코드 보기, 상태 줄을 붙인다.
 *
 * 설계:
 * <ul>
 *     <li>표 자체(그리기, 셀 편집, 저장)는 {@link ResultSetGrid}가 맡고, 이 클래스는 표 밖의 기능만 가진다. 정렬·다음 페이지처럼 행이 바뀌면
 *     표를 고치지 않고 바뀐 행으로 새 {@link ResultSetGrid}를 만들어 바꿔 끼운다(열 너비, 스크롤, 선택은 이어받는다).
 *     표가 값을 표시 문자열로 미리 갖는 구조(C단계)와 편집 모델(G단계)을 그대로 두고 기능을 더하기 위해서다.</li>
 *     <li><b>정렬</b>(SWT {@code ResultSetViewer.toggleSortOrder}): 열 머리글을 누르면 오름차순 → 내림차순 → 정렬 없음 순서로 바뀐다.
 *     Ctrl을 누르고 누르면 다른 열의 정렬을 두고 이 열을 더한다. 서버에서 정렬할지는 SWT 설정 {@code resultset.order.mode}
 *     ({@link OrderingStrategy}: SMART면 아직 읽지 않은 행이 있을 때만 서버, CLIENT_SIDE는 항상 표 안에서, SERVER_SIDE는 항상 서버)와
 *     DB가 결과 정렬을 지원하는지로 정하고, 쿼리를 다시 읽을 수 없는 결과({@link ResultSetSource#requeryBlocker()})는 표 안에서 정렬한다.
 *     표 안 정렬은 SWT {@code ResultSetModel.resetOrderingImpl}처럼 먼저 읽은 순서로 되돌린 뒤 열의 값 처리기 비교기, 문자열은 대소문자 무시,
 *     그 밖에는 {@link DBUtils#compareDataValues}로 비교한다.</li>
 *     <li><b>필터</b>(SWT {@code ResultSetFilterPanel}): 필터 줄({@link ResultSetFilterBar})에 WHERE 조건을 쓰고 Enter로 적용하면
 *     {@link DBDDataFilter#setWhere}로 쿼리를 다시 읽는다. 표의 오른쪽 클릭 "Filter by Value"는 SWT {@code FilterByAttributeAction}처럼
 *     열 조건(연산자와 값)을 더한다. 필터 줄은 현재 조건을 SWT처럼 {@link SQLUtils#appendConditionString}으로 보여 준다.</li>
 *     <li><b>다음 페이지</b>(SWT {@code readNextSegment}, {@code readAllData}): 표를 끝까지 내리면(SWT 설정 {@code resultset.autofetch.next.segment},
 *     기본 켬) 또는 상태 줄의 "Fetch next"를 누르면 같은 쿼리를 지금 행 수부터 한 번에 읽는 행 수만큼 더 읽어 붙인다. "Fetch all"은 확인한 뒤 나머지를 모두 읽는다.</li>
 *     <li>다시 읽기는 모두 백그라운드 Job({@link DataSourceJob})에서 {@link DBSDataContainer#readData}로 한다. 저장하지 않은 변경이 있으면
 *     SWT {@code checkForChanges}처럼 먼저 저장하거나 취소해야 한다(자동 다음 페이지는 묻지 않고 건너뛴다).</li>
 *     <li><b>값 패널</b>({@link ResultSetValuePanel}, F7)과 <b>레코드 보기</b>({@link ResultSetRecordView})는 지금 선택한 칸·행을 보여 준다.
 *     값 패널을 연 상태는 다음 결과에도 이어진다(이 실행 동안).</li>
 *     <li><b>상태 줄</b>: 읽은 행 수("200 rows fetched — more available"), 다음 페이지 링크, 선택한 칸의 집계(SSMS 상태 줄처럼 개수·합계·평균·최솟값·최댓값,
 *     모델 계층의 {@code org.jkiss.dbeaver.model.data.aggregate} 함수 사용, {@link ResultSetAggregates}).</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 값 패널, 정렬·필터, 다음 페이지, 레코드 보기, 복사 형식, 열 너비 맞춤, 선택 집계 (docs/chromite/swing-result-viewer.md)
 */
final class ResultSetViewerPanel extends JPanel {

    private static final Log log = Log.getLog(ResultSetViewerPanel.class);

    /**
     * SWT {@code ResultSetPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT} (UI bundle, default on).
     */
    private static final String PREF_AUTO_FETCH_NEXT_SEGMENT = "resultset.autofetch.next.segment"; //$NON-NLS-1$
    private static final String CARD_GRID = "grid"; //$NON-NLS-1$
    private static final String CARD_RECORD = "record"; //$NON-NLS-1$
    private static final int SORT_CLICK_BORDER = 4;

    /**
     * Whether new result viewers open with the value panel (the state of the last toggle in this session).
     */
    private static boolean valuePanelDefault;

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
    private final ResultSetFilterBar filterBar;
    private final ResultSetValuePanel valuePanel;
    private final ResultSetRecordView recordView;
    private final JLabel rowsLabel = new JLabel();
    private final JButton fetchNextButton;
    private final JButton fetchAllButton;
    private final JLabel aggregateLabel = new JLabel();
    private final JToggleButton valuePanelToggle;
    private final JToggleButton recordToggle;
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
    private boolean hasMore;
    private boolean clientOrdered;
    private boolean reading;
    @Nullable
    private String statusMessage;
    private int lastScrollValue;
    private boolean valuePanelShown;
    // Kept apart from the toggle button: a click selects the button before its action runs
    private boolean recordMode;
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
        };
        this.fetched = fetched;
        this.ordinals = sequence(fetched.data().rows().size());
        this.dataFilter = createDataFilter(fetched.data().bindings());
        this.hasMore = fetched.data().truncated() && fetched.source().segmentSize() > 0;

        filterBar = new ResultSetFilterBar(new ResultSetFilterBar.Listener() {
            @Override
            public void applyFilter(@NotNull String where) {
                ResultSetViewerPanel.this.applyWhere(where);
            }

            @Nullable
            @Override
            public String getQueryText() {
                return ResultSetViewerPanel.this.getQueryText();
            }
        });
        valuePanel = new ResultSetValuePanel();
        recordView = new ResultSetRecordView(new RecordSource());

        Action toggleValuePanel = createAction("Value Panel", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), () -> setValuePanelShown(!valuePanelShown));
        toggleValuePanel.putValue(Action.SHORT_DESCRIPTION, "Show the value of the selected cell beside the grid (F7)");
        valuePanelToggle = createToggle(toggleValuePanel);
        Action toggleRecord = createAction("Record", null, () -> setRecordMode(!isRecordMode()));
        toggleRecord.putValue(Action.SHORT_DESCRIPTION, "Show the selected row as a record (one column per line)");
        recordToggle = createToggle(toggleRecord);
        filterBar.addTrailing(valuePanelToggle);
        filterBar.addTrailing(recordToggle);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put((KeyStroke) toggleValuePanel.getValue(Action.ACCELERATOR_KEY), "chromite.toggleValuePanel"); //$NON-NLS-1$
        getActionMap().put("chromite.toggleValuePanel", toggleValuePanel); //$NON-NLS-1$

        Action fetchNext = createAction("Fetch next", null, () -> readNextSegment(false));
        fetchNextButton = createLinkButton(fetchNext);
        Action fetchAll = createAction("Fetch all", null, this::readAllData);
        fetchAll.putValue(Action.SHORT_DESCRIPTION, "Read all remaining rows of the query");
        fetchAllButton = createLinkButton(fetchAll);

        cards.add(new JPanel(), CARD_GRID);
        cards.add(recordView, CARD_RECORD);
        // The split stays in place and the hidden value panel is invisible: moving the grid to another parent would
        // take the keyboard focus away from it
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cards, valuePanel);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        valuePanel.setVisible(false);

        add(filterBar, BorderLayout.NORTH);
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
        bar.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        rowsLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        left.add(rowsLabel);
        left.add(fetchNextButton);
        left.add(fetchAllButton);
        aggregateLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        bar.add(left, BorderLayout.CENTER);
        bar.add(aggregateLabel, BorderLayout.EAST);
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
        return recordMode;
    }

    private void setRecordMode(boolean recordMode) {
        this.recordMode = recordMode;
        recordToggle.setSelected(recordMode);
        ((CardLayout) cards.getLayout()).show(cards, recordMode ? CARD_RECORD : CARD_GRID);
        if (recordMode) {
            recordView.refresh();
            // The record line of the grid's selected column, so the value panel keeps showing the same cell
            recordView.focusTable(grid != null ? grid.getTable().getColumnModel().getSelectionModel().getLeadSelectionIndex() : -1);
        } else if (grid != null) {
            grid.getTable().requestFocusInWindow();
        }
        scheduleSelectionUpdate();
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
        installSortHeader(table);
        installMenus(table);
        ListSelectionListener selectionListener = e -> scheduleSelectionUpdate();
        table.getSelectionModel().addListSelectionListener(selectionListener);
        table.getColumnModel().getSelectionModel().addListSelectionListener(selectionListener);
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        if (scrollPane != null) {
            lastScrollValue = 0;
            scrollPane.getVerticalScrollBar().addAdjustmentListener(this::verticalScrolled);
        }
        ((CardLayout) cards.getLayout()).show(cards, isRecordMode() ? CARD_RECORD : CARD_GRID);
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
        filterBar.setEnabled(fetched.source().canRequery(), fetched.source().requeryBlocker());
        filterBar.setFilterText(getConditionText());
        updateStatus();
        recordView.refresh();
        scheduleSelectionUpdate();
    }

    @Nullable
    private static JViewport getViewport(@NotNull JTable table) {
        return table.getParent() instanceof JViewport viewport ? viewport : null;
    }

    /**
     * Sort arrows in the column headers and header clicks that sort (Ctrl adds the column to the sort).
     */
    private void installSortHeader(@NotNull JTable table) {
        JTableHeader header = table.getTableHeader();
        TableCellRenderer delegate = header.getDefaultRenderer();
        // The wrapper is not a component, so a look and feel switch would not reach the wrapped renderer
        header.addPropertyChangeListener("UI", e -> { //$NON-NLS-1$
            if (delegate instanceof Component component) {
                SwingUtilities.updateComponentTreeUI(component);
            }
        });
        header.setDefaultRenderer((headerTable, value, isSelected, hasFocus, row, column) -> {
            Component component = delegate.getTableCellRendererComponent(headerTable, value, isSelected, hasFocus, row, column);
            if (component instanceof JLabel label) {
                DBDAttributeConstraint constraint = getConstraint(headerTable.convertColumnIndexToModel(column));
                Icon icon = null;
                if (constraint != null && constraint.getOrderPosition() > 0) {
                    icon = UIManager.getIcon(constraint.isOrderDescending() ? "Table.descendingSortIcon" : "Table.ascendingSortIcon"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                label.setIcon(icon);
                label.setHorizontalTextPosition(SwingConstants.LEFT);
            }
            return component;
        });
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
                toggleSort(table.convertColumnIndexToModel(column), null, (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0);
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
                    headerMenu.add(createAction("Sort Ascending", null, () -> toggleSort(modelColumn, Boolean.FALSE, false)));
                    headerMenu.add(createAction("Sort Descending", null, () -> toggleSort(modelColumn, Boolean.TRUE, false)));
                }
                Action clearSort = createAction("Clear Sorting", null, this::clearSorting);
                clearSort.setEnabled(dataFilter.hasOrdering());
                headerMenu.add(clearSort);
                headerMenu.addSeparator();
                if (column >= 0) {
                    headerMenu.add(createAction("Fit Column Width", null, () -> ResultSetColumnFit.fitColumn(table, column)));
                }
                headerMenu.add(createAction("Fit All Column Widths", null, () -> ResultSetColumnFit.fitAllColumns(table)));
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
     * Adds the viewer's items to the grid's context menu: filters, copy formats, panels.
     */
    private void installMenus(@NotNull JTable table) {
        JPopupMenu menu = table.getComponentPopupMenu();
        if (menu == null) {
            return;
        }
        JMenu filterMenu = new JMenu("Filter by Value");
        JMenu copyAsMenu = ResultSetCopyFormats.createMenu(table, () -> grid.getCurrentData(), this::getDataSource);
        Action clearFilter = createAction("Clear Filter", null, () -> applyWhere(""));
        Action fitColumns = createAction("Fit All Column Widths", null, () -> ResultSetColumnFit.fitAllColumns(table));
        JCheckBoxMenuItem valuePanelItem = new JCheckBoxMenuItem(valuePanelToggle.getAction());
        JCheckBoxMenuItem recordItem = new JCheckBoxMenuItem(recordToggle.getAction());

        int copyIndex = -1;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JMenuItem item && "Copy with Headers".equals(item.getText())) { //$NON-NLS-1$
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
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                fillFilterMenu(filterMenu, table);
                clearFilter.setEnabled(fetched.source().canRequery() && dataFilter.hasConditions() && !reading);
                valuePanelItem.setSelected(valuePanelShown);
                recordItem.setSelected(isRecordMode());
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
            filterMenu.setToolTipText(fetched.source().canRequery() ? null : "Filters need " + fetched.source().requeryBlocker());
            return;
        }
        filterMenu.setToolTipText(null);
        int modelColumn = table.convertColumnIndexToModel(column);
        DBDAttributeBinding binding = fetched.data().bindings()[modelColumn];
        Object value = getCellValue(row, modelColumn);
        String name = binding.getName();
        if (value != null && !(value instanceof ResultSetCellContent)) {
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
            filterMenu.add(createAction("Remove the Filter of " + name, null, () -> {
                DBDDataFilter filter = new DBDDataFilter(dataFilter);
                DBDAttributeConstraint copy = filter.getConstraint(binding);
                if (copy != null) {
                    copy.setCriteria(null);
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
     * The original value of a cell: the edited value of an editable result, the kept value or the LOB content, or
     * null for NULL and values that are not kept.
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
            if (value == null && editRow.state != ResultSetEditModel.RowState.ADDED && !editRow.isCellChanged(column)) {
                int number = editRow.getRowNumber();
                if (number < cells.size() && cells.get(number)[column] instanceof ResultSetCellContent content) {
                    return content;
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

    @Nullable
    private String getQueryText() {
        return fetched.source().dataContainer() instanceof SQLQueryContainer container
            && container.getQuery() != null ? container.getQuery().getText() : null;
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
        aggregateLabel.setText(isRecordMode() ? "" : ResultSetAggregates.describeSelection(table, this::getAggregateValue));
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
            return "no grid";
        }
        JTable table = grid.getTable();
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int viewColumn = isRecordMode() && recordView.getSelectedColumn() >= 0
            ? recordView.getSelectedColumn() : table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (row < 0 || viewColumn < 0) {
            return "no cell is selected";
        }
        return grid.setCellText(row, table.convertColumnIndexToModel(viewColumn), text);
    }

    //////////////////////////////////////////////////////////
    // Status

    private void updateStatus() {
        int rows = fetched.data().rows().size();
        ResultSetEditModel editModel = fetched.editModel();
        if (editModel != null) {
            rows = editModel.getRowCount();
        }
        String text;
        if (reading) {
            text = "Reading…";
        } else if (statusMessage != null) {
            text = statusMessage;
        } else {
            text = String.format("%,d", rows) + (rows == 1 ? " row" : " rows") + (hasMore ? " fetched — more available" : "");
            if (dataFilter.hasConditions()) {
                text += " (filtered)";
            }
        }
        rowsLabel.setText(text);
        boolean canRead = hasMore && fetched.source().canRequery() && !reading;
        fetchNextButton.setVisible(canRead);
        fetchAllButton.setVisible(canRead);
        fetchNextButton.setText("Fetch next " + String.format("%,d", fetched.source().segmentSize()));
        if (!fetched.source().canRequery() && hasMore) {
            rowsLabel.setToolTipText("More rows can't be read: " + fetched.source().requeryBlocker());
        } else {
            rowsLabel.setToolTipText(null);
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
        if (reading || !checkNoChanges("sort the results")) {
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
        if (reading || !dataFilter.hasOrdering() || !checkNoChanges("sort the results")) {
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
            }
        }
        filter.setWhere(old.getWhere());
        filter.setOrder(old.getOrder());
        return filter;
    }

    /**
     * The conditions of the filter as SQL, as the SWT filter panel shows them.
     */
    @NotNull
    private String getConditionText() {
        DBPDataSource dataSource = getDataSource();
        if (dataSource == null || !dataFilter.hasConditions()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try {
            SQLUtils.appendConditionString(dataFilter, dataSource, null, text, true);
        } catch (Exception e) {
            log.debug("Can't make the filter text", e);
            return CommonUtils.notEmpty(dataFilter.getWhere());
        }
        return text.toString();
    }

    /**
     * The filter bar was applied (SWT {@code ResultSetFilterPanel.setCustomDataFilter}): the text replaces the column
     * conditions.
     */
    private void applyWhere(@NotNull String where) {
        if (where.trim().equals(getConditionText().trim())) {
            return;
        }
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        for (DBDAttributeConstraint constraint : filter.getConstraints()) {
            constraint.setCriteria(null);
        }
        filter.setWhere(where.isBlank() ? null : where.trim());
        applyFilter(filter);
    }

    private void filterByValue(@NotNull DBDAttributeBinding binding, @NotNull DBCLogicalOperator operator, @Nullable Object value) {
        DBDDataFilter filter = new DBDDataFilter(dataFilter);
        DBDAttributeConstraint constraint = filter.getConstraint(binding);
        if (constraint == null) {
            return;
        }
        constraint.setOperator(operator);
        constraint.setValue(value);
        applyFilter(filter);
    }

    private void applyFilter(@NotNull DBDDataFilter filter) {
        if (reading || !fetched.source().canRequery()) {
            return;
        }
        if (!checkNoChanges("filter the results")) {
            filterBar.setFilterText(getConditionText());
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
            statusMessage = "Wait until the changes are saved to " + what + ".";
            updateStatus();
            return false;
        }
        int changes = grid.getChangeCount();
        if (changes == 0) {
            return true;
        }
        return DBWorkbench.getPlatformUI().confirmAction("Unsaved Changes",
            "The results have " + changes + (changes == 1 ? " unsaved change" : " unsaved changes")
                + ". To " + what + " the changes are discarded. Discard them?", true);
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

    private void applyRequery(@NotNull ResultSetGrid.Fetched newFetched, @NotNull DBDDataFilter filter) {
        releaseContents(fetched.source().cellValues());
        fetched = newFetched;
        ordinals = sequence(newFetched.data().rows().size());
        hasMore = newFetched.data().truncated() && newFetched.source().segmentSize() > 0;
        dataFilter = rebindFilter(filter, newFetched.data().bindings());
        clientOrdered = false;
        installGrid(new ResultSetGrid(newFetched, gridHost), false);
        host.editStateChanged();
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
            statusMessage = "Save or cancel the changes to fetch more rows.";
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
        if (!DBWorkbench.getPlatformUI().confirmAction("Fetch All Rows",
            "Read all remaining rows of this result? A large result can take a long time and a lot of memory.")) {
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
            DBWorkbench.getPlatformUI().showMessageBox("Fetch More Rows",
                "Save or cancel the pending changes of this grid before fetching more rows.", false);
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
            statusMessage = "Save or cancel the changes to fetch more rows.";
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
        fetched = new ResultSetGrid.Fetched(data, editModel, fetched.readOnlyReason(), newSource);
        ordinals = newOrdinals;
        installGrid(new ResultSetGrid(fetched, gridHost), keepView);
        host.editStateChanged();
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
        filterBar.setReading(true);
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
        filterBar.setReading(false);
        if (!isDisplayable()) {
            return;
        }
        if (error != null) {
            String message = CommonUtils.getAllExceptionMessages(error);
            statusMessage = "Read failed: " + CommonUtils.truncateString(message.replace('\n', ' '), 200);
            host.appendError("Can't read the results again: " + message);
            log.debug("Result read failed", error);
            // The filter that failed stays in the bar for correction; the data filter is what the grid shows
            updateStatus();
            return;
        }
        if (work != null) {
            work.run();
        }
        filterBar.setFilterText(getConditionText());
        updateStatus();
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
            return grid == null ? "no grid" : grid.setCellText(row, column, text);
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
}
