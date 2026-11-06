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
package com.seizia.chromite.model.admin.space;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 공간 사용량 화면의 표 하나(데이터베이스, 파일, 테이블스페이스, 데이터 파일, 큰 테이블).
 *
 * 설계: DB마다 보이는 단위가 다르다(SQL Server 파일, PostgreSQL·Oracle 테이블스페이스, Oracle 데이터 파일). 표마다 종류({@link Kind})와
 * 열({@link SpaceColumn})을 방언이 정하고 화면은 표를 하나씩 하위 탭으로 보인다. 로그인 권한으로 읽을 수 없는 표는 행 없이
 * {@link #getUnavailableReason()}을 가진다(화면이 탭을 끄고 안내한다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public final class SpaceGrid {

    /**
     * What the rows are; the view keeps the selected tab by kind across refreshes.
     */
    public enum Kind {
        DATABASES,
        FILES,
        TABLESPACES,
        DATA_FILES,
        TABLES
    }

    @NotNull
    private final Kind kind;
    @NotNull
    private final String title;
    @NotNull
    private final List<SpaceColumn> columns;
    @NotNull
    private final List<Object[]> rows = new ArrayList<>();
    @Nullable
    private String unavailableReason;

    public SpaceGrid(@NotNull Kind kind, @NotNull String title, @NotNull SpaceColumn... columns) {
        this.kind = kind;
        this.title = title;
        this.columns = List.of(columns);
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    @NotNull
    public String getTitle() {
        return title;
    }

    @NotNull
    public List<SpaceColumn> getColumns() {
        return columns;
    }

    @NotNull
    public List<Object[]> getRows() {
        return rows;
    }

    /**
     * Adds a row; values in column order (see {@link SpaceColumn.Kind} for their types).
     */
    public void addRow(@NotNull Object... values) {
        if (values.length != columns.size()) {
            throw new IllegalArgumentException("Expected " + columns.size() + " values, got " + values.length); //$NON-NLS-1$ //$NON-NLS-2$
        }
        rows.add(values);
    }

    /**
     * Why the login cannot read this grid (localized), or null if it was read.
     */
    @Nullable
    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailable(@NotNull String reason) {
        unavailableReason = reason;
        rows.clear();
    }
}
