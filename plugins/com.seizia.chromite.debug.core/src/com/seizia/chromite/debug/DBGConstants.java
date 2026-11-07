/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
 * Keys of a debug configuration ({@link DBGController#getDebugConfiguration()}) that every debugger UI fills in.
 *
 * 설계: SWT 제품은 이 값을 Eclipse 실행 구성(launch configuration)과 마커 속성 이름으로 두었다. Swing 디버거는 실행 구성이 없고
 * 시작 대화상자가 한 번 쓰는 맵만 만드므로 DB와 무관한 키만 남겼다. DB별 키는 각 구현(PostgreSQL {@code PostgreDebugConstants})에 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 실행 구성·마커 상수를 빼고 시작 방식·대상 세션·인수 키를 둠
 *   (docs/chromite/procedure-debugger.md)
 */
public final class DBGConstants {

    /**
     * How the debugger reaches the code: {@link #ATTACH_KIND_LOCAL} or {@link #ATTACH_KIND_GLOBAL}.
     */
    public static final String ATTR_ATTACH_KIND = "attachKind"; //$NON-NLS-1$
    /**
     * Attach mode: the server process id whose call is debugged (empty or absent: any session).
     */
    public static final String ATTR_ATTACH_PROCESS = "attachProcess"; //$NON-NLS-1$
    /**
     * Local mode: the argument values ({@code List<String>} of SQL expressions, in the order of the input parameters).
     */
    public static final String ATTR_PARAMETERS = "parameters"; //$NON-NLS-1$

    /**
     * The debugger runs the routine itself in a connection of its own.
     */
    public static final String ATTACH_KIND_LOCAL = "LOCAL"; //$NON-NLS-1$
    /**
     * The debugger waits until another session calls the routine (a global breakpoint).
     */
    public static final String ATTACH_KIND_GLOBAL = "GLOBAL"; //$NON-NLS-1$

    private DBGConstants() {
    }
}
