/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.ui.CopyMode;
import com.seizia.chromite.ui.internal.UINavigatorMessages;
import com.seizia.chromite.ui.navigator.NavigatorUtils;
import com.seizia.chromite.utils.RuntimeUtils;

public class NavigatorHandlerCopyObject extends NavigatorHandlerCopyAbstract {

    @Override
    protected CopyMode getCopyMode() {
        return CopyMode.DEFAULT;
    }

    @Override
    protected String getObjectDisplayString(Object object)
    {
        DBPNamedObject adapted = RuntimeUtils.getObjectAdapter(object, DBPNamedObject.class);
        if (adapted != null) {
            return DBUtils.getObjectShortName(adapted);
        } else {
            return null;
        }
    }

    @Override
    protected String getSelectionTitle(IStructuredSelection selection)
    {
        if (selection.size() > 1) {
            return UINavigatorMessages.actions_navigator_copy_object_copy_objects;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node != null) {
            return NLS.bind(UINavigatorMessages.actions_navigator_copy_object_copy_node, node.getNodeTypeLabel());
        }
        return null;
    }

}
