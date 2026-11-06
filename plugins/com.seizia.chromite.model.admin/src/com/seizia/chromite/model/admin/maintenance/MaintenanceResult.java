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

import com.seizia.chromite.model.admin.AdminGrid;
import org.jkiss.code.NotNull;

import java.util.List;

/**
 * 유지 관리 문장을 실행한 결과: 서버가 돌려준 표(MySQL의 Table/Op/Msg_type/Msg_text, Oracle 컴파일 오류), 메시지
 * (PostgreSQL VACUUM VERBOSE의 INFO, SQL Server DBCC의 정보 메시지, 문장마다 걸린 시간, 오류), 끝난 상태.
 *
 * @param grids         result sets returned by the statements
 * @param messages      server messages and progress, in order
 * @param status        how it ended
 * @param elapsedMillis time of the whole run
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceResult(@NotNull List<AdminGrid> grids, @NotNull List<Message> messages, @NotNull Status status, long elapsedMillis) {

    public enum Status {
        SUCCEEDED,
        /** A statement failed; the following ones were not run */
        FAILED,
        CANCELED
    }

    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    /**
     * A line of the run log.
     *
     * @param level how it is shown
     * @param text  the text (server messages as the server wrote them)
     */
    public record Message(@NotNull Level level, @NotNull String text) {
    }
}
