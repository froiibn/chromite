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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 예약 실행 기록 보기("History…"): 실행 목록(최근 것이 위)과 고른 실행의 앞 몇 행.
 *
 * 설계: 기록은 {@link QueryLibraryHistory}에서 읽는다. 대화상자를 열면 그 쿼리의 알림 표시(트리 배지)를 지운다(확인한 것으로 봄).
 * 비모달이라 열어 둔 채로 다음 실행을 기다릴 수 있고, 예약 실행기의 변경 알림으로 목록을 다시 읽는다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 SQL 편집기 확장의 쿼리 기록 창({@link QueryHistoryDialog})과 이름이 겹쳐 QueryHistoryDialog에서 이름을 바꿈
 *   (docs/chromite/swing-query-library.md)
 */
final class QueryRunHistoryDialog extends JDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$
    private static final String[] COLUMNS = {"Time", "Server", "Status", "Rows", "Duration", "Message"};

    private final QueryLibraryItem item;
    private final RunsModel runsModel = new RunsModel();
    private final JTable runsTable = new JTable(runsModel);
    private final DefaultTableModel snapshotModel = new DefaultTableModel();
    private final Runnable schedulerListener = this::reload;
    private List<QueryLibraryHistory.Run> runs = new ArrayList<>();

    private QueryRunHistoryDialog(@NotNull Window owner, @NotNull QueryLibraryItem item) {
        super(owner, "History - " + item.getName(), ModalityType.MODELESS);
        this.item = item;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        runsTable.setRowHeight(Math.max(runsTable.getRowHeight(), 26));
        runsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSnapshot();
            }
        });
        runsTable.setDefaultRenderer(Object.class, new StatusRenderer());
        int[] widths = {150, 130, 70, 60, 80, 360};
        for (int i = 0; i < widths.length; i++) {
            runsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JTable snapshotTable = new JTable(snapshotModel);
        snapshotTable.setEnabled(false);
        snapshotTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        JLabel snapshotLabel = new JLabel("First " + QueryScheduler.SNAPSHOT_ROWS + " rows of the selected run");
        snapshotLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel snapshotPanel = new JPanel(new BorderLayout(0, 4));
        snapshotPanel.add(snapshotLabel, BorderLayout.NORTH);
        snapshotPanel.add(new JScrollPane(snapshotTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(runsTable), snapshotPanel);
        split.setResizeWeight(0.55);
        split.setBorder(BorderFactory.createEmptyBorder());

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(900, 560);
        setLocationRelativeTo(owner);

        QueryScheduler.getInstance().addListener(schedulerListener);
        reload();
    }

    /**
     * Shows the history of a query and clears its alert.
     *
     * @param parent component of the window to open it over, or null for the active window
     */
    static void open(@Nullable Component parent, @NotNull QueryLibraryItem item) {
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (owner == null && parent instanceof Window window) {
            owner = window;
        }
        if (owner == null) {
            for (Frame frame : Frame.getFrames()) {
                if (frame.isShowing()) {
                    owner = frame;
                    break;
                }
            }
        }
        if (QueryLibraryState.hasAlert(item)) {
            QueryLibraryState.setAlert(item, false);
            QueryLibrary.getInstance().fireChanged();
        }
        new QueryRunHistoryDialog(owner, item).setVisible(true);
    }

    @Override
    public void dispose() {
        QueryScheduler.getInstance().removeListener(schedulerListener);
        super.dispose();
    }

    private void reload() {
        int selected = runsTable.getSelectedRow();
        runs = new ArrayList<>(QueryLibraryHistory.getRuns(item.getStateKey()).reversed());
        runsModel.fireTableDataChanged();
        if (!runs.isEmpty()) {
            int row = selected >= 0 && selected < runs.size() ? selected : 0;
            runsTable.setRowSelectionInterval(row, row);
        }
        showSnapshot();
    }

    private void showSnapshot() {
        int row = runsTable.getSelectedRow();
        List<List<String>> snapshot = row >= 0 && row < runs.size() ? runs.get(row).snapshot() : List.of();
        if (snapshot.isEmpty()) {
            snapshotModel.setDataVector(new Object[0][0], new Object[0]);
            return;
        }
        Object[] columns = snapshot.getFirst().toArray();
        Object[][] data = new Object[snapshot.size() - 1][];
        for (int i = 1; i < snapshot.size(); i++) {
            data[i - 1] = snapshot.get(i).toArray();
        }
        snapshotModel.setDataVector(data, columns);
    }

    private final class RunsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return runs.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            QueryLibraryHistory.Run run = runs.get(row);
            return switch (column) {
                case 0 -> run.time().format(TIME_FORMAT);
                case 1 -> run.server();
                case 2 -> run.status();
                case 3 -> run.rows();
                case 4 -> run.durationMillis() + " ms";
                default -> run.message();
            };
        }
    }

    /**
     * The status column in the theme's status colors.
     */
    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof QueryLibraryHistory.Status status) {
                Color color = UIManager.getColor(switch (status) {
                    case ALERT, ERROR -> "Chromite.status.error"; //$NON-NLS-1$
                    case SKIPPED -> "Chromite.status.warning"; //$NON-NLS-1$
                    case OK -> "Chromite.status.success"; //$NON-NLS-1$
                });
                if (color != null) {
                    setForeground(color);
                }
            } else if (!isSelected) {
                setForeground(table.getForeground());
            }
            return this;
        }
    }
}
