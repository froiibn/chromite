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
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;
import org.locationtech.jts.awt.ShapeWriter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 결과 표의 공간 보기: 기하 열(PostGIS·MySQL 등 {@code data.gis}의 {@link DBGeometry}, SQL Server {@code geometry}/{@code geography})의
 * 값을 평면에 그린다. 밑그림 지도(타일)는 쓰지 않는다.
 *
 * 설계:
 * <ul>
 *     <li>폐쇄망에서 쓰는 제품이라 온라인 타일 서비스(SWT GIS 보기의 Leaflet 지도)를 쓰지 않는다. 좌표를 그대로 x는 가로, y는 위로 그리고,
 *     격자와 좌표 눈금만 둔다. 경위도(SRID 4326 등)는 경도를 가로, 위도를 세로로 그린 등장방형이다.</li>
 *     <li>기하 값은 모델의 값 처리기가 만든 {@link DBGeometry}(JTS 기하, 곡선은 {@link DBGeometry#linearize()})를 쓴다. 값 처리기가 없는
 *     SQL Server는 드라이버가 준 바이트를 {@link SqlServerSpatialReader}로 읽는다. 그리기는 JTS의 {@link ShapeWriter}다.
 *     JTS·WKG는 이미 제품에 있는 {@code org.jkiss.bundle.gis}에 들어 있어 새 라이브러리가 없다.</li>
 *     <li>표에 읽혀 있는 행만 그린다. 값 해석(LOB 읽기 포함)은 백그라운드 Job에서 하고, 마지막 요청의 결과만 보인다.</li>
 *     <li>점은 8px 원(바탕색 테두리), 선은 2px, 면은 옅게 칠하고 테두리를 그린다. 색은 결과 차트와 같은 계열 색의 첫 색이고,
 *     표에서 고른 행의 기하는 둘째 색으로 위에 그린다. 기하를 누르면 그 행이 표에서 선택된다. 끌어서 옮기고 휠로 확대·축소한다.</li>
 * </ul>
 * EDT에서만 쓴다(값 해석 Job 제외).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-result-charts-spatial.md)
 */
final class ResultSetSpatialView extends JPanel {

    private static final Log log = Log.getLog(ResultSetSpatialView.class);

    /**
     * The viewer's access to the rows of the grid.
     */
    interface Source {
        @NotNull
        DBDAttributeBinding[] getBindings();

        @NotNull
        String[] getColumnNames();

        int getRowCount();

        /**
         * The kept value of a cell: a {@link DBGeometry}, bytes or LOB content, or null.
         */
        @Nullable
        Object getSpatialValue(int row, int column);

        int getCurrentRow();

        void setCurrentRow(int row);
    }

    /**
     * One drawn value: its grid row and geometry.
     */
    private record Item(int row, @NotNull Geometry geometry) {
    }

    /**
     * Values read for one column: the drawable ones, the empty and NULL ones, those that could not be read, and the SRIDs.
     */
    private record Loaded(@NotNull List<Item> items, int empty, int unreadable, @NotNull Set<Integer> srids) {
    }

    /**
     * The screen shapes of one drawn item: polygon areas, and outlines of lines and point markers.
     */
    private record HitShapes(@NotNull List<Shape> areas, @NotNull List<Shape> outlines) {
    }

    private static final double ZOOM_STEP = 1.25;

    private final Source source;
    private final JComboBox<String> columnCombo = new JComboBox<>();
    private final JLabel infoLabel = new JLabel();
    private final MapCanvas canvas = new MapCanvas();
    private int[] spatialColumns = new int[0];
    private long generation;
    private boolean updating;

    ResultSetSpatialView(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;
        columnCombo.addActionListener(e -> {
            if (!updating) {
                refresh();
            }
        });
        JButton fitButton = new JButton(EditorMessages.editor_spatial_fit);
        fitButton.setToolTipText(EditorMessages.editor_spatial_fit_tip);
        fitButton.addActionListener(e -> canvas.fit());
        infoLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), UIScale.scale(2)));
        bar.add(new JLabel(EditorMessages.editor_spatial_column));
        bar.add(columnCombo);
        bar.add(fitButton);
        infoLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)));
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(infoLabel, BorderLayout.SOUTH);
    }

    //////////////////////////////////////////////////////////
    // Spatial columns

    /**
     * Whether the column holds geometries this view can draw: the value handler makes {@link DBGeometry} values (the
     * model's spatial support), or it is a SQL Server {@code geometry}/{@code geography} column.
     */
    static boolean isSpatialColumn(@NotNull DBDAttributeBinding binding) {
        try {
            if (DBGeometry.class.isAssignableFrom(binding.getValueHandler().getValueObjectType(binding))) {
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("Can't get the value type of '" + binding.getName() + "'", e);
        }
        return isSqlServerSpatial(binding) != null;
    }

    /**
     * For a SQL Server spatial column: whether it is {@code geography}; null for other columns.
     */
    @Nullable
    private static Boolean isSqlServerSpatial(@NotNull DBDAttributeBinding binding) {
        if (!(binding.getDataSource() instanceof SQLServerDataSource)) {
            return null;
        }
        String typeName = CommonUtils.notEmpty(binding.getTypeName()).toLowerCase(Locale.ROOT);
        if (typeName.equals(SQLServerConstants.TYPE_GEOGRAPHY)) {
            return Boolean.TRUE;
        }
        return typeName.equals(SQLServerConstants.TYPE_GEOMETRY) ? Boolean.FALSE : null;
    }

    //////////////////////////////////////////////////////////
    // Loading

    /**
     * Lists the spatial columns again and draws the chosen one from the grid's rows (the view is shown or the rows changed).
     */
    void refresh() {
        DBDAttributeBinding[] bindings = source.getBindings();
        String[] names = source.getColumnNames();
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < bindings.length; i++) {
            if (isSpatialColumn(bindings[i])) {
                columns.add(i);
            }
        }
        int previous = columnCombo.getSelectedIndex() >= 0 && columnCombo.getSelectedIndex() < spatialColumns.length
            ? spatialColumns[columnCombo.getSelectedIndex()] : -1;
        spatialColumns = columns.stream().mapToInt(Integer::intValue).toArray();
        updating = true;
        try {
            columnCombo.removeAllItems();
            int selected = 0;
            for (int i = 0; i < spatialColumns.length; i++) {
                columnCombo.addItem(names[spatialColumns[i]]);
                if (spatialColumns[i] == previous) {
                    selected = i;
                }
            }
            if (spatialColumns.length > 0) {
                columnCombo.setSelectedIndex(selected);
            }
            columnCombo.setEnabled(spatialColumns.length > 1);
        } finally {
            updating = false;
        }
        long current = ++generation;
        if (spatialColumns.length == 0) {
            canvas.setLoaded(null, EditorMessages.editor_spatial_no_columns);
            infoLabel.setText(" "); //$NON-NLS-1$
            return;
        }
        int column = spatialColumns[Math.max(0, columnCombo.getSelectedIndex())];
        DBDAttributeBinding binding = bindings[column];
        Boolean geography = isSqlServerSpatial(binding);
        // The values are taken on the EDT; reading LOBs and parsing run in the background
        int rowCount = source.getRowCount();
        Object[] values = new Object[rowCount];
        for (int row = 0; row < rowCount; row++) {
            values[row] = source.getSpatialValue(row, column);
        }
        canvas.setLoaded(null, EditorMessages.editor_common_reading);
        infoLabel.setText(" "); //$NON-NLS-1$
        new AbstractJob("Read geometry values") {
            {
                setUser(false);
                setSystem(true);
            }

            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Loaded loaded = load(values, geography, monitor);
                SwingUtilities.invokeLater(() -> {
                    if (current == generation) {
                        show(loaded);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @NotNull
    private static Loaded load(@NotNull Object[] values, @Nullable Boolean geography, @NotNull DBRProgressMonitor monitor) {
        List<Item> items = new ArrayList<>();
        Set<Integer> srids = new TreeSet<>();
        int empty = 0;
        int unreadable = 0;
        for (int row = 0; row < values.length && !monitor.isCanceled(); row++) {
            try {
                Geometry geometry = toGeometry(values[row], geography, monitor);
                if (geometry == null || geometry.isEmpty()) {
                    empty++;
                } else {
                    items.add(new Item(row, geometry));
                    srids.add(values[row] instanceof DBGeometry value && value.getSRID() != 0 ? value.getSRID() : geometry.getSRID());
                }
            } catch (Exception e) {
                if (unreadable == 0) {
                    log.debug("Can't read a geometry value", e);
                }
                unreadable++;
            }
        }
        return new Loaded(items, empty, unreadable, srids);
    }

    /**
     * The geometry of a kept cell value, or null for NULL.
     *
     * @param geography for SQL Server bytes: geography (true) or geometry (false); null when the column is not SQL Server's
     * @throws Exception if the value can't be read or parsed
     */
    @Nullable
    private static Geometry toGeometry(@Nullable Object value, @Nullable Boolean geography, @NotNull DBRProgressMonitor monitor) throws Exception {
        if (value instanceof DBGeometry dbGeometry) {
            if (dbGeometry.isNull()) {
                return null;
            }
            DBGeometry linear = dbGeometry.linearize();
            Geometry geometry = linear.getGeometry();
            if (geometry != null) {
                return geometry;
            }
            // Text values (WKT, EWKT with an "SRID=…;" prefix)
            String text = CommonUtils.notEmpty(linear.getString()).trim();
            if (text.regionMatches(true, 0, "SRID=", 0, 5) && text.indexOf(';') > 0) { //$NON-NLS-1$
                text = text.substring(text.indexOf(';') + 1);
            }
            return new WKTReader().read(text);
        }
        byte[] bytes = null;
        if (value instanceof byte[] array) {
            bytes = array;
        } else if (value instanceof ResultSetCellContent content) {
            bytes = content.load(monitor).bytes();
        }
        if (bytes != null && geography != null) {
            return SqlServerSpatialReader.read(bytes, geography);
        }
        if (value == null) {
            return null;
        }
        throw new IllegalArgumentException("Unsupported spatial value " + value.getClass().getName());
    }

    private void show(@NotNull Loaded loaded) {
        List<String> notes = new ArrayList<>();
        notes.add(NLS.bind(loaded.items().size() == 1 ? EditorMessages.editor_spatial_note_one : EditorMessages.editor_spatial_note_many,
            String.format("%,d", loaded.items().size()))); //$NON-NLS-1$
        if (loaded.srids().size() == 1) {
            notes.add(NLS.bind(EditorMessages.editor_spatial_note_srid, loaded.srids().iterator().next()));
        } else if (loaded.srids().size() > 1) {
            notes.add(EditorMessages.editor_spatial_note_mixed_srid);
        }
        if (loaded.empty() > 0) {
            notes.add(NLS.bind(EditorMessages.editor_spatial_note_empty, String.format("%,d", loaded.empty()))); //$NON-NLS-1$
        }
        if (loaded.unreadable() > 0) {
            notes.add(NLS.bind(EditorMessages.editor_spatial_note_unreadable, String.format("%,d", loaded.unreadable()))); //$NON-NLS-1$
        }
        notes.add(EditorMessages.editor_spatial_note_no_basemap);
        infoLabel.setText(String.join(" · ", notes)); //$NON-NLS-1$
        canvas.setLoaded(loaded.items(), loaded.items().isEmpty() ? EditorMessages.editor_spatial_no_values : null);
    }

    /**
     * The selected row of the grid changed: its geometry is drawn highlighted.
     */
    void selectionChanged() {
        canvas.repaint();
    }

    //////////////////////////////////////////////////////////
    // Drawing

    /**
     * The drawing area: geometries in model coordinates through a scale and an offset (y up), with a coordinate grid.
     */
    private final class MapCanvas extends JComponent {
        private List<Item> items = List.of();
        @Nullable
        private String message;
        // Screen x = offsetX + x * scale; screen y = offsetY - y * scale
        private double scale = 1;
        private double offsetX;
        private double offsetY;
        private boolean fitted;
        @Nullable
        private Point dragStart;
        // Shapes of the last paint in screen coordinates, for the mouse (same order as items)
        private final List<HitShapes> hitShapes = new ArrayList<>();

        MapCanvas() {
            setOpaque(false);
            setToolTipText(""); //$NON-NLS-1$
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragStart = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart != null) {
                        offsetX += e.getX() - dragStart.x;
                        offsetY += e.getY() - dragStart.y;
                        dragStart = e.getPoint();
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int index = itemAt(e.getPoint());
                    if (index >= 0) {
                        source.setCurrentRow(items.get(index).row());
                        repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double factor = e.getPreciseWheelRotation() < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
                    // Zoom around the mouse: the point under it stays in place
                    offsetX = e.getX() - (e.getX() - offsetX) * factor;
                    offsetY = e.getY() - (e.getY() - offsetY) * factor;
                    scale *= factor;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void setLoaded(@Nullable List<Item> items, @Nullable String message) {
            this.items = items == null ? List.of() : items;
            this.message = message;
            fitted = false;
            repaint();
        }

        /**
         * Shows all geometries with a margin.
         */
        void fit() {
            fitted = false;
            repaint();
        }

        private void fitNow() {
            Envelope envelope = new Envelope();
            for (Item item : items) {
                envelope.expandToInclude(item.geometry().getEnvelopeInternal());
            }
            if (envelope.isNull() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            if (envelope.getWidth() == 0 && envelope.getHeight() == 0) {
                // One point: a unit square around it
                envelope.expandBy(1);
            }
            int margin = UIScale.scale(24);
            double width = Math.max(1, getWidth() - margin * 2);
            double height = Math.max(1, getHeight() - margin * 2);
            scale = Math.min(envelope.getWidth() > 0 ? width / envelope.getWidth() : Double.MAX_VALUE,
                envelope.getHeight() > 0 ? height / envelope.getHeight() : Double.MAX_VALUE);
            offsetX = getWidth() / 2.0 - envelope.centre().x * scale;
            offsetY = getHeight() / 2.0 + envelope.centre().y * scale;
            fitted = true;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
                g2.setFont(font.deriveFont(font.getSize2D() - UIScale.scale(1f)));
                paintMap(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintMap(@NotNull Graphics2D g) {
            Color secondary = color("Chromite.secondaryForeground", Color.GRAY); //$NON-NLS-1$
            hitShapes.clear();
            if (message != null || items.isEmpty()) {
                FontMetrics fm = g.getFontMetrics();
                String text = message == null ? "" : message; //$NON-NLS-1$
                g.setColor(secondary);
                g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                return;
            }
            if (!fitted) {
                fitNow();
            }
            paintGrid(g, secondary);

            Color[] palette = DashboardChart.seriesPalette();
            Color surface = getParent() != null ? getParent().getBackground() : color("Panel.background", Color.WHITE); //$NON-NLS-1$
            ShapeWriter writer = new ShapeWriter((coordinate, point) -> point.setLocation(offsetX + coordinate.x * scale, offsetY - coordinate.y * scale));
            // Leave out vertices closer than half a pixel: large polygons stay fast to draw
            writer.setDecimation(0.5 / scale);
            int currentRow = source.getCurrentRow();
            Item selected = null;
            HitShapes selectedHit = null;
            for (Item item : items) {
                HitShapes hit = new HitShapes(new ArrayList<>(), new ArrayList<>());
                if (item.row() == currentRow) {
                    // Drawn last, on top and in its own color only (two translucent fills would mix)
                    selected = item;
                    selectedHit = hit;
                } else {
                    paintGeometry(g, writer, item.geometry(), palette[0], surface, hit);
                }
                hitShapes.add(hit);
            }
            if (selected != null) {
                paintGeometry(g, writer, selected.geometry(), palette[1], surface, selectedHit);
            }
        }

        /**
         * Draws a geometry (collections part by part) and adds the screen shapes of its parts for the mouse: areas as they
         * are, lines and points as outlines that are tested with a wider stroke.
         */
        private void paintGeometry(@NotNull Graphics2D g, @NotNull ShapeWriter writer, @NotNull Geometry geometry, @NotNull Color color,
                                   @NotNull Color surface, @NotNull HitShapes hit) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Geometry part = geometry.getGeometryN(i);
                if (part.isEmpty()) {
                    continue;
                }
                if (part != geometry && part instanceof GeometryCollection) {
                    paintGeometry(g, writer, part, color, surface, hit);
                } else if (part instanceof org.locationtech.jts.geom.Point point) {
                    // JTS point (java.awt.Point is the mouse position type in this class)
                    double x = offsetX + point.getX() * scale;
                    double y = offsetY - point.getY() * scale;
                    double radius = UIScale.scale(4f);
                    double ring = UIScale.scale(2f);
                    g.setColor(surface);
                    g.fill(new Ellipse2D.Double(x - radius - ring, y - radius - ring, (radius + ring) * 2, (radius + ring) * 2));
                    g.setColor(color);
                    Shape marker = new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2);
                    g.fill(marker);
                    hit.outlines().add(marker);
                } else if (part instanceof Polygon) {
                    Shape shape = writer.toShape(part);
                    g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 56));
                    g.fill(shape);
                    g.setColor(color);
                    g.setStroke(new BasicStroke(UIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                    hit.areas().add(shape);
                } else if (part instanceof LineString) {
                    Shape shape = writer.toShape(part);
                    g.setColor(color);
                    g.setStroke(new BasicStroke(UIScale.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                    hit.outlines().add(shape);
                }
            }
        }

        /**
         * Grid lines at round coordinates with their values at the left and bottom edges.
         */
        private void paintGrid(@NotNull Graphics2D g, @NotNull Color textColor) {
            Color gridColor = color("Chromite.dividerColor", Color.LIGHT_GRAY); //$NON-NLS-1$
            double minX = (0 - offsetX) / scale;
            double maxX = (getWidth() - offsetX) / scale;
            double minY = (offsetY - getHeight()) / scale;
            double maxY = offsetY / scale;
            double[] xAxis = ResultSetChart.niceAxis(minX, maxX, Math.max(2, getWidth() / UIScale.scale(110)));
            double[] yAxis = ResultSetChart.niceAxis(minY, maxY, Math.max(2, getHeight() / UIScale.scale(80)));
            g.setStroke(new BasicStroke(UIScale.scale(1f)));
            for (double x = xAxis[0]; x <= xAxis[1] + xAxis[2] / 1e6; x += xAxis[2]) {
                int sx = (int) Math.round(offsetX + x * scale);
                g.setColor(gridColor);
                g.drawLine(sx, 0, sx, getHeight());
                g.setColor(textColor);
                g.drawString(formatCoordinate(x, xAxis[2]), sx + UIScale.scale(3), getHeight() - UIScale.scale(4));
            }
            for (double y = yAxis[0]; y <= yAxis[1] + yAxis[2] / 1e6; y += yAxis[2]) {
                int sy = (int) Math.round(offsetY - y * scale);
                g.setColor(gridColor);
                g.drawLine(0, sy, getWidth(), sy);
                g.setColor(textColor);
                g.drawString(formatCoordinate(y, yAxis[2]), UIScale.scale(4), sy - UIScale.scale(3));
            }
        }

        /**
         * The topmost drawn item at a point: inside an area, or within a few pixels of a line or point.
         */
        private int itemAt(@NotNull Point point) {
            BasicStroke reach = new BasicStroke(UIScale.scale(8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            for (int i = hitShapes.size() - 1; i >= 0; i--) {
                HitShapes hit = hitShapes.get(i);
                for (Shape area : hit.areas()) {
                    if (area.contains(point)) {
                        return i;
                    }
                }
                for (Shape outline : hit.outlines()) {
                    if (outline.contains(point) || reach.createStrokedShape(outline).contains(point)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = itemAt(event.getPoint());
            if (index < 0) {
                return null;
            }
            Item item = items.get(index);
            Point2D world = new Point2D.Double((event.getX() - offsetX) / scale, (offsetY - event.getY()) / scale);
            return NLS.bind(EditorMessages.editor_spatial_tip, new Object[]{
                String.format("%,d", item.row() + 1), //$NON-NLS-1$
                item.geometry().getGeometryType(),
                formatCoordinate(world.getX(), 1 / scale) + ", " + formatCoordinate(world.getY(), 1 / scale)}); //$NON-NLS-1$
        }
    }

    @NotNull
    private static String formatCoordinate(double value, double step) {
        int digits = step >= 1 ? 0 : Math.min(8, (int) Math.ceil(-Math.log10(step)) + 1);
        return String.format(Locale.ROOT, "%." + digits + "f", Math.abs(value) < step / 1e6 ? 0.0 : value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static Color color(@NotNull String key, @NotNull Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}
