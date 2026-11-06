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
package com.seizia.chromite.model.admin.maintenance;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * 유지 관리 동작의 옵션 하나(예: VACUUM의 FULL, 표본 비율, CHECK TABLE의 검사 방식).
 *
 * 설계: 화면은 종류만 보고 체크 상자·콤보를 그린다. 그래서 DB별 방언은 옵션 목록만 정하고 대화상자는 DB를 모른다.
 * 서버 버전이나 에디션이 허용하지 않는 옵션은 숨기지 않고 끈 채 이유를 도구 설명에 보인다({@link #enabled}).
 *
 * @param id           key of the value in the option map ({@link MaintenanceDialect#buildCommand})
 * @param label        label (localized)
 * @param kind         how it is edited
 * @param choices      values of a {@link Kind#CHOICE}, empty otherwise
 * @param defaultValue {@link Boolean} for a flag, the choice value for a choice
 * @param toolTip      tool tip (localized), or null
 * @param enabled      false when the server does not allow it (the tool tip says why)
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceOption(
    @NotNull String id,
    @NotNull String label,
    @NotNull Kind kind,
    @NotNull List<Choice> choices,
    @NotNull Object defaultValue,
    @Nullable String toolTip,
    boolean enabled
) {

    public enum Kind {
        /** A check box; value {@link Boolean} */
        FLAG,
        /** A combo; value is the {@link Choice#value()} (sample sizes are choices too: a few common rates) */
        CHOICE
    }

    /**
     * A value of a choice.
     *
     * @param value value put into the command
     * @param label label (localized)
     */
    public record Choice(@NotNull String value, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    @NotNull
    public static MaintenanceOption flag(@NotNull String id, @NotNull String label, boolean defaultValue, @Nullable String toolTip) {
        return new MaintenanceOption(id, label, Kind.FLAG, List.of(), defaultValue, toolTip, true);
    }

    /**
     * A flag the server does not allow: shown off and disabled.
     */
    @NotNull
    public static MaintenanceOption disabledFlag(@NotNull String id, @NotNull String label, @NotNull String reason) {
        return new MaintenanceOption(id, label, Kind.FLAG, List.of(), false, reason, false);
    }

    @NotNull
    public static MaintenanceOption choice(@NotNull String id, @NotNull String label, @NotNull List<Choice> choices,
                                           @NotNull String defaultValue, @Nullable String toolTip) {
        return new MaintenanceOption(id, label, Kind.CHOICE, choices, defaultValue, toolTip, true);
    }
}
