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
package com.seizia.chromite.ext.altibase.model;

import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Job and queue statements of Altibase-family servers (Altibase, ShardSphere): create, change, enable / disable and drop
 * a job ({@code SYSTEM_.SYS_JOBS_}); create, compact and drop a queue.
 *
 * 설계: 두 서버(Altibase 7.3, ShardSphere 5.1.9)에서 직접 실행해 확인한 모양 가운데 둘 다 받는 것만 쓴다(docs/chromite/altibase.md
 * "작업 만들기·바꾸기·삭제", "큐"). 그래서 연결마다 고를 것이 없다. 객체 관리자({@code AltibaseJobManager}, {@code AltibaseQueueManager})와
 * Object Explorer 유지 관리 명령({@code ObjectMaintenanceActions})이 이 함수를 써서 문장이 한 곳에서만 만들어진다. 화면은 값만 넘긴다.
 * <ul>
 *     <li>CREATE JOB은 START가 늘 있다: Altibase는 START 없는 CREATE JOB을 구문 오류로 거절하고 ShardSphere는 받는다. END·INTERVAL은 빼도
 *     둘 다 받는다(INTERVAL이 없으면 카탈로그에 주기 0, 단위 없음). ENABLE과 COMMENT는 이 순서로 끝에 쓴다(COMMENT 뒤의 ENABLE·DISABLE은
 *     구문 오류). DISABLE은 쓰지 않는다: 새 작업은 꺼진 채 만들어진다.</li>
 *     <li>주기 단위는 두 서버가 다 받는 YEAR, MONTH, DAY, HOUR, MINUTE이다(카탈로그 코드 YY, MM, DD, HH, MI). SECOND는 ShardSphere만
 *     받고(코드 SS) WEEK는 둘 다 구문 오류다.</li>
 *     <li>ALTER JOB … SET은 한 문장에 절 하나만 받는다(둘 이상은 두 서버 모두 구문 오류). 그래서 바뀐 절마다 문장 하나다:
 *     EXEC, START, END(없애기는 {@code END NULL}), INTERVAL, COMMENT(없애기는 빈 문자열), ENABLE, DISABLE. {@code INTERVAL NULL}과
 *     {@code COMMENT NULL}은 구문 오류이고, {@code START NULL}은 Altibase가 거절한다(NOT NULL 열).</li>
 *     <li>시각은 {@code TO_DATE('yyyy-MM-dd HH:mm:ss', 'YYYY-MM-DD HH24:MI:SS')}로 쓴다(서버의 날짜 형식 설정과 관계없음).</li>
 *     <li>실행할 프로시저 호출은 사용자가 쓴 글 그대로다({@code SYS.PROC}, {@code PROC(1)}). 서버는 프로시저가 있는지 확인하지 않는다
 *     (없는 프로시저로도 만들어지고 바뀐다, 두 서버 모두).</li>
 *     <li>큐는 {@code CREATE QUEUE q (size)}(1~32000바이트, 32001은 201454), {@code ALTER QUEUE q COMPACT}, {@code DROP QUEUE q}다. 이름은
 *     스키마를 붙여 쓴다(두 서버 모두 받음). {@code DROP QUEUE … CASCADE}와 {@code DROP TABLE}(큐에서 201252)은 쓰지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성: 작업 만들기·바꾸기·켜기·끄기·삭제, 큐 만들기·압축·삭제(앱 변경 #154, docs/chromite/altibase.md)
 */
public final class AltibaseJobDDL {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$
    private static final String DATE_TIME_FORMAT = "YYYY-MM-DD HH24:MI:SS"; //$NON-NLS-1$

    /**
     * The interval units both servers take: the statement keyword and the catalog code ({@code SYS_JOBS_.INTERVAL_TYPE}).
     */
    public enum Unit {
        YEAR("YY"), //$NON-NLS-1$
        MONTH("MM"), //$NON-NLS-1$
        DAY("DD"), //$NON-NLS-1$
        HOUR("HH"), //$NON-NLS-1$
        MINUTE("MI"); //$NON-NLS-1$

        private final String code;

        Unit(@NotNull String code) {
            this.code = code;
        }

        @NotNull
        public String getCode() {
            return code;
        }

        /**
         * The unit of a catalog code, or null for no or another code.
         */
        @Nullable
        public static Unit fromCode(@Nullable String code) {
            for (Unit unit : values()) {
                if (unit.code.equals(code)) {
                    return unit;
                }
            }
            return null;
        }
    }

    /**
     * A repeat interval of a job.
     */
    public record Interval(int amount, @NotNull Unit unit) {
    }

    /**
     * A new job.
     *
     * @param exec     the procedure call ({@code [user.]procedure[(args)]})
     * @param end      null: no end
     * @param interval null: no interval (the job runs once)
     * @param comment  null or empty: no comment
     */
    public record Definition(
        @NotNull String exec,
        @NotNull LocalDateTime start,
        @Nullable LocalDateTime end,
        @Nullable Interval interval,
        boolean enabled,
        @Nullable String comment
    ) {
    }

    /**
     * The change of a job's settings; null fields are unchanged.
     *
     * @param removeEnd true: no end ({@code END NULL}); {@code end} is then null
     * @param comment   the new comment, empty to remove it
     */
    public record Change(
        @Nullable String exec,
        @Nullable LocalDateTime start,
        @Nullable LocalDateTime end,
        boolean removeEnd,
        @Nullable Interval interval,
        @Nullable String comment
    ) {
        public boolean isEmpty() {
            return exec == null && start == null && end == null && !removeEnd && interval == null && comment == null;
        }
    }

    private AltibaseJobDDL() {
    }

    /**
     * {@code CREATE JOB j EXEC p START s [END e] [INTERVAL n unit] [ENABLE] [COMMENT 'c']}.
     */
    @NotNull
    public static String createJob(@NotNull AltibaseJob job, @NotNull Definition definition) {
        StringBuilder sql = new StringBuilder("CREATE JOB ").append(DBUtils.getQuotedIdentifier(job))
            .append(" EXEC ").append(definition.exec())
            .append(" START ").append(toDate(definition.start()));
        if (definition.end() != null) {
            sql.append(" END ").append(toDate(definition.end()));
        }
        if (definition.interval() != null) {
            sql.append(" INTERVAL ").append(intervalClause(definition.interval()));
        }
        if (definition.enabled()) {
            sql.append(" ENABLE");
        }
        if (definition.comment() != null && !definition.comment().isEmpty()) {
            sql.append(" COMMENT ").append(SQLUtils.quoteString(job.getDataSource(), definition.comment()));
        }
        return sql.toString();
    }

    /**
     * One {@code ALTER JOB j SET …} per changed setting (the servers take one clause per statement). Empty if nothing changes.
     */
    @NotNull
    public static List<String> alterJob(@NotNull AltibaseJob job, @NotNull Change change) {
        String prefix = "ALTER JOB " + DBUtils.getQuotedIdentifier(job) + " SET ";
        List<String> statements = new ArrayList<>();
        if (change.exec() != null) {
            statements.add(prefix + "EXEC " + change.exec());
        }
        if (change.start() != null) {
            statements.add(prefix + "START " + toDate(change.start()));
        }
        if (change.removeEnd()) {
            statements.add(prefix + "END NULL");
        } else if (change.end() != null) {
            statements.add(prefix + "END " + toDate(change.end()));
        }
        if (change.interval() != null) {
            statements.add(prefix + "INTERVAL " + intervalClause(change.interval()));
        }
        if (change.comment() != null) {
            statements.add(prefix + "COMMENT " + SQLUtils.quoteString(job.getDataSource(), change.comment()));
        }
        return statements;
    }

    /**
     * {@code ALTER JOB j SET ENABLE | DISABLE}.
     */
    @NotNull
    public static String setJobEnabled(@NotNull AltibaseJob job, boolean enabled) {
        return "ALTER JOB " + DBUtils.getQuotedIdentifier(job) + " SET " + (enabled ? "ENABLE" : "DISABLE");
    }

    /**
     * {@code DROP JOB j}.
     */
    @NotNull
    public static String dropJob(@NotNull AltibaseJob job) {
        return "DROP JOB " + DBUtils.getQuotedIdentifier(job);
    }

    /**
     * {@code CREATE QUEUE s.q (size)}.
     */
    @NotNull
    public static String createQueue(@NotNull String qualifiedName, int messageSize) {
        return "CREATE QUEUE " + qualifiedName + " (" + messageSize + ")";
    }

    /**
     * {@code ALTER QUEUE s.q COMPACT}.
     */
    @NotNull
    public static String compactQueue(@NotNull String qualifiedName) {
        return "ALTER QUEUE " + qualifiedName + " COMPACT";
    }

    /**
     * {@code DROP QUEUE s.q} (also drops the queue's index and its internal sequence).
     */
    @NotNull
    public static String dropQueue(@NotNull String qualifiedName) {
        return "DROP QUEUE " + qualifiedName;
    }

    @NotNull
    private static String toDate(@NotNull LocalDateTime time) {
        return "TO_DATE('" + DATE_TIME.format(time) + "', '" + DATE_TIME_FORMAT + "')";
    }

    @NotNull
    private static String intervalClause(@NotNull Interval interval) {
        return interval.amount() + " " + interval.unit().name();
    }
}
