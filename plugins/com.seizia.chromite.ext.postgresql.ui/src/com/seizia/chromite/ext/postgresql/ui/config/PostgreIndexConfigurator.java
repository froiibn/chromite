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

package com.seizia.chromite.ext.postgresql.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.model.PostgreAttribute;
import com.seizia.chromite.ext.postgresql.model.PostgreIndex;
import com.seizia.chromite.ext.postgresql.model.PostgreIndexColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditIndexPage;
import org.jkiss.utils.CommonUtils;

import java.util.Arrays;
import java.util.Map;

/**
 * Postgre index configurator
 */
public class PostgreIndexConfigurator implements DBEObjectConfigurator<PostgreIndex> {


    @NotNull
    @Override
    public PostgreIndex configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object parent, @NotNull PostgreIndex index, @NotNull Map<String, Object> options) {
        return new UITask<PostgreIndex>() {
            @Override
            protected PostgreIndex runTask() {
                EditIndexPage editPage = new EditIndexPage(
                    "Edit index",
                    index,
                    Arrays.asList(DBSIndexType.OTHER, DBSIndexType.HASHED));
                if (!editPage.edit()) {
                    return null;
                }

                StringBuilder idxName = new StringBuilder(64);
                idxName.append(CommonUtils.escapeIdentifier(((DBSTable)parent).getName()));
                index.setName(idxName.toString());
                index.setIndexType(editPage.getIndexType());
                index.setUnique(editPage.isUnique());
                int colIndex = 1;
                for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                    if (colIndex == 1) {
                        idxName.append("_").append(CommonUtils.escapeIdentifier(tableColumn.getName())); //$NON-NLS-1$
                    }
                    index.addColumn(
                        new PostgreIndexColumn(
                            index,
                            (PostgreAttribute) tableColumn,
                            null,
                            colIndex++,
                            !Boolean.TRUE.equals(editPage.getAttributeProperty(tableColumn, EditIndexPage.PROP_DESC)),
                            -1,
                            false));
                }
                idxName.append("_IDX"); //$NON-NLS-1$
                index.setName(DBObjectNameCaseTransformer.transformObjectName(index, idxName.toString()));
                return index;
            }
        }.execute();
    }

}
