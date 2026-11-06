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
import com.seizia.chromite.model.admin.dashboard.DashboardHistory;
import com.seizia.chromite.model.admin.dashboard.DashboardLabels;
import com.seizia.chromite.model.admin.dashboard.DashboardSample;
import com.seizia.chromite.model.admin.dashboard.DashboardSampler;
import com.seizia.chromite.model.admin.dashboard.ServerDashboards;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.DashboardMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.dashboard.registry.DashboardItemConfiguration;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.geom.RoundRectangle2D;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서버 대시보드: 연결 하나의 실시간 차트 묶음(Activity Monitor의 개요 탭).
 *
 * 설계: SSMS Activity Monitor의 개요(Overview) 창처럼 서버 활동 차트를 Activity Monitor 문서의 첫 탭에 둔다. SWT의 대시보드 보기
 * ({@code DataSourceDashboardView}, {@code DashboardListViewer}, {@code DashboardViewItem})를 옮긴 것이다.
 * <ul>
 *     <li>차트 정의는 DBeaver 대시보드 등록부({@link ServerDashboards}), 값 읽기는 모델 클래스 {@link DashboardSampler}가 한다.
 *     SWT는 1초마다 도는 작업({@code DashboardUpdateJob})이 차트마다 정한 주기({@code updatePeriod})로 읽지만, 여기서는 사용자가 고른
 *     새로 고침 간격(1·2·5·10·30·60초, 기본 2초) 하나로 모든 차트를 한 번에 읽는다(같은 서버 연결에서 차례로).</li>
 *     <li>서버 연결은 같은 Activity Monitor의 세션·잠금 탭과 함께 쓰는 {@link AdminConnection}(격리된 실행 컨텍스트 하나)이다.
 *     SWT도 대시보드를 위해 따로 연결을 연다({@code dashboard.openSeparateConnection} 기본값).</li>
 *     <li>탭이 보일 때만 읽고 앞선 읽기가 끝나지 않았으면 그 차례를 건너뛴다. 처음 탭을 열 때만 연결하고, 그 뒤로는 연결이 끊겨 있으면
 *     읽지 않는다(간격마다 비밀번호를 묻지 않도록, 세션 탭과 같은 규칙). 다시 연결하려면 일시 중지 후 다시 시작을 누른다.</li>
 *     <li>읽지 못한 차트는 오류를 차트에 보이고 30초 동안 건너뛴다. SWT는 그 차트의 자동 갱신을 아예 끈다.</li>
 *     <li>보이는 차트, 순서, 넓게(두 칸) 여부는 드라이버마다 이 번들의 설정({@code dashboard.layout.<드라이버>})에, 한 줄의 차트 수와
 *     새로 고침 간격은 {@code dashboard.perRow}, {@code dashboard.refreshSeconds}에 둔다. 처음에는 정의의 {@code showByDefault} 차트를 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
final class DashboardPanel extends JPanel {

    private static final String PREF_REFRESH = "dashboard.refreshSeconds"; //$NON-NLS-1$
    private static final String PREF_PER_ROW = "dashboard.perRow"; //$NON-NLS-1$
    private static final String PREF_LAYOUT = "dashboard.layout."; //$NON-NLS-1$
    private static final String WIDE_SUFFIX = ":wide"; //$NON-NLS-1$
    // Saved when all charts are hidden: an empty value would read as "not set" and bring the default charts back
    private static final String NO_CHARTS = "-"; //$NON-NLS-1$
    private static final int[] INTERVALS = {1, 2, 5, 10, 30, 60};
    private static final int DEFAULT_INTERVAL = 2;
    private static final int DEFAULT_PER_ROW = 2;
    private static final int MAX_PER_ROW = 4;
    private static final long RETRY_MILLIS = 30_000;
    // Visible time span: this many reads (at least one minute, at most the age the chart keeps)
    private static final int WINDOW_POINTS = 150;

