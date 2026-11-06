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
package com.seizia.chromite.ext.mssql.ui.config;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.model.SQLServerTableColumn;
import com.seizia.chromite.ext.mssql.model.SQLServerTableIndex;
import com.seizia.chromite.ext.mssql.model.SQLServerTableIndexColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.editors.object.struct.EditIndexPage;
import org.jkiss.utils.CommonUtils;

import java.util.Arrays;
import java.util.Map;

/**
 * SQL Server index configurator
 */
public class SQLServerIndexConfigurator implements DBEObjectConfigurator<SQLServerTableIndex> {

    private static final DBSIndexType[] SQLSERVER_INDEX_TYPES = {
            SQLServerConstants.INDEX_TYPE_DEFAULT,
            SQLServerConstants.INDEX_TYPE_NON_CLUSTERED,
            DBSIndexType.CLUSTERED
    };

    @Nullable
    @Override
    public SQLServerTableIndex configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull SQLServerTableIndex index, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            EditIndexPage editPage = new SQLServerEditIndexPage(index);
            if (!editPage.edit()) {
                return null;
            }
            index.setUnique(editPage.isUnique());
            index.setIndexType(editPage.getIndexType());
            index.setDescription(editPage.getDescription());
            StringBuilder idxName = new StringBuilder(64);
            idxName.append(CommonUtils.escapeIdentifier(index.getTable().getName()));
            int colIndex = 1;
            for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
                if (colIndex == 1) {
                    idxName.append("_").append(CommonUtils.escapeIdentifier(tableColumn.getName()));
                }
                index.addColumn(
                    new SQLServerTableIndexColumn(
                        index,
                        0,
                        (SQLServerTableColumn) tableColumn,
                        colIndex++,
                        !Boolean.TRUE.equals(editPage.getAttributeProperty(tableColumn, EditIndexPage.PROP_DESC)),
                        Boolean.TRUE.equals(editPage.getAttributeProperty(tableColumn, SQLServerEditIndexPage.PROP_INCLUDED))));
            }
            idxName.append("_IDX");
            index.setName(DBObjectNameCaseTransformer.transformObjectName(index, idxName.toString()));
            return index;
        });
    }

    private static class SQLServerEditIndexPage extends EditIndexPage {
        public static final String PROP_INCLUDED = "included";

        private int includedColumnIndex;

        public SQLServerEditIndexPage(@NotNull DBSTableIndex index) {
            super("Create index", index, Arrays.asList(SQLSERVER_INDEX_TYPES));
        }

        @Override
        protected void createAttributeColumns(@NotNull Table columnsTable) {
            super.createAttributeColumns(columnsTable);
            UIUtils.createTableColumn(columnsTable, SWT.NONE, "Included");
        }

        @Override
        protected int fillAttributeColumns(@NotNull DBSEntityAttribute attribute, @NotNull AttributeInfo attributeInfo, @NotNull TableItem columnItem) {
            includedColumnIndex = super.fillAttributeColumns(attribute, attributeInfo, columnItem) + 1;
            columnItem.setText(includedColumnIndex, Boolean.TRUE.equals(attributeInfo.getProperty(PROP_INCLUDED)) ? "YES" : "NO");
            return includedColumnIndex;
        }

        @Nullable
        @Override
        protected Control createCellEditor(@NotNull Table table, int index, @NotNull TableItem item, @NotNull AttributeInfo attributeInfo) {
            if (index == includedColumnIndex) {
                final boolean isIncluded = Boolean.TRUE.equals(attributeInfo.getProperty(PROP_INCLUDED));
                final CCombo combo = new CCombo(table, SWT.DROP_DOWN | SWT.READ_ONLY);
                combo.add("YES");
                combo.add("NO");
                combo.select(isIncluded ? 0 : 1);
                return combo;
            }
            return super.createCellEditor(table, index, item, attributeInfo);
        }

        @Override
        protected void saveCellValue(@NotNull Control control, int index, @NotNull TableItem item, @NotNull AttributeInfo attributeInfo) {
            if (index == includedColumnIndex) {
                final boolean isIncluded = ((CCombo) control).getSelectionIndex() == 0;
                item.setText(index, isIncluded ? "YES" : "NO");
                attributeInfo.setProperty(PROP_INCLUDED, isIncluded);
            } else {
                super.saveCellValue(control, index, item, attributeInfo);
            }
        }
    }
}
