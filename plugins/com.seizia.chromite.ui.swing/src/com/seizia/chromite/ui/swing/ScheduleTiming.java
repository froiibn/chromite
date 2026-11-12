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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a scheduled run happens: every N minutes/hours, daily at hh:mm, weekdays (Monday-Friday) at hh:mm; and whether the
 * schedule is on. Shared by the scheduled library queries ({@link QueryLibrarySchedule}) and the scheduled saved tasks
 * ({@link TaskScheduler}), which both run on the one {@link RunScheduler}.
 *
 * 설계:
 * <ul>
 *     <li>시각 형태는 세 가지만 둔다. cron 문법 없이 간단한 화면({@link ScheduleWhenPanel})으로 정하기 위해서다.</li>
 *     <li>시각은 이 PC의 지역 시각({@link LocalDateTime})이다. 서머타임이 바뀌는 날: 앞으로 당기면 없는 시각(예: 02:30)은 지나간 것으로
 *     보아 시계가 넘어간 뒤 첫 확인에서 한 번 돈다. 뒤로 물리면 같은 시각이 두 번 오지만 한 번 돈 뒤 다음 시각은 다음 날이라 한 번만 돈다.
 *     간격 예약은 벽시계로 더하므로 물리는 날 한 번 최대 1시간 늦어지고, 당기는 날 한 번 일찍 돈다(한국은 서머타임이 없음).</li>
 *     <li>다음 실행 시각({@link #nextRun})은 지금 이후의 첫 시각이다. 꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.
 *     {@link #previousRun}은 그렇게 놓친 마지막 시각을 화면에 보이기 위한 것이다.</li>
 *     <li>저장 형식은 {@code every: 30m|2h}, {@code daily: hh:mm}, {@code weekdays: hh:mm}, {@code enabled: true|false} 키다
 *     (쿼리 파일의 {@code -- @schedule} 블록과 저장된 작업의 {@code schedule} 객체가 같은 키).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@link QueryLibrarySchedule}의 시각 부분을 떼어 작업 예약과 함께 씀(앱 변경 #104,
 *   docs/chromite/task-scheduling.md "하나의 스케줄러")
 */
class ScheduleTiming {

    /**
     * When the run happens.
     */
    enum Kind {
        INTERVAL, DAILY, WEEKDAYS
    }

    static final String KEY_EVERY = "every"; //$NON-NLS-1$
    static final String KEY_DAILY = "daily"; //$NON-NLS-1$
    static final String KEY_WEEKDAYS = "weekdays"; //$NON-NLS-1$
    static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$

    private static final Pattern INTERVAL_PATTERN = Pattern.compile("^(\\d+)\\s*([mh])$", Pattern.CASE_INSENSITIVE);

    private Kind kind = Kind.INTERVAL;
    // INTERVAL
    private int intervalMinutes = 60;
    // DAILY, WEEKDAYS
    private LocalTime time = LocalTime.of(8, 0);
    private boolean enabled = true;

    /**
     * Copies the timing into another schedule (a copy of a subclass keeps its own parts).
     */
    void copyTimingTo(@NotNull ScheduleTiming target) {
        target.kind = kind;
        target.intervalMinutes = intervalMinutes;
        target.time = time;
        target.enabled = enabled;
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

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Whether another schedule runs at the same times (the scheduler keeps the next run time of an entry whose times did not change).
     */
    boolean isSameTiming(@Nullable ScheduleTiming other) {
        return other != null && other.kind == kind && other.formatWhen().equals(formatWhen());
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
                    long minutes = Duration.between(lastRun, after).toMinutes();
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
                while (isWeekend(next)) {
                    next = next.plusDays(1);
                }
                return next;
            }
        }
    }

    /**
     * The last run time at or before {@code before} (the slot a closed Chromite missed), or null for an interval schedule
     * without a last run (its rhythm starts when it is turned on).
     */
    @Nullable
    LocalDateTime previousRun(@NotNull LocalDateTime before, @Nullable LocalDateTime lastRun) {
        switch (kind) {
            case INTERVAL -> {
                if (lastRun == null || lastRun.isAfter(before)) {
                    return null;
                }
                long steps = Duration.between(lastRun, before).toMinutes() / intervalMinutes;
                return lastRun.plusMinutes(steps * intervalMinutes);
            }
            case DAILY -> {
                LocalDateTime previous = before.toLocalDate().atTime(time);
                return previous.isAfter(before) ? previous.minusDays(1) : previous;
            }
            default -> {
                LocalDateTime previous = before.toLocalDate().atTime(time);
                if (previous.isAfter(before)) {
                    previous = previous.minusDays(1);
                }
                while (isWeekend(previous)) {
                    previous = previous.minusDays(1);
                }
                return previous;
            }
        }
    }

    private static boolean isWeekend(@NotNull LocalDateTime time) {
        return time.getDayOfWeek() == DayOfWeek.SATURDAY || time.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /**
     * Text for people: "every 5 minutes", "daily at 08:30", "weekdays at 08:30".
     */
    @NotNull
    String describe() {
        return switch (kind) {
            case INTERVAL -> intervalMinutes % 60 == 0
                ? (intervalMinutes == 60 ? ToolsMessages.schedule_every_hour : NLS.bind(ToolsMessages.schedule_every_hours, intervalMinutes / 60))
                : (intervalMinutes == 1 ? ToolsMessages.schedule_every_minute : NLS.bind(ToolsMessages.schedule_every_minutes, intervalMinutes));
            case DAILY -> NLS.bind(ToolsMessages.schedule_daily_at, time);
            case WEEKDAYS -> NLS.bind(ToolsMessages.schedule_weekdays_at, time);
        };
    }

    /**
     * The key of the time ({@code every}, {@code daily}, {@code weekdays}).
     */
    @NotNull
    String getWhenKey() {
        return switch (kind) {
            case INTERVAL -> KEY_EVERY;
            case DAILY -> KEY_DAILY;
            case WEEKDAYS -> KEY_WEEKDAYS;
        };
    }

    /**
     * Value of the {@code every:}/{@code daily:}/{@code weekdays:} key.
     */
    @NotNull
    String formatWhen() {
        return switch (kind) {
            case INTERVAL -> intervalMinutes % 60 == 0 ? intervalMinutes / 60 + "h" : intervalMinutes + "m"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> time.toString();
        };
    }

    /**
     * Reads one {@code key: value} of the schedule: the time and whether it is on.
     *
     * @return false for a key that is not about the timing
     */
    boolean readKey(@NotNull String key, @NotNull String value) {
        switch (key) {
            case KEY_EVERY -> {
                Matcher matcher = INTERVAL_PATTERN.matcher(value.trim());
                if (matcher.matches()) {
                    int amount = Integer.parseInt(matcher.group(1));
                    kind = Kind.INTERVAL;
                    setIntervalMinutes(matcher.group(2).equalsIgnoreCase("h") ? amount * 60 : amount); //$NON-NLS-1$
                }
            }
            case KEY_DAILY, KEY_WEEKDAYS -> {
                try {
                    setTime(LocalTime.parse(value.trim()));
                    kind = key.equals(KEY_DAILY) ? Kind.DAILY : Kind.WEEKDAYS;
                } catch (DateTimeParseException e) {
                    // Keep the default
                }
            }
            case KEY_ENABLED -> enabled = CommonUtils.toBoolean(value);
            default -> {
                return false;
            }
        }
        return true;
    }
}
