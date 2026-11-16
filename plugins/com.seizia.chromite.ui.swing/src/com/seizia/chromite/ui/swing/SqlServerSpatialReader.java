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

import com.seizia.chromite.model.gis.CircularArc;
import org.jkiss.code.NotNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server {@code geometry}/{@code geography} 값의 바이트(CLR 직렬화 형식, [MS-SSCLRT] 2.1)를 JTS 기하로 읽는다. 결과 표의 공간 보기만 쓴다.
 *
 * 설계: Microsoft JDBC 드라이버는 두 형식을 바이트 배열로 넘기고, 모델 계층에는 SQL Server 공간 값 처리기가 없다(PostGIS·MySQL 등은
 * {@code data.gis}의 {@code DBGeometry}로 받는다). 모델에 값 처리기를 더하면 표의 표시·편집·저장·내보내기가 함께 바뀌므로, 이미 읽은 바이트를
 * 공간 보기에서만 해석한다. 드라이버의 {@code com.microsoft.sqlserver.jdbc.Geometry}는 드라이버 클래스 로더에 있어 Swing 번들에서 부를 수 없다.
 * <ul>
 *     <li>머리: SRID(4바이트), 버전(1 또는 2), 속성 바이트(Z, M, 단일 점, 단일 선분). 그 뒤 점 배열, Z·M 배열, 도형(figure) 배열,
 *     모양(shape) 배열이 온다. 모양은 부모 번호로 묶음(Multi*, GeometryCollection)을 이룬다.</li>
 *     <li>{@code geography}의 점은 위도, 경도 순서로 들어 있다(실제 값 {@code geography::Point(37.5665, 126.978, 4326)}의 바이트로 확인).
 *     지도처럼 경도를 가로축에 두려고 x = 경도, y = 위도로 바꾼다.</li>
 *     <li>버전 2의 곡선(CircularString, CompoundCurve, CurvePolygon)은 {@code data.gis}의 {@link CircularArc}로 직선으로 나눈다.
 *     Z·M 값은 그리지 않으므로 읽고 버린다. FullGlobe는 그릴 수 없어 빈 기하로 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-result-charts-spatial.md)
 */
final class SqlServerSpatialReader {

    private static final int FLAG_HAS_Z = 0x01;
    private static final int FLAG_HAS_M = 0x02;
    private static final int FLAG_SINGLE_POINT = 0x08;
    private static final int FLAG_SINGLE_LINE_SEGMENT = 0x10;

    // OpenGIS shape types of the format
    private static final int TYPE_POINT = 1;
    private static final int TYPE_LINESTRING = 2;
    private static final int TYPE_POLYGON = 3;
    private static final int TYPE_MULTIPOINT = 4;
    private static final int TYPE_MULTILINESTRING = 5;
    private static final int TYPE_MULTIPOLYGON = 6;
    private static final int TYPE_GEOMETRYCOLLECTION = 7;
    private static final int TYPE_CIRCULARSTRING = 8;
    private static final int TYPE_COMPOUNDCURVE = 9;
    private static final int TYPE_CURVEPOLYGON = 10;

    // Figure attributes of version 2 (version 1: 0 interior ring, 1 stroke, 2 exterior ring; all straight)
    private static final int FIGURE_V2_ARC = 2;
    private static final int FIGURE_V2_COMPOSITE = 3;

    // Segment types of a composite curve (version 2): the "first" variants start a new run of points
    private static final int SEGMENT_LINE = 0;
    private static final int SEGMENT_ARC = 1;
    private static final int SEGMENT_FIRST_LINE = 2;

    // As data.gis WKGUtils.linearize: about 32 segments per quadrant
    private static final double ARC_TOLERANCE = 0.001;

    private record Figure(int attribute, int pointOffset) {
    }

    private record Shape(int parent, int figureOffset, int type) {
    }

    private final GeometryFactory factory;
    private final boolean geography;
    private int version;
    private Coordinate[] points;
    private Figure[] figures;
    private Shape[] shapes;
    private int[] segments;
    private int segmentIndex;

    private SqlServerSpatialReader(boolean geography, int srid) {
        this.geography = geography;
        this.factory = new GeometryFactory(new org.locationtech.jts.geom.PrecisionModel(), srid);
    }

