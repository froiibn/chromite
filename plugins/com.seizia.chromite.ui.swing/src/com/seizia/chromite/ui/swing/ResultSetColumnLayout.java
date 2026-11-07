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

import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 결과 표의 열 배치와 이동(SWT {@code grid.*} 명령): 열 숨기기·모두 보이기·빈 열 숨기기, 열 순서(머리글 끌기), 행·열로 가기, 확대·축소.
 *
 * 설계:
 * <ul>
 *     <li>숨기기는 표의 열 모델에서 {@link TableColumn}을 빼는 것이다(값과 편집 모델은 그대로라 저장·복사·내보내기는 모델 열 번호를 쓴다). 다시 보이면
 *     모델 순서 자리에 새 열을 넣는다. 적어도 한 열은 남긴다.</li>
 *     <li>정렬·다음 페이지·다시 읽기는 새 표를 만들므로({@link ResultSetViewerPanel}) 그 전 배치({@link State}: 보이는 열의 모델 번호 순서와 너비)를 옮겨 놓는다.
 *     열이 같은 결과(같은 열 이름)에만 옮긴다. 외래 키로 다른 테이블로 가면 처음 배치다.</li>
 *     <li>확대·축소(SWT {@code zoomIn}/{@code zoomOut}, Alt+0 / Alt+9)는 표의 글꼴과 행 높이를 10%씩 바꾼다. 이 결과 뷰어 안에서만 유지한다.</li>
 *     <li>행으로 가기(Ctrl+G)는 읽은 행의 번호(1부터), 열로 가기(Ctrl+Shift+G)는 이름으로 거르는 목록이다. 숨긴 열로 가면 그 열을 다시 보인다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "열 배치와 이동")
 */
final class ResultSetColumnLayout {

    private static final float ZOOM_STEP = 0.1f;
    static final int MIN_ZOOM = -5;
    static final int MAX_ZOOM = 10;

    /**
     * The visible columns of a grid in view order.
     *
     * @param names  the column names of the result (all columns, model order), to check that a new grid has the same columns
     * @param order  model indexes of the visible columns in view order
     * @param widths their widths
     */
    record State(@NotNull List<String> names, @NotNull int[] order, @NotNull int[] widths) {
    }

    private ResultSetColumnLayout() {
    }

