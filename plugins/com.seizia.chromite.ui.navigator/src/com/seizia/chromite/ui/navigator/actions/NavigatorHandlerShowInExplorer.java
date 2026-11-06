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
package com.seizia.chromite.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.ui.ShellUtils;
import com.seizia.chromite.utils.GeneralUtils;

/**
 * Opens the selected resource in the system file manager.
 *
 * 변경 이력:
 * - 2026-11-07 Microsoft Store 제품 삭제: 유일한 구현(WinStoreEnvPathMapper)과 함께 IEnvironmentPathMapper 경로 변환을 지웠다.
 */
public class NavigatorHandlerShowInExplorer extends NavigatorHandlerObjectBase {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final IStructuredSelection structSelection = (IStructuredSelection) HandlerUtil.getCurrentSelection(event);
        final Object element = structSelection.getFirstElement();
        IResource resource = GeneralUtils.adapt(element, IResource.class);
        if (resource != null) {
            IPath location = resource.getLocation();
            if (location != null) {
                ShellUtils.showInSystemExplorer(location.toString());
            }
        }
        return null;
    }
}