    /**
     * Reads a serialized value.
     *
     * @param geography true for {@code geography} (latitude first), false for {@code geometry}
     * @throws IllegalArgumentException if the bytes are not a value of this format
     */
    @NotNull
    static Geometry read(@NotNull byte[] bytes, boolean geography) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int srid = buffer.getInt();
            SqlServerSpatialReader reader = new SqlServerSpatialReader(geography, srid);
            return reader.readValue(buffer);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a SQL Server spatial value: " + e.getMessage(), e);
        }
    }

    @NotNull
    private Geometry readValue(@NotNull ByteBuffer buffer) {
        version = buffer.get() & 0xFF;
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("unknown version " + version);
        }
        int flags = buffer.get() & 0xFF;
        if ((flags & FLAG_SINGLE_POINT) != 0) {
            points = readPoints(buffer, 1, flags);
            return factory.createPoint(points[0]);
        }
        if ((flags & FLAG_SINGLE_LINE_SEGMENT) != 0) {
            points = readPoints(buffer, 2, flags);
            return factory.createLineString(points);
        }
        points = readPoints(buffer, buffer.getInt(), flags);
        figures = new Figure[buffer.getInt()];
        for (int i = 0; i < figures.length; i++) {
            int attribute = buffer.get() & 0xFF;
            figures[i] = new Figure(attribute, buffer.getInt());
        }
        shapes = new Shape[buffer.getInt()];
        for (int i = 0; i < shapes.length; i++) {
            int parent = buffer.getInt();
            int figureOffset = buffer.getInt();
            shapes[i] = new Shape(parent, figureOffset, buffer.get() & 0xFF);
        }
        if (version == 2 && buffer.remaining() >= 4) {
            segments = new int[buffer.getInt()];
            for (int i = 0; i < segments.length; i++) {
                segments[i] = buffer.get() & 0xFF;
            }
        } else {
            segments = new int[0];
        }
        if (shapes.length == 0) {
            return factory.createGeometryCollection();
        }
        return readShape(0);
    }

    @NotNull
    private Coordinate[] readPoints(@NotNull ByteBuffer buffer, int count, int flags) {
        Coordinate[] result = new Coordinate[count];
        for (int i = 0; i < count; i++) {
            double first = buffer.getDouble();
            double second = buffer.getDouble();
            // geography: latitude, longitude; drawn with the longitude across
            result[i] = geography ? new Coordinate(second, first) : new Coordinate(first, second);
        }
        // Z and M values are not drawn
        int skip = ((flags & FLAG_HAS_Z) != 0 ? count : 0) + ((flags & FLAG_HAS_M) != 0 ? count : 0);
        buffer.position(buffer.position() + skip * Double.BYTES);
        return result;
    }

    @NotNull
    private Geometry readShape(int index) {
        Shape shape = shapes[index];
        int firstFigure = shape.figureOffset();
        int endFigure = figureEnd(index);
        switch (shape.type()) {
            case TYPE_POINT:
                return firstFigure < 0 ? factory.createPoint() : factory.createPoint(figurePoints(firstFigure)[0]);
            case TYPE_LINESTRING:
            case TYPE_CIRCULARSTRING:
            case TYPE_COMPOUNDCURVE:
                return firstFigure < 0 ? factory.createLineString() : factory.createLineString(curvePoints(firstFigure));
            case TYPE_POLYGON:
            case TYPE_CURVEPOLYGON:
                return createPolygon(firstFigure, endFigure);
            case TYPE_MULTIPOINT:
                return factory.createMultiPoint(children(index).stream().map(Point.class::cast).toArray(Point[]::new));
            case TYPE_MULTILINESTRING:
                return factory.createMultiLineString(children(index).stream().map(LineString.class::cast).toArray(LineString[]::new));
            case TYPE_MULTIPOLYGON:
                return factory.createMultiPolygon(children(index).stream().map(Polygon.class::cast).toArray(Polygon[]::new));
            case TYPE_GEOMETRYCOLLECTION:
                return factory.createGeometryCollection(children(index).toArray(Geometry[]::new));
            default:
                // FullGlobe and unknown shapes: nothing to draw
                return factory.createGeometryCollection();
        }
    }

    @NotNull
    private Polygon createPolygon(int firstFigure, int endFigure) {
        if (firstFigure < 0 || firstFigure >= endFigure) {
            return factory.createPolygon();
        }
        LinearRing shell = createRing(curvePoints(firstFigure));
        LinearRing[] holes = new LinearRing[endFigure - firstFigure - 1];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = createRing(curvePoints(firstFigure + 1 + i));
        }
        return factory.createPolygon(shell, holes);
    }

    @NotNull
    private LinearRing createRing(@NotNull Coordinate[] coordinates) {
        // Linearized arcs can miss the first point by rounding: close the ring explicitly
        if (coordinates.length > 0 && !coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
            Coordinate[] closed = new Coordinate[coordinates.length + 1];
            System.arraycopy(coordinates, 0, closed, 0, coordinates.length);
            closed[coordinates.length] = coordinates[0];
            coordinates = closed;
        }
        return factory.createLinearRing(coordinates);
    }

    @NotNull
    private List<Geometry> children(int index) {
        List<Geometry> result = new ArrayList<>();
        for (int i = index + 1; i < shapes.length; i++) {
            if (shapes[i].parent() == index) {
                result.add(readShape(i));
            }
        }
        return result;
    }

    /**
     * The figure after the last figure of a shape: the figure of the next shape that has figures and is not inside this
     * shape, or the end.
     */
    private int figureEnd(int index) {
        for (int i = index + 1; i < shapes.length; i++) {
            if (!isInside(i, index) && shapes[i].figureOffset() >= 0) {
                return shapes[i].figureOffset();
            }
        }
        return figures.length;
    }

    private boolean isInside(int shape, int ancestor) {
        for (int parent = shapes[shape].parent(); parent >= 0; parent = shapes[parent].parent()) {
            if (parent == ancestor) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private Coordinate[] figurePoints(int figure) {
        int start = figures[figure].pointOffset();
        int end = figure + 1 < figures.length ? figures[figure + 1].pointOffset() : points.length;
        Coordinate[] result = new Coordinate[Math.max(0, end - start)];
        System.arraycopy(points, start, result, 0, result.length);
        return result;
    }

    /**
     * The points of a figure with its arcs made straight.
     */
    @NotNull
    private Coordinate[] curvePoints(int figure) {
        Coordinate[] controls = figurePoints(figure);
        if (version < 2) {
            return controls;
        }
        int attribute = figures[figure].attribute();
        if (attribute == FIGURE_V2_ARC) {
            return linearizeArcs(controls);
        }
        if (attribute == FIGURE_V2_COMPOSITE) {
            return linearizeComposite(controls);
        }
        return controls;
    }

    @NotNull
    private static Coordinate[] linearizeArcs(@NotNull Coordinate[] controls) {
        List<Coordinate> result = new ArrayList<>();
        result.add(controls[0]);
        for (int i = 2; i < controls.length; i += 2) {
            appendArc(result, controls[i - 2], controls[i - 1], controls[i]);
        }
        return result.toArray(Coordinate[]::new);
    }

    /**
     * A composite curve: its points are consumed by the segments of the value's segment list, in figure order.
     */
    @NotNull
    private Coordinate[] linearizeComposite(@NotNull Coordinate[] controls) {
        List<Coordinate> result = new ArrayList<>();
        result.add(controls[0]);
        int point = 0;
        boolean first = true;
        while (segmentIndex < segments.length && point < controls.length - 1) {
            int type = segments[segmentIndex];
            if (!first && type >= SEGMENT_FIRST_LINE) {
                // The next figure's run starts here
                break;
            }
            segmentIndex++;
            first = false;
            if (type == SEGMENT_LINE || type == SEGMENT_FIRST_LINE) {
                result.add(controls[++point]);
            } else if (point + 2 < controls.length) {
                appendArc(result, controls[point], controls[point + 1], controls[point + 2]);
                point += 2;
            } else {
                break;
            }
        }
        return result.toArray(Coordinate[]::new);
    }

    private static void appendArc(@NotNull List<Coordinate> result, @NotNull Coordinate start, @NotNull Coordinate middle, @NotNull Coordinate end) {
        List<org.cugos.wkg.Coordinate> arc = new CircularArc(
            org.cugos.wkg.Coordinate.create2D(start.x, start.y),
            org.cugos.wkg.Coordinate.create2D(middle.x, middle.y),
            org.cugos.wkg.Coordinate.create2D(end.x, end.y)).linearize(ARC_TOLERANCE);
        // The arc repeats its start point, which is in the result already
        for (int i = 1; i < arc.size(); i++) {
            result.add(new Coordinate(arc.get(i).getX(), arc.get(i).getY()));
        }
    }
}
