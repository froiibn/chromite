/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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

import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;
import com.seizia.chromite.ui.controls.resultset.colors.ResetAllColorAction;
import com.seizia.chromite.ui.controls.resultset.handler.ResultSetHandlerMain;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;
import com.seizia.chromite.ui.controls.resultset.spreadsheet.SpreadsheetCommandHandler;

public class FilterResetAllSettingsAction extends AbstractResultSetViewerAction {

    public FilterResetAllSettingsAction(ResultSetViewer resultSetViewer) {
        super(resultSetViewer, ResultSetMessages.controls_resultset_viewer_action_reset_all_settings);
    }

    @Override
    public void run() {
        ResultSetViewer viewer = getResultSetViewer();
        viewer.clearDataFilter(false);
        if (viewer.getDataFilter().hasHiddenAttributes()) {
            ActionUtils.runCommand(SpreadsheetCommandHandler.CMD_SHOW_COLUMNS, viewer.getSite());
        }
        if (viewer.hasColorOverrides()) {
            new ResetAllColorAction(viewer).execute(false);
        }
        if (viewer.hasColumnTransformers()) {
            new FilterResetAllTransformersAction(viewer).execute(false);
        }
        if (viewer.getDataFilter().hasPinnedAttributes()) {
            new FilterResetAllPinsAction(viewer).execute(false);
        }
        viewer.refreshData(null);
    }
}
