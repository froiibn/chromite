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

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * Debugger of one routine with one configuration. This interface is expected to be used in synch manner (from a job).
 *
 * 설계: SWT 제품에서는 Eclipse 디버그 모델({@code DatabaseDebugTarget})이 이 컨트롤러를 감쌌고 중단점은 Eclipse 마커였다.
 * Swing 디버거는 이 인터페이스를 직접 쓰므로, 세션을 열기 전에 중단점을 놓을 수 있게 루틴 원본({@link #readSource})과
 * 그 원본의 식별자({@link #getSourceIdentifier})를 컨트롤러가 준다. 중단점 설명은 마커 속성 맵 대신 원본 식별자와 줄 번호로 만든다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): readSource·getSourceIdentifier 추가, describeBreakpoint를
 *   마커 속성 대신 원본·줄로 (docs/chromite/procedure-debugger.md)
 */
public interface DBGController {

    DBPDataSourceContainer getDataSourceContainer();

    Map<String, Object> getDebugConfiguration();

    /**
     * Connects (if needed) and starts the debug session as configured: runs the routine (local mode) or waits for
     * another session to call it (attach mode).
     */
    DBGSession openSession(DBRProgressMonitor monitor) throws DBGException;

    /**
     * The source of the debugged routine as the server's debugger numbers its lines ({@link DBGStackFrame#getLineNumber}).
     */
    @NotNull
    String readSource(@NotNull DBRProgressMonitor monitor) throws DBGException;

    /**
     * Identifier of the debugged routine, as {@link DBGStackFrame#getSourceIdentifier} names the routine of a frame.
     */
    @NotNull
    Object getSourceIdentifier();

    /**
     * A line breakpoint in a routine.
     *
     * @param sourceIdentifier routine ({@link #getSourceIdentifier}, {@link DBGStackFrame#getSourceIdentifier})
     * @param lineNumber       line as {@link #readSource} numbers it
     */
    @NotNull
    DBGBreakpointDescriptor describeBreakpoint(@NotNull Object sourceIdentifier, int lineNumber);

    /*
     * Events
     */
    void registerEventHandler(DBGEventHandler eventHandler);

    void unregisterEventHandler(DBGEventHandler eventHandler);

    void dispose();

}
