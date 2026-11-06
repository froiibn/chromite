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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.editors.sql.SQLEditor;
import com.seizia.chromite.ui.editors.sql.SQLPreferenceConstants;
import com.seizia.chromite.utils.PrefUtils;

import java.util.Map;

public class SQLEditorHandlerEnableDisableFolding extends AbstractHandler implements IElementUpdater {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor instanceof SQLEditor) {
            DBPPreferenceStore preferenceStore  = ((SQLEditor) editor).getActivePreferenceStore();
            boolean previousValue = preferenceStore.getBoolean(SQLPreferenceConstants.FOLDING_ENABLED);
            preferenceStore.setValue(SQLPreferenceConstants.FOLDING_ENABLED, !previousValue);
            PrefUtils.savePreferenceStore(preferenceStore);
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        IEditorPart editor = element.getServiceLocator().getService(IWorkbenchWindow.class).getActivePage().getActiveEditor();
        if (editor instanceof SQLEditor) {
            element.setChecked(((SQLEditor) editor).getActivePreferenceStore().getBoolean(SQLPreferenceConstants.FOLDING_ENABLED));    
        }
    }
}
