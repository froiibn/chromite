/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ui.gis.panel;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogConstants;
import com.seizia.chromite.model.gis.DBGeometry;
import com.seizia.chromite.model.gis.GisConstants;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.MenuCreator;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.gis.IGeometryValueEditor;
import com.seizia.chromite.ui.gis.internal.GISMessages;
import com.seizia.chromite.ui.gis.preferences.PrefPageGIS;

import java.util.Arrays;
import java.util.List;

class SelectCRSAction extends Action {

    private IGeometryValueEditor valueEditor;

    SelectCRSAction(IGeometryValueEditor valueEditor) {
        super(valueEditor.getValueSRID() == GisConstants.SRID_SIMPLE ? GisConstants.LL_CRS_SIMPLE : "EPSG:" + valueEditor.getValueSRID(), Action.AS_DROP_DOWN_MENU);
        setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.CHART_LINE));
        this.valueEditor = valueEditor;
    }

    @Override
    public void run() {
        if (valueEditor instanceof GISLeafletViewer &&
                Arrays.stream(((GISLeafletViewer) valueEditor).getCurrentValue()).map(DBGeometry::getSRID).distinct().count() > 1) {
            DBWorkbench.getPlatformUI().showWarningMessageBox("Warning", "Can't change coordinate reference system because geometries have different SRID.");
        } else {
            SelectSRIDDialog manageCRSDialog = new SelectSRIDDialog(
                    UIUtils.getActiveWorkbenchShell(),
                    valueEditor.getValueSRID());
            if (manageCRSDialog.open() == IDialogConstants.OK_ID) {
                valueEditor.setValueSRID(manageCRSDialog.getSelectedSRID());
            }
        }
    }

    @Override
    public IMenuCreator getMenuCreator() {
        return new MenuCreator(control -> {
            MenuManager menuManager = new MenuManager();
            menuManager.setRemoveAllWhenShown(true);
            menuManager.addMenuListener(manager -> {
                menuManager.add(new SetCRSAction(valueEditor, GisConstants.SRID_4326));
                menuManager.add(new SetCRSAction(valueEditor, GisConstants.SRID_3857));
                menuManager.add(new SetCRSAction(valueEditor, GisConstants.SRID_3395));
                menuManager.add(new SetCRSAction(valueEditor, GisConstants.SRID_SIMPLE));
                menuManager.add(new Separator());
                List<Integer> recentSRIDs = GISEditorUtils.getRecentSRIDs();
                if (!recentSRIDs.isEmpty()) {
                    for (Integer recentSRID : recentSRIDs) {
                        menuManager.add(new SetCRSAction(valueEditor, recentSRID));
                    }
                    menuManager.add(new Separator());
                }
                menuManager.add(new Action(GISMessages.panel_select_crs_action_menu_manager_other) {
                    @Override
                    public void run() {
                        SelectCRSAction.this.run();
                    }
                });
                menuManager.add(new Action(GISMessages.panel_select_crs_action_menu_manager_config) {
                    @Override
                    public void run() {
                        UIUtils.showPreferencesFor(null, null, PrefPageGIS.PAGE_ID);
                    }
                });
            });
            return menuManager;
        });
    }

}
