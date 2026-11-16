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
package com.seizia.chromite.debug;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * How the debugged call ended (local mode): its result rows as text, or the error it ended with.
 *
 * 설계: 디버거 화면이 끝에 결과와 메시지를 보이도록 모델이 실행 결과를 글자로만 넘긴다. 결과는 루틴 하나의 반환값(또는 집합 반환 함수의
 * 행 몇 개)이라 결과 격자 대신 작은 표면 충분하고, 값 처리기를 거치지 않아도 서버가 보낸 글자 그대로 보여 줄 수 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param columns      result column names (empty: no result set, e.g. a procedure without output)
 * @param rows         result rows, values as the server's text (null for SQL NULL)
 * @param errorMessage the error the call ended with (null: it succeeded)
 * @param canceled     the call was stopped by the debugger (Stop)
 */
public record DBGTargetResult(
    @NotNull List<String> columns,
    @NotNull List<List<String>> rows,
    @Nullable String errorMessage,
    boolean canceled
) {
}
