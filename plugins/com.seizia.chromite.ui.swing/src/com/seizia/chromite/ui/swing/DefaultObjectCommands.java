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

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.awt.*;

/**
 * Object Explorer "Set as Default": makes a database or schema the default of the connection's main session.
 *
 * 설계: SWT {@code NavigatorHandlerSetDefaultObject}와 같이 연결의 주 실행 문맥(기본 인스턴스의 기본 문맥)의 기본 카탈로그·스키마를 바꾼다.
 * 인자는 쿼리 창의 DB 콤보와 같은 {@link DBExecUtils#setExecutionContextDefaults}다(스키마의 카탈로그가 지금과 다르면 카탈로그도 함께).
 * <ul>
 *     <li>SWT는 활성 편집기의 문맥도 함께 바꿨다. Swing 쿼리 창은 대개 자기 세션을 가지며(쿼리 창마다 별도 연결) 그 창의 DB 콤보와 Ctrl+0이
 *     그 세션을 바꾸므로, 이 명령은 주 세션만 바꾼다. 주 세션을 같이 쓰는 쿼리 창과 새로 여는 쿼리 창(주 세션의 기본값을 복사함)이 따른다.</li>
 *     <li>트랜잭션(사용자 규칙): 같은 인스턴스 안의 스키마·카탈로그 바꾸기(SET search_path, ALTER SESSION SET CURRENT_SCHEMA, USE)는
 *     열린 트랜잭션을 끝내지 않는다. 다른 데이터베이스 인스턴스로 옮길 때(PostgreSQL 데이터베이스)는 주 세션이 다른 서버 세션이 되어
 *     열린 트랜잭션이 보이지 않게 남으므로, 그때만 {@link ExitTransactionCheck#confirmDefaultChange}로 커밋/롤백/취소를 묻는다
 *     (쿼리 창 DB 콤보의 같은 판단과 같음).</li>
 *     <li>지금 기본값인 객체에서는 꺼진다({@link DBNUtils#isDefaultElement}, 트리는 그 노드를 굵게 그린다). 바꾼 뒤 메인 창이 그 연결의
 *     쿼리 창 DB 콤보를 다시 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-productivity.md "기본 스키마")
 */
final class DefaultObjectCommands {

    private DefaultObjectCommands() {
    }

    /**
     * True for a database or schema of a connected connection that its main session can change to and is not the default.
     * In-memory model data only (EDT).
     */
    static boolean canSetDefault(@Nullable DBNNode node) {
        DBSObject object = node instanceof DBNDatabaseNode databaseNode ? databaseNode.getObject() : null;
        if (!(object instanceof DBSCatalog || object instanceof DBSSchema)) {
            return false;
        }
        DBCExecutionContextDefaults<?, ?> defaults = getDefaults(object);
        if (defaults == null || DBNUtils.isDefaultElement(node)) {
            return false;
        }
        return object instanceof DBSCatalog ? defaults.supportsCatalogChange() : defaults.supportsSchemaChange();
    }

    /**
     * Makes the node's database or schema the main session's default, in a progress dialog.
     *
     * @param afterChange runs in the EDT after the change (also after a failure: the default may have changed in part)
     */
    static void setDefault(@NotNull Component owner, @NotNull DBNNode node, @NotNull Runnable afterChange) {
        DBSObject object = node instanceof DBNDatabaseNode databaseNode ? databaseNode.getObject() : null;
        DBPDataSource dataSource = object == null ? null : object.getDataSource();
        DBCExecutionContext context = dataSource == null ? null : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
        if (defaults == null) {
            return;
        }
        String targetName = DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
        // Another database instance: the main session becomes another server session (its open transaction would stay behind)
        DBSInstance instance = object instanceof DBSInstance self ? self : DBUtils.getParentOfType(DBSInstance.class, object);
        if (instance != null && instance != context.getOwnerInstance()
            && !ExitTransactionCheck.confirmDefaultChange(owner, context, targetName)) {
            return;
        }
        DBSCatalog currentCatalog = defaults.getDefaultCatalog();
        String currentCatalogName = currentCatalog == null ? null : currentCatalog.getName();
        DBSCatalog catalog = object instanceof DBSCatalog self ? self : DBUtils.getParentOfType(DBSCatalog.class, object);
        String newCatalogName = catalog != null ? catalog.getName() : currentCatalogName;
        String newSchemaName = object instanceof DBSSchema ? object.getName() : null;
        String title = ObjectExplorerMessages.oe_set_default;
        try {
            ProgressDialog.runTask(title, monitor -> {
                DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context, newCatalogName, currentCatalogName, newSchemaName);
                return null;
            });
        } catch (DBInterruptedException e) {
            // Canceled
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, NLS.bind(ObjectExplorerMessages.oe_set_default_error, targetName), e);
        }
        afterChange.run();
    }

    @Nullable
    private static DBCExecutionContextDefaults<?, ?> getDefaults(@NotNull DBSObject object) {
        DBPDataSource dataSource = object.getDataSource();
        if (dataSource == null || !dataSource.getContainer().isConnected()) {
            return null;
        }
        DBCExecutionContext context = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        return context == null ? null : context.getContextDefaults();
    }
}
