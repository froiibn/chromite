/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.ui.navigator.INavigatorNodeActionHandler;

/**
 * Read-only action handler
 */
public abstract class NavigatorNodeActionHandlerAbstract implements INavigatorNodeActionHandler {

    @Override
    public boolean isEnabledFor(@NotNull DBNNode node) {
        return true;
    }

    @Override
    public boolean isSticky(@NotNull DBNNode node) {
        return true;
    }

    @Override
    @Nullable
    public DBPImage getNodeActionIcon(@NotNull DBNNode node) {
        return DBIcon.TYPE_UNKNOWN;
    }

    @Override
    @Nullable
    public String getNodeActionToolTip(@NotNull DBNNode node) {
        return null;
    }

}
