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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ER 다이어그램 그리기: 테이블 상자의 크기 재기, 관계선 경로, 그리기, 마우스 위치의 상자·관계선 찾기.
 *
 * 설계:
 * <ul>
 *     <li>모든 좌표는 다이어그램 좌표(확대 1)다. 확대·이동은 캔버스가 Graphics 변환으로 한다. 그래서 화면, PNG, SVG가 같은 코드로 그려진다
 *     ({@link ErdPainter}).</li>
 *     <li>상자(Islands 카드 모양): 둥근 사각형, 머리글 띠(테이블 아이콘과 {@code 스키마.테이블}), 열 행(기본 키·외래 키·일반 열 아이콘,
 *     이름, 형식과 NOT NULL). 형식 표시는 SWT ERD의 "Types"·"Nullability" 표시(ERDUIUtils.getFullAttributeLabel)처럼
 *     {@code 형식 NOT NULL}이고, 형식 열은 상자 안에서 세로로 맞춘다. 열 표시는 모두 / 키만(기본 키·외래 키 열) / 없음이다.</li>
 *     <li>색은 그릴 때마다 테마(UIManager의 Chromite 키)에서 읽어 테마를 바꾸면 바로 따른다. 테마 파일에 새 키를 더하지 않았다.</li>
 *     <li>관계선은 직각으로 꺾는다: 상자가 가로로 떨어져 있으면 마주 보는 옆면에서 나와 가운데에서 한 번 세로로, 세로로 떨어져 있으면
 *     위아래 면에서, 겹치면 오른쪽으로 돌아 들어간다. 옆면에서 나올 때는 외래 키 열과 참조하는 열의 행 높이에서 나온다.
 *     층을 여럿 건너는 선이 사이의 상자를 지나가면 상자 옆 틈으로 나와 상자들 위나 아래의 빈 통로로 돌아간다(가장 가까운 통로).
 *     끝 모양은 까마귀발 표기다({@link ErdDiagramModel.End}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdRenderer {

    /**
     * Columns shown in the table boxes.
     */
    enum ColumnMode {
        ALL,
        KEYS,
        NONE
    }

    static final int HEADER_HEIGHT = 30;
    static final int ROW_HEIGHT = 22;
    private static final int ROWS_PADDING = 4;
    private static final int PAD_X = 10;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 6;
    private static final int TYPE_GAP = 20;
    private static final int MIN_WIDTH = 150;
    private static final int ARC = 10;
    /**
     * Straight part of a relationship line at a box, room for the end symbols.
     */
    private static final int STUB = 30;
    /**
     * Distance of a detour corridor from the boxes it passes.
     */
    private static final int CORRIDOR_GAP = 14;
    private static final int GRID_STEP = 20;
    private static final String NOT_NULL = " NOT NULL"; //$NON-NLS-1$

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    ColumnMode columnMode = ColumnMode.ALL;
    boolean showTypes = true;

    /**
     * Theme colors, read when painting.
     */
    record Palette(Color canvas, Color grid, Color box, Color border, Color header, Color text, Color secondary,
                   Color line, Color accent) {
        @NotNull
        static Palette current() {
            Color text = color("Label.foreground", Color.BLACK); //$NON-NLS-1$
            Color secondary = color("Chromite.secondaryForeground", Color.GRAY); //$NON-NLS-1$
            return new Palette(
                color("Chromite.PropertyGrid.categoryBackground", Color.WHITE), //$NON-NLS-1$
                color("Chromite.dividerColor", Color.LIGHT_GRAY), //$NON-NLS-1$
                color("Chromite.panelBackground", Color.WHITE), //$NON-NLS-1$
                color("Component.borderColor", Color.GRAY), //$NON-NLS-1$
                color("Chromite.island.tabSelectedBackground", Color.LIGHT_GRAY), //$NON-NLS-1$
                text,
                secondary,
                secondary,
                color("Component.focusColor", Color.BLUE)); //$NON-NLS-1$
        }

        @NotNull
        private static Color color(@NotNull String key, @NotNull Color fallback) {
            Color c = UIManager.getColor(key);
            return c != null ? new Color(c.getRGB(), true) : fallback;
        }
    }

    @NotNull
    static Font textFont() {
        Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
        return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    @NotNull
    static Font titleFont() {
        Font font = UIManager.getFont("semibold.font"); //$NON-NLS-1$
        return font != null ? font.deriveFont(textFont().getSize2D()) : textFont().deriveFont(Font.BOLD);
    }

    @NotNull
    List<ErdDiagramModel.Column> visibleColumns(@NotNull ErdDiagramModel.Entity entity) {
        return switch (columnMode) {
            case ALL -> entity.columns;
            case KEYS -> entity.columns.stream().filter(c -> c.primaryKey() || c.foreignKey()).toList();
            case NONE -> List.of();
        };
    }

    /**
     * Sets the size of every box for the current options.
     */
    void measure(@NotNull Collection<ErdDiagramModel.Entity> entities) {
        Font textFont = textFont();
        Font titleFont = titleFont();
        for (ErdDiagramModel.Entity entity : entities) {
            List<ErdDiagramModel.Column> columns = visibleColumns(entity);
            double width = PAD_X + ICON_SIZE + ICON_GAP + textWidth(titleFont, entity.title) + PAD_X;
            double nameWidth = 0;
            double typeWidth = 0;
            for (ErdDiagramModel.Column column : columns) {
                nameWidth = Math.max(nameWidth, textWidth(textFont, column.name()));
                if (showTypes) {
                    typeWidth = Math.max(typeWidth, textWidth(textFont, typeText(column)));
                }
            }
            if (!columns.isEmpty()) {
                width = Math.max(width, PAD_X + ICON_SIZE + ICON_GAP + nameWidth + (showTypes ? TYPE_GAP + typeWidth : 0) + PAD_X);
            }
            entity.width = (int) Math.ceil(Math.max(MIN_WIDTH, width));
            entity.height = HEADER_HEIGHT + (columns.isEmpty() ? 0 : columns.size() * ROW_HEIGHT + 2 * ROWS_PADDING);
        }
    }

    /**
     * Bounds of all boxes with the layout margin around them (the diagram size).
     */
    @NotNull
    static Rectangle2D diagramBounds(@NotNull Collection<ErdDiagramModel.Entity> entities) {
        double maxX = 0;
        double maxY = 0;
        for (ErdDiagramModel.Entity entity : entities) {
            maxX = Math.max(maxX, entity.x + entity.width);
            maxY = Math.max(maxY, entity.y + entity.height);
        }
        // Self links go out to the right of their box
        return new Rectangle2D.Double(0, 0, maxX + ErdLayout.MARGIN + STUB, maxY + ErdLayout.MARGIN);
    }

    /**
     * Paints the diagram.
     *
     * @param order      boxes in painting order (last on top)
     * @param selected   selected boxes
     * @param hoverLink  relationship under the mouse, or null
     * @param clip       visible area in diagram coordinates (null: everything)
     * @param grid       paint the dot grid (screen only)
     */
    void paint(@NotNull ErdPainter painter, @NotNull Palette palette, @NotNull List<ErdDiagramModel.Entity> order,
               @NotNull List<ErdDiagramModel.Link> links, @NotNull Set<ErdDiagramModel.Entity> selected,
               @Nullable ErdDiagramModel.Link hoverLink, @Nullable Rectangle2D clip, boolean grid) {
        if (grid && clip != null) {
            paintGrid(painter, palette, clip);
        }
        // Lines below the boxes; highlighted ones last
        List<ErdDiagramModel.Link> highlighted = new ArrayList<>();
        for (ErdDiagramModel.Link link : links) {
            if (link == hoverLink || selected.contains(link.source()) || selected.contains(link.target())) {
                highlighted.add(link);
            } else {
                paintLink(painter, palette, link, order, palette.line(), 1f);
            }
        }
        for (ErdDiagramModel.Link link : highlighted) {
            paintLink(painter, palette, link, order, palette.accent(), link == hoverLink ? 2f : 1.5f);
        }
        for (ErdDiagramModel.Entity entity : order) {
            if (clip == null || clip.intersects(entity.x - 2, entity.y - 2, entity.width + 4, entity.height + 4)) {
                paintEntity(painter, palette, entity, selected.contains(entity));
            }
        }
    }

    private static void paintGrid(@NotNull ErdPainter painter, @NotNull Palette palette, @NotNull Rectangle2D clip) {
        Path2D dots = new Path2D.Double();
        double startX = Math.floor(clip.getMinX() / GRID_STEP) * GRID_STEP;
        double startY = Math.floor(clip.getMinY() / GRID_STEP) * GRID_STEP;
        for (double x = startX; x <= clip.getMaxX(); x += GRID_STEP) {
            for (double y = startY; y <= clip.getMaxY(); y += GRID_STEP) {
                dots.append(new Rectangle2D.Double(x - 0.75, y - 0.75, 1.5, 1.5), false);
            }
        }
        painter.fill(dots, palette.grid());
    }

    private void paintEntity(@NotNull ErdPainter painter, @NotNull Palette palette, @NotNull ErdDiagramModel.Entity entity, boolean selected) {
        double x = entity.x;
        double y = entity.y;
        double w = entity.width;
        double h = entity.height;
        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, w, h, ARC * 2, ARC * 2);
        painter.fill(box, palette.box());

        // Header band: rounded top corners only (the whole box when no columns are shown)
        Shape header;
        if (h <= HEADER_HEIGHT) {
            header = box;
        } else {
            Path2D band = new Path2D.Double();
            band.moveTo(x, y + HEADER_HEIGHT);
            band.lineTo(x, y + ARC);
            band.quadTo(x, y, x + ARC, y);
            band.lineTo(x + w - ARC, y);
            band.quadTo(x + w, y, x + w, y + ARC);
            band.lineTo(x + w, y + HEADER_HEIGHT);
            band.closePath();
            header = band;
        }
        painter.fill(header, palette.header());
        if (h > HEADER_HEIGHT) {
            painter.stroke(new Line2D.Double(x, y + HEADER_HEIGHT, x + w, y + HEADER_HEIGHT), palette.border(), 1f);
        }
        Font titleFont = titleFont();
        Font textFont = textFont();
        painter.icon(entity.view ? "view" : "table", x + PAD_X, y + (HEADER_HEIGHT - ICON_SIZE) / 2.0); //$NON-NLS-1$ //$NON-NLS-2$
        painter.text(entity.title, x + PAD_X + ICON_SIZE + ICON_GAP, baseline(titleFont, y, HEADER_HEIGHT), titleFont, palette.text());

        List<ErdDiagramModel.Column> columns = visibleColumns(entity);
        double nameX = x + PAD_X + ICON_SIZE + ICON_GAP;
        double typeX = nameX;
        if (showTypes) {
            for (ErdDiagramModel.Column column : columns) {
                typeX = Math.max(typeX, nameX + textWidth(textFont, column.name()) + TYPE_GAP);
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            ErdDiagramModel.Column column = columns.get(i);
            double rowY = rowTop(entity, i);
            String iconId = column.primaryKey() ? "column-pk" : column.foreignKey() ? "column-fk" : "column"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            painter.icon(iconId, x + PAD_X, rowY + (ROW_HEIGHT - ICON_SIZE) / 2.0);
            double base = baseline(textFont, rowY, ROW_HEIGHT);
            painter.text(column.name(), nameX, base, textFont, palette.text());
            if (showTypes) {
                painter.text(typeText(column), typeX, base, textFont, palette.secondary());
            }
        }

        Color borderColor = selected || entity.primary ? palette.accent() : palette.border();
        painter.stroke(box, borderColor, selected ? 2f : 1f);
    }

    private void paintLink(@NotNull ErdPainter painter, @NotNull Palette palette, @NotNull ErdDiagramModel.Link link,
                           @NotNull List<ErdDiagramModel.Entity> boxes, @NotNull Color color, float width) {
        double[] pts = route(link, boxes);
        Path2D line = new Path2D.Double();
        line.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) {
            line.lineTo(pts[i], pts[i + 1]);
        }
        painter.stroke(line, color, width);
        int n = pts.length;
        paintEnd(painter, palette, link.sourceEnd(), pts[0], pts[1], pts[2], pts[3], color, width);
        paintEnd(painter, palette, link.targetEnd(), pts[n - 2], pts[n - 1], pts[n - 4], pts[n - 3], color, width);
    }

    /**
     * Crow's foot end at point (px, py) of a box; (qx, qy) is the next point of the line (gives the direction).
     */
    private static void paintEnd(@NotNull ErdPainter painter, @NotNull Palette palette, @NotNull ErdDiagramModel.End end,
                                 double px, double py, double qx, double qy, @NotNull Color color, float width) {
        double len = Math.hypot(qx - px, qy - py);
        if (len < 1) {
            return;
        }
        double dx = (qx - px) / len;
        double dy = (qy - py) / len;
        // Perpendicular
        double nx = -dy;
        double ny = dx;
        Path2D marks = new Path2D.Double();
        switch (end) {
            case ONE -> {
                bar(marks, px, py, dx, dy, nx, ny, 8);
                bar(marks, px, py, dx, dy, nx, ny, 13);
            }
            case ZERO_OR_ONE -> bar(marks, px, py, dx, dy, nx, ny, 8);
            case ZERO_OR_MANY -> {
                double fx = px + dx * 14;
                double fy = py + dy * 14;
                marks.moveTo(fx, fy);
                marks.lineTo(px + nx * 7, py + ny * 7);
                marks.moveTo(fx, fy);
                marks.lineTo(px - nx * 7, py - ny * 7);
            }
        }
        painter.stroke(marks, color, width);
        if (end != ErdDiagramModel.End.ONE) {
            double distance = end == ErdDiagramModel.End.ZERO_OR_MANY ? 20 : 17;
            Ellipse2D circle = new Ellipse2D.Double(px + dx * distance - 4, py + dy * distance - 4, 8, 8);
            painter.fill(circle, palette.canvas());
            painter.stroke(circle, color, width);
        }
    }

    private static void bar(@NotNull Path2D path, double px, double py, double dx, double dy, double nx, double ny, double at) {
        double cx = px + dx * at;
        double cy = py + dy * at;
        path.moveTo(cx + nx * 6, cy + ny * 6);
        path.lineTo(cx - nx * 6, cy - ny * 6);
    }

    /**
     * Points (x0, y0, x1, y1, ...) of the orthogonal line of a relationship, from the foreign key box to the key box.
     *
     * @param boxes all boxes: a line between columns that would cross another box goes around it
     */
    @NotNull
    double[] route(@NotNull ErdDiagramModel.Link link, @NotNull List<ErdDiagramModel.Entity> boxes) {
        ErdDiagramModel.Entity s = link.source();
        ErdDiagramModel.Entity t = link.target();
        double ys = anchorY(s, link.sourceColumns());
        double yt = anchorY(t, link.targetColumns());
        if (s == t) {
            if (Math.abs(ys - yt) < 6) {
                yt = Math.min(s.y + s.height - 4, ys + ROW_HEIGHT / 2.0 + 4);
            }
            double xr = s.x + s.width + STUB;
            return new double[]{s.x + s.width, ys, xr, ys, xr, yt, s.x + s.width, yt};
        }
        double sRight = s.x + s.width;
        double tRight = t.x + t.width;
        if (sRight + 2 * STUB <= t.x) {
            double mid = Math.round((sRight + t.x) / 2);
            double[] direct = {sRight, ys, mid, ys, mid, yt, t.x, yt};
            return isClear(direct, s, t, boxes) ? direct : detour(direct, sRight, ys, sRight + STUB, t.x - STUB, t.x, yt, s, t, boxes);
        }
        if (tRight + 2 * STUB <= s.x) {
            double mid = Math.round((tRight + s.x) / 2);
            double[] direct = {s.x, ys, mid, ys, mid, yt, tRight, yt};
            return isClear(direct, s, t, boxes) ? direct : detour(direct, s.x, ys, s.x - STUB, tRight + STUB, tRight, yt, s, t, boxes);
        }
        double sBottom = s.y + s.height;
        double tBottom = t.y + t.height;
        double xs = Math.round(s.centerX());
        double xt = Math.round(t.centerX());
        if (sBottom + 2 * STUB <= t.y) {
            double mid = Math.round((sBottom + t.y) / 2);
            return new double[]{xs, sBottom, xs, mid, xt, mid, xt, t.y};
        }
        if (tBottom + 2 * STUB <= s.y) {
            double mid = Math.round((tBottom + s.y) / 2);
            return new double[]{xs, s.y, xs, mid, xt, mid, xt, tBottom};
        }
        // Close or overlapping boxes: around the right side
        double xr = Math.max(sRight, tRight) + STUB;
        return new double[]{sRight, ys, xr, ys, xr, yt, tRight, yt};
    }

    /**
     * A line between box columns that would cross other boxes: leaves the source box sideways into the gap next to it,
     * runs along a free horizontal corridor (above or below the boxes in between) to the gap next to the target box, and
     * enters it sideways. The corridor nearest to the two rows is used; without one the direct line is kept.
     */
    @NotNull
    private static double[] detour(@NotNull double[] direct, double x0, double ys, double cx1, double cx2, double x3, double yt,
                                   @NotNull ErdDiagramModel.Entity s, @NotNull ErdDiagramModel.Entity t,
                                   @NotNull List<ErdDiagramModel.Entity> boxes) {
        double left = Math.min(cx1, cx2);
        double right = Math.max(cx1, cx2);
        List<Double> candidates = new ArrayList<>();
        candidates.add(ys);
        candidates.add(yt);
        for (ErdDiagramModel.Entity box : boxes) {
            if (box != s && box != t && box.x < right && box.x + box.width > left) {
                candidates.add(box.y - CORRIDOR_GAP);
                candidates.add(box.y + box.height + CORRIDOR_GAP);
            }
        }
        double best = Double.NaN;
        double bestCost = Double.MAX_VALUE;
        for (double y : candidates) {
            double cost = Math.abs(ys - y) + Math.abs(y - yt);
            if (cost < bestCost && isClear(new double[]{cx1, ys, cx1, y, cx2, y, cx2, yt}, s, t, boxes)) {
                best = y;
                bestCost = cost;
            }
        }
        if (Double.isNaN(best)) {
            return direct;
        }
        return new double[]{x0, ys, cx1, ys, cx1, best, cx2, best, cx2, yt, x3, yt};
    }

    /**
     * Whether no segment of an orthogonal line crosses a box other than its own two.
     */
    private static boolean isClear(@NotNull double[] pts, @NotNull ErdDiagramModel.Entity s, @NotNull ErdDiagramModel.Entity t,
                                   @NotNull List<ErdDiagramModel.Entity> boxes) {
        for (ErdDiagramModel.Entity box : boxes) {
            if (box == s || box == t) {
                continue;
            }
            Rectangle2D r = new Rectangle2D.Double(box.x - 2, box.y - 2, box.width + 4, box.height + 4);
            for (int i = 0; i + 3 < pts.length; i += 2) {
                if (r.intersectsLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Y where a line leaves a box: the row of the first of the columns if it is shown, otherwise the header middle.
     */
    private double anchorY(@NotNull ErdDiagramModel.Entity entity, @NotNull List<String> columnNames) {
        List<ErdDiagramModel.Column> columns = visibleColumns(entity);
        if (!columnNames.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(columnNames.getFirst())) {
                    return rowTop(entity, i) + ROW_HEIGHT / 2.0;
                }
            }
        }
        return entity.y + HEADER_HEIGHT / 2.0;
    }

    private static double rowTop(@NotNull ErdDiagramModel.Entity entity, int row) {
        return entity.y + HEADER_HEIGHT + ROWS_PADDING + row * ROW_HEIGHT;
    }

    /**
     * The topmost box at a point, or null.
     */
    @Nullable
    static ErdDiagramModel.Entity entityAt(@NotNull List<ErdDiagramModel.Entity> order, double x, double y) {
        for (int i = order.size() - 1; i >= 0; i--) {
            ErdDiagramModel.Entity entity = order.get(i);
            if (x >= entity.x && x <= entity.x + entity.width && y >= entity.y && y <= entity.y + entity.height) {
                return entity;
            }
        }
        return null;
    }

    /**
     * The relationship line nearest to a point within a distance, or null.
     */
    @Nullable
    ErdDiagramModel.Link linkAt(@NotNull List<ErdDiagramModel.Link> links, @NotNull List<ErdDiagramModel.Entity> boxes,
                                double x, double y, double tolerance) {
        ErdDiagramModel.Link best = null;
        double bestDistance = tolerance;
        for (ErdDiagramModel.Link link : links) {
            double[] pts = route(link, boxes);
            for (int i = 0; i + 3 < pts.length; i += 2) {
                double d = Line2D.ptSegDist(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], x, y);
                if (d <= bestDistance) {
                    bestDistance = d;
                    best = link;
                }
            }
        }
        return best;
    }

    @NotNull
    private String typeText(@NotNull ErdDiagramModel.Column column) {
        return column.required() ? column.type() + NOT_NULL : column.type();
    }

    private static double textWidth(@NotNull Font font, @NotNull String text) {
        return font.getStringBounds(text, FRC).getWidth();
    }

    private static double baseline(@NotNull Font font, double top, double height) {
        java.awt.font.LineMetrics metrics = font.getLineMetrics("Ag", FRC); //$NON-NLS-1$
        return Math.round(top + (height - metrics.getAscent() - metrics.getDescent()) / 2 + metrics.getAscent());
    }
}
