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
package com.seizia.chromite.ext.vertica.ui.views;

import org.eclipse.jface.dialogs.IDialogPage;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.views.GenericConnectionPage;
import com.seizia.chromite.ui.dialogs.connection.DriverPropertiesDialogPage;

public class VerticaConnectionPage extends GenericConnectionPage {

    @Nullable
    @Override
    public IDialogPage[] getDialogPages(boolean extrasOnly, boolean forceCreate) {
        return new IDialogPage[] {
            new VerticaConnectionPageAdvanced(),
            new DriverPropertiesDialogPage(this)
        };
    }
}
