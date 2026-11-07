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
package com.seizia.chromite.ext.mimer.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mimer.model.MimerDataSource;
import com.seizia.chromite.ext.mimer.model.MimerProgram;
import com.seizia.chromite.ext.mimer.model.MimerUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds CREATE / DROP / ALTER support for Mimer SQL programs, so "Create New Program" is
 * available on the DBA Views &gt; Programs tree node and its properties view is editable:
 * {@code CREATE IDENT "n" AS PROGRAM USING 'password'} / {@code DROP IDENT "n" [CASCADE]} /
 * {@code ALTER IDENT "n" SET PASSWORD '...'} for the Password property (see {@link
 * MimerProgram#buildAlterDDL}). {@code CASCADE} is offered via the delete-confirmation
 * "Cascade" checkbox with an extra "are you sure" prompt (see {@link MimerCascadeDropUtil});
 * omitted, {@code RESTRICT} is implicit and the drop fails loud rather than silently dropping
 * objects the ident owns.
 *
 * 설계: CREATE 문에는 암호가 들어 있으므로 쿼리 관리자 기록에서 뺀다({@code setDisableSessionLogging}, PostgreSQL 역할·
 * SQL Server 로그인 관리자와 같음). 빈 암호는 {@link MimerProgram#buildCreateDDL}이 거절한다.
 *
 * 변경 이력:
 * - 2026-11-08 빈 암호의 CREATE 거절, 암호가 든 CREATE의 기록 끄기 (docs/chromite/swing-create-objects.md "새 사용자의 암호")
 *
 * @author Mimer Information Technology
 */
public class MimerProgramManager extends SQLObjectEditor<MimerProgram, MimerDataSource> {

    private static final String[] ALTERABLE_PROPERTIES = {"password"};

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY | FEATURE_DELETE_CASCADE;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Nullable
    @Override
    public DBSObjectCache<MimerDataSource, MimerProgram> getObjectsCache(@NotNull MimerProgram object) {
        return object.getDataSource().getProgramCache();
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_PROGRAM";
    }

    @NotNull
    @Override
    protected MimerProgram createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerProgram((MimerDataSource) container, getBaseObjectName());
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        actions.add(new SQLDatabasePersistAction("Create program", command.getObject().buildCreateDDL()));
        command.setDisableSessionLogging(true); // Hide password from Query Manager
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(MimerCascadeDropUtil.dropAction("Drop program",
            command.getObject().buildDropDDL(), options, "program", command.getObject().getName(), executionContext));
    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        Set<String> changed = new HashSet<>();
        for (String propId : ALTERABLE_PROPERTIES) {
            if (command.hasProperty(propId)) {
                changed.add(propId);
            }
        }
        for (String ddl : command.getObject().buildAlterDDL(changed)) {
            actionList.add(new SQLDatabasePersistAction("Alter program", ddl));
        }
        if (command.hasProperty("comment")) {
            MimerProgram program = command.getObject();
            String quotedName = "\"" + program.getName().replace("\"", "\"\"") + "\"";
            MimerUtils.addCommentModifyAction(actionList, program, "IDENT", quotedName, "Comment program", monitor);
        }
    }
}
