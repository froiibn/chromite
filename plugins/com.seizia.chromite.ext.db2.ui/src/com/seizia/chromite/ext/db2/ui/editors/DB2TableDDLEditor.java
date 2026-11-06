/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.db2.ui.editors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import com.seizia.chromite.ext.db2.model.DB2Table;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.editors.sql.SQLSourceViewer;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

public class DB2TableDDLEditor extends SQLSourceViewer<DB2Table> {

    public DB2TableDDLEditor() {
    }

    @Override
    protected void contributeEditorCommands(IContributionManager toolBarManager) {
        super.contributeEditorCommands(toolBarManager);

        toolBarManager.add(new Separator());
        toolBarManager.add(ActionUtils.makeActionContribution(
            new Action("Include views", Action.AS_CHECK_BOX) {
                {
                    setImageDescriptor(SwtIcons.getImageDescriptor(DBIcon.TREE_VIEW));
                    setToolTipText("Include views to the DDL");
                    setChecked(viewsIncluded());
                }

                @Override
                public void run() {
                    getDatabaseEditorInput().setAttribute(DBPScriptObject.OPTION_SCRIPT_INCLUDE_VIEWS, isChecked());
                    refreshPart(this, true);
                }
            }, true));

    }

    @Override
    protected Map<String, Object> getSourceOptions() {
        Map<String, Object> options = super.getSourceOptions();
        options.put(DBPScriptObject.OPTION_SCRIPT_INCLUDE_VIEWS, viewsIncluded());
        return options;
    }

    private boolean viewsIncluded() {
        return CommonUtils.getBoolean(
            getDatabaseEditorInput().getAttribute(DBPScriptObject.OPTION_SCRIPT_INCLUDE_VIEWS), true);
    }
}
