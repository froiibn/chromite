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
package com.seizia.chromite.ui.editors;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.internal.WorkbenchMessages;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceContainerProvider;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.ToolbarSeparatorContribution;
import com.seizia.chromite.ui.css.CSSUtils;
import com.seizia.chromite.ui.editors.internal.EditorsMessages;

/**
 * DB editor utils
 */
public class DatabaseEditorUtils {

    // Associates editor with datasource
    public static void setPartBackground(IEditorPart editor, Composite composite) {
        if (composite == null || composite.isDisposed()) {
            return;
        }
        CTabFolder tabFolder = null;
        Composite rootComposite = composite;
        for (Composite c = composite; c != null; c = c.getParent()) {
            if (!c.isDisposed() && c.getParent() instanceof CTabFolder) {
                tabFolder = (CTabFolder) c.getParent();
                //rootComposite = c;
                break;
            }
        }
        if (tabFolder != null) {
            tabFolder.setBorderVisible(false);
        }

        DBPDataSourceContainer dsContainer = null;
        if (editor instanceof DBPDataSourceContainerProvider dscp) {
            dsContainer = dscp.getDataSourceContainer();
        } else if (editor instanceof DBPContextProvider cp) {
            DBCExecutionContext context = cp.getExecutionContext();
            if (context != null) {
                dsContainer = context.getDataSource().getContainer();
            }
        }

        if (dsContainer == null) {
            rootComposite.setData(CSSUtils.DATABASE_EDITOR_COMPOSITE_DATASOURCE, null);
            rootComposite.setBackground(null);
        } else {
            Color bgColor = UIUtils.getConnectionColor(dsContainer.getConnectionConfiguration());

            rootComposite.setData(CSSUtils.DATABASE_EDITOR_COMPOSITE_DATASOURCE, dsContainer);
            rootComposite.setBackground(bgColor);
        }
    }

    public static void contributeStandardEditorActions(IWorkbenchSite workbenchSite, IContributionManager contributionManager) {
        contributionManager.add(ActionUtils.makeCommandContribution(
            workbenchSite,
            IWorkbenchCommandConstants.FILE_REFRESH,
            EditorsMessages.database_editor_command_refresh_name,
            UIIcon.REFRESH,
            EditorsMessages.database_editor_command_refresh_tip,
            true
        ));
        if (contributionManager instanceof ToolBarManager) {
            contributionManager.add(new ToolbarSeparatorContribution(true));
        }
        contributionManager.add(ActionUtils.makeCommandContribution(
            workbenchSite,
            IWorkbenchCommandConstants.FILE_SAVE,
            WorkbenchMessages.Save,
            UIIcon.SAVE,
            EditorsMessages.database_editor_command_save_tip,
            true
        ));
        contributionManager.add(ActionUtils.makeCommandContribution(
            workbenchSite,
            IWorkbenchCommandConstants.FILE_REVERT,
            WorkbenchMessages.Workbench_revert,
            UIIcon.RESET,
            EditorsMessages.database_editor_command_revert_tip,
            true
        ));
        if (contributionManager instanceof ToolBarManager) {
            contributionManager.add(new ToolbarSeparatorContribution(true));
        }
    }

}
