/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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

/**
 * A variable or argument visible in a stack frame.
 *
 * 설계: SWT 버전의 {@code getType()}은 늘 TEXT를 돌려주는 열거형(DBGVariableType)이라 Variables 창에 보일 수 없었다. 서버가 아는
 * 형식 이름({@link #getTypeName})으로 바꾸고 열거형은 지웠다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): getType()·DBGVariableType 대신 getTypeName(), isArgument() 추가
 *   (docs/chromite/procedure-debugger.md)
 */
public interface DBGVariable<T> {

    T getVal();

    String getName();

    /**
     * Data type name as the server formats it (for example {@code integer}, {@code character varying}).
     */
    String getTypeName();

    /**
     * True for an argument of the routine, false for a local variable.
     */
    boolean isArgument();

    int getLineNumber();

}
