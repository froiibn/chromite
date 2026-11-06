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
package com.seizia.chromite.ui.swing;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.struct.DBSObject;

import java.awt.*;

/**
 * Object Explorer의 Tasks &gt; Back Up… / Restore… 명령: 데이터베이스 종류에 맞는 대화상자를 연다.
 *
 * 설계: SQL Server는 서버가 백업 파일을 쓰는 T-SQL({@link SqlServerBackupDialog}, {@link SqlServerRestoreDialog}),
 * PostgreSQL·MySQL/MariaDB는 DBeaver 네이티브 도구 작업({@link NativeBackupRestoreDialog})이다. 메뉴를 켜는 조건은
 * SWT의 도구 메뉴 조건을 따른다: PostgreSQL은 서버 종류가 네이티브 클라이언트를 지원할 때
 * ({@code supportsNativeClient()}, ext.postgresql plugin.xml의 pgDatabaseBackup 작업 조건), MySQL은
 * {@code supportsNativeClients()}(ext.mysql plugin.xml)다. SQL Server는 디스크로 백업할 수 없는 Azure·Fabric·Babelfish 드라이버와
 * Synapse를 뺀다(서버 에디션은 대화상자를 열 때 다시 확인한다). Oracle은 대상이 아니다(RMAN·Data Pump는 서버 쪽 도구).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
final class BackupRestoreCommands {

    private BackupRestoreCommands() {
    }

    /**
     * The object is a database that Back Up… and Restore… apply to.
     */
    static boolean canBackUp(@Nullable DBSObject object) {
        if (object instanceof SQLServerDatabase database) {
            DBPDriver driver = database.getDataSource().getContainer().getDriver();
            return !SQLServerUtils.isDriverAzure(driver) && !SQLServerUtils.isDriverFabric(driver)
                && !SQLServerUtils.isDriverBabelfish(driver) && !database.getDataSource().isSynapseDatabase();
        }
        if (object instanceof PostgreDatabase database) {
            return database.getDataSource().getServerType().supportsNativeClient();
        }
        if (object instanceof MySQLCatalog catalog) {
            return catalog.getDataSource().supportsNativeClients();
        }
        return false;
    }

    static void backUp(@NotNull Component owner, @NotNull DBSObject object, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        if (object instanceof SQLServerDatabase database) {
            SqlServerBackupDialog.open(owner, database, queryWindows);
        } else if (canBackUp(object)) {
            NativeBackupRestoreDialog.open(owner, object, false);
        }
    }

    static void restore(@NotNull Component owner, @NotNull DBSObject object, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        if (object instanceof SQLServerDatabase database) {
            SqlServerRestoreDialog.open(owner, database, queryWindows);
        } else if (canBackUp(object)) {
            NativeBackupRestoreDialog.open(owner, object, true);
        }
    }
}
