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
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 관리 화면(유지 관리, 복제)의 하위 탭들: 표({@link AdminGrid})마다 탭 하나에 설명, 표(또는 빈 표·읽을 수 없음 문구), 고른 행의 상세를 보인다.
 *
 * 설계: 공간 사용량 탭({@link DiskUsagePanel})의 하위 탭과 같은 동작(다시 읽어도 같은 표면 정렬·열 너비·고른 탭 유지, 읽을 수 없는 표는 이유를 탭 안에)을
 * 두 화면이 함께 쓰도록 한 곳에 둔다. 공간 사용량은 표 종류가 열거형({@code SpaceGrid.Kind})이라 그대로 두었다(고치지 않음).
 * <ul>
 *     <li>행이 없고 표에 빈 표 문구가 있으면 표 대신 그 문구를 보인다. 복제가 구성되지 않은 서버는 오류가 아니라 "구성되지 않음"이다.</li>
 *     <li>행에 상세 글이 있는 표(Oracle 컴파일 오류)는 아래에 상세 칸을 두고 고른 행의 글을 보인다.</li>
 *     <li>행의 값({@code AdminGrid#getRowTag})은 행을 두 번 누를 때 넘긴다(유지 관리 탭이 그 행의 권장 작업으로 대화상자를 연다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4·6 유지 관리·복제 상태 (docs/chromite/swing-admin-tools.md)
 */
final class AdminGridTabs extends JTabbedPane {

    private final Map<String, AdminGridTable> tables = new LinkedHashMap<>();
    private final Map<String, JTextArea> details = new HashMap<>();
    private final Map<String, AdminGrid> grids = new HashMap<>();
    private final List<Runnable> selectionListeners = new ArrayList<>();
    @Nullable
    private Consumer<Object> rowAction;

    AdminGridTabs() {
        putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        addChangeListener(e -> fireSelection());
    }

    /**
     * Called when a row is double-clicked or Enter is pressed, with the tag of the row (never with a null tag).
     */
    void setRowAction(@NotNull Consumer<Object> action) {
        rowAction = action;
    }

    /**
     * Called when the selected row or tab changes.
     */
    void addSelectionListener(@NotNull Runnable listener) {
        selectionListeners.add(listener);
    }

    /**
     * The tag of the selected row of the visible grid, or null.
     */
    @Nullable
    Object getSelectedTag() {
        AdminGrid grid = getSelectedGrid();
        AdminGridTable table = grid == null ? null : tables.get(grid.getId());
        int row = table == null ? -1 : table.getSelectedModelRow();
        return grid == null || row < 0 || row >= grid.getRows().size() ? null : grid.getRowTag(row);
    }

    @Nullable
    private AdminGrid getSelectedGrid() {
        int index = getSelectedIndex();
        if (index < 0 || !(getComponentAt(index) instanceof JComponent page) || !(page.getClientProperty(AdminGrid.class) instanceof String id)) {
            return null;
        }
        return grids.get(id);
    }

    /**
     * One tab per grid; the tabs stay (with their sort and selection) when the same grids are read again.
     */
    void showGrids(@NotNull List<AdminGrid> newGrids) {
        String selectedId = getSelectedGrid() == null ? null : getSelectedGrid().getId();
        List<String> ids = new ArrayList<>();
        for (AdminGrid grid : newGrids) {
            ids.add(grid.getId());
        }
        if (!ids.equals(new ArrayList<>(tables.keySet())) || getTabCount() != newGrids.size()) {
            removeAll();
            tables.clear();
            details.clear();
            for (AdminGrid grid : newGrids) {
                addTab(grid.getTitle(), new JPanel(new BorderLayout()));
                AdminGridTable table = new AdminGridTable(false);
                table.getSelectionModel().addListSelectionListener(e -> {
                    if (!e.getValueIsAdjusting()) {
                        updateDetail(grid.getId());
                        fireSelection();
                    }
                });
                table.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                            runRowAction();
                        }
                    }
                });
                table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "chromite.rowAction"); //$NON-NLS-1$ //$NON-NLS-2$
                table.getActionMap().put("chromite.rowAction", new AbstractAction() { //$NON-NLS-1$
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        runRowAction();
                    }
                });
                tables.put(grid.getId(), table);
            }
        }
        grids.clear();
        for (int i = 0; i < newGrids.size(); i++) {
            AdminGrid grid = newGrids.get(i);
            grids.put(grid.getId(), grid);
            JPanel page = (JPanel) getComponentAt(i);
            page.putClientProperty(AdminGrid.class, grid.getId());
            page.removeAll();
            setTitleAt(i, grid.getTitle());
            AdminGridTable table = tables.get(grid.getId());
            if (grid.getDescription() != null) {
                page.add(createText(grid.getDescription(), 6), BorderLayout.NORTH);
            }
            if (grid.getUnavailableReason() != null) {
                table.clearRows();
                setToolTipTextAt(i, grid.getUnavailableReason());
                page.add(createText(grid.getUnavailableReason(), 16), BorderLayout.CENTER);
            } else if (grid.getRows().isEmpty() && grid.getEmptyText() != null) {
                table.setRows(grid.getColumns(), grid.getRows());
                setToolTipTextAt(i, null);
                page.add(createText(grid.getEmptyText(), 16), BorderLayout.CENTER);
            } else {
                table.setRows(grid.getColumns(), grid.getRows());
                setToolTipTextAt(i, null);
                JScrollPane scroll = new JScrollPane(table);
                scroll.setBorder(BorderFactory.createEmptyBorder());
                if (grid.hasDetails()) {
                    JTextArea detail = details.computeIfAbsent(grid.getId(), id -> createDetailArea());
                    JScrollPane detailScroll = new JScrollPane(detail);
                    detailScroll.setBorder(BorderFactory.createTitledBorder(MaintenanceViewMessages.maintview_details));
                    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, detailScroll);
                    split.setResizeWeight(0.7);
                    split.setBorder(BorderFactory.createEmptyBorder());
                    page.add(split, BorderLayout.CENTER);
                    updateDetail(grid.getId());
                } else {
                    page.add(scroll, BorderLayout.CENTER);
                }
            }
            page.revalidate();
            page.repaint();
            if (grid.getId().equals(selectedId)) {
                setSelectedIndex(i);
            }
        }
        fireSelection();
    }

    private void runRowAction() {
        Object tag = getSelectedTag();
        if (tag != null && rowAction != null) {
            rowAction.accept(tag);
        }
    }

    private void updateDetail(@NotNull String gridId) {
        JTextArea detail = details.get(gridId);
        AdminGrid grid = grids.get(gridId);
        AdminGridTable table = tables.get(gridId);
        if (detail == null || grid == null || table == null) {
            return;
        }
        int row = table.getSelectedModelRow();
        String text = row >= 0 && row < grid.getRows().size() ? grid.getRowDetail(row) : null;
        detail.setText(text == null ? "" : text); //$NON-NLS-1$
        detail.setCaretPosition(0);
    }

    private void fireSelection() {
        for (Runnable listener : selectionListeners) {
            listener.run();
        }
    }

    @NotNull
    private static JTextArea createDetailArea() {
        JTextArea area = new JTextArea(5, 40);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Font font = UIManager.getFont("monospaced.font"); //$NON-NLS-1$
        if (font != null) {
            area.setFont(font);
        }
        return area;
    }

    @NotNull
    private static JComponent createText(@NotNull String text, int padding) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        int scaled = UIScale.scale(padding);
        area.setBorder(BorderFactory.createEmptyBorder(scaled, UIScale.scale(10), scaled, UIScale.scale(10)));
        area.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        return area;
    }
}
