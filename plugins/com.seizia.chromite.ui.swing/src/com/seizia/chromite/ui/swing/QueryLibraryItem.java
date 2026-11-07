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

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Query Library의 쿼리 하나(파일 하나): 이름, 설명, SQL, 기본 실행 대상, 기본 DB/스키마, 만든 시각, 매개변수 정의.
 *
 * 설계:
 * <ul>
 *     <li>저장 형식은 {@link QueryLibraryFile}(머리말 주석이 붙은 {@code .sql} 파일)이다. 이 클래스는 파일 내용을 메모리에 담은 값이고
 *     편집기·대화상자는 사본({@link #copy})을 고친 뒤 저장한다.</li>
 *     <li>ID({@code @id})는 파일을 옮기거나 이름을 바꿔도 유지된다. 마지막 입력값처럼 쿼리에 붙는 이 PC의 상태는 ID로 찾는다
 *     ({@link QueryLibraryState}). 머리말이 없는 파일(다른 도구에서 만든 {@code .sql})은 처음 저장할 때 ID를 받는다.</li>
 *     <li>실행 대상: {@link TargetMode#ASK}(실행할 때마다 묻기), {@link TargetMode#CURRENT}(Object Explorer에서 고른 연결, 없으면 활성 쿼리 창의 연결,
 *     없으면 묻기), {@link TargetMode#CONNECTION}(정해진 연결). 정해진 연결은 데이터 소스 ID와 이름을 함께 저장해,
 *     공유한 쿼리를 다른 PC에서 열면 ID가 달라도 이름으로 찾는다(둘 다 없으면 묻는다).</li>
 *     <li>모르는 머리말 줄({@link #getExtraHeader})은 그대로 보관했다가 다시 쓴다. 다른 버전의 Chromite가 쓴 항목을 지우지 않기 위해서다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "저장 형식")
 * - 2026-11-06 Query Library ③: 예약 실행 정의({@link QueryLibrarySchedule}) (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 고친 시각({@code @updated})을 지움: 저장마다 바뀌어 Git에서 늘 충돌함. 고친 시각은 파일의 수정 시각
 *   (docs/chromite/swing-query-library.md "저장 형식")
 */
final class QueryLibraryItem {

    /**
     * Default target of a run; the id is the value of {@code @target:} in the file.
     */
    enum TargetMode {
        ASK("ask", ToolsMessages.library_target_ask), //$NON-NLS-1$
        CURRENT("current", ToolsMessages.library_target_current), //$NON-NLS-1$
        CONNECTION("connection", ToolsMessages.library_target_connection); //$NON-NLS-1$

        private final String id;
        private final String label;

        TargetMode(@NotNull String id, @NotNull String label) {
            this.id = id;
            this.label = label;
        }

        @NotNull
        String getId() {
            return id;
        }

        @NotNull
        String getLabel() {
            return label;
        }

        @NotNull
        static TargetMode fromId(@Nullable String id) {
            String value = CommonUtils.notEmpty(id).trim().toLowerCase(Locale.ROOT);
            for (TargetMode mode : values()) {
                if (mode.id.equals(value)) {
                    return mode;
                }
            }
            return CURRENT;
        }
    }

    @Nullable
    private Path file;
    @Nullable
    private String id;
    private String name;
    @Nullable
    private String description;
    private String sql = "";
    private TargetMode targetMode = TargetMode.CURRENT;
    @Nullable
    private String targetId;
    @Nullable
    private String targetName;
    @Nullable
    private String database;
    @Nullable
    private String schema;
    @Nullable
    private OffsetDateTime created;
    private final List<QueryLibraryParameter> parameters = new ArrayList<>();
    @Nullable
    private QueryLibrarySchedule schedule;
    private final List<String> extraHeader = new ArrayList<>();
    private boolean hasHeader;

    QueryLibraryItem(@NotNull String name) {
        this.name = name;
    }

    /**
     * A new query with a fresh id.
     */
    @NotNull
    static QueryLibraryItem create(@NotNull String name, @NotNull String sql) {
        QueryLibraryItem item = new QueryLibraryItem(name);
        item.id = UUID.randomUUID().toString();
        item.sql = sql;
        item.created = OffsetDateTime.now().withNano(0);
        return item;
    }

    @NotNull
    QueryLibraryItem copy() {
        QueryLibraryItem copy = new QueryLibraryItem(name);
        copy.file = file;
        copy.id = id;
        copy.description = description;
        copy.sql = sql;
        copy.targetMode = targetMode;
        copy.targetId = targetId;
        copy.targetName = targetName;
        copy.database = database;
        copy.schema = schema;
        copy.created = created;
        parameters.forEach(p -> copy.parameters.add(p.copy()));
        copy.schedule = schedule != null ? schedule.copy() : null;
        copy.extraHeader.addAll(extraHeader);
        copy.hasHeader = hasHeader;
        return copy;
    }

    @Nullable
    Path getFile() {
        return file;
    }

    void setFile(@Nullable Path file) {
        this.file = file;
    }

    /**
     * The id from the file; a file without a header has none until it is saved.
     */
    @Nullable
    String getId() {
        return id;
    }

    void setId(@Nullable String id) {
        this.id = id;
    }

    /**
     * Key of the local state of this query: its id, or its file for a file without an id.
     */
    @NotNull
    String getStateKey() {
        return id != null ? id : "file:" + file; //$NON-NLS-1$
    }

    /**
     * Gives the query an id if it has none (it is written by the next save).
     */
    void ensureId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotNull
    String getName() {
        return name;
    }

    void setName(@NotNull String name) {
        this.name = name;
    }

    @Nullable
    String getDescription() {
        return description;
    }

    void setDescription(@Nullable String description) {
        this.description = CommonUtils.nullIfEmpty(description);
    }

    @NotNull
    String getSql() {
        return sql;
    }

    void setSql(@NotNull String sql) {
        this.sql = sql;
    }

    @NotNull
    TargetMode getTargetMode() {
        return targetMode;
    }

    void setTargetMode(@NotNull TargetMode targetMode) {
        this.targetMode = targetMode;
    }

    @Nullable
    String getTargetId() {
        return targetId;
    }

    @Nullable
    String getTargetName() {
        return targetName;
    }

    void setTarget(@Nullable String targetId, @Nullable String targetName) {
        this.targetId = CommonUtils.nullIfEmpty(targetId);
        this.targetName = CommonUtils.nullIfEmpty(targetName);
    }

    @Nullable
    String getDatabase() {
        return database;
    }

    void setDatabase(@Nullable String database) {
        this.database = CommonUtils.nullIfEmpty(database);
    }

    @Nullable
    String getSchema() {
        return schema;
    }

    void setSchema(@Nullable String schema) {
        this.schema = CommonUtils.nullIfEmpty(schema);
    }

    @Nullable
    OffsetDateTime getCreated() {
        return created;
    }

    void setCreated(@Nullable OffsetDateTime created) {
        this.created = created;
    }

    @NotNull
    List<QueryLibraryParameter> getParameters() {
        return parameters;
    }

    /**
     * The definition of a parameter (names compare without case, as the SQL parser's variable names), or null.
     */
    @Nullable
    QueryLibraryParameter findParameter(@NotNull String parameterName) {
        for (QueryLibraryParameter parameter : parameters) {
            if (parameter.getName().equalsIgnoreCase(parameterName)) {
                return parameter;
            }
        }
        return null;
    }

    /**
     * The schedule definition ({@code @schedule}), or null. Whether it runs on this computer is local state
     * ({@link QueryLibraryState#isScheduleActive}).
     */
    @Nullable
    QueryLibrarySchedule getSchedule() {
        return schedule;
    }

    void setSchedule(@Nullable QueryLibrarySchedule schedule) {
        this.schedule = schedule;
    }

    /**
     * Header lines this version does not know, written back unchanged.
     */
    @NotNull
    List<String> getExtraHeader() {
        return extraHeader;
    }

    /**
     * Whether the file had a Chromite header (a plain {@code .sql} file from another tool has none).
     */
    boolean hasHeader() {
        return hasHeader;
    }

    void setHasHeader(boolean hasHeader) {
        this.hasHeader = hasHeader;
    }

    @Override
    public String toString() {
        return name;
    }
}
