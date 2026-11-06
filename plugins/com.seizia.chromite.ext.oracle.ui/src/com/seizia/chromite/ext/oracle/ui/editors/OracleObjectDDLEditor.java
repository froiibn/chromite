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

package com.seizia.chromite.ext.oracle.ui.editors;

import org.eclipse.jface.action.IContributionManager;
import com.seizia.chromite.ext.oracle.model.OracleTable;
import com.seizia.chromite.ui.editors.sql.SQLSourceViewer;
import org.jkiss.utils.CommonUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * OracleObjectDDLEditor
 */
public class OracleObjectDDLEditor extends SQLSourceViewer<OracleTable> implements OracleDDLOptions{
    private Map<String, Object> oracleDDLOptions = new HashMap<>();

    public OracleObjectDDLEditor()
    {
    }

    @Override
    protected void contributeEditorCommands(IContributionManager contributionManager)
    {
        super.contributeEditorCommands(contributionManager);
        if (getSourceObject() instanceof OracleTable) {
            OracleEditorUtils.addDDLControl(contributionManager, getSourceObject(), this);
        }
    }

    public void putDDLOptions(String name, Object value) {
        oracleDDLOptions.put(name, value);
    }

    @Override
    protected Map<String, Object> getSourceOptions() {
        Map<String, Object> options = super.getSourceOptions();
        if (!CommonUtils.isEmpty(oracleDDLOptions)) {
            options.putAll(oracleDDLOptions);
        }
        return options;
    }
}