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

import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/**
 * Object Explorer의 Tasks &gt; Back Up… / Restore… 명령: 데이터베이스 종류에 맞는 대화상자를 연다.
 *
 * 설계: SQL Server는 서버가 백업 파일을 쓰는 T-SQL({@link SqlServerBackupDialog}, {@link SqlServerRestoreDialog}),
 * PostgreSQL·MySQL/MariaDB는 DBeaver 네이티브 도구 작업({@link NativeBackupRestoreDialog})이다. 메뉴를 켜는 조건은
 * SWT의 도구 메뉴 조건을 따른다: PostgreSQL은 서버 종류가 네이티브 클라이언트를 지원할 때
 * ({@code supportsNativeClient()}, ext.postgresql plugin.xml의 pgDatabaseBackup 작업 조건), MySQL은
 * {@code supportsNativeClients()}(ext.mysql plugin.xml)다. SQL Server는 디스크로 백업할 수 없는 Azure·Fabric·Babelfish 드라이버와
 * Synapse를 뺀다(서버 에디션은 대화상자를 열 때 다시 확인한다). Oracle은 대상이 아니다(RMAN·Data Pump는 서버 쪽 도구).
 * PostgreSQL·MySQL 조건은 Chromite Agent 백업·복원 도구와 같도록 {@link NativeBackupRestore#isSupported}에 둔다.
 * Run Script with Native Client…(Tasks 메뉴, Query 메뉴)도 같은 대화상자의 스크립트 방식이라 여기 둔다. 대상은 PostgreSQL 데이터베이스,
 * MySQL/MariaDB 카탈로그, Oracle 스키마·연결이다({@link NativeBackupRestore#isScriptSupported}, 모델 작업 형식의 대상과 같음).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-08 PostgreSQL·MySQL 조건을 {@link NativeBackupRestore#isSupported}로 옮김(Chromite Agent 도구와 함께 씀)
 * - 2026-11-08 Run Script with Native Client…(Object Explorer, 쿼리 창) (docs/chromite/swing-database-tasks.md)
 */
final class BackupRestoreCommands {

    private BackupRestoreCommands() {
    }

    /**
     * Run Script with Native Client… applies to the object (see {@link NativeBackupRestore#isScriptSupported}).
     */
    static boolean canRunScript(@Nullable DBSObject object) {
        return NativeBackupRestore.isScriptSupported(object);
    }

    static void runScript(@NotNull Component owner, @NotNull DBSObject object) {
        if (canRunScript(object)) {
            NativeBackupRestoreDialog.openScript(owner, object, null);
        }
    }

    /**
     * Query &gt; Run with Native Client…: the query window's saved file runs on the window's current database (PostgreSQL,
     * MySQL/MariaDB) or schema (Oracle) with the connection's native client. The tool reads the file, so the window must be
     * saved first (as in SWT, which passed the editor's file).
     */
    static void runEditorScript(@NotNull Component owner, @NotNull SqlEditorPanel editor) {
        DBSObject target = findEditorTarget(editor);
        if (target == null) {
            JOptionPane.showMessageDialog(owner, BackupRestoreMessages.native_script_no_target,
                BackupRestoreMessages.native_script_editor_menu, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Path file = editor.getFile();
        if (file == null || editor.isDirty()) {
            JOptionPane.showMessageDialog(owner, BackupRestoreMessages.native_script_save_first,
                BackupRestoreMessages.native_script_editor_menu, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        NativeBackupRestoreDialog.openScript(owner, target, file.toString());
    }

    /**
     * The window's current database or schema from its session; without a session the connection's default PostgreSQL
     * database, the MySQL/MariaDB database named in the connection, or the Oracle connection itself.
     */
    @Nullable
    static DBSObject findEditorTarget(@NotNull SqlEditorPanel editor) {
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        if (container == null || container.getDataSource() == null) {
            return null;
        }
        DBCExecutionContext context = editor.getExecutionContext();
        DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
        if (defaults != null) {
            if (canRunScript(defaults.getDefaultCatalog())) {
                return defaults.getDefaultCatalog();
            }
            if (canRunScript(defaults.getDefaultSchema())) {
                return defaults.getDefaultSchema();
            }
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource instanceof PostgreDataSource pg && canRunScript(pg.getDefaultInstance())) {
            return pg.getDefaultInstance();
        }
        String databaseName = container.getConnectionConfiguration().getDatabaseName();
        if (dataSource instanceof MySQLDataSource my && !CommonUtils.isEmpty(databaseName) && canRunScript(my.getCatalog(databaseName))) {
            return my.getCatalog(databaseName);
        }
        if (dataSource instanceof DBSObject object && canRunScript(object)) {
            return object;
        }
        return null;
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
        return NativeBackupRestore.isSupported(object);
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
