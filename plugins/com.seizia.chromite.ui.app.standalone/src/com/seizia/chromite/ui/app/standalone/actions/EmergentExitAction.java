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
package com.seizia.chromite.ui.app.standalone.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.ui.IWorkbenchWindow;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.app.standalone.internal.CoreApplicationMessages;

public class EmergentExitAction extends Action {

    private IWorkbenchWindow window;

    public EmergentExitAction(IWorkbenchWindow window) {
        super(CoreApplicationMessages.actions_menu_exit_emergency);
        setId(getClass().getName());
        this.window = window;
    }

    @Override
    public void run() {
        if (UIUtils.confirmAction(
            window == null ? null : window.getShell(),
            CoreApplicationMessages.actions_menu_exit_emergency,
            CoreApplicationMessages.actions_menu_exit_emergency_message,
            DBIcon.STATUS_WARNING)) {
            System.exit(1);
        }
    }

}