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
package com.seizia.chromite.ext.generic.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericObjectContainer;
import com.seizia.chromite.ext.generic.model.GenericProcedure;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.cache.ListCache;

import java.util.List;
import java.util.Map;

/**
 * Generic procedure manager
 */
public class GenericProcedureManager extends SQLObjectEditor<GenericProcedure, GenericStructContainer> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, GenericProcedure> getObjectsCache(GenericProcedure object) {
        GenericStructContainer container = object.getContainer();
        return new ListCache<>(((GenericObjectContainer) container).getProcedureCache());
    }

    @Override
    protected GenericProcedure createDatabaseObject(
        @NotNull DBRProgressMonitor monitor, @NotNull DBECommandContext context, @NotNull final Object container,
        @Nullable Object from, @NotNull Map<String, Object> options
    ) throws DBException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBCException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        GenericProcedure object = command.getObject();
        String procedureName;
        GenericMetaModel metaModel = object.getDataSource().getMetaModel();
        if (metaModel.supportsOverloadedProcedureNames()) {
            try {
                procedureName = object.getProcedureSignature(monitor, metaModel.showProcedureParamNames());
            } catch (DBException e) {
                log.debug("Can't read procedure/function parameters", e);
                procedureName = object.getFullyQualifiedName(DBPEvaluationContext.DDL);
            }
        } else {
            procedureName = object.getFullyQualifiedName(DBPEvaluationContext.DDL);
        }
        actions.add(
            new SQLDatabasePersistAction(
                ModelMessages.model_jdbc_drop_table,
                "DROP " + object.getProcedureType().name() +  //$NON-NLS-2$
                    " " + procedureName)
        );
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return false;
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericProcedure object) {
        return true;
    }

}
