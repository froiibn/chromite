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
package com.seizia.chromite.ext.exasol.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.ExasolConstants;
import com.seizia.chromite.ext.exasol.model.ExasolTable;
import com.seizia.chromite.ext.exasol.model.ExasolTableUniqueKey;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.struct.SQLConstraintManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

import java.util.List;
import java.util.Map;

public class ExasolPrimaryKeyManager
		extends SQLConstraintManager<ExasolTableUniqueKey, ExasolTable> 
		implements DBEObjectRenamer<ExasolTableUniqueKey>{

	@Override
	public DBSObjectCache<? extends DBSObject, ExasolTableUniqueKey> getObjectsCache(
			ExasolTableUniqueKey object)
	{
		return object.getTable().getSchema().getConstraintCache();
	}

	@Override
	protected ExasolTableUniqueKey createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
	) throws DBException
	{
		return new ExasolTableUniqueKey(
			(ExasolTable) container,
			DBSEntityConstraintType.PRIMARY_KEY,
			true,
			"CONSTRAINT"
		);		
	}
	
	@Override
	protected String getDropConstraintPattern(ExasolTableUniqueKey constraint)
	{
		return "ALTER TABLE " + constraint.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) + " DROP PRIMARY KEY";
	}
	
	@Override
	protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
										  @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options)
	{
		ExasolTableUniqueKey obj = (ExasolTableUniqueKey) command.getObject();
		try {
			actions.add(new SQLDatabasePersistAction("Create PK", ExasolUtils.getPKDdl(obj, monitor)));
		} catch (DBException e) {
			log.error("Could not generated DDL for PK");
		}
	}

	@Override
	public void renameObject(@NotNull DBECommandContext commandContext,
							 @NotNull ExasolTableUniqueKey object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException
	{
		processObjectRename(commandContext, object, options, newName);
		
	}
	
	@Override
	protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList,
										  @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
	{
		final ExasolTableUniqueKey constraint = command.getObject();
		
		if (command.getProperties().containsKey(DBConstants.PROP_ID_ENABLED))
		{
			actionList.add(
					new SQLDatabasePersistAction("Alter PK",
							"ALTER TABLE " + constraint.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) + 
							" MODIFY CONSTRAINT " + constraint.getName() + " " +
							(constraint.getEnabled() ? ExasolConstants.KEYWORD_ENABLE : ExasolConstants.KEYWORD_DISABLE)
							)
					);
		}
	}
	
	
	@Override
	protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
										  @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options)
	{
		final ExasolTableUniqueKey key = command.getObject();
		actions.add(
				new SQLDatabasePersistAction(
					"Rename PK", 
					"ALTER TABLE " + DBUtils.getObjectFullName(key.getTable(),DBPEvaluationContext.DDL) + " RENAME CONSTRAINT " 
					+ DBUtils.getQuotedIdentifier(key.getDataSource(),command.getOldName()) + " to " +
					DBUtils.getQuotedIdentifier(key.getDataSource(), command.getNewName())
				)
		);

	}
	

}
