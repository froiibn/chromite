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
package com.seizia.chromite.ext.oracle.debug.internal;

import com.seizia.chromite.debug.DBGStackFrame;
import org.jkiss.code.NotNull;

/**
 * A frame of {@code DBMS_DEBUG.PRINT_BACKTRACE}.
 *
 * 설계: 프레임 번호({@link #getLevel})는 백트레이스 표의 번호다(1 = 가장 오래된 프레임, 디버거가 실행한 익명 블록). {@code GET_VALUE}·
 * {@code SET_VALUE}가 같은 번호를 쓴다(맨 위 프레임은 0으로도 부를 수 있다). 익명 블록 프레임은 원본이 없어 세션이 목록에서 뺀다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param name       unit and subprogram, for example {@code HR.EMP_PKG.RAISE_SALARY}
 * @param level      backtrace frame number
 * @param lineNumber line of the unit's source
 * @param unit       the frame's unit
 * @param top        the innermost frame (where the target is stopped)
 */
record OracleDebugStackFrame(@NotNull String name, int level, int lineNumber, @NotNull OracleDebugUnit unit, boolean top)
    implements DBGStackFrame {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public Object getSourceIdentifier() {
        return unit;
    }

    /**
     * Frame number for {@code GET_VALUE} and {@code SET_VALUE}: 0 for the innermost frame.
     */
    int valueFrame() {
        return top ? 0 : level;
    }
}
