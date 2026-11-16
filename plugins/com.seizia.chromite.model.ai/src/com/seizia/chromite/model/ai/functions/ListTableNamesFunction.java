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
package com.seizia.chromite.model.ai.functions;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.AIFunction;
import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.AIFunctionVerifier;
import com.seizia.chromite.model.ai.engine.AIDatabaseContext;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ListTableNamesFunction implements AIFunction, AIFunctionVerifier {

    @NotNull
    @Override
    public FunctionState getFunctionState(
        @NotNull AIFunctionContext context,
        @NotNull AIFunctionDescriptor function
    ) {
        return context.getContext() == null ? FunctionState.NOT_APPLICABLE : FunctionState.APPLICABLE;
    }

    @NotNull
    @Override
    public AIFunctionResult callFunction(
        @NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters
    ) throws DBException {
        AIDatabaseContext aiDatabaseContext = context.getContext();
        if (aiDatabaseContext == null) {
            throw new DBException("No database connection");
        }
        DBCExecutionContext executionContext = aiDatabaseContext.getExecutionContext();
        DBPDataSource dataSource = executionContext.getDataSource();
        if (!(dataSource instanceof DBSObjectContainer objectContainer)) {
            throw new DBException("Schemas are not supported by driver");
        }

        String catalogName = CommonUtils.toString(parameters.get(AIFunctionParams.CATALOG_PARAM), null);
        String schemaNamesParam = CommonUtils.toString(parameters.get(AIFunctionParams.SCHEMAS_PARAM), null);

        Class<? extends DBSObject> dsChildType = objectContainer.getPrimaryChildType(context.getMonitor());
        if (DBSEntity.class.isAssignableFrom(dsChildType)) {
            // Ignore catalog and schema. LLM may hallucinate
            catalogName = null;
            schemaNamesParam = null;
        } else if (DBSSchema.class.isAssignableFrom(dsChildType)) {
            // Ignore schema. LLM may hallucinate
            catalogName = null;
        }

        String[] schemaNames;
        if (CommonUtils.isEmpty(schemaNamesParam)) {
            schemaNames = new String[] { "" };
        } else {
            schemaNames = schemaNamesParam.split(",");
        }
        StringBuilder result = new StringBuilder();

        for (String schemaName : schemaNames) {
            DBSObject container = DBUtils.getObjectByPath(
                context.getMonitor(),
                executionContext,
                objectContainer,
                catalogName,
                schemaName,
                null
            );
            if (container == null) {
                container = objectContainer;
            }
            if (!(container instanceof DBSObjectContainer targetContainer)) {
                throw new DBException("Table container not found in the database");
            }
            if (!DBSEntity.class.isAssignableFrom(targetContainer.getPrimaryChildType(context.getMonitor()))) {
                throw new DBException("Container '" + targetContainer.getName() + "' doesn't support tables");
            }
            if (!AIUtils.isObjectInScope(aiDatabaseContext, executionContext, targetContainer)) {
                throw new DBException("Container '" + targetContainer.getName() + "' is not in scope. Access restricted.");
            }

            Map<DBSEntityType, List<DBSEntity>> entities = new LinkedHashMap<>();
            for (DBSObject child : CommonUtils.safeCollection(targetContainer.getChildren(context.getMonitor()))) {
                if (!(child instanceof DBSEntity entity)) {
                    continue;
                }
                if (!AIUtils.isObjectInScope(aiDatabaseContext, executionContext, child)) {
                    continue;
                }
                entities.computeIfAbsent(entity.getEntityType(), dbsEntityType -> new ArrayList<>()).add(entity);
            }

            if (entities.isEmpty()) {
                continue;
            }

            for (Map.Entry<DBSEntityType, List<DBSEntity>> e : entities.entrySet()) {
                result.append(e.getKey().getName()).append(" names:");
                List<DBSEntity> value = e.getValue();
                for (int i = 0; i < value.size(); i++) {
                    if (i > 0) {
                        result.append(',');
                    }
                    result.append(DBUtils.getObjectFullName(dataSource, value.get(i), DBPEvaluationContext.DML));
                }
                result.append("\n");
            }
        }
        if (result.isEmpty()) {
            return new AIFunctionResult(
                AIFunctionType.INFORMATION,
                "There are no tables in " + objectContainer.getName()
            );
        }
        return new AIFunctionResult(AIFunctionType.INFORMATION, result.toString());
    }
}
