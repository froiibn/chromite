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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.admin.logs.ServerLogDialect;
import com.seizia.chromite.model.admin.logs.ServerLogEntry;
import com.seizia.chromite.model.admin.logs.ServerLogPage;
import com.seizia.chromite.model.admin.logs.ServerLogQuery;
import com.seizia.chromite.model.admin.logs.ServerLogSource;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.ui.swing.internal.AdminViewMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.text.DateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Activity Monitor의 Server Logs(서버 로그) 탭: 오류 로그·경고 로그·느린 쿼리 로그를 최신 항목부터 한 쪽씩 보인다.
 *
 * 설계: Disk Usage 탭({@link DiskUsagePanel})과 같은 이유로 Activity Monitor 문서의 탭이다. DB별 읽기와 권한 확인은 모델의
 * {@link ServerLogDialect}가 한다.
 * <ul>
 *     <li>고르는 것: 로그(DB가 주는 목록), 보관본(SQL Server 보관 번호, PostgreSQL 파일), 시간 범위(최근 1시간·24시간·7일·30일, 전체,
 *     직접 지정), 수준, 찾을 글자. 바꾸면 처음 쪽부터 다시 읽는다. 찾을 글자는 Enter로 적용한다(글자마다 서버를 읽지 않게).</li>
 *     <li>큰 로그를 통째로 읽지 않는다: 한 쪽은 {@link #PAGE_SIZE}개, "더 읽기"로 다음 쪽을 붙인다. 화면에 쌓는 항목은 {@link #MAX_ENTRIES}개까지다.</li>
 *     <li>읽을 수 없는 로그(권한, 서버 설정)는 고를 수 있지만 읽지 않고 안내 줄에 이유를 보인다.</li>
 *     <li>표는 한 줄 메시지이고 고른 항목의 전체 메시지와 상세(SQLSTATE, 문장, 모듈)는 아래 칸에 보인다(선택해 복사할 수 있다).</li>
 *     <li>시각은 로그에 적힌 그대로다(서버의 로그 시간대). 직접 지정 범위도 그 시각으로 비교한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 5 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
final class ServerLogPanel extends JPanel {

    private static final Log log = Log.getLog(ServerLogPanel.class);
    static final int PAGE_SIZE = 500;
    static final int MAX_ENTRIES = 10_000;
    private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$
    // What the user types in the custom range: "2026-11-07 05:30" or with seconds
    private static final DateTimeFormatter INPUT_TIME = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm") //$NON-NLS-1$
        .optionalStart().appendPattern(":ss").optionalEnd() //$NON-NLS-1$
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .toFormatter();

    /**
     * A time range choice: the last period, all, or a custom range.
     */
    private record TimeRange(@NotNull String label, @Nullable Duration last, boolean custom) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final AdminConnection connection;
    private final JComboBox<ServerLogSource> sourceCombo = new JComboBox<>();
    private final JLabel archiveLabel = new JLabel(AdminViewMessages.admin_logs_archive);
    private final JComboBox<ServerLogSource.Option> archiveCombo = new JComboBox<>();
    private final JComboBox<TimeRange> timeCombo = new JComboBox<>(new TimeRange[]{
        new TimeRange(AdminViewMessages.admin_logs_time_hour, Duration.ofHours(1), false),
        new TimeRange(AdminViewMessages.admin_logs_time_day, Duration.ofDays(1), false),
        new TimeRange(AdminViewMessages.admin_logs_time_week, Duration.ofDays(7), false),
        new TimeRange(AdminViewMessages.admin_logs_time_month, Duration.ofDays(30), false),
        new TimeRange(AdminViewMessages.admin_logs_time_all, null, false),
        new TimeRange(AdminViewMessages.admin_logs_time_custom, null, true)});
    private final JComboBox<ServerLogSource.Option> levelCombo = new JComboBox<>();
    private final JTextField searchField = new JTextField(18);
    private final JTextField fromField = new JTextField(14);
    private final JTextField toField = new JTextField(14);
    private final JPanel rangeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), 0));
    private final AccessNoticeBar noticeBar = new AccessNoticeBar();
    private final AdminGridTable table = new AdminGridTable(true);
    private final JTextArea detailText = new JTextArea();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JButton moreButton = new JButton(AdminViewMessages.admin_logs_load_more);
    private final Action refreshAction;
    private final List<ServerLogEntry> entries = new ArrayList<>();
    private boolean loading;
    private boolean updatingCombos;
    @Nullable
    private ServerLogQuery lastQuery;
    @Nullable
    private String nextCursor;
    private int serial;

    ServerLogPanel(@NotNull AdminConnection connection) {
        super(new BorderLayout());
        this.connection = connection;

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        refreshAction = new AbstractAction(ConnectionMessages.common_refresh) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refresh();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, AdminViewMessages.admin_logs_refresh_tip);
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createLabel(AdminViewMessages.admin_logs_source));
        sourceCombo.setToolTipText(AdminViewMessages.admin_logs_source_tip);
        sourceCombo.setEnabled(false);
        sourceCombo.addActionListener(e -> {
            if (!updatingCombos) {
                sourceSelected();
                readFirstPage();
            }
        });
        toolBar.add(sourceCombo);
        archiveLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, UIScale.scale(4)));
        toolBar.add(archiveLabel);
        archiveCombo.setToolTipText(AdminViewMessages.admin_logs_archive_tip);
        archiveCombo.setPrototypeDisplayValue(new ServerLogSource.Option("", "postgresql-2026-11-07_000000.json - current")); //$NON-NLS-1$ //$NON-NLS-2$
        archiveCombo.addActionListener(e -> {
            if (!updatingCombos) {
                readFirstPage();
            }
        });
        toolBar.add(archiveCombo);
        toolBar.add(createLabel(AdminViewMessages.admin_logs_time));
        timeCombo.setSelectedIndex(1);
        timeCombo.setToolTipText(AdminViewMessages.admin_logs_time_tip);
        timeCombo.addActionListener(e -> {
            boolean custom = timeCombo.getSelectedItem() instanceof TimeRange range && range.custom();
            rangeBar.setVisible(custom);
            if (!custom) {
                readFirstPage();
            }
        });
        toolBar.add(timeCombo);

        // Second line: level and search; third line: the custom range (shown for it only). One line does not fit a narrow document
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), 0));
        filterBar.setOpaque(false);
        filterBar.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), UIScale.scale(4), UIScale.scale(8)));
        filterBar.add(new JLabel(AdminViewMessages.admin_logs_level));
        levelCombo.setToolTipText(AdminViewMessages.admin_logs_level_tip);
        levelCombo.setPrototypeDisplayValue(new ServerLogSource.Option("", "ERROR, FATAL, PANIC")); //$NON-NLS-1$ //$NON-NLS-2$
        levelCombo.addActionListener(e -> {
            if (!updatingCombos) {
                readFirstPage();
            }
        });
        filterBar.add(levelCombo);
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, AdminViewMessages.admin_logs_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setToolTipText(AdminViewMessages.admin_logs_search_tip);
        searchField.addActionListener(e -> readFirstPage());
        filterBar.add(searchField);
        for (JComboBox<?> combo : List.of(sourceCombo, archiveCombo, timeCombo)) {
            combo.setMaximumSize(combo.getPreferredSize());
        }

        rangeBar.setOpaque(false);
        rangeBar.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), UIScale.scale(4), UIScale.scale(8)));
        rangeBar.add(new JLabel(AdminViewMessages.admin_logs_from));
        rangeBar.add(fromField);
        rangeBar.add(new JLabel(AdminViewMessages.admin_logs_to));
        rangeBar.add(toField);
        JButton applyButton = new JButton(AdminViewMessages.admin_logs_apply);
        applyButton.addActionListener(e -> readFirstPage());
        rangeBar.add(applyButton);
        for (JTextField field : List.of(fromField, toField)) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-MM-dd HH:mm"); //$NON-NLS-1$
            field.setToolTipText(AdminViewMessages.admin_logs_time_format_tip);
            field.addActionListener(e -> readFirstPage());
        }
        rangeBar.setVisible(false);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        detailText.setEditable(false);
        detailText.setLineWrap(true);
        detailText.setWrapStyleWord(true);
        detailText.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(8), UIScale.scale(6), UIScale.scale(8)));
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(360)));
        JScrollPane detailScroll = new JScrollPane(detailText);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(140)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
        split.setResizeWeight(0.75);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        moreButton.setToolTipText(NLS.bind(AdminViewMessages.admin_logs_load_more_tip, PAGE_SIZE));
        moreButton.setVisible(false);
        moreButton.addActionListener(e -> readNextPage());
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(statusLabel, BorderLayout.CENTER);
        JPanel moreWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(2)));
        moreWrap.setOpaque(false);
        moreWrap.add(moreButton);
        south.add(moreWrap, BorderLayout.EAST);

        JPanel bars = new JPanel(new BorderLayout());
        bars.setOpaque(false);
        bars.add(toolBar, BorderLayout.NORTH);
        JPanel filterBars = new JPanel(new BorderLayout());
        filterBars.setOpaque(false);
        filterBars.add(filterBar, BorderLayout.NORTH);
        filterBars.add(rangeBar, BorderLayout.SOUTH);
        bars.add(filterBars, BorderLayout.CENTER);
        bars.add(noticeBar, BorderLayout.SOUTH);
        add(bars, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    @NotNull
    private static JLabel createLabel(@NotNull String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, UIScale.scale(4)));
        return label;
    }

    /**
     * Lists the logs again (permissions or settings may have changed), then reads the newest entries.
     */
    void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        refreshAction.setEnabled(false);
        statusLabel.setText(connection.getContainer().isConnected() ? AdminViewMessages.admin_logs_listing : ConnectionMessages.common_connecting);
        statusLabel.setToolTipText(null);
        connection.run("List server logs", (monitor, session, profile) -> { //$NON-NLS-1$
            ServerLogDialect dialect = ServerLogDialect.forDataSource(session.getDataSource());
            if (dialect == null || !(session instanceof JDBCSession jdbcSession)) {
                return null;
            }
            return dialect.listSources(monitor, jdbcSession);
        }, sources -> {
            loading = false;
            refreshAction.setEnabled(true);
            showSources(sources);
        }, error -> {
            loading = false;
            refreshAction.setEnabled(true);
            showError(error);
        });
    }

    private void showSources(@Nullable List<ServerLogSource> sources) {
        if (sources == null) {
            refreshAction.setEnabled(false);
            noticeBar.setNotice(NLS.bind(AdminViewMessages.admin_logs_not_supported, connection.getContainer().getDriver().getName()));
            statusLabel.setText(" "); //$NON-NLS-1$
            return;
        }
        String selectedId = sourceCombo.getSelectedItem() instanceof ServerLogSource source ? source.id() : null;
        updatingCombos = true;
        try {
            sourceCombo.removeAllItems();
            ServerLogSource select = null;
            for (ServerLogSource source : sources) {
                sourceCombo.addItem(source);
                // Keep the chosen log; otherwise the first one that can be read
                if (source.id().equals(selectedId) || select == null && source.isAvailable() && selectedId == null) {
                    select = source;
                }
            }
            if (select == null && !sources.isEmpty()) {
                select = sources.get(0);
            }
            sourceCombo.setSelectedItem(select);
            sourceCombo.setEnabled(sources.size() > 1);
            sourceCombo.setMaximumSize(sourceCombo.getPreferredSize());
        } finally {
            updatingCombos = false;
        }
        sourceSelected();
        readFirstPage();
    }

    /**
     * Fills the archive and level choices of the chosen log and shows why it cannot be read, if so.
     */
    private void sourceSelected() {
        ServerLogSource source = getSource();
        updatingCombos = true;
        try {
            String archiveId = archiveCombo.getSelectedItem() instanceof ServerLogSource.Option option ? option.id() : null;
            archiveCombo.removeAllItems();
            levelCombo.removeAllItems();
            levelCombo.addItem(new ServerLogSource.Option("", AdminViewMessages.admin_logs_level_all)); //$NON-NLS-1$
            if (source != null) {
                for (ServerLogSource.Option archive : source.archives()) {
                    archiveCombo.addItem(archive);
                    if (archive.id().equals(archiveId)) {
                        archiveCombo.setSelectedItem(archive);
                    }
                }
                for (ServerLogSource.Option level : source.levels()) {
                    levelCombo.addItem(level);
                }
            }
            boolean available = source != null && source.isAvailable();
            archiveLabel.setVisible(source == null || !source.archives().isEmpty());
            archiveCombo.setVisible(archiveLabel.isVisible());
            archiveCombo.setEnabled(available && archiveCombo.getItemCount() > 1);
            levelCombo.setEnabled(available && levelCombo.getItemCount() > 1);
            timeCombo.setEnabled(available);
            searchField.setEnabled(available);
            noticeBar.setNotice(source == null ? null : source.unavailableReason());
        } finally {
            updatingCombos = false;
        }
        revalidate();
    }

    @Nullable
    private ServerLogSource getSource() {
        return sourceCombo.getSelectedItem() instanceof ServerLogSource source ? source : null;
    }

    /**
     * Reads the newest matching entries with the current choices.
     */
    private void readFirstPage() {
        ServerLogSource source = getSource();
        if (updatingCombos || source == null) {
            return;
        }
        serial++;
        entries.clear();
        nextCursor = null;
        lastQuery = null;
        moreButton.setVisible(false);
        table.setRows(columns(source), List.of());
        detailText.setText(""); //$NON-NLS-1$
        if (!source.isAvailable()) {
            statusLabel.setText(AdminViewMessages.admin_logs_unavailable_status);
            statusLabel.setToolTipText(source.unavailableReason());
            return;
        }
        ServerLogQuery query = buildQuery(source);
        if (query == null) {
            return;
        }
        read(source, query);
    }

    private void readNextPage() {
        ServerLogSource source = getSource();
        if (source == null || lastQuery == null || nextCursor == null || loading) {
            return;
        }
        read(source, lastQuery.withCursor(nextCursor));
    }

    @Nullable
    private ServerLogQuery buildQuery(@NotNull ServerLogSource source) {
        TimeRange range = timeCombo.getSelectedItem() instanceof TimeRange value ? value : null;
        LocalDateTime from = null;
        LocalDateTime to = null;
        if (range != null && range.custom()) {
            try {
                from = parseInput(fromField.getText());
                to = parseInput(toField.getText());
            } catch (DateTimeParseException e) {
                statusLabel.setText(AdminViewMessages.admin_logs_bad_time);
                statusLabel.setToolTipText(null);
                return null;
            }
        }
        String archiveId = archiveCombo.getSelectedItem() instanceof ServerLogSource.Option option ? option.id() : null;
        String levelId = levelCombo.getSelectedItem() instanceof ServerLogSource.Option option && !option.id().isEmpty() ? option.id() : null;
        return new ServerLogQuery(source.id(), archiveId, range == null ? null : range.last(), from, to, searchField.getText(), levelId,
            null, PAGE_SIZE);
    }

    @Nullable
    private static LocalDateTime parseInput(@NotNull String text) {
        return text.isBlank() ? null : LocalDateTime.parse(text.strip(), INPUT_TIME);
    }

    private void read(@NotNull ServerLogSource source, @NotNull ServerLogQuery query) {
        int readSerial = serial;
        loading = true;
        refreshAction.setEnabled(false);
        moreButton.setEnabled(false);
        statusLabel.setText(AdminViewMessages.admin_logs_reading);
        statusLabel.setToolTipText(null);
        connection.run("Read server log", (monitor, session, profile) -> { //$NON-NLS-1$
            ServerLogDialect dialect = ServerLogDialect.forDataSource(session.getDataSource());
            if (dialect == null || !(session instanceof JDBCSession jdbcSession)) {
                return null;
            }
            monitor.subTask(AdminViewMessages.admin_logs_reading);
            return dialect.read(monitor, jdbcSession, query);
        }, page -> {
            loading = false;
            refreshAction.setEnabled(true);
            moreButton.setEnabled(true);
            if (readSerial == serial && page != null) {
                showPage(source, query, page);
            }
        }, error -> {
            loading = false;
            refreshAction.setEnabled(true);
            moreButton.setEnabled(true);
            if (readSerial == serial) {
                showError(error);
            }
        });
    }

    private void showPage(@NotNull ServerLogSource source, @NotNull ServerLogQuery query, @NotNull ServerLogPage page) {
        List<ServerLogEntry> added = page.entries();
        if (entries.size() + added.size() > MAX_ENTRIES) {
            added = added.subList(0, MAX_ENTRIES - entries.size());
        }
        entries.addAll(added);
        List<Object[]> rows = new ArrayList<>();
        boolean levels = !source.levels().isEmpty();
        boolean sources = source.sourceLabel() != null;
        for (ServerLogEntry entry : added) {
            List<Object> row = new ArrayList<>();
            row.add(entry.time() == null ? null : SHOW_TIME.format(entry.time()));
            if (levels) {
                row.add(entry.level());
            }
            if (sources) {
                row.add(entry.source());
            }
            row.add(entry.message());
            rows.add(row.toArray());
        }
        table.appendRows(rows);
        lastQuery = query;
        boolean capped = entries.size() >= MAX_ENTRIES;
        nextCursor = capped ? null : page.nextCursor();
        moreButton.setVisible(nextCursor != null);
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        String text;
        if (entries.isEmpty()) {
            text = AdminViewMessages.admin_logs_none;
        } else if (capped && page.nextCursor() != null) {
            text = NLS.bind(AdminViewMessages.admin_logs_status_capped, entries.size(), MAX_ENTRIES);
        } else {
            text = NLS.bind(nextCursor != null ? AdminViewMessages.admin_logs_status_more : AdminViewMessages.admin_logs_status_all, entries.size());
        }
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_updated, text, time));
        statusLabel.setToolTipText(text);
        if (table.getSelectedRow() < 0 && table.getRowCount() > 0 && query.cursor() == null) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void showError(@NotNull Throwable error) {
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
        statusLabel.setToolTipText(ErrorText.describeLine(error));
        log.debug("Error reading the server log", error); //$NON-NLS-1$
    }

    @NotNull
    private static List<SpaceColumn> columns(@NotNull ServerLogSource source) {
        List<SpaceColumn> columns = new ArrayList<>();
        columns.add(SpaceColumn.text(AdminViewMessages.admin_logs_col_time));
        if (!source.levels().isEmpty()) {
            columns.add(SpaceColumn.text(AdminViewMessages.admin_logs_col_level));
        }
        if (source.sourceLabel() != null) {
            columns.add(SpaceColumn.text(source.sourceLabel()));
        }
        columns.add(SpaceColumn.text(AdminViewMessages.admin_logs_col_message));
        return columns;
    }

    /**
     * The whole message and its details of the selected entry.
     */
    private void showDetail() {
        int row = table.getSelectedModelRow();
        if (row < 0 || row >= entries.size()) {
            detailText.setText(""); //$NON-NLS-1$
            return;
        }
        ServerLogEntry entry = entries.get(row);
        StringBuilder text = new StringBuilder();
        if (entry.time() != null) {
            text.append(SHOW_TIME.format(entry.time()));
        }
        if (entry.level() != null) {
            text.append(text.isEmpty() ? "" : "  ").append(entry.level()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (entry.source() != null) {
            text.append(text.isEmpty() ? "" : "  ").append(entry.source()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        text.append(text.isEmpty() ? "" : "\n\n").append(entry.message()); //$NON-NLS-1$ //$NON-NLS-2$
        if (entry.detail() != null) {
            text.append("\n\n").append(entry.detail()); //$NON-NLS-1$
        }
        detailText.setText(text.toString());
        detailText.setCaretPosition(0);
    }
}
