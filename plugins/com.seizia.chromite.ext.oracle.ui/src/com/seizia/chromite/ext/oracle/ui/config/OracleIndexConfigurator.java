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
package com.seizia.chromite.ext.oracle.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleTableColumn;
import com.seizia.chromite.ext.oracle.model.OracleTableIndex;
import com.seizia.chromite.ext.oracle.model.OracleTableIndexColumn;
import com.seizia.chromite.ext.oracle.ui.internal.OracleUIMessages;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditIndexPage;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * Oracle index manager
 */
public class OracleIndexConfigurator implements DBEObjectConfigurator<OracleTableIndex> {

    @NotNull
    @Override
    public OracleTableIndex configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull OracleTableIndex index, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditIndexPage editPage = new EditIndexPage(
                OracleUIMessages.edit_oracle_index_manager_dialog_title,
                index,
                Collections.singletonList(DBSIndexType.OTHER));
            if (!editPage.edit()) {
                return null;
            }

            StringBuilder idxName = new StringBuilder(64);
            idxName.append(CommonUtils.escapeIdentifier(index.getTable().getName())).append("_") //$NON-NLS-1$
                .append(CommonUtils.escapeIdentifier(editPage.getSelectedAttributes().iterator().next().getName()))
                .append("_IDX"); //$NON-NLS-1$
            index.setName(DBObjectNameCaseTransformer.transformName(index.getDataSource(), idxName.toString()));
            index.setUnique(editPage.isUnique());
            index.setIndexType(editPage.getIndexType());
            int colIndex = 1;
            for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                index.addColumn(
                    new OracleTableIndexColumn(
                        index,
                        (OracleTableColumn) tableColumn,
                        colIndex++,
                        !Boolean.TRUE.equals(editPage.getAttributeProperty(tableColumn, EditIndexPage.PROP_DESC)),
                        null));
            }
            return index;
        });
    }

}
