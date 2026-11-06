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
package com.seizia.chromite.model.admin.dashboard;

import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlExpression;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.dashboard.DBDashboardCalcType;
import org.jkiss.dbeaver.model.dashboard.DBDashboardFetchType;
import org.jkiss.dbeaver.model.dashboard.DBDashboardMapQuery;
import org.jkiss.dbeaver.model.dashboard.DBDashboardValueType;
import org.jkiss.dbeaver.model.dashboard.DashboardConstants;
import org.jkiss.dbeaver.model.dashboard.registry.DashboardItemConfiguration;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 서버 대시보드 차트의 값을 읽는다(연결 하나, 차트 여러 개).
 *
 * 설계: SWT에서는 이 일을 UI 플러그인 {@code org.jkiss.dbeaver.ui.dashboard}가 한다. 질의 실행과 결과 모양 바꾸기는
 * {@code DashboardUpdater}(updateDashboard, readMapQueryData, fetchDashboardMapData, fetchDashboardData, transposeDataset), 차분 계산은
 * 차트 그리기 클래스 {@code DashboardRendererTimeseries#updateDashboardData}에 있고, 둘 다 SWT·JFreeChart 화면 객체에 묶여 있다.
 * Swing 번들은 SWT 플러그인을 쓸 수 없으므로 화면과 상관없는 부분만 이 모델 클래스로 다시 만들었다. 정의(질의, 계산 방식, 값 형식)는
 * DBeaver 모델({@link DashboardItemConfiguration})을 그대로 읽는다.
 * <ul>
 *     <li>한 번 읽을 때 같은 맵 질의(예: MySQL {@code SHOW GLOBAL STATUS})는 한 번만 실행하고 그 값을 쓰는 차트가 나눠 쓴다(SWT와 같음).</li>
 *     <li>차트 하나의 질의가 실패해도 다른 차트는 계속 읽는다. SWT는 그 차트의 자동 갱신을 끄지만, 여기서는 오류를 결과로 돌려주고
 *     끌지 말지는 화면이 정한다.</li>
 *     <li>차분({@code calc="delta"})은 직전 값과의 차이를 두 읽기 사이의 시간(밀리초)으로 나눈 초당 값이다. SWT는 초 단위로 버린 경과 시간으로
 *     나누어 1.9초 간격이 1초로 계산되는 일이 있었다. 반올림 규칙(값 형식이 {@code decimal}이 아니면 정수로)은 SWT와 같다.</li>
 *     <li>읽은 시각은 결과의 {@code STAT_TIMESTAMP} 열이 있으면 그 값, 없으면 읽은 때다(SWT와 같음).</li>
 * </ul>
 * 같은 작업 안에서만 부른다(직전 값을 들고 있어 여러 스레드에서 동시에 부르지 않는다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public final class DashboardSampler {

    private static final Log log = Log.getLog(DashboardSampler.class);

    private record RawPoint(long time, @NotNull Map<String, Double> values) {
    }

    /**
     * Last raw values of the delta charts, by chart id.
     */
    private final Map<String, RawPoint> lastRaw = new HashMap<>();

    /**
     * Reads the current values of the charts.
     *
     * @return the sample of each chart, in the order of the charts
     */
    @NotNull
    public Map<DashboardItemConfiguration, DashboardSample> sample(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCSession session,
        @NotNull Collection<DashboardItemConfiguration> items
    ) {
        session.enableLogging(false);
        // Each map query runs once per read; its result (or error) serves all charts over it
        Map<DBDashboardMapQuery, MapResult> mapResults = new HashMap<>();
        Map<DBDashboardMapQuery, DBException> mapErrors = new HashMap<>();
        Map<DashboardItemConfiguration, DashboardSample> result = new LinkedHashMap<>();
        for (DashboardItemConfiguration item : items) {
            if (monitor.isCanceled()) {
                break;
            }
            DashboardSample sample;
            try {
                List<RawPoint> rows;
                DBDashboardMapQuery mapQuery = item.getMapQuery();
                if (mapQuery != null) {
                    DBException mapError = mapErrors.get(mapQuery);
                    if (mapError != null) {
                        throw mapError;
                    }
                    MapResult map = mapResults.get(mapQuery);
                    if (map == null) {
                        try {
                            map = readMapQuery(monitor, session, mapQuery);
                        } catch (DBException e) {
                            mapErrors.put(mapQuery, e);
                            throw e;
                        }
                        mapResults.put(mapQuery, map);
                    }
                    rows = readMapItem(item, map);
                } else {
                    rows = readQueries(monitor, session, item);
                }
                sample = calculate(item, rows);
            } catch (Throwable e) {
                log.debug("Error reading dashboard '" + item.getId() + "'", e);
                lastRaw.remove(item.getId());
                sample = DashboardSample.failed(e);
            }
            result.put(item, sample);
        }
        return result;
    }

    /**
     * Forgets the last values of the delta charts (the next read only sets the base again).
     */
    public void reset() {
        lastRaw.clear();
    }

    private record MapResult(long time, @NotNull Map<String, Object> values) {
    }

    @NotNull
    private static MapResult readMapQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCSession session,
        @NotNull DBDashboardMapQuery mapQuery
    ) throws DBException {
        Map<String, Object> values = new HashMap<>();
        long time = System.currentTimeMillis();
        try (DBCStatement dbStat = session.prepareStatement(DBCStatementType.QUERY, mapQuery.getQueryText(), false, false, false)) {
            if (dbStat.executeStatement()) {
                try (DBCResultSet dbResults = dbStat.openResultSet()) {
                    time = System.currentTimeMillis();
                    while (dbResults != null && dbResults.nextRow()) {
                        values.put(CommonUtils.toString(dbResults.getAttributeValue(0)), dbResults.getAttributeValue(1));
                    }
                }
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBCException("Error reading map query '" + mapQuery.getId() + "' data", e);
        }
        return new MapResult(time, values);
    }

    /**
     * Values of a chart over a map query: the listed keys, or the formula over all values (SWT {@code fetchDashboardMapData}).
     */
    @NotNull
    private static List<RawPoint> readMapItem(@NotNull DashboardItemConfiguration item, @NotNull MapResult map) throws DBException {
        String[] mapKeys = item.getMapKeys();
        String[] mapLabels = item.getMapLabels();
        Map<String, Double> values = new LinkedHashMap<>();
        if (!ArrayUtils.isEmpty(mapKeys)) {
            if (ArrayUtils.isEmpty(mapLabels) || mapLabels.length < mapKeys.length) {
                mapLabels = mapKeys;
            }
            for (int i = 0; i < mapKeys.length; i++) {
                Double value = toDouble(map.values().get(mapKeys[i]));
                if (value != null) {
                    values.put(mapLabels[i], value);
                }
            }
        } else {
            JexlExpression formula = item.getMapFormulaExpr();
            if (formula == null) {
                return List.of();
            }
            Map<String, Object> ciMap = new HashMap<>(map.values().size());
            for (Map.Entry<String, Object> entry : map.values().entrySet()) {
                // SHOW GLOBAL STATUS returns text: give the formula numbers
                Object value = entry.getValue();
                Double number = toDouble(value);
                ciMap.put(entry.getKey().toLowerCase(Locale.ENGLISH), number != null ? (Object) number : value);
            }
            Object result;
            try {
                result = formula.evaluate(new MapContext(ciMap, item));
            } catch (Exception e) {
                if (CommonUtils.getRootCause(e) instanceof ArithmeticException) {
                    // Division by zero (e.g. no key reads yet): the value is undefined at this moment, not an error
                    return List.of();
                }
                // Shown on the chart: the JEXL message names internal classes, so it goes only to the log
                log.debug("Error evaluating the formula of dashboard '" + item.getId() + "'", e);
                throw new DBException("The formula of the chart cannot be evaluated: " + formula.getSourceText());
            }
            if (!(result instanceof Number number)) {
                return List.of();
            }
            String name = !ArrayUtils.isEmpty(mapLabels) ? mapLabels[0] : item.getName();
            values.put(name, number.doubleValue());
        }
        return List.of(new RawPoint(map.time(), values));
    }

    /**
     * Variables of a map formula: {@code map} (values by lower case key) and {@code dashboard}, as in SWT.
     */
    private record MapContext(@NotNull Map<String, Object> map, @NotNull DashboardItemConfiguration dashboard) implements JexlContext {
        @Override
        public Object get(String name) {
            return switch (name) {
                case "map" -> map; //$NON-NLS-1$
                case "dashboard" -> dashboard; //$NON-NLS-1$
                default -> null;
            };
        }

        @Override
        public void set(String name, Object value) {
            log.debug("Dashboard formulas cannot set variables");
        }

        @Override
        public boolean has(String name) {
            return "map".equals(name) || "dashboard".equals(name); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Runs the queries of a chart (SWT {@code updateDashboard} and {@code fetchDashboardData}).
     */
    @NotNull
    private static List<RawPoint> readQueries(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCSession session,
        @NotNull DashboardItemConfiguration item
    ) throws DBException {
        List<RawPoint> rows = new ArrayList<>();
        for (DashboardItemConfiguration.QueryMapping query : item.getQueries()) {
            if (monitor.isCanceled()) {
                break;
            }
            try (DBCStatement dbStat = session.prepareStatement(DBCStatementType.QUERY, query.getQueryText(), false, false, false)) {
                if (dbStat.executeStatement()) {
                    try (DBCResultSet dbResults = dbStat.openResultSet()) {
                        if (dbResults != null) {
                            rows.addAll(readResults(item, dbResults));
                        }
                    }
                }
            } catch (DBException e) {
                throw e;
            } catch (Exception e) {
                throw new DBCException("Error reading dashboard '" + item.getId() + "'", e);
            }
        }
        return rows;
    }

    @NotNull
    private static List<RawPoint> readResults(@NotNull DashboardItemConfiguration item, @NotNull DBCResultSet dbResults) throws DBCException {
        List<String> columns = new ArrayList<>();
        int timestampColumn = -1;
        List<? extends DBCAttributeMetaData> attributes = dbResults.getMeta().getAttributes();
        for (int i = 0; i < attributes.size(); i++) {
            DBCAttributeMetaData attribute = attributes.get(i);
            String name = attribute.getLabel();
            if (CommonUtils.isEmpty(name)) {
                name = attribute.getName();
            }
            if (DashboardConstants.RS_COL_TIMESTAMP.equalsIgnoreCase(name)) {
                timestampColumn = i;
            }
            columns.add(name);
        }
        boolean byRows = item.getFetchType() == DBDashboardFetchType.rows;
        long now = System.currentTimeMillis();
        List<RawPoint> rows = new ArrayList<>();
        Map<String, Double> transposed = new LinkedHashMap<>();
        int rowIndex = 0;
        while (dbResults.nextRow() && rowIndex < item.getMaxItems()) {
            rowIndex++;
            long time = now;
            if (timestampColumn >= 0 && dbResults.getAttributeValue(timestampColumn) instanceof Date date) {
                time = date.getTime();
            }
            if (byRows) {
                // First column is the series name, the second its value (SWT transposeDataset with one value column)
                if (columns.size() >= 2) {
                    String name = CommonUtils.toString(dbResults.getAttributeValue(0), String.valueOf(rowIndex));
                    Double value = toDouble(dbResults.getAttributeValue(1));
                    if (value != null) {
                        transposed.put(name, value);
                    }
                }
                continue;
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                if (i == timestampColumn) {
                    continue;
                }
                Double value = toDouble(dbResults.getAttributeValue(i));
                if (value != null) {
                    values.put(columns.get(i), value);
                }
            }
            rows.add(new RawPoint(time, values));
        }
        if (byRows) {
            rows.add(new RawPoint(now, transposed));
        }
        return rows;
    }

    /**
     * Turns read values into chart points: as they are, or the change per second since the last read.
     */
    @NotNull
    private DashboardSample calculate(@NotNull DashboardItemConfiguration item, @NotNull List<RawPoint> rows) {
        boolean replace = item.getFetchType() == DBDashboardFetchType.stats;
        List<DashboardSample.Point> points = new ArrayList<>();
        if (item.getCalcType() == DBDashboardCalcType.delta) {
            RawPoint previous = lastRaw.get(item.getId());
            for (RawPoint row : rows) {
                if (previous != null && row.time() > previous.time()) {
                    double seconds = (row.time() - previous.time()) / 1000.0;
                    Map<String, Double> values = new LinkedHashMap<>();
                    for (Map.Entry<String, Double> entry : row.values().entrySet()) {
                        Double before = previous.values().get(entry.getKey());
                        if (before == null) {
                            continue;
                        }
                        double delta = (entry.getValue() - before) / seconds;
                        if (item.getValueType() != DBDashboardValueType.decimal) {
                            delta = Math.round(delta);
                        }
                        // A counter that went back (server restart, statistics reset) has no rate for this interval
                        if (delta >= 0) {
                            values.put(entry.getKey(), delta);
                        }
                    }
                    points.add(new DashboardSample.Point(row.time(), values));
                }
                previous = row;
            }
            if (previous != null) {
                lastRaw.put(item.getId(), previous);
            }
        } else {
            for (RawPoint row : rows) {
                points.add(new DashboardSample.Point(row.time(), row.values()));
            }
        }
        return new DashboardSample(points, replace, null);
    }

    @Nullable
    private static Double toDouble(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
