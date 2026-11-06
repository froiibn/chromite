/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2016-2016 Karl Griesser (fullref@gmail.com)
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
package com.seizia.chromite.ext.exasol.ui.editors;

import com.seizia.chromite.ext.exasol.editors.ExasolSourceObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.editors.sql.SQLSourceViewer;

public class ExasolSourceViewEditor extends SQLSourceViewer<ExasolSourceObject> {
    public ExasolSourceViewEditor()
    {
    }
    
    @Override
    protected boolean isReadOnly()
    {
        return false;
    }
    
    @Override
    protected void setSourceText(DBRProgressMonitor monitor, String sourceText)
    {
        getInputPropertySource().setPropertyValue(monitor, "objectDefinitionText", sourceText);
    }

}
