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

import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * A line breakpoint in a PL/SQL unit. The breakpoint number {@code DBMS_DEBUG.SET_BREAKPOINT} gives is kept by the session.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param unit       the unit
 * @param lineNumber line of the unit's {@code ALL_SOURCE} text, from 1
 */
record OracleDebugBreakpoint(@NotNull OracleDebugUnit unit, int lineNumber) implements DBGBreakpointDescriptor {

    @NotNull
    @Override
    public Map<String, Object> toMap() {
        return Map.of("owner", unit.owner(), "type", unit.type(), "name", unit.name(), "line", lineNumber); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
