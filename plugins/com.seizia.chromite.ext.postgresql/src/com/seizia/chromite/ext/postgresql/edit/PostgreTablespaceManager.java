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

package com.seizia.chromite.ext.postgresql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreTablespace;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistActionAtomic;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.*;


/**
 * PostgreTablespaceManager
 *
 * 설계: 이름 바꾸기·소유자·옵션은 다른 PostgreSQL 개체와 같은 길이다. 이름은 {@link DBEObjectRenamer}(탐색기 F2), 소유자와 옵션은
 * 속성 명령({@link #addObjectModifyActions}, 데이터베이스 소유자를 고치는 {@code PostgreDatabaseManager}와 같음)이다. 서버의 ALTER TABLESPACE는
 * SET과 RESET을 한 문장에 받지 않으므로(시험 서버에서 구문 오류) 바뀐 옵션은 SET 한 문장, 빠진 옵션은 RESET 한 문장이다. ALTER TABLESPACE는
 * 트랜잭션 안에서 실행되고 롤백되므로(CREATE·DROP과 달리) 원자적 동작(자동 커밋 전환)이 아닌 보통 동작으로 둔다.
 * 시스템 테이블스페이스({@link PostgreTablespace#isSystemTablespace})에는 새 명령을 열지 않는다.
 *
 * 변경 이력:
 * - 2026-11-15 이름 바꾸기, 소유자·옵션 변경(앱 변경 #129, docs/chromite/swing-object-properties.md "PostgreSQL 테이블스페이스")
 */
public class PostgreTablespaceManager extends SQLObjectEditor<PostgreTablespace, PostgreDatabase> implements DBEObjectRenamer<PostgreTablespace> {

    private final static Set<String> systemTablespaces = new HashSet<>(Arrays.asList("pg_default", "pg_global"));

    private static final Log log = Log.getLog(PostgreTablespaceManager.class);

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public DBSObjectCache<PostgreDatabase, PostgreTablespace> getObjectsCache(PostgreTablespace object) {
        return object.getDatabase().tablespaceCache;
    }

    @Override
    protected PostgreTablespace createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        return new PostgreTablespace((PostgreDatabase) container);
    }

    @Override
    public void deleteObject(@NotNull DBECommandContext commandContext, @NotNull PostgreTablespace object, @NotNull Map<String, Object> options)
        throws DBException {
        if (systemTablespaces.contains(object.getName().toLowerCase())) {
            DBWorkbench.getPlatformUI().showError("Drop tablespace", "Unable to drop system tablespace " + object.getName());
        } else {
            super.deleteObject(commandContext, object, options);
        }
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options) {
        final PostgreTablespace tablespace = command.getObject();

        try {
            actions.add(
                new SQLDatabasePersistActionAtomic("Create tablespace", tablespace.getObjectDefinitionText(monitor, options)) { //$NON-NLS-2$
                    @Override
                    public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) throws DBCException {
                        super.afterExecute(session, error);
                        if (error == null) {
                            // A later options change compares with what the tablespace was made with
                            try {
                                tablespace.setSavedOptions(PostgreTablespace.parseOptions(tablespace.getOptions()));
                            } catch (DBException e) {
                                log.debug("Cannot read the options of the new tablespace", e);
                            }
                        }
                    }
                }
            );
        } catch (DBException e) {
            log.error(e);
        }
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options) {
        actions.add(
            new SQLDatabasePersistActionAtomic("Drop tablespace", "DROP TABLESPACE " //$NON-NLS-2$
                + DBUtils.getQuotedIdentifier(command.getObject()))
        );

    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        PostgreTablespace tablespace = command.getObject();
        String alterPrefix = "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + " "; //$NON-NLS-1$ //$NON-NLS-2$
        if (command.hasProperty("owner")) { //$NON-NLS-1$
            actionList.add(new SQLDatabasePersistAction("Change tablespace owner",
                alterPrefix + "OWNER TO " + DBUtils.getQuotedIdentifier(tablespace.getOwner(monitor)))); //$NON-NLS-1$
        }
        if (command.hasProperty("options")) { //$NON-NLS-1$
            Map<String, String> saved = tablespace.getSavedOptions();
            Map<String, String> wanted = PostgreTablespace.parseOptions(tablespace.getOptions());
            // Options whose value is new or different, then the ones that were removed: one statement each
            Map<String, String> set = new LinkedHashMap<>();
            wanted.forEach((name, value) -> {
                if (!value.equals(saved.get(name))) {
                    set.put(name, value);
                }
            });
            List<String> reset = new ArrayList<>();
            for (String name : saved.keySet()) {
                if (!wanted.containsKey(name)) {
                    reset.add(name);
                }
            }
            Map<String, String> afterSet = new LinkedHashMap<>(saved);
            afterSet.putAll(set);
            if (!set.isEmpty()) {
                StringJoiner pairs = new StringJoiner(", ", "(", ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                set.forEach((name, value) -> pairs.add(name + "=" + value)); //$NON-NLS-1$
                actionList.add(new OptionsAction(tablespace, alterPrefix + "SET " + pairs, afterSet)); //$NON-NLS-1$
            }
            if (!reset.isEmpty()) {
                Map<String, String> afterReset = new LinkedHashMap<>(afterSet);
                reset.forEach(afterReset::remove);
                actionList.add(new OptionsAction(tablespace, alterPrefix + "RESET (" + String.join(", ", reset) + ")", afterReset)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull PostgreTablespace object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        PostgreDatabase database = command.getObject().getDatabase();
        actions.add(new SQLDatabasePersistAction("Rename tablespace",
            "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(database.getDataSource(), command.getOldName()) //$NON-NLS-1$
                + " RENAME TO " + DBUtils.getQuotedIdentifier(database.getDataSource(), command.getNewName()))); //$NON-NLS-1$
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @Override
    public boolean canDeleteObject(@NotNull PostgreTablespace object) {
        return true;
    }

    @Override
    public boolean canEditObject(@NotNull PostgreTablespace object) {
        return !object.isSystemTablespace();
    }

    @Override
    public boolean canRenameObject(@NotNull PostgreTablespace object) {
        return !object.isSystemTablespace();
    }

    /**
     * ALTER TABLESPACE … SET / RESET: once executed, the tablespace keeps the options the server now has.
     */
    private static class OptionsAction extends SQLDatabasePersistAction {
        private final PostgreTablespace tablespace;
        private final Map<String, String> optionsAfter;

        OptionsAction(@NotNull PostgreTablespace tablespace, @NotNull String script, @NotNull Map<String, String> optionsAfter) {
            super("Change tablespace options", script);
            this.tablespace = tablespace;
            this.optionsAfter = optionsAfter;
        }

        @Override
        public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) throws DBCException {
            super.afterExecute(session, error);
            if (error == null) {
                tablespace.setSavedOptions(optionsAfter);
            }
        }
    }
}
