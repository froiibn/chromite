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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.dashboard.registry.DashboardItemConfiguration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차트 하나의 계열별 최근 값(시계열).
 *
 * 설계: SWT는 JFreeChart의 {@code TimeSeries}가 점을 들고 최대 개수({@code maxItems}, 기본 300)와 최대 나이({@code maxAge}, 기본 30분)로
 * 오래된 점을 지운다. Swing 차트는 JFreeChart를 쓰지 않으므로(docs/chromite/swing-dashboard.md "차트 라이브러리") 같은 규칙을 이 클래스가 맡는다.
 * 계열 순서는 처음 나온 순서이고, 계열이 나중에 사라져도(예: MySQL 세션의 명령 종류) 지우지 않는다. 화면이 계열 색을 순서로 정하므로
 * 계열이 줄어도 남은 계열의 색이 바뀌지 않는다.
 * EDT에서만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public final class DashboardHistory {

    /**
     * A point of a series.
     */
    public record Value(long time, double value) {
    }

    private final int maxItems;
    private final long maxAge;
    private final Map<String, Deque<Value>> series = new LinkedHashMap<>();
    private long lastTime;

    public DashboardHistory(@NotNull DashboardItemConfiguration item) {
        this.maxItems = Math.max(2, item.getMaxItems());
        this.maxAge = item.getMaxAge();
    }

    /**
     * Adds the points of a read (or replaces all of them for charts that read whole statistics).
     */
    public void add(@NotNull DashboardSample sample) {
        if (sample.error() != null) {
            return;
        }
        if (sample.replace()) {
            series.values().forEach(Deque::clear);
        }
        for (DashboardSample.Point point : sample.points()) {
            for (Map.Entry<String, Double> entry : point.values().entrySet()) {
                Deque<Value> values = series.computeIfAbsent(entry.getKey(), k -> new ArrayDeque<>());
                Value last = values.peekLast();
                if (last != null && last.time() >= point.time()) {
                    // Same time again (a chart with STAT_TIMESTAMP that did not change): keep the newer value
                    if (last.time() == point.time()) {
                        values.pollLast();
                    } else {
                        continue;
                    }
                }
                values.addLast(new Value(point.time(), entry.getValue()));
                while (values.size() > maxItems) {
                    values.pollFirst();
                }
            }
            lastTime = Math.max(lastTime, point.time());
        }
        if (maxAge > 0) {
            long oldest = lastTime - maxAge;
            for (Deque<Value> values : series.values()) {
                while (!values.isEmpty() && values.peekFirst().time() < oldest) {
                    values.pollFirst();
                }
            }
        }
    }

    public void clear() {
        series.clear();
        lastTime = 0;
    }

    /**
     * Series names in the order they first appeared.
     */
    @NotNull
    public List<String> getSeriesNames() {
        return new ArrayList<>(series.keySet());
    }

    @NotNull
    public List<Value> getValues(@NotNull String seriesName) {
        Deque<Value> values = series.get(seriesName);
        return values == null ? List.of() : new ArrayList<>(values);
    }

    /**
     * The newest value of a series, or null.
     */
    @Nullable
    public Value getLast(@NotNull String seriesName) {
        Deque<Value> values = series.get(seriesName);
        return values == null ? null : values.peekLast();
    }

    /**
     * Time of the newest point, 0 when empty.
     */
    public long getLastTime() {
        return lastTime;
    }

    public boolean isEmpty() {
        return series.values().stream().allMatch(Deque::isEmpty);
    }
}
