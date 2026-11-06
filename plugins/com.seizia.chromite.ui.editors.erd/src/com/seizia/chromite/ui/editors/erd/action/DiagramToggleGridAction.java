/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
/*
 * Created on Jul 23, 2004
 */
package com.seizia.chromite.ui.editors.erd.action;

import org.eclipse.jface.action.Action;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.editors.erd.ERDIcon;
import com.seizia.chromite.ui.editors.erd.ERDUIConstants;
import com.seizia.chromite.ui.editors.erd.internal.ERDUIActivator;
import com.seizia.chromite.ui.editors.erd.internal.ERDUIMessages;
import com.seizia.chromite.utils.PrefUtils;

/**
 * Action to toggle the layout between manual and automatic
 *
 * @author Serge Rider
 */
public class DiagramToggleGridAction extends Action {

    public DiagramToggleGridAction() {
        super(ERDUIMessages.erd_editor_control_action_toggle_grid, AS_CHECK_BOX);
        setImageDescriptor(SwtIcons.getImageDescriptor(ERDIcon.LAYER_GRID));
        setChecked(ERDUIActivator.getDefault().getPreferences().getBoolean(ERDUIConstants.PREF_GRID_ENABLED));
    }

    @Override
    public void run() {
        final DBPPreferenceStore store = ERDUIActivator.getDefault().getPreferences();
        store.setValue(ERDUIConstants.PREF_GRID_ENABLED, isChecked());
        PrefUtils.savePreferenceStore(store);
    }

}