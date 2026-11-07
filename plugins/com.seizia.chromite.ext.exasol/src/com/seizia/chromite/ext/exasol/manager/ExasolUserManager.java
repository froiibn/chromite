/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.exasol.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.ExasolConstants;
import com.seizia.chromite.ext.exasol.ExasolMessages;
import com.seizia.chromite.ext.exasol.ExasolUserType;
import com.seizia.chromite.ext.exasol.model.ExasolDataSource;
import com.seizia.chromite.ext.exasol.model.ExasolPriority;
import com.seizia.chromite.ext.exasol.model.security.ExasolUser;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * Exasol user manager: CREATE/ALTER/RENAME/DROP USER and the user comment.
 *
 * 설계: 로컬 사용자의 암호는 만드는 쪽(대화상자)이 받아 {@link ExasolUser#setPassword}로 넣는다. 관리자는 암호를 지어내지 않는다:
 * 암호가 비어 있으면 문장을 만들지 않고 거절한다(받을 수 없는 경로가 고정 암호의 사용자를 만들지 않게). 암호는 언제나 큰따옴표로 감싸고
 * 안의 큰따옴표를 두 번 쓴다(Exasol의 {@code IDENTIFIED BY "…"}). {@code DBUtils.getQuotedIdentifier}는 이미 따옴표로 싸인 글을 그대로
 * 두므로 암호에는 쓰지 않는다. 암호가 든 문장은 쿼리 관리자 기록에서 뺀다({@code setDisableSessionLogging}, PostgreSQL 역할·SQL Server
 * 로그인 관리자와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 새 사용자의 고정 암호({@code "password"})를 없애고, 빈 암호는 거절, 암호 따옴표 처리와 기록 끄기
 *   (docs/chromite/swing-create-objects.md "새 사용자의 암호")
 */
public class ExasolUserManager extends SQLObjectEditor<ExasolUser, ExasolDataSource> implements DBEObjectRenamer<ExasolUser> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public DBSObjectCache<ExasolDataSource, ExasolUser> getObjectsCache(
        ExasolUser object) {
        ExasolDataSource ds = (ExasolDataSource) object.getDataSource();
        return ds.getUserCache();

    }

    @Override
    protected ExasolUser createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
    	// No password: the caller asks for it (an empty one is refused in addObjectCreateActions)
    	return new ExasolUser((ExasolDataSource) container, "user", "", "", "", "", ExasolUserType.LOCAL);
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        ExasolUser obj = command.getObject();

        StringBuilder script = new StringBuilder("CREATE USER " + DBUtils.getQuotedIdentifier(obj) + " IDENTIFIED ");

        switch (obj.getType()) {
            case LOCAL:
                script.append(" BY ").append(quotePassword(obj));
                command.setDisableSessionLogging(true); // Hide password from Query Manager
                break;
            case LDAP:
                script.append(" AT LDAP AS '" + obj.getDn() + "'");
                break;
            default:
                script.append(" BY KERBEROS PRINCIPAL '" + obj.getKerberosPrincipal() + "'");
                break;
        }
        actions.add(new SQLDatabasePersistAction("Create User", script.toString()));

        if (!CommonUtils.isEmpty(obj.getDescription())) {
            actions.add(Comment(obj));
        }

    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        ExasolUser obj = command.getObject();
        actions.add(new SQLDatabasePersistAction("Drop User", "DROP USER " + DBUtils.getQuotedIdentifier(obj)));
    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext,
                             @NotNull ExasolUser object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    @Override
    protected void processObjectRename(DBECommandContext commandContext,
                                       ExasolUser object, Map<String, Object> options, String newName) throws DBException {
        ObjectRenameCommand command = new ObjectRenameCommand(object, ModelMessages.model_jdbc_rename_object, options, newName);
        commandContext.addCommand(command, new RenameObjectReflector(), true);
    }

    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options) {
        ExasolUser obj = command.getObject();
        actions.add(
            new SQLDatabasePersistAction(
                "Rename User",
                "RENAME USER " + DBUtils.getQuotedIdentifier(obj.getDataSource(), command.getOldName()) + " to " +
                    DBUtils.getQuotedIdentifier(obj.getDataSource(), command.getNewName()))
        );
    }

    /**
     * The password as Exasol writes it ({@code "…"} with inner quotes doubled).
     *
     * @throws DBException if the user has no password: no statement is generated without one
     */
    @NotNull
    private static String quotePassword(@NotNull ExasolUser user) throws DBException {
        String password = user.getPassword();
        if (CommonUtils.isEmpty(password)) {
            throw new DBException("A password is required for the local user " + user.getName());
        }
        return "\"" + password.replace("\"", "\"\"") + "\"";
    }

    private SQLDatabasePersistAction Comment(ExasolUser obj) {
        return new SQLDatabasePersistAction("Comment on User", "COMMENT ON USER " + DBUtils.getQuotedIdentifier(obj) + " IS '" + ExasolUtils.quoteString(obj.getDescription()) + "'");
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList,
                                          @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        ExasolUser obj = command.getObject();

        if (command.getProperties().containsKey("description")) {

            actionList.add(Comment(obj));
        }

        if (command.getProperties().containsKey("priority")) {
        	
        	ExasolPriority priority = obj.getPriority();
        	
        	if (ExasolConstants.PRIORITY_GROUP_CLASS.equals(priority.getClass().getName())) {
                String script = String.format("GRANT PRIORITY GROUP %s to %s", DBUtils.getQuotedIdentifier(priority), DBUtils.getQuotedIdentifier(obj));
                actionList.add(new SQLDatabasePersistAction(ExasolMessages.manager_assign_priority_group, script));
        	}
        	if (ExasolConstants.CONSUMER_GROUP_CLASS.equals(priority.getClass().getName())) {
                String script = String.format("ALTER USER  %s SET CONSUMER_GROUP = %s", DBUtils.getQuotedIdentifier(obj), DBUtils.getQuotedIdentifier(priority));
                actionList.add(new SQLDatabasePersistAction(ExasolMessages.manager_assign_priority_group, script));
        	}
        }

        if (command.getProperties().containsKey("dn")) {
            String script = String.format("ALTER USER " + DBUtils.getQuotedIdentifier(obj) + " IDENTIFIED AT LDAP AS '%s'", obj.getDn());
            actionList.add(new SQLDatabasePersistAction("alter user", script));
            return;
        }

        if (command.getProperties().containsKey("kerberosPrincipal")) {
            String script = String.format("ALTER USER " + DBUtils.getQuotedIdentifier(obj) + " IDENTIFIED BY KERBEROS PRINCIPAL '%s'", obj.getKerberosPrincipal());
            actionList.add(new SQLDatabasePersistAction("alter user", script));
            return;
        }

        if (command.getProperties().containsKey("password")) {

            StringBuilder script = new StringBuilder("ALTER USER " + DBUtils.getQuotedIdentifier(obj) + " IDENTIFIED ");
            script.append(" BY ").append(quotePassword(obj)).append(" ");
            command.setDisableSessionLogging(true); // Hide password from Query Manager
            /*ExasolDataSource ds = (ExasolDataSource) obj.getDataSource();
            if (!ds.hasAlterUserPrivilege()) {
                String oldPassword = new UITask<String>() {
                    @Override
                    protected String runTask() {
                        ExasolUserQueryPassword dialog = new ExasolUserQueryPassword(UIUtils.getActiveWorkbenchShell());

                        if (dialog.open() != IDialogConstants.OK_ID) {
                            throw new IllegalStateException("Password has to be provided");
                        }

                        return dialog.getPassword();
                    }
                }.execute();

                if (CommonUtils.isEmpty(oldPassword)) {
                    throw new IllegalStateException("Old password can not be empty");
                }

                script.append(" REPLACE \"" + oldPassword + "\"");
            }*/

            actionList.add(new SQLDatabasePersistAction("Modify User", script.toString()));
        }
    }

}