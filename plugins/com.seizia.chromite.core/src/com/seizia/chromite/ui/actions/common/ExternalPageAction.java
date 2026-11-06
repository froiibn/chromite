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
package com.seizia.chromite.ui.actions.common;

import org.eclipse.jface.action.Action;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIUtils;

public class ExternalPageAction extends Action {
    private final String url;

    public ExternalPageAction(String title, DBIcon icon, String url) {
        super(title, SwtIcons.getImageDescriptor(icon));
        this.url = url;
    }

    @Override
    public void run() {
        UIUtils.openWebBrowser(url);
    }
}
