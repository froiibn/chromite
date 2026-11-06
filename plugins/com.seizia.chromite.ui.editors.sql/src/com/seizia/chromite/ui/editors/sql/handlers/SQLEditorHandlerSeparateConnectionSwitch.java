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
package com.seizia.chromite.ui.editors.sql.handlers;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ModelPreferences.SeparateConnectionBehavior;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.AbstractDataSourceHandler;
import com.seizia.chromite.ui.editors.sql.SQLPreferenceConstants;
import com.seizia.chromite.ui.editors.sql.internal.SQLEditorMessages;
import com.seizia.chromite.ui.navigator.INavigatorModelView;
import com.seizia.chromite.ui.navigator.NavigatorUtils;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.PrefUtils;

import java.util.ArrayList;
import java.util.List;

public class SQLEditorHandlerSeparateConnectionSwitch extends CompoundContributionItem {

    @NotNull
    @Override
    protected IContributionItem[] getContributionItems() {
        DBPPreferenceStore preferenceStore = getPreferenceStore();
        if (preferenceStore == null) {
            Action placeholder = new Action(
                SQLEditorMessages.sql_editor_separate_connection_no_editor_or_ds_selected,
                Action.AS_UNSPECIFIED
            ) {
                @Override
                public void run() {
                    // do nothing
                }
            };
            placeholder.setEnabled(false);
            return new IContributionItem[] { new ActionContributionItem(placeholder) };
        }
        
        List<SeparateConnectionBehavior> behaviors = List.of(
            SeparateConnectionBehavior.ALWAYS,
            SeparateConnectionBehavior.DEFAULT,
            SeparateConnectionBehavior.NEVER
        );
        SeparateConnectionBehavior currValue = SeparateConnectionBehavior.parse(
            preferenceStore.getString(SQLPreferenceConstants.EDITOR_SEPARATE_CONNECTION)
        );
        
        List<IContributionItem> items = new ArrayList<>(behaviors.size());
        for (final SeparateConnectionBehavior behavior : behaviors) {
            Action action = new Action(behavior.getTitle(), Action.AS_RADIO_BUTTON) {
                @Override
                public void run() {
                    preferenceStore.setValue(SQLPreferenceConstants.EDITOR_SEPARATE_CONNECTION, behavior.name());
                    PrefUtils.savePreferenceStore(preferenceStore);
                }
            };
            if (currValue.equals(behavior)) {
                action.setChecked(true);
            }
            items.add(new ActionContributionItem(action));
        }
        
        return items.toArray(new IContributionItem[behaviors.size()]);
    }

    @Nullable
    private DBPPreferenceStore getPreferenceStore() {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        IWorkbenchPage page = window.getActivePage();
        if (page != null) {

            DBPDataSourceContainer dsContainer = AbstractDataSourceHandler.getDataSourceContainerFromPart(page.getActiveEditor());
            if (dsContainer != null) { 
                return dsContainer.getPreferenceStore();
            } 
            
            INavigatorModelView navigatorView = GeneralUtils.adapt(page.getActivePart(), INavigatorModelView.class);
            if (navigatorView != null) {
                ISelection selection = navigatorView.getNavigatorViewer().getSelection();
                if (selection != null) {
                    DBSObject selectedObject = NavigatorUtils.getSelectedObject(selection);
                    if (selectedObject instanceof DBPDataSourceContainer) {
                        return ((DBPDataSourceContainer) selectedObject).getPreferenceStore();
                    } else if (selectedObject != null) {
                        DBPDataSource dataSource = selectedObject.getDataSource();
                        if (dataSource != null) {
                            return dataSource.getContainer().getPreferenceStore();
                        }
                    }
                }
            }
        } 
        return null;
    }
}
