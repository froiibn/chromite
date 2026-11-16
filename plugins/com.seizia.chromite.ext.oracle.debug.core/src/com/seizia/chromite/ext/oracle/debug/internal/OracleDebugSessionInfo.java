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

import com.seizia.chromite.debug.DBGSessionInfo;
import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * The debugged (target) session: its SID and the {@code DBMS_DEBUG} debug session ID.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param sid     the target session's SID
 * @param debugId the debug session ID {@code DBMS_DEBUG.INITIALIZE} returned
 */
record OracleDebugSessionInfo(@NotNull String sid, @NotNull String debugId) implements DBGSessionInfo {

    @Override
    public Object getID() {
        return sid;
    }

    @Override
    public String getTitle() {
        return "Oracle debug session (SID " + sid + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of("sid", sid, "debugId", debugId); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
