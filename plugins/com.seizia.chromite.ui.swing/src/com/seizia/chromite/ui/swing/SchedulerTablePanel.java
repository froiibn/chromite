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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Document tab of the job schedulers that shows rows read from the server: the job run history (Oracle, pg_cron) and the
 * running jobs (Oracle), with a detail box for the selected row's messages and optional row commands.
 *
 * 설계: Oracle 작업 실행 기록·실행 중인 작업과 pg_cron 작업 실행 기록은 모양이 같아(표 + 고른 행의 오류·출력 글 + 새로 고침) 한 부품으로
 * 만들었다. 표는 Activity Monitor와 같은 {@link ObjectListTable}({@code @Property} 열)이고, 자동 새로 고침은 {@link AutoRefreshBox}다.
 * SQL Server 에이전트의 작업 기록 탭({@link AgentJobHistoryPanel})은 실행과 단계를 묶어 보이는 다른 모양이라 그대로 둔다.
 * <ul>
 *     <li>읽기는 Job에서 하고 결과는 EDT로 넘긴다. 읽기 문은 탐색기와 같은 메타 세션에서 하므로(읽는 쪽이 고름) PostgreSQL처럼
 *     데이터베이스마다 연결이 다른 경우에도 그 데이터베이스를 읽는다. 바꾸는 명령(작업 중지)은 모델의 격리된 연결에서 한다.</li>
 *     <li>읽는 중이면 자동 새로 고침 차례는 건너뛴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
final class SchedulerTablePanel<T> extends JPanel implements ToolDocument {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    /**
     * Reads the rows, in a background job.
     */
    interface Reader<T> {
        @NotNull
        List<T> read(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    /**
     * A command on the selected row; returns true when the rows should be read again.
     */
    record RowAction<T>(@NotNull String name, @NotNull String icon, @Nullable String toolTip, @NotNull Predicate<T> enabled, @NotNull Predicate<T> run) {
    }

    /**
     * What the tab shows.
     *
     * @param detail       text of the detail box for a row, or null for no detail box
     * @param refreshKey   preference key of the auto refresh interval, or null for no auto refresh
     * @param note         a line shown above the table (e.g. how long the server keeps history), or null
     */
    record Spec<T>(
        @NotNull DBPDataSourceContainer container,
        @NotNull String title,
        @NotNull String toolTip,
        @NotNull String icon,
        @NotNull Class<T> type,
        @NotNull Function<T, Object> keyOf,
        @NotNull Reader<T> reader,
        @Nullable Function<T, String> detail,
        @Nullable String refreshKey,
        @Nullable String note,
        @NotNull List<RowAction<T>> actions
    ) {
    }

    private final Spec<T> spec;
    private final ObjectListTable<T> table;
    private final JTextArea detailArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JTextField searchField = new JTextField(16);
    private final List<Action> rowActions = new ArrayList<>();
    @Nullable
    private final AutoRefreshBox autoRefresh;
    private volatile boolean loading;
    private volatile boolean disposed;

    SchedulerTablePanel(@NotNull Spec<T> spec) {
        super(new BorderLayout());
        this.spec = spec;
        table = new ObjectListTable<>(spec.keyOf(), spec.container());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                rowSelected();
            }
        });

        Action refreshAction = new AbstractAction(SchedulerMessages.scheduler_refresh) {
            @Override
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        };
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        if (spec.refreshKey() != null) {
            autoRefresh = new AutoRefreshBox(spec.refreshKey(), this, () -> {
                if (!loading && spec.container().isConnected()) {
                    refresh();
                }
            });
            autoRefresh.setMaximumSize(autoRefresh.getPreferredSize());
            toolBar.add(autoRefresh);
        } else {
            autoRefresh = null;
        }
        if (!spec.actions().isEmpty()) {
            toolBar.addSeparator();
            for (RowAction<T> rowAction : spec.actions()) {
                Action action = new AbstractAction(rowAction.name()) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        T row = table.getSelectedObject();
                        if (row != null && rowAction.run().test(row)) {
                            refresh();
                        }
                    }
                };
                action.putValue(Action.SHORT_DESCRIPTION, rowAction.toolTip());
                rowActions.add(action);
                toolBar.add(UiIcons.createToolButton(action, rowAction.icon()));
            }
        }
        toolBar.add(Box.createHorizontalGlue());
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.scheduler_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setMaximumSize(searchField.getPreferredSize());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                table.setSearchText(searchField.getText());
            }
        });
        toolBar.add(searchField);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolBar, BorderLayout.NORTH);
        if (spec.note() != null) {
            JLabel noteLabel = new JLabel(spec.note(), UiIcons.get("info"), SwingConstants.LEADING); //$NON-NLS-1$
            noteLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
            north.add(noteLabel, BorderLayout.SOUTH);
        }

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        Component center = tableScroll;
        if (spec.detail() != null) {
            detailArea.setEditable(false);
            detailArea.setLineWrap(true);
            detailArea.setWrapStyleWord(true);
            detailArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            JScrollPane detailScroll = new JScrollPane(detailArea);
            detailScroll.setBorder(BorderFactory.createEmptyBorder());
            detailScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(120)));
            tableScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(300)));
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
            split.setResizeWeight(0.7);
            split.setBorder(BorderFactory.createEmptyBorder());
            center = split;
        }
        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        add(north, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        rowSelected();
        refresh();
    }

    /**
     * Reads the rows again in the background.
     */
    void refresh() {
        if (disposed) {
            return;
        }
        loading = true;
        statusLabel.setText(SchedulerMessages.scheduler_reading);
        new AbstractJob("Read " + spec.title()) { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    List<T> rows = spec.reader().read(monitor);
                    SwingUtilities.invokeLater(() -> showRows(rows));
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> showError(e));
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void showRows(@NotNull List<T> rows) {
        loading = false;
        if (disposed) {
            return;
        }
        table.setObjects(rows, spec.type());
        statusLabel.setText(rows.isEmpty()
            ? NLS.bind(SchedulerMessages.scheduler_rows_empty, TIME_FORMAT.format(LocalTime.now()))
            : NLS.bind(SchedulerMessages.scheduler_rows_status, rows.size(), TIME_FORMAT.format(LocalTime.now())));
        statusLabel.setToolTipText(null);
        rowSelected();
    }

    private void showError(@NotNull Throwable error) {
        loading = false;
        if (disposed) {
            return;
        }
        statusLabel.setText(ErrorText.describeLine(error));
        statusLabel.setToolTipText(ErrorText.describe(error));
    }

    private void rowSelected() {
        T row = table.getSelectedObject();
        for (int i = 0; i < rowActions.size(); i++) {
            rowActions.get(i).setEnabled(row != null && spec.actions().get(i).enabled().test(row));
        }
        if (spec.detail() != null) {
            detailArea.setText(row == null ? "" : spec.detail().apply(row)); //$NON-NLS-1$
            detailArea.setCaretPosition(0);
        }
    }

    @NotNull
    @Override
    public String getTitle() {
        return spec.title();
    }

    @NotNull
    @Override
    public String getToolTip() {
        return spec.toolTip();
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get(spec.icon());
    }

    @Override
    public void dispose() {
        disposed = true;
        if (autoRefresh != null) {
            autoRefresh.dispose();
        }
    }
}
