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
package com.seizia.chromite.ext.exasol.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.exasol.model.ExasolTableColumn;
import com.seizia.chromite.ext.exasol.model.ExasolTableIndex;
import com.seizia.chromite.ext.exasol.model.ExasolTableIndexColumn;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.EditIndexPage;

import java.util.Arrays;
import java.util.Map;


public class ExasolIndexConfigurator implements DBEObjectConfigurator<ExasolTableIndex> {


	@NotNull
    @Override
	public ExasolTableIndex configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull ExasolTableIndex index, @NotNull Map<String, Object> options) {
		return UITask.run(() -> {
				EditIndexPage editPage = new EditIndexPage(
						"create index",
						index,
						Arrays.asList(new DBSIndexType("LOCAL","LOCAL"), new DBSIndexType("GLOBAL","GLOBAL")),
						false
					);
				if (!editPage.edit()) {
					return null;
				}
				
				index.setIndexType(editPage.getIndexType());
				int colIndex = 1;
				for (DBSEntityAttribute tableColumn : editPage.getSelectedAttributes()) {
					index.addColumn(
								new ExasolTableIndexColumn(index, (ExasolTableColumn) tableColumn, colIndex++)
							);
				}
				index.setName(index.getIndexType().getName() + " INDEX " + index.getSimpleColumnString());
				return index;
			});
		}


}
