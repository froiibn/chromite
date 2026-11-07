/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
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
package com.seizia.chromite.ext.postgresql.debug.internal.impl;

import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import com.seizia.chromite.ext.postgresql.debug.PostgreDebugConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PG breakpoint: a line of a function (line -1: the function's start).
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 마커 속성에서 만드는 fromMap 삭제, 전역 중단점의 대상 pid, 같은 함수·줄이면 같은 중단점(equals)
 *   (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugBreakpointDescriptor implements DBGBreakpointDescriptor {
    private final Object oid;

    private final long lineNo;

    private final boolean onStart;

    private final long targetId;

    private final boolean all;

    public PostgreDebugBreakpointDescriptor(Object oid, long lineNo) {
        this(oid, lineNo, -1);
    }

    /**
     * @param targetId server process id the breakpoint applies to (global breakpoints), -1 for all sessions
     */
    public PostgreDebugBreakpointDescriptor(Object oid, long lineNo, long targetId) {
        this.oid = oid;
        this.lineNo = lineNo;
        this.onStart = lineNo < 0;
        this.targetId = targetId;
        this.all = targetId < 0;
    }

    public Object getObjectId() {
        return oid;
    }

    public long getLineNo() {
        return lineNo;
    }

    public boolean isOnStart() {
        return onStart;
    }

    public long getTargetId() {
        return targetId;
    }

    public boolean isAll() {
        return all;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put(PostgreDebugConstants.ATTR_FUNCTION_OID, String.valueOf(oid));
        map.put("onStart", onStart);
        map.put("targetId", String.valueOf(targetId));
        map.put("all", all);
        return map;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PostgreDebugBreakpointDescriptor other && String.valueOf(oid).equals(String.valueOf(other.oid))
            && lineNo == other.lineNo;
    }

    @Override
    public int hashCode() {
        return Objects.hash(String.valueOf(oid), lineNo);
    }

    @Override
    public String toString() {
        return "PostgreDebugBreakpointDescriptor [obj=" + oid + ", line=" + lineNo + ", properties=" + toMap() + "]";
    }

}