    /**
     * A chart on the dashboard.
     */
    private final class Entry {
        final DashboardItemConfiguration item;
        final DashboardHistory history;
        final DashboardChart chart;
        final ChartCard card;
        boolean wide;
        long retryAt;

        Entry(@NotNull DashboardItemConfiguration item, boolean wide) {
            this.item = item;
            this.wide = wide;
            history = new DashboardHistory(item);
            chart = new DashboardChart(item, history);
            chart.setMessage(DashboardMessages.dashboard_waiting);
            card = new ChartCard(this);
        }
    }

    private record IntervalItem(int seconds) {
        @Override
        public String toString() {
            return seconds < 60 ? NLS.bind(ConnectionMessages.auto_refresh_seconds, seconds)
                : NLS.bind(ConnectionMessages.auto_refresh_minutes, seconds / 60);
        }
    }

    private record PerRowItem(int count) {
        @Override
        public String toString() {
            return NLS.bind(DashboardMessages.dashboard_per_row, count);
        }
    }

    private final AdminConnection connection;
    private final DBPDataSourceContainer container;
    private final List<DashboardItemConfiguration> available;
    private final List<Entry> entries = new ArrayList<>();
    private final DashboardSampler sampler = new DashboardSampler();
    private final AtomicBoolean resetSampler = new AtomicBoolean();
    private final GridPanel grid = new GridPanel();
    private final JScrollPane gridScroll = new JScrollPane(grid);
    private final JLabel emptyLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel content = new JPanel(new CardLayout());
    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<IntervalItem> intervalBox;
    private final JComboBox<PerRowItem> perRowBox;
    private final Action pauseAction;
    private final Timer timer;
    private boolean paused;
    private boolean sampling;
    private int perRow;

    DashboardPanel(@NotNull AdminConnection connection) {
        super(new BorderLayout());
        this.connection = connection;
        this.container = connection.getContainer();
        this.available = ServerDashboards.getItems(container);
        DBPPreferenceStore preferences = getPreferences();

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        pauseAction = new AbstractAction(DashboardMessages.dashboard_pause) {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPaused(!paused);
            }
        };
        pauseAction.putValue(Action.SHORT_DESCRIPTION, DashboardMessages.dashboard_pause_tip);
        // The button takes the icon of the action when it changes (Pause: stop, Resume: run)
        toolBar.add(UiIcons.createToolButton(pauseAction, "stop")); //$NON-NLS-1$

        intervalBox = new JComboBox<>();
        for (int seconds : INTERVALS) {
            intervalBox.addItem(new IntervalItem(seconds));
        }
        intervalBox.setSelectedItem(new IntervalItem(preferences.contains(PREF_REFRESH) ? preferences.getInt(PREF_REFRESH) : DEFAULT_INTERVAL));
        if (intervalBox.getSelectedIndex() < 0) {
            intervalBox.setSelectedItem(new IntervalItem(DEFAULT_INTERVAL));
        }
        intervalBox.setToolTipText(DashboardMessages.dashboard_interval_tip);
        intervalBox.setMaximumSize(intervalBox.getPreferredSize());
        intervalBox.addActionListener(e -> {
            preferences.setValue(PREF_REFRESH, getIntervalSeconds());
            PrefUtils.savePreferenceStore(preferences);
            applyInterval();
        });
        toolBar.add(intervalBox);
        toolBar.addSeparator();

