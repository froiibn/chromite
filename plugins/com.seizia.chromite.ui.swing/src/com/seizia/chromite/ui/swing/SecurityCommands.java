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

import com.seizia.chromite.model.admin.security.Securable;
import com.seizia.chromite.model.admin.security.SecurityDialect;
import com.seizia.chromite.model.admin.security.SecurityPrincipal;
import com.seizia.chromite.model.admin.security.SecurityStatement;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourcePermission;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Object Explorer commands of the security editors: Properties of a login, user or role, New Login/User/Role…, Delete,
 * and Permissions… of a table, view, sequence or routine.
 *
 * 설계: 탐색기({@code ObjectExplorerPanel})에는 메뉴 항목만 두고 판단과 실행은 이 클래스가 한다(다른 기능과 겹치는 파일을 적게 고치기 위해).
 * DB별 차이는 모델 번들의 {@link SecurityDialect}가 맡는다. 변경은 SSMS 속성 창처럼 OK를 누르면 스크립트를 먼저 보여 주고
 * (테이블 디자이너의 저장과 같은 {@link ScriptPreviewDialog}) Execute를 고르면 실행한 뒤, SWT 편집기의 저장처럼 Logins·Roles·Users 폴더를 다시 읽는다
 * ({@code EntityEditor.saveCommandContext}). 폴더 노드의 새로 고침은 폴더의 부모 객체(SQL Server는 데이터 원본 전체)를 다시 읽어 트리 노드가 바뀌므로,
 * 트리가 그 폴더를 보여 주고 있으면 Object Explorer의 새로 고침(F5와 같은 경로, {@link ObjectExplorerPanel#refreshNavigatorNode})에 맡긴다.
 * 모델 이벤트만으로는 SQL Server 로그인 폴더에 새 로그인이 나타나지 않았다(2026-11-06 확인).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class SecurityCommands {

    private static final Log log = Log.getLog(SecurityCommands.class);

    /**
     * What happened to a script shown with {@link #applyScript}.
     */
    enum ApplyResult {
        /** Executed successfully */
        EXECUTED,
        /** Opened in a query window or canceled: the caller stays open */
        NOT_EXECUTED
    }

    private SecurityCommands() {
    }

    static boolean canOpenProperties(@Nullable DBSObject object) {
        SecurityDialect dialect = SecurityDialect.forObject(object);
        return dialect != null && dialect.isPrincipal(object);
    }

    static boolean canEditPermissions(@Nullable DBSObject object) {
        SecurityDialect dialect = SecurityDialect.forObject(object);
        return dialect != null && dialect.canEditPermissions(object);
    }

    /**
     * What "New …" creates in the selected node, or null if it is not a Logins, Roles or Users folder.
     */
    @Nullable
    static SecurityPrincipal.Kind getNewPrincipalKind(@Nullable DBNNode node) {
        if (!(node instanceof DBNDatabaseFolder folder) || folder.getChildrenClass() == null) {
            return null;
        }
        SecurityDialect dialect = SecurityDialect.forObject(folder.getParentObject());
        return dialect == null ? null : dialect.getNewPrincipalKind(folder.getChildrenClass());
    }

    @NotNull
    static String getNewPrincipalLabel(@Nullable SecurityPrincipal.Kind kind) {
        if (kind == null) {
            return SecurityMessages.security_new_login;
        }
        return switch (kind) {
            case LOGIN -> SecurityMessages.security_new_login;
            case USER -> SecurityMessages.security_new_user;
            case ROLE -> SecurityMessages.security_new_role;
        };
    }

    static void openProperties(@NotNull ObjectExplorerPanel owner, @NotNull DBSObject object, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        SecurityDialect dialect = SecurityDialect.forObject(object);
        if (dialect == null) {
            return;
        }
        SecurityPrincipal principal;
        try {
            principal = ProgressDialog.runTask(NLS.bind(SecurityMessages.security_progress_load, object.getName()),
                monitor -> dialect.loadPrincipal(monitor, object));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SecurityMessages.security_properties,
                NLS.bind(SecurityMessages.security_error_load, object.getName()), e);
            return;
        }
        PrincipalPropertiesDialog.show(owner, dialect, principal, queryWindows, null);
    }

    static void newPrincipal(@NotNull ObjectExplorerPanel owner, @NotNull DBNDatabaseFolder folder, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        SecurityPrincipal.Kind kind = getNewPrincipalKind(folder);
        SecurityDialect dialect = SecurityDialect.forObject(folder.getParentObject());
        if (kind == null || dialect == null) {
            return;
        }
        SecurityPrincipal principal;
        try {
            principal = ProgressDialog.runTask(getNewPrincipalLabel(kind), monitor -> dialect.newPrincipal(monitor, kind));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(getNewPrincipalLabel(kind), null, e);
            return;
        }
        PrincipalPropertiesDialog.show(owner, dialect, principal, queryWindows, folder);
    }

    static void deletePrincipal(@NotNull ObjectExplorerPanel owner, @NotNull DBSObject object, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        SecurityDialect dialect = SecurityDialect.forObject(object);
        if (dialect == null || !dialect.isPrincipal(object)) {
            return;
        }
        if (JOptionPane.showConfirmDialog(owner, NLS.bind(SecurityMessages.security_drop_question, object.getName()),
            SecurityMessages.security_drop_title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        SecurityPrincipal principal;
        try {
            principal = ProgressDialog.runTask(NLS.bind(SecurityMessages.security_progress_load, object.getName()),
                monitor -> dialect.loadPrincipal(monitor, object));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SecurityMessages.security_drop_title,
                NLS.bind(SecurityMessages.security_error_load, object.getName()), e);
            return;
        }
        applyScript(owner, NLS.bind(SecurityMessages.security_drop_title_object, object.getName()), dialect,
            dialect.generateDropScript(principal), queryWindows, owner, null, object);
    }

    static void openObjectPermissions(@NotNull ObjectExplorerPanel owner, @NotNull DBSObject object, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        SecurityDialect dialect = SecurityDialect.forObject(object);
        if (dialect == null) {
            return;
        }
        ObjectPermissionsDialog.Model model;
        try {
            model = ProgressDialog.runTask(NLS.bind(SecurityMessages.security_progress_load, object.getName()), monitor -> {
                Securable securable = dialect.toSecurable(monitor, object);
                if (securable == null) {
                    throw new DBException("Permissions are not supported for " + object.getName()); //$NON-NLS-1$
                }
                return new ObjectPermissionsDialog.Model(securable, dialect.loadObjectPermissions(monitor, securable));
            });
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SecurityMessages.security_permissions,
                NLS.bind(SecurityMessages.security_error_load, object.getName()), e);
            return;
        }
        ObjectPermissionsDialog.show(owner, dialect, model, queryWindows);
    }

    /**
     * Shows the script with Execute; executes it and reads the changed node again.
     *
     * @param explorer        Object Explorer, which reads the changed folder again
     * @param refreshNode     navigator node to read again after executing (the folder of a new principal), or null
     * @param refreshParentOf an object whose parent node (the Logins, Roles or Users folder) is read again after executing, or null
     */
    @NotNull
    static ApplyResult applyScript(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull SecurityDialect dialect,
        @NotNull List<SecurityStatement> statements,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @NotNull ObjectExplorerPanel explorer,
        @Nullable DBNDatabaseNode refreshNode,
        @Nullable DBSObject refreshParentOf
    ) {
        if (statements.isEmpty()) {
            JOptionPane.showMessageDialog(owner, SecurityMessages.security_no_changes, title, JOptionPane.INFORMATION_MESSAGE);
            return ApplyResult.NOT_EXECUTED;
        }
        if (!dialect.getDataSource().getContainer().hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA)) {
            JOptionPane.showMessageDialog(owner, NLS.bind(SecurityMessages.security_error_metadata_restricted,
                dialect.getDataSource().getContainer().getName()), title, JOptionPane.WARNING_MESSAGE);
            return ApplyResult.NOT_EXECUTED;
        }
        String script = dialect.formatScript(statements);
        ScriptPreviewDialog.Result choice = ScriptPreviewDialog.show(owner, NLS.bind(SecurityMessages.security_script_title, title), script, true);
        if (choice == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(dialect.getDataSource().getContainer(), script, 0, false);
            return ApplyResult.NOT_EXECUTED;
        }
        if (choice != ScriptPreviewDialog.Result.EXECUTE) {
            return ApplyResult.NOT_EXECUTED;
        }
        // The node is looked up before executing: a dropped or renamed principal is still in the navigator model then
        DBNDatabaseNode[] node = {refreshNode};
        try {
            ProgressDialog.runTask(SecurityMessages.security_progress_apply, monitor -> {
                if (node[0] == null && refreshParentOf != null) {
                    DBNDatabaseNode objectNode = DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(monitor, refreshParentOf, false);
                    if (objectNode != null && objectNode.getParentNode() instanceof DBNDatabaseNode parentNode) {
                        node[0] = parentNode;
                    }
                }
                dialect.execute(monitor, statements);
                return null;
            });
            refresh(explorer, node[0]);
            return ApplyResult.EXECUTED;
        } catch (SecurityDialect.ExecutionException e) {
            DBWorkbench.getPlatformUI().showError(title,
                NLS.bind(SecurityMessages.security_error_apply, e.getStatement().displaySql()), e);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, SecurityMessages.security_error_apply_generic, e);
        }
        // Statements before the failing one may have been executed (DDL is not transactional everywhere)
        refresh(explorer, node[0]);
        return ApplyResult.NOT_EXECUTED;
    }

    /**
     * Shows the script without Execute ("Script" button).
     */
    static void showScript(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull SecurityDialect dialect,
        @NotNull List<SecurityStatement> statements,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        if (statements.isEmpty()) {
            JOptionPane.showMessageDialog(owner, SecurityMessages.security_no_changes, title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String script = dialect.formatScript(statements);
        if (ScriptPreviewDialog.show(owner, NLS.bind(SecurityMessages.security_script_title, title), script, false) == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(dialect.getDataSource().getContainer(), script, 0, false);
        }
    }

    /**
     * Reads the folder again: through Object Explorer when it shows the folder (the same path as F5, which also rebuilds the tree
     * nodes), otherwise only in the navigator model so that the next expansion shows the change.
     */
    private static void refresh(@NotNull ObjectExplorerPanel explorer, @Nullable DBNDatabaseNode node) {
        if (node == null || explorer.refreshNavigatorNode(node)) {
            return;
        }
        try {
            ProgressDialog.runTask(SecurityMessages.security_progress_apply, monitor -> node.refreshNode(monitor, DBNEvent.FORCE_REFRESH));
        } catch (DBException e) {
            log.debug("Refresh of " + node.getNodeDisplayName() + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
