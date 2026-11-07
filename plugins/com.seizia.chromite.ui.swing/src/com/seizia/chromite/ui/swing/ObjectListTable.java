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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.runtime.properties.DataSourcePropertyFilter;
import com.seizia.chromite.runtime.properties.ObjectAttributeDescriptor;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 모델 객체 목록을 {@code @Property} 열로 보여 주는 표 (세션, 잠금, 잠금 상세).
 *
 * 설계: SWT의 {@code ObjectListControl}(세션 표 {@code SessionTable}, 잠금 표 {@code LockTable}의 바탕)과 같은 규칙으로 열을 만든다.
 * <ul>
 *     <li>열은 객체 클래스의 {@code @Property} 게터({@link ObjectAttributeDescriptor#extractAnnotations})를 연결의 속성 필터
 *     ({@link DataSourcePropertyFilter}: 비싼 속성 읽기 설정)로 거른 것 중 {@code viewable}이고 숨김이 아닌 것이다. 나머지 속성은
 *     화면의 Details 탭에서 본다. {@code visibleIf}가 있는 속성(MySQL "Show performance info" 열)은 한 행이라도 보이면,
 *     {@code optional} 속성은 한 행이라도 값이 있으면 열을 둔다.</li>
 *     <li>열 구성이 그대로면 다시 읽어도 열 너비와 정렬을 그대로 두고, 고른 행은 키({@code keyOf})가 같은 행으로 다시 고른다
 *     (자동 새로 고침 중에도 선택이 유지되도록, SWT {@code SessionLoadVisualizer}와 같음).</li>
 *     <li>값의 글자는 {@link DBValueFormatting}(UI 형식)이고, 여러 줄 글자(쿼리)는 한 줄로 보인다. 정렬은 원래 값(숫자, 날짜)으로 한다.</li>
 *     <li>찾기 글자({@link #setSearchText})는 SWT 세션 편집기의 검색처럼 어느 열의 글자에든 들어 있는 행만 남긴다(대소문자 무시).</li>
 *     <li>암호 속성은 값을 가린다({@link #maskPassword}, SWT {@code ObjectListControl}과 같음). 모델에서 값을 읽는 자리에서 가리므로
 *     표시, 도구 설명, 찾기, 정렬 어디에도 실제 값이 가지 않는다. 같은 함수를 속성 창·Details 표·세션 상세도 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: Activity Monitor, 잠금 보기 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 암호 속성 가리기 (docs/chromite/password-handling.md)
 */
final class ObjectListTable<T> extends JTable {

    private static final Log log = Log.getLog(ObjectListTable.class);
    private static final int MAX_COLUMN_WIDTH = 360;
    private static final int MIN_COLUMN_WIDTH = 48;
    /**
     * Shown instead of the value of a password property ({@link #maskPassword}).
     */
    static final String MASKED_VALUE = "********"; //$NON-NLS-1$

    private final ObjectListModel model = new ObjectListModel();
    private final TableRowSorter<ObjectListModel> sorter = new TableRowSorter<>(model);
    private final Function<T, Object> keyOf;
    @Nullable
    private final DBPDataSourceContainer container;
    @Nullable
    private Predicate<T> extraFilter;
    private String searchText = "";

    /**
     * @param keyOf     identity of a row across reloads (session id, lock id)
     * @param container connection whose property filter applies, or null
     */
    ObjectListTable(@NotNull Function<T, Object> keyOf, @Nullable DBPDataSourceContainer container) {
        this.keyOf = keyOf;
        this.container = container;
        setModel(model);
        setRowSorter(sorter);
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setFillsViewportHeight(true);
        setShowHorizontalLines(false);
        setShowVerticalLines(false);
        setIntercellSpacing(new Dimension(0, 0));
        getTableHeader().setReorderingAllowed(true);
        getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setDefaultRenderer(Object.class, new ValueRenderer());
    }

    /**
     * Fills the viewport when the columns are narrower than it (no empty horizontal scroll bar), scrolls otherwise.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && getPreferredSize().width < viewport.getWidth();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // The renderer is not in the component tree; a new one picks up the colors of the new theme
        if (model != null) {
            setDefaultRenderer(Object.class, new ValueRenderer());
        }
    }

    /**
     * Shows a new list of objects, keeping the columns (widths, sort) when they are the same and selecting the rows
     * that were selected before.
     *
     * @param type base type of the objects; its properties are the columns
     */
    void setObjects(@NotNull Collection<? extends T> objects, @NotNull Class<?> type) {
        List<T> selected = getSelectedObjects();
        Set<Object> selectedKeys = new HashSet<>();
        for (T object : selected) {
            selectedKeys.add(keyOf.apply(object));
        }
        List<ObjectPropertyDescriptor> columns = collectColumns(objects, type);
        boolean structureChanged = !sameColumns(columns, model.columns);
        model.columns = columns;
        model.rows = new ArrayList<>(objects);
        if (structureChanged) {
            List<? extends RowSorter.SortKey> sortKeys = sorter.getSortKeys();
            model.fireTableStructureChanged();
            installComparators();
            packColumns();
            if (!sortKeys.isEmpty() && sortKeys.get(0).getColumn() < columns.size()) {
                sorter.setSortKeys(sortKeys);
            }
        } else {
            model.fireTableDataChanged();
        }
        if (!selectedKeys.isEmpty()) {
            for (int row = 0; row < model.rows.size(); row++) {
                if (selectedKeys.contains(keyOf.apply(model.rows.get(row)))) {
                    int viewRow = convertRowIndexToView(row);
                    if (viewRow >= 0) {
                        addRowSelectionInterval(viewRow, viewRow);
                    }
                }
            }
        }
    }

    void clearObjects() {
        model.rows = new ArrayList<>();
        model.fireTableDataChanged();
    }

    @NotNull
    List<T> getObjects() {
        return model.rows;
    }

    @NotNull
    List<T> getSelectedObjects() {
        List<T> selected = new ArrayList<>();
        for (int viewRow : getSelectedRows()) {
            int row = convertRowIndexToModel(viewRow);
            if (row >= 0 && row < model.rows.size()) {
                selected.add(model.rows.get(row));
            }
        }
        return selected;
    }

    @Nullable
    T getSelectedObject() {
        int viewRow = getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int row = convertRowIndexToModel(viewRow);
        return row >= 0 && row < model.rows.size() ? model.rows.get(row) : null;
    }

    /**
     * Selects the row of an object (by key) and scrolls to it; clears the selection if it is not shown.
     */
    void selectObject(@Nullable T object) {
        clearSelection();
        if (object == null) {
            return;
        }
        Object key = keyOf.apply(object);
        for (int row = 0; row < model.rows.size(); row++) {
            if (Objects.equals(key, keyOf.apply(model.rows.get(row)))) {
                int viewRow = convertRowIndexToView(row);
                if (viewRow >= 0) {
                    setRowSelectionInterval(viewRow, viewRow);
                    scrollRectToVisible(getCellRect(viewRow, 0, true));
                }
                return;
            }
        }
    }

    /**
     * Shows only the rows with a column that contains the text (any case); an empty text shows all rows.
     */
    void setSearchText(@NotNull String text) {
        searchText = text.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    /**
     * An additional row filter (e.g. "only my sessions"), or null.
     */
    void setExtraFilter(@Nullable Predicate<T> filter) {
        extraFilter = filter;
        applyFilter();
    }

    private void applyFilter() {
        if (searchText.isEmpty() && extraFilter == null) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ObjectListModel, ? extends Integer> entry) {
                T object = model.rows.get(entry.getIdentifier());
                if (extraFilter != null && !extraFilter.test(object)) {
                    return false;
                }
                if (searchText.isEmpty()) {
                    return true;
                }
                for (int column = 0; column < model.columns.size(); column++) {
                    if (formatValue(model.getValueAt(entry.getIdentifier(), column)).toLowerCase(Locale.ROOT).contains(searchText)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    @NotNull
    private List<ObjectPropertyDescriptor> collectColumns(@NotNull Collection<? extends T> objects, @NotNull Class<?> type) {
        List<ObjectPropertyDescriptor> properties = ObjectAttributeDescriptor.extractAnnotations(
            null, List.of(type), new DataSourcePropertyFilter(container));
        List<ObjectPropertyDescriptor> columns = new ArrayList<>();
        for (ObjectPropertyDescriptor property : properties) {
            if (!property.isViewable() || property.isHidden()) {
                continue;
            }
            if (!objects.isEmpty() && objects.stream().noneMatch(object -> isVisible(property, object))) {
                continue;
            }
            // As ObjectListControl: an optional property is a column only if some row has a value
            if (property.isOptional() && objects.stream().allMatch(object -> readValue(property, object) == null)) {
                continue;
            }
            columns.add(property);
        }
        return columns;
    }

    private static boolean isVisible(@NotNull ObjectPropertyDescriptor property, @NotNull Object object) {
        try {
            return property.isPropertyVisible(object, object);
        } catch (Throwable e) {
            log.debug(e);
            return false;
        }
    }

    @Nullable
    private static Object readValue(@NotNull ObjectPropertyDescriptor property, @NotNull Object object) {
        try {
            return property.readValue(object, null, false);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean sameColumns(@NotNull List<ObjectPropertyDescriptor> a, @NotNull List<ObjectPropertyDescriptor> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getId().equals(b.get(i).getId()) || a.get(i).getDeclaringClass() != b.get(i).getDeclaringClass()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void installComparators() {
        Comparator<Object> comparator = (a, b) -> {
            if (a == null || b == null) {
                return a == null ? (b == null ? 0 : -1) : 1;
            }
            if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
                return ca.compareTo(b);
            }
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return formatValue(a).compareToIgnoreCase(formatValue(b));
        };
        for (int column = 0; column < model.columns.size(); column++) {
            sorter.setComparator(column, comparator);
        }
    }

    /**
     * Column widths from the header and the first rows, capped so that long texts (queries) do not take the window.
     */
    private void packColumns() {
        FontMetrics metrics = getFontMetrics(getFont());
        int padding = UIScale.scale(16);
        int rowsToMeasure = Math.min(model.rows.size(), 50);
        for (int column = 0; column < model.columns.size(); column++) {
            int width = metrics.stringWidth(model.getColumnName(column)) + padding + UIScale.scale(12);
            for (int row = 0; row < rowsToMeasure; row++) {
                width = Math.max(width, metrics.stringWidth(formatValue(model.getValueAt(row, column))) + padding);
            }
            TableColumn tableColumn = getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(Math.max(UIScale.scale(MIN_COLUMN_WIDTH), Math.min(width, UIScale.scale(MAX_COLUMN_WIDTH))));
        }
    }

    /**
     * The value to show for a property. A password property ({@code @Property(password = true)}, or the
     * {@code password} feature of a declared property) shows {@link #MASKED_VALUE} when it has a value, as the SWT
     * object lists did; an empty one stays empty. The real value never reaches the cell, its tool tip, the search or the
     * sort.
     */
    @Nullable
    static Object maskPassword(@NotNull DBPPropertyDescriptor property, @Nullable Object value) {
        if (value == null || !property.hasFeature(DBConstants.PROP_FEATURE_PASSWORD)) {
            return value;
        }
        return CommonUtils.isEmpty(CommonUtils.toString(value)) ? "" : MASKED_VALUE; //$NON-NLS-1$
    }

    /**
     * One-line display text of a property value.
     */
    @NotNull
    static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof String string ? string : DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI);
        return CommonUtils.notEmpty(text).replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private final class ObjectListModel extends AbstractTableModel {
        private List<ObjectPropertyDescriptor> columns = new ArrayList<>();
        private List<T> rows = new ArrayList<>();

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
            return columns.get(column).getDisplayName();
        }

        @Override
        public Object getValueAt(int row, int column) {
            try {
                ObjectPropertyDescriptor property = columns.get(column);
                return maskPassword(property, property.readValue(rows.get(row), null, true));
            } catch (Throwable e) {
                log.debug("Can't read property " + columns.get(column).getId(), e);
                return null;
            }
        }
    }

    private final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = formatValue(value);
            super.getTableCellRendererComponent(table, text, isSelected, false, row, column);
            setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(6)));
            // Full text of long values (queries) in the tool tip
            setToolTipText(text.length() > 40 ? text : null);
            return this;
        }
    }
}
