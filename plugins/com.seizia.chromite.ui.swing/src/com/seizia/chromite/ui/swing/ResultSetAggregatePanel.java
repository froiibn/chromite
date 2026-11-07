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
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.aggregate.FunctionAvg;
import com.seizia.chromite.model.data.aggregate.FunctionCount;
import com.seizia.chromite.model.data.aggregate.FunctionCountDistinct;
import com.seizia.chromite.model.data.aggregate.FunctionCountNulls;
import com.seizia.chromite.model.data.aggregate.FunctionMax;
import com.seizia.chromite.model.data.aggregate.FunctionMedian;
import com.seizia.chromite.model.data.aggregate.FunctionMin;
import com.seizia.chromite.model.data.aggregate.FunctionMode;
import com.seizia.chromite.model.data.aggregate.FunctionSum;
import com.seizia.chromite.model.data.aggregate.IAggregateFunction;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 집계 패널(SWT {@code AggregateColumnsPanel}): 결과 표에서 고른 칸을 열마다(또는 모두 합쳐) 고른 함수로 집계해 표로 보인다.
 *
 * 설계:
 * <ul>
 *     <li>계산은 모델 계층의 집계 함수({@code com.seizia.chromite.model.data.aggregate}, SWT 패널과 같은 것)를 쓴다. 함수는 메뉴에서 고르고
 *     (기본: 개수·합계·평균·최솟값·최댓값, SWT 기본과 같음) 이 실행 동안 기억한다.</li>
 *     <li>상태 줄 집계({@link ResultSetAggregates})와 같은 규칙: 합계·평균·중앙값에는 숫자 값({@link Number})만 넣는다(코드 열의 "001"이 합계에
 *     들어가지 않게). 최솟값·최댓값은 숫자, 날짜, 글자도 비교하고 글자·날짜는 표에 보이는 글자로 쓴다. 개수·서로 다른 개수·최빈값은 NULL이 아닌 값,
 *     NULL 개수는 NULL만 센다. SWT 패널은 글자도 숫자로 읽어 보지만 여기서는 하지 않는다.</li>
 *     <li>"열마다"를 끄면 고른 칸 전체를 한 줄로 집계한다(SWT "Group by columns"를 끈 것과 같음). 그룹(값별) 집계는 하지 않는다.</li>
 *     <li>EDT에서 계산하므로 {@link #MAX_CELLS}칸까지만 센다(최빈값은 값 수의 제곱에 비례). 넘으면 아래 줄에 알린다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "집계 패널")
 */
final class ResultSetAggregatePanel extends JPanel {

    private static final int MAX_CELLS = 100_000;

    /**
     * The aggregate functions of the panel, in column order.
     */
    enum Function {
        COUNT(FunctionCount::new),
        COUNT_DISTINCT(FunctionCountDistinct::new),
        NULLS(FunctionCountNulls::new),
        SUM(FunctionSum::new),
        AVG(FunctionAvg::new),
        MIN(FunctionMin::new),
        MAX(FunctionMax::new),
        MEDIAN(FunctionMedian::new),
        MODE(FunctionMode::new);

        private final Supplier<IAggregateFunction> factory;

        Function(@NotNull Supplier<IAggregateFunction> factory) {
            this.factory = factory;
        }

        @NotNull
        String getLabel() {
            return switch (this) {
                case COUNT -> GridNavMessages.gridnav_agg_count;
                case COUNT_DISTINCT -> GridNavMessages.gridnav_agg_count_distinct;
                case NULLS -> GridNavMessages.gridnav_agg_nulls;
                case SUM -> GridNavMessages.gridnav_agg_sum;
                case AVG -> GridNavMessages.gridnav_agg_avg;
                case MIN -> GridNavMessages.gridnav_agg_min;
                case MAX -> GridNavMessages.gridnav_agg_max;
                case MEDIAN -> GridNavMessages.gridnav_agg_median;
                case MODE -> GridNavMessages.gridnav_agg_mode;
            };
        }

        /**
         * Whether a value goes into this function (see the class comment).
         */
        boolean accepts(@Nullable Object value) {
            boolean isNull = value == null || DBUtils.isNullValue(value);
            return switch (this) {
                case NULLS -> true;
                case COUNT, COUNT_DISTINCT, MODE -> !isNull;
                case SUM, AVG, MEDIAN -> value instanceof Number;
                case MIN, MAX -> !isNull && value instanceof Comparable<?>;
            };
        }
    }

    /**
     * The grid the panel reads.
     */
    interface Source {
        @Nullable
        JTable getTable();

        /**
         * The original value of a cell (table row, model column), or null.
         */
        @Nullable
        Object getValue(int row, int column);

        /**
         * The text the grid shows for a cell.
         */
        @NotNull
        String getDisplayText(int row, int column);

        @NotNull
        String getColumnName(int column);
    }

    /**
     * Functions chosen in this session (all panels).
     */
    private static final Set<Function> chosenFunctions = EnumSet.of(Function.COUNT, Function.SUM, Function.AVG, Function.MIN, Function.MAX);
    private static boolean byColumn = true;

    private final Source source;
    private final ResultModel model = new ResultModel();
    private final JTable resultTable = new JTable(model);
    private final JLabel noteLabel = new JLabel();

    ResultSetAggregatePanel(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        JButton functionsButton = new JButton(GridNavMessages.gridnav_agg_functions);
        functionsButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        functionsButton.addActionListener(e -> showFunctionsMenu(functionsButton));
        toolBar.add(functionsButton);
        JCheckBox byColumnCheck = new JCheckBox(GridNavMessages.gridnav_agg_by_column, byColumn);
        byColumnCheck.setToolTipText(GridNavMessages.gridnav_agg_by_column_tip);
        byColumnCheck.addActionListener(e -> {
            byColumn = byColumnCheck.isSelected();
            refresh();
        });
        toolBar.add(byColumnCheck);
        add(toolBar, BorderLayout.NORTH);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setCellSelectionEnabled(true);
        resultTable.getTableHeader().setReorderingAllowed(false);
        resultTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                // Values right-aligned like the numbers of the grid, the column names left
                setHorizontalAlignment(column == 0 ? SwingConstants.LEFT : SwingConstants.RIGHT);
                return this;
            }
        });
        add(new JScrollPane(resultTable), BorderLayout.CENTER);
        noteLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        noteLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(noteLabel, BorderLayout.SOUTH);
    }

    private void showFunctionsMenu(@NotNull JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (Function function : Function.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(function.getLabel(), chosenFunctions.contains(function));
            item.addActionListener(e -> {
                if (item.isSelected()) {
                    chosenFunctions.add(function);
                } else if (chosenFunctions.size() > 1) {
                    chosenFunctions.remove(function);
                }
                refresh();
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * Computes the aggregates of the grid's selection again.
     */
    void refresh() {
        List<Function> functions = new ArrayList<>(chosenFunctions);
        List<String> names = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        JTable table = source.getTable();
        boolean capped = false;
        if (table != null && table.getSelectedRowCount() > 0 && table.getSelectedColumnCount() > 0) {
            int[] selectedRows = table.getSelectedRows();
            int[] selectedColumns = table.getSelectedColumns();
            int counted = 0;
            Accumulator all = byColumn ? null : new Accumulator(functions);
            for (int viewColumn : selectedColumns) {
                int column = table.convertColumnIndexToModel(viewColumn);
                Accumulator accumulator = all != null ? all : new Accumulator(functions);
                for (int row : selectedRows) {
                    if (counted >= MAX_CELLS) {
                        capped = true;
                        break;
                    }
                    counted++;
                    accumulator.add(source.getValue(row, column), () -> source.getDisplayText(row, column));
                }
                if (all == null) {
                    names.add(source.getColumnName(column));
                    rows.add(accumulator.results());
                }
                if (capped) {
                    break;
                }
            }
            if (all != null) {
                names.add(NLS.bind(GridNavMessages.gridnav_agg_selection, selectedColumns.length));
                rows.add(all.results());
            }
        }
        model.set(functions, names, rows);
        noteLabel.setText(table == null || rows.isEmpty() ? GridNavMessages.gridnav_agg_select_cells
            : capped ? NLS.bind(GridNavMessages.gridnav_agg_capped, String.format("%,d", MAX_CELLS)) : " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The functions of one row of the panel.
     */
    private static final class Accumulator {
        private final List<Function> functions;
        private final IAggregateFunction[] instances;
        private final int[] counts;
        // Min and max of texts and dates are shown as the grid shows them
        private final Map<Object, String> displays = new IdentityHashMap<>();

        Accumulator(@NotNull List<Function> functions) {
            this.functions = functions;
            this.instances = new IAggregateFunction[functions.size()];
            this.counts = new int[functions.size()];
            for (int i = 0; i < instances.length; i++) {
                instances[i] = functions.get(i).factory.get();
            }
        }

        void add(@Nullable Object value, @NotNull Supplier<String> display) {
            for (int i = 0; i < instances.length; i++) {
                Function function = functions.get(i);
                if (!function.accepts(value)) {
                    continue;
                }
                // Texts and dates are compared as they are, not parsed as numbers (aggregateAsStrings)
                if (instances[i].accumulate(value, !(value instanceof Number))) {
                    counts[i]++;
                    if ((function == Function.MIN || function == Function.MAX || function == Function.MODE)
                        && value != null && !(value instanceof Number)) {
                        displays.computeIfAbsent(value, v -> display.get());
                    }
                }
            }
        }

        @NotNull
        Object[] results() {
            Object[] results = new Object[instances.length];
            for (int i = 0; i < instances.length; i++) {
                // Counting functions give 0 without values; the others have no result
                Function function = functions.get(i);
                boolean counting = function == Function.COUNT || function == Function.NULLS || function == Function.COUNT_DISTINCT;
                Object result = counts[i] == 0 && !counting ? null : instances[i].getResult(counts[i]);
                String shown = result == null ? null : displays.get(result);
                results[i] = shown != null ? shown : ResultSetAggregates.format(result);
            }
            return results;
        }
    }

    private static final class ResultModel extends AbstractTableModel {
        private List<Function> functions = List.of();
        private List<String> names = List.of();
        private List<Object[]> rows = List.of();

        void set(@NotNull List<Function> functions, @NotNull List<String> names, @NotNull List<Object[]> rows) {
            boolean structure = !functions.equals(this.functions);
            this.functions = functions;
            this.names = names;
            this.rows = rows;
            if (structure) {
                fireTableStructureChanged();
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return functions.size() + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? GridNavMessages.gridnav_agg_column : functions.get(column - 1).getLabel();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return columnIndex == 0 ? names.get(rowIndex) : rows.get(rowIndex)[columnIndex - 1];
        }
    }
}
