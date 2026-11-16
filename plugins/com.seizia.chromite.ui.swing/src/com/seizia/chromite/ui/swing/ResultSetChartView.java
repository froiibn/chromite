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
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 결과 표의 차트 보기: 선, 막대, 원형 차트와 가로축(범주) 열, 값 열, 행 범위를 고르고 PNG로 저장한다. 그림은 {@link ResultSetChart}가 그린다.
 *
 * 설계:
 * <ul>
 *     <li>차트는 표에 지금 읽혀 있는 행으로 그린다(다시 읽지 않음). 더 읽을 행이 있으면 알림 줄에 적어 "Fetch all"로 모두 읽은 뒤 그리게 한다.
 *     값은 수신기가 남긴 칸의 원래 값({@link Number}, 날짜)을 쓰고, 가로축 글자는 표의 표시 문자열을 써서 표와 같은 형식으로 보인다.</li>
 *     <li>차트 보기로 바꿀 때마다 표에서 고른 칸으로 처음 설정을 정한다. 칸을 둘 이상 골랐으면 고른 숫자 열이 값, 고른 숫자가 아닌 첫 열이 가로축,
 *     행을 둘 이상 골랐으면 고른 행만 그린다. 칸 하나만 골랐으면 결과 전체: 숫자가 아닌 첫 열이 가로축(없으면 행 번호), 숫자 열이 값이다.
 *     가로축이 날짜·숫자면 선, 아니면 막대로 시작한다.</li>
 *     <li>값 열은 8개까지 고른다(대시보드와 같은 검증된 8색, 색을 돌려 쓰지 않음). 원형은 첫 값 열 하나만 그린다.</li>
 *     <li>선 차트에서 가로축 열이 숫자·날짜이고 모든 행에 값이 있으면 값에 비례한 위치(가로축 값 순서)로, 아니면 행 순서의 범주로 그린다.</li>
 *     <li>PNG는 화면에 보이는 크기와 배율 그대로 저장한다(마우스 강조는 빼고). 숫자 표 대신 그림만 남으므로 값은 표나 내보내기로 본다.</li>
 * </ul>
 * EDT에서만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-result-charts-spatial.md)
 * - 2026-11-12 차트 종류 단추(선·막대·원)의 좌우 여백을 {@link UiSizes#TEXT_TOOL_BUTTON_STYLE}로: 아이콘 단추 여백(4)이라 눌린 바탕과 글자 사이가 거의 없었다
 *   (앱 변경 #71 e, docs/chromite/design-spec.md "치수 토큰")
 */
final class ResultSetChartView extends JPanel {

    /**
     * The viewer's access to the rows of the grid.
     */
    interface Source {
        @NotNull
        DBDAttributeBinding[] getBindings();

        @NotNull
        String[] getColumnNames();

        @NotNull
        boolean[] getNumericColumns();

        int getRowCount();

        /**
         * The original value of a cell (a number, a date…), or null for NULL and values that are not kept.
         */
        @Nullable
        Object getValue(int row, int column);

        @Nullable
        Object getDisplayValue(int row, int column);

        /**
         * Selected rows of the grid, in grid order.
         */
        @NotNull
        int[] getSelectedRows();

        /**
         * Selected columns of the grid, as column (model) indexes in display order.
         */
        @NotNull
        int[] getSelectedColumns();

        /**
         * Whether the query has rows that are not read yet.
         */
        boolean hasMoreRows();
    }

    static final int MAX_SERIES = 8;
    private static final int ROW_NUMBER = -1;

    private record Column(int index, @NotNull String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Path lastDirectory;

    private final Source source;
    private final ResultSetChart chart = new ResultSetChart();
    private final JToggleButton lineButton;
    private final JToggleButton barButton;
    private final JToggleButton pieButton;
    private final JComboBox<Column> xCombo = new JComboBox<>();
    private final JButton valuesButton = new JButton();
    private final JComboBox<String> rowsCombo = new JComboBox<>();
    private final JButton saveButton = new JButton(EditorMessages.editor_chart_save_png);
    private final JLabel noteLabel = new JLabel();

    private ResultSetChart.Type type = ResultSetChart.Type.BAR;
    private final Set<Integer> valueColumns = new LinkedHashSet<>();
    private int[] selectedRows = new int[0];
    // While the controls are filled from the code: their listeners do nothing
    private boolean updating;

    ResultSetChartView(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;

        lineButton = createTypeButton(EditorMessages.editor_chart_line, ResultSetChart.Type.LINE);
        barButton = createTypeButton(EditorMessages.editor_chart_bar, ResultSetChart.Type.BAR);
        pieButton = createTypeButton(EditorMessages.editor_chart_pie, ResultSetChart.Type.PIE);
        ButtonGroup typeGroup = new ButtonGroup();
        typeGroup.add(lineButton);
        typeGroup.add(barButton);
        typeGroup.add(pieButton);

        xCombo.addActionListener(e -> {
            if (!updating) {
                refresh();
            }
        });
        rowsCombo.addActionListener(e -> {
            if (!updating) {
                refresh();
            }
        });
        valuesButton.addActionListener(e -> showValuesMenu());
        valuesButton.setToolTipText(EditorMessages.editor_chart_values_tip);
        saveButton.addActionListener(e -> savePng());
        noteLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), UIScale.scale(2)));
        bar.add(lineButton);
        bar.add(barButton);
        bar.add(pieButton);
        bar.add(Box.createHorizontalStrut(UIScale.scale(8)));
        bar.add(new JLabel(EditorMessages.editor_chart_x_axis));
        bar.add(xCombo);
        bar.add(valuesButton);
        bar.add(new JLabel(EditorMessages.editor_chart_rows));
        bar.add(rowsCombo);
        bar.add(saveButton);
        // The notes under the chart: in the control row they would be cut off in a narrow window
        noteLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)));

        add(bar, BorderLayout.NORTH);
        add(chart, BorderLayout.CENTER);
        add(noteLabel, BorderLayout.SOUTH);
    }

    @NotNull
    private JToggleButton createTypeButton(@NotNull String text, @NotNull ResultSetChart.Type buttonType) {
        JToggleButton button = new JToggleButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, UiSizes.TEXT_TOOL_BUTTON_STYLE);
        button.setFocusable(false);
        button.addActionListener(e -> {
            if (!updating) {
                type = buttonType;
                refresh();
            }
        });
        return button;
    }

    //////////////////////////////////////////////////////////
    // Setup from the grid

    /**
     * Chooses the axes, value columns and rows from the grid selection (the chart view is being shown).
     */
    void configureFromSelection() {
        DBDAttributeBinding[] bindings = source.getBindings();
        boolean[] numeric = source.getNumericColumns();
        int[] rows = source.getSelectedRows();
        int[] columns = source.getSelectedColumns();
        // One cell selected (the usual state): the whole result
        boolean useSelection = rows.length * columns.length > 1;

        List<Integer> selectedNumeric = new ArrayList<>();
        int x = ROW_NUMBER;
        if (useSelection) {
            for (int column : columns) {
                if (numeric[column]) {
                    selectedNumeric.add(column);
                } else if (x == ROW_NUMBER) {
                    x = column;
                }
            }
        }
        if (x == ROW_NUMBER) {
            // The first label-like column of the result (often a name or a date), else the row number
            for (int i = 0; i < bindings.length; i++) {
                if (!numeric[i]) {
                    x = i;
                    break;
                }
            }
        }
        valueColumns.clear();
        List<Integer> candidates = selectedNumeric;
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>();
            for (int i = 0; i < numeric.length; i++) {
                if (numeric[i]) {
                    candidates.add(i);
                }
            }
        }
        for (int column : candidates) {
            if (column != x && valueColumns.size() < MAX_SERIES) {
                valueColumns.add(column);
            }
        }
        selectedRows = useSelection && rows.length > 1 ? rows.clone() : new int[0];
        type = x != ROW_NUMBER && (numeric[x] || bindings[x].getDataKind() == DBPDataKind.DATETIME)
            ? ResultSetChart.Type.LINE : ResultSetChart.Type.BAR;

        updating = true;
        try {
            xCombo.removeAllItems();
            xCombo.addItem(new Column(ROW_NUMBER, EditorMessages.editor_chart_row_number));
            String[] names = source.getColumnNames();
            Column selected = null;
            for (int i = 0; i < names.length; i++) {
                Column column = new Column(i, names[i]);
                xCombo.addItem(column);
                if (i == x) {
                    selected = column;
                }
            }
            xCombo.setSelectedIndex(selected == null ? 0 : x + 1);
            rowsCombo.removeAllItems();
            rowsCombo.addItem(EditorMessages.editor_chart_rows_all);
            if (selectedRows.length > 1) {
                rowsCombo.addItem(NLS.bind(EditorMessages.editor_chart_rows_selected, String.format("%,d", selectedRows.length))); //$NON-NLS-1$
                rowsCombo.setSelectedIndex(1);
            }
            rowsCombo.setEnabled(selectedRows.length > 1);
        } finally {
            updating = false;
        }
        refresh();
    }

    //////////////////////////////////////////////////////////
    // Chart data

    /**
     * Draws the chart again from the grid's rows (the rows changed, or a control).
     */
    void refresh() {
        updating = true;
        try {
            lineButton.setSelected(type == ResultSetChart.Type.LINE);
            barButton.setSelected(type == ResultSetChart.Type.BAR);
            pieButton.setSelected(type == ResultSetChart.Type.PIE);
        } finally {
            updating = false;
        }
        String[] names = source.getColumnNames();
        boolean[] numeric = source.getNumericColumns();
        // Columns may have changed since the setup (another query in the same viewer does not happen, but be safe)
        valueColumns.removeIf(column -> column < 0 || column >= numeric.length || !numeric[column]);
        List<String> valueNames = new ArrayList<>();
        for (int column : valueColumns) {
            valueNames.add(names[column]);
        }
        valuesButton.setText(valueNames.isEmpty()
            ? EditorMessages.editor_chart_values_none
            : NLS.bind(EditorMessages.editor_chart_values, String.join(", ", valueNames)) + " ▾"); //$NON-NLS-1$ //$NON-NLS-2$

        boolean anyNumeric = false;
        for (boolean value : numeric) {
            anyNumeric |= value;
        }
        valuesButton.setEnabled(anyNumeric);
        List<String> notes = new ArrayList<>();
        ResultSetChart.Data data = null;
        String message = null;
        int[] rows = getRows();
        if (!anyNumeric) {
            message = EditorMessages.editor_chart_no_numeric;
        } else if (valueColumns.isEmpty()) {
            message = EditorMessages.editor_chart_choose_values;
        } else if (rows.length == 0) {
            message = EditorMessages.editor_chart_no_rows;
        } else {
            data = buildData(rows, notes);
        }
        notes.add(0, NLS.bind(rows.length == 1 ? EditorMessages.editor_chart_note_rows_one : EditorMessages.editor_chart_note_rows_many,
            String.format("%,d", rows.length))); //$NON-NLS-1$
        if (source.hasMoreRows() && rows.length == source.getRowCount()) {
            notes.add(EditorMessages.editor_chart_note_more_rows);
        }
        noteLabel.setText(String.join(" · ", notes)); //$NON-NLS-1$
        chart.setChart(data, message);
        saveButton.setEnabled(chart.hasChart());
    }

    @NotNull
    private int[] getRows() {
        int count = source.getRowCount();
        if (rowsCombo.getSelectedIndex() == 1 && selectedRows.length > 1) {
            return Arrays.stream(selectedRows).filter(row -> row < count).toArray();
        }
        int[] rows = new int[count];
        for (int i = 0; i < count; i++) {
            rows[i] = i;
        }
        return rows;
    }

    @NotNull
    private ResultSetChart.Data buildData(@NotNull int[] rows, @NotNull List<String> notes) {
        Column xItem = (Column) xCombo.getSelectedItem();
        int x = xItem == null ? ROW_NUMBER : xItem.index();
        String[] names = source.getColumnNames();
        DBDAttributeBinding[] bindings = source.getBindings();

        // A proportional X axis for a line over numbers or dates that every row has
        double[] xValues = null;
        boolean timeAxis = false;
        if (type == ResultSetChart.Type.LINE && x != ROW_NUMBER) {
            timeAxis = bindings[x].getDataKind() == DBPDataKind.DATETIME;
            if (timeAxis || source.getNumericColumns()[x]) {
                xValues = new double[rows.length];
                for (int i = 0; i < rows.length && xValues != null; i++) {
                    double value = toNumber(source.getValue(rows[i], x));
                    if (Double.isNaN(value)) {
                        xValues = null;
                    } else {
                        xValues[i] = value;
                    }
                }
            }
            if (xValues != null) {
                // Points in X order, so the line does not jump back
                Integer[] order = new Integer[rows.length];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                double[] keys = xValues;
                Arrays.sort(order, (a, b) -> Double.compare(keys[a], keys[b]));
                int[] sortedRows = new int[rows.length];
                double[] sortedX = new double[rows.length];
                for (int i = 0; i < order.length; i++) {
                    sortedRows[i] = rows[order[i]];
                    sortedX[i] = keys[order[i]];
                }
                rows = sortedRows;
                xValues = sortedX;
            }
        }

        String[] labels = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            if (x == ROW_NUMBER) {
                labels[i] = String.valueOf(rows[i] + 1);
            } else {
                Object display = source.getDisplayValue(rows[i], x);
                labels[i] = display == null ? "" : display.toString(); //$NON-NLS-1$
            }
        }

        List<ResultSetChart.Series> series = new ArrayList<>();
        for (int column : valueColumns) {
            double[] values = new double[rows.length];
            for (int i = 0; i < rows.length; i++) {
                values[i] = toNumber(source.getValue(rows[i], column));
            }
            series.add(new ResultSetChart.Series(names[column], values));
            if (type == ResultSetChart.Type.PIE) {
                if (valueColumns.size() > 1) {
                    notes.add(NLS.bind(EditorMessages.editor_chart_note_pie_first, names[column]));
                }
                int left = 0;
                for (double value : values) {
                    if (!(value > 0)) {
                        left++;
                    }
                }
                if (left > 0) {
                    notes.add(NLS.bind(EditorMessages.editor_chart_note_pie_left_out, String.format("%,d", left))); //$NON-NLS-1$
                }
                break;
            }
        }
        String xTitle = x == ROW_NUMBER ? EditorMessages.editor_chart_row_number : names[x];
        return new ResultSetChart.Data(type, xTitle, labels, xValues, timeAxis && xValues != null, series);
    }

    /**
     * A cell value as a number: numbers as they are, dates as epoch milliseconds, anything else NaN.
     */
    private static double toNumber(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        return Double.NaN;
    }

    //////////////////////////////////////////////////////////
    // Controls

    private void showValuesMenu() {
        JPopupMenu menu = new JPopupMenu();
        String[] names = source.getColumnNames();
        boolean[] numeric = source.getNumericColumns();
        List<JCheckBoxMenuItem> items = new ArrayList<>();
        for (int i = 0; i < numeric.length; i++) {
            if (!numeric[i]) {
                continue;
            }
            int column = i;
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(names[i], valueColumns.contains(i));
            // Several columns are checked in a row: the menu stays open
            item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE); //$NON-NLS-1$
            item.addActionListener(e -> {
                if (item.isSelected()) {
                    valueColumns.add(column);
                } else {
                    valueColumns.remove(column);
                }
                // Keep the column order of the result, not the order of the clicks
                List<Integer> ordered = new ArrayList<>(valueColumns);
                ordered.sort(Integer::compare);
                valueColumns.clear();
                valueColumns.addAll(ordered);
                updateLimit(items);
                refresh();
            });
            items.add(item);
            menu.add(item);
        }
        if (items.size() > MAX_SERIES) {
            menu.addSeparator();
            JMenuItem limit = new JMenuItem(NLS.bind(EditorMessages.editor_chart_values_limit, MAX_SERIES));
            limit.setEnabled(false);
            menu.add(limit);
        }
        updateLimit(items);
        menu.show(valuesButton, 0, valuesButton.getHeight());
    }

    /**
     * At most eight value columns: the unchecked ones are disabled while eight are checked.
     */
    private void updateLimit(@NotNull List<JCheckBoxMenuItem> items) {
        boolean full = valueColumns.size() >= MAX_SERIES;
        for (JCheckBoxMenuItem item : items) {
            item.setEnabled(item.isSelected() || !full);
        }
    }

    private void savePng() {
        if (!chart.hasChart() || chart.getWidth() <= 0 || chart.getHeight() <= 0) {
            return;
        }
        String title = EditorMessages.editor_chart_save_png_title;
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory.toFile() : null);
        chooser.setDialogTitle(title);
        FileNameExtensionFilter filter = new FileNameExtensionFilter(EditorMessages.editor_chart_png_filter, "png"); //$NON-NLS-1$
        chooser.setFileFilter(filter);
        chooser.setSelectedFile(new File("chart.png")); //$NON-NLS-1$
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase().endsWith(".png")) { //$NON-NLS-1$
            file = file.resolveSibling(file.getFileName() + ".png"); //$NON-NLS-1$
        }
        if (Files.exists(file) && !SwingPlatformUI.confirmChange(this, NLS.bind(EditorMessages.editor_export_replace_confirm, file.getFileName()),
            title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        lastDirectory = file.toAbsolutePath().getParent();
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        double scale = configuration != null ? Math.max(1, configuration.getDefaultTransform().getScaleX()) : 1;
        BufferedImage image = chart.createImage(getBackground(), scale);
        try {
            ImageIO.write(image, "png", file.toFile()); //$NON-NLS-1$
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, NLS.bind(EditorMessages.editor_export_error_writing, file.getFileName()) + "\n" + ErrorText.describe(e), //$NON-NLS-1$
                title, JOptionPane.ERROR_MESSAGE);
        }
    }
}
