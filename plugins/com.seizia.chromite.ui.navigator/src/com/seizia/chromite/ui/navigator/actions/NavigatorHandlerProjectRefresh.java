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
package com.seizia.chromite.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.runtime.DefaultProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ChromiteNotifications;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;

public class NavigatorHandlerProjectRefresh extends NavigatorHandlerObjectBase {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final IWorkbenchWindow workbenchWindow = HandlerUtil.getActiveWorkbenchWindow(event);
        if (CommonUtils.toBoolean(event.getParameter("singleProject"))) {
            NavigatorHandlerRefresh.refreshInNavigator(
                event,
                HandlerUtil.getActivePart(event),
                true);
            return null;
        }
        try {
            workbenchWindow.run(true, true, monitor -> {
                try {
                    DBPPlatformDesktop.getInstance().getWorkspace().refreshWorkspaceContents(new DefaultProgressMonitor(monitor));
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
                ChromiteNotifications.showNotification(
                    ChromiteNotifications.NT_GENERIC,
                    "Projects refresh",
                    "Project list was synchronized with local file system",
                    DBPMessageType.INFORMATION,
                    null);
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError("Refresh workspace", "Can't refresh workspace", e.getTargetException());
        } catch (InterruptedException e) {
            // do nothing
        }
        return null;
    }

}