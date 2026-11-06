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

/**
 * 공간 사용량 표의 열 하나: 머리글과 값의 종류.
 *
 * 설계: 값은 원래 숫자(바이트, 개수, 백분율)로 두고 화면이 사람이 읽는 단위로 보인다. 그래야 정렬과 "정확한 값 복사"가 숫자로 되고,
 * 도구 설명에 정확한 바이트 수를 보일 수 있다. 화면은 종류만 보고 그리므로 DB별 방언은 열 종류만 고르면 된다.
 *
 * @param label   column header (localized)
 * @param kind    how values are shown and sorted
 * @param toolTip header tool tip (localized), or null
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public record SpaceColumn(@NotNull String label, @NotNull Kind kind, @Nullable String toolTip) {

    /**
     * Value kinds. Values are {@link String} for {@link #TEXT}, {@link Long} for {@link #BYTES}, {@link #BYTES_LIMIT} and
     * {@link #COUNT}, {@link Double} for {@link #PERCENT}; any of them may be null (unknown or not readable).
     */
    public enum Kind {
        TEXT,
        /** A size in bytes */
        BYTES,
        /** A size limit in bytes; {@link #UNLIMITED} means no limit */
        BYTES_LIMIT,
        /** A number of rows, tables, files */
        COUNT,
        /** 0 to 100 */
        PERCENT
    }

    /**
     * A {@link Kind#BYTES_LIMIT} value without a limit.
     */
    public static final long UNLIMITED = -1;

    @NotNull
    public static SpaceColumn text(@NotNull String label) {
        return new SpaceColumn(label, Kind.TEXT, null);
    }

    @NotNull
    public static SpaceColumn bytes(@NotNull String label) {
        return new SpaceColumn(label, Kind.BYTES, null);
    }

    @NotNull
    public static SpaceColumn count(@NotNull String label) {
        return new SpaceColumn(label, Kind.COUNT, null);
    }

    @NotNull
    public static SpaceColumn percent(@NotNull String label) {
        return new SpaceColumn(label, Kind.PERCENT, null);
    }
}
