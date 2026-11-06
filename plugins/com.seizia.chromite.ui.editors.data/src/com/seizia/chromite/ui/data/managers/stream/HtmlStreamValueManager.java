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
package com.seizia.chromite.ui.data.managers.stream;

import org.eclipse.ui.IEditorPart;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.ui.data.IStreamValueEditor;
import com.seizia.chromite.ui.data.IStreamValueManager;
import com.seizia.chromite.ui.data.IValueController;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.MimeTypes;

/**
 * HTML preview manager
 */
public class HtmlStreamValueManager implements IStreamValueManager {

    @NotNull
    @Override
    public MatchType matchesTo(@NotNull DBRProgressMonitor monitor, @NotNull DBSTypedObject attribute, @Nullable DBDContent value) {
        if (value != null && MimeTypes.TEXT_HTML.equalsIgnoreCase(value.getContentType())) {
            return MatchType.PRIMARY;
        }
        return ContentUtils.isTextContent(value) ? MatchType.APPLIES : MatchType.NONE;
    }

    @NotNull
    @Override
    public IStreamValueEditor createPanelEditor(@NotNull IValueController controller) {
        return new HtmlPanelEditor(controller);
    }

    @NotNull
    @Override
    public IEditorPart createEditorPart(@NotNull IValueController controller) {
        return new HtmlEditorPart(controller);
    }
}