        Action chartsAction = new AbstractAction(DashboardMessages.dashboard_charts) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (e.getSource() instanceof Component source) {
                    showChartsMenu(source);
                }
            }
        };
        chartsAction.putValue(Action.SHORT_DESCRIPTION, DashboardMessages.dashboard_charts_tip);
        chartsAction.setEnabled(!available.isEmpty());
        toolBar.add(UiIcons.createToolButton(chartsAction, "layout")); //$NON-NLS-1$
        perRowBox = new JComboBox<>();
        for (int count = 1; count <= MAX_PER_ROW; count++) {
            perRowBox.addItem(new PerRowItem(count));
        }
        perRow = preferences.contains(PREF_PER_ROW) ? preferences.getInt(PREF_PER_ROW) : DEFAULT_PER_ROW;
        perRow = Math.max(1, Math.min(MAX_PER_ROW, perRow));
        perRowBox.setSelectedItem(new PerRowItem(perRow));
        perRowBox.setToolTipText(DashboardMessages.dashboard_per_row_tip);
        perRowBox.setMaximumSize(perRowBox.getPreferredSize());
        perRowBox.setEnabled(!available.isEmpty());
        perRowBox.addActionListener(e -> {
            if (perRowBox.getSelectedItem() instanceof PerRowItem item) {
                perRow = item.count();
                preferences.setValue(PREF_PER_ROW, perRow);
                PrefUtils.savePreferenceStore(preferences);
                grid.revalidate();
                grid.repaint();
            }
        });
        toolBar.add(perRowBox);

        gridScroll.setBorder(BorderFactory.createEmptyBorder());
        gridScroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(24));
        emptyLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        content.add(gridScroll, "grid"); //$NON-NLS-1$
        content.add(emptyLabel, "empty"); //$NON-NLS-1$

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        add(toolBar, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        loadLayout();
        timer = new Timer(1000, e -> {
            if (isShowing()) {
                sample(false);
            }
        });
        timer.setRepeats(true);
        applyInterval();
    }

    /**
     * First read when the tab is shown for the first time; connects if needed.
     */
    void refresh() {
        sample(true);
    }

    void dispose() {
        timer.stop();
    }

    // ---- Reading ----

    private void sample(boolean connect) {
        if (sampling || paused || entries.isEmpty()) {
            return;
        }
        if (!connect && !container.isConnected()) {
            statusLabel.setText(DashboardMessages.dashboard_status_not_connected);
            return;
        }
        long now = System.currentTimeMillis();
        List<DashboardItemConfiguration> targets = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.retryAt <= now) {
                targets.add(entry.item);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        sampling = true;
        connection.run("Read server dashboard", //$NON-NLS-1$
            (monitor, session, profile) -> {
                if (resetSampler.getAndSet(false)) {
                    sampler.reset();
                }
                return sampler.sample(monitor, session, targets);
            },
            this::applySamples,
            error -> {
                sampling = false;
                statusLabel.setText(NLS.bind(DashboardMessages.dashboard_status_error, ErrorText.describeLine(error)));
            });
    }

    private void applySamples(@NotNull Map<DashboardItemConfiguration, DashboardSample> samples) {
        sampling = false;
        long now = System.currentTimeMillis();
        int failed = 0;
        for (Map.Entry<DashboardItemConfiguration, DashboardSample> result : samples.entrySet()) {
            Entry entry = findEntry(result.getKey());
            if (entry == null) {
                // Hidden while it was read
                continue;
            }
            DashboardSample sample = result.getValue();
            if (sample.error() != null) {
                failed++;
                entry.retryAt = now + RETRY_MILLIS;
                entry.chart.setMessage(NLS.bind(DashboardMessages.dashboard_chart_failed, ErrorText.summary(sample.error())));
                continue;
            }
            entry.history.add(sample);
            entry.chart.setMessage(entry.history.isEmpty() ? DashboardMessages.dashboard_waiting : null);
        }
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(now));
        statusLabel.setText(paused ? DashboardMessages.dashboard_status_paused
            : failed == 0 ? NLS.bind(DashboardMessages.dashboard_status_updated, time)
            : NLS.bind(DashboardMessages.dashboard_status_failed, new Object[] {time, failed, samples.size()}));
    }

    @Nullable
    private Entry findEntry(@NotNull DashboardItemConfiguration item) {
        for (Entry entry : entries) {
            if (entry.item == item) {
                return entry;
            }
        }
        return null;
    }

    private void setPaused(boolean paused) {
        this.paused = paused;
        pauseAction.putValue(Action.NAME, paused ? DashboardMessages.dashboard_resume : DashboardMessages.dashboard_pause);
        pauseAction.putValue(Action.SHORT_DESCRIPTION, paused ? DashboardMessages.dashboard_resume_tip : DashboardMessages.dashboard_pause_tip);
        pauseAction.putValue(Action.SMALL_ICON, UiIcons.get(paused ? "run" : "stop")); //$NON-NLS-1$ //$NON-NLS-2$
        if (paused) {
            statusLabel.setText(DashboardMessages.dashboard_status_paused);
        } else {
            // The change per second over the pause is not a rate of the moment: start the counters again; retry failed charts
            resetSampler.set(true);
            for (Entry entry : entries) {
                entry.retryAt = 0;
            }
            sample(true);
        }
    }

    private int getIntervalSeconds() {
        return intervalBox.getSelectedItem() instanceof IntervalItem item ? item.seconds() : DEFAULT_INTERVAL;
    }

    private void applyInterval() {
        int seconds = getIntervalSeconds();
        timer.stop();
        timer.setDelay(seconds * 1000);
        timer.setInitialDelay(seconds * 1000);
        timer.start();
        for (Entry entry : entries) {
            updateWindow(entry);
        }
    }

    private void updateWindow(@NotNull Entry entry) {
        long intervalMillis = getIntervalSeconds() * 1000L;
        long window = Math.max(60_000, intervalMillis * WINDOW_POINTS);
        if (entry.item.getMaxAge() > 0) {
            window = Math.min(window, entry.item.getMaxAge());
        }
        entry.chart.setWindow(window, intervalMillis * 3 + 1000);
    }

    // ---- Layout ----

    private void loadLayout() {
        String saved = getPreferences().getString(getLayoutKey());
        List<Entry> loaded = new ArrayList<>();
        if (!CommonUtils.isEmpty(saved)) {
            Map<String, DashboardItemConfiguration> byId = new LinkedHashMap<>();
            for (DashboardItemConfiguration item : available) {
                byId.put(item.getId(), item);
            }
            for (String token : saved.split(",")) { //$NON-NLS-1$
                if (token.equals(NO_CHARTS)) {
                    continue;
                }
                boolean wide = token.endsWith(WIDE_SUFFIX);
                String id = wide ? token.substring(0, token.length() - WIDE_SUFFIX.length()) : token;
                DashboardItemConfiguration item = byId.remove(id);
                if (item != null) {
                    loaded.add(new Entry(item, wide));
                }
            }
        } else {
            for (DashboardItemConfiguration item : available) {
                if (item.isShowByDefault()) {
                    loaded.add(new Entry(item, false));
                }
            }
        }
        setEntries(loaded);
    }

    private void saveLayout() {
        StringBuilder layout = new StringBuilder();
        for (Entry entry : entries) {
            if (!layout.isEmpty()) {
                layout.append(',');
            }
            layout.append(entry.item.getId());
            if (entry.wide) {
                layout.append(WIDE_SUFFIX);
            }
        }
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(getLayoutKey(), layout.isEmpty() ? NO_CHARTS : layout.toString());
        PrefUtils.savePreferenceStore(preferences);
    }

    private void resetLayout() {
        getPreferences().setToDefault(getLayoutKey());
        PrefUtils.savePreferenceStore(getPreferences());
        loadLayout();
        sample(false);
    }

    private void setEntries(@NotNull List<Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        rebuildGrid();
    }

    private void rebuildGrid() {
        grid.removeAll();
        for (Entry entry : entries) {
            updateWindow(entry);
            grid.add(entry.card);
        }
        CardLayout cards = (CardLayout) content.getLayout();
        if (entries.isEmpty()) {
            emptyLabel.setText(available.isEmpty() ? DashboardMessages.dashboard_no_charts : DashboardMessages.dashboard_no_selection);
            cards.show(content, "empty"); //$NON-NLS-1$
        } else {
            cards.show(content, "grid"); //$NON-NLS-1$
        }
        grid.revalidate();
        grid.repaint();
    }

    private void showChartsMenu(@NotNull Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        for (DashboardItemConfiguration item : available) {
            Entry entry = findEntry(item);
            JCheckBoxMenuItem menuItem = new JCheckBoxMenuItem(DashboardLabels.getTitle(item), entry != null);
            String description = DashboardLabels.getDescription(item);
            if (!description.isEmpty()) {
                menuItem.setToolTipText(description);
            }
            menuItem.addActionListener(e -> {
                Entry current = findEntry(item);
                if (current != null) {
                    entries.remove(current);
                } else {
                    entries.add(new Entry(item, false));
                }
                rebuildGrid();
                saveLayout();
                if (current == null) {
                    sample(false);
                }
            });
            menu.add(menuItem);
        }
        menu.addSeparator();
        JMenuItem clearItem = new JMenuItem(DashboardMessages.dashboard_clear);
        clearItem.setToolTipText(DashboardMessages.dashboard_clear_tip);
        clearItem.setEnabled(!entries.isEmpty());
        clearItem.addActionListener(e -> {
            resetSampler.set(true);
            for (Entry entry : entries) {
                clearEntry(entry);
            }
        });
        menu.add(clearItem);
        JMenuItem resetItem = new JMenuItem(DashboardMessages.dashboard_reset_layout);
        resetItem.addActionListener(e -> resetLayout());
        menu.add(resetItem);
        menu.show(invoker, 0, invoker.getHeight());
    }

    private void showCardMenu(@NotNull Entry entry, @NotNull Component invoker) {
        int index = entries.indexOf(entry);
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem wideItem = new JCheckBoxMenuItem(DashboardMessages.dashboard_card_wide, entry.wide);
        wideItem.setEnabled(perRow > 1);
        wideItem.addActionListener(e -> {
            entry.wide = !entry.wide;
            grid.revalidate();
            grid.repaint();
            saveLayout();
        });
        menu.add(wideItem);
        JMenuItem leftItem = new JMenuItem(DashboardMessages.dashboard_card_move_left);
        leftItem.setEnabled(index > 0);
        leftItem.addActionListener(e -> moveEntry(entry, -1));
        menu.add(leftItem);
        JMenuItem rightItem = new JMenuItem(DashboardMessages.dashboard_card_move_right);
        rightItem.setEnabled(index >= 0 && index < entries.size() - 1);
        rightItem.addActionListener(e -> moveEntry(entry, 1));
        menu.add(rightItem);
        menu.addSeparator();
        JMenuItem clearItem = new JMenuItem(DashboardMessages.dashboard_card_clear);
        clearItem.addActionListener(e -> clearEntry(entry));
        menu.add(clearItem);
        JMenuItem hideItem = new JMenuItem(DashboardMessages.dashboard_card_hide);
        hideItem.addActionListener(e -> {
            entries.remove(entry);
            rebuildGrid();
            saveLayout();
        });
        menu.add(hideItem);
        menu.show(invoker, 0, invoker.getHeight());
    }

    private void moveEntry(@NotNull Entry entry, int offset) {
        int index = entries.indexOf(entry);
        int target = index + offset;
        if (index < 0 || target < 0 || target >= entries.size()) {
            return;
        }
        entries.remove(index);
        entries.add(target, entry);
        rebuildGrid();
        saveLayout();
    }

    private void clearEntry(@NotNull Entry entry) {
        // Delta charts need a new base read; the next reads fill the chart again
        resetSampler.set(true);
        entry.history.clear();
        entry.retryAt = 0;
        entry.chart.setMessage(DashboardMessages.dashboard_waiting);
    }

    @NotNull
    private String getLayoutKey() {
        return PREF_LAYOUT + container.getDriver().getProviderId() + "." + container.getDriver().getId(); //$NON-NLS-1$
    }

    @NotNull
    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Rounded card of a chart: title, options button, the chart.
     */
    private final class ChartCard extends JPanel {

        private final Entry entry;

        ChartCard(@NotNull Entry entry) {
            super(new BorderLayout());
            this.entry = entry;
            setOpaque(false);
            int pad = UIScale.scale(10);
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), pad, pad, UIScale.scale(6)));
            String titleText = DashboardLabels.getTitle(entry.item);
            JLabel title = new JLabel(titleText);
            title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            // The title is cut in a narrow card: the tool tip has it in full, with the description
            String description = DashboardLabels.getDescription(entry.item);
            title.setToolTipText(description.isEmpty() ? titleText
                : "<html><b>" + escapeHtml(titleText) + "</b><br>" + escapeHtml(description) + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Action optionsAction = new AbstractAction(DashboardMessages.dashboard_card_options_tip) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (e.getSource() instanceof Component source) {
                        showCardMenu(entry, source);
                    }
                }
            };
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.add(title, BorderLayout.CENTER);
            header.add(UiIcons.createActionButton(optionsAction, "more"), BorderLayout.EAST); //$NON-NLS-1$
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(4), UIScale.scale(4)));
            add(header, BorderLayout.NORTH);
            add(entry.chart, BorderLayout.CENTER);
        }

        boolean isWide() {
            return entry.wide;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float arc = UIScale.scale(12f);
                Color border = UIManager.getColor("Component.borderColor"); //$NON-NLS-1$
                g2.setColor(getParent() != null ? getParent().getBackground() : getBackground());
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, arc, arc));
                g2.setColor(border != null ? border : Color.LIGHT_GRAY);
                g2.setStroke(new BasicStroke(UIScale.scale(1f)));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, arc, arc));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Grid of chart cards: {@link #perRow} cards in a row, a wide card takes two cells. Rows grow to fill the visible height
     * (up to a limit) and scroll when they do not fit.
     */
    private final class GridPanel extends JPanel implements Scrollable {

        GridPanel() {
            setLayout(new GridLayoutManager());
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(8), UIScale.scale(8)));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UIScale.scale(24);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - UIScale.scale(24), UIScale.scale(24));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }

    private final class GridLayoutManager implements LayoutManager {

        private int gap() {
            return UIScale.scale(8);
        }

        private int minRowHeight() {
            return UIScale.scale(230);
        }

        private int maxRowHeight() {
            return UIScale.scale(420);
        }

        /**
         * Row and column of each card, and the number of rows.
         */
        private int place(@NotNull Container parent, @Nullable int[][] cells) {
            int columns = Math.max(1, perRow);
            int row = 0;
            int column = 0;
            for (int i = 0; i < parent.getComponentCount(); i++) {
                int span = parent.getComponent(i) instanceof ChartCard card && card.isWide() ? Math.min(2, columns) : 1;
                if (column + span > columns) {
                    row++;
                    column = 0;
                }
                if (cells != null) {
                    cells[i] = new int[] {row, column, span};
                }
                column += span;
                if (column >= columns) {
                    row++;
                    column = 0;
                }
            }
            return column == 0 ? row : row + 1;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int rows = place(parent, null);
            return new Dimension(UIScale.scale(400) + insets.left + insets.right,
                rows * minRowHeight() + Math.max(0, rows - 1) * gap() + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int count = parent.getComponentCount();
            int[][] cells = new int[count][];
            int rows = place(parent, cells);
            if (rows == 0) {
                return;
            }
            int columns = Math.max(1, perRow);
            int width = parent.getWidth() - insets.left - insets.right;
            int height = parent.getHeight() - insets.top - insets.bottom;
            int gap = gap();
            double cellWidth = (width - gap * (columns - 1)) / (double) columns;
            int rowHeight = Math.max(minRowHeight(), Math.min(maxRowHeight(), (height - gap * (rows - 1)) / rows));
            for (int i = 0; i < count; i++) {
                int[] cell = cells[i];
                int x = insets.left + (int) Math.round(cell[1] * (cellWidth + gap));
                int right = insets.left + (int) Math.round((cell[1] + cell[2]) * (cellWidth + gap) - gap);
                int y = insets.top + cell[0] * (rowHeight + gap);
                parent.getComponent(i).setBounds(x, y, right - x, rowHeight);
            }
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }
}
