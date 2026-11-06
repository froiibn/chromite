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
package com.seizia.chromite.ui.controls.resultset.handler;

import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.resultset.IResultSetController;
import com.seizia.chromite.ui.controls.resultset.ResultSetPreferences;

import java.util.Map;

/**
 * ResultSetHandlerToggleSaveConfirmation
 */
public class ResultSetHandlerToggleSaveConfirmation extends ResultSetHandlerMain implements IElementUpdater {

    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchPart workbenchPart = UIUtils.getActiveWorkbenchWindow().getActivePage().getActivePart();
        if (workbenchPart != null) {
            IResultSetController resultSet = ResultSetHandlerMain.getActiveResultSet(workbenchPart);
            if (resultSet != null) {
                element.setChecked(resultSet.getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_SET_CONFIRM_BEFORE_SAVE));
            }
        }
    }
}