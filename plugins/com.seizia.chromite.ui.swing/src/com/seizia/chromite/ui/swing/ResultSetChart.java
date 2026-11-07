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

import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 결과 표의 차트 하나(선, 막대, 원형)를 그린다. 무엇을 그릴지는 {@link ResultSetChartView}가 {@link Data}로 넘긴다.
 *
 * 설계: 서버 대시보드 차트({@link DashboardChart})처럼 Java2D로 직접 그린다. JFreeChart는 제품에 없고(LGPL 1.6 MB를 새로 넣어야 함),
 * 선·막대·원형과 축, 범례, 마우스 위치의 값, PNG 저장만 필요하기 때문이다. 그릴 때마다 Look and Feel의 색을 읽으므로 테마 전환을 따로 처리하지 않는다.
 * <ul>
 *     <li>계열 색은 대시보드와 같은 검증된 8색({@link DashboardChart#seriesPalette()})을 같은 순서로 쓴다. 계열은 8개까지다(보기가 막는다).
 *     원형은 8조각까지 그리고, 더 많으면 큰 7조각과 회색 "Other"로 묶는다.</li>
 *     <li>값 축은 하나다. 막대는 0에서 시작하고(음수는 0 아래로), 선은 값의 범위에 맞춘다. 눈금은 1·2·2.5·5의 10의 거듭제곱 배다.</li>
 *     <li>가로축은 범주(행마다 같은 간격, 표의 표시 문자열)이거나, 선 차트에서 X 열이 숫자·날짜면 값에 비례한 위치다.
 *     글자가 겹치면 범주 이름을 건너뛴다.</li>
 *     <li>계열이 둘 이상이면 범례를 위에 두고, 하나면 그 이름을 축 제목처럼 위에 쓴다. 글자는 계열 색이 아니라 글자 색이다.</li>
 *     <li>마우스를 올리면 선은 세로선과 점, 막대는 그 범주의 띠, 원형은 그 조각을 강조하고 값 상자를 보인다. PNG로 저장할 때는 그리지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-result-charts-spatial.md)
 */
final class ResultSetChart extends JComponent {

    enum Type {
        LINE, BAR, PIE
    }

    /**
     * One value column: its values per point, NaN where the cell is empty or not a number.
     */
    record Series(@NotNull String name, @NotNull double[] values) {
    }

    /**
     * What to draw.
     *
     * @param xTitle   the name of the X axis (column name or "Row")
     * @param labels   the category label of each point
     * @param xValues  positions of the points on a proportional X axis (numbers or epoch milliseconds, ascending), or
     *                 null for evenly spaced categories
     * @param timeAxis whether {@code xValues} are epoch milliseconds
     */
    record Data(
        @NotNull Type type,
        @NotNull String xTitle,
        @NotNull String[] labels,
        @Nullable double[] xValues,
        boolean timeAxis,
        @NotNull List<Series> series
    ) {
    }

    private static final int MAX_PIE_SLICES = 8;
    private static final int VALUE_TICKS = 4;
    private static final int MARKER_LIMIT = 50;

    @Nullable
    private Data data;
    @Nullable
    private String message;
    @Nullable
    private Point hover;
    // Geometry of the last paint, for the mouse
    private final Rectangle plot = new Rectangle();
    private final List<Shape> sliceShapes = new ArrayList<>();

    ResultSetChart() {
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover = e.getPoint();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /**
     * The chart to draw, or a message instead of it (no values, nothing chosen).
     */
    void setChart(@Nullable Data data, @Nullable String message) {
        this.data = data;
        this.message = message;
        // The mouse may be elsewhere by now (the view was hidden): the highlight comes back with the next move
        hover = null;
        repaint();
    }

    boolean hasChart() {
        return data != null && message == null;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UIScale.scale(480), UIScale.scale(300));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            setHints(g2);
            paintChart(g2, getWidth(), getHeight(), true);
        } finally {
            g2.dispose();
        }
    }

    /**
     * The chart as an image of the component's size on a solid background (no mouse highlight), for "Save as PNG".
     *
     * @param scale pixels per component unit (the screen scale, so the image is as sharp as on screen)
     */
    @NotNull
    BufferedImage createImage(@NotNull Color background, double scale) {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        BufferedImage image = new BufferedImage((int) Math.ceil(width * scale), (int) Math.ceil(height * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(background);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(scale, scale);
            setHints(g);
            paintChart(g, width, height, false);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void setHints(@NotNull Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private void paintChart(@NotNull Graphics2D g, int width, int height, boolean interactive) {
        Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
        g.setFont(font.deriveFont(font.getSize2D() - UIScale.scale(1f)));
        Point mouse = interactive ? hover : null;
        sliceShapes.clear();
        if (message != null || data == null) {
            paintMessage(g, message == null ? "" : message, new Rectangle(0, 0, width, height)); //$NON-NLS-1$
            return;
        }
        if (data.type() == Type.PIE) {
            paintPie(g, data, width, height, mouse);
        } else {
            paintXY(g, data, width, height, mouse);
        }
    }

    //////////////////////////////////////////////////////////
    // Line and bar

    private void paintXY(@NotNull Graphics2D g, @NotNull Data data, int width, int height, @Nullable Point mouse) {
        Color foreground = color("Label.foreground", Color.BLACK); //$NON-NLS-1$
        Color secondary = color("Chromite.secondaryForeground", Color.GRAY); //$NON-NLS-1$
        Color gridColor = color("Chromite.dividerColor", Color.LIGHT_GRAY); //$NON-NLS-1$
        Color[] palette = DashboardChart.seriesPalette();
        FontMetrics fm = g.getFontMetrics();
        int pad = UIScale.scale(8);
        List<Series> series = data.series();
        boolean bar = data.type() == Type.BAR;
        int count = data.labels().length;

        // Legend for two or more series; the name of a single series as the axis title
        int top = pad;
        if (series.size() > 1) {
            top += paintLegend(g, series, palette, pad, top, width - pad * 2, foreground);
        } else {
            g.setColor(secondary);
            g.drawString(fit(series.get(0).name(), fm, width - pad * 2), pad, top + fm.getAscent());
            top += fm.getHeight() + UIScale.scale(4);
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (double value : s.values()) {
                if (Double.isFinite(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }
        if (min > max) {
            paintMessage(g, EditorMessages.editor_chart_no_values, new Rectangle(0, top, width, height - top));
            return;
        }
        if (bar) {
            // Bars grow from zero
            min = Math.min(min, 0);
            max = Math.max(max, 0);
        }
        double[] axis = niceAxis(min, max, VALUE_TICKS);
        double low = axis[0];
        double high = axis[1];
        double step = axis[2];
        int ticks = (int) Math.round((high - low) / step);

        int axisWidth = 0;
        for (int i = 0; i <= ticks; i++) {
            axisWidth = Math.max(axisWidth, fm.stringWidth(formatAxis(low + step * i, step)));
        }
        int bottomText = fm.getHeight() * 2 + UIScale.scale(6);
        plot.setBounds(pad + axisWidth + UIScale.scale(6), top + fm.getAscent() / 2,
            Math.max(1, width - pad * 2 - axisWidth - UIScale.scale(8)), Math.max(1, height - top - fm.getAscent() / 2 - bottomText - pad / 2));

        // Horizontal grid lines with the value labels
        g.setStroke(new BasicStroke(UIScale.scale(1f)));
        for (int i = 0; i <= ticks; i++) {
            double value = low + step * i;
            int y = toY(value, low, high);
            g.setColor(gridColor);
            g.drawLine(plot.x, y, plot.x + plot.width, y);
            String label = formatAxis(value, step);
            g.setColor(secondary);
            g.drawString(label, plot.x - UIScale.scale(6) - fm.stringWidth(label), y + fm.getAscent() / 2 - UIScale.scale(1));
        }

        double[] positions = pointPositions(data, count);
        double slot = count > 0 ? (double) plot.width / count : plot.width;
        paintXLabels(g, data, positions, slot, secondary);
        // X axis title under the labels
        String xTitle = fit(data.xTitle(), fm, plot.width);
        g.setColor(secondary);
        g.drawString(xTitle, plot.x + (plot.width - fm.stringWidth(xTitle)) / 2, plot.y + plot.height + UIScale.scale(4) + fm.getHeight() + fm.getAscent());

        int hoverIndex = mouse != null && plot.contains(mouse) && count > 0 ? nearestPoint(positions, mouse.x) : -1;
        Shape oldClip = g.getClip();
        g.clipRect(plot.x - UIScale.scale(6), plot.y - UIScale.scale(6), plot.width + UIScale.scale(12), plot.height + UIScale.scale(12));
        if (bar) {
            if (hoverIndex >= 0) {
                Color band = color("Table.selectionInactiveBackground", gridColor); //$NON-NLS-1$
                g.setColor(band);
                g.fill(new Rectangle2D.Double(positions[hoverIndex] - slot / 2, plot.y, slot, plot.height));
            }
            paintBars(g, series, palette, positions, slot, low, high);
        } else {
            paintLines(g, series, palette, positions, low, high, count <= MARKER_LIMIT);
        }
        g.setClip(oldClip);
        // Zero line over the bars and lines when the values cross it
        if (low < 0 && high > 0) {
            g.setColor(secondary);
            g.setStroke(new BasicStroke(UIScale.scale(1f)));
            int zero = toY(0, low, high);
            g.drawLine(plot.x, zero, plot.x + plot.width, zero);
        }

        if (hoverIndex >= 0) {
            if (!bar) {
                int x = (int) Math.round(positions[hoverIndex]);
                g.setColor(secondary);
                g.setStroke(new BasicStroke(UIScale.scale(1f)));
                g.drawLine(x, plot.y, x, plot.y + plot.height);
                int radius = UIScale.scale(4);
                for (int i = 0; i < series.size(); i++) {
                    double value = series.get(i).values()[hoverIndex];
                    if (Double.isFinite(value)) {
                        int y = toY(value, low, high);
                        paintMarker(g, x, y, radius, palette[i]);
                    }
                }
            }
            List<String> lines = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            for (int i = 0; i < series.size(); i++) {
                double value = series.get(i).values()[hoverIndex];
                lines.add(series.get(i).name() + "  " + (Double.isFinite(value) ? formatValue(value) : EditorMessages.editor_chart_empty_value)); //$NON-NLS-1$
                colors.add(palette[i]);
            }
            paintTip(g, data.labels()[hoverIndex], lines, colors, mouse.x, plot.y + UIScale.scale(4), width);
        }
    }

    /**
     * The x position of each point: the middle of its category slot, or proportional to its X value.
     */
    @NotNull
    private double[] pointPositions(@NotNull Data data, int count) {
        double[] positions = new double[count];
        double[] xValues = data.type() == Type.LINE ? data.xValues() : null;
        if (xValues != null && count > 0) {
            double first = xValues[0];
            double last = xValues[count - 1];
            for (int i = 0; i < count; i++) {
                positions[i] = last > first ? plot.x + (xValues[i] - first) / (last - first) * plot.width : plot.x + plot.width / 2.0;
            }
        } else {
            double slot = count > 0 ? (double) plot.width / count : plot.width;
            for (int i = 0; i < count; i++) {
                positions[i] = plot.x + slot * (i + 0.5);
            }
        }
        return positions;
    }

    private void paintXLabels(@NotNull Graphics2D g, @NotNull Data data, @NotNull double[] positions, double slot, @NotNull Color color) {
        FontMetrics fm = g.getFontMetrics();
        int baseline = plot.y + plot.height + UIScale.scale(4) + fm.getAscent();
        g.setColor(color);
        double[] xValues = data.type() == Type.LINE ? data.xValues() : null;
        if (xValues != null && xValues.length > 1 && xValues[xValues.length - 1] > xValues[0]) {
            // A proportional axis: round ticks between the first and the last value
            double first = xValues[0];
            double last = xValues[xValues.length - 1];
            List<Double> ticks = new ArrayList<>();
            String pattern = null;
            if (data.timeAxis()) {
                pattern = datePattern(last - first);
                int labelCount = Math.max(2, Math.min(6, plot.width / UIScale.scale(110)));
                // Few distinct times (months, days of a report): label those, not points in between
                List<Double> distinct = new ArrayList<>();
                for (double value : xValues) {
                    if (distinct.isEmpty() || distinct.get(distinct.size() - 1) != value) {
                        distinct.add(value);
                    }
                }
                if (distinct.size() <= labelCount) {
                    ticks.addAll(distinct);
                } else {
                    for (int i = 0; i < labelCount; i++) {
                        ticks.add(first + (last - first) * i / (labelCount - 1));
                    }
                }
            } else {
                double[] axis = niceAxis(first, last, Math.max(2, Math.min(8, plot.width / UIScale.scale(80))));
                for (double value = axis[0]; value <= last + axis[2] / 1e6; value += axis[2]) {
                    if (value >= first - axis[2] / 1e6) {
                        ticks.add(value);
                    }
                }
            }
            int nextLeft = Integer.MIN_VALUE;
            for (double value : ticks) {
                String label = pattern != null ? new SimpleDateFormat(pattern).format(new Date((long) value)) : formatAxis(value, (last - first) / 10);
                int labelWidth = fm.stringWidth(label);
                int x = plot.x + (int) Math.round((value - first) / (last - first) * plot.width) - labelWidth / 2;
                x = Math.max(plot.x - UIScale.scale(4), Math.min(plot.x + plot.width - labelWidth + UIScale.scale(4), x));
                if (x < nextLeft) {
                    continue;
                }
                g.drawString(label, x, baseline);
                nextLeft = x + labelWidth + UIScale.scale(10);
            }
            return;
        }
        // Categories: every n-th label so that they do not overlap, each cut to its room
        String[] labels = data.labels();
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, fm.stringWidth(label));
            if (widest >= UIScale.scale(120)) {
                break;
            }
        }
        int room = Math.min(widest, UIScale.scale(120)) + UIScale.scale(10);
        int every = Math.max(1, (int) Math.ceil(room / Math.max(slot, 0.001)));
        int maxWidth = (int) Math.max(UIScale.scale(24), slot * every - UIScale.scale(10));
        for (int i = 0; i < labels.length; i += every) {
            String label = fit(labels[i], fm, maxWidth);
            int labelWidth = fm.stringWidth(label);
            int x = (int) Math.round(positions[i]) - labelWidth / 2;
            x = Math.max(plot.x - UIScale.scale(4), Math.min(plot.x + plot.width - labelWidth + UIScale.scale(4), x));
            g.drawString(label, x, baseline);
        }
    }

    private void paintLines(@NotNull Graphics2D g, @NotNull List<Series> series, @NotNull Color[] palette, @NotNull double[] positions,
                            double low, double high, boolean markers) {
        BasicStroke line = new BasicStroke(UIScale.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        int radius = UIScale.scale(4);
        for (int s = 0; s < series.size(); s++) {
            double[] values = series.get(s).values();
            Path2D path = new Path2D.Double();
            boolean drawing = false;
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) {
                    // An empty cell breaks the line
                    drawing = false;
                    continue;
                }
                double y = toY(values[i], low, high);
                if (drawing) {
                    path.lineTo(positions[i], y);
                } else {
                    path.moveTo(positions[i], y);
                    // A point alone is drawn as a short dash so that it is visible
                    path.lineTo(positions[i] + 0.1, y);
                    drawing = true;
                }
            }
            g.setColor(palette[s]);
            g.setStroke(line);
            g.draw(path);
            if (markers) {
                for (int i = 0; i < values.length; i++) {
                    if (Double.isFinite(values[i])) {
                        paintMarker(g, (int) Math.round(positions[i]), toY(values[i], low, high), radius, palette[s]);
                    }
                }
            }
        }
    }

    private void paintBars(@NotNull Graphics2D g, @NotNull List<Series> series, @NotNull Color[] palette, @NotNull double[] positions,
                           double slot, double low, double high) {
        int k = series.size();
        double gap = UIScale.scale(2f);
        double group = slot * 0.8;
        double barWidth = (group - gap * (k - 1)) / k;
        if (barWidth < 2) {
            // Many bars: no gap, at least a hairline
            gap = 0;
            barWidth = Math.max(1, group / k);
        }
        double zero = toY(0, low, high);
        double radius = Math.min(UIScale.scale(4f), barWidth / 2);
        for (int s = 0; s < k; s++) {
            double[] values = series.get(s).values();
            g.setColor(palette[s]);
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i]) || values[i] == 0) {
                    continue;
                }
                double x = positions[i] - group / 2 + s * (barWidth + gap);
                double y = toY(values[i], low, high);
                double top = Math.min(y, zero);
                double barHeight = Math.max(1, Math.abs(zero - y));
                // Rounded at the data end, square at the baseline
                Area area = new Area(new RoundRectangle2D.Double(x, top, barWidth, barHeight, radius * 2, radius * 2));
                double half = Math.min(barHeight, radius);
                area.add(new Area(new Rectangle2D.Double(x, values[i] > 0 ? top + barHeight - half : top, barWidth, half)));
                g.fill(area);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Pie

    private record Slice(@NotNull String label, double value) {
    }

    private void paintPie(@NotNull Graphics2D g, @NotNull Data data, int width, int height, @Nullable Point mouse) {
        Color foreground = color("Label.foreground", Color.BLACK); //$NON-NLS-1$
        Color secondary = color("Chromite.secondaryForeground", Color.GRAY); //$NON-NLS-1$
        Color surface = surfaceColor();
        Color[] palette = DashboardChart.seriesPalette();
        FontMetrics fm = g.getFontMetrics();
        int pad = UIScale.scale(8);
        Series series = data.series().get(0);

        // Positive values only, largest first; the rest of more than eight slices is "Other"
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < series.values().length; i++) {
            double value = series.values()[i];
            if (Double.isFinite(value) && value > 0) {
                slices.add(new Slice(data.labels()[i], value));
            }
        }
        g.setColor(secondary);
        g.drawString(fit(series.name(), fm, width - pad * 2), pad, pad + fm.getAscent());
        int top = pad + fm.getHeight() + UIScale.scale(6);
        if (slices.isEmpty()) {
            paintMessage(g, EditorMessages.editor_chart_pie_no_positive, new Rectangle(0, top, width, height - top));
            return;
        }
        slices.sort((a, b) -> Double.compare(b.value(), a.value()));
        boolean other = slices.size() > MAX_PIE_SLICES;
        if (other) {
            double rest = 0;
            for (Slice slice : slices.subList(MAX_PIE_SLICES - 1, slices.size())) {
                rest += slice.value();
            }
            int restCount = slices.size() - (MAX_PIE_SLICES - 1);
            slices = new ArrayList<>(slices.subList(0, MAX_PIE_SLICES - 1));
            slices.add(new Slice(NLS.bind(EditorMessages.editor_chart_other, String.format("%,d", restCount)), rest)); //$NON-NLS-1$
        }
        double total = 0;
        for (Slice slice : slices) {
            total += slice.value();
        }

        // The pie and its legend (names and shares) side by side, centered together
        int legendWidth = Math.min(UIScale.scale(280), Math.max(UIScale.scale(140), width / 3));
        int available = height - top - pad;
        int diameter = Math.max(UIScale.scale(40), Math.min(available, width - legendWidth - pad * 4));
        int pieX = Math.max(pad, (width - diameter - pad * 2 - legendWidth) / 2);
        int pieY = top + Math.max(0, (available - diameter) / 2);
        plot.setBounds(pieX, pieY, diameter, diameter);
        double angle = 90;
        int hoverIndex = -1;
        for (int i = 0; i < slices.size(); i++) {
            double extent = slices.get(i).value() / total * 360;
            Arc2D arc = new Arc2D.Double(pieX, pieY, diameter, diameter, angle, -extent, Arc2D.PIE);
            sliceShapes.add(arc);
            if (mouse != null && arc.contains(mouse)) {
                hoverIndex = i;
            }
            angle -= extent;
        }
        for (int i = 0; i < slices.size(); i++) {
            g.setColor(other && i == slices.size() - 1 ? secondary : palette[i]);
            g.fill(sliceShapes.get(i));
        }
        // A surface gap between the slices
        g.setColor(surface);
        g.setStroke(new BasicStroke(UIScale.scale(2f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        if (slices.size() > 1) {
            for (Shape slice : sliceShapes) {
                g.draw(slice);
            }
        }
        if (hoverIndex >= 0) {
            g.setColor(foreground);
            g.setStroke(new BasicStroke(UIScale.scale(2f)));
            g.draw(sliceShapes.get(hoverIndex));
        }

        int legendX = pieX + diameter + pad * 2;
        int legendRight = Math.min(width - pad, legendX + legendWidth);
        // Rows a little apart when there is room; in a low view they close up to the text height so all slices are listed
        int lineHeight = Math.max(fm.getHeight(), Math.min(fm.getHeight() + UIScale.scale(4), available / slices.size()));
        int y = top + Math.max(0, (available - slices.size() * lineHeight) / 2);
        int swatch = UIScale.scale(10);
        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            g.setColor(other && i == slices.size() - 1 ? secondary : palette[i]);
            g.fill(new RoundRectangle2D.Float(legendX, y + (fm.getHeight() - swatch) / 2f, swatch, swatch, UIScale.scale(3), UIScale.scale(3)));
            String share = formatShare(slice.value() / total);
            int shareWidth = fm.stringWidth(share);
            int textX = legendX + swatch + UIScale.scale(6);
            int textWidth = legendRight - textX - shareWidth - UIScale.scale(8);
            g.setColor(foreground);
            g.drawString(fit(slice.label(), fm, textWidth), textX, y + fm.getAscent());
            g.setColor(secondary);
            g.drawString(share, legendRight - shareWidth, y + fm.getAscent());
            y += lineHeight;
        }
        if (hoverIndex >= 0) {
            Slice slice = slices.get(hoverIndex);
            paintTip(g, slice.label(), List.of(formatValue(slice.value()) + "  (" + formatShare(slice.value() / total) + ")"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(other && hoverIndex == slices.size() - 1 ? secondary : palette[hoverIndex]), mouse.x, mouse.y + UIScale.scale(12), width);
        }
    }

    //////////////////////////////////////////////////////////
    // Parts

    /**
     * Legend items (swatch and name) in rows from the left; returns its height.
     */
    private int paintLegend(@NotNull Graphics2D g, @NotNull List<Series> series, @NotNull Color[] palette, int left, int top, int width,
                            @NotNull Color textColor) {
        FontMetrics fm = g.getFontMetrics();
        int swatch = UIScale.scale(10);
        int lineHeight = fm.getHeight();
        int x = left;
        int y = top;
        for (int i = 0; i < series.size(); i++) {
            String name = fit(series.get(i).name(), fm, width - swatch - UIScale.scale(5));
            int itemWidth = swatch + UIScale.scale(5) + fm.stringWidth(name) + UIScale.scale(14);
            if (x > left && x + itemWidth > left + width) {
                x = left;
                y += lineHeight;
            }
            g.setColor(palette[i]);
            g.fill(new RoundRectangle2D.Float(x, y + (lineHeight - swatch) / 2f, swatch, swatch, UIScale.scale(3), UIScale.scale(3)));
            g.setColor(textColor);
            g.drawString(name, x + swatch + UIScale.scale(5), y + fm.getAscent());
            x += itemWidth;
        }
        return y - top + lineHeight + UIScale.scale(6);
    }

    private void paintMarker(@NotNull Graphics2D g, int x, int y, int radius, @NotNull Color color) {
        // A ring of the surface color keeps overlapping markers apart
        int ring = UIScale.scale(2);
        g.setColor(surfaceColor());
        g.fillOval(x - radius - ring, y - radius - ring, (radius + ring) * 2, (radius + ring) * 2);
        g.setColor(color);
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /**
     * A value box near the mouse: a header and one line per value with its color.
     */
    private static void paintTip(@NotNull Graphics2D g, @NotNull String header, @NotNull List<String> lines, @NotNull List<Color> colors,
                                 int anchorX, int anchorY, int width) {
        FontMetrics fm = g.getFontMetrics();
        int swatch = UIScale.scale(8);
        int padding = UIScale.scale(8);
        int maxText = UIScale.scale(320);
        String head = fit(header, fm, maxText);
        int boxWidth = fm.stringWidth(head);
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            String text = fit(line, fm, maxText);
            shown.add(text);
            boxWidth = Math.max(boxWidth, swatch + UIScale.scale(6) + fm.stringWidth(text));
        }
        boxWidth += padding * 2;
        int boxHeight = fm.getHeight() * (shown.size() + 1) + padding * 2 - UIScale.scale(2);
        int boxX = anchorX + UIScale.scale(12);
        if (boxX + boxWidth > width) {
            boxX = anchorX - UIScale.scale(12) - boxWidth;
        }
        boxX = Math.max(0, boxX);
        g.setColor(color("ToolTip.background", Color.DARK_GRAY)); //$NON-NLS-1$
        g.fill(new RoundRectangle2D.Float(boxX, anchorY, boxWidth, boxHeight, UIScale.scale(8), UIScale.scale(8)));
        Color tipForeground = color("ToolTip.foreground", Color.WHITE); //$NON-NLS-1$
        g.setColor(tipForeground);
        int textY = anchorY + padding + fm.getAscent() - UIScale.scale(1);
        g.drawString(head, boxX + padding, textY);
        for (int i = 0; i < shown.size(); i++) {
            textY += fm.getHeight();
            g.setColor(colors.get(i));
            g.fillRect(boxX + padding, textY - fm.getAscent() / 2 - swatch / 2 + UIScale.scale(1), swatch, swatch);
            g.setColor(tipForeground);
            g.drawString(shown.get(i), boxX + padding + swatch + UIScale.scale(6), textY);
        }
    }

    private static void paintMessage(@NotNull Graphics2D g, @NotNull String text, @NotNull Rectangle area) {
        FontMetrics fm = g.getFontMetrics();
        String shown = fit(text, fm, Math.max(UIScale.scale(40), area.width - UIScale.scale(24)));
        g.setColor(color("Chromite.secondaryForeground", Color.GRAY)); //$NON-NLS-1$
        g.drawString(shown, area.x + (area.width - fm.stringWidth(shown)) / 2, area.y + (area.height - fm.getHeight()) / 2 + fm.getAscent());
    }

    /**
     * The color the chart is drawn on (the view's background), for the gaps between slices and the marker rings.
     */
    @NotNull
    private Color surfaceColor() {
        Container parent = getParent();
        return parent != null ? parent.getBackground() : color("Panel.background", Color.WHITE); //$NON-NLS-1$
    }

    private int toY(double value, double low, double high) {
        double ratio = high > low ? (value - low) / (high - low) : 0.5;
        return plot.y + plot.height - (int) Math.round(ratio * plot.height);
    }

    private static int nearestPoint(@NotNull double[] positions, int x) {
        int best = -1;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < positions.length; i++) {
            double d = Math.abs(positions[i] - x);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * A value axis from {@code min} to {@code max} with round ticks: {low, high, step}. The step is 1, 2, 2.5 or 5 times
     * a power of ten.
     */
    @NotNull
    static double[] niceAxis(double min, double max, int ticks) {
        if (max <= min) {
            double margin = min == 0 ? 1 : Math.abs(min) * 0.1;
            min -= margin;
            max += margin;
        }
        double raw = (max - min) / Math.max(1, ticks);
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double step = 10 * magnitude;
        for (double candidate : new double[]{1, 2, 2.5, 5, 10}) {
            if (candidate * magnitude >= raw) {
                step = candidate * magnitude;
                break;
            }
        }
        double low = Math.floor(min / step) * step;
        double high = Math.ceil(max / step) * step;
        return new double[]{low, high, step};
    }

    /**
     * The date pattern of the time axis labels by the time span shown.
     */
    @NotNull
    private static String datePattern(double spanMillis) {
        double day = 86_400_000d;
        if (spanMillis < 2 * day) {
            return "MM-dd HH:mm"; //$NON-NLS-1$
        }
        if (spanMillis < 3 * 365 * day) {
            return "yyyy-MM-dd"; //$NON-NLS-1$
        }
        return "yyyy-MM"; //$NON-NLS-1$
    }

    /**
     * A value in the value box: grouping, up to two decimals (four below one).
     */
    @NotNull
    static String formatValue(double value) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(Math.abs(value) < 1 ? 4 : 2);
        return format.format(value);
    }

    /**
     * An axis label: K/M from ten thousand on, as many decimals as the step needs.
     */
    @NotNull
    private static String formatAxis(double value, double step) {
        NumberFormat format = NumberFormat.getNumberInstance();
        if (Math.abs(value) >= 10_000_000 && step >= 1_000_000) {
            format.setMaximumFractionDigits(1);
            return format.format(value / 1_000_000) + "M"; //$NON-NLS-1$
        }
        if (Math.abs(value) >= 10_000 && step >= 1_000) {
            format.setMaximumFractionDigits(1);
            return format.format(value / 1_000) + "K"; //$NON-NLS-1$
        }
        int digits = step >= 1 ? 0 : Math.min(6, (int) Math.ceil(-Math.log10(step)) + 1);
        format.setMaximumFractionDigits(digits);
        return format.format(Math.abs(value) < step / 1e6 ? 0 : value);
    }

    @NotNull
    private static String formatShare(double share) {
        NumberFormat format = NumberFormat.getPercentInstance();
        format.setMaximumFractionDigits(1);
        return format.format(share);
    }

    /**
     * The text cut with "…" to the width.
     */
    @NotNull
    static String fit(@NotNull String text, @NotNull FontMetrics fm, int width) {
        // No label is wider than a few hundred characters: long values are cut first, so the loop below stays short
        String single = CommonUtils.truncateString(text, 300).replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
        if (fm.stringWidth(single) <= width) {
            return single;
        }
        int end = single.length();
        while (end > 0 && fm.stringWidth(single.substring(0, end) + "…") > width) { //$NON-NLS-1$
            end--;
        }
        return single.substring(0, end) + "…"; //$NON-NLS-1$
    }

    @NotNull
    private static Color color(@NotNull String key, @NotNull Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}
