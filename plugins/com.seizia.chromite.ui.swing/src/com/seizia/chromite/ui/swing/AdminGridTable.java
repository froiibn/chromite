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

import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.admin.space.SpaceFormat;
import com.seizia.chromite.ui.swing.internal.AdminViewMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 관리 화면(공간 사용량, 서버 로그)의 표: 열 종류({@link SpaceColumn.Kind})에 따라 값을 보이고 정렬하고 복사한다.
 *
 * 설계: 값은 원래 숫자로 두고 그릴 때만 바꾼다. 크기는 사람이 읽는 단위(1.5 GB)로 보이고 도구 설명에 정확한 바이트 수를 보인다.
 * 정렬은 원래 값으로 하고 빈 값(읽을 수 없음)은 늘 아래로 보낸다(오름·내림 모두). Activity Monitor의 {@link ObjectListTable}은
 * 모델 객체의 {@code @Property}로 열을 만들지만, 이 화면의 행은 시스템 뷰 질의 결과라 열 정의를 직접 받는다.
 * <ul>
 *     <li>복사: Ctrl+C와 "복사"는 보이는 글자, "머리글과 함께 복사"는 머리글 줄을 더하고, "정확한 값 복사"는 크기를 바이트 수 그대로
 *     (스프레드시트에 붙여 계산할 수 있게) 탭으로 나눈 줄로 복사한다.</li>
 *     <li>다시 읽어도 열이 같으면 정렬과 열 너비를 그대로 둔다(새로 고침마다 사용자의 정렬이 풀리지 않게).</li>
 *     <li>공간 사용량 표는 열 너비를 지키고 옆으로 스크롤한다. 로그 표({@code fillWidth})는 앞 열(시간, 수준, 출처)을 잰 너비로 두고 마지막 열(메시지)이
 *     남은 너비를 쓴다. JTable은 모자라거나 남는 너비를 모든 열에 선호 너비 비율로 나눠 시간·수준 열이 잘렸다({@link #doLayout}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량·서버 로그 (docs/chromite/swing-admin-tools.md)
 */
final class AdminGridTable extends JTable {

    private static final int MAX_COLUMN_WIDTH = 420;
    private static final int MIN_COLUMN_WIDTH = 48;
    private static final int MAX_TOOL_TIP = 500;
    private static final int FILL_COLUMN_WIDTH = 120;

    private final GridModel model = new GridModel();
    private final TableRowSorter<GridModel> sorter = new TableRowSorter<>(model);
    private final boolean fillWidth;

    /**
     * @param fillWidth the last column takes the rest of the width and the table never scrolls sideways (log messages);
     *                  otherwise the columns keep their widths and the table scrolls when they are wider than the view
     */
    AdminGridTable(boolean fillWidth) {
        this.fillWidth = fillWidth;
        setModel(model);
        setRowSorter(sorter);
        setAutoResizeMode(fillWidth ? JTable.AUTO_RESIZE_LAST_COLUMN : JTable.AUTO_RESIZE_OFF);
        setFillsViewportHeight(true);
        setShowHorizontalLines(false);
        setShowVerticalLines(false);
        setIntercellSpacing(new Dimension(0, 0));
        getTableHeader().setReorderingAllowed(true);
        getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setDefaultRenderer(Object.class, new ValueRenderer());

        Action copy = createAction(AdminViewMessages.admin_copy, () -> copy(false, false));
        // The table's own Ctrl+C copies toString() of the raw values; copy what is shown instead
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "chromite.copy"); //$NON-NLS-1$
        getActionMap().put("chromite.copy", copy); //$NON-NLS-1$
        copy.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        JPopupMenu menu = new JPopupMenu();
        menu.add(copy);
        menu.add(createAction(AdminViewMessages.admin_copy_headers, () -> copy(true, false)));
        JMenuItem exactItem = menu.add(createAction(AdminViewMessages.admin_copy_exact, () -> copy(true, true)));
        exactItem.setToolTipText(AdminViewMessages.admin_copy_exact_tip);
        setComponentPopupMenu(menu);
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable task) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
    }

    /**
     * Fills the viewport when the columns are narrower than it (no empty horizontal scroll bar), scrolls otherwise;
     * always fills it with {@code fillWidth}.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return fillWidth || getParent() instanceof JViewport viewport && getPreferredSize().width < viewport.getWidth();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // The renderer is not in the component tree; a new one picks up the colors of the new theme
        if (model != null) {
            setDefaultRenderer(Object.class, new ValueRenderer());
        }
    }

    @Override
    protected JTableHeader createDefaultTableHeader() {
        return new JTableHeader(columnModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int column = columnAtPoint(event.getPoint());
                if (column < 0) {
                    return null;
                }
                SpaceColumn spaceColumn = model.columns.get(convertColumnIndexToModel(column));
                return spaceColumn.toolTip() != null ? spaceColumn.toolTip() : spaceColumn.label();
            }
        };
    }

    /**
     * Shows new rows; keeps the sort and the column widths when the columns are the same.
     */
    void setRows(@NotNull List<SpaceColumn> columns, @NotNull List<Object[]> rows) {
        boolean structureChanged = !columns.equals(model.columns);
        model.columns = columns;
        model.rows = new ArrayList<>(rows);
        if (structureChanged) {
            model.fireTableStructureChanged();
            installComparators();
            packColumns();
        } else {
            model.fireTableDataChanged();
        }
    }

    void clearRows() {
        model.rows = new ArrayList<>();
        model.fireTableDataChanged();
    }

    /**
     * Adds rows below the current ones (the next page of a log).
     */
    void appendRows(@NotNull List<Object[]> rows) {
        int first = model.rows.size();
        model.rows.addAll(rows);
        if (!rows.isEmpty()) {
            model.fireTableRowsInserted(first, model.rows.size() - 1);
            if (first == 0) {
                // The widths were measured without rows (the columns are set before the first page)
                packColumns();
            }
        }
    }

    int getModelRowCount() {
        return model.rows.size();
    }

    /**
     * The model row of the selected view row, or -1.
     */
    int getSelectedModelRow() {
        int viewRow = getSelectedRow();
        return viewRow < 0 ? -1 : convertRowIndexToModel(viewRow);
    }

    private void installComparators() {
        for (int column = 0; column < model.columns.size(); column++) {
            sorter.setComparator(column, comparator(model.columns.get(column).kind()));
        }
    }

    @NotNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<Object> comparator(@NotNull SpaceColumn.Kind kind) {
        return (a, b) -> {
            // Empty values (not readable) below the others in both directions
            if (a == null || b == null) {
                if (a == b) {
                    return 0;
                }
                boolean descending = !sorter.getSortKeys().isEmpty() && sorter.getSortKeys().get(0).getSortOrder() == SortOrder.DESCENDING;
                return (a == null ? 1 : -1) * (descending ? -1 : 1);
            }
            if (kind == SpaceColumn.Kind.BYTES_LIMIT) {
                // Unlimited is the largest limit
                long la = ((Number) a).longValue() == SpaceColumn.UNLIMITED ? Long.MAX_VALUE : ((Number) a).longValue();
                long lb = ((Number) b).longValue() == SpaceColumn.UNLIMITED ? Long.MAX_VALUE : ((Number) b).longValue();
                return Long.compare(la, lb);
            }
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
                return ca.compareTo(b);
            }
            return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
        };
    }

    /**
     * Column widths from the header and the first rows, capped so that long texts (paths, messages) do not take the window.
     */
    private void packColumns() {
        FontMetrics metrics = getFontMetrics(getFont());
        int padding = UIScale.scale(18);
        int rowsToMeasure = Math.min(model.rows.size(), 60);
        for (int column = 0; column < model.columns.size(); column++) {
            int width = metrics.stringWidth(model.getColumnName(column)) + padding + UIScale.scale(12);
            for (int row = 0; row < rowsToMeasure; row++) {
                width = Math.max(width, metrics.stringWidth(format(model.columns.get(column), model.rows.get(row)[column])) + padding);
            }
            TableColumn tableColumn = getColumnModel().getColumn(column);
            int preferred = Math.max(UIScale.scale(MIN_COLUMN_WIDTH), Math.min(width, UIScale.scale(MAX_COLUMN_WIDTH)));
            tableColumn.setPreferredWidth(preferred);
        }
    }

    /**
     * With {@code fillWidth} the columns keep their measured widths and the last one takes the rest. JTable itself spreads
     * the difference over all columns by their preferred widths, which cut the time and level columns of a log.
     */
    @Override
    public void doLayout() {
        int count = getColumnModel().getColumnCount();
        if (!fillWidth || count == 0 || getTableHeader() != null && getTableHeader().getResizingColumn() != null) {
            super.doLayout();
            return;
        }
        int used = 0;
        for (int i = 0; i < count - 1; i++) {
            TableColumn column = getColumnModel().getColumn(i);
            column.setWidth(column.getPreferredWidth());
            used += column.getPreferredWidth();
        }
        getColumnModel().getColumn(count - 1).setWidth(Math.max(UIScale.scale(FILL_COLUMN_WIDTH), getWidth() - used));
    }

    /**
     * Display text of a value: sizes in units, counts with digit grouping, percents with one decimal, texts on one line.
     */
    @NotNull
    static String format(@NotNull SpaceColumn column, @Nullable Object value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return switch (column.kind()) {
            case BYTES -> SpaceFormat.bytes(((Number) value).longValue());
            case BYTES_LIMIT -> ((Number) value).longValue() == SpaceColumn.UNLIMITED ? AdminViewMessages.admin_unlimited
                : SpaceFormat.bytes(((Number) value).longValue());
            case COUNT -> NumberFormat.getIntegerInstance().format(((Number) value).longValue());
            case PERCENT -> {
                NumberFormat format = NumberFormat.getNumberInstance();
                format.setMinimumFractionDigits(1);
                format.setMaximumFractionDigits(1);
                yield format.format(((Number) value).doubleValue()) + " %"; //$NON-NLS-1$
            }
            case TEXT -> String.valueOf(value).replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    /**
     * The exact value: bytes and counts as plain numbers, texts as shown.
     */
    @NotNull
    private static String exact(@NotNull SpaceColumn column, @Nullable Object value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return switch (column.kind()) {
            case BYTES, COUNT -> String.valueOf(((Number) value).longValue());
            case BYTES_LIMIT -> ((Number) value).longValue() == SpaceColumn.UNLIMITED ? AdminViewMessages.admin_unlimited
                : String.valueOf(((Number) value).longValue());
            case PERCENT -> String.valueOf(((Number) value).doubleValue());
            case TEXT -> format(column, value);
        };
    }

    /**
     * Copies the selected rows (all rows if none is selected) as tab separated lines in the column order of the view.
     */
    private void copy(boolean headers, boolean exactValues) {
        int[] viewRows = getSelectedRows();
        if (viewRows.length == 0) {
            viewRows = new int[getRowCount()];
            for (int i = 0; i < viewRows.length; i++) {
                viewRows[i] = i;
            }
        }
        StringBuilder text = new StringBuilder();
        if (headers) {
            for (int viewColumn = 0; viewColumn < getColumnCount(); viewColumn++) {
                text.append(viewColumn == 0 ? "" : "\t").append(getColumnName(viewColumn)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            text.append('\n');
        }
        for (int viewRow : viewRows) {
            Object[] row = model.rows.get(convertRowIndexToModel(viewRow));
            for (int viewColumn = 0; viewColumn < getColumnCount(); viewColumn++) {
                int column = convertColumnIndexToModel(viewColumn);
                SpaceColumn spaceColumn = model.columns.get(column);
                text.append(viewColumn == 0 ? "" : "\t") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(exactValues ? exact(spaceColumn, row[column]) : format(spaceColumn, row[column]));
            }
            text.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    private final class GridModel extends AbstractTableModel {
        private List<SpaceColumn> columns = new ArrayList<>();
        private List<Object[]> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).label();
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }
    }

    private final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            SpaceColumn spaceColumn = model.columns.get(convertColumnIndexToModel(column));
            String text = format(spaceColumn, value);
            super.getTableCellRendererComponent(table, text, isSelected, false, row, column);
            setHorizontalAlignment(spaceColumn.kind() == SpaceColumn.Kind.TEXT ? SwingConstants.LEFT : SwingConstants.RIGHT);
            setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(6)));
            String toolTip = null;
            if (value instanceof Number number && (spaceColumn.kind() == SpaceColumn.Kind.BYTES
                || spaceColumn.kind() == SpaceColumn.Kind.BYTES_LIMIT && number.longValue() != SpaceColumn.UNLIMITED)) {
                // The exact size behind the rounded unit
                toolTip = NLS.bind(AdminViewMessages.admin_bytes_tip, SpaceFormat.exact(number.longValue()));
            } else if (text.length() > 40) {
                toolTip = text.length() > MAX_TOOL_TIP ? text.substring(0, MAX_TOOL_TIP) + "…" : text; //$NON-NLS-1$
            }
            setToolTipText(toolTip);
            return this;
        }
    }
}
