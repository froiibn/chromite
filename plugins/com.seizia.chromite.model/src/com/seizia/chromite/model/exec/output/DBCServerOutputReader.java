/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.model.exec.output;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionResult;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Provides ability to read server logs for certain session
 *
 * 설계: 서버 출력 설정({@link #isServerOutputEnabled})은 언제든 바뀌지만, 서버 쪽에서 켜고 끄는 상태(Oracle {@code DBMS_OUTPUT.ENABLE})는
 * 세션마다 있고 세션을 열 때만 맞춰졌다. {@link #syncServerOutput}으로 열린 세션도 다음 문장 전에 설정에 맞춘다. 서버 상태가 없는
 * 리더(경고만 다시 읽는 기본 리더 등)는 아무것도 하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: {@link #syncServerOutput} 추가 (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 */
public interface DBCServerOutputReader extends DBPObject
{
    boolean isServerOutputEnabled();

    /**
     * Brings the server side of the context's session in line with {@link #isServerOutputEnabled()} when the setting
     * changed after the session opened. Called before a statement in the job thread that runs it.
     */
    default void syncServerOutput(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context) throws DBCException {
    }

    /**
     * If async output reading is supported then SQL job will read output during statement execution.
     */
    boolean isAsyncOutputReadSupported();

    /**
     * Reads server output messages.
     * Only @queryResult or @statement can be specified. Non-null statement means async output reading.
     * Output for statement can be requested only if @isAsyncOutputReadSupported returns true.
     */
    void readServerOutput(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @Nullable DBCExecutionResult executionResult,
        @Nullable DBCStatement statement,
        @NotNull DBCOutputWriter output)
        throws DBCException;
}
