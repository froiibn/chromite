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

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 결과 안 찾기 줄(SWT 결과 그리드의 Find/Replace, {@code ResultsetFindReplaceOverlay}에서 찾기만): 결과 위에 붙는 한 줄로, 표에 보이는 글자에서
 * 찾는 말을 차례로 찾아 그 칸을 고른다.
 *
 * 설계:
 * <ul>
 *     <li>찾는 곳은 표의 표시 문자열(보이는 열, 표의 열 순서, 행 순서)이다. NULL 칸은 건너뛴다(값이 아니라 표지). 지금 고른 칸 다음부터 찾고 끝에 닿으면
 *     처음부터 다시 찾는다. 대소문자 구분은 고를 수 있다(기본 구분 안 함).</li>
 *     <li>모두 몇 개인지와 지금 몇 번째인지 보인다("3 / 17", {@link #MAX_COUNTED_CELLS}칸이 넘는 결과는 세지 않음). 칸 값을 바꾸지 않으므로 바꾸기는 없다(편집은 표의 붙여넣기·셀 편집으로).</li>
 *     <li>Ctrl+F가 열고(고른 칸의 글자를 처음 찾는 말로), Enter/F3은 다음, Shift+Enter/Shift+F3은 이전, Esc는 닫고 표로 돌아간다.</li>
 *     <li>읽은 행만 찾는다. 서버에서 거르려면 열 필터를 쓴다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "결과 안 찾기")
 * - 2026-11-12 아이콘 단추의 도구 설명을 설명문 대신 기능 이름(동작·메뉴의 이름)과 단축키로 (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class ResultSetFindBar extends JPanel {

    /**
     * Results up to this many cells get the match count ("3 / 17").
     */
    private static final long MAX_COUNTED_CELLS = 2_000_000;

    private final Supplier<JTable> tableSupplier;
    private final JTextField field = new JTextField(24);
    private final JCheckBox matchCase = new JCheckBox(GridNavMessages.gridnav_find_match_case);
    private final JLabel resultLabel = new JLabel();

    /**
     * @param tableSupplier the grid's current table (null while there is none)
     */
    ResultSetFindBar(@NotNull Supplier<JTable> tableSupplier) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 1));
        this.tableSupplier = tableSupplier;
        add(new JLabel(GridNavMessages.gridnav_find_label));
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, GridNavMessages.gridnav_find_placeholder);
        add(field);
        JButton previous = new JButton(GridNavMessages.gridnav_find_previous);
        previous.setToolTipText(GridNavMessages.gridnav_find_previous_tip);
        previous.addActionListener(e -> find(false));
        JButton next = new JButton(GridNavMessages.gridnav_find_next);
        next.setToolTipText(GridNavMessages.gridnav_find_next_tip);
        next.addActionListener(e -> find(true));
        add(previous);
        add(next);
        matchCase.addActionListener(e -> resultLabel.setText(""));
        add(matchCase);
        resultLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        add(resultLabel);
        Action close = ResultSetViewerPanel.createAction(GridNavMessages.gridnav_find_close, null, this::close);
        add(UiIcons.createActionButton(close, "close")); //$NON-NLS-1$
        for (JComponent component : new JComponent[]{previous, next, matchCase}) {
            component.setFocusable(false);
        }
        field.addActionListener(e -> find(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "chromite.findPrevious", () -> find(false)); //$NON-NLS-1$
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "chromite.findNext", () -> find(true)); //$NON-NLS-1$
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "chromite.findPreviousF3", () -> find(false)); //$NON-NLS-1$
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "chromite.findClose", this::close); //$NON-NLS-1$
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                resultLabel.setText("");
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                resultLabel.setText("");
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        setVisible(false);
    }

    private void bind(@NotNull KeyStroke key, @NotNull String name, @NotNull Runnable task) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        getActionMap().put(name, ResultSetViewerPanel.createAction(name, null, task));
    }

    /**
     * Shows the bar with the text of the selected cell (a short one) and puts the focus in it.
     */
    void open() {
        JTable table = tableSupplier.get();
        if (table != null && field.getText().isEmpty()) {
            int row = table.getSelectionModel().getLeadSelectionIndex();
            int column = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
            String text = cellText(table, row, column);
            if (text != null && text.length() <= 100 && text.indexOf('\n') < 0) {
                field.setText(text);
            }
        }
        setVisible(true);
        revalidate();
        field.selectAll();
        field.requestFocusInWindow();
    }

    private void close() {
        setVisible(false);
        revalidate();
        JTable table = tableSupplier.get();
        if (table != null) {
            table.requestFocusInWindow();
        }
    }

    /**
     * Finds the next (or previous) cell with the text after the selected one and selects it.
     */
    void find(boolean forward) {
        JTable table = tableSupplier.get();
        String text = field.getText();
        if (table == null || text.isEmpty()) {
            return;
        }
        int rows = table.getRowCount();
        int columns = table.getColumnCount();
        if (rows == 0 || columns == 0) {
            resultLabel.setText(GridNavMessages.gridnav_find_none);
            return;
        }
        boolean caseSensitive = matchCase.isSelected();
        String needle = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        // Cells in row order; long: rows times columns can pass the int range
        long total = (long) rows * columns;
        int lead = table.getSelectionModel().getLeadSelectionIndex();
        int leadColumn = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        long start = lead < 0 || leadColumn < 0 ? (forward ? -1 : total) : (long) lead * columns + leadColumn;
        long found = -1;
        for (long step = 1; step <= total; step++) {
            long index = Math.floorMod(start + (forward ? step : -step), total);
            if (matches(table, (int) (index / columns), (int) (index % columns), needle, caseSensitive)) {
                found = index;
                break;
            }
        }
        if (found < 0) {
            resultLabel.setText(GridNavMessages.gridnav_find_none);
            return;
        }
        table.changeSelection((int) (found / columns), (int) (found % columns), false, false);
        if (total > MAX_COUNTED_CELLS) {
            // Counting every match of a very large result would hold up the UI
            resultLabel.setText(GridNavMessages.gridnav_find_found);
            return;
        }
        int matches = 0;
        int position = 0;
        for (long index = 0; index < total; index++) {
            if (matches(table, (int) (index / columns), (int) (index % columns), needle, caseSensitive)) {
                matches++;
                if (index == found) {
                    position = matches;
                }
            }
        }
        resultLabel.setText(NLS.bind(GridNavMessages.gridnav_find_position, position, matches));
    }

    private static boolean matches(@NotNull JTable table, int row, int column, @NotNull String needle, boolean caseSensitive) {
        String text = cellText(table, row, column);
        if (text == null) {
            return false;
        }
        return (caseSensitive ? text : text.toLowerCase(Locale.ROOT)).contains(needle);
    }

    /**
     * The text a cell shows, or null for NULL and cells out of the table.
     */
    @Nullable
    private static String cellText(@NotNull JTable table, int row, int column) {
        if (row < 0 || column < 0 || row >= table.getRowCount() || column >= table.getColumnCount()) {
            return null;
        }
        Object value = table.getValueAt(row, column);
        return value == null || value == ResultSetGrid.NULL_VALUE ? null : value.toString();
    }
}
