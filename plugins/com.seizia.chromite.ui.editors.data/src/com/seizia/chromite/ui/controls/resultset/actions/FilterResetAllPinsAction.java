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
package com.seizia.chromite.ui.controls.resultset.actions;

import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDAttributeConstraintBase;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;

public class FilterResetAllPinsAction extends AbstractResultSetViewerAction {

    public FilterResetAllPinsAction(ResultSetViewer resultSetViewer) {
        super(resultSetViewer, ResultSetMessages.controls_resultset_viewer_action_reset_all_pins);
    }

    @Override
    public void run() {
        execute(true);
    }

    void execute(boolean refresh) {
        for (DBDAttributeConstraint c : getResultSetViewer().getDataFilter().getConstraints()) {
            c.removeOption(DBDAttributeConstraintBase.ATTR_OPTION_PINNED);
        }
        if (refresh) {
            getResultSetViewer().redrawData(true, true);
        }
    }
}
