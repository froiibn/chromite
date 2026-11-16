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

import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * Controller factory. A driver registers it as an adapter of its routine class ({@code org.eclipse.core.runtime.adapters}),
 * so {@code GeneralUtils.adapt(routine, DBGControllerFactory.class)} tells whether a routine can be debugged.
 *
 * 설계: SWT 제품은 연결({@code DBPDataSourceContainer})의 어댑터로 등록하고 루틴은 실행 구성에 넣었다. Swing에는 실행 구성이
 * 없고 "Debug…"는 루틴 노드에서 시작하므로 루틴 클래스의 어댑터로 바꿨다. 그래서 디버거가 있는 드라이버의 루틴에서만 명령이 켜지고,
 * 드라이버 클래스는 그 드라이버의 루틴을 어댑트할 때만 읽힌다.
 *
 * 시작 대화상자는 루틴을 열 때 이미 이 팩터리를 들고 있으므로, 다른 세션의 호출에 붙는 방식(attach)을 지원하는지도 여기서 알린다
 * ({@link #supportsAttach}). Oracle {@code DBMS_DEBUG}는 대상 세션이 스스로 디버그 ID를 만들어야 해서 지원하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 연결 대신 루틴으로 컨트롤러를 만듦 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 supportsAttach 추가(Oracle 디버거)
 */
public interface DBGControllerFactory {

    /**
     * True if the debugger can wait for another session's call ({@link DBGConstants#ATTACH_KIND_GLOBAL}); the default.
     */
    default boolean supportsAttach() {
        return true;
    }

    /**
     * @param routine       the routine to debug
     * @param configuration attach kind, arguments ({@link DBGConstants})
     */
    @NotNull
    DBGController createController(@NotNull DBSObject routine, @NotNull Map<String, Object> configuration) throws DBGException;

}
