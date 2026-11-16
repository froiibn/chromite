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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * "Scheduled…" 개요: 예약된 쿼리 목록(일정, 서버, 이 PC에서 활성, 다음 실행, 마지막 실행과 상태)과 일시 정지/다시 시작, 지금 실행, 기록.
 *
 * 설계: 비모달 대화상자 하나만 연다. 예약 실행기({@link QueryScheduler})의 변경 알림과 5초 타이머(다음 실행까지 남은 시간)로 다시 그린다.
 * "Active here" 칸을 바꾸면 이 PC의 활성 여부(로컬 설정)만 바뀌고 쿼리 파일은 바뀌지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class QueryScheduledDialog extends JDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"); //$NON-NLS-1$
    private static final String[] COLUMNS = {ToolsMessages.scheduled_column_query, ToolsMessages.scheduled_column_schedule,
        ToolsMessages.scheduled_column_connections, ToolsMessages.scheduled_column_active, ToolsMessages.scheduled_column_next_run,
        ToolsMessages.scheduled_column_last_run, ToolsMessages.scheduled_column_last_status};

    private static QueryScheduledDialog instance;

    private final EntriesModel model = new EntriesModel();
    private final JTable table = new JTable(model);
    private final Runnable schedulerListener = this::reload;
    private final Timer timer = new Timer(5000, e -> model.fireTableRowsUpdated(0, Math.max(0, model.getRowCount() - 1)));
    private List<QueryScheduler.Entry> entries = new ArrayList<>();

    private QueryScheduledDialog(@NotNull Window owner) {
        super(owner, ToolsMessages.scheduled_title, ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        table.setRowHeight(Math.max(table.getRowHeight(), 26));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {200, 120, 160, 80, 110, 110, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JLabel hint = new JLabel(ToolsMessages.scheduled_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JButton runNowButton = new JButton(ToolsMessages.scheduled_run_now);
        runNowButton.addActionListener(e -> {
            QueryScheduler.Entry entry = getSelected();
            if (entry != null) {
                QueryScheduler.getInstance().runNow(entry);
            }
        });
        JButton historyButton = new JButton(ToolsMessages.library_action_history);
        historyButton.addActionListener(e -> {
            QueryScheduler.Entry entry = getSelected();
            if (entry != null) {
                QueryRunHistoryDialog.open(this, entry.item);
            }
        });
        JButton closeButton = new JButton(ToolsMessages.button_close);
        closeButton.addActionListener(e -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(runNowButton);
        left.add(historyButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(left, BorderLayout.WEST);
        south.add(closeButton, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(hint, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(1060, 380);
        setLocationRelativeTo(owner);

        QueryScheduler.getInstance().addListener(schedulerListener);
        timer.start();
        reload();
    }

    static void open(@NotNull Component parent) {
        if (instance != null && instance.isDisplayable()) {
            instance.toFront();
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(parent);
        instance = new QueryScheduledDialog(owner != null ? owner : JOptionPane.getRootFrame());
        instance.setVisible(true);
    }

    @Override
    public void dispose() {
        timer.stop();
        QueryScheduler.getInstance().removeListener(schedulerListener);
        instance = null;
        super.dispose();
    }

    private void reload() {
        int selected = table.getSelectedRow();
        entries = QueryScheduler.getInstance().getEntries();
        model.fireTableDataChanged();
        if (selected >= 0 && selected < entries.size()) {
            table.setRowSelectionInterval(selected, selected);
        }
    }

    private QueryScheduler.Entry getSelected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < entries.size() ? entries.get(row) : null;
    }

    private final class EntriesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
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
        public Class<?> getColumnClass(int column) {
            return column == 3 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }

        @Override
        public Object getValueAt(int row, int column) {
            QueryScheduler.Entry entry = entries.get(row);
            QueryLibrarySchedule schedule = entry.item.getSchedule();
            boolean active = QueryLibraryState.isScheduleActive(entry.item);
            return switch (column) {
                case 0 -> entry.item.getName();
                case 1 -> schedule == null ? "" : schedule.isEnabled() ? schedule.describe() //$NON-NLS-1$
                    : NLS.bind(ToolsMessages.scheduled_disabled, schedule.describe());
                case 2 -> schedule == null ? "" : schedule.getTargets().stream() //$NON-NLS-1$
                    .map(QueryLibrarySchedule.Target::name).collect(Collectors.joining(", ")); //$NON-NLS-1$
                case 3 -> active;
                case 4 -> entry.isRunning() ? ToolsMessages.scheduled_running : !active || schedule == null || !schedule.isEnabled() || entry.nextRun == null
                    ? "—" : formatNext(entry.nextRun); //$NON-NLS-1$
                case 5 -> entry.lastRun != null ? entry.lastRun.format(TIME_FORMAT) : "—"; //$NON-NLS-1$
                default -> entry.lastStatus == null ? "" : entry.lastStatus.getLabel() + ": " + entry.lastMessage; //$NON-NLS-1$ //$NON-NLS-2$
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 3) {
                QueryScheduler.getInstance().setActive(entries.get(row), Boolean.TRUE.equals(value));
            }
        }

        @NotNull
        private static String formatNext(@NotNull LocalDateTime next) {
            long seconds = Math.max(0, java.time.Duration.between(LocalDateTime.now(), next).getSeconds());
            String remaining = seconds < 60 ? NLS.bind(ToolsMessages.scheduled_in_seconds, seconds)
                : seconds < 3600 ? NLS.bind(ToolsMessages.scheduled_in_minutes, seconds / 60)
                : NLS.bind(ToolsMessages.scheduled_in_hours, seconds / 3600);
            return next.format(TIME_FORMAT) + " (" + remaining + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
