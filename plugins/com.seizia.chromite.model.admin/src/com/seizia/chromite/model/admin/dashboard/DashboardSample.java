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

import java.util.List;
import java.util.Map;

/**
 * 차트 하나를 한 번 읽은 결과: 시각과 계열별 값의 점들, 또는 오류.
 *
 * 설계: 읽기({@link DashboardSampler}, 백그라운드 작업)와 그리기(EDT)를 나누려고 값만 담은 변하지 않는 객체로 넘긴다.
 * 차분(delta) 차트의 첫 읽기처럼 아직 그릴 점이 없으면 {@link #points()}가 비어 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public record DashboardSample(
    @NotNull List<Point> points,
    boolean replace,
    @Nullable Throwable error
) {

    /**
     * Values of the series (by series name, in result order) at a time.
     */
    public record Point(long time, @NotNull Map<String, Double> values) {
    }

    @NotNull
    static DashboardSample failed(@NotNull Throwable error) {
        return new DashboardSample(List.of(), false, error);
    }
}
