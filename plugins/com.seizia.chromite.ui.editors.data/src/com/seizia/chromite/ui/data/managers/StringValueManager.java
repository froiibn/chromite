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
package com.seizia.chromite.ui.data.managers;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.data.DBDContentCached;
import com.seizia.chromite.ui.controls.resultset.ResultSetPreferences;
import com.seizia.chromite.ui.data.IValueController;
import com.seizia.chromite.ui.data.IValueEditor;
import com.seizia.chromite.ui.data.dialogs.TextViewDialog;
import com.seizia.chromite.ui.data.editors.ContentInlineEditor;
import com.seizia.chromite.ui.data.editors.ContentPanelEditor;
import com.seizia.chromite.ui.data.editors.StringInlineEditor;
import com.seizia.chromite.ui.editors.content.ContentEditor;
import com.seizia.chromite.utils.ContentUtils;

import java.util.Collection;

/**
 * String value manager
 */
public class StringValueManager extends ContentValueManager {

    private static final long PLAIN_STRING_MAX_LENGTH = 32;

    @NotNull
    @Override
    public IValueController.EditType[] getSupportedEditTypes() {
        return new IValueController.EditType[] {IValueController.EditType.INLINE, IValueController.EditType.PANEL, IValueController.EditType.EDITOR};
    }

    @Override
    public IValueEditor createEditor(@NotNull IValueController controller)
        throws DBException
    {
        DBPDataKind dataKind = controller.getValueType().getDataKind();
        switch (controller.getEditType()) {
            case INLINE:
                // Open inline/panel editor
                Object value = controller.getValue();
                if (dataKind == DBPDataKind.ARRAY && !(value instanceof Collection)) {
                    dataKind = DBPDataKind.OBJECT;
                }
                if (dataKind == DBPDataKind.STRING || dataKind == DBPDataKind.NUMERIC || dataKind == DBPDataKind.DATETIME || dataKind == DBPDataKind.BOOLEAN || dataKind == DBPDataKind.OBJECT) {
                    return new StringInlineEditor(controller);
                } else if (value instanceof DBDContentCached &&
                    ContentUtils.isTextValue(((DBDContentCached) value).getCachedValue()))
                {
                    return new ContentInlineEditor(controller);
                } else {
                    return null;
                }
            case PANEL:
                long maxLength = controller.getValueType().getMaxLength();
                if (dataKind == DBPDataKind.NUMERIC || dataKind == DBPDataKind.DATETIME || dataKind == DBPDataKind.BOOLEAN || (maxLength > 0 && maxLength < PLAIN_STRING_MAX_LENGTH)) {
                    return new StringInlineEditor(controller);
                } else {
                    return new ContentPanelEditor(controller);
                }
            case EDITOR:
                if (controller.getExecutionContext().getDataSource().getContainer().getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_SET_STRING_USE_CONTENT_EDITOR)) {
                    return ContentEditor.openEditor(controller);
                } else {
                    return new TextViewDialog(controller);
                }
            default:
                return null;
        }
    }

}