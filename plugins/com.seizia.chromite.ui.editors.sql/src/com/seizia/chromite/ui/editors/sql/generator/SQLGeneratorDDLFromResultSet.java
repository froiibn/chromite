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
package com.seizia.chromite.ui.editors.sql.generator;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.generator.SQLGenerator;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.tools.transfer.database.DatabaseConsumerSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingContainer;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingType;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferUtils;
import com.seizia.chromite.ui.controls.resultset.IResultSetController;
import com.seizia.chromite.ui.controls.resultset.ResultSetDataContainer;
import com.seizia.chromite.ui.controls.resultset.ResultSetDataContainerOptions;
import com.seizia.chromite.ui.editors.sql.internal.SQLEditorMessages;
import org.jkiss.utils.ArrayUtils;

import java.util.Collections;

public class SQLGeneratorDDLFromResultSet extends SQLGenerator<IResultSetController> {

    @Override
    public boolean hasOptions() {
        return false;
    }

    @Override
    protected void generateSQL(@NotNull DBRProgressMonitor monitor, @NotNull StringBuilder sql, @NotNull IResultSetController object)
            throws DBException {
        ResultSetDataContainer dataContainer = new ResultSetDataContainer(object, new ResultSetDataContainerOptions());
        DatabaseMappingContainer mapping = new DatabaseMappingContainer(new DatabaseConsumerSettings(), dataContainer);
        mapping.refreshMappingType(monitor, DatabaseMappingType.create, true, true);
        
        DBPDataSource dataSource = object.getDataContainer().getDataSource();
        if (dataSource.getInfo().isDynamicMetadata()) {
            sql.append(SQLEditorMessages.sql_generator_nonsql_text);
            return;
        }

        DBSDataContainer container = object.getModel().getAttributes()[0].getDataContainer();
        DBSObjectContainer objContainer = null;
        for (DBSObject obj = container; obj != null; obj = obj.getParentObject()) {
            if (obj instanceof DBSObjectContainer dbsObjectContainer) {
                objContainer = dbsObjectContainer;
            }
        }
        if (objContainer == null && dataSource instanceof DBSObjectContainer dbsObjectContainer) {
            objContainer = dbsObjectContainer;
        }
        if (objContainer == null) {
            sql.append(SQLEditorMessages.sql_generator_no_obj_container_text);
            return;
        }

        while (!DBSEntity.class.isAssignableFrom(objContainer.getPrimaryChildType(monitor))) {
            objContainer = (DBSObjectContainer) objContainer.getChildren(monitor).iterator().next();
        }
        
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(dataSource, true);
        DBEPersistAction[] ddl = DatabaseTransferUtils.generateTargetTableDDL(
            monitor,
            executionContext,
            objContainer,
            mapping,
            Collections.emptyMap()
        );

        if (ArrayUtils.isEmpty(ddl)) {
            sql.append(SQLEditorMessages.sql_generator_no_ddl_text);
            return;
        }
        
        String text = SQLUtils.generateScript(dataSource, ddl, true);
        sql.append(text);
    }
}
