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
package com.seizia.chromite.ui.gis.panel;

import org.eclipse.jface.action.Action;
import com.seizia.chromite.model.gis.GisConstants;
import com.seizia.chromite.ui.gis.IGeometryValueEditor;
import com.seizia.chromite.ui.gis.internal.GISMessages;

class SetCRSAction extends Action {
    private final IGeometryValueEditor valueEditor;
    private final int srid;

    public SetCRSAction(IGeometryValueEditor valueEditor, int srid) {
        super(srid == GisConstants.SRID_SIMPLE ? GISMessages.panel_set_crs_action_text_simple : "EPSG:" + srid, AS_CHECK_BOX);
        this.valueEditor = valueEditor;
        this.srid = srid;
    }

    @Override
    public boolean isChecked() {
        return srid == valueEditor.getValueSRID();
    }

    @Override
    public void run() {
        valueEditor.setValueSRID(srid);
    }
}
