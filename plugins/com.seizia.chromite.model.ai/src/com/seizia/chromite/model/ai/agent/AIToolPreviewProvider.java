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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.Map;

/**
 * Optional interface of a tool implementation: the exact SQL/DDL or command line a call will run, or the text change it
 * makes in a query window, shown in the confirmation request before a WRITE tool runs.
 *
 * 설계: 확인 대화상자는 미리 보기의 제목과 첫 줄을 미리 보기의 종류({@link Kind})로 고른다. 모델 번들은 화면 문구를 갖지 않으므로 종류만
 * 알리고 문구는 화면이 정한다. 쿼리 창 고치기는 아무것도 실행하지 않으므로 "실행할 내용"이라고 보이면 틀리다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "확인")
 * - 2026-11-08 미리 보기 종류({@link Kind}): 쿼리 창 글 고치기의 차이 (docs/chromite/ai-agent.md "편집기 도구")
 */
public interface AIToolPreviewProvider {

    /**
     * What a preview shows.
     */
    enum Kind {
        /** What the call runs: SQL, DDL or a command line. */
        RUN,
        /** A change of a query window's text (lines starting with "- " are removed, "+ " added); nothing runs. */
        TEXT_CHANGE
    }

    @Nullable
    String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException;

    /**
     * What the preview of the call shows.
     */
    @NotNull
    default Kind getPreviewKind(@NotNull AIToolCallContext context) {
        return Kind.RUN;
    }
}
