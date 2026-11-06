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

package com.seizia.chromite.ext.gaussdb.ui.editors;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.gaussdb.model.GaussDBPackage;
import com.seizia.chromite.ext.postgresql.model.PostgreScriptObject;
import com.seizia.chromite.ext.postgresql.ui.editors.PostgreSourceViewEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class GaussDBPackageBodyViewEditor extends PostgreSourceViewEditor {

    @Override
    protected boolean isReadOnly() {
        return false;
    }

    @Override
    protected void setSourceText(DBRProgressMonitor monitor, String sourceText) {
        getInputPropertySource().setPropertyValue(monitor, "extendedDefinitionText", sourceText);
    }

    @Override
    protected String getSourceText(DBRProgressMonitor monitor) throws DBException {
        PostgreScriptObject object = getSourceObject();
        if (object instanceof GaussDBPackage) {
            GaussDBPackage sourceObject = (GaussDBPackage) object;
            return sourceObject.getExtendedDefinitionText();
        }
        return "";
    }
}
