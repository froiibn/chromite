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

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.admin.dashboard.DashboardHistory;
import com.seizia.chromite.model.admin.dashboard.DashboardLabels;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.dashboard.DBDashboardCalcType;
import com.seizia.chromite.model.dashboard.DBDashboardValueType;
import com.seizia.chromite.model.dashboard.registry.DashboardItemConfiguration;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 서버 대시보드의 시계열 차트 하나(범례, 값 축, 시간 축, 선, 마우스를 올린 시각의 값).
 *
 * 설계: SWT 대시보드는 JFreeChart({@code DashboardRendererTimeseries}, XY 선 차트)로 그린다. Swing에서는 작은 Java2D 차트를 직접 만들었다
 * (docs/chromite/swing-dashboard.md "차트 라이브러리"): 필요한 것은 계열 몇 개의 선, 0부터 시작하는 값 축, 시간 축, 범례, 마우스 위치의 값뿐이고,
 * JFreeChart를 쓰면 LGPL 라이브러리(1.6 MB)를 Swing 제품에 새로 넣고 테마를 바꿀 때마다 차트 색을 다시 넣어야 한다.
 * 이 차트는 그릴 때마다 Look and Feel의 색을 읽으므로 라이트·다크 전환을 따로 처리하지 않는다.
 * <ul>
 *     <li>계열 색은 처음 나온 순서로 고정한 8색(라이트·다크 각각 고른 값, 색각 이상 구분을 검사한 순서)이다. 텍스트는 계열 색이 아니라 글자 색으로 쓴다.</li>
 *     <li>값 축은 0부터 보기 좋은 최댓값까지(백분율은 0~100), 눈금 4칸이다. 시간 축은 최근 {@link #setWindow 창 길이}만큼 보인다.</li>
 *     <li>두 점 사이가 새로 고침 간격의 세 배보다 멀면(일시 중지, 탭을 가린 동안) 선을 잇지 않는다.</li>
 *     <li>계열이 하나면 선 아래를 옅게 칠한다. 계열이 둘 이상이면 범례에 현재 값과 함께 이름을 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
final class DashboardChart extends JComponent {

    // Categorical series colors, light and dark steps of the same eight hues (validated order, docs/chromite/swing-dashboard.md)
    private static final Color[] LIGHT_SERIES = colors(0x2a78d6, 0xeb6834, 0x1baf7a, 0xeda100, 0xe87ba4, 0x008300, 0x6250d6, 0xe34948);
    private static final Color[] DARK_SERIES = colors(0x3987e5, 0xd95926, 0x199e70, 0xc98500, 0xd55181, 0x008300, 0x9085e9, 0xe66767);
    private static final int TICKS = 4;

    private final DashboardItemConfiguration item;
    private final DashboardHistory history;
    private long windowMillis = 300_000;
    private long gapMillis = 7_000;
    @Nullable
    private String message;
    private int hoverX = -1;
    // Plot bounds of the last paint, for the mouse
    private final Rectangle plot = new Rectangle();

    DashboardChart(@NotNull DashboardItemConfiguration item, @NotNull DashboardHistory history) {
        this.item = item;
        this.history = history;
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverX = plot.contains(e.getPoint()) ? e.getX() : -1;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverX = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /**
     * Visible time span and the distance after which two points are not joined.
     */
    void setWindow(long windowMillis, long gapMillis) {
        this.windowMillis = windowMillis;
        this.gapMillis = gapMillis;
        repaint();
    }

    /**
     * Text shown over the plot (waiting for values, read error), or null.
     */
    void setMessage(@Nullable String message) {
        this.message = message;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UIScale.scale(320), UIScale.scale(170));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintChart(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintChart(@NotNull Graphics2D g) {
        Color foreground = color("Label.foreground", Color.BLACK); //$NON-NLS-1$
        Color secondary = color("Chromite.secondaryForeground", Color.GRAY); //$NON-NLS-1$
        Color gridColor = color("Chromite.dividerColor", Color.LIGHT_GRAY); //$NON-NLS-1$
        Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
        Font small = font.deriveFont(font.getSize2D() - UIScale.scale(1f));
        int pad = UIScale.scale(4);

        List<String> names = history.getSeriesNames();
        Color[] palette = FlatLaf.isLafDark() ? DARK_SERIES : LIGHT_SERIES;

        // Legend: swatch, name and the newest value of each series
        g.setFont(small);
        FontMetrics fm = g.getFontMetrics();
        int y = 0;
        int legendHeight = 0;
        if (!names.isEmpty()) {
            int x = pad;
            int lineHeight = fm.getHeight();
            int swatch = UIScale.scale(10);
            legendHeight = lineHeight;
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                DashboardHistory.Value last = history.getLast(name);
                String label = DashboardLabels.getSeries(name);
                String value = last == null ? "" : " " + formatValue(last.value()); //$NON-NLS-1$
                int width = swatch + UIScale.scale(5) + fm.stringWidth(label) + fm.stringWidth(value) + UIScale.scale(14);
                if (x > pad && x + width > getWidth()) {
                    x = pad;
                    y += lineHeight;
                    legendHeight += lineHeight;
                }
                g.setColor(palette[i % palette.length]);
                g.fill(new RoundRectangle2D.Float(x, y + (lineHeight - swatch) / 2f, swatch, swatch, UIScale.scale(3), UIScale.scale(3)));
                int textX = x + swatch + UIScale.scale(5);
                g.setColor(secondary);
                g.drawString(label, textX, y + fm.getAscent());
                g.setColor(foreground);
                g.drawString(value, textX + fm.stringWidth(label), y + fm.getAscent());
                x += width;
            }
            legendHeight += UIScale.scale(6);
        }

        // Value range from 0 (percent: 0..100) over the visible points
        long end = history.getLastTime();
        long start = end - windowMillis;
        double max = 0;
        for (String name : names) {
            for (DashboardHistory.Value value : history.getValues(name)) {
                if (value.time() >= start) {
                    max = Math.max(max, value.value());
                }
            }
        }
        double top = item.getValueType() == DBDashboardValueType.percent ? 100 : niceCeiling(max);
        double step = top / TICKS;

        int axisWidth = 0;
        for (int i = 0; i <= TICKS; i++) {
            axisWidth = Math.max(axisWidth, fm.stringWidth(formatAxis(step * i)));
        }
        plot.setBounds(pad + axisWidth + UIScale.scale(6), legendHeight + fm.getAscent() / 2,
            0, 0);
        plot.width = Math.max(1, getWidth() - plot.x - pad - UIScale.scale(2));
        plot.height = Math.max(1, getHeight() - plot.y - fm.getHeight() - UIScale.scale(4));

        // Horizontal grid lines with the value labels
        g.setStroke(new BasicStroke(UIScale.scale(1f)));
        for (int i = 0; i <= TICKS; i++) {
            int gy = plot.y + plot.height - (int) Math.round(plot.height * (double) i / TICKS);
            g.setColor(gridColor);
            g.drawLine(plot.x, gy, plot.x + plot.width, gy);
            String label = formatAxis(step * i);
            g.setColor(secondary);
            g.drawString(label, plot.x - UIScale.scale(6) - fm.stringWidth(label), gy + fm.getAscent() / 2 - UIScale.scale(1));
        }

        // Time labels under the plot
        if (end > 0) {
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss"); //$NON-NLS-1$
            int labels = Math.max(2, Math.min(5, plot.width / UIScale.scale(90)));
            int nextLeft = Integer.MIN_VALUE;
            // From the newest time backwards: in a narrow chart the labels that do not fit are left out, the newest stays
            for (int i = labels - 1; i >= 0; i--) {
                long time = start + windowMillis * i / (labels - 1);
                String label = timeFormat.format(new Date(time));
                int width = fm.stringWidth(label);
                int lx = toX(time, start) - width / 2;
                lx = Math.max(plot.x, Math.min(plot.x + plot.width - width, lx));
                if (nextLeft != Integer.MIN_VALUE && lx + width + UIScale.scale(8) > nextLeft) {
                    continue;
                }
                nextLeft = lx;
                g.setColor(secondary);
                g.drawString(label, lx, plot.y + plot.height + UIScale.scale(3) + fm.getAscent());
            }
        }

        // Series lines
        Shape oldClip = g.getClip();
        g.clipRect(plot.x, plot.y - UIScale.scale(2), plot.width + 1, plot.height + UIScale.scale(4));
        BasicStroke line = new BasicStroke(UIScale.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        for (int i = 0; i < names.size(); i++) {
            List<DashboardHistory.Value> values = history.getValues(names.get(i));
            Color seriesColor = palette[i % palette.length];
            List<Path2D> segments = buildSegments(values, start, top);
            if (names.size() == 1) {
                // One series: a light area under the line
                g.setColor(new Color(seriesColor.getRed(), seriesColor.getGreen(), seriesColor.getBlue(), 28));
                for (Path2D segment : segments) {
                    Path2D area = (Path2D) segment.clone();
                    Rectangle bounds = segment.getBounds();
                    area.lineTo(bounds.x + bounds.width, plot.y + plot.height);
                    area.lineTo(bounds.x, plot.y + plot.height);
                    area.closePath();
                    g.fill(area);
                }
            }
            g.setColor(seriesColor);
            g.setStroke(line);
            for (Path2D segment : segments) {
                g.draw(segment);
            }
        }
        g.setClip(oldClip);

        if (message != null) {
            paintMessage(g, message, secondary);
        } else if (hoverX >= 0 && !names.isEmpty() && end > 0) {
            paintHover(g, names, palette, start, top, small);
        }
    }

    @NotNull
    private List<Path2D> buildSegments(@NotNull List<DashboardHistory.Value> values, long start, double top) {
        List<Path2D> segments = new ArrayList<>();
        Path2D current = null;
        long previousTime = 0;
        for (DashboardHistory.Value value : values) {
            if (value.time() < start - gapMillis) {
                continue;
            }
            double px = toX(value.time(), start);
            double py = toY(value.value(), top);
            if (current == null || value.time() - previousTime > gapMillis) {
                current = new Path2D.Double();
                current.moveTo(px, py);
                segments.add(current);
            } else {
                current.lineTo(px, py);
            }
            previousTime = value.time();
        }
        // A single point is drawn as a short dash so that it is visible
        for (Path2D segment : segments) {
            Rectangle bounds = segment.getBounds();
            if (bounds.width == 0 && bounds.height == 0) {
                segment.lineTo(bounds.x + 0.1, bounds.y);
            }
        }
        return segments;
    }

    /**
     * Crosshair at the mouse with the values of the nearest points.
     */
    private void paintHover(@NotNull Graphics2D g, @NotNull List<String> names, @NotNull Color[] palette, long start, double top, @NotNull Font font) {
        long time = start + Math.round((double) (hoverX - plot.x) / plot.width * windowMillis);
        List<String> lines = new ArrayList<>();
        List<Color> lineColors = new ArrayList<>();
        long shownTime = -1;
        Color surface = color("Chromite.island.background", Color.WHITE); //$NON-NLS-1$
        g.setColor(color("Chromite.secondaryForeground", Color.GRAY)); //$NON-NLS-1$
        g.setStroke(new BasicStroke(UIScale.scale(1f)));
        g.drawLine(hoverX, plot.y, hoverX, plot.y + plot.height);
        int radius = UIScale.scale(4);
        for (int i = 0; i < names.size(); i++) {
            DashboardHistory.Value nearest = null;
            for (DashboardHistory.Value value : history.getValues(names.get(i))) {
                if (value.time() >= start && (nearest == null || Math.abs(value.time() - time) < Math.abs(nearest.time() - time))) {
                    nearest = value;
                }
            }
            if (nearest == null || Math.abs(nearest.time() - time) > gapMillis) {
                continue;
            }
            shownTime = Math.max(shownTime, nearest.time());
            int px = toX(nearest.time(), start);
            int py = toY(nearest.value(), top);
            g.setColor(surface);
            g.fillOval(px - radius - 1, py - radius - 1, (radius + 1) * 2, (radius + 1) * 2);
            g.setColor(palette[i % palette.length]);
            g.fillOval(px - radius, py - radius, radius * 2, radius * 2);
            lines.add(DashboardLabels.getSeries(names.get(i)) + "  " + formatValue(nearest.value())); //$NON-NLS-1$
            lineColors.add(palette[i % palette.length]);
        }
        if (lines.isEmpty()) {
            return;
        }
        String header = new SimpleDateFormat("HH:mm:ss").format(new Date(shownTime)); //$NON-NLS-1$
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int swatch = UIScale.scale(8);
        int padding = UIScale.scale(8);
        int width = fm.stringWidth(header);
        for (String text : lines) {
            width = Math.max(width, swatch + UIScale.scale(6) + fm.stringWidth(text));
        }
        width += padding * 2;
        int height = fm.getHeight() * (lines.size() + 1) + padding * 2 - UIScale.scale(2);
        int boxX = hoverX + UIScale.scale(12);
        if (boxX + width > getWidth()) {
            boxX = hoverX - UIScale.scale(12) - width;
        }
        boxX = Math.max(0, boxX);
        int boxY = plot.y + UIScale.scale(4);
        g.setColor(color("ToolTip.background", Color.DARK_GRAY)); //$NON-NLS-1$
        g.fill(new RoundRectangle2D.Float(boxX, boxY, width, height, UIScale.scale(8), UIScale.scale(8)));
        Color tipForeground = color("ToolTip.foreground", Color.WHITE); //$NON-NLS-1$
        g.setColor(tipForeground);
        int textY = boxY + padding + fm.getAscent() - UIScale.scale(1);
        g.drawString(header, boxX + padding, textY);
        for (int i = 0; i < lines.size(); i++) {
            textY += fm.getHeight();
            g.setColor(lineColors.get(i));
            g.fillRect(boxX + padding, textY - fm.getAscent() / 2 - swatch / 2 + UIScale.scale(1), swatch, swatch);
            g.setColor(tipForeground);
            g.drawString(lines.get(i), boxX + padding + swatch + UIScale.scale(6), textY);
        }
    }

    private void paintMessage(@NotNull Graphics2D g, @NotNull String text, @NotNull Color foreground) {
        g.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        FontMetrics fm = g.getFontMetrics();
        // Wrap to the plot width, at most three lines
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) { //$NON-NLS-1$
            String candidate = current.isEmpty() ? word : current + " " + word; //$NON-NLS-1$
            if (fm.stringWidth(candidate) > plot.width - UIScale.scale(16) && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        if (lines.size() > 3) {
            lines = new ArrayList<>(lines.subList(0, 3));
            lines.set(2, lines.get(2) + "…"); //$NON-NLS-1$
        }
        int textY = plot.y + (plot.height - fm.getHeight() * lines.size()) / 2 + fm.getAscent();
        g.setColor(foreground);
        for (String line : lines) {
            g.drawString(line, plot.x + (plot.width - fm.stringWidth(line)) / 2, textY);
            textY += fm.getHeight();
        }
    }

    private int toX(long time, long start) {
        return plot.x + (int) Math.round((double) (time - start) / windowMillis * plot.width);
    }

    private int toY(double value, double top) {
        double ratio = top <= 0 ? 0 : Math.min(1.02, value / top);
        return plot.y + plot.height - (int) Math.round(ratio * plot.height);
    }

    /**
     * A value with its unit: bytes as B/KB/MB/GB, percent, numbers; "/s" for the per second charts.
     */
    @NotNull
    String formatValue(double value) {
        boolean perSecond = item.getCalcType() == DBDashboardCalcType.delta;
        String text = switch (item.getValueType()) {
            case bytes -> formatBytes(value);
            case percent -> formatNumber(value, 1) + "%"; //$NON-NLS-1$
            case integer -> formatNumber(value, 0);
            case decimal -> formatNumber(value, 2);
        };
        return perSecond && item.getValueType() != DBDashboardValueType.percent ? text + "/s" : text; //$NON-NLS-1$
    }

    @NotNull
    private String formatAxis(double value) {
        return switch (item.getValueType()) {
            case bytes -> formatBytes(value);
            case percent -> formatNumber(value, 0) + "%"; //$NON-NLS-1$
            default -> formatCompact(value);
        };
    }

    @NotNull
    private static String formatBytes(double value) {
        String[] units = {"B", "KB", "MB", "GB", "TB"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        int unit = 0;
        while (Math.abs(value) >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return formatNumber(value, unit == 0 || Math.abs(value) >= 100 ? 0 : 1) + " " + units[unit]; //$NON-NLS-1$
    }

    @NotNull
    private static String formatCompact(double value) {
        if (Math.abs(value) >= 1_000_000) {
            return formatNumber(value / 1_000_000, Math.abs(value) >= 10_000_000 ? 0 : 1) + "M"; //$NON-NLS-1$
        }
        if (Math.abs(value) >= 10_000) {
            return formatNumber(value / 1_000, 0) + "K"; //$NON-NLS-1$
        }
        return formatNumber(value, Math.abs(value) < 10 && value != Math.rint(value) ? 1 : 0);
    }

    @NotNull
    private static String formatNumber(double value, int fractionDigits) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(fractionDigits);
        format.setMinimumFractionDigits(0);
        return format.format(value);
    }

    /**
     * Top of the value axis: 1, 2, 2.5 or 5 times a power of ten, so that the four ticks are round numbers.
     */
    private static double niceCeiling(double max) {
        if (max <= 0) {
            return TICKS;
        }
        double raw = max * 1.1 / TICKS;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double[] steps = {1, 2, 2.5, 5, 10};
        for (double candidate : steps) {
            if (candidate * magnitude >= raw) {
                double step = candidate * magnitude;
                // Whole numbers for counts: no 0.25 ticks
                if (step < 1 && max >= 1) {
                    step = 1;
                }
                return step * TICKS;
            }
        }
        return 10 * magnitude * TICKS;
    }

    @NotNull
    private static Color color(@NotNull String key, @NotNull Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    @NotNull
    private static Color[] colors(int... rgb) {
        Color[] result = new Color[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            result[i] = new Color(rgb[i]);
        }
        return result;
    }
}