    @NotNull
    static State capture(@NotNull JTable table) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < table.getModel().getColumnCount(); i++) {
            names.add(table.getModel().getColumnName(i));
        }
        TableColumnModel columns = table.getColumnModel();
        int[] order = new int[columns.getColumnCount()];
        int[] widths = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = columns.getColumn(i).getModelIndex();
            widths[i] = columns.getColumn(i).getWidth();
        }
        return new State(names, order, widths);
    }

    /**
     * Whether a grid shows the columns in their own order, all of them.
     */
    static boolean isNatural(@NotNull State state) {
        if (state.order().length != state.names().size()) {
            return false;
        }
        for (int i = 0; i < state.order().length; i++) {
            if (state.order()[i] != i) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts the columns of a new grid of the same result in the captured order, hidden columns left out.
     *
     * @param widths also take the captured widths (the same rows; a new read fits them again)
     * @return false if the grid has other columns (nothing changed)
     */
    static boolean restore(@NotNull JTable table, @NotNull State state, boolean widths) {
        int count = table.getModel().getColumnCount();
        if (count != state.names().size()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!state.names().get(i).equals(table.getModel().getColumnName(i))) {
                return false;
            }
        }
        TableColumnModel columns = table.getColumnModel();
        TableColumn[] byModel = new TableColumn[count];
        for (int i = 0; i < columns.getColumnCount(); i++) {
            TableColumn column = columns.getColumn(i);
            byModel[column.getModelIndex()] = column;
        }
        while (columns.getColumnCount() > 0) {
            columns.removeColumn(columns.getColumn(0));
        }
        for (int i = 0; i < state.order().length; i++) {
            TableColumn column = byModel[state.order()[i]];
            if (column != null) {
                if (widths) {
                    column.setPreferredWidth(state.widths()[i]);
                }
                columns.addColumn(column);
            }
        }
        return true;
    }

    /**
     * Hides the given view columns; at least one column stays.
     *
     * @return the number of hidden columns
     */
    static int hideColumns(@NotNull JTable table, @NotNull int[] viewColumns) {
        TableColumnModel columns = table.getColumnModel();
        List<TableColumn> hidden = new ArrayList<>();
        for (int viewColumn : viewColumns) {
            if (viewColumn >= 0 && viewColumn < columns.getColumnCount()) {
                hidden.add(columns.getColumn(viewColumn));
            }
        }
        if (hidden.size() >= columns.getColumnCount()) {
            hidden.removeLast();
        }
        table.clearSelection();
        hidden.forEach(columns::removeColumn);
        return hidden.size();
    }

    /**
     * Shows the hidden columns at the position of their model order.
     *
     * @return the number of columns shown again
     */
    static int showAllColumns(@NotNull JTable table) {
        int shown = 0;
        for (int modelColumn = 0; modelColumn < table.getModel().getColumnCount(); modelColumn++) {
            if (showColumn(table, modelColumn)) {
                shown++;
            }
        }
        return shown;
    }

    /**
     * Shows a hidden column after the visible columns that come before it in model order.
     *
     * @return whether it was hidden
     */
    static boolean showColumn(@NotNull JTable table, int modelColumn) {
        TableColumnModel columns = table.getColumnModel();
        int position = 0;
        for (int i = 0; i < columns.getColumnCount(); i++) {
            int index = columns.getColumn(i).getModelIndex();
            if (index == modelColumn) {
                return false;
            }
            if (index < modelColumn) {
                position = i + 1;
            }
        }
        TableColumn column = new TableColumn(modelColumn, 120);
        column.setHeaderValue(table.getModel().getColumnName(modelColumn));
        columns.addColumn(column);
        columns.moveColumn(columns.getColumnCount() - 1, position);
        return true;
    }

    static int getHiddenCount(@NotNull JTable table) {
        return table.getModel().getColumnCount() - table.getColumnCount();
    }

    /**
     * Hides the columns whose read rows are all NULL or empty (SWT "Hide empty columns").
     *
     * @return the number of hidden columns
     */
    static int hideEmptyColumns(@NotNull JTable table) {
        if (table.getRowCount() == 0) {
            return 0;
        }
        List<Integer> empty = new ArrayList<>();
        for (int viewColumn = 0; viewColumn < table.getColumnCount(); viewColumn++) {
            boolean isEmpty = true;
            for (int row = 0; row < table.getRowCount() && isEmpty; row++) {
                Object value = table.getValueAt(row, viewColumn);
                isEmpty = value == null || value == ResultSetGrid.NULL_VALUE || value.toString().isEmpty();
            }
            if (isEmpty) {
                empty.add(viewColumn);
            }
        }
        return hideColumns(table, empty.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Grid font and row height at a zoom step (0: the theme's), from the grid's own values at step 0.
     */
    static void applyZoom(@NotNull JTable table, @NotNull Font baseFont, int baseRowHeight, int step) {
        float factor = 1 + step * ZOOM_STEP;
        table.setFont(step == 0 ? baseFont : baseFont.deriveFont(baseFont.getSize2D() * factor));
        table.setRowHeight(Math.max(8, Math.round(baseRowHeight * factor)));
    }

    /**
     * Ctrl+G: asks for a row number (1-based, of the read rows) and selects that row.
     */
    static void goToRow(@NotNull Component parent, @NotNull JTable table) {
        int rows = table.getRowCount();
        if (rows == 0) {
            return;
        }
        int current = Math.max(table.getSelectedRow(), 0) + 1;
        Object input = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(parent),
            NLS.bind(GridNavMessages.gridnav_goto_row_prompt, String.format("%,d", rows)), //$NON-NLS-1$
            GridNavMessages.gridnav_goto_row_title, JOptionPane.QUESTION_MESSAGE, null, null, String.valueOf(current));
        if (input == null) {
            return;
        }
        int row;
        try {
            row = Integer.parseInt(input.toString().trim().replace(",", "")); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (NumberFormatException e) {
            return;
        }
        row = Math.clamp(row, 1, rows) - 1;
        int column = Math.max(table.getSelectedColumn(), 0);
        table.changeSelection(row, column, false, false);
        table.requestFocusInWindow();
    }

    /**
     * Ctrl+Shift+G: picks a column from a list filtered by name and selects it in the current row (a hidden column is
     * shown again).
     */
    static void goToColumn(@NotNull Component parent, @NotNull JTable table) {
        int count = table.getModel().getColumnCount();
        if (count == 0) {
            return;
        }
        DefaultListModel<String> listModel = new DefaultListModel<>();
        List<Integer> listColumns = new ArrayList<>();
        JTextField filter = new JTextField(24);
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(12);
        Runnable fill = () -> {
            listModel.clear();
            listColumns.clear();
            String text = filter.getText().trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < count; i++) {
                String name = table.getModel().getColumnName(i);
                if (text.isEmpty() || name.toLowerCase(Locale.ROOT).contains(text)) {
                    listColumns.add(i);
                    boolean hidden = table.convertColumnIndexToView(i) < 0;
                    listModel.addElement(hidden ? NLS.bind(GridNavMessages.gridnav_goto_column_hidden, name) : name);
                }
            }
            if (!listModel.isEmpty()) {
                list.setSelectedIndex(0);
            }
        };
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fill.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fill.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        fill.run();
        // Up and down in the filter move in the list
        filter.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int index = list.getSelectedIndex();
                if (e.getKeyCode() == KeyEvent.VK_DOWN && index < listModel.size() - 1) {
                    list.setSelectedIndex(index + 1);
                    list.ensureIndexIsVisible(index + 1);
                } else if (e.getKeyCode() == KeyEvent.VK_UP && index > 0) {
                    list.setSelectedIndex(index - 1);
                    list.ensureIndexIsVisible(index - 1);
                }
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(filter, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog(SwingUtilities.getWindowAncestor(parent), GridNavMessages.gridnav_goto_column_title);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    pane.setValue(JOptionPane.OK_OPTION);
                }
            }
        });
        filter.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                filter.requestFocusInWindow();
            }
        });
        dialog.setVisible(true);
        dialog.dispose();
        int index = list.getSelectedIndex();
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue()) || index < 0) {
            return;
        }
        selectColumn(table, listColumns.get(index));
    }

    /**
     * Selects a model column in the current row and scrolls to it.
     */
    static void selectColumn(@NotNull JTable table, int modelColumn) {
        showColumn(table, modelColumn);
        int viewColumn = table.convertColumnIndexToView(modelColumn);
        if (viewColumn < 0 || table.getRowCount() == 0) {
            return;
        }
        int row = Math.max(table.getSelectionModel().getLeadSelectionIndex(), 0);
        table.changeSelection(Math.min(row, table.getRowCount() - 1), viewColumn, false, false);
        table.requestFocusInWindow();
    }

    /**
     * The status note about hidden columns, or null when no column is hidden.
     */
    @Nullable
    static String describeHidden(@NotNull JTable table) {
        int hidden = getHiddenCount(table);
        return hidden == 0 ? null : NLS.bind(GridNavMessages.gridnav_columns_hidden, hidden);
    }
}
