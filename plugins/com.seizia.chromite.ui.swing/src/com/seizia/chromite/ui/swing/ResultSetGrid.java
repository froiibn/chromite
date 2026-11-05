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
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 결과 집합 하나를 보여 주는 읽기 전용 표(SSMS 결과 그리드 하나).
 *
 * 설계: 값은 Job 스레드의 {@link ResultSetGridReceiver}가 결과 집합이 열려 있는 동안 표시 문자열로 바꿔 넘긴다
 * ({@link Data}). 그래서 EDT에서는 모델 계층(값 처리기, LOB 읽기)을 부르지 않고, 결과 집합을 닫은 뒤에도 값을 그대로 보여 줄 수 있다.
 * NULL은 표시 문자열과 구분되는 표지({@link #NULL_VALUE})로 두어 "NULL"이라는 문자열 값과 다르게 그린다(SSMS처럼 "NULL", 흐린 기울임꼴).
 * 숫자 열은 오른쪽 정렬한다. 열 너비는 머리글과 앞쪽 행들의 글자 너비로 정하고, 표는 자동 맞춤을 끄고 가로 스크롤한다.
 * 왼쪽에 SSMS처럼 행 번호 열을 둔다(행 머리글, 누르면 그 행 선택).
 * 편집은 아직 없다(swing-frontend.md "결과 모델 분석"의 Swing 그리드 계획 1단계).
 * 복사(Ctrl+C)는 {@link JTable}의 기본 전송 처리기가 선택한 칸의 표시 문자열을 탭으로 나눠 복사한다.
 * 오른쪽 클릭 메뉴(SSMS 결과 그리드와 같은 항목): Copy, Copy with Headers(Ctrl+Shift+C), Export to CSV…({@link ResultSetExport}).
 * 오른쪽 클릭한 칸이 선택 밖이면 그 칸을 선택한 뒤 메뉴를 연다.
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 E단계: 오른쪽 클릭 메뉴(복사, 머리글 포함 복사, CSV 내보내기) (docs/chromite/swing-frontend.md "구현 현황 — E단계")
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
    private static final int CELL_PADDING = 14;

    /**
     * Fetched result set.
     *
     * @param columns   column labels
     * @param numeric   whether a column holds numbers (right-aligned)
     * @param rows      display strings or {@link #NULL_VALUE}
     * @param truncated fetching stopped at the maximum row count
     */
    record Data(@NotNull String[] columns, @NotNull boolean[] numeric, @NotNull List<Object[]> rows, boolean truncated) {
    }

    ResultSetGrid(@NotNull Data data) {
        super(new BorderLayout());
        JTable table = new JTable(new GridModel(data));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CellRenderer(data.numeric()));

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setRowHeaderView(createRowHeader(table, data.rows().size()));
        add(scrollPane, BorderLayout.CENTER);
        fitColumnWidths(table, data);
        installContextMenu(table, data);
    }

    private static void installContextMenu(@NotNull JTable table, @NotNull Data data) {
        Action copyAction = createAction("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
            () -> TransferHandler.getCopyAction().actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"))); //$NON-NLS-1$
        Action copyWithHeadersAction = createAction("Copy with Headers",
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
            () -> DBWorkbench.getPlatformUI().copyTextToClipboard(ResultSetExport.copySelection(table, data, true), false));
        Action exportCsvAction = createAction("Export to CSV…", null, () -> ResultSetExport.exportCsv(table, data));
        // Ctrl+C stays with the table's transfer handler; the menu only shows it
        table.getInputMap(JComponent.WHEN_FOCUSED).put((KeyStroke) copyWithHeadersAction.getValue(Action.ACCELERATOR_KEY), "chromite.copyWithHeaders"); //$NON-NLS-1$
        table.getActionMap().put("chromite.copyWithHeaders", copyWithHeadersAction); //$NON-NLS-1$

        JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.add(copyAction);
        popupMenu.add(copyWithHeadersAction);
        popupMenu.addSeparator();
        popupMenu.add(exportCsvAction);
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
        boolean hasRows = !data.rows().isEmpty();
        copyAction.setEnabled(hasRows);
        copyWithHeadersAction.setEnabled(hasRows);
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

    private static void fitColumnWidths(@NotNull JTable table, @NotNull Data data) {
        FontMetrics cellMetrics = table.getFontMetrics(table.getFont());
        Font headerFont = table.getTableHeader().getFont();
        FontMetrics headerMetrics = table.getFontMetrics(headerFont != null ? headerFont : table.getFont());
        int measuredRows = Math.min(MEASURED_ROWS, data.rows().size());
        for (int column = 0; column < data.columns().length; column++) {
            int width = headerMetrics.stringWidth(data.columns()[column]);
            for (int row = 0; row < measuredRows; row++) {
                width = Math.max(width, cellMetrics.stringWidth(toDisplayText(data.rows().get(row)[column])));
            }
            TableColumn tableColumn = table.getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(Math.clamp(width + CELL_PADDING, MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH));
        }
    }

    /**
     * Row numbers like SSMS. A one-column table keeps the row heights of the grid; clicking a number selects the row.
     */
    @NotNull
    private static JComponent createRowHeader(@NotNull JTable table, int rowCount) {
        JTable header = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return rowCount;
            }

            @Override
            public int getColumnCount() {
                return 1;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                return rowIndex + 1;
            }
        }) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(UIManager.getColor("TableHeader.background")); //$NON-NLS-1$
                setForeground(UIManager.getColor("TableHeader.foreground")); //$NON-NLS-1$
            }
        };
        header.setFocusable(false);
        header.setRowSelectionAllowed(false);
        header.setRowHeight(table.getRowHeight());
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.RIGHT);
        header.setDefaultRenderer(Object.class, renderer);
        int width = header.getFontMetrics(header.getFont()).stringWidth(String.valueOf(Math.max(rowCount, 9))) + CELL_PADDING;
        header.getColumnModel().getColumn(0).setPreferredWidth(width);
        header.setPreferredScrollableViewportSize(new Dimension(width, 0));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = header.rowAtPoint(e.getPoint());
                if (row >= 0 && table.getColumnCount() > 0) {
                    table.changeSelection(row, 0, false, false);
                    table.changeSelection(row, table.getColumnCount() - 1, false, true);
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

    private static final class GridModel extends AbstractTableModel {
        private final Data data;

        GridModel(@NotNull Data data) {
            this.data = data;
        }

        @Override
        public int getRowCount() {
            return data.rows().size();
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
            return data.rows().get(rowIndex)[columnIndex];
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
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
            setFont(isNull ? font.deriveFont(Font.ITALIC) : font);
            if (!isSelected) {
                // setForeground also becomes the renderer's default, so every cell sets it
                setForeground(isNull ? UIManager.getColor("Label.disabledForeground") : table.getForeground()); //$NON-NLS-1$
            }
            return this;
        }
    }
}
