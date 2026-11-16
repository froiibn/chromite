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
package com.seizia.chromite.ext.oracle.importer;

import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionRole;
import org.jkiss.code.Nullable;

/**
 * Rules shared by the Oracle tool importers.
 *
 * 설계: SQL Developer와 Toad 가져오기가 역할을 같은 규칙으로 옮기도록 한곳에 둔다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
final class OracleImportUtils {

    private OracleImportUtils() {
    }

    /**
     * The logon role to keep for a role name of another tool: SYSDBA or SYSOPER (the roles Chromite supports), else null.
     * The default role is not stored: the legacy role key would make the user name "user AS Normal".
     */
    @Nullable
    static String getLogonRole(@Nullable String role) {
        if (role == null) {
            return null;
        }
        for (OracleConnectionRole value : OracleConnectionRole.values()) {
            if (value != OracleConnectionRole.NORMAL && value.getTitle().equalsIgnoreCase(role.trim())) {
                return value.getTitle();
            }
        }
        return null;
    }
}
