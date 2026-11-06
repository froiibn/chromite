/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.vertica.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.edit.GenericSequenceManager;
import com.seizia.chromite.ext.generic.model.GenericSequence;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.vertica.model.VerticaSchema;
import com.seizia.chromite.ext.vertica.model.VerticaSequence;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VerticaSequenceManager extends GenericSequenceManager implements DBEObjectRenamer<GenericSequence> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Override
    protected GenericSequence createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        GenericStructContainer structContainer = (GenericStructContainer) container;
        VerticaSchema schema = (VerticaSchema) structContainer.getSchema();
        VerticaSequence sequence = new VerticaSequence(structContainer, getBaseObjectName().toLowerCase(Locale.ROOT));
        setNewObjectName(monitor, schema, sequence);
        return sequence;
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        final StringBuilder sequenceOptions = new StringBuilder();
        GenericSequence sequence = command.getObject();
        sequenceOptions.append("CREATE SEQUENCE ").append(sequence.getFullyQualifiedName(DBPEvaluationContext.DDL));
        addSequenceOptions(sequence, sequenceOptions, command.getProperties());
        actions.add(new SQLDatabasePersistAction(
            "Create sequence",
            sequenceOptions.toString()
        ));
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext, @NotNull GenericSequence object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
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
        GenericSequence sequence = command.getObject();
        actions.add(
            new SQLDatabasePersistAction(
                "Rename sequence",
                "ALTER SEQUENCE " + sequence.getFullyQualifiedName(DBPEvaluationContext.DDL) + //$NON-NLS-1$
                    " RENAME TO " + DBUtils.getQuotedIdentifier(sequence.getDataSource(), command.getNewName())) //$NON-NLS-1$
        );
    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) {
        GenericSequence sequence = command.getObject();
        final StringBuilder sequenceOptions = new StringBuilder();
        addSequenceOptions(sequence, sequenceOptions, command.getProperties());

        if (!sequenceOptions.isEmpty()) {
            actionList.add(new SQLDatabasePersistAction(
                "Alter sequence",
                "ALTER SEQUENCE " + sequence.getFullyQualifiedName(DBPEvaluationContext.DDL) + sequenceOptions
            ));
        }
    }

    private void addSequenceOptions(GenericSequence sequence, StringBuilder ddl, Map<Object, Object> options) {
        if (options.containsKey("incrementBy")) {
            ddl.append("\n\tINCREMENT BY ").append(options.get("incrementBy"));
        }
        if (options.containsKey("minValue")) {
            ddl.append("\n\tMINVALUE ").append(options.get("minValue"));
        }
        if (options.containsKey("maxValue")) {
            ddl.append("\n\tMAXVALUE ").append(options.get("maxValue"));
        }
        if (options.containsKey("lastValue")) {
            if (!sequence.isPersisted()) {
                ddl.append("\n\tSTART WITH ").append(options.get("lastValue"));
            } else {
                ddl.append("\n\tRESTART WITH ").append(options.get("lastValue"));
            }
        }
        if (options.containsKey("cacheCount")) {
            ddl.append("\n\tCACHE ").append(options.get("cacheCount"));
        }
        if (options.containsKey("cycle")) {
            ddl.append("\n\t");
            if (!CommonUtils.toBoolean(options.get("cycle"))) {
                ddl.append("NO ");
            }
            ddl.append("CYCLE");
        }
    }
}
