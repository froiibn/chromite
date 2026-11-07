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
package com.seizia.chromite.registry;

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.connection.DBPConnectionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Regression tests of the "Restrict script execute" permission that the query window checks before it runs anything
 * ({@code SqlExecutionSafety.checkExecutePermission}, docs/chromite/swing-sql-exec-safety.md).
 *
 * 설계: 쿼리 창은 {@link DBPDataSourceContainer#hasModifyPermission}만 보고 실행을 막으므로 그 판단을 연결 종류와 연결의 제한 목록으로
 * 확인한다. 연결에 제한 목록이 있으면 연결 종류의 목록 대신 그것을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "SQL 실행 안전")
 */
public class ExecuteScriptsPermissionTest extends ChromiteUnitTest {

    @Test
    public void executeScriptsFollowsTheConnectionTypeUnlessTheConnectionHasItsOwnRestrictions() {
        DBPDataSourceContainer container = configureTestContainer("sqlite_jdbc");
        DataSourceDescriptor descriptor = (DataSourceDescriptor) container;
        Assertions.assertTrue(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));

        DBPConnectionType restrictedType = new DBPConnectionType(DBPConnectionType.PROD);
        restrictedType.setModifyPermissions(List.of(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
        container.getConnectionConfiguration().setConnectionType(restrictedType);
        Assertions.assertFalse(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
        Assertions.assertTrue(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_DATA));

        // The connection's own list replaces the type's
        descriptor.setModifyPermissions(List.of(DBPDataSourcePermission.PERMISSION_EDIT_DATA));
        Assertions.assertTrue(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
        descriptor.setModifyPermissions(List.of(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
        Assertions.assertFalse(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
        descriptor.setModifyPermissions(null);
        Assertions.assertFalse(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EXECUTE_SCRIPTS));
    }
}
