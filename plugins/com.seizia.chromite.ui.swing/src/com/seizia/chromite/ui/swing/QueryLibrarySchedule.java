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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Library 쿼리의 예약 실행 정의: 언제(간격, 매일, 평일)와 예약 사용 여부({@link ScheduleTiming}), 어느 서버에서, 어떤 매개변수 값으로,
 * 어떤 조건이면 알릴지.
 *
 * 설계:
 * <ul>
 *     <li>정의는 쿼리 파일 머리말의 {@code -- @schedule} 블록이다({@link QueryLibraryFile}). 공유하면 정의도 함께 간다. 이 PC에서 실제로 돌릴지는
 *     파일이 아니라 로컬 설정이다({@link QueryLibraryState#isScheduleActive}): 공유 쿼리가 동료 PC마다 저절로 돌지 않게 하기 위해서다.</li>
 *     <li>시각(N분/N시간 간격, 매일 HH:mm, 평일 HH:mm)과 사용 여부, 다음 실행 시각은 저장된 작업의 예약과 같은 {@link ScheduleTiming}이다
 *     (스케줄러 하나, {@link RunScheduler}). 꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.</li>
 *     <li>조건({@link Condition}): 결과 행 수나 첫 행 첫 열 값과 숫자(또는 글자)를 비교한다. 맞으면 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-12 시각 부분(종류, 간격, 시각, 사용 여부, 다음 시각, 문구, 파일 키)을 {@link ScheduleTiming}으로 옮겨 작업 예약과 함께 씀
 *   (앱 변경 #104, docs/chromite/task-scheduling.md "하나의 스케줄러")
 */
final class QueryLibrarySchedule extends ScheduleTiming {

    /**
     * A connection the scheduled query runs on: data source id and name (found by name on another computer).
     */
    record Target(@Nullable String id, @NotNull String name) {
    }

    private static final Pattern CONDITION_PATTERN = Pattern.compile("^(rows|first)\\s*(>=|<=|!=|>|<|=)\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * A condition on the result: {@code rows > 0}, {@code rows >= 10}, {@code first > 90} (first column of the first row).
     */
    record Condition(@NotNull String subject, @NotNull String operator, @NotNull String value) {
        @Nullable
        static Condition parse(@Nullable String text) {
            Matcher matcher = CONDITION_PATTERN.matcher(CommonUtils.notEmpty(text).trim());
            return matcher.matches()
                ? new Condition(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2), matcher.group(3).trim()) : null;
        }

        boolean isRows() {
            return "rows".equals(subject); //$NON-NLS-1$
        }

        /**
         * @param rows       rows of the first result set
         * @param firstValue first column of the first row, or null
         */
        boolean matches(long rows, @Nullable String firstValue) {
            if (isRows()) {
                return compare(BigDecimal.valueOf(rows), value);
            }
            if (firstValue == null) {
                return false;
            }
            try {
                return compare(new BigDecimal(firstValue.trim()), value);
            } catch (NumberFormatException e) {
                // Text values compare as text
                int result = firstValue.compareTo(value);
                return test(result);
            }
        }

        private boolean compare(@NotNull BigDecimal actual, @NotNull String expected) {
            try {
                return test(actual.compareTo(new BigDecimal(expected)));
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private boolean test(int comparison) {
            return switch (operator) {
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case "!=" -> comparison != 0;
                default -> comparison == 0;
            };
        }

        @Override
        public String toString() {
            return subject + " " + operator + " " + value;
        }

        /**
         * Text for people: "row count > 0", "first value >= 90".
         */
        @NotNull
        String describe() {
            return NLS.bind(isRows() ? ToolsMessages.schedule_condition_rows : ToolsMessages.schedule_condition_first, operator, value);
        }
    }

    private final List<Target> targets = new ArrayList<>();
    private final Map<String, String> parameterValues = new LinkedHashMap<>();
    @Nullable
    private Condition condition;

    @NotNull
    QueryLibrarySchedule copy() {
        QueryLibrarySchedule copy = new QueryLibrarySchedule();
        copyTimingTo(copy);
        copy.targets.addAll(targets);
        copy.parameterValues.putAll(parameterValues);
        copy.condition = condition;
        return copy;
    }

    @NotNull
    List<Target> getTargets() {
        return targets;
    }

    @NotNull
    Map<String, String> getParameterValues() {
        return parameterValues;
    }

    @Nullable
    Condition getCondition() {
        return condition;
    }

    void setCondition(@Nullable Condition condition) {
        this.condition = condition;
    }

    /**
     * Reads one {@code key: value} line of the {@code @schedule} block: the timing ({@link ScheduleTiming#readKey}), the targets,
     * the parameter values and the condition.
     *
     * @return false for a key this version does not know
     */
    @Override
    boolean readKey(@NotNull String key, @NotNull String value) {
        if (super.readKey(key, value)) {
            return true;
        }
        switch (key) {
            case "target" -> { //$NON-NLS-1$
                int separator = value.indexOf('|');
                String id = separator >= 0 ? value.substring(0, separator).trim() : null;
                String name = separator >= 0 ? value.substring(separator + 1).trim() : value.trim();
                targets.add(new Target(CommonUtils.nullIfEmpty(id), name));
            }
            case "condition" -> condition = Condition.parse(value); //$NON-NLS-1$
            default -> {
                if (key.startsWith("param.")) { //$NON-NLS-1$
                    parameterValues.put(key.substring("param.".length()), value); //$NON-NLS-1$
                } else {
                    return false;
                }
            }
        }
        return true;
    }
}
