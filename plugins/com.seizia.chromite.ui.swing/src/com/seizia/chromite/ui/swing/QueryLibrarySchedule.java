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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Library 쿼리의 예약 실행 정의: 언제(간격, 매일, 평일), 어느 서버에서, 어떤 매개변수 값으로, 어떤 조건이면 알릴지, 예약 사용 여부.
 *
 * 설계:
 * <ul>
 *     <li>정의는 쿼리 파일 머리말의 {@code -- @schedule} 블록이다({@link QueryLibraryFile}). 공유하면 정의도 함께 간다. 이 PC에서 실제로 돌릴지는
 *     파일이 아니라 로컬 설정이다({@link QueryLibraryState#isScheduleActive}): 공유 쿼리가 동료 PC마다 저절로 돌지 않게 하기 위해서다.</li>
 *     <li>시각 형태는 세 가지(N분/N시간 간격, 매일 HH:mm, 평일 HH:mm)만 둔다. cron 문법 없이 간단한 화면으로 정하기 위해서다.</li>
 *     <li>다음 실행 시각({@link #nextRun})은 지금 이후의 첫 시각이다. 꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.</li>
 *     <li>조건({@link Condition}): 결과 행 수나 첫 행 첫 열 값과 숫자(또는 글자)를 비교한다. 맞으면 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 */
final class QueryLibrarySchedule {

    /**
     * When the query runs.
     */
    enum Kind {
        INTERVAL, DAILY, WEEKDAYS
    }

    /**
     * A connection the scheduled query runs on: data source id and name (found by name on another computer).
     */
    record Target(@Nullable String id, @NotNull String name) {
    }

    private static final Pattern CONDITION_PATTERN = Pattern.compile("^(rows|first)\\s*(>=|<=|!=|>|<|=)\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("^(\\d+)\\s*([mh])$", Pattern.CASE_INSENSITIVE);

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
            return (isRows() ? "row count " : "first value ") + operator + " " + value;
        }
    }

    private Kind kind = Kind.INTERVAL;
    // INTERVAL
    private int intervalMinutes = 60;
    // DAILY, WEEKDAYS
    private LocalTime time = LocalTime.of(8, 0);
    private final List<Target> targets = new ArrayList<>();
    private final Map<String, String> parameterValues = new LinkedHashMap<>();
    @Nullable
    private Condition condition;
    private boolean enabled = true;

    @NotNull
    QueryLibrarySchedule copy() {
        QueryLibrarySchedule copy = new QueryLibrarySchedule();
        copy.kind = kind;
        copy.intervalMinutes = intervalMinutes;
        copy.time = time;
        copy.targets.addAll(targets);
        copy.parameterValues.putAll(parameterValues);
        copy.condition = condition;
        copy.enabled = enabled;
        return copy;
    }

    @NotNull
    Kind getKind() {
        return kind;
    }

    void setKind(@NotNull Kind kind) {
        this.kind = kind;
    }

    int getIntervalMinutes() {
        return intervalMinutes;
    }

    void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = Math.max(1, intervalMinutes);
    }

    @NotNull
    LocalTime getTime() {
        return time;
    }

    void setTime(@NotNull LocalTime time) {
        this.time = time.withSecond(0).withNano(0);
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

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The first run time after {@code after}. Interval runs follow the last run ({@code lastRun + n * interval}) so
     * that a restart keeps the rhythm; without a last run the first run is one interval from now.
     */
    @NotNull
    LocalDateTime nextRun(@NotNull LocalDateTime after, @Nullable LocalDateTime lastRun) {
        switch (kind) {
            case INTERVAL -> {
                if (lastRun != null && !lastRun.isAfter(after)) {
                    long minutes = java.time.Duration.between(lastRun, after).toMinutes();
                    long steps = minutes / intervalMinutes + 1;
                    return lastRun.plusMinutes(steps * intervalMinutes);
                }
                return after.plusMinutes(intervalMinutes).withSecond(0).withNano(0);
            }
            case DAILY -> {
                LocalDateTime next = after.toLocalDate().atTime(time);
                return next.isAfter(after) ? next : next.plusDays(1);
            }
            default -> {
                LocalDateTime next = after.toLocalDate().atTime(time);
                if (!next.isAfter(after)) {
                    next = next.plusDays(1);
                }
                while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    next = next.plusDays(1);
                }
                return next;
            }
        }
    }

    /**
     * Text for people: "every 5 minutes", "daily at 08:30", "weekdays at 08:30".
     */
    @NotNull
    String describe() {
        return switch (kind) {
            case INTERVAL -> intervalMinutes % 60 == 0
                ? "every " + (intervalMinutes == 60 ? "hour" : intervalMinutes / 60 + " hours")
                : "every " + (intervalMinutes == 1 ? "minute" : intervalMinutes + " minutes");
            case DAILY -> "daily at " + time;
            case WEEKDAYS -> "weekdays at " + time;
        };
    }

    /**
     * Value of the file's {@code every:}/{@code daily:}/{@code weekdays:} key.
     */
    @NotNull
    String formatWhen() {
        return switch (kind) {
            case INTERVAL -> intervalMinutes % 60 == 0 ? intervalMinutes / 60 + "h" : intervalMinutes + "m"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> time.toString();
        };
    }

    /**
     * Reads one {@code key: value} line of the {@code @schedule} block.
     *
     * @return false for a key this version does not know
     */
    boolean readKey(@NotNull String key, @NotNull String value) {
        switch (key) {
            case "every" -> { //$NON-NLS-1$
                Matcher matcher = INTERVAL_PATTERN.matcher(value.trim());
                if (matcher.matches()) {
                    int amount = Integer.parseInt(matcher.group(1));
                    kind = Kind.INTERVAL;
                    setIntervalMinutes(matcher.group(2).equalsIgnoreCase("h") ? amount * 60 : amount); //$NON-NLS-1$
                }
            }
            case "daily", "weekdays" -> { //$NON-NLS-1$ //$NON-NLS-2$
                try {
                    setTime(LocalTime.parse(value.trim()));
                    kind = key.equals("daily") ? Kind.DAILY : Kind.WEEKDAYS; //$NON-NLS-1$
                } catch (DateTimeParseException e) {
                    // Keep the default
                }
            }
            case "target" -> { //$NON-NLS-1$
                int separator = value.indexOf('|');
                String id = separator >= 0 ? value.substring(0, separator).trim() : null;
                String name = separator >= 0 ? value.substring(separator + 1).trim() : value.trim();
                targets.add(new Target(CommonUtils.nullIfEmpty(id), name));
            }
            case "condition" -> condition = Condition.parse(value); //$NON-NLS-1$
            case "enabled" -> enabled = CommonUtils.toBoolean(value); //$NON-NLS-1$
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
