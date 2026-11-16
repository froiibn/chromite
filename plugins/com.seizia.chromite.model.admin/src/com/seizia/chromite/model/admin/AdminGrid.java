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
package com.seizia.chromite.model.admin;

import com.seizia.chromite.model.admin.space.SpaceColumn;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 관리 화면(유지 관리, 복제)의 표 하나: 열, 행, 행마다 붙는 값(동작 대상, 상세 글), 비었을 때와 읽을 수 없을 때의 문구.
 *
 * 설계: 공간 사용량의 {@code SpaceGrid}는 표 종류가 공간 사용량에 맞춘 열거형이다. 유지 관리·복제 표는 DB마다 종류가 달라 문자열 ID로
 * 구분하고(새로 고침 뒤 고른 하위 탭 유지), 행에 동작 대상(유지 관리 후보의 대상과 권장 동작)과 상세 글(Oracle 컴파일 오류)을 붙일 수 있게 했다.
 * 열 정의는 공간 사용량과 같은 {@link SpaceColumn}이라 화면의 {@code AdminGridTable}이 그대로 그린다.
 * <ul>
 *     <li>{@link #getEmptyText()}: 행이 없을 때 표 대신 보일 문구. 복제가 구성되지 않은 서버는 오류가 아니라 "구성되지 않음"이다.</li>
 *     <li>{@link #getUnavailableReason()}: 로그인 권한으로 읽을 수 없을 때의 이유(화면이 탭 안에 보인다).</li>
 *     <li>{@link #getDescription()}: 표 위에 보일 설명(예: 부풀림은 통계로 계산한 추정치라는 것).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 유지 관리·복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public final class AdminGrid {

    @NotNull
    private final String id;
    @NotNull
    private final String title;
    @NotNull
    private final List<SpaceColumn> columns;
    @NotNull
    private final List<Object[]> rows = new ArrayList<>();
    @NotNull
    private final List<Object> rowTags = new ArrayList<>();
    @NotNull
    private final List<String> rowDetails = new ArrayList<>();
    @Nullable
    private String description;
    @Nullable
    private String emptyText;
    @Nullable
    private String unavailableReason;

    public AdminGrid(@NotNull String id, @NotNull String title, @NotNull List<SpaceColumn> columns) {
        this.id = id;
        this.title = title;
        this.columns = List.copyOf(columns);
    }

    public AdminGrid(@NotNull String id, @NotNull String title, @NotNull SpaceColumn... columns) {
        this(id, title, List.of(columns));
    }

    /**
     * Stable id: the view keeps the selected tab by id across refreshes.
     */
    @NotNull
    public String getId() {
        return id;
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
        return Collections.unmodifiableList(rows);
    }

    /**
     * Adds a row; values in column order (see {@link SpaceColumn.Kind} for their types).
     */
    public void addRow(@NotNull Object... values) {
        addTaggedRow(null, null, values);
    }

    /**
     * Adds a row with what an action on it applies to and a detail text.
     *
     * @param tag    value of the row for the view (e.g. the maintenance action it suggests), or null
     * @param detail longer text shown for the selected row (e.g. compile errors), or null
     */
    public void addTaggedRow(@Nullable Object tag, @Nullable String detail, @NotNull Object... values) {
        if (values.length != columns.size()) {
            throw new IllegalArgumentException("Expected " + columns.size() + " values, got " + values.length); //$NON-NLS-1$ //$NON-NLS-2$
        }
        rows.add(values);
        rowTags.add(tag);
        rowDetails.add(detail);
    }

    @Nullable
    public Object getRowTag(int row) {
        return rowTags.get(row);
    }

    @Nullable
    public String getRowDetail(int row) {
        return rowDetails.get(row);
    }

    /**
     * Whether any row has a detail text (the view then shows a detail pane).
     */
    public boolean hasDetails() {
        for (String detail : rowDetails) {
            if (detail != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    /**
     * Text shown instead of the grid when it has no rows (localized), or null to show the empty grid.
     */
    @Nullable
    public String getEmptyText() {
        return emptyText;
    }

    public void setEmptyText(@Nullable String emptyText) {
        this.emptyText = emptyText;
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
        rowTags.clear();
        rowDetails.clear();
    }
}
