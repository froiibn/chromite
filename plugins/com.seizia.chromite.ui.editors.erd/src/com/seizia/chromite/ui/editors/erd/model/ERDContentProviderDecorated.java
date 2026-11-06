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
package com.seizia.chromite.ui.editors.erd.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.erd.*;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.editors.erd.ERDUIConstants;
import com.seizia.chromite.ui.editors.erd.editor.ERDViewStyle;
import com.seizia.chromite.ui.editors.erd.internal.ERDUIActivator;

import java.util.List;

/**
 * ERD content provider decorated
 */
public class ERDContentProviderDecorated extends ERDContentProviderDefault {

    private static final Log log = Log.getLog(ERDContentProviderDecorated.class);

    public ERDContentProviderDecorated() {
    }

    @Override
    public void fillEntityFromObject(@NotNull DBRProgressMonitor monitor, @NotNull ERDDiagram diagram,
                                     @NotNull List<ERDEntity> otherEntities, @NotNull ERDEntity erdEntity) throws DBCException {
        ERDAttributeVisibility attributeVisibility = ERDAttributeVisibility.ALL;
        boolean alphabeticalOrder = false;
        if (diagram instanceof ERDContainerDecorated decoratedDiagram) {
            attributeVisibility = decoratedDiagram.getDecorator().supportsAttributeVisibility() ?
                erdEntity.getAttributeVisibility() : ERDAttributeVisibility.ALL;
            if (attributeVisibility == null) {
                EntityDiagram.NodeVisualInfo visualInfo = decoratedDiagram.getVisualInfo(erdEntity.getObject(), false);
                if (visualInfo != null) {
                    attributeVisibility = visualInfo.attributeVisibility;
                }
                if (attributeVisibility == null) {
                    attributeVisibility = decoratedDiagram.getAttributeVisibility();
                }
            }
            alphabeticalOrder = decoratedDiagram.hasAttributeStyle(ERDViewStyle.ALPHABETICAL_ORDER);
        }
        DBPPreferenceStore store = ERDUIActivator.getDefault().getPreferences();
        boolean loadLazyDescriptions = store.getBoolean(ERDUIConstants.PREF_LOAD_LAZY_DESCRIPTIONS);
        fillEntityFromObject(monitor, diagram, otherEntities, erdEntity, new ERDAttributeSettings(attributeVisibility, alphabeticalOrder, loadLazyDescriptions));
    }

}
