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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * A PL/SQL program unit as {@code ALL_SOURCE} names it: the source identifier of the Oracle debugger (breakpoints, frames,
 * sources).
 *
 * 설계: {@code DBMS_DEBUG}의 줄 번호는 단위({@code ALL_SOURCE}의 OWNER·NAME·TYPE) 원본의 줄이다. 패키지 프로시저의 줄은 패키지 본문의
 * 줄이므로 같은 패키지의 프로시저들은 한 단위(PACKAGE BODY)를 함께 쓴다. 디버거 화면은 이 값을 원본·중단점의 열쇠로 쓰므로 record의
 * equals·toString을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 *
 * @param owner schema
 * @param type  {@code ALL_SOURCE.TYPE}: PROCEDURE, FUNCTION, PACKAGE, PACKAGE BODY, TRIGGER, TYPE, TYPE BODY
 * @param name  object name (the package for a packaged routine)
 */
record OracleDebugUnit(@NotNull String owner, @NotNull String type, @NotNull String name) {

    static final String TYPE_PROCEDURE = "PROCEDURE"; //$NON-NLS-1$
    static final String TYPE_FUNCTION = "FUNCTION"; //$NON-NLS-1$
    static final String TYPE_PACKAGE = "PACKAGE"; //$NON-NLS-1$
    static final String TYPE_PACKAGE_BODY = "PACKAGE BODY"; //$NON-NLS-1$
    static final String TYPE_TRIGGER = "TRIGGER"; //$NON-NLS-1$
    static final String TYPE_TYPE = "TYPE"; //$NON-NLS-1$
    static final String TYPE_TYPE_BODY = "TYPE BODY"; //$NON-NLS-1$

    /**
     * {@code DBMS_DEBUG.namespace_*} of breakpoints in this unit.
     */
    int namespace() {
        return switch (type) {
            case TYPE_PACKAGE_BODY, TYPE_TYPE_BODY -> 2; // namespace_pkg_body
            case TYPE_TRIGGER -> 3; // namespace_trigger
            default -> 1; // namespace_pkgspec_or_toplevel
        };
    }

    /**
     * The unit of a stack frame ({@code DBMS_DEBUG.program_info.LibunitType}, the library cache object type), or null for
     * an anonymous block or an unknown kind.
     */
    @Nullable
    static OracleDebugUnit ofFrame(@Nullable String owner, @Nullable String name, int libunitType) {
        if (owner == null || name == null) {
            return null;
        }
        String type = switch (libunitType) {
            case 7 -> TYPE_PROCEDURE;
            case 8 -> TYPE_FUNCTION;
            case 9 -> TYPE_PACKAGE;
            case 11 -> TYPE_PACKAGE_BODY;
            case 12 -> TYPE_TRIGGER;
            case 13 -> TYPE_TYPE;
            case 14 -> TYPE_TYPE_BODY;
            default -> null;
        };
        return type == null ? null : new OracleDebugUnit(owner, type, name);
    }

    @NotNull
    @Override
    public String toString() {
        return owner + "." + name + " (" + type + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
