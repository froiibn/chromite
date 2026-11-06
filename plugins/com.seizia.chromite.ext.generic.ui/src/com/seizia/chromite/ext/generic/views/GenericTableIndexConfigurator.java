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

package com.seizia.chromite.ext.generic.views;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.ext.generic.model.GenericTableIndex;
import com.seizia.chromite.ext.generic.model.GenericTableIndexColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditIndexPage;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.Map;

/**
 * Generic table index configurator
 */
public class GenericTableIndexConfigurator implements DBEObjectConfigurator<GenericTableIndex> {

    @NotNull
    @Override
    public GenericTableIndex configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object table, @NotNull GenericTableIndex index, @NotNull Map<String, Object> options) {
        GenericTableBase tableBase = (GenericTableBase) table;
        boolean supportUniqueIndexes = tableBase.supportUniqueIndexes();
        Collection<DBSIndexType> tableIndexTypes = tableBase.getTableIndexTypes();
        return new UITask<GenericTableIndex>() {
            @Override
            protected GenericTableIndex runTask() {
                EditIndexPage editPage = new EditIndexPage(
                    "Create index",
                    index,
                    tableIndexTypes, supportUniqueIndexes);
                if (!editPage.edit()) {
                    return null;
                }
                index.setIndexType(editPage.getIndexType());
                StringBuilder idxName = new StringBuilder(64);
                idxName.append(CommonUtils.escapeIdentifier(index.getTable().getName()));
                int colIndex = 1;
                for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                    if (colIndex == 1) {
                        idxName.append("_").append(CommonUtils.escapeIdentifier(tableColumn.getName()));
                    }
                    index.addColumn(
                        new GenericTableIndexColumn(
                            index,
                            (GenericTableColumn) tableColumn,
                            colIndex++,
                            !Boolean.TRUE.equals(editPage.getAttributeProperty(tableColumn, EditIndexPage.PROP_DESC))));
                }
                idxName.append("_IDX");
                index.setName(DBObjectNameCaseTransformer.transformObjectName(index, idxName.toString()));
                index.setUnique(editPage.isUnique());
                return index;
            }
        }.execute();
    }

}
